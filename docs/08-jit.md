# D8 · 模板 JIT 编译器

> 前置阅读：D3（字节码）、D4（编译器）、D5（虚拟机）、D6（值模型）、D7（内联缓存）、D9（双后端对拍）。

KJS 有**两个执行后端**：解释器（D5）和本篇的 JIT。两者吃的是同一份 `Bytecode`，跑的是同一套 `JsValues`/`JitBridge` 语义，差别只在"指令怎么被驱动"。本篇回答这些核心问题：

- JIT 到底在做什么、编译成了什么东西？
- 是不是"每个函数都生成一个 Java 类"？类什么时候运行、会不会落盘成 `.class` 文件？
- 生成的代码为什么比解释器快？快在哪几层？
- 生成的 Java 类怎么实现原 JS 逻辑、**凭什么保证语义一致**？
- 还有哪些 JIT 内部结构值得知道（抽象解释、特化、回退、类加载、调优）？

---

## 1. 一句话原理：把 KJS 字节码"翻译"成 JVM 字节码

KJS 的 JIT 是**模板 JIT（template / method JIT）**，不是 tracing JIT，也不是生成 `.java` 源文件再走 `javac`：

- **时机**：运行时（runtime），当某个函数"变热"时。
- **输入**：一份 `Bytecode`（一段已经编译好的 KJS 指令序列，见 D3/D4）。
- **输出**：一份 **JVM `.class` 字节码**，用 ASM 直接拼出来，不经过 `.java` 源文件。
- **手段**：用 `ClassWriter` 把每条 KJS 指令**逐条翻译成一段确定的 JVM 指令**（"模板"），得到一个 `Compiled` 抽象类的子类。

三条核心映射是理解全部内容的关键：

| KJS 概念 | 在 JVM 后端里变成什么 |
|---|---|
| KJS **操作数栈**（`push`/`pop`） | JVM 的**原生操作数栈**（每 `push` 是一条 `dup`/常量入栈，每 `pop` 是栈顶消费） |
| KJS **局部变量槽**（`LOAD_LOCAL`/`STORE_LOCAL`） | JVM 方法的 **local slot**（普通值占 1 槽，double 占 2 槽） |
| KJS **指令 `when(op)` 分发** | **展开成顺序的 JVM 指令**，不再有分发循环 |

关键认知：**KJS 没有"重新实现一套 JS 语义"，而是把语义"委托"给了 `JsValues` / `JitBridge`**。`Compiled.invoke` 里凡是涉及对象、字符串、属性读取、全局变量、相等比较、调用等"复杂"操作，都直接调用和解释器/Walker **完全相同**的 `JitBridge.*` 静态方法（D6/D7）。JIT 真正"自己优化"的，只占总指令里的一小撮——**数值/布尔的算术与比较**（见 §5 的"类型特化"）。

> 为什么不生成 `.java` 再 `javac`？因为那样要经过"写临时文件 → 启动 javac 进程 → 类路径扫描 → 加载"，慢且依赖文件系统。ASM 直接产出 `byte[]` 交给 `ClassLoader.defineClass`，一步到位，且天然在内存里（§4）。

---

## 2. 编译产物长什么样：一个 `Compiled` 子类

### 2.1 一个函数 = 一个生成的类

`compile(closure)`（Jit.kt）为每个**变热的 `Bytecode` 生成且只生成一个类**，它是抽象类 `Compiled`（Compiled.kt）的子类：

```kotlin
abstract class Compiled {
    abstract fun invoke(vm: Vm, realm: Realm, closure: VmClosure,
                        thisVal: Any?, argsArr: Array<Any?>): Any?
}
```

类名形如 `io/kjs/jit/Jit_<safeName>_<id>_a<attempt>`（`<safeName>` 是函数名清洗后的结果，`<id>`/`<attempt>` 是全局计数器）。**每个函数一份、互相独立**。多个闭包若共享同一份 `Bytecode`，仍各自请求编译（见 §11 的线程安全说明），但形态完全一致。

### 2.2 生成类的内存结构

