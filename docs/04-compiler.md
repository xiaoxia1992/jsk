# D4 · 编译器 Compiler（AST → Bytecode）

> 前置知识：D2（AST）、D3（字节码）。
>
> 本篇拆解 `ir/Compiler.kt`：如何把 AST 编译成 `Bytecode`。核心是四件事——**作用域/槽位分配、
> Upvalue 闭包解析、变量提升、跳转修补**，外加 class 解语法糖。读完能理解"结构化的 AST 如何变成
> VM（D5）可直接执行的扁平指令流"。

## 1. 编译器结构：每个函数一个 Compiler

`Compiler`（`Compiler.kt:23`）不全局单例，而是**每个函数一次 new**（含顶层 program）：

```kotlin
23:39:engine/src/main/kotlin/io/kjs/ir/Compiler.kt
class Compiler(
    val parent: Compiler?,                 // 外层函数编译器（构成作用域链）
    val bytecode: Bytecode,                // 本函数的字节码产物
    val isArrow: Boolean,                  // 是否箭头函数（this 沿用外层）
    source: String,
) {
    data class Scope(val kind: String, val locals: LinkedHashMap<String, Int>, val nextSlot: Int)
    private val scopeStack = ArrayDeque<Scope>()   // 块级作用域栈
    private val upvalues = mutableListOf<Upvalue>() // 本函数捕获的 upvalue 列表
    private val scopeDepth get() = scopeStack.size
    private val strings get() = bytecode.strings
    // ...
}
```

- **`parent` 链**：内层函数能通过 `parent` 一路向上找外围的局部变量（用于闭包与 `upvalue`）。
- **`Scope`**（`Compiler.kt:28`）：一个块级作用域 = 局部名 → 槽号映射 + 下一空闲槽 `nextSlot`。
- **`upvalues`**：本函数需从外层捕获的变量清单（`Upvalue` 记录来源是"外层局部槽"还是"更外层 upvalue"）。

`compileProgram`（`Compiler.kt:43`）是入口：hoist → 编译顶层语句 → `HALT` → `freeze` → 返回 bytecode。
`compileFunction`（`Compiler.kt:158`）对每个函数创建子 `Compiler`，预留参数槽并编译函数体。

## 2. 作用域与槽位分配（核心原理一）

局部变量不按名字引用，而是**编译期分配一个整数"槽号"**，运行时就是 `Frame.locals[槽]`（D5 §5）。

```kotlin
57:87:engine/src/main/kotlin/io/kjs/ir/Compiler.kt
fun enterBlock(kind: String) { scopeStack.addLast(Scope(kind, LinkedHashMap(), nextSlotLocal())) }
fun leaveBlock() { val s = scopeStack.removeLast(); for ((_, slot) in s.locals) freeSlot(slot); emit(POP_BLOCK) }
fun declareLocal(name: String): Int {
    // 块作用域(let/const)在最近一层声明；函数作用域(var/函数声明)提升到函数顶部
    val scope = if (isBlockScoped) scopeStack.last() else scopeStack.first()
    return scope.locals.getOrPut(name) { scope.nextSlot++ }  // 新名字才占新槽
}
fun resolveLocal(name: String): Int? {
    // 从最近块向上找，但遇到"函数作用域"边界(非 block)即停（不越函数）
    for (i in scopeStack.indices.reversed())
        if (scopeStack[i].locals.containsKey(name)) return scopeStack[i].locals[name]
    return null
}
```

要点：
- **`declareLocal`**：`let/const`（`isBlockScoped`）放进当前块；`var`/函数声明**提升到函数顶部**
  （`scopeStack.first()`），这正是 JS "var 提升" 与 "块级 let 不跨块" 的实现。
- **`resolveLocal`** 遇函数作用域边界停止——保证 `var x` 在函数内任何位置都解析到同一顶层槽（提升）。
- **`freeSlot`**（`Compiler.kt:67`）：块退出时回收其局部槽，供后续块复用，控制 `localCount` 不膨胀。
- `PUSH_BLOCK`/`POP_BLOCK`（D5 §2）是运行时块边界指令，配合 `leaveBlock` 的 `emit(POP_BLOCK)`。

## 3. Upvalue 闭包解析（核心原理二）

当内层函数引用了**外层函数**的局部变量，该变量要被捕获成 upvalue。`resolveUpvalue`
（`Compiler.kt:90`）沿 `parent` 链解析：

```kotlin
90:103:engine/src/main/kotlin/io/kjs/ir/Compiler.kt
fun resolveUpvalue(name: String): Int? {
    val p = parent ?: return null
    val local = p.resolveLocal(name)            // 外层函数自己的局部槽？
    if (local != null) {
        val idx = p.addUpvalue(Upvalue(true, local))   // parentIsLocal = true
        return upvalues.size.also { upvalues.add(Upvalue(false, idx)) }  // 本层引用外层 upvalue
    }
    val up = p.resolveUpvalue(name) ?: return null     // 递归: 更外层
    val idx = p.addUpvalue(Upvalue(false, up))          // 链式引用外层已闭包的 upvalue
    return upvalues.size.also { upvalues.add(Upvalue(false, idx)) }
}
```

于是 `upvalueInfo`（最终写入 `Bytecode.upvalueInfo`）记录每个 upvalue 的"来源"。VM 在
`MAKE_CLOSURE` 时据此组装 `Upvalue[]` 盒子链（D5 §9）：`parentIsLocal` 的从本帧局部槽开/复用盒子，
否则引更外层的 `Upvalue`。**这正是 ES 闭包"捕获变量而非值"的实现**。

## 4. 变量提升（核心原理三）

`hoisting`（`Compiler.kt:107`）在编译每个函数体**之前**先扫一遍声明：

```kotlin
107:156:engine/src/main/kotlin/io/kjs/ir/Compiler.kt
private fun hoisting(node: Node) {
    when (node) {
        is VarDecl -> for (d in node.decls) {
            val slot = declareLocal(d.name!!, isBlockScoped = node.kind != "var")  // var 提到函数顶
            if (d.init != null) { compileExpr(d.init); emit(STORE_LOCAL, slot) }  // 有初值则在顶部求值
        }
        is FunctionDecl -> {
            val fnBc = compileFunction(node, ...)
            val slot = declareLocal(node.name, isBlockScoped = false)
            emit(MAKE_CLOSURE, fnIdx(fnBc)); emit(STORE_LOCAL, slot)   // 函数声明整体提升
        }
        is Block -> node.body.forEach { hoisting(it) }
        is If -> { hoisting(node.cons); node.alt?.let { hoisting(it) } }
        // while/for 等同理递归
    }
}
```

效果对应 JS 语义：
- `var x` / 函数声明被提升到函数顶部（用 `scopeStack.first()` 的槽）。
- 函数声明**整体提升且可调用**（先编译子函数、发射 `MAKE_CLOSURE`），所以"先调用后声明"的函数可用。
- `let/const` 通过"块级 `Scope` + `PUSH_BLOCK/POP_BLOCK`"实现 TDZ（运行时访问未初始化块变量由
  块环境变量管理，D6 §5）。

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
- **块退出回收槽**：`leaveBlock` 须 `freeSlot`，否则嵌套块槽号无限增长、且 TDZ 名冲突。
- **class 继承原型顺序**：`superClass.prototype` 设置必须在实例属性赋值前，否则覆盖错序。
- **rest 参数与前导**：解构/rest 前导须在"参数已绑槽"之后，且 `localCount` 要覆盖临时槽。
