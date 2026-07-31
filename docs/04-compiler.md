# D4 · 编译器 Compiler（AST → Bytecode）

> **白话导读（给第一次读的人）**：编译器干一件事——把"语法树"翻译成"字节码清单"。四个核心概念最该先懂：①变量名被换成"第几号格子"(槽)；②闭包=内层函数记住外层变量，实现成一个"共享盒子"；③提升=进函数先把所有 var/函数声明登记好，所以能"先调用后声明"；④跳转修补=if/for 的跳转目标还不知道时先占坑、知道了再填。后面逐节带源码深讲。

> 前置知识：D2（AST）、D3（字节码）。
>
> 本篇拆解 `ir/Compiler.kt`：如何把 AST 编译成 `Bytecode`。核心是四件事——**作用域/槽位分配、
> Upvalue 闭包解析、变量提升、跳转修补**，外加 class 解语法糖。读完能理解"结构化的 AST 如何变成
> VM（D5）可直接执行的扁平指令流"。

## 1. 编译器结构：每个函数一个 Compiler

`Compiler`（`Compiler.kt:23`）不全局单例，而是**每个函数一次 new**（含顶层 program 作为无名函数）：

```kotlin
23:39:engine/src/main/kotlin/io/kjs/ir/Compiler.kt
class Compiler private constructor(
    private val parent: Compiler?,         // 外层函数编译器（构成作用域链）
    val bytecode: Bytecode,                // 本函数的字节码产物
) {
    /** 词法作用域链：每个块/函数一层 Scope。 */
    private class Scope(val parent: Scope?, val isFunction: Boolean) {
        val locals = LinkedHashMap<String, Int>()   // 本作用域内: 名字 → 槽号
        val consts = HashSet<String>()              // 本作用域内被声明为 const 的名字
    }
    private var scope: Scope = Scope(null, isFunction = true)  // 当前作用域(链头)
    private var nextSlot = 0                          // 当前函数下一个可用槽号(全函数共享)
    val upvalues = ArrayList<Upvalue>()               // 本函数捕获的外层变量清单
```

要点：
- **`parent` 链**：内层 `Compiler` 持外层 `Compiler` 引用，构成"函数嵌套作用域链"，用于闭包与 upvalue 解析（§3）。
- **`Scope` 是链表而非栈**：每个块 `enterBlock()` 时 new 一个 `Scope` 挂到 `scope.parent`，`leaveBlock()` 回到 parent。函数体本身是一层 `isFunction=true` 的 Scope。`Scope` 里只有 `locals`（名字→槽）和 `consts`，**没有** `nextSlot` 字段。
- **`nextSlot` 是 Compiler 级计数器**：整个函数（含所有块）共用一个 `nextSlot`，新局部变量/临时槽都从这里 `++` 拿走槽号（§2）。
- **`upvalues`**：本函数需从外层捕获的变量清单，`Upvalue(name, parentIsLocal, parentIndex)` 记录每个捕获的来源。

`compileProgram`（`Compiler.kt:43`）是入口：hoist → 编译顶层语句 → `HALT` → `freeze` → 返回 bytecode。
`compileFunction`（`Compiler.kt:158`）对每个函数创建子 `Compiler`，预留参数槽并编译函数体。

## 2. 作用域与槽位分配（核心原理一）

> **小白讲解**：**槽位（slot）** = 给局部变量编的"第几号格子"。JS 里变量名是给人看的，机器只认编号。编译器扫描函数体，把 `var/let/参数` 依次分配到 `locals[0]`、`locals[1]`……，后面生成的字节码里就只写"读第 0 号格子、写第 1 号格子"。这样栈式虚拟机不用记名字、直接按编号存取，又快又省。

局部变量不按名字引用，而是**编译期分配一个整数"槽号"**，运行时就是 `Frame.locals[槽]`（D5 §5）。
本小节系统阐述局部变量的识别方式、编译期与运行期各自的处理、槽位是否会复用与如何申请，以及局部变量表大小如何确定。

### 2.1 什么叫局部变量，怎么识别

在编译期，凡是"发生在函数体内（非顶层）的声明"——`var` / `let` / `const` 声明、函数声明、形参、循环变量（`for (let i …)`）、`catch` 参数、解构临时变量、类的 `super` 引用变量——都会被编译器识别为局部变量并分配槽号。识别动作统一落在 `declareLocal(name)`：