```
class Jit_sum_7_a0 extends Compiled {
    static Object[] CONSTS;     // 由 closure.bc.constants 拷贝而来（LOAD_CONST 直接读这里）
    static Object[] STRINGS;    // 由 closure.bc.strings 拷贝而来（LOAD_STR 直接读这里）

    invoke(vm, realm, closure, thisVal, argsArr): Object {
        // —— prologue：把实参塞进局部槽（见 §2.3）——
        // —— body：翻译后的指令序列，真实 JVM 栈即 KJS 栈 ——
        // —— epilogue：RET 弹出返回值并 ARETURN ——
    }
}
```

要点：

- **`CONSTS` / `STRINGS` 是 `static` 字段**：`LOAD_CONST a` 被翻译成 `GETSTATIC CONSTS; xxxxx; AALOAD`，从类里的常量池直接取，**完全绕过** `bridge.constOf` 桥接调用。这是又一处相对解释器的提速（解释器每次 `LOAD_CONST` 都走 `bridge.constOf` 查池再 `push`）。
- **没有 `Frame` 对象、没有 `Array<Any?>` 操作数栈**：KJS 的 `push` 在生成代码里就是"把值留在 JVM 栈顶"，`pop` 就是"接下来的指令直接消费栈顶"。解释器里那一坨 `frame.stack[sp++]` 索引运算彻底消失。
- **`invoke` 签名与解释器 `execClosureArr` 同形**：都是 `(vm, realm, closure, thisVal, argsArr) → Object`。这就是为什么 JIT 能"无缝替换"解释器（D5 §3）——调用方根本分不清自己调的是解释器还是编译后的类。

### 2.3 prologue：实参 → 局部槽

`emitBody` 在方法开头生成一段**序言**，把 `argsArr` 拷进局部槽。规则与解释器（`Vm.runFrame` 里 `frame.locals[i] = argsArr[i]`）完全一致：

1. `for (i in 0 until paramCount)`：`argsArr[i]` 若**被抽象解释为 DOUBLE**，先 `bridge.toD` 转成原生 double 再 `DSTORE i`；否则 `ASTORE i`。参数不足则补 `UNDEFINED`。
2. 其余局部槽（`var`/`let` 声明）初始化为 `0.0`（double 槽）或 `null`（object 槽）。
3. `GET_THIS`（若有）从局部槽 1 取（`thisVal` 被放在 `argsArr` 的槽 1 位置，见 D5 §3 的 `argsArr[1]=thisVal`）。

> 局部槽布局：参数在前 `0..paramCount-1`，随后是 `var`/`let` 局部（按源码出现顺序分配）。例如 `function sum(n){var s=0; var i=0; for(...){s=s+i} return s}` 的槽位为 **`n`=槽0、`s`=槽1、`i`=槽2**（double 各占 1 槽）。注意 `n` 一定是槽 0，因为解释器就是 `frame.locals[i]=argsArr[i]` 把参数铺在 0 号起。

### 2.4 一个具体例子：`sum`

原始 JS：

```js
function sum(n) {
  var s = 0;
  var i = 0;
  for (var i = 0; i < n; i++) {
    s = s + i;
  }
  return s;
}
```

抽象解释 + 发射后，`Compiled.invoke` 的"伪 JVM 字节码"大致是（栈视图 `[]` 表示空栈）：

```text
; ===== prologue =====
ALOAD  argsArr; ICONST_0; AALOAD; INVOKESTATIC toD → DSTORE 0   ; n  (double)
DCONST_0 → DSTORE 1                                            ; s = 0.0
DCONST_0 → DSTORE 2                                            ; i = 0.0
; ===== loop head (LD_JMP 0) =====
L_head:
DLOAD 2; DLOAD 0; DCMPG; IFGE L_end      ; 栈空：i<n ?  (i>=n 跳到结尾)
; —— body ——
DLOAD 1; DLOAD 2; DADD; DSTORE 1         ; s = s + i
DLOAD 2; DCONST_1; DADD; DSTORE 2        ; i = i + 1
GOTO L_head
; ===== end =====
L_end:
DLOAD 1; ARETURN                        ; return s
```

整段循环里**没有任何对象分配、没有任何数组索引、没有任何分发**，全是原生 `D*`/`GOTO`/`IF*` 指令。对照解释器：解释器每轮循环要走 2 次"读字节码→`when(op)` 分支→访问 `frame.stack`/`frame.locals`"，外加 `toNumber`（如果没被特化）的装箱。

