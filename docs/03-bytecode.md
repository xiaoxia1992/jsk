# D3 · 字节码与指令集

> 前置知识：D1、D2。本篇讲"AST 编译成的产物长什么样"，这是理解 VM（D5）和 JIT（D8）的基础。

## 1. 三条并行 IntArray 车道

KJS 字节码最反直觉也最关键的设计：**不用 opcode 对象数组，而用三个并行的 `IntArray`**。
`Bytecode`（`ir/Bytecode.kt:11`）字段：

```kotlin
11:40:engine/src/main/kotlin/io/kjs/ir/Bytecode.kt
class Bytecode(val sourceName: String) {
    var codeA = IntArray(64)      // opcode（见 Opcode 枚举的 ordinal）
    var aOpsA = IntArray(64)      // 操作数 A
    var bOpsA = IntArray(64)      // 操作数 B
    var countA = 0                // 已写指令数
    // 常量 / 字符串 / 函数池
    val constants = mutableListOf<Any?>()
    val strings = mutableListOf<String>()
    val functions = mutableListOf<Bytecode>()
    // 内联缓存槽（LOAD_PROP/LOAD_GLOBAL 用）
    var caches = mutableListOf<PropIc>()
    val nameToIdx = mutableMapOf<String, Int>()
    var arity = 0
    var name: String? = null
    ...
}
```

为什么这么做？

- **缓存友好**：VM 主循环（`Vm.kt`）顺序读 `codeA[i]`，三个数组在内存里是连续的 `int`，
  预取器能一路读下去；若用 `Op(op,a,b)` 对象数组，每个对象散落堆上，缓存命中率差、GC 压力高。
- **零对象分配**：指令本身不占对象，编译期只 `emit(op,a,b)` 往数组填数（见下）。
- **可直接喂 JIT**：ASM 生成代码时也按 `codeA/aOpsA/bOpsA` 顺序读，和 VM 同构（D8）。

> 数组初始 64，写满时 `grow()` 翻倍。大多数函数远小于 64 条，几乎不触发扩容。

## 2. 发射与冻结

```kotlin
42:60:engine/src/main/kotlin/io/kjs/ir/Bytecode.kt
fun emit(op: Opcode, a: Int = 0, b: Int = 0): Int {
    ensureCapacity(countA + 1)
    codeA[countA] = op.ordinal   // 用枚举 ordinal 当紧凑 int
    aOpsA[countA] = a
    bOpsA[countA] = b
    val here = countA
    countA++
    return here                   // 返回该指令下标，供跳转修补
}
fun freeze() {
    codeA = codeA.copyOf(countA)  // 裁掉多余容量
    aOpsA = aOpsA.copyOf(countA)
    bOpsA = bOpsA.copyOf(countA)
}
```

`emit` 返回写入位置的 `here`，这是**跳转修补**的关键（D4 的 if/while/for）。

## 3. 常量 / 字符串 / 函数池

- `constants`：数字、BigInt、正则等直接值。
- `strings`：标识符、字符串字面量（用 `addString` 去重，返回下标 `b` 操作数）。
- `functions`：嵌套函数/箭头/closures 的**子 Bytecode**，递归编译；`MAKE_CLOSURE` 的 `b`
  指向这里的下标。
- `nameToIdx`：命名函数表（供 `function f(){}` 调用自身 / 堆栈回溯）。
- `arity`：形参个数（用于 `arguments` 长度与 rest 参数）。

## 4. 指令集（Opcode.kt 节选）

```mermaid
flowchart LR
    subgraph 加载/存储
      L0[LOAD_NULL/TRUE/FALSE/UNDEFINED]
      L1[LOAD_CONST a] L2[LOAD_STR b] L3[LOAD_LOCAL a]
      L4[STORE_LOCAL a] L5[LOAD_PROP a] L6[STORE_PROP a]
      L7[LOAD_GLOBAL b] L8[STORE_GLOBAL b]
      L9[LOAD_THIS] L10[MAKE_CLOSURE b a]
    end
    subgraph 运算
      O1[ADD SUB MUL DIV ...]
      O2[EQ NEQ STRICT_EQ ...]
      O3[NOT NEG TYPEOF ...]
    end
    subgraph 控制流
      C1[JMP b] C2[JMP_IF_FALSE b] C3[JMP_IF_TRUE b]
      C4[CALL a b] C5[CALL_METHOD a b c] C6[NEW a b]
      C7[RETURN] C8[RETURN_UNDEF]
    end
    subgraph 结构化
      S1[PUSH_SCOPE/POP_SCOPE]
      S2[TRY_PUSH/TRY_POP]
      S3[ITER_INIT/ITER_NEXT/ITER_JUMP]
      S4[THROW]
    end
```