```kotlin
67:76:engine/src/main/kotlin/io/kjs/ir/Compiler.kt
private fun declareLocal(name: String, isConst: Boolean = false): Int {
    val slot = nextSlot++              // 拿走当前 nextSlot 作为槽号, 计数器 +1
    scope.locals[name] = slot          // 记入"当前作用域"的名字 → 槽映射
    if (isConst) scope.consts.add(name)
    return slot
}
```

识别时机分布在编译的不同阶段：
- **提升扫描 `hoistVarAndFunctions`**（`Compiler.kt:107`）：进入函数体前先扫 `var` 与函数声明，给它们 `declareLocal` 分配槽（JS 的 var/函数提升，见 §4）。
- **`compileFunction` 预填参数**（`Compiler.kt:165`）：每个形参 `c.declareLocal(p.name)` 占用槽 `0..paramCount-1`。
- **`compileVarDecl`（let/const）**（`Compiler.kt:279`）：遇到 `let/const` 声明时 `declareLocal(name, isConst=…)`。
- **块 / 循环 / catch**：`enterBlock()` 后块内 `let/const` 在 `compileVarDecl` 分配；`compileForIn/ForOf` 对循环变量 `declareLocal`；`compileTry` 对 catch 参数 `declareLocal`；解构走 `declareScratchLocal()` 匿名槽。
- **class**：`compileClass` 对 `super` 引用 `declareLocal(superVarName)`；`emitBuildArgsArray` 对展开参数用 `declareScratchLocal()`。

### 2.2 编译时怎么处理：名字被擦除，只剩整数

声明一旦 `declareLocal`，名字只存在于编译期的 `scope.locals` 表。此后所有对该名字的**读写**，都通过 `resolveLocal` 把名字查回槽号，再发射带整数操作数的指令：

```kotlin
78:87:engine/src/main/kotlin/io/kjs/ir/Compiler.kt
private fun resolveLocal(name: String): Int? {
    var s: Scope? = scope
    while (s != null) {
        val v = s.locals[name]
        if (v != null) return v            // 从内向外找到最近的声明层
        if (s.isFunction) return null       // 越过函数作用域边界即停(不跨函数)
        s = s.parent
    }
    return null
}
```

`emitLoadIdent`（`Compiler.kt:931`）据此决定发射哪条指令——**名字在编译期就消失了，字节码里只有整数槽号**：

```kotlin
931:938:engine/src/main/kotlin/io/kjs/ir/Compiler.kt
private fun emitLoadIdent(name: String, line: Int) {
    val slot = resolveLocal(name)
    if (slot != null) { bytecode.emit(Op.LOAD_LOCAL, slot, 0, line); return }   // 局部 → 整数槽
    val up = resolveUpvalue(name)
    if (up != null) { bytecode.emit(Op.LOAD_UPVAL, up, 0, line); return }        // 闭包捕获
    if (name == "arguments") { bytecode.emit(Op.LOAD_ARGUMENTS); return }
    bytecode.emit(Op.LOAD_GLOBAL, bytecode.strIdx(name), 0, line)                 // 否则全局
}
```

### 2.3 运行时怎么处理：整数槽 → 数组下标

字节码里的 `LOAD_LOCAL slot` / `STORE_LOCAL slot`，在 VM 直接变成数组下标访问（`Vm.kt:245`）：

```kotlin
245:253:engine/src/main/kotlin/io/kjs/vm/Vm.kt
Op.LOAD_LOCAL -> { val raw = f.locals[a]; f.push(if (raw is Upvalue) raw.value else (raw ?: JsValues.UNDEFINED)) }
Op.STORE_LOCAL -> { val raw = f.locals[a]; val v = f.peek(); if (raw is Upvalue) raw.value = v else f.locals[a] = v }
```

`f.locals` 即 `Frame.locals: Array<Any?>`（D5 §5）——一个编译期定大小、运行期填值的扁平数组。运行时**完全不出现变量名**，只剩 `locals[slot]` 下标访问，这是 VM 快的关键之一。

### 2.4 多个局部变量会复用同一个槽吗

不会主动复用，当前实现**槽号只增不减**。分情况：
- **不同名字的局部变量**：每个 `declareLocal` 都 `nextSlot++`，各占唯一槽，绝不共享。
- **同一函数内同名 `var`**：提升阶段 `hoistVarAndFunctions` 用 `if (!scope.locals.containsKey(name)) declareLocal(name)` 去重（`Compiler.kt:111`），同一 `var` 只分配一次槽；后续 `compileVarDecl` 的 var 分支 `resolveLocal(name)` 取到同一槽发射 `STORE_LOCAL existing`——**同名 var 复用同一槽**（标准提升语义）。
- **块级 `let/const`**：`enterBlock()` 仅切换 `scope` 指针，`leaveBlock()` 只把 `scope` 指回 parent 并发射 `POP_BLOCK`。**槽号计数器 `nextSlot` 永不回退**——源码注释明说 "slots themselves are leaked until function return"（M2 简化）。因此两块各自 `let x` 时内层占新槽，外层槽即便块已退出也不回收；不同名字的块级变量同样只增不减。
- **无"生命周期不重叠即复用"优化**：教科书式槽分配会分析变量死区来复用槽，KJS 当前没做（显式声明为 M2 取舍），换取实现简单与正确。