> 这个端到端例子对应 §7 的"抽象解释如何投票出 double"。可以看到 `s`/`i`/`n` 全程 `DSTORE`/`DLOAD`，正是特化生效的结果。

### 2.5 生成类的生命周期

```mermaid
flowchart TD
    BC["Bytecode (一份函数)"] -->|变热: hotness≥阈值| REQ["requestCompile(closure)"]
    REQ -->|提交到 daemon 线程| ASM["ASM 逐指令发射 → byte[]"]
    ASM --> DEF["JitClassLoader.define(name, bytes)"]
    DEF --> CLS["生成类 Jit_... 注册进 JVM"]
    CLS --> PUB["volatile 发布到 closure.compiled"]
    PUB --> RUN["下次调用: already.invoke(...) 直接执行"]
```

---

## 3. 何时运行：变热才编译，下一次调用即走 JIT

### 3.1 触发阈值

`execClosureArr`（D5 §3）里：

```kotlin
val already = closure.compiled
if (already != null) {               // 已经编译好 → 直接走 JIT，绕过 when(op) 分发
    closure.jitCalls++
    return already.invoke(this, realm, c, thisVal, argsArr)
}
closure.jitHits++
if (closure.jitHits >= KJS_JIT_THRESHOLD)   // 默认 3
    requestCompile(closure)
return runFrame(frame)                // 本次仍解释执行
```

即：**前 2 次（默认）解释执行并累计热度，第 3 次触发异步编译请求，编译完成之后的每一次调用都走 `invoke`**。阈值由 `KJS_JIT_THRESHOLD` 环境变量控制（§12）。

### 3.2 异步编译，无停顿

`requestCompile` 把任务交给一个**单线程守护线程池 `compilerPool`**（Kotlin `Executors.newSingleThreadExecutor().asCoroutineDispatcher()`）。编译在后台做，当前调用**立刻回到解释器跑完**，不阻塞。编译完成后通过 `closure.compiled = ...`（Kotlin `volatile` 字段）发布。由于 `volatile` 语义，下一次调用 `execClosureArr` 读到的 `already` 即为新类——**不会有竞态，也不需要锁**。

### 3.3 "运行"就是一次普通的 JVM 方法调用

一旦 `closure.compiled` 非空，调用该函数等于调用一个普通的 JVM 方法 `Compiled.invoke(...)`。这意味着两层 JIT：

1. **KJS JIT**（本篇）：KJS 字节码 → JVM 字节码。
2. **HotSpot C2**（JVM 自带的优化编译器）：当 `invoke` 自己也成为 JVM 热点时，HotSpot 会把它进一步编译成**机器码**，做内联、寄存器分配（double 进 XMM 浮点寄存器）、逃逸分析等（§5）。

所以"JIT 什么时候运行"的答案是：**编译完成的瞬间起，所有对该函数的后续调用都会运行生成的 `invoke`；运行多久、何时被 C2 再编译，由 JVM 自己决定**。

---

## 4. 会落盘成 `.class` 文件吗：不会

明确结论：**KJS 的 JIT 产物只存在于内存，绝不写磁盘。**

```kotlin
private object JitClassLoader : ClassLoader(Jit::class.java.classLoader) {
    fun define(name: String, bytes: ByteArray): Class<*> =
        defineClass(name, bytes, 0, bytes.size)   // 直接吃内存里的 byte[]，从不开文件
}
```

- 没有 `.java`、没有 `.class` 文件、没有 `javac` 调用。`className` 只是 `defineClass` 需要的内部标识。
- 进程退出即丢失，**没有持久化缓存**：下次启动同一段脚本会重新走"变热 → 异步编译"流程。
- HotSpot 自己可能把 `invoke` 再编译成机器码并缓存，但那是 **JVM 的事**，与 KJS 无关，且通常也不落盘（除非开了 `-XX:+PrintCompilation` 之类诊断）。

> 设计取舍：不落盘是为了简单和零外部依赖。代价是"冷启动"每次都要重新 JIT；对该项目的脚本规模（bench 级别）完全可忽略。

---

