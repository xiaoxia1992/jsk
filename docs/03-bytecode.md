# D3 · 字节码与指令集 Bytecode

> 前置知识：D0（管线）、D2（AST）。
>
> 本篇拆解 `ir/Bytecode.kt` 与 `ir/Opcode.kt`：编译产物为何用"三条并行 IntArray"表示、常量池如何
> 去重、跳转如何修补、以及 `disasm` 如何反汇编。读完能理解 VM（D5）脚下那层数据结构。

## 1. 为什么是三条并行 IntArray

> **小白导读（先看这段）**：字节码就是"给虚拟机看的机器指令清单"。JS 源码被编译后，变成一串很简单的指令（例如"把数字 3 压进栈""把两个数相加"）。KJS 把这些指令存成**三个并排的清单（数组）**：第 1 张只记"每个步骤干啥"（操作类型），第 2、3 张记每个步骤需要的"参数"。三张清单**同一行号对应同一条指令**——这就是"三条并行"的来历。
>
> 为什么这么干？因为一排排整齐的数组，比"每条指令都做成一个对象"跑得更快、更省内存（下面详述）。别被"IntArray""车道"吓到，把它想成 Excel 的三列就够了。别的 VM（如 V8 Ignition、QuickJS）多数用"一条字节流、参数紧跟在操作码后面"的单流写法；KJS 选"三条并行数组"是为了让 `when(op)` 分发更整齐统一，代价是内存比单流略胖一点（详见 §7 设计取舍）。

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

> **小白导读**：`emit` 就是"往清单末尾加一行指令"。麻烦出在 `if` / `for` 这类带跳转的指令——它要跳到的"目标行号"在写它的时候还**不存在**（目标在后面才生成）。所以编译器先填一个**占位**数字（比如 0），等后面知道了真正的行号，再用 `patchA` 把那行的占位改成正确行号。就像写菜谱时先写"转到第 ___ 步"，等后面步骤写好了，再回去把空白填上。`patchB` 同理，只是改第二列的参数（用得少，比如 `try/catch` 同时记 catch 和 finally 两个位置）。

编译器一边顺序 `emit`，一边在"跳转目标未知"时打**占位指令**，最后回填：