> 一句话：**唯一会"复用"的是同名 `var`（提升去重）；其余一律新开槽、用完不回收，直到函数返回。**

### 2.5 槽位怎么申请

就一个动作——`nextSlot++`：

```kotlin
34:34:engine/src/main/kotlin/io/kjs/ir/Compiler.kt
private var nextSlot = 0          // Compiler 级计数器, 整个函数共享
```

- 普通声明：`declareLocal(name)` 把 `nextSlot` 当前值作为槽号，再自增（§2.1）。
- 匿名临时槽：解构/展开用 `declareScratchLocal()`（`Compiler.kt:439`）以 `__destructScratch__${nextSlot}__` 为名字 `declareLocal`，避免与用户变量撞名。
- **参数优先占 0..paramCount-1**：`compileFunction` 开头先为形参 `declareLocal`，所以参数槽固定在前，局部/临时槽从 `paramCount` 之后继续 `nextSlot++`。
- 注意：KJS **没有 `freeSlot` / `reserveSlot` 这类"释放后复用"的 API**（块退出不回收槽，见 2.4）。

### 2.6 槽位数量编译期就定死：局部变量表不是"动态数组"

"局部变量表是 `Array`，数组大小如何确定"——其大小**在编译期就定死，运行期不会动态扩容**：

- 函数编译结束时，`nextSlot` 的终值就是"本函数用了多少槽"，编译器把它写进字节码：
  ```kotlin
  50:205:engine/src/main/kotlin/io/kjs/ir/Compiler.kt
  bc.localCount = c.nextSlot        // compileProgram / compileFunction 末尾都这样落定
  ```
- 运行期建帧（`Vm.kt:175`）读取 `bc.localCount` 这一个**固定整数**决定数组大小：
  ```kotlin
  175:176:engine/src/main/kotlin/io/kjs/vm/Vm.kt
  val localsSize = maxOf(bc.localCount, bc.paramCount) + 4   // +4 安全余量
  val locals = borrowLocals(localsSize)                       // 从对象池借一个"够大"的定长数组
  ```
- `borrowLocals(minSize)`（`Vm.kt:110`）从 `localsPool` 取一个 `size >= minSize` 的数组；不够就 `arrayOfNulls(maxOf(minSize,8))` 新建。借到的数组大小恰好覆盖本函数的所有槽（含临时槽），**运行期不会再增长**——所有局部变量的槽号在编译期已全部分配完毕，运行期只是往 `locals[slot]` 填值。

> 对比全局变量（D5 §5.6）：全局变量是 `HashMap`，运行期动态增删、靠名字查；而局部变量表是**编译期定长数组**，靠整数下标随机访问。两者存储模型本质不同。

### 2.7 作用域边界：`resolveLocal` 为何遇函数即停

`resolveLocal`（`Compiler.kt:78`）沿 `scope` 链向上找，遇到 `isFunction=true` 的 Scope 就 `return null`——保证"局部解析不越过当前函数"：
- 函数内 `var x` 提升到函数级 Scope，函数体任何位置（含嵌套块）`resolveLocal("x")` 都命中这层，得到同一槽（提升）。
- 嵌套块里的 `let x` 在块级 Scope，外层的同名 `var x` 在父函数 Scope；内层解析命中内层，外层解析命中函数级层，互不干扰（块级隔离）。
- 要引用更外层的变量，必须走 `resolveUpvalue`（§3），而非 `resolveLocal`。

## 3. Upvalue 闭包解析（核心原理二）

当内层函数引用了**外层函数**的局部变量，该变量要被捕获成 upvalue。`resolveUpvalue`
（`Compiler.kt:90`）沿 `parent` 链解析：