## 5. 为什么生成的代码更快：六个层面的"消除"

速度来自 JIT **逐层拿掉了解释器的开销**，而不是魔法是"更快的算法"：

1. **去掉指令分发循环**。解释器每条指令：`while(true){ op=code[pc++]; when(op){ ... } }`——一次大 `when`（底层 `tableswitch`）+ 一次 `pc` 推进，每指令都有。JIT 把整个函数**展开成顺序 JVM 指令**，没有循环、没有分支表、没有 `pc` 变量。函数越大、循环越热，收益越大。

2. **操作数栈实体化**。解释器用 `Array<Any?>` + `sp` 索引（`frame.stack[sp++]`），每次 `push`/`pop` 两次数组访问。`Array<Any?>` 是**对象数组**，元素还要装箱。JIT 把栈变成 JVM 原生操作数栈，单条 `dup`/`swap`/消费即完成，且能被 HotSpot 分配进寄存器。

3. **类型特化去装箱（最关键）**。核心热点循环里 `s=s+i` 全程 `DLOAD`/`DADD`/`DSTORE`，double 是 **JVM 原生 64 位浮点**，零堆分配、零 `Double.valueOf`/`doubleValue`。解释器若未特化，每轮都要 `toNumber` → 装箱成 `Double` 对象（§7）。这一项在数值循环里是数量级的差距。

4. **桥接方法内联**。凡是对象/字符串/属性/全局/比较等"复杂"操作都调用 `JitBridge.*`——这些是 **`static`、方法体小、调用单态** 的辅助方法。HotSpot C2 会把它们**内联回** `invoke` 体内，调用开销消失，且内联后更容易做常量传播。

5. **内联缓存（IC）共享且可内联**。`LOAD_PROP` 走和解释器**同一个 `PropIc`**（D7），`LOAD_GLOBAL` 走同一个 `GlobalIc`。单态站点（同一形状的属性访问）命中后稳定，C2 易将其内联为直接字段/原型读取。

6. **常量/字符串走 static 字段 + 真实 JVM 分支**。`LOAD_CONST`→`GETSTATIC CONSTS; AALOAD` 直接取池；`JMP`/`JT`/`JF` 变成 `GOTO`/`IFEQ`/`IFNE`，是 CPU 分支预测器友好的**真实跳转**，而非解释器的"查表 + 跳 `pc`"。

> 量化对比（同一 `sum(1e6)` 循环，参考 `bench-jit.js`/`bench-jit-node.js`）：
> - 解释器：每轮 ≈ 多次 `Array<Any?>` 访问 + `toNumber` 装箱（每轮至少 1 个 `Double` 分配）。
> - JIT：每轮 ≈ 纯 `DADD` + `DCMPG` + `IFGE`/`GOTO`，**不碰堆**。
>
> 注意：未特化路径（字符串拼接 `+`、对象属性运算、`==` 含对象等）仍走 `JitBridge`，速度≈解释器，但仍省掉了第 1、2 层的分发循环与数组栈开销。

---

## 6. 逻辑一致性如何保证：翻译 + 委托，而非重新实现

"生成的 Java 类"并**不是**把 JS 重写成 Java，而是把 JS 语义**保真地映射**到 JVM 指令上。一致性由以下机制层层保证：

### 6.1 每条指令 1:1 直译，语义等价

每个 KJS opcode 有**唯一确定**的 JVM 指令序列（见 D3 指令集与 §7 映射表）。翻译是机械的、不引入新语义。例如 `ADD`：

- 两边都是 double → `DADD`（结果 == `JsValues.toNumber(a)+toNumber(b)`，且因两者本就是 double，`toNumber` 是恒等）。
- 否则 → `JitBridge.add(a, b)`，即解释器 `Op.ADD` 分支调用的同一函数（D5 §7）。

无论走哪条路，**数值结果都来自 `JsValues` 的同一实现**，所以特化路径和装箱路径结果一致。

### 6.2 复杂语义全部委托给 `JsValues` / `JitBridge`

`toNumber`、`toBool`、`looseEq`（松散相等）、`typeof`、`add`（字符串拼接）、`loadProp`、`getGlobal`、`invokeCall` 等，**JIT 与解释器/Walker 调用的是同一份代码**（D6/D9）。JIT 只负责"把操作数摆到正确位置再调它"，不重写规则。