```kotlin
53:62:engine/src/main/kotlin/io/kjs/ir/Bytecode.kt
fun emit(op: Op, a: Int = 0, b: Int = 0) { code.add(op.ordinal); aOps.add(a); bOps.add(b) }
fun emitNumber(v: Number) = emit(Op.CONST, constIdx(v.toDouble()))
fun emitString(s: String) = emit(Op.LOAD_STR, strIdx(s))
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

结合 `demo-jit-log.js` 的执行 trace 来理解：顶层 `pc=0 MAKE_CLOSURE a=0` 之后，主字节码流里再也
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
时，也会逐条 trace 子函数的指令。在 `demo-jit-log.js` 的主帧 trace 中，`CALL square(5)` 直接把结果 `31.0` 返回并继续主帧，子帧日志未被展开，因此表面上看不到 `square` 的内部指令。

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

`Op`（`Opcode.kt:17`）是全部指令的枚举（共 70 余条，`< 80 ops`），VM 的 `when(op)`（D5 §8）逐条实现。
所有指令统一为"8 位 opcode + A/B 双操作数"的定宽布局；按职责分组（与源码 `Opcode.kt` 一一对应）：

- **常量/加载**：`LOAD_UNDEF`、`LOAD_NULL`、`LOAD_TRUE`、`LOAD_FALSE`、`LOAD_ZERO`、`LOAD_ONE`、
  `LOAD_INT`(a=字面值)、`LOAD_CONST`(a=常量池下标)、`LOAD_STR`(a=字符串池下标)。
- **局部/参数**：`LOAD_LOCAL`(a=槽，压 `locals[a]`)、`STORE_LOCAL`(a=槽，**写槽、留值不弹**)、`LOAD_ARG`(a=实参下标，压 `args[a]`)、
  `STORE_ARG`(a=实参下标，**写参、留值不弹**)、`LOAD_ARGUMENTS`（按需构造 `arguments` 对象并压栈）。
- **名称解析**：`LOAD_GLOBAL`/`STORE_GLOBAL`(a=名字，STORE 写全局、**留值不弹**)、`DECL_GLOBAL`(a=名字，声明 let/const **并弹初值**)；
  `LOAD_UPVAL`(a=闭包 upvalue 下标，压捕获值)/`STORE_UPVAL`(a=闭包 upvalue 下标，**写捕获、留值不弹**)。
- **属性/下标**：`LOAD_PROP`(a=名字，弹 obj)、`STORE_PROP`(a=名字，栈 `[obj,value]→value`)、
  `LOAD_ELEM`(栈 `[obj,key]→value`)、`STORE_ELEM`(栈 `[obj,key,value]→value`)、
  `DELETE_PROP`(a=名字)、`DELETE_ELEM`(栈 `[obj,key]→bool`)。
- **构造**：`MAKE_OBJECT`(a=键值对数)、`MAKE_ARRAY`(a=元素数)、`MAKE_CLOSURE`(a=functions[] 下标，
  捕获当前环境)。
- **栈操作**：`DUP`、`POP`、`SWAP`。
- **算术/逻辑**：`ADD/SUB/MUL/DIV/MOD/POW`、`NEG/PLUS/NOT/BITNOT/TYPEOF/VOID_OP`、`TO_NUMBER`
  （`++/--` 的强制）、`BITAND/BITOR/BITXOR/SHL/SHR/USHR`、`EQ/NEQ/SEQ/SNEQ/LT/LE/GT/GE`、
  `INSTANCEOF/IN_OP`、`AND_LOG/OR_LOG`（理论保留，编译器实际改用 `JT_KEEP/JF_KEEP`，不进 VM）。
- **控制流**：`JMP`(a=绝对 pc)、`JT`(弹，真跳)、`JF`(弹，假跳)、`JT_KEEP`(`||` 短路：真则保留栈顶
  并跳，否则弹)、`JF_KEEP`(`&&` 短路：假则保留栈顶并跳，否则弹)。
- **调用/构造**：`CALL`(a=argc，栈 `[fn,arg1..argN]→结果`)、`CALL_METHOD`(a=argc，栈
  `[obj,fn,arg1..argN]→结果`，`obj` 作 `this`)、`NEW_OP`(a=argc，栈 `[ctor,arg1..argN]→实例`)、
  `GET_THIS`、`RET`(弹栈顶作返回值)、`RET_UNDEF`。
- **作用域/块**：`PUSH_BLOCK`、`POP_BLOCK`（let/const 块边界；实际仅作栅栏，不回收槽，D4 §3）。
- **异常**：`THROW`、`TRY_ENTER`(a=catch pc, b=finally pc，无则 -1)、`TRY_EXIT`、`END_FINALLY`。
- **迭代**：`FOR_IN_INIT`(弹 obj，压 IterState)、`FOR_IN_NEXT`(a=取完跳转 pc，压 key)、
  `FOR_OF_INIT`(弹 obj，压 ForOfState)、`FOR_OF_NEXT`(a=取完跳转 pc，压 value)。
- **顶层求值**：`STASH_RESULT`(弹栈顶进 `frame.lastResult`)、`HALT`(返回 `lastResult`)。

末尾 `OP_VALUES`（`Opcode.kt:104`）是 `{ordinal → Op}` 反向表，VM 用它把 `code[pc]` 的整数瞬间映射
回枚举，零分支成本。

> 注意：`obj[k]` 这类**动态键**访问走 `LOAD_ELEM`/`STORE_ELEM`（键在栈上），而非虚构的
> `LOAD_PROP_BYVAL`；展开 `f(...arr)` 在编译期降级为 `fn.apply(this, argsArr)`，无专属 opcode（D4 §9、D5 §17）。

## 6.1 逐指令参考表（栈 / 局部变量 / 池 的精确语义）

> **约定**：
> - 表内 `…` 表示栈底不动的部分，右侧为栈顶；`[…, x, y] → […, z]` 表示「执行前 / 执行后」。
> - **KJS 的赋值是一条表达式**：`STORE_*` 系列执行后**把被赋值的值留在栈顶**（VM 用 `peek()` 读取、不 `pop()`），因此独立语句通常要在 `STORE_*` 后紧跟一条 `POP` 把残留清掉（与 08-jit §2.4.3 一致）。`DECL_GLOBAL` 是唯一「弹出初值」的例外。
> - `locals[槽]` 若存的是 `Upvalue` 包装（闭包捕获），读写走 `raw.value`。
> - 算术/比较结果类型：数值运算得 `Double`（或 `BigInteger`），比较得 `Boolean`；`ADD` 任一侧为 `String` 时走字符串拼接。
> - 跳转指令的 `a` 为**绝对 pc**（去 freeze 前由 `patchA` 回填，见 §3）。

### 6.1.1 常量 / 字面量（压栈，无副作用）

| 指令 | A/B | 作用 | 栈变化 | 副作用 |
|---|---|---|---|---|
| `NOP` | — | 空操作 | `[…] → […]` | 无 |
| `LOAD_UNDEF` | — | 压 `undefined` | `[…] → […, undefined]` | 无 |
| `LOAD_NULL` | — | 压 `null` | `[…] → […, null]` | 无 |
| `LOAD_TRUE` | — | 压 `true` | `[…] → […, true]` | 无 |
| `LOAD_FALSE` | — | 压 `false` | `[…] → […, false]` | 无 |
| `LOAD_ZERO` | — | 压 `0.0` | `[…] → […, 0.0]` | 无 |
| `LOAD_ONE` | — | 压 `1.0` | `[…] → […, 1.0]` | 无 |
| `LOAD_INT` | a | 压 32 位整数字面量 `a`（存为 `Double`） | `[…] → […, a.toDouble()]` | 无 |
| `LOAD_CONST` | a | 压 `constants[a]` | `[…] → […, constants[a]]` | 读常量池 |
| `LOAD_STR` | a | 压 `strings[a]` | `[…] → […, strings[a]]` | 读字符串池 |

### 6.1.2 局部变量 / 形参 / 全局 / upvalue

| 指令 | A/B | 作用 | 栈变化 | 副作用 |
|---|---|---|---|---|
| `LOAD_LOCAL` | a | 压 `locals[a]`（解析 `Upvalue`） | `[…] → […, locals[a]]` | 读 locals |
| `STORE_LOCAL` | a | `locals[a] = peek()`（写槽，**留值不弹**） | `[…, v] → […, v]` | 写 locals[a] |
| `LOAD_ARG` | a | 压 `args[a]`，越界压 `undefined` | `[…] → […, args[a]?]` | 读实参数组 |
| `STORE_ARG` | a | `args[a] = peek()`（写参，**留值不弹**） | `[…, v] → […, v]` | 写 args[a] |
| `LOAD_ARGUMENTS` | — | 按需构造 `arguments` 对象并压栈 | `[…] → […, argumentsObj]` | 新建数组（来自 `args`） |
| `LOAD_GLOBAL` | a, b | 压全局变量 `strings[a]`；`b!=0` 时容忍 `undefined` | `[…] → […, global]` | 读全局环境 |
| `STORE_GLOBAL` | a | 写全局 `strings[a] = peek()`（**留值不弹**） | `[…, v] → […, v]` | 写全局环境 |
| `DECL_GLOBAL` | a | 声明 `let/const` 并把**初值弹出**（初值来自栈顶） | `[…, v] → […]` | 注册全局绑定 |
| `LOAD_UPVAL` | a | 压 `upvalues[a].value` | `[…] → […, val]` | 读闭包捕获 |
| `STORE_UPVAL` | a | `upvalues[a].value = peek()`（**留值不弹**） | `[…, v] → […, v]` | 写闭包捕获 |

### 6.1.3 属性 / 元素 / 对象字面量

| 指令 | A/B | 作用 | 栈变化 | 副作用 |
|---|---|---|---|---|
| `LOAD_PROP` | a | 弹 `obj`，压 `obj[strings[a]]` | `[…, obj] → […, value]` | 读属性 |
| `STORE_PROP` | a | 弹 `val`、弹 `obj`，`obj[strings[a]] = val`，**压回 val** | `[…, obj, val] → […, val]` | 写属性 |
| `LOAD_ELEM` | — | 弹 `key`、弹 `obj`，压 `obj[key]` | `[…, obj, key] → […, value]` | 读属性（动态键） |
| `STORE_ELEM` | — | 弹 `val`、弹 `key`、弹 `obj`，`obj[key]=val`，**压回 val** | `[…, obj, key, val] → […, val]` | 写属性（动态键） |
| `DELETE_PROP` | a | 弹 `obj`，压 `delete obj[strings[a]]`（布尔） | `[…, obj] → […, bool]` | 删属性 |
| `DELETE_ELEM` | — | 弹 `key`、弹 `obj`，压 `key in obj ? delete : false` | `[…, obj, key] → […, bool]` | 删属性 |
| `MAKE_OBJECT` | a | 弹 `2a` 个 `(key,val)` 对，压新建 `JsObject` | `[…, k1,v1,…,ka,va] → […, obj]` | 新建对象 |
| `MAKE_ARRAY` | a | 弹 `a` 个元素，压新建 `JsArray` | `[…, e1,…,ea] → […, arr]` | 新建数组 |
| `MAKE_CLOSURE` | a | 以 functions[a] 绑定当前闭包环境，压 `VmClosure` | `[…] → […, closure]` | 新建闭包（捕获 env） |

### 6.1.4 栈操作原语

| 指令 | A/B | 作用 | 栈变化 | 副作用 |
|---|---|---|---|---|
| `DUP` | — | 复制栈顶 | `[…, v] → […, v, v]` | 无 |
| `POP` | — | 弹栈顶 | `[…, v] → […]` | 无 |
| `SWAP` | — | 交换栈顶两元素 | `[…, a, b] → […, b, a]` | 无 |

### 6.1.5 算术 / 一元 / 位运算（二元均弹 2、压 1）

| 指令 | A/B | 作用 | 栈变化 | 副作用 |
|---|---|---|---|---|
| `ADD` | — | 弹 `r,l`；若任一为 `String` 则拼接，否则数值加 | `[…, l, r] → […, result]` | 无 |
| `SUB`/`MUL`/`DIV`/`MOD` | — | 弹 `r,l`，数值运算 | `[…, l, r] → […, result]` | 无 |
| `POW` | — | 弹 `r,l`，求幂 | `[…, l, r] → […, result]` | 无 |
| `NEG` | — | 弹 `v`，压 `-v`（BigInteger 取反） | `[…, v] → […, -v]` | 无 |
| `PLUS` | — | 弹 `v`，压 `toNumber(v)` | `[…, v] → […, num]` | 无 |
| `NOT` | — | 弹 `v`，压 `!toBool(v)` | `[…, v] → […, bool]` | 无 |
| `BITNOT` | — | 弹 `v`，压 `~toInt32(v)`（存 Double） | `[…, v] → […, num]` | 无 |
| `BITAND`/`BITOR`/`BITXOR`/`BITSHL`/`BITSHR`/`BITUSHR` | — | 弹 `r,l`，int32 位运算 | `[…, l, r] → […, num]` | 无 |
| `TYPEOF` | — | 弹 `v`，压类型字符串 | `[…, v] → […, str]` | 无 |
| `VOID_OP` | — | 弹 `v`，压 `undefined`（表达式求值副作用） | `[…, v] → […, undefined]` | 无 |
| `TO_NUMBER` | — | 弹 `v`，压 `toNumber(v)` | `[…, v] → […, num]` | 无 |

### 6.1.6 比较（弹 2、压 1 布尔）

| 指令 | A/B | 作用 | 栈变化 | 副作用 |
|---|---|---|---|---|
| `EQ`/`NEQ` | — | 弹 `r,l`，抽象相等/不等 | `[…, l, r] → […, bool]` | 无 |
| `SEQ`/`SNEQ` | — | 弹 `r,l`，严格相等/不等 | `[…, l, r] → […, bool]` | 无 |
| `LT`/`LE`/`GT`/`GE` | — | 弹 `r,l`，数值/字典序比较 | `[…, l, r] → […, bool]` | 无 |
| `INSTANCEOF` | — | 弹 `ctor`、弹 `obj`，压 `obj instanceof ctor` | `[…, obj, ctor] → […, bool]` | 无 |
| `IN_OP` | — | 弹 `k`、弹 `o`，压 `k in o` | `[…, o, k] → […, bool]` | 无 |

### 6.1.7 控制流 / 跳转（均不改栈深度，除非注明）

| 指令 | A/B | 作用 | 栈变化 | 副作用 |
|---|---|---|---|---|
| `JMP` | a | `pc = a` | `[…] → […]` | 改 pc |
| `JT` | a | 弹 `v`；真则 `pc=a` | `[…, v] → […]` | 改 pc（弹值） |
| `JF` | a | 弹 `v`；假则 `pc=a` | `[…, v] → […]` | 改 pc（弹值） |
| `JT_KEEP` | a | 窥栈顶 `v`；真则 `pc=a` 并**保留 v**，否则弹 `v` 顺序执行 | `[…, v] → […, v]`（跳）或 `[…]`（落） | 改 pc |
| `JF_KEEP` | a | 窥栈顶 `v`；假则 `pc=a` 并**保留 v**，否则弹 `v` 顺序执行 | `[…, v] → […, v]`（跳）或 `[…]`（落） | 改 pc |

### 6.1.8 调用 / 返回 / this

| 指令 | A/B | 作用 | 栈变化 | 副作用 |
|---|---|---|---|---|
| `CALL` | a | 弹 `a` 实参 + 弹 `fn`，进入调用，压返回值 | `[…, fn, a1…aN] → […, result]` | 新建/复用 Frame |
| `CALL_METHOD` | a | 弹 `a` 实参 + 弹 `fn` + 弹 `obj`，`obj.fn(...)`，压返回值 | `[…, obj, fn, a1…aN] → […, result]` | 新建/复用 Frame，`this=obj` |
| `NEW_OP` | a | 弹 `a` 实参 + 弹 `ctor`，构造实例，压实例 | `[…, ctor, a1…aN] → […, instance]` | 新建/复用 Frame |
| `RET` | — | 弹栈顶作为函数结果，结束当前 Frame | `[…, v] →`（出帧） | 结束 Frame |
| `RET_UNDEF` | — | 以 `undefined` 返回，不弹栈 | `[…] →`（出帧） | 结束 Frame |
| `GET_THIS` | — | 压 `thisVal`（函数则为 `closure.thisVal`，否则 `globalObject`） | `[…] → […, this]` | 无 |
| `LOAD_ARGUMENTS` | — | 见 §6.1.2（同条） | — | — |

### 6.1.9 异常

| 指令 | A/B | 作用 | 栈变化 | 副作用 |
|---|---|---|---|---|
| `THROW` | — | 弹 `v`，抛出 `JsThrown(v)` | `[…, v] →`（抛异常） | 中断执行流 |
| `TRY_ENTER` | a, b | 压处理器记录（catch=`a`、finally=`b`、当前 sp） | `[…] → […]` | 写 `exceptionHandlers`（handlerTop+=3） |
| `TRY_EXIT` | — | 弹出处理器记录 | `[…] → […]` | `handlerTop-=3` |
| `END_FINALLY` | — | 若有挂起异常则重抛，否则无操作 | 视挂起状态而定 | 控制异常传播 |

### 6.1.10 迭代（for-in / for-of）

| 指令 | A/B | 作用 | 栈变化 | 副作用 |
|---|---|---|---|---|
| `FOR_IN_INIT` | — | 弹 `obj`，压 key 迭代状态 `IterState` | `[…, obj] → […, state]` | 新建迭代状态 |
| `FOR_IN_NEXT` | a | 窥 `state`；耗尽则 `pc=a`，否则压下一个 key（指针++） | `[…, state] → […, state, key]`（继续）或跳 `a` | 改 pc |
| `FOR_OF_INIT` | — | 弹 `obj`，压 for-of 状态 | `[…, obj] → […, state]` | 新建迭代状态 |
| `FOR_OF_NEXT` | a | 窥 `state`；耗尽则 `pc=a`，否则压下一个 value | `[…, state] → […, state, value]`（继续）或跳 `a` | 改 pc |

### 6.1.11 作用域围栏 / 顶层

| 指令 | A/B | 作用 | 栈变化 | 副作用 |
|---|---|---|---|---|
| `PUSH_BLOCK` / `POP_BLOCK` | — | 作用域围栏（当前实现为 no-op） | `[…] → […]` | 仅语义标记，无栈/环境变化 |
| `STASH_RESULT` | — | 弹栈顶存入 `frame.lastResult`（顶层结果累积） | `[…, v] → […]` | 写 `frame.lastResult` |
| `HALT` | — | 以 `frame.lastResult` 结束顶层执行（不弹栈） | `[…] →`（停机） | 结束 Realm 执行 |

> 注：`AND_LOG`/`OR_LOG` 在 `Opcode.kt` 中保留但 VM 不发出（逻辑与/或走短路跳转 `JT_KEEP`/`JF_KEEP`），不要期望在字节码里出现。

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