```kotlin
90:103:engine/src/main/kotlin/io/kjs/ir/Compiler.kt
private fun resolveUpvalue(name: String): Int? {
    val p = parent ?: return null
    val local = p.resolveLocal(name)                       // 外层函数自己的局部槽？
    if (local != null) return addUpvalue(name, true, local)        // parentIsLocal = true
    val up = p.resolveUpvalue(name)                        // 递归: 更外层
    if (up != null) return addUpvalue(name, false, up)             // 引用外层已闭包的 upvalue
    return null
}
private fun addUpvalue(name: String, isLocal: Boolean, idx: Int): Int {
    val existing = upvalues.indexOfFirst { it.name == name && it.parentIsLocal == isLocal && it.parentIndex == idx }
    if (existing >= 0) return existing                     // 同名同来源 → 复用同一 upvalue 下标
    upvalues.add(Upvalue(name, isLocal, idx))
    return upvalues.size - 1
}
```

于是 `upvalueInfo`（最终写入 `Bytecode.upvalueInfo`，`Compiler.kt:206`）记录每个 upvalue 的"来源"。VM 在
`MAKE_CLOSURE` 时据此组装 `Upvalue[]` 盒子链（D5 §9）：`parentIsLocal` 的从本帧局部槽开/复用盒子，
否则引更外层的 `Upvalue`。**这正是 ES 闭包"捕获变量而非值"的实现**。注意 `addUpvalue` 对"同名同来源"
的捕获会复用同一下标——多个闭包捕获同一外层变量时指向同一 `Upvalue` 盒子。

## 4. 变量提升（核心原理三）

`hoistVarAndFunctions`（`Compiler.kt:107`）在编译每个函数体**之前**先扫一遍声明，但**只提升 `var` 与
函数声明**（ES5-style），`let/const` 不在此阶段分配（它们在 `compileVarDecl` 编译到时才 `declareLocal`，见 §2.1）：

```kotlin
107:145:engine/src/main/kotlin/io/kjs/ir/Compiler.kt
private fun hoistVarAndFunctions(body: List<Stmt>, isTopLevel: Boolean) {
    fun declareOneVar(name: String) {
        if (isTopLevel) { /* 顶层 var 落在 globalEnv, 不占槽 */ }
        else if (!scope.locals.containsKey(name)) declareLocal(name)  // 函数内 var 提到函数级 Scope
    }
    fun walk(stmts: List<Stmt>) {
        for (s in stmts) when (s) {
            is VarDecl -> if (s.kind == "var") for (d in s.declarators) declareFromDeclarator(d)
            is FunctionDecl -> {
                if (isTopLevel) { /* 顶层函数声明走 DECL_GLOBAL */ }
                else if (!scope.locals.containsKey(s.name)) declareLocal(s.name)  // 函数名提升
            }
            is Block -> walk(s.body)
            is If -> { walk(listOf(s.cons)); s.alt?.let { walk(listOf(it)) } }
            is While -> walk(listOf(s.body))
            is ForC -> { (s.init as? VarDecl)?.let { if (it.kind=="var") for (d in it.declarators) declareFromDeclarator(d) }; walk(listOf(s.body)) }
            // ForIn/ForOf/Try 同理递归
        }
    }
    walk(body)
    // 函数声明整体提升: 先编译子函数, 再在本函数顶部发射 MAKE_CLOSURE + STORE_LOCAL/DECL_GLOBAL
    for (s in body) if (s is FunctionDecl) emitFunctionDecl(s, isTopLevel)
}
```

效果对应 JS 语义：
- `var x` 被提到函数级 Scope 的槽（因 `walk` 在 `enterBlock` 之前执行，`scope` 仍是函数级），所以函数内
  任何位置 `resolveLocal("x")` 都命中同一槽——这就是提升。
- 函数声明**整体提升且可调用**：`emitFunctionDecl` 先 `compileFunction` 编译子函数，再在顶部发射
  `MAKE_CLOSURE` + `STORE_LOCAL`（或顶层 `DECL_GLOBAL`），所以"先调用后声明"的函数可用。
- `let/const` **不提升**：其槽在 `compileVarDecl`（`Compiler.kt:279`）真正编译到该语句时才分配，且位于
  块级 Scope。KJS 当前未实现严格 TDZ 运行时检查（块级隔离靠 Scope 链 + `PUSH_BLOCK/POP_BLOCK` 围栏保证），
  属已知简化。
- 顶层（程序根）的 `var`/函数声明 `isTopLevel=true`，不占局部槽、改走 `DECL_GLOBAL`（全局环境，D5 §11）。

## 5. 跳转修补（核心原理四）

> **小白讲解**：这是编译器四个大招里最绕的一个，但本质很简单：写 `if/for` 的跳转指令时，目标行号还不知道，先 `emit` 一个占位跳转（目标填 0），等把"then 分支 / 循环体"编译完、知道结尾在第几行后，用 `patchA` 把占位回填成真行号。详见 D3 §3 的比喻（写菜谱"转到第 ___ 步"）。