### 6.3 类型特化只改"表示"，不改"结果"

抽象解释投票出 `DOUBLE` 后，double 在 JVM 里是原生 64 位浮点；装箱路径里它是 `Double` 对象。但：

- 原生 double = `toD(value)`，`toD` == `JsValues.toNumber`。
- 数学运算对 double 是逐位一致的 IEEE 754 加法。
- 因此 `DADD` 分支与 `bridge.add` 的 double 分支**数值完全相同**，只是"在栈上是什么形态"不同。

### 6.4 不支持的指令 → 函数整体拒绝，零正确性损失

`canCompile`（Jit.kt）在编译前先把关：只要 `Bytecode` 含**任一** JIT 不支持的 opcode（如 `THROW`/`TRY`/`CATCH`/`ENTRY_HANDLER`/`END_FINALLY`/`MAKE_CLOSURE`/`LOAD_PROP_BYVAL` 风格动态键、含 upvalue、含大常量池等），或函数有 `upvalueInfo`（闭包捕获），**整函数放弃 JIT，永远留在解释器**。这意味着：JIT 只接它 100% 能正确翻译的函数，绝不"半吊子"执行导致语义偏差。

### 6.5 回退机制：特化覆盖不到就全装箱，保证能跑且正确

发射期若抽象栈追踪发现某处 double "埋得太深"无法安全装箱（如 `CALL` 时 double 压在栈第 3 层以下），抛 `JitAbort` → `compile` 捕获后改用**全装箱方案**重编译（`tryCompileBoxed`）：所有值以 `Object` 形态走 `JitBridge`，与解释器逐字等价，只是慢些。若连全装箱都因 JVM 字节码校验失败（`VerifyError`），同样回退到全装箱。两种回退都**保证可运行且语义正确**，只是不再享受特化提速。

### 6.6 异常跨栈帧：JIT 函数不含 try/catch，异常冒泡到解释器帧

JIT 支持的指令集合里**没有**异常处理 opcode（`TRY`/`CATCH`/`ENTRY_HANDLER`/`END_FINALLY`），所以 JIT 编译出的函数体内不可能有 `try/catch`。当 JIT 函数（或其调用的 `JitBridge` 方法）抛出 `JsThrown`（D5 §14）时：

- 该异常是 `RuntimeException` 子类，**直接沿 JVM 调用栈 unwind**，穿过所有中间的 JIT 帧（每个 `invoke` 是一层 JVM 栈帧，深度与 JS 调用深度一一对应）。
- 它一直冒泡到**最近的"解释器帧"**——即某个仍在 `runLoop` 里、带有 `try { when(op) } catch (JsThrown)` 的帧。
- 由该解释器帧按自己的 `handlers` 栈执行 `catch`/`finally`（D5 §14）。

因为 JS 的调用深度本就用 Kotlin 递归栈表示（D5），JIT 帧和解释器帧在 JVM 栈上**无缝衔接**，异常处理的语义（栈展开、finally 执行、返回值/异常续传）与纯解释器执行**完全一致**。这是"双后端对拍"（D9）能兜住一切一致性的根本原因：JIT 只是 VM 后端的"另一种形态"，共享同一套值模型。

---

## 7. 类型特化详解：抽象解释 + 发射期抽象栈

（本章是速度快慢的核心，展开讲透。）

### 7.1 为什么需要"预测类型"

JIT 必须在**不真正运行**函数的情况下，猜出每个栈位置在每条指令后是什么类型，才能决定发 `DADD`（原生 double）还是 `bridge.add`（装箱）。这个"猜"分两步：

1. **抽象解释（compile 期）**：在 `inferDoubleLocals` 里跑一遍"影子执行"，对每个局部变量槽投票"是否为 double"。
2. **发射期抽象栈（emitBody 期）**：维护一个 `ArrayDeque<T>`，实时追踪"当前 JVM 操作数栈顶是 DOUBLE / BOOL / ANY"，据此给每条算术指令选具体模板。

### 7.2 第一步：局部变量的抽象解释（投票机制）

`inferDoubleLocals` 沿控制流正向扫描，对每个 `var`/`let` 局部维护一个 `DoubleVote`：

