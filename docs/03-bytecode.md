# D3 · 字节码与指令集 Bytecode

> 前置知识：D0（管线）、D2（AST）。
>
> 本篇拆解 `ir/Bytecode.kt` 与 `ir/Opcode.kt`：编译产物为何用"三条并行 IntArray"表示、常量池如何
> 去重、跳转如何修补、以及 `disasm` 如何反汇编。读完能理解 VM（D5）脚下那层数据结构。

## 1. 为什么是三条并行 IntArray

VM 是栈式字节码机，`Bytecode`（`Bytecode.kt:12`）把"指令流"与"操作数"分离成三条**等长**车道：

```kotlin
12:44:engine/src/main/kotlin/io/kjs/ir/Bytecode.kt
class Bytecode(
    val name: String,
    val paramCount: Int,
    val isArrow: Boolean,
) {
    val code = ArrayList<Int>()            // 车道0: opcode 整数(编译期可变)
    val aOps = ArrayList<Int>()            // 车道1: 操作数 A（多数指令用）
    val bOps = ArrayList<Int>()            // 车道2: 操作数 B（少数指令用）
    lateinit var codeA: IntArray           // freeze 后的只读快照(见 §4)
    lateinit var aOpsA: IntArray
    lateinit var bOpsA: IntArray
    val constants = ArrayList<Any?>()      // 常量池: Double/Boolean/null/Undefined(不含 String)
    val strings = ArrayList<String>()      // 字符串池: 名字/literal
    val functions = ArrayList<Bytecode>()  // 子函数池: MAKE_CLOSURE 取用(见 §4.5)
    var localCount = 0                     // 本函数的局部槽总数(含参数 + 临时)
    var caches: Array<Any?>? = null        // 内联缓存槽(按 pc 索引, D7)
}
```

对比"把每条指令做成对象"的方案（如 `class AddInstr`），并行 IntArray 的好处是：**连续内存、零
对象分配、VM 用 `code[pc++]` 顺序读取无指针跳跃**，且 opcode 是 `Int`，可直接 `OP_VALUES[code[pc]]`
查表（D5 §8）。这正是它快于"对象指令流"的根本。

> 为什么 A/B 两条操作数？多数指令只用一个操作数（如 `LOAD_LOCAL a`），少数（`JMP_IF_FALSE a b`、
> `CALL argc b`）需要两个。`a` 常是"池索引/跳转目标/槽号"，`b` 常是"标志位"（如 `LOAD_GLOBAL`
> 的"容忍未定义"标志）。这种固定 2 操作数布局让 `when` 分支统一读 `a/b`。

## 2. 常量池与去重

`constIdx`/`strIdx`（`Bytecode.kt:42`）把相同常量合并到池里、返回下标；`fnIdx` 把子函数加进
`functions` 池。池下标即字节码里传的 `a`：

```kotlin
42:52:engine/src/main/kotlin/io/kjs/ir/Bytecode.kt
fun constIdx(v: Any?): Int { var i = constants.indexOf(v); if (i < 0) { i = constants.size; constants.add(v) }; return i }
fun strIdx(s: String): Int { var i = strings.indexOf(s); if (i < 0) { i = strings.size; strings.add(s) }; return i }
fun fnIdx(f: Bytecode): Int { var i = functions.indexOf(f); if (i < 0) { i = functions.size; functions.add(f) }; return i }
```

> 注意这里用 `indexOf` 去重：相同字符串/数字只占一个池槽，省内存也利于 IC 形状比较（D7）。

## 3. 发射与跳转修补（emit / patchA / patchB）

编译器一边顺序 `emit`，一边在"跳转目标未知"时打**占位指令**，最后回填：

```kotlin
53:62:engine/src/main/kotlin/io/kjs/ir/Bytecode.kt
fun emit(op: Op, a: Int = 0, b: Int = 0) { code.add(op.ordinal); aOps.add(a); bOps.add(b) }
fun emitNumber(v: Number) = emit(Op.CONST, constIdx(v.toDouble()))
fun emitString(s: String) = emit(Op.LOAD_CONST_STR, strIdx(s))
fun patchA(at: Int, a: Int) { aOps[at] = a }   // 回填操作数 A（如跳转目标）
fun patchB(at: Int, b: Int) { bOps[at] = b }   // 回填操作数 B
```

典型用法（Compiler 的 `compileIf`，D4 §7）：先 `emit(JF, 0)` 占位，编译 then 分支记下 `thenEnd`，
再 `patchA(jfAt, thenEnd)` 让假跳转到 then 之后。**附带指令**的 `at` 由 `code.size-1`（最后发出的
那条）取得——因此补丁点必须"紧跟在 emit 之后立刻记录"，否则 `code.size-1` 指向别处。

## 4. freeze：从可变 List 到不可变快照

编译完需要把可变 `MutableList` 冻结成 `IntArray` 供 VM 高速顺序读：

