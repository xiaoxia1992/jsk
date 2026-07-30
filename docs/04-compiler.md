# D4 · 编译器 Compiler（AST → Bytecode）

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

VM 没有原生 class，`compileClass`（`Compiler.kt:485`）把 `class` 解成"构造函数 + 原型赋值"：

- 构造 `ClassDecl.constructor`（或默认空构造）编译成普通 `FunctionExpr`。
- 遍历 `members`：实例方法挂到 `prototype`，静态方法挂到构造函数对象本身（`emit(STORE_PROP,
  strIdx("prototype"))` 等）。`get/set` 访问器用 `Object.defineProperty` 语义发射。
- 继承（`superClass`）：在构造函数体顶部插入 `superClass.prototype` 作为新实例原型的设置，并把
  `super` 引用解析到外层 `this` 与原型链（通过 `SuperMember/SuperCall` AST 节点编译成相应属性访问）。
- 最终发射 `MAKE_CLASS_INSTANCE` 组合出完整类对象，交给调用处 `STORE_LOCAL` 等。

> 解糖让 class 完全落在"函数 + 原型 + 属性"这套已实现的原语上，VM 无需为 class 新增任何 opcode。

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
- `SPREAD`：`f(...arr)` 把数组展开成多个实参，发射 `SPREAD` + `emitBuildArgsArray`（`Compiler.kt:1209`）
  在运行时集合成 `argsArr`。

## 10. 设计取舍

- **每函数一 Compiler + parent 链**：闭包/作用域天然落在编译器结构上，Upvalue 沿链解析。
- **槽号代替名字**：运行时零哈希查找（`Frame.locals[slot]`），是 VM 快的关键之一。
- **提升 + 块级作用域分离**：`var`/函数声明提函数顶，`let/const` 用块 Scope 实现 TDZ。
- **跳转占位 + 补丁**：扁平指令流无标签，跳转目标编译期未知，用占位回填解决。
- **class 全解糖**：不污染 VM opcode 集，class = 函数+原型+属性。
- **标识符四级回退**：局部 → upvalue → arguments → 全局，覆盖 JS 全部名称解析路径。

## 11. 常见坑

- **补丁点记录时机**：占位 `emit` 后必须立即存 `at`，否则 `code.size-1` 指错（D3 §8）。
- **`var` 提升边界**：`resolveLocal` 须在函数作用域边界停止，否则跨函数共享槽号。
- **Upvalue 链式**：`parentIsLocal=false` 必须引"外层已闭包 upvalue"而非重新开盒，否则多层闭包
  捕获不一致（D5 §9）。
- **块级槽不回收（M2 简化）**：`leaveBlock` 只回退 `scope` 指针、发射 `POP_BLOCK`，`nextSlot` 与已占槽
  **不回收**（源码注释 "slots themselves are leaked until function return"）。当前无"生命周期不重叠即
  复用槽"的优化，深层嵌套块会使 `localCount` 偏大，但正确性无虞。
- **class 继承原型顺序**：`superClass.prototype` 设置必须在实例属性赋值前，否则覆盖错序。
- **rest 参数与前导**：解构/rest 前导须在"参数已绑槽"之后，且 `localCount` 要覆盖临时槽。