`compileIf/compileWhile/compileForC`（`Compiler.kt:672`）用 `emit(JF/JMP, 0)` 占位 + `patchA` 回填。
以 `if` 为例：

```kotlin
674:690:engine/src/main/kotlin/io/kjs/ir/Compiler.kt
fun compileIf(node: If) {
    val jfAt = emitPlaceholder(JF)        // 占位: 条件假则跳到 else/end
    compileStmt(node.cons)
    if (node.alt != null) {
        val jEnd = emitPlaceholder(JMP)   // then 之后跳到 end
        patchA(jfAt, codeSize())          // 回填: 假跳转到 else 起点
        compileStmt(node.alt)
        patchA(jEnd, codeSize())          // 回填: 跳到 end
    } else patchA(jfAt, codeSize())
}
```

`emitPlaceholder(JF)` = `emit(JF,0)` 后返回 `code.size-1`（`Compiler.kt:46`）。**关键纪律**：占位
后必须立即记录 `at`，因为随后 `emit` 会增长 `code.size`，再取 `code.size-1` 就指向错指令。`break/
continue` 通过 `breakPatches/continuePatches`（`Compiler.kt:154`）在循环结束后统一回填（D5 的分发
循环据此实现循环）。`for-of`/`for-in` 的迭代协议指令（`FOR_OF_INIT/NEXT` 等）也在这发射（D5 §15）。

## 6. class 解语法糖（核心原理五）

VM 没有原生 class，`compileClass`（`Compiler.kt:486`）把 `class` 整体解成"构造函数 + 原型链 + 属性赋值"，
全程只用已有的 `MAKE_CLOSURE`/`STORE_PROP`/`LOAD_PROP`/`CALL_METHOD` 等原语，不新增任何 opcode。
完整流程分五步（对照 `compileClass` 与 `buildClassCtorFunction`）：

### 6.1 父类表达式求值 → 临时槽

若声明了 `extends <super>`，先把父类求值存进一个 `const` 临时槽 `__super${uid}__`（`declareLocal`
+ `STORE_LOCAL`），后续所有 `super.xxx` 都引用这个槽。`super` 使能期间压入 `classStack`
（`Compiler.kt:471`）一个 `ClassCtx`，让本类及嵌套方法体内的 `SuperMember/SuperCall` 能找到该槽名。

### 6.2 构造函数合成（buildClassCtorFunction）

不是直接编译用户写的 `constructor`，而是先合成一个 `FunctionExpr`：

- **实例字段**：所有非静态 `FIELD` 成员被前置成 `this.x = init;`（在用户 ctor 体之前执行）。
- **子类默认构造**：若用户没写 ctor，合成 `constructor(...args) { super(...args); }`（`rest` 参数 +
  `SuperCall` 带展开）。
- **基类默认构造**：合成空体 `constructor() {}`。
- **有 ctor**：把实例字段语句 + 用户 ctor 体拼成新函数体。

随后 `compileFunction` 把它编译成普通字节码并 `MAKE_CLOSURE`，ctor 句柄暂存 `ctorSlot` 临时槽。

### 6.3 原型链接线（prototype 拓扑）

```kotlin
// 节选自 engine/src/main/kotlin/io/kjs/ir/Compiler.kt
// ctor.prototype = Object.create(super ? super.prototype : Object.prototype)
LOAD_LOCAL ctorSlot
LOAD_GLOBAL "Object" ; LOAD_PROP "create"
[superVar.prototype 或 Object.prototype]        // 新实例的原型
CALL 1 ; STORE_PROP "prototype" ; POP
// ctor.prototype.constructor = ctor
LOAD_LOCAL ctorSlot ; LOAD_PROP "prototype"
LOAD_LOCAL ctorSlot ; STORE_PROP "constructor" ; POP
```

这一步在 VM 运行期复刻了标准 JS 的"实例.__proto__ === Ctor.prototype"与
"Ctor.prototype.constructor === Ctor"双向指向。

### 6.4 静态成员继承（setPrototypeOf）

仅当 `extends` 时：`Object.setPrototypeOf(ctor, super)`（`Compiler.kt:540`），使子类构造器自身能
继承父类的静态方法/属性（静态成员沿"构造器原型链"向上找）。

### 6.5 方法 / 访问器 / 字段的落位

遍历 `members`（`Compiler.kt:552`）：