```kotlin
68:73:engine/src/main/kotlin/io/kjs/ir/Bytecode.kt
fun freeze() {
    codeA = code.toIntArray()
    aOpsA = aOps.toIntArray()
    bOpsA = bOps.toIntArray()
    for (f in functions) if (!f::codeA.isInitialized) f.freeze()   // 递归冻结子函数
}
```

`codeA/aOpsA/bOpsA`（`Bytecode.kt:18`）就是 VM 真正读取的车道（见 D5 §8 的 `OP_VALUES[code[pc]]`）。
`caches` 是**惰性的**（`null` 直到首次 `LOAD_PROP` 才建数组，D5 §10 / D7），故不在 freeze 里初始化。

## 4.5 函数是独立编译单元（为什么主字节码流里看不到子函数）

回到你看到的 `demo-jit-log.js` 那条 trace：顶层 `pc=0 MAKE_CLOSURE a=0` 之后，主字节码流里再也
没有 `square` 的任何指令，也没有 `x`。这不是引擎"丢了"代码，而是**函数是独立编译单元**这一核心模型：

- 每个 JS 函数（含顶层 program 本身）都编译成**一个独立的 `Bytecode` 对象**（D4 §1）。
- 子函数的字节码**不内联进父字节码**，而是放进父 `Bytecode` 的 `functions: ArrayList<Bytecode>` 表
  （`Bytecode.kt:34`）。
- 父字节码流里，子函数只以一条 `MAKE_CLOSURE a=fnIdx` 出现——`a` 是 `functions` 表下标，运行时据此
  取出子 `Bytecode` 造闭包（VM 的 `MAKE_CLOSURE` → 读 `bc.functions[a]`）。

所以 `MAKE_CLOSURE a=0` 的含义是"取第 0 号子函数（即 `square`）造个闭包"。`square` 的真实指令和
局部变量**躺在 `bc.functions[0]` 里，不在顶层 `code` 车道里**。

### 4.5.1 局部变量 `x` 归谁所有

变量名在编译期就被擦成整数槽号（D4 §2），且槽号**只在各自函数的 `locals` 表里有效**：

- `x` 是 `square` 的**形参**，在 `square` 自己的 Bytecode 里占 `locals[0]`。
- `c` 是 `square` 的 `var`，占 `square.locals[1]`，`square.localCount = 2`。
- 顶层字节码流里**根本没有 `x`/`c` 这些名字**，只有 `square` 的引用和几个全局名
  （`square`/`loud`/`console`/`r` 都是 `DECL_GLOBAL`/`LOAD_GLOBAL`）。

一句话：**局部变量是"函数的私有财产"，不会泄露到父字节码流**。要看 `x` 占几号槽，得看 `square` 的
Bytecode，而不是主 Bytecode。

### 4.5.2 子函数的字节码长什么样（以 `square` 为例）

按编译器规则（D4）推导 `function square(x){ var c=x+1; return x*x+c; }` 的字节码：

```
=== square/1 locals=2 ===        // paramCount=1, x→slot0, c→slot1
  pc0  LOAD_LOCAL   0            ; x
  pc1  LOAD_INT     1            ; 1
  pc2  ADD                        ; x+1
  pc3  STORE_LOCAL  1            ; c = x+1
  pc4  LOAD_LOCAL   0            ; x
  pc5  LOAD_LOCAL   0            ; x
  pc6  MUL                        ; x*x
  pc7  LOAD_LOCAL   1            ; c
  pc8  ADD                        ; x*x+c
  pc9  RET                        ; return
```

对比顶层只有 `MAKE_CLOSURE`/调用指令——`square` 的 10 条指令全在 `bc.functions[0]`。这也解释了
`demo-jit-log.js` 的注释"`square` 纯算术 → 能被 JIT"：`square` 内部无任何全局访问/属性读写，JIT 可
整函数类型特化（D8）。

### 4.5.3 怎么看到子函数的真实字节码

调用主 `Bytecode.disasm()` 会**递归打印所有子函数**（`Bytecode.kt:112`：
`for ((i, f) in functions.withIndex()) sb.append("\n-- fn[$i] --\n").append(f.disasm())`），
所以输出里的 `-- fn[0] --` 段就是 `square` 的完整反汇编。VM 的 `Tracer` 进入 `CALL` 后的子 `Frame`
时，也会逐条 trace 子函数的指令——你贴的那段 trace 只列了主 `Frame`，是因为 `CALL square(5)` 直接
把结果 `31.0` 返回并继续主帧，没有展开子帧日志。

## 5. 反汇编（disasm）

`disasm`（`Bytecode.kt:98`）把字节码还原成可读的文本，主要供 `Tracer` 与调试：