- `UNKNOWN` → `LIKELY` → `YES`：只要命中"被赋常量数字 / 被 numeric 算术结果赋值 / 被另一 YES 局部赋值"等正向证据就往上投；一旦命中"被赋对象/字符串/调用结果/参数回退（参数无法静态确定类型）"等反向证据，立刻降到 `MAYBE`（不可信）。
- **单调性**：正向证据只升不降（到 `YES` 后不再因别的分支回退），保证**只要投出 `YES` 就一定是安全的**——最坏情况是"本可特化却没特化"，绝不"错误地特化"。
- **分支重置**：遇到 `JMP` 目标或 `JT`/`JF` 的任一出口，该局部在该分支点的抽象类型被重置为 `UNKNOWN`（保守），防止"只在某分支是 double"被错误推广到汇合点。
- 只有最终 `YES` 的局部，发射期才用原生 double 槽（`DSTORE`/`DLOAD`）。

> 产物：`doubleLocals` 集合（如 `{n, s, i}`），供发射期把对应 `LOAD_LOCAL`/`STORE_LOCAL` 直接翻译成 `DLOAD n`/`DSTORE n`，免去 `toD` 转换。

### 7.3 第二步：发射期抽象栈（`ArrayDeque<T>`）

`emitBody` 维护 `aStack: ArrayDeque<T>`，逐指令更新栈顶类型：

- `LOAD_INT 0` → 压 `DOUBLE`（DCONST_0）
- 已知 `YES` 的 `LOAD_LOCAL` → 压 `DOUBLE`（DLOAD n）
- `STORE_LOCAL`（double 槽）→ 弹 `DOUBLE`
- `ADD`：若栈顶两个都是 `DOUBLE` → `DADD`；否则 → `boxTopTwoForCall` 后调 `bridge.add`
- `LT`：若两个 `DOUBLE` → `DCMPG` + `IFxx` 得到 `BOOL`；否则 `bridge.lt`

`T` 只有三种：`DOUBLE`（原生双精度）、`BOOL`（原生 `Z`）、`ANY`（装箱 `Object`）。栈深度随 `push`/`pop`（`addLast`/`removeLast`）增减，与 KJS 操作数栈 1:1 对应。

### 7.4 端到端：数值循环全程不碰堆

`sum` 例子中 `inferDoubleLocals` 投出 `n/s/i` 均为 `DOUBLE`，于是：

```mermaid
flowchart TD
    A["LOAD_INT 0 (i=0)"] --> B["DCONST_0 · 栈[D]"]
    B --> C["STORE_LOCAL 2 (i) · DSTORE 2"]
    C --> D["loop: DLOAD 2; DLOAD 0; DCMPG; IFGE end"]
    D -->|"i<n"| E["s=s+i: DLOAD 1; DLOAD 2; DADD; DSTORE 1"]
    E --> F["i++: DLOAD 2; DCONST_1; DADD; DSTORE 2"]
    F --> D
    D -->|"i>=n"| G["return s: DLOAD 1; ARETURN"]
```

整段循环里只有 `D*` 与 `GOTO`/`IFGE`——**全程不碰堆、不装箱、不查池**，就是 §5 第 3 点"去装箱"的来源。

---

## 8. 属性 / 全局 / 调用 桥（与 D7 的关系）

JIT 不支持"动态键属性读取"和闭包，但支持最常见的 `obj.prop` 与全局变量，机制与解释器共享：

- **`LOAD_PROP a`**：
  ```kotlin
  val ic = closure.bc.caches[bc.pc] as PropIc   // 与解释器同一把 IC
  m.visitVarInsn(ALOAD, 3); m.visitFieldInsn(GETSTATIC, ..., "caches", ...)
  m.visitMethodInsn(INVOKESTATIC, BRIDGE, "readProp", "(LPropIc;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false)
  ```
  走 `JitBridge.readProp`，`.readProp` 再委托 `ShapeCache.getShape` + `shape.loadSlot`（D7）。**首次走 `slow`，命中后走 `fast` 直接取槽**——和解释器同一 IC，所以"形状稳定后快"的特性在 JIT 里同样成立。