按用途分类（`ir/Opcode.kt`）：

| 类 | 代表 opcode | a / b 含义 |
|----|------------|-----------|
| 常量/字面 | `LOAD_CONST a`、`LOAD_STR b`、`LOAD_NULL` | `a`=常量池下标，`b`=字符串池下标 |
| 局部变量 | `LOAD_LOCAL a`、`STORE_LOCAL a`、`LOAD_THIS` | `a`=槽位 |
| 属性 | `LOAD_PROP a`、`STORE_PROP a` | `a`=IC 槽下标（见缓存） |
| 全局 | `LOAD_GLOBAL b`、`STORE_GLOBAL b` | `b`=字符串池里的名字（含 IC） |
| 闭包 | `MAKE_CLOSURE b a` | `b`=functions 池，`a`=upvalue 个数 |
| 运算 | `ADD/SUB/MUL/DIV/EQ/NEQ/TYPEOF/NOT/…` | 多从操作数栈取 |
| 控制流 | `JMP b`、`JMP_IF_FALSE b`、`CALL a b`、`NEW a b` | `b`=跳转目标指令下标/`a b`=参数个数 |
| 异常 | `TRY_PUSH h`、`TRY_POP`、`THROW` | `h`=handler 区起始 |
| 迭代 | `ITER_INIT`、`ITER_NEXT b`、`ITER_JUMP b` | for-in/for-of |
| 作用域 | `PUSH_SCOPE`、`POP_SCOPE` | `with`/块级作用域 |
| 返回 | `RETURN`、`RETURN_UNDEF` | 弹出栈顶为返回值 |

> 注意 `LOAD_PROP` 的 `a` 与 `LOAD_GLOBAL` 的 `b` 并不直接是属性名下标，而是 **IC 槽下标**——
> 真正的名字在 `caches[a]`（或更早的 stages）里缓存。这是 D7 的核心。

## 5. 反汇编 `disasm()`

`Bytecode.disasm()`（`Bytecode.kt:70`）把三条车道翻译成可读文本，是 debug 与 `--trace` 的命脉：

```text
0  LOAD_STR        b=0        ; "x"
2  LOAD_CONST      a=1        ; 1
4  ADD
5  STORE_LOCAL     a=0
7  RETURN_UNDEF
```

配合 `Tracer.onBytecode` 在 `Engine.eval` 里打印，你能直接看到每段源码编译成什么。

## 6. 与 VM 的契约

VM 主循环（`Vm.kt`）就是：

```kotlin
while (pc < countA) {
    val op = Opcode.fromOrdinal(codeA[pc])
    when (op) { ... aOpsA[pc] ... bOpsA[pc] ... pc++ }
}
```

`Opcode.fromOrdinal` 是 `entries[ordinal]`——一个数组下标，零成本。

## 7. 设计取舍

- **为什么用 `IntArray` 而非 `ByteArray`？** 操作数可能超过 255（常量池、函数池、跳转距离），
  直接 `int` 省去编解码。
- **为什么 opcode 用 ordinal 而非字符串？** `when(op)` 编译成 JVM `tableswitch`，比字符串比较快得多。
- **为什么常量池独立于代码？** 共享同一份字符串/数字，避免每条指令都内嵌大对象；JIT 期也能复用。

## 8. 常见坑

- **跳转目标越界**：`emit` 返回的 `here` 是写入时下标，跳转修补时要回填 `bOpsA[here]`（D4）。
  若漏补，VM 会跳到 `0` 或越界 → 死循环 / 崩溃。
- **`countA` 必须在 `freeze` 前正确**：`freeze` 裁数组副本，之后任何 `emit` 都会写到旧长度之外。
- **IC 槽需在 emit 时分配**：`LOAD_PROP` 的 `a` 必须在编译期通过 `bc.addPropCache()` 预留，
  否则运行时 IC 数组下标错乱（D7）。