- 实例方法/访问器：挂在 `ctor.prototype` 上（`LOAD_LOCAL ctorSlot; LOAD_PROP "prototype"`）。
- 静态方法/字段：直接挂在 `ctorSlot`（构造函数对象）上。
- 每条成员都是一次 `compileFunction` + `MAKE_CLOSURE` + `STORE_PROP("name")` + `POP`。
- **访问器简化**：`get/set` 当前被当作普通方法挂上（源码注释明说"proper `Object.defineProperty`
  才是规范路径"），属已知简化——访问器会带上 `()` 才能调用，不是真正的 getter/setter 语义。
- **实例字段**：非静态 `FIELD` 已在 §6.2 注入 ctor 预导，这里只处理静态字段。

### 6.6 super 的编译

- `super.prop`（`compileSuperMember` `Compiler.kt:635`）：编译成 `__superVar.prototype.prop`——
  即从父类原型上读，不是从实例上读。
- `super(...args)`（`compileSuperCall` `Compiler.kt:649`）：编译成 `__superVar.call(this, ...args)`
  （无展开）或 `__superVar.apply(this, argsArr)`（有展开）。关键是用 `GET_THIS` 把当前实例绑定成
  `this`，从而父类 ctor 的初始赋值落到正确实例上。

> 解糖让 class 完全落在"函数 + 原型 + 属性"这套已实现的原语上，VM 无需为 class 新增任何 opcode；
> 但访问器与 `this` 在静态方法中的指向等细节仍是 M2 简化点。

## 7. 参数前导（prelude）：默认值 / 解构 / rest

`compileFunction`（`Compiler.kt:158`）预留参数槽后，按参数列表发射**前导代码**：

- 有 `= default`：`JT(b)` 检查该槽是否为 `undefined`，是则求值默认值 `STORE_LOCAL`。
- 解构参数（如 `function f({a, b})`）：先 `LOAD_LOCAL` 槽 → `expandDestructure`（`Compiler.kt:290`）
  把模式编译成一系列 `LOAD_PROP` + `STORE_LOCAL` 到新槽，最后丢弃原参数槽。
- `rest`（`...args`）：`emit(MAKE_ARRAY, 0)` 收集剩余实参到最后一槽。

这与 D5 §4 "参数逆序收进 `argsArr` → 绑进 `locals[0..paramCount-1]`" 衔接：前导代码在参数已入槽后
做二次加工。

## 8. 标识符加载：四路回退

`emitLoadIdent`（`Compiler.kt:931`）决定一个名字 `x` 编译成哪条指令，回退链：

```
1. 本函数局部槽 resolveLocal(x) != null  → LOAD_LOCAL
2. 外层 upvalue resolveUpvalue(x) != null → LOAD_UPVAL
3. x == "arguments"                        → LOAD_ARGUMENTS
4. 否则                                    → LOAD_GLOBAL（运行时再查 Environment 链, D5 §11）
```

`emitStoreIdent`（`Compiler.kt:940`）对称：`STORE_LOCAL / STORE_UPVAL / STORE_GLOBAL`。赋值到未声明
全局名时 `STORE_GLOBAL` 走 `setOrDeclareGlobal`（D5 §11）。

## 9. 调用发射

`compileCall`（`Compiler.kt:1124`）区分：
- `CALL`：先编译 callee，再按顺序编译各实参（参数入栈顺序 = `arg0..argN-1`，D5 §4）。
- `CALL_METHOD`（`obj.method(...)`）：先编译 `obj` 入栈，再编译实参，最后发射（VM 弹出 `obj` 作 `this`）。
- `NEW_OP`：`new Ctor(...)`，编译 `ctor` + 实参后发射（`this` = 新实例，D5 §4）。
- **展开无专属 opcode**：`f(...arr)` 由 `emitBuildArgsArray`（`Compiler.kt:1209`）用 `Array.prototype
  .concat`/`push` 把实参拼成 `JsArray`，再以 `fn.apply(thisObj, argsArr)` 调用，运行期只是一次
  普通 `CALL_METHOD`（D5 §17）
  在运行时集合成 `argsArr`。

## 10. 解构绑定模式的编译（核心原理八）

除参数前导外，所有 `let/const/var`、`=` 赋值、`=` 形参数默认值、for-of 等都会走到 `compileBindPattern`
（`Compiler.kt:291`）。它消费栈顶一个 RHS 值，按模式把子值绑定到各自目标。模式分四类：

### 10.1 标识符 + 默认值

`IdentPattern`（`Compiler.kt:293`）：栈顶是值，若 `pattern` 带默认值先调 `applyDefault`（见 §12），
再 `bindIdent`（`Compiler.kt:326`）按 `kind` 落位——`var` 写入已解析的 local 或 `DECL_GLOBAL`，
`let/const` 走 `declareLocal` 新开槽，`""` 表示纯赋值（写已有 local/global）。