- **`LOAD_GLOBAL a`**：`bridge.getGlobal` + 同一把 `GlobalIc`（`c.globalIc`）。JIT 用 `GETSTATIC <className>; GLOBAL_IC` 取 IC 句柄，保证与解释器引用同一个 IC 实例。
- **`CALL a`**：把栈顶 `argc+1` 个操作数（`callee` + 参数）整理成 `Object[]`（若其中有 double，需 `boxTop` 成 `Double`），然后 `bridge.invokeCall2(args, callee, vm)` → `vm.invokeFast` → 递归回 `execClosureArr`。若被调函数也已 JIT，则直接进它的 `invoke`，形成**嵌套的 JVM 调用栈**（见 §6.6 异常传播）。

> 约束：CALL 时若 double 出现在栈第 3 层及以下（"埋太深"），`emitCALL` 抛 `JitAbort` → 全装箱重编译（§6.5）。

---

## 9. 编译两阶段：特化优先 + 失败回退

`compile`（Jit.kt）流程：

```kotlin
fun compile(closure): Compiled? {
    val bc = closure.bc
    if (canCompile(bc)) {                       // 前置把关：无不支持 opcode / 无 upvalue / 池不大
        try { return tryCompile(closure, bc, spec = true) }   // 优先特化方案
        catch (ab: JitAbort) { /* fall through */ }
    }
    return tryCompileBoxed(closure, bc)         // 全装箱方案（与解释器逐字等价）
}
```

- `tryCompile(spec=true)`：用抽象解释结果 + 发射期抽象栈，尽可能发原生模板；遇到覆盖不到的特化场景抛 `JitAbort`。
- `tryCompileBoxed`：所有值以 `Object` 走 `JitBridge`，无原生 double 优化，但**任何函数都能编译且语义与解释器一致**。
- `VerifyError`：字节码校验失败（极少见，通常是栈类型推导边角 case）在 `define` 时抛出，同样回退到 `tryCompileBoxed`。

这一设计保证：**能特化的尽量特化（快），特化不了的至少还能跑（正确）**，绝不会因为"想快"而牺牲正确性。

---

## 10. JitBridge：JIT 与值模型之间的薄适配层

`JitBridge` 是一组 `static` 方法，是生成代码与 `JsValues`（D6）之间的唯一桥梁：

- 数值/字符串/布尔运算：`add`/`sub`/`mul`/`div`/`mod`/`neg`/`lt`/`le`/`gt`/`ge`/`looseEq`/`strictEq`/`toNumber`/`toBool` —— 内部全部委托 `JsValues.*`。
- 属性/全局：`readProp`/`writeProp`/`getGlobal`/`setGlobal` —— 复用 `ShapeCache`/`GlobalIc`（D7）。
- 调用/构造：`invokeCall2`/`invokeNew` —— 委托 `Vm.invokeFast`/`newOperator`。
- 池访问：`constOf`/`strOf` —— 仅在未特化路径或常量池过大时用到。

因为 JIT 与解释器都只通过这一层触碰值语义，**双后端不可能出现"同一段 JS 两种结果"**（否则对拍 D9 立刻红）。

---

## 11. 生成类的类加载与线程安全

- **类加载器**：`JitClassLoader` 是 `child-first` 的单例 `object`，父加载器是 `Jit` 自己的类加载器。生成的类**永不卸载**（单例持有），进程内类数量有界（受"变热函数数"限制），无内存泄漏风险。
- **发布安全**：编译产物通过 `closure.compiled = c`（Kotlin `volatile`）发布。`requestCompile` 在后台线程编译，`execClosureArr` 在前台读——`volatile` 保证"写完成对读可见"，且生成的类在被赋值前必然已 `defineClass` 成功，故不会出现"半初始化类被调用"。
- **并发编译**：`compilerPool` 是单线程，同一时刻只有一个函数在编译；不同闭包并发变热时排队编译，互不干扰。
- **类名唯一性**：全局 `classCounter` + `attempt` 计数保证即便同一函数被多次请求，类名也不冲突。

---

## 12. 调优与环境变量