```kotlin
98:114:engine/src/main/kotlin/io/kjs/ir/Bytecode.kt
fun disasm(): String {
    val sb = StringBuilder()
    sb.append("=== $name/$paramCount locals=$localCount ===\n")
    for (pc in 0 until code.size) {
        val op = OP_VALUES[code[pc]]
        sb.append(String.format("%4d  %-14s %5d %5d", pc, op.name, aOps[pc], bOps[pc]))
        when (op) {
            Op.LOAD_CONST -> sb.append("   ; ${constants[aOps[pc]]}")
            Op.LOAD_STR, Op.LOAD_GLOBAL, Op.STORE_GLOBAL, Op.DECL_GLOBAL,
            Op.LOAD_PROP, Op.STORE_PROP, Op.DELETE_PROP -> sb.append("   ; \"${strings[aOps[pc]]}\"")
            else -> {}
        }
        sb.append('\n')
    }
    for ((i, f) in functions.withIndex()) sb.append("\n-- fn[$i] --\n").append(f.disasm())
    return sb.toString()
}
```

它顺便把"池下标 → 实际常量/字符串"解析出来，看反汇编就能定位每条指令的语义。编译器在 `compileProgram`
末尾统一 `freeze()` 再交 VM（`Compiler.kt:43` 后由 `Engine.kt:52` 触发）。

## 6. 指令集（Opcode.kt）

`Op`（`Opcode.kt:17`）是全部指令的枚举，VM 的 `when(op)`（D5 §8）逐条实现。按职责分组：

- **常量/加载**：`CONST`（数字）、`LOAD_CONST_STR`、`LOAD_NULL`、`LOAD_TRUE`、`LOAD_FALSE`、
  `LOAD_UNDEFINED`、`LOAD_THIS`、`LOAD_ARGUMENTS`。
- **栈/局部/参数**：`DUP`、`POP`、`LOAD_LOCAL`、`STORE_LOCAL`、`LOAD_ARG`、`STORE_ARG`、
  `LOAD_GLOBAL`、`STORE_GLOBAL`、`DECL_GLOBAL`。
- **属性/下标**：`LOAD_PROP`、`STORE_PROP`、`LOAD_ELEM`、`STORE_ELEM`、`LOAD_PROP_BYVAL`、
  `DEL_PROP`。
- **运算**：`ADD`、`SUB`、`MUL`、`DIV`、`MOD`、`POW`、`NEG`、`INC`、`DEC`；`BITAND/BITOR/BITXOR/
  SHL/SHR/USHR`；`LT/GT/LE/GE/EQ/NEQ/SEQ/SNEQ`；`AND_LOG/OR_LOG`（理论保留，编译器改用 `JF_KEEP`）。
- **构造**：`MAKE_OBJECT`、`MAKE_ARRAY`、`MAKE_FUNCTION`/`MAKE_CLOSURE`、`MAKE_CLASS_INSTANCE`。
- **控制流**：`JMP`、`JT`、`JF`、`JT_KEEP`、`JF_KEEP`、`RET`、`RET_UNDEF`、`HALT`；`PUSH_BLOCK`、
  `POP_BLOCK`（块级作用域边界）。
- **调用/构造**：`CALL`、`CALL_METHOD`、`NEW_OP`、`SPREAD`（展开实参）、`STASH_RESULT`。
- **闭包**：`LOAD_UPVAL`、`STORE_UPVAL`、`MAKE_CLOSURE`。
- **异常**：`THROW`、`TRY_ENTER`、`TRY_EXIT`、`END_FINALLY`。
- **迭代**：`FOR_IN_INIT`、`FOR_IN_NEXT`、`FOR_OF_INIT`、`FOR_OF_NEXT`。

末尾 `OP_VALUES`（`Opcode.kt:104`）是 `{ordinal → Op}` 反向表，VM 用它把 `code[pc]` 的整数瞬间映射
回枚举，零分支成本。

## 7. 设计取舍

- **并行 IntArray 而非对象流**：连续内存 + 整数 opcode 查表，是性能内核；代价是"跳转修补"需显式
  管理（§3）。
- **A/B 双操作数固定布局**：`when` 分支统一读 `a/b`，少数双操作数指令自然复用。
- **常量池去重**：相同字面量只占一槽，省内存且利于 IC 形状比较。
- **caches 惰性**：无属性访问的函数完全不分配 IC 数组。
- **freeze 不可变化**：编译完成后字节码只读，VM 可放心共享，无并发写风险。

## 8. 常见坑

- **补丁点 `code.size-1`**：若 `emit` 后又 `emit`（如补丁前插了别的指令），`code.size-1` 指向错误，
  跳转目标错乱。务必"emit 占位后立即记录 at"。
- **池去重副作用**：`indexOf` 依赖 `equals`，自定义值若未正确实现 `equals` 会重复入池或错配。
- **freeze 前不可执行**：VM 读 `codeA`，未 freeze 时为空。Compiler 必须在 `compileProgram` 末尾
  freeze（编译器内部子函数也各自 freeze）。
- **`caches` 未解冻**：`caches` 是运行时状态，不跟随 `freeze`；同一 `Bytecode` 被多 Frame 并发执行
  时 `caches[pc]` 需线程安全（本 VM 单线程，未加锁）。