### 10.2 数组模式 `[a, , b, ...rest]`

`compileBindArrayPattern`（`Compiler.kt:381`）把 RHS 暂存到 scratch 槽 `rhsSlot`，然后逐元素：
`LOAD_LOCAL rhsSlot; LOAD_INT i; LOAD_ELEM` 取 `rhs[i]`，递归 `compileBindPattern`；`rest` 用
`rhs.slice(n)`（`.slice` + `CALL_METHOD 1`）得到剩余数组再绑定。

### 10.3 对象模式 `{a, b: x, ...rest}`

`compileBindObjectPattern`（`Compiler.kt:408`）同样把 RHS 存 scratch 槽，逐 prop：
`LOAD_LOCAL rhsSlot; LOAD_PROP key` 取属性后递归绑定。`rest` 较特殊：先 `Object.assign({}, rhs)`
得到浅拷贝，再对每个已消费 key 执行 `DELETE_PROP`，从而模拟"其余自有属性"语义。

### 10.4 默认值与 assign 目标

- 默认值的字节码形态见 §12（`DUP; LOAD_UNDEF; SEQ; JF` 跳过 → 否则 `POP` + 编译默认表达式）。
- `AssignTargetPattern`（`Compiler.kt:301`）用于"赋值到任意表达式目标"（如 `[o.x] = rhs`），
  用 `compileAssignTargetStoreTopLeaveValue` 计算 obj 并 `STORE_PROP`，最后 `POP` 丢弃。

> 所有解构统一"先存 scratch 槽 → 反复索引/取属性"的策略，避免重复求值 RHS，且天然支持嵌套
> 解构与默认值组合。

## 11. 更新与复合赋值（核心原理九）

`++/--` 与 `+=` 等需要"读旧值 → 计算 → 写回 → 决定表达式值"。

### 11.1 ++ / -- （compileUpdate）

`readAndStore`（`Compiler.kt:985`）的标准序列（以 `++` 为例）：

```
loadCurrent        // old
TO_NUMBER          // old(已转 number)
DUP                // old, old
LOAD_ONE           // old, old, 1
ADD                // old, new
[前缀] DUP; storeNew; POP; SWAP; POP   // 结果留下 new
[后缀] DUP; storeNew; POP; POP         // 结果留下 old
```

- `storeNew` 对 `Ident` 走 `emitStoreIdent`；对 `Member` 先存 `tmpObj`/`tmpKey` 临时槽，再用
  `LOAD_ELEM/STORE_ELEM` 读写（见 `Compiler.kt:1009`）。
- `TO_NUMBER` 保证 `"5"++` 得到 `6` 而非字符串拼接，符合 JS 语义。
- 前缀/后缀的差异只在最后保留栈上的 `new` 还是 `old`。

### 11.2 += 等（compileAssign 复合分支）

`a += b` 展开为 `a = a + b`（`Compiler.kt:1061`）：`emitLoadIdent(a)` 取旧值 → 编译 `b` → `ADD`
→ `DUP` 后 `emitStoreIdent(a)` 写回 → `POP` 留下新值。成员目标同样用 `__cmp_obj__/__cmp_key__`
临时槽完成"读-算-写回"。

## 12. 数值字面量发射优化（核心原理十）

`emitNumber`（`Compiler.kt:922`）按值形状选最紧凑的 opcode，减小常量池与指令数：

| 情形 | 发射 | 说明 |
|---|---|---|
| `0`（且非 `-0`） | `LOAD_ZERO` | `1.0/d < 0` 排除 `-0` |
| `1` | `LOAD_ONE` | |
| 落在 `Int` 范围且无小数 | `LOAD_INT asInt` | 省去常量池槽 |
| 其它 | `LOAD_CONST constIdx(d)` | 大数/浮点走常量池 |

`BigIntLit` 固定走 `LOAD_CONST`（`constIdx` 存 `String`），`StringLit/TemplateLit` 走 `LOAD_STR`。

## 13. for-in / for-of 的字节码发射（核心原理十一）

两个循环共享"迭代器 + 回填"模式，区别在于 VM 提供的迭代原语：

- **for-in**（`compileForIn` `Compiler.kt:758`）：编译右侧得对象 → `FOR_IN_INIT`（VM 取可枚举自有
  + 继承 key 集合）→ 循环体顶部 `FOR_IN_NEXT -1` 取下一个 key，回填到目标后 `POP` 丢弃表达式值。