| 变量 | 默认 | 作用 |
|---|---|---|
| `KJS_JIT` | `true`（非 `false` 即开） | 总开关。设 `false` 禁用 JIT，全部走解释器。 |
| `KJS_JIT_THRESHOLD` | `3` | 热度阈值。`execClosureArr` 累计调用达到该值触发编译请求。调低（如 `1`）可更快 JIT，调高减少编译开销。 |
| `KJS_JIT_SPEC` | `true` | 是否允许特化方案（原生 double）。设 `false` 强制全装箱（用于调试/对拍一致性）。 |
| `KJS_JIT_ASYNC` | `true` | 是否异步编译。设 `false` 则编译阻塞在当前调用（便于调试，但会拖慢首次热调用）。 |
| `KJS_JIT_VERBOSE` | `false` | 打印每次编译的函数名、是否特化、是否回退。 |
| `KJS_JIT_LOG` | （空） | 若设置，将 JIT 统计写入该文件。 |

> 调试技巧：开 `KJS_JIT_VERBOSE` 看哪些函数被 JIT、是否回退；若怀疑 JIT 与解释器不一致，设 `KJS_JIT=false` 跑同一脚本，结果应与 JIT 路径完全相同（D9 对拍保证）。

---

## 13. 设计取舍（为什么这么设计）

- **模板 JIT 而非 tracing JIT**：实现简单、单遍翻译、可静态分析类型；代价是对"动态形状"的函数优化不如 tracing。对该引擎的脚本规模足够。
- **只特化数值/布尔，其余委托桥**：把"最难保证正确"的语义（相等、属性、this、调用）交给已验证的 `JsValues`/`JitBridge`，JIT 只优化最易证明安全、收益最大的一块。
- **不支持就整体放弃**：宁可少优化，也不引入"半正确"路径。`canCompile` 的保守把关是正确性的第一道防线。
- **内存编译、不落盘**：零外部依赖、零 IO；代价是无持久化缓存、冷启动重编译（可忽略）。
- **与解释器共享调用入口**：`execClosureArr` 一处分流，调用方无感，便于"双后端对拍"（D9）和灰度开关（`KJS_JIT`）。

---

## 14. 常见坑（实现与调试）

- **`loop` 是 Mermaid 保留字**：写文档的 sequenceDiagram 时不能用 `loop` 作参与者别名（会触发 `Expecting 'ACTOR', got 'loop'`）。本文图均用 `flowchart` 规避。
- **double 占 2 个 JVM 局部槽**：`inferDoubleLocals` 投出 `YES` 的局部用 `DSTORE n`/`DLOAD n`；若某局部被投为 `YES` 但运行时偶尔是对象，会**类型错误**——靠 §7.2 的单调性+分支重置保证不误投。
- **`CALL` 的 cat2 限制**：double 是 `category-2`，`SWAP` 只能交换栈顶两个 `category-1`。若 double 出现在参数区第 3 层以下，`emitCALL` 主动 `JitAbort` 回退全装箱，避免生成非法字节码。
- **抽象栈必须与真实 JVM 栈同步**：`aStack` 的 `push`/`pop` 必须和发射的 `DUP`/`SWAP`/消费严格对应，否则 `VerifyError`；所有 `boxTop`/`SWAP` 序列都同步更新 `aStack`，否则后续指令会误判类型。
- **`VerifyError` 必须回退**：JVM 在 `defineClass` 时做字节码校验，栈类型不匹配会抛 `VerifyError`；`compile` 捕获后走全装箱重编译，确保总能跑。
- **线程安全**：编译在 `compilerPool` 单线程，`closure.compiled` 用 `volatile` 发布，避免"读到半初始化类"。

---

## 15. 与全局的关系

- 输入来自 **D4（编译器）** 的 `Bytecode`；执行语义来自 **D5（虚拟机）** 的 `execClosureArr`/`invokeFast` 与 **D6（值模型）** 的 `JsValues`。
- 性能来源与 **D3（字节码）** 的指令设计强相关：指令越规整、局部性越好，特化命中率越高。
- 正确性由 **D7（内联缓存）** 的共享 IC 与 **D9（双后端对拍）** 的同结果断言共同兜底。
- 想看"解释器 vs JIT vs Node"的真实差距，直接跑 `bench-jit.js` / `bench-jit-node.js` / `bench.js`。
