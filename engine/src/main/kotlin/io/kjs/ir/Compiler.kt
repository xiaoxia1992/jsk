package io.kjs.ir

import io.kjs.parse.*

/**
 * AST -> Bytecode compiler.
 *
 * Design:
 *  - One compilation unit per function (top-level program is a function too).
 *  - Scope/slot allocation is per-function: params + var hoistings + let/const slots
 *    all live in a flat [LongArray]-backed local frame at runtime. Block scopes
 *    (let/const) map to fresh slots whose lifetime is guarded by PUSH_BLOCK/POP_BLOCK
 *    (the VM just uses that pair as a fence; slots themselves are leaked until
 *    function return — acceptable for M2, revisit when adding IC/JIT in M5).
 *  - Closures: when an inner function references a name from an outer function,
 *    we record an "upvalue" entry. Upvalues are resolved via a chain of parent
 *    compilers so we don't need a name lookup at runtime for captured locals.
 *
 * Unsupported in M2 (falls back to runtime throw): labeled break/continue,
 * arguments[] mutation through `arguments[i] = x`, `eval`, `with`. for-in/for-of
 * are compiled via FOR_IN_INIT/NEXT helpers.
 */
class Compiler private constructor(
    private val parent: Compiler?,
    val bytecode: Bytecode,
) {
    /** Lexical scope chain for slot allocation. */
    private class Scope(val parent: Scope?, val isFunction: Boolean) {
        val locals = LinkedHashMap<String, Int>()
        val consts = HashSet<String>()
    }

    private var scope: Scope = Scope(null, isFunction = true)
    private var nextSlot = 0

    /** Upvalue entry: either refers to a local in the immediate parent (index) or
     *  another upvalue in the parent (parentUpvalue). */
    data class Upvalue(val name: String, val parentIsLocal: Boolean, val parentIndex: Int)
    val upvalues = ArrayList<Upvalue>()

    companion object {
        /** Compile a full program: outer "function" with no params. */
        fun compileProgram(program: Program, source: String): Bytecode {
            val bc = Bytecode(name = "<main>", paramCount = 0, isArrow = false)
            bc.source = source
            val c = Compiler(parent = null, bytecode = bc)
            c.hoistVarAndFunctions(program.body, isTopLevel = true)
            for (s in program.body) c.compileStmt(s)
            bc.emit(Op.HALT)
            bc.localCount = c.nextSlot
            bc.freeze()
            return bc
        }
    }

    // ---- scope utilities ----
    private fun enterBlock(): Scope {
        val s = Scope(scope, isFunction = false)
        scope = s
        bytecode.emit(Op.PUSH_BLOCK)
        return s
    }
    private fun leaveBlock() {
        bytecode.emit(Op.POP_BLOCK)
        scope = scope.parent ?: error("scope underflow")
    }
    private fun declareLocal(name: String, isConst: Boolean = false): Int {
        // let/const must not redeclare in same block scope
        if (scope.locals.containsKey(name)) {
            // allow var redeclaration; but for strictness we'll simply overwrite the slot
        }
        val slot = nextSlot++
        scope.locals[name] = slot
        if (isConst) scope.consts.add(name)
        return slot
    }
    /** Returns slot if name is a local in current function's chain of scopes. */
    private fun resolveLocal(name: String): Int? {
        var s: Scope? = scope
        while (s != null) {
            val v = s.locals[name]
            if (v != null) return v
            if (s.isFunction) return null
            s = s.parent
        }
        return null
    }

    /** Try to resolve as upvalue: look up in the parent compiler recursively; returns index into upvalues[]. */
    private fun resolveUpvalue(name: String): Int? {
        val p = parent ?: return null
        val local = p.resolveLocal(name)
        if (local != null) return addUpvalue(name, true, local)
        val up = p.resolveUpvalue(name)
        if (up != null) return addUpvalue(name, false, up)
        return null
    }
    private fun addUpvalue(name: String, isLocal: Boolean, idx: Int): Int {
        val existing = upvalues.indexOfFirst { it.name == name && it.parentIsLocal == isLocal && it.parentIndex == idx }
        if (existing >= 0) return existing
        upvalues.add(Upvalue(name, isLocal, idx))
        return upvalues.size - 1
    }

    // ---- hoisting ----
    /** ES5-style hoisting for var and function decls. Called once per function body. */
    private fun hoistVarAndFunctions(body: List<Stmt>, isTopLevel: Boolean) {
        // First pass: declare var names and function decls as locals (or globals if top-level).
        fun walk(stmts: List<Stmt>) {
            for (s in stmts) when (s) {
                is VarDecl -> if (s.kind == "var") for (d in s.declarators) {
                    if (isTopLevel) { /* top-level vars live on the global env */ }
                    else if (!scope.locals.containsKey(d.name)) declareLocal(d.name)
                }
                is FunctionDecl -> {
                    if (isTopLevel) { /* handled at emission */ }
                    else if (!scope.locals.containsKey(s.name)) declareLocal(s.name)
                }
                is Block -> walk(s.body)
                is If -> { walk(listOf(s.cons)); s.alt?.let { walk(listOf(it)) } }
                is While -> walk(listOf(s.body))
                is DoWhile -> walk(listOf(s.body))
                is ForC -> {
                    (s.init as? VarDecl)?.let { if (it.kind == "var") for (d in it.declarators) if (!scope.locals.containsKey(d.name)) declareLocal(d.name) }
                    walk(listOf(s.body))
                }
                is ForIn -> walk(listOf(s.body))
                is ForOf -> walk(listOf(s.body))
                is Try -> { walk(s.block.body); s.catchBody?.let { walk(it.body) }; s.finallyBody?.let { walk(it.body) } }
                else -> {}
            }
        }
        walk(body)

        // Function decls: emit MAKE_CLOSURE ... STORE_LOCAL (or DECL_GLOBAL) at the top of the function body.
        for (s in body) if (s is FunctionDecl) emitFunctionDecl(s, isTopLevel)
    }

    private fun emitFunctionDecl(s: FunctionDecl, isTopLevel: Boolean) {
        val fnBc = compileFunction(s.name, s.params, s.body, isArrow = false)
        val fi = bytecode.fnIdx(fnBc)
        bytecode.emit(Op.MAKE_CLOSURE, fi, 0, s.line)
        if (isTopLevel) bytecode.emit(Op.DECL_GLOBAL, bytecode.strIdx(s.name))
        else {
            val slot = scope.locals[s.name] ?: declareLocal(s.name)
            bytecode.emit(Op.STORE_LOCAL, slot); bytecode.emit(Op.POP)
        }
    }

    private fun compileFunction(name: String, params: List<String>, body: Block, isArrow: Boolean): Bytecode {
        val bc = Bytecode(name = name, paramCount = params.size, isArrow = isArrow)
        bc.source = bytecode.source
        val c = Compiler(parent = this, bytecode = bc)
        // params -> first N slots
        for (p in params) c.declareLocal(p)
        c.hoistVarAndFunctions(body.body, isTopLevel = false)
        for (s in body.body) c.compileStmt(s)
        bc.emit(Op.RET_UNDEF)
        bc.localCount = c.nextSlot
        // Publish upvalue table as a strings/indices pair on bytecode via a sidecar
        bc.upvalueInfo = c.upvalues.toList()
        return bc
    }

    // ---- statements ----
    private fun compileStmt(s: Stmt) {
        when (s) {
            is Block -> {
                enterBlock()
                hoistVarAndFunctions(s.body, isTopLevel = false)
                for (st in s.body) compileStmt(st)
                leaveBlock()
            }
            is ExprStmt -> { compileExpr(s.expr); bytecode.emit(Op.STASH_RESULT) }
            is VarDecl -> compileVarDecl(s)
            is If -> compileIf(s)
            is While -> compileWhile(s)
            is DoWhile -> compileDoWhile(s)
            is ForC -> compileForC(s)
            is ForIn -> compileForIn(s)
            is ForOf -> compileForOf(s)
            is Return -> { if (s.arg != null) { compileExpr(s.arg); bytecode.emit(Op.RET) } else bytecode.emit(Op.RET_UNDEF) }
            is Throw -> { compileExpr(s.arg); bytecode.emit(Op.THROW) }
            is Try -> compileTry(s)
            is FunctionDecl -> {} // already hoisted
            is Labeled -> { pendingLabel = s.label; compileStmt(s.body); pendingLabel = null }
            is Break -> {
                val target = findLoop(s.label) ?: error("break target not found")
                bytecode.emit(Op.JMP, -1, 1, s.line); target.breaks.add(bytecode.size - 1)
            }
            is Continue -> {
                val target = findLoop(s.label) ?: error("continue target not found")
                bytecode.emit(Op.JMP, -1, 2, s.line); target.continues.add(bytecode.size - 1)
            }
            is EmptyStmt -> {}
        }
    }

    private fun compileVarDecl(s: VarDecl) {
        for (d in s.declarators) {
            val isVar = s.kind == "var"
            val init: Expr? = d.init
            if (init != null) compileExpr(init)
            else if (isVar) continue  // hoisted; no re-init
            else bytecode.emit(Op.LOAD_UNDEF)

            if (isVar) {
                // var can reuse any enclosing local slot in the same function
                val existing = resolveLocal(d.name)
                if (existing != null) {
                    bytecode.emit(Op.STORE_LOCAL, existing); bytecode.emit(Op.POP)
                } else {
                    // top-level var -> global
                    bytecode.emit(Op.DECL_GLOBAL, bytecode.strIdx(d.name))
                }
            } else {
                // let/const: always a fresh slot in the *current* scope (shadowing outer)
                val slot = declareLocal(d.name, isConst = (s.kind == "const"))
                bytecode.emit(Op.STORE_LOCAL, slot); bytecode.emit(Op.POP)
            }
        }
    }

    private fun compileIf(s: If) {
        compileExpr(s.test)
        val jf = bytecode.emit(Op.JF, -1, 0, s.line)
        compileStmt(s.cons)
        if (s.alt != null) {
            val jmp = bytecode.emit(Op.JMP, -1, 0, s.line)
            bytecode.patchA(jf, bytecode.size)
            compileStmt(s.alt)
            bytecode.patchA(jmp, bytecode.size)
        } else {
            bytecode.patchA(jf, bytecode.size)
        }
    }

    // --- loop bookkeeping for break/continue ---
    private class LoopPatch(
        val label: String? = null,
        val breaks: MutableList<Int> = mutableListOf(),
        val continues: MutableList<Int> = mutableListOf()
    )
    private val loopPatches = ArrayDeque<LoopPatch>()
    /** Pending label consumed by the next loop-like statement. */
    private var pendingLabel: String? = null

    private fun findLoop(label: String?): LoopPatch? {
        if (label == null) return loopPatches.lastOrNull()
        for (i in loopPatches.indices.reversed()) {
            val lp = loopPatches.elementAt(i)
            if (lp.label == label) return lp
        }
        return null
    }

    private fun compileWhile(s: While) {
        val top = bytecode.size
        compileExpr(s.test)
        val jf = bytecode.emit(Op.JF, -1, 0, s.line)
        loopPatches.addLast(LoopPatch(label = pendingLabel)); pendingLabel = null
        compileStmt(s.body)
        val patch = loopPatches.removeLast()
        for (ci in patch.continues) bytecode.patchA(ci, top)
        bytecode.emit(Op.JMP, top)
        val end = bytecode.size
        bytecode.patchA(jf, end)
        for (bi in patch.breaks) bytecode.patchA(bi, end)
    }

    private fun compileDoWhile(s: DoWhile) {
        val top = bytecode.size
        loopPatches.addLast(LoopPatch(label = pendingLabel)); pendingLabel = null
        compileStmt(s.body)
        val patch = loopPatches.removeLast()
        val condPc = bytecode.size
        for (ci in patch.continues) bytecode.patchA(ci, condPc)
        compileExpr(s.test)
        bytecode.emit(Op.JT, top)
        val end = bytecode.size
        for (bi in patch.breaks) bytecode.patchA(bi, end)
    }

    private fun compileForC(s: ForC) {
        enterBlock()
        when (val i = s.init) {
            is VarDecl -> compileVarDecl(i)
            is ExprStmt -> { compileExpr(i.expr); bytecode.emit(Op.POP) }
            null -> {}
            else -> {}
        }
        val top = bytecode.size
        val jfEnd: Int = if (s.test != null) {
            compileExpr(s.test); bytecode.emit(Op.JF, -1, 0, s.line)
        } else -1
        loopPatches.addLast(LoopPatch(label = pendingLabel)); pendingLabel = null
        compileStmt(s.body)
        val patch = loopPatches.removeLast()
        val updatePc = bytecode.size
        for (ci in patch.continues) bytecode.patchA(ci, updatePc)
        if (s.update != null) { compileExpr(s.update); bytecode.emit(Op.POP) }
        bytecode.emit(Op.JMP, top)
        val end = bytecode.size
        if (jfEnd >= 0) bytecode.patchA(jfEnd, end)
        for (bi in patch.breaks) bytecode.patchA(bi, end)
        leaveBlock()
    }

    private fun compileForIn(s: ForIn) {
        enterBlock()
        // Optionally declare loop var
        if (s.leftKind != null && s.left is Ident) declareLocal(s.left.name, isConst = s.leftKind == "const")
        compileExpr(s.right)
        bytecode.emit(Op.FOR_IN_INIT)
        val top = bytecode.size
        val next = bytecode.emit(Op.FOR_IN_NEXT, -1, 0, s.line)
        // Store the produced key into left target; assign leaves value on stack, pop it.
        compileAssignTarget(s.left, fromStack = true)
        bytecode.emit(Op.POP)
        loopPatches.addLast(LoopPatch(label = pendingLabel)); pendingLabel = null
        compileStmt(s.body)
        val patch = loopPatches.removeLast()
        for (ci in patch.continues) bytecode.patchA(ci, top)
        bytecode.emit(Op.JMP, top)
        val end = bytecode.size
        bytecode.patchA(next, end)
        bytecode.emit(Op.POP)  // pop iterator handle
        for (bi in patch.breaks) bytecode.patchA(bi, end)
        leaveBlock()
    }

    private fun compileForOf(s: ForOf) {
        enterBlock()
        if (s.leftKind != null && s.left is Ident) declareLocal(s.left.name, isConst = s.leftKind == "const")
        compileExpr(s.right)
        bytecode.emit(Op.FOR_OF_INIT)
        val top = bytecode.size
        val next = bytecode.emit(Op.FOR_OF_NEXT, -1, 0, s.line)
        compileAssignTarget(s.left, fromStack = true)
        bytecode.emit(Op.POP)
        loopPatches.addLast(LoopPatch(label = pendingLabel)); pendingLabel = null
        compileStmt(s.body)
        val patch = loopPatches.removeLast()
        for (ci in patch.continues) bytecode.patchA(ci, top)
        bytecode.emit(Op.JMP, top)
        val end = bytecode.size
        bytecode.patchA(next, end)
        bytecode.emit(Op.POP)
        for (bi in patch.breaks) bytecode.patchA(bi, end)
        leaveBlock()
    }

    private fun compileTry(s: Try) {
        val enter = bytecode.emit(Op.TRY_ENTER, -1, -1, s.line)
        compileStmt(s.block)
        bytecode.emit(Op.TRY_EXIT)
        val jmpAfter = bytecode.emit(Op.JMP, -1, 0, s.line)

        val catchPc: Int
        if (s.catchBody != null) {
            catchPc = bytecode.size
            enterBlock()
            if (s.catchParam != null) {
                val slot = declareLocal(s.catchParam)
                bytecode.emit(Op.STORE_LOCAL, slot); bytecode.emit(Op.POP)
            } else {
                bytecode.emit(Op.POP) // discard thrown value
            }
            for (st in s.catchBody.body) compileStmt(st)
            leaveBlock()
        } else catchPc = -1

        val finallyPc: Int
        if (s.finallyBody != null) {
            bytecode.patchA(jmpAfter, bytecode.size)
            finallyPc = bytecode.size
            for (st in s.finallyBody.body) compileStmt(st)
            bytecode.emit(Op.END_FINALLY)
        } else {
            bytecode.patchA(jmpAfter, bytecode.size)
            finallyPc = -1
        }
        bytecode.patchA(enter, catchPc); bytecode.patchB(enter, finallyPc)
    }

    // ---- expressions ----
    private fun compileExpr(e: Expr) {
        when (e) {
            is NumberLit -> emitNumber(e.value, e.line)
            is StringLit -> bytecode.emit(Op.LOAD_STR, bytecode.strIdx(e.value), 0, e.line)
            is TemplateLit -> bytecode.emit(Op.LOAD_STR, bytecode.strIdx(e.raw), 0, e.line)
            is BoolLit -> bytecode.emit(if (e.value) Op.LOAD_TRUE else Op.LOAD_FALSE)
            NullLit -> bytecode.emit(Op.LOAD_NULL)
            UndefinedLit -> bytecode.emit(Op.LOAD_UNDEF)
            ThisExpr -> bytecode.emit(Op.GET_THIS)
            is Ident -> emitLoadIdent(e.name, e.line)
            is ArrayLit -> { e.elements.forEach { if (it != null) compileExpr(it) else bytecode.emit(Op.LOAD_UNDEF) }; bytecode.emit(Op.MAKE_ARRAY, e.elements.size) }
            is ObjectLit -> {
                for ((k, v) in e.props) { bytecode.emit(Op.LOAD_STR, bytecode.strIdx(k)); compileExpr(v) }
                bytecode.emit(Op.MAKE_OBJECT, e.props.size)
            }
            is FunctionExpr -> {
                val fnBc = compileFunction(e.name ?: "", e.params, e.body, isArrow = false)
                bytecode.emit(Op.MAKE_CLOSURE, bytecode.fnIdx(fnBc), 0, e.line)
            }
            is ArrowFn -> {
                val body = if (e.body is Block) e.body else Block(listOf(Return(e.body as Expr)))
                val fnBc = compileFunction("", e.params, body, isArrow = true)
                bytecode.emit(Op.MAKE_CLOSURE, bytecode.fnIdx(fnBc), 0, e.line)
            }
            is Unary -> compileUnary(e)
            is Update -> compileUpdate(e)
            is Binary -> compileBinary(e)
            is Logical -> compileLogical(e)
            is Assign -> compileAssign(e)
            is Conditional -> { compileExpr(e.test); val jf = bytecode.emit(Op.JF, -1); compileExpr(e.cons); val j = bytecode.emit(Op.JMP, -1); bytecode.patchA(jf, bytecode.size); compileExpr(e.alt); bytecode.patchA(j, bytecode.size) }
            is Member -> compileMember(e, forCall = false)
            is Call -> compileCall(e)
            is NewExpr -> compileNew(e)
            is Sequence -> { for ((i, ex) in e.items.withIndex()) { compileExpr(ex); if (i != e.items.lastIndex) bytecode.emit(Op.POP) } }
        }
    }

    private fun emitNumber(d: Double, line: Int) {
        if (d == 0.0 && !(1.0 / d < 0)) { bytecode.emit(Op.LOAD_ZERO, 0, 0, line); return }
        if (d == 1.0) { bytecode.emit(Op.LOAD_ONE, 0, 0, line); return }
        val asInt = d.toInt()
        if (asInt.toDouble() == d && d >= Int.MIN_VALUE.toDouble() && d <= Int.MAX_VALUE.toDouble()) {
            bytecode.emit(Op.LOAD_INT, asInt, 0, line); return
        }
        bytecode.emit(Op.LOAD_CONST, bytecode.constIdx(d), 0, line)
    }

    private fun emitLoadIdent(name: String, line: Int) {
        val slot = resolveLocal(name)
        if (slot != null) { bytecode.emit(Op.LOAD_LOCAL, slot, 0, line); return }
        val up = resolveUpvalue(name)
        if (up != null) { bytecode.emit(Op.LOAD_UPVAL, up, 0, line); return }
        if (name == "arguments") { bytecode.emit(Op.LOAD_ARGUMENTS); return }
        bytecode.emit(Op.LOAD_GLOBAL, bytecode.strIdx(name), 0, line)
    }

    private fun emitStoreIdent(name: String) {
        val slot = resolveLocal(name)
        if (slot != null) { bytecode.emit(Op.STORE_LOCAL, slot); return }
        val up = resolveUpvalue(name)
        if (up != null) { bytecode.emit(Op.STORE_UPVAL, up); return }
        bytecode.emit(Op.STORE_GLOBAL, bytecode.strIdx(name))
    }

    private fun compileUnary(e: Unary) {
        // `typeof x` where x is a bare identifier that may not be declared needs special care.
        if (e.op == "typeof" && e.arg is Ident) {
            val name = e.arg.name
            val slot = resolveLocal(name); val up = if (slot == null) resolveUpvalue(name) else null
            when {
                slot != null -> bytecode.emit(Op.LOAD_LOCAL, slot)
                up != null -> bytecode.emit(Op.LOAD_UPVAL, up)
                else -> bytecode.emit(Op.LOAD_GLOBAL, bytecode.strIdx(name), 1 /* tolerateUndef */)
            }
            bytecode.emit(Op.TYPEOF); return
        }
        if (e.op == "delete" && e.arg is Member) {
            val m = e.arg
            if (m.computed) {
                compileExpr(m.obj); compileExpr(m.computedExpr!!); bytecode.emit(Op.DELETE_ELEM)
            } else {
                compileExpr(m.obj); bytecode.emit(Op.DELETE_PROP, bytecode.strIdx(m.prop))
            }
            return
        }
        compileExpr(e.arg)
        when (e.op) {
            "!" -> bytecode.emit(Op.NOT)
            "-" -> bytecode.emit(Op.NEG)
            "+" -> bytecode.emit(Op.PLUS)
            "~" -> bytecode.emit(Op.BITNOT)
            "typeof" -> bytecode.emit(Op.TYPEOF)
            "void" -> bytecode.emit(Op.VOID_OP)
            "delete" -> { bytecode.emit(Op.POP); bytecode.emit(Op.LOAD_TRUE) }
            else -> error("unknown unary ${e.op}")
        }
    }

    private fun compileUpdate(e: Update) {
        // Reads current, coerces to number, computes new, stores back, leaves old/new on stack.
        fun readAndStore(loadCurrent: () -> Unit, storeNew: () -> Unit) {
            loadCurrent(); bytecode.emit(Op.TO_NUMBER)   // old value (coerced)
            bytecode.emit(Op.DUP)                         // old, old
            bytecode.emit(Op.LOAD_ONE)                   // old, old, 1
            bytecode.emit(if (e.op == "++") Op.ADD else Op.SUB) // old, new
            if (e.prefix) {
                bytecode.emit(Op.DUP); storeNew(); bytecode.emit(Op.POP)  // stack: old, new, new -> new
                bytecode.emit(Op.SWAP); bytecode.emit(Op.POP)              // drop old, keep new
            } else {
                bytecode.emit(Op.DUP); storeNew(); bytecode.emit(Op.POP)  // stack: old, new, new -> new
                bytecode.emit(Op.POP)                                      // drop new, keep old
            }
        }
        when (val t = e.arg) {
            is Ident -> readAndStore({ emitLoadIdent(t.name, e.line) }, { emitStoreIdent(t.name) })
            is Member -> {
                // For member update we need obj/key duplicated; simpler path:
                if (t.computed) {
                    compileExpr(t.obj); compileExpr(t.computedExpr!!)
                    bytecode.emit(Op.DUP)                           // obj, key, key
                    bytecode.emit(Op.SWAP)                           // obj, key, key  (wrong order for DUP2)
                    // We need obj,key,obj,key on stack. Re-do cleanly:
                }
                // Fallback: evaluate through a temp local
                val tmpObj = declareLocal("__upd_obj__")
                compileExpr(t.obj); bytecode.emit(Op.STORE_LOCAL, tmpObj); bytecode.emit(Op.POP)
                val tmpKey = declareLocal("__upd_key__")
                if (t.computed) { compileExpr(t.computedExpr!!) } else { bytecode.emit(Op.LOAD_STR, bytecode.strIdx(t.prop)) }
                bytecode.emit(Op.STORE_LOCAL, tmpKey); bytecode.emit(Op.POP)

                readAndStore(
                    loadCurrent = {
                        bytecode.emit(Op.LOAD_LOCAL, tmpObj); bytecode.emit(Op.LOAD_LOCAL, tmpKey); bytecode.emit(Op.LOAD_ELEM)
                    },
                    storeNew = {
                        // stack top has: new value; we need [obj,key,new]
                        bytecode.emit(Op.LOAD_LOCAL, tmpObj); bytecode.emit(Op.SWAP)
                        bytecode.emit(Op.LOAD_LOCAL, tmpKey); bytecode.emit(Op.SWAP)
                        bytecode.emit(Op.STORE_ELEM)
                    }
                )
            }
            else -> error("invalid update target")
        }
    }

    private fun compileBinary(e: Binary) {
        compileExpr(e.left); compileExpr(e.right)
        val op = when (e.op) {
            "+" -> Op.ADD; "-" -> Op.SUB; "*" -> Op.MUL; "/" -> Op.DIV
            "%" -> Op.MOD; "**" -> Op.POW
            "<" -> Op.LT; "<=" -> Op.LE; ">" -> Op.GT; ">=" -> Op.GE
            "==" -> Op.EQ; "!=" -> Op.NEQ; "===" -> Op.SEQ; "!==" -> Op.SNEQ
            "&" -> Op.BITAND; "|" -> Op.BITOR; "^" -> Op.BITXOR
            "<<" -> Op.SHL; ">>" -> Op.SHR; ">>>" -> Op.USHR
            "instanceof" -> Op.INSTANCEOF; "in" -> Op.IN_OP
            else -> error("unknown binary ${e.op}")
        }
        bytecode.emit(op, 0, 0, e.line)
    }

    private fun compileLogical(e: Logical) {
        compileExpr(e.left)
        val jump = when (e.op) { "||" -> Op.JT_KEEP; "&&" -> Op.JF_KEEP; "??" -> Op.JT_KEEP /* handled at runtime via special? keep simple */ else -> error("bad logical") }
        val jpc = bytecode.emit(jump, -1, 0, e.line)
        compileExpr(e.right)
        bytecode.patchA(jpc, bytecode.size)
    }

    private fun compileAssign(e: Assign) {
        if (e.op == "=") {
            compileExpr(e.value)
            compileAssignTarget(e.target, fromStack = true)
            return
        }
        // compound assignment: target op= rhs  =>  target = target op rhs
        val baseOp = when (e.op) {
            "+=" -> Op.ADD; "-=" -> Op.SUB; "*=" -> Op.MUL; "/=" -> Op.DIV; "%=" -> Op.MOD
            "&=" -> Op.BITAND; "|=" -> Op.BITOR; "^=" -> Op.BITXOR
            "<<=" -> Op.SHL; ">>=" -> Op.SHR; ">>>=" -> Op.USHR
            else -> error("unknown assign ${e.op}")
        }
        when (val t = e.target) {
            is Ident -> {
                emitLoadIdent(t.name, e.line)
                compileExpr(e.value)
                bytecode.emit(baseOp)
                bytecode.emit(Op.DUP); emitStoreIdent(t.name); bytecode.emit(Op.POP)
            }
            is Member -> {
                // stash obj/key in temps
                val tmpObj = declareLocal("__cmp_obj__")
                compileExpr(t.obj); bytecode.emit(Op.STORE_LOCAL, tmpObj); bytecode.emit(Op.POP)
                val tmpKey = declareLocal("__cmp_key__")
                if (t.computed) compileExpr(t.computedExpr!!) else bytecode.emit(Op.LOAD_STR, bytecode.strIdx(t.prop))
                bytecode.emit(Op.STORE_LOCAL, tmpKey); bytecode.emit(Op.POP)
                // load current
                bytecode.emit(Op.LOAD_LOCAL, tmpObj); bytecode.emit(Op.LOAD_LOCAL, tmpKey); bytecode.emit(Op.LOAD_ELEM)
                compileExpr(e.value)
                bytecode.emit(baseOp)
                // store back: need [obj,key,val]
                bytecode.emit(Op.LOAD_LOCAL, tmpObj); bytecode.emit(Op.SWAP)
                bytecode.emit(Op.LOAD_LOCAL, tmpKey); bytecode.emit(Op.SWAP)
                bytecode.emit(Op.STORE_ELEM)
            }
            else -> error("invalid compound assign target")
        }
    }

    /** Expects the value to store on top of the stack (if fromStack). Leaves the value on top. */
    private fun compileAssignTarget(target: Expr, fromStack: Boolean) {
        when (target) {
            is Ident -> { emitStoreIdent(target.name) }
            is Member -> {
                // stack: value
                // rearrange to [obj, key, value]
                if (target.computed) {
                    // Have: value; build obj,key,value
                    compileExpr(target.obj); bytecode.emit(Op.SWAP)                    // obj, value
                    compileExpr(target.computedExpr!!); bytecode.emit(Op.SWAP)         // obj, key, value
                    bytecode.emit(Op.STORE_ELEM)
                } else {
                    compileExpr(target.obj); bytecode.emit(Op.SWAP)                    // obj, value
                    bytecode.emit(Op.STORE_PROP, bytecode.strIdx(target.prop))
                }
            }
            else -> error("invalid assignment target")
        }
    }

    private fun compileMember(e: Member, forCall: Boolean) {
        compileExpr(e.obj)
        if (forCall) bytecode.emit(Op.DUP)  // keep obj as `this`
        if (e.computed) {
            compileExpr(e.computedExpr!!); bytecode.emit(Op.LOAD_ELEM)
        } else {
            bytecode.emit(Op.LOAD_PROP, bytecode.strIdx(e.prop))
        }
    }

    private fun compileCall(e: Call) {
        if (e.callee is Member) {
            // Method call: obj, fn, args... -> CALL_METHOD keeps obj as `this`.
            val m = e.callee
            compileExpr(m.obj); bytecode.emit(Op.DUP)       // obj, obj
            if (m.computed) { compileExpr(m.computedExpr!!); bytecode.emit(Op.LOAD_ELEM) }
            else bytecode.emit(Op.LOAD_PROP, bytecode.strIdx(m.prop))   // obj, fn
            for (a in e.args) compileExpr(a)
            bytecode.emit(Op.CALL_METHOD, e.args.size, 0, e.line)
        } else {
            compileExpr(e.callee)
            for (a in e.args) compileExpr(a)
            bytecode.emit(Op.CALL, e.args.size, 0, e.line)
        }
    }

    private fun compileNew(e: NewExpr) {
        compileExpr(e.callee)
        for (a in e.args) compileExpr(a)
        bytecode.emit(Op.NEW_OP, e.args.size, 0, e.line)
    }
}

/** Back-channel: per-function upvalue table stored alongside the bytecode. */
var Bytecode.upvalueInfo: List<Compiler.Upvalue>
    get() = _upvalues[this] ?: emptyList()
    set(v) { _upvalues[this] = v }

private val _upvalues = java.util.IdentityHashMap<Bytecode, List<Compiler.Upvalue>>()