- **for-of**（`compileForIn` `Compiler.kt:781`）：`FOR_OF_INIT` 先 `obj[Symbol.iterator]()` 拿到
  迭代器存 VM 句柄槽，循环体 `FOR_OF_NEXT -1` 调 `iterator.next()`，命中 `done` 则跳到 `end`。
- 两者都 `enterBlock` 声明循环变量（若为 `let/const`），循环末尾 `JMP top` 回到取数点；
  `break/continue` 通过 `loopPatches` 回填到 `end`/`top`，`end` 后 `POP` 释放迭代器句柄。

> 迭代状态完全由 VM 在帧外维护（见 D5 §15），编译器只负责"开启—取值—结束"三件套，因而
> 解构/展开/迭代协议都不需要新 opcode。

## 14. try/catch/finally 的字节码布局（核心原理十二）

`compileTry`（`Compiler.kt:802`）用 `TRY_ENTER`/`TRY_EXIT`/`END_FINALLY` 三指令框住异常流：

```
TRY_ENTER  catchPc, finallyPc      // 两操作数均先填 -1，编译完回填
<try 块>
TRY_EXIT
JMP  finallyPc(A)                  // 正常落地的跳转，跳过 catch
--- catchPc ---
[enterBlock; declare catchParam; STORE_LOCAL; POP]
<catch 块>
leaveBlock
--- finallyPc ---                  // 无论正常/异常都到这
<finally 块>
END_FINALLY                        // 把异常（若有）继续向上抛
```

- `TRY_ENTER` 两个操作数：catch 入口 `patchA`、finally 入口 `patchB`，编译期未知故先 `-1`。
- catch 参数绑定到新槽；无 catch 时 `catchPc = -1`（VM 直接跳过）。
- `END_FINALLY` 是异常传播的"续传点"：若进入 finally 是因为异常，这里重新抛出，否则正常结束。

## 15. 设计取舍

- **每函数一 Compiler + parent 链**：闭包/作用域天然落在编译器结构上，Upvalue 沿链解析。
- **槽号代替名字**：运行时零哈希查找（`Frame.locals[slot]`），是 VM 快的关键之一。
- **提升 + 块级作用域分离**：`var`/函数声明提函数顶，`let/const` 用块 Scope 实现 TDZ。
- **跳转占位 + 补丁**：扁平指令流无标签，跳转目标编译期未知，用占位回填解决。
- **class 全解糖**：不污染 VM opcode 集，class = 函数+原型+属性。
- **标识符四级回退**：局部 → upvalue → arguments → 全局，覆盖 JS 全部名称解析路径。

## 16. 常见坑

- **补丁点记录时机**：占位 `emit` 后必须立即存 `at`，否则 `code.size-1` 指错（D3 §8）。
- **`var` 提升边界**：`resolveLocal` 须在函数作用域边界停止，否则跨函数共享槽号。
- **Upvalue 链式**：`parentIsLocal=false` 必须引"外层已闭包 upvalue"而非重新开盒，否则多层闭包
  捕获不一致（D5 §9）。
- **块级槽不回收（M2 简化）**：`leaveBlock` 只回退 `scope` 指针、发射 `POP_BLOCK`，`nextSlot` 与已占槽
  **不回收**（源码注释 "slots themselves are leaked until function return"）。当前无"生命周期不重叠即
  复用槽"的优化，深层嵌套块会使 `localCount` 偏大，但正确性无虞。
- **class 继承原型顺序**：`superClass.prototype` 设置必须在实例属性赋值前，否则覆盖错序。
- **rest 参数与前导**：解构/rest 前导须在"参数已绑槽"之后，且 `localCount` 要覆盖临时槽。
- **解构 scratch 槽必须覆盖 `localCount`**：`declareScratchLocal` 开的临时槽也计入最终 `localCount`
  （§2.6），否则 VM 建帧时数组太小，运行期越界。
- **`emitNumber` 别手填 `-0` 特例**：`0.0` 与 `-0.0` 必须区别（前者 `LOAD_ZERO`、后者 `LOAD_CONST`），
  否则 `Object.is(-0, 0)` 语义失真。
- **前缀/后缀更新的栈平衡**：`readAndStore` 的最后一步必须用 `SWAP; POP`（前缀）或 `POP`（后缀）
  精确丢弃，否则表达式值或栈高度错乱（§11.1）。
- **`TRY_ENTER` 两个回填点缺一不可**：catch 与 finally 入口都要在 `compileTry` 末尾 `patchA/patchB`，
  漏填任一个会让 VM 跳到 `0` 或野地址。
