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
        fun declareOneVar(name: String) {
            if (isTopLevel) { /* globals live on env */ }
            else if (!scope.locals.containsKey(name)) declareLocal(name)
        }
        fun declareFromPattern(p: Pattern) {
            for (n in collectPatternNames(p)) declareOneVar(n)
        }
        fun declareFromDeclarator(d: Declarator) {
            if (d.name != null) declareOneVar(d.name)
            else if (d.pattern != null) declareFromPattern(d.pattern)
        }
        fun walk(stmts: List<Stmt>) {
            for (s in stmts) when (s) {
                is VarDecl -> if (s.kind == "var") for (d in s.declarators) declareFromDeclarator(d)
                is FunctionDecl -> {
                    if (isTopLevel) { /* handled at emission */ }
                    else if (!scope.locals.containsKey(s.name)) declareLocal(s.name)
                }
                is Block -> walk(s.body)
                is If -> { walk(listOf(s.cons)); s.alt?.let { walk(listOf(it)) } }
                is While -> walk(listOf(s.body))
                is DoWhile -> walk(listOf(s.body))
                is ForC -> {
                    (s.init as? VarDecl)?.let { if (it.kind == "var") for (d in it.declarators) declareFromDeclarator(d) }
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

    private fun compileFunction(name: String, params: List<Param>, body: Block, isArrow: Boolean): Bytecode {
        val bc = Bytecode(name = name, paramCount = params.size, isArrow = isArrow)
        bc.source = bytecode.source
        val c = Compiler(parent = this, bytecode = bc)
        // Reserve slot 0..N-1 for parameters. For simple identifier params, the slot is
        // pre-filled by the VM (`args[i]`). For pattern params, we reserve a slot anyway
        // but overwrite it via the destructuring prelude below.
        for (p in params) {
            val slotName = p.name ?: "__param${c.nextSlot}__"
            c.declareLocal(slotName)
        }
        c.hoistVarAndFunctions(body.body, isTopLevel = false)

        // --- parameter prelude: defaults and destructuring ---
        for ((i, p) in params.withIndex()) {
            when {
                p.rest -> {
                    // Emit:  __paramI__ = Array.prototype.slice.call(arguments, i)
                    //        (simplified: use LOAD_ARGUMENTS then .slice(i))
                    val slot = c.scope.locals[p.name!!]!!
                    bc.emit(Op.LOAD_ARGUMENTS)
                    bc.emit(Op.LOAD_PROP, bc.strIdx("slice"))
                    bc.emit(Op.LOAD_ARGUMENTS)
                    bc.emit(Op.SWAP)                    // stack: args, sliceFn
                    bc.emit(Op.LOAD_INT, i)
                    bc.emit(Op.CALL_METHOD, 1)
                    bc.emit(Op.STORE_LOCAL, slot); bc.emit(Op.POP)
                }
                p.pattern != null -> {
                    // Push the current param value, apply default, bind the pattern.
                    bc.emit(Op.LOAD_ARG, i)
                    if (p.default != null) c.applyDefault(p.default)
                    c.compileBindPattern(p.pattern, kind = "let")
                }
                p.default != null -> {
                    val slot = c.scope.locals[p.name!!]!!
                    // if arg is undefined, initialize with default
                    bc.emit(Op.LOAD_ARG, i)
                    c.applyDefault(p.default)
                    bc.emit(Op.STORE_LOCAL, slot); bc.emit(Op.POP)
                }
                // else: plain identifier param — VM already filled the slot
            }
        }

        for (s in body.body) c.compileStmt(s)
        bc.emit(Op.RET_UNDEF)
        bc.localCount = c.nextSlot
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
            is ClassDecl -> {
                // Build the class value on the stack, then bind to its name in the
                // current scope (local if inside a function, else global).
                compileClass(s)
                val nm = s.name ?: error("class declaration without name")
                if (parent == null) {
                    bytecode.emit(Op.DECL_GLOBAL, bytecode.strIdx(nm))
                } else {
                    val slot = scope.locals[nm] ?: declareLocal(nm)
                    bytecode.emit(Op.STORE_LOCAL, slot); bytecode.emit(Op.POP)
                }
            }
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
            if (d.pattern != null) {
                // Destructuring declaration: push RHS (or undefined), then bind pattern.
                if (d.init != null) compileExpr(d.init) else bytecode.emit(Op.LOAD_UNDEF)
                compileBindPattern(d.pattern, s.kind)
                continue
            }
            val name = d.name!!
            val init: Expr? = d.init
            if (init != null) compileExpr(init)
            else if (isVar) continue  // hoisted; no re-init
            else bytecode.emit(Op.LOAD_UNDEF)

            if (isVar) {
                val existing = resolveLocal(name)
                if (existing != null) {
                    bytecode.emit(Op.STORE_LOCAL, existing); bytecode.emit(Op.POP)
                } else {
                    bytecode.emit(Op.DECL_GLOBAL, bytecode.strIdx(name))
                }
            } else {
                val slot = declareLocal(name, isConst = (s.kind == "const"))
                bytecode.emit(Op.STORE_LOCAL, slot); bytecode.emit(Op.POP)
            }
        }
    }

    /**
     * Bind a destructuring pattern. Consumes one RHS value from the stack.
     * `kind` is "var", "let", "const" — used to decide how bindings are declared.
     * For assignment (no declaration), pass `kind = ""`.
     */
    private fun compileBindPattern(p: Pattern, kind: String) {
        when (p) {
            is IdentPattern -> {
                // stack: value
                if (p.default != null) {
                    // if value === undefined then replace with default
                    applyDefault(p.default)
                }
                bindIdent(p.name, kind)
            }
            is AssignTargetPattern -> {
                if (p.default != null) applyDefault(p.default)
                // assign to arbitrary target; consumes stack top (leaves value, then POP)
                compileAssignTargetStoreTopLeaveValue(p.target)
                bytecode.emit(Op.POP)
            }
            is ArrayPattern -> compileBindArrayPattern(p, kind)
            is ObjectPattern -> compileBindObjectPattern(p, kind)
        }
    }

    /** Stack top: value. Replace with `defaultExpr` iff it's strictly undefined. */
    private fun applyDefault(defaultExpr: Expr) {
        // if value !== undefined goto KEEP
        bytecode.emit(Op.DUP)
        bytecode.emit(Op.LOAD_UNDEF)
        bytecode.emit(Op.SEQ)
        val jf = bytecode.emit(Op.JF, -1)   // if NOT undefined, keep existing value
        // was undefined — drop and replace
        bytecode.emit(Op.POP)
        compileExpr(defaultExpr)
        bytecode.patchA(jf, bytecode.size)
    }

    /** Introduce / store `name` according to `kind` (top of stack: value). */
    private fun bindIdent(name: String, kind: String) {
        when (kind) {
            "var" -> {
                val existing = resolveLocal(name)
                if (existing != null) {
                    bytecode.emit(Op.STORE_LOCAL, existing); bytecode.emit(Op.POP)
                } else {
                    bytecode.emit(Op.DECL_GLOBAL, bytecode.strIdx(name))
                }
            }
            "let", "const" -> {
                val slot = declareLocal(name, isConst = kind == "const")
                bytecode.emit(Op.STORE_LOCAL, slot); bytecode.emit(Op.POP)
            }
            "" -> {
                // Assignment form: store into existing binding (local or global).
                val existing = resolveLocal(name)
                if (existing != null) {
                    bytecode.emit(Op.STORE_LOCAL, existing); bytecode.emit(Op.POP)
                } else {
                    bytecode.emit(Op.STORE_GLOBAL, bytecode.strIdx(name)); bytecode.emit(Op.POP)
                }
            }
        }
    }

    /** Store stack top into `target`; leaves the value on stack. (Caller decides to POP or keep.) */
    private fun compileAssignTargetStoreTopLeaveValue(target: Expr) {
        when (target) {
            is Ident -> {
                val existing = resolveLocal(target.name)
                if (existing != null) bytecode.emit(Op.STORE_LOCAL, existing)
                else bytecode.emit(Op.STORE_GLOBAL, bytecode.strIdx(target.name))
            }
            is Member -> {
                // Need obj on stack beneath value: [value] -> [value, obj, value] via DUP
                // Actually the interpreter expects [obj, value] for STORE_PROP.
                // Strategy: compute obj first separately.
                // stack: value
                compileExpr(target.obj)      // stack: value, obj
                bytecode.emit(Op.SWAP)       // stack: obj, value
                if (!target.computed) {
                    bytecode.emit(Op.STORE_PROP, bytecode.strIdx(target.prop))
                } else {
                    // Need [obj, key, value]; we have [obj, value]. Insert key.
                    // Simpler path: compile key then restore via: obj, value, key → obj, key, value
                    compileExpr(target.computedExpr!!)  // stack: obj, value, key
                    bytecode.emit(Op.SWAP)              // obj, key, value
                    bytecode.emit(Op.STORE_ELEM)
                }
            }
            else -> error("Unsupported assignment target in destructuring: ${target::class.simpleName}")
        }
    }

    private fun compileBindArrayPattern(p: ArrayPattern, kind: String) {
        // Stack: rhs
        // Store rhs in a fresh scratch local so we can index into it multiple times.
        val rhsSlot = declareScratchLocal()
        bytecode.emit(Op.STORE_LOCAL, rhsSlot); bytecode.emit(Op.POP)

        for ((i, el) in p.elements.withIndex()) {
            if (el == null) continue
            // rhs[i]
            bytecode.emit(Op.LOAD_LOCAL, rhsSlot)
            bytecode.emit(Op.LOAD_INT, i)
            bytecode.emit(Op.LOAD_ELEM)
            compileBindPattern(el, kind)
        }

        if (p.rest != null) {
            // rest = rhs.slice(n)
            bytecode.emit(Op.LOAD_LOCAL, rhsSlot)
            bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("slice"))
            bytecode.emit(Op.LOAD_LOCAL, rhsSlot)   // this = rhs
            bytecode.emit(Op.SWAP)                  // stack: rhs, sliceFn
            bytecode.emit(Op.LOAD_INT, p.elements.size)
            bytecode.emit(Op.CALL_METHOD, 1)
            compileBindPattern(p.rest, kind)
        }
    }

    private fun compileBindObjectPattern(p: ObjectPattern, kind: String) {
        // Stack: rhs
        val rhsSlot = declareScratchLocal()
        bytecode.emit(Op.STORE_LOCAL, rhsSlot); bytecode.emit(Op.POP)

        for (prop in p.props) {
            bytecode.emit(Op.LOAD_LOCAL, rhsSlot)
            bytecode.emit(Op.LOAD_PROP, bytecode.strIdx(prop.key))
            compileBindPattern(prop.value, kind)
        }

        if (p.rest != null) {
            // rest = { ...rhs minus consumed keys }
            // Emit: Object.assign({}, rhs) then delete consumed keys
            bytecode.emit(Op.LOAD_GLOBAL, bytecode.strIdx("Object"))
            bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("assign"))
            // Stack: assignFn
            // Need: assignFn(dst, rhs)
            // MAKE_OBJECT with 0 pairs = {}
            bytecode.emit(Op.MAKE_OBJECT, 0)       // stack: assignFn, {}
            bytecode.emit(Op.LOAD_LOCAL, rhsSlot)  // stack: assignFn, {}, rhs
            bytecode.emit(Op.CALL, 2)              // stack: dst
            // Now delete consumed keys from dst
            for (prop in p.props) {
                bytecode.emit(Op.DUP)
                bytecode.emit(Op.DELETE_PROP, bytecode.strIdx(prop.key))
                bytecode.emit(Op.POP)
            }
            compileBindPattern(p.rest, kind)
        }
    }

    private fun declareScratchLocal(): Int {
        // Anonymous slot — name it using a counter so it never clashes with user locals.
        val name = "__destructScratch__${nextSlot}__"
        return declareLocal(name, isConst = false)
    }

    /** Collect all identifier binding names from a pattern. Used by hoisting. */
    private fun collectPatternNames(p: Pattern): List<String> {
        val out = mutableListOf<String>()
        fun walk(pat: Pattern) {
            when (pat) {
                is IdentPattern -> out.add(pat.name)
                is ArrayPattern -> {
                    for (el in pat.elements) if (el != null) walk(el)
                    pat.rest?.let { walk(it) }
                }
                is ObjectPattern -> {
                    for (pp in pat.props) walk(pp.value)
                    pat.rest?.let { walk(it) }
                }
                is AssignTargetPattern -> { /* target is already bound elsewhere */ }
            }
        }
        walk(p)
        return out
    }

    // ---------- class support ----------

    /** Stack entry tracking the innermost-enclosing class's super-binding name (null if no super). */
    private data class ClassCtx(val superVarName: String?)
    private val classStack = ArrayDeque<ClassCtx>()
    private fun findClassCtx(): ClassCtx? {
        // Walk up parent compilers too, so nested functions inside class methods still resolve super.
        var c: Compiler? = this
        while (c != null) {
            if (c.classStack.isNotEmpty()) return c.classStack.last()
            c = c.parent
        }
        return null
    }

    private var classUid = 0
    private fun freshSuperName(): String = "__super${classUid++}__"

    /** Emit bytecode that evaluates the class and leaves the constructor on the stack. */
    private fun compileClass(decl: ClassDecl) {
        // 1) evaluate super expression into a local slot (or push null if no super).
        val superVarName: String? = if (decl.superClass != null) freshSuperName() else null
        if (superVarName != null) {
            compileExpr(decl.superClass!!)
            val slot = declareLocal(superVarName, isConst = true)
            bytecode.emit(Op.STORE_LOCAL, slot); bytecode.emit(Op.POP)
        }

        // 2) build constructor function. If the user wrote one use their body; otherwise
        //    synthesize a default:
        //      - subclass: super(...args);
        //      - base:     (empty)
        val instanceFields = decl.members.filter { !it.isStatic && it.kind == MemberKind.FIELD }
        val ctor = buildClassCtorFunction(decl, instanceFields)

        // Emit MAKE_CLOSURE for ctor. Push classCtx so any SuperCall/SuperMember in this
        // or nested function bodies finds the super name.
        classStack.addLast(ClassCtx(superVarName))
        try {
            val ctorBc = compileFunction(decl.name ?: "", ctor.params, ctor.body, isArrow = false)
            bytecode.emit(Op.MAKE_CLOSURE, bytecode.fnIdx(ctorBc))
        } finally {
            classStack.removeLast()
        }

        // Stack: [ctor]
        // Stash ctor in a scratch local for repeated use below.
        val ctorSlot = declareScratchLocal()
        bytecode.emit(Op.STORE_LOCAL, ctorSlot); bytecode.emit(Op.POP)

        // 3) ctor.prototype = Object.create(super ? super.prototype : Object.prototype)
        //    ctor.prototype.constructor = ctor
        bytecode.emit(Op.LOAD_LOCAL, ctorSlot)
        bytecode.emit(Op.LOAD_GLOBAL, bytecode.strIdx("Object"))
        bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("create"))
        if (superVarName != null) {
            emitLoadIdent(superVarName, decl.line)
            bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("prototype"))
        } else {
            bytecode.emit(Op.LOAD_GLOBAL, bytecode.strIdx("Object"))
            bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("prototype"))
        }
        bytecode.emit(Op.CALL, 1)                 // stack: ctor, newProto
        bytecode.emit(Op.STORE_PROP, bytecode.strIdx("prototype"))
        bytecode.emit(Op.POP)                      // discard newProto leftover
        // ctor.prototype.constructor = ctor
        bytecode.emit(Op.LOAD_LOCAL, ctorSlot)
        bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("prototype"))
        bytecode.emit(Op.LOAD_LOCAL, ctorSlot)
        bytecode.emit(Op.STORE_PROP, bytecode.strIdx("constructor"))
        bytecode.emit(Op.POP)

        // If super, inherit static members:  Object.setPrototypeOf(ctor, super)
        if (superVarName != null) {
            bytecode.emit(Op.LOAD_GLOBAL, bytecode.strIdx("Object"))
            bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("setPrototypeOf"))
            bytecode.emit(Op.LOAD_LOCAL, ctorSlot)
            emitLoadIdent(superVarName, decl.line)
            bytecode.emit(Op.CALL, 2)
            bytecode.emit(Op.POP)
        }

        // 4) methods / accessors / static members
        classStack.addLast(ClassCtx(superVarName))
        try {
            for (m in decl.members) {
                when (m.kind) {
                    MemberKind.METHOD -> {
                        // target = isStatic ? ctor : ctor.prototype
                        if (m.isStatic) bytecode.emit(Op.LOAD_LOCAL, ctorSlot)
                        else {
                            bytecode.emit(Op.LOAD_LOCAL, ctorSlot)
                            bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("prototype"))
                        }
                        val fn = m.func!!
                        val fnBc = compileFunction(m.name, fn.params, fn.body, isArrow = false)
                        bytecode.emit(Op.MAKE_CLOSURE, bytecode.fnIdx(fnBc))
                        bytecode.emit(Op.STORE_PROP, bytecode.strIdx(m.name))
                        bytecode.emit(Op.POP)
                    }
                    MemberKind.FIELD -> {
                        if (m.isStatic) {
                            bytecode.emit(Op.LOAD_LOCAL, ctorSlot)
                            if (m.fieldInit != null) compileExpr(m.fieldInit) else bytecode.emit(Op.LOAD_UNDEF)
                            bytecode.emit(Op.STORE_PROP, bytecode.strIdx(m.name))
                            bytecode.emit(Op.POP)
                        } else {
                            // instance field: handled inside ctor prelude (buildClassCtorFunction).
                        }
                    }
                    MemberKind.GETTER, MemberKind.SETTER -> {
                        // Simplified: store getter/setter as a regular method; a proper
                        // Object.defineProperty-based accessor would be the spec path.
                        if (m.isStatic) bytecode.emit(Op.LOAD_LOCAL, ctorSlot)
                        else {
                            bytecode.emit(Op.LOAD_LOCAL, ctorSlot)
                            bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("prototype"))
                        }
                        val fn = m.func!!
                        val fnBc = compileFunction(m.name, fn.params, fn.body, isArrow = false)
                        bytecode.emit(Op.MAKE_CLOSURE, bytecode.fnIdx(fnBc))
                        bytecode.emit(Op.STORE_PROP, bytecode.strIdx(m.name))
                        bytecode.emit(Op.POP)
                    }
                }
            }
        } finally {
            classStack.removeLast()
        }

        // 5) Leave ctor on the stack as the expression's value.
        bytecode.emit(Op.LOAD_LOCAL, ctorSlot)
    }

    /**
     * Construct a FunctionExpr-shape struct for the class's constructor.
     * Instance fields (if any) are prepended as `this.x = init;` statements.
     */
    private fun buildClassCtorFunction(decl: ClassDecl, instanceFields: List<ClassMember>): FunctionExpr {
        val stmts = mutableListOf<Stmt>()
        // Instance field initializations: `this.name = init;` at top of body, *after* any
        // explicit super() call if present.  For simplicity we place them at the start; user
        // ctor body comes after.
        for (f in instanceFields) {
            val target = Member(ThisExpr, f.name, false)
            val init = f.fieldInit ?: UndefinedLit
            stmts.add(ExprStmt(Assign("=", target, init)))
        }
        if (decl.constructor != null) {
            val fn = decl.constructor.func!!
            stmts.addAll(fn.body.body)
            return FunctionExpr(decl.constructor.name, fn.params, Block(stmts))
        }
        // Synthesize default constructor.
        val params: List<Param>
        if (decl.superClass != null) {
            // Default for a subclass:  constructor(...args) { super(...args); }
            params = listOf(Param("args", null, null, rest = true))
            val argsIdent = Ident("args")
            val spread = Unary("...", argsIdent, true)
            stmts.add(ExprStmt(SuperCall(listOf(spread))))
        } else {
            params = emptyList()
        }
        return FunctionExpr(decl.name ?: "", params, Block(stmts))
    }

    /** `super.prop` → currentSuperVar.prototype.prop */
    private fun compileSuperMember(e: SuperMember) {
        val ctx = findClassCtx() ?: error("'super' used outside of a class")
        val sn = ctx.superVarName ?: error("'super' used in a class without 'extends'")
        emitLoadIdent(sn, e.line)
        bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("prototype"))
        if (e.computed) {
            compileExpr(e.computedExpr!!)
            bytecode.emit(Op.LOAD_ELEM)
        } else {
            bytecode.emit(Op.LOAD_PROP, bytecode.strIdx(e.prop))
        }
    }

    /** `super(...args)` → superVar.call(this, ...args); leaves undefined on stack. */
    private fun compileSuperCall(e: SuperCall) {
        val ctx = findClassCtx() ?: error("'super()' used outside of a class")
        val sn = ctx.superVarName ?: error("'super()' used in a class without 'extends'")

        val hasSpread = e.args.any { it is Unary && it.op == "..." }
        if (!hasSpread) {
            // Emit: superVar.call(this, arg0, arg1, ...)
            emitLoadIdent(sn, e.line)
            bytecode.emit(Op.DUP)
            bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("call"))
            bytecode.emit(Op.GET_THIS)
            for (a in e.args) compileExpr(a)
            bytecode.emit(Op.CALL_METHOD, 1 + e.args.size)
        } else {
            // superVar.apply(this, argsArr)
            emitLoadIdent(sn, e.line)
            bytecode.emit(Op.DUP)
            bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("apply"))
            bytecode.emit(Op.GET_THIS)
            emitBuildArgsArray(e.args)
            bytecode.emit(Op.CALL_METHOD, 2)
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
            is BigIntLit -> bytecode.emit(Op.LOAD_CONST, bytecode.constIdx(e.value), 0, e.line)
            is StringLit -> bytecode.emit(Op.LOAD_STR, bytecode.strIdx(e.value), 0, e.line)
            is TemplateLit -> bytecode.emit(Op.LOAD_STR, bytecode.strIdx(e.raw), 0, e.line)
            is BoolLit -> bytecode.emit(if (e.value) Op.LOAD_TRUE else Op.LOAD_FALSE)
            NullLit -> bytecode.emit(Op.LOAD_NULL)
            UndefinedLit -> bytecode.emit(Op.LOAD_UNDEF)
            ThisExpr -> bytecode.emit(Op.GET_THIS)
            is Ident -> emitLoadIdent(e.name, e.line)
            is ArrayLit -> {
                val hasSpread = e.elements.any { it is Unary && it.op == "..." }
                if (!hasSpread) {
                    e.elements.forEach { if (it != null) compileExpr(it) else bytecode.emit(Op.LOAD_UNDEF) }
                    bytecode.emit(Op.MAKE_ARRAY, e.elements.size)
                } else {
                    // Build the array element-by-element, handling spreads via concat.
                    emitBuildArgsArray(e.elements.map { it ?: UndefinedLit })
                }
            }
            is ObjectLit -> {
                val hasSpread = e.props.any { it.first == "__rest__" }
                if (!hasSpread) {
                    for ((k, v) in e.props) { bytecode.emit(Op.LOAD_STR, bytecode.strIdx(k)); compileExpr(v) }
                    bytecode.emit(Op.MAKE_OBJECT, e.props.size)
                } else {
                    // Build via: let o = {}; Object.assign(o, src) for each spread; set own for each regular prop.
                    bytecode.emit(Op.MAKE_OBJECT, 0)
                    val slot = declareScratchLocal()
                    bytecode.emit(Op.STORE_LOCAL, slot); bytecode.emit(Op.POP)
                    for ((k, v) in e.props) {
                        if (k == "__rest__") {
                            // Object.assign(o, src)
                            val src = (v as Unary).arg
                            bytecode.emit(Op.LOAD_GLOBAL, bytecode.strIdx("Object"))
                            bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("assign"))
                            bytecode.emit(Op.LOAD_LOCAL, slot)
                            compileExpr(src)
                            bytecode.emit(Op.CALL, 2)
                            bytecode.emit(Op.POP)  // discard returned 'o' (same as slot)
                        } else {
                            // o[k] = v
                            bytecode.emit(Op.LOAD_LOCAL, slot)
                            compileExpr(v)
                            bytecode.emit(Op.STORE_PROP, bytecode.strIdx(k))
                            bytecode.emit(Op.POP)
                        }
                    }
                    bytecode.emit(Op.LOAD_LOCAL, slot)
                }
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
            is DestructuringAssign -> {
                // Evaluate RHS, DUP it (destructuring leaves it on the stack as the
                // expression's value), then bind into the pattern.
                compileExpr(e.value)
                bytecode.emit(Op.DUP)
                compileBindPattern(e.pattern, kind = "")
                // After compileBindPattern, the consumed RHS copy is gone; the
                // original DUP'd value remains as the expression result.
            }
            is ClassExpr -> compileClass(e.decl)
            is SuperMember -> compileSuperMember(e)
            is SuperCall -> compileSuperCall(e)
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
        val hasSpread = e.args.any { it is Unary && it.op == "..." }
        if (e.callee is SuperMember) {
            // super.m(args) — must call with `this` bound to the current instance.
            // Emit stack [obj=this, fn=_super.prototype[prop]] and CALL_METHOD.
            val ctx = findClassCtx() ?: error("'super' used outside a class")
            val sn = ctx.superVarName ?: error("'super' in a class without 'extends'")
            val m = e.callee
            bytecode.emit(Op.GET_THIS)                              // this
            emitLoadIdent(sn, e.line)
            bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("prototype"))
            if (m.computed) { compileExpr(m.computedExpr!!); bytecode.emit(Op.LOAD_ELEM) }
            else bytecode.emit(Op.LOAD_PROP, bytecode.strIdx(m.prop))
            // Stack: this, fn
            if (!hasSpread) {
                for (a in e.args) compileExpr(a)
                bytecode.emit(Op.CALL_METHOD, e.args.size, 0, e.line)
            } else {
                // Build args array and use fn.apply(this, argsArr). Need extra scratch.
                val fnSlot = declareScratchLocal()
                bytecode.emit(Op.STORE_LOCAL, fnSlot); bytecode.emit(Op.POP)   // stack: this
                val thisSlot = declareScratchLocal()
                bytecode.emit(Op.STORE_LOCAL, thisSlot); bytecode.emit(Op.POP) // stack: (empty)
                emitBuildArgsArray(e.args)
                val argsSlot = declareScratchLocal()
                bytecode.emit(Op.STORE_LOCAL, argsSlot); bytecode.emit(Op.POP)
                bytecode.emit(Op.LOAD_LOCAL, fnSlot)
                bytecode.emit(Op.DUP)
                bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("apply"))
                bytecode.emit(Op.LOAD_LOCAL, thisSlot)
                bytecode.emit(Op.LOAD_LOCAL, argsSlot)
                bytecode.emit(Op.CALL_METHOD, 2, 0, e.line)
            }
            return
        }
        if (e.callee is Member) {
            val m = e.callee
            compileExpr(m.obj); bytecode.emit(Op.DUP)       // obj, obj
            if (m.computed) { compileExpr(m.computedExpr!!); bytecode.emit(Op.LOAD_ELEM) }
            else bytecode.emit(Op.LOAD_PROP, bytecode.strIdx(m.prop))   // obj, fn
            if (hasSpread) {
                // Stack: obj, fn.  Need: fn.apply(obj, argsArr).
                val fnSlot = declareScratchLocal()
                bytecode.emit(Op.STORE_LOCAL, fnSlot); bytecode.emit(Op.POP)  // obj
                val objSlot = declareScratchLocal()
                bytecode.emit(Op.STORE_LOCAL, objSlot); bytecode.emit(Op.POP) // <empty>
                emitBuildArgsArray(e.args)
                val argsSlot = declareScratchLocal()
                bytecode.emit(Op.STORE_LOCAL, argsSlot); bytecode.emit(Op.POP)
                bytecode.emit(Op.LOAD_LOCAL, fnSlot); bytecode.emit(Op.DUP)
                bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("apply"))
                bytecode.emit(Op.LOAD_LOCAL, objSlot)
                bytecode.emit(Op.LOAD_LOCAL, argsSlot)
                bytecode.emit(Op.CALL_METHOD, 2, 0, e.line)
            } else {
                for (a in e.args) compileExpr(a)
                bytecode.emit(Op.CALL_METHOD, e.args.size, 0, e.line)
            }
        } else {
            compileExpr(e.callee)
            if (hasSpread) {
                val fnSlot = declareScratchLocal()
                bytecode.emit(Op.STORE_LOCAL, fnSlot); bytecode.emit(Op.POP)
                emitBuildArgsArray(e.args)
                val argsSlot = declareScratchLocal()
                bytecode.emit(Op.STORE_LOCAL, argsSlot); bytecode.emit(Op.POP)
                bytecode.emit(Op.LOAD_LOCAL, fnSlot); bytecode.emit(Op.DUP)
                bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("apply"))
                bytecode.emit(Op.LOAD_UNDEF)
                bytecode.emit(Op.LOAD_LOCAL, argsSlot)
                bytecode.emit(Op.CALL_METHOD, 2, 0, e.line)
            } else {
                for (a in e.args) compileExpr(a)
                bytecode.emit(Op.CALL, e.args.size, 0, e.line)
            }
        }
    }

    /**
     * Build an Array out of the argument list, handling any `...expr` spread entries.
     * Emits bytecode that leaves the resulting JsArray on top of the stack.
     *
     * Strategy: store the in-progress array into a scratch local, then for each arg
     * either `arr.push(value)` or `arr = arr.concat(spread)`.
     */
    private fun emitBuildArgsArray(args: List<Expr>) {
        val arrSlot = declareScratchLocal()
        bytecode.emit(Op.MAKE_ARRAY, 0)
        bytecode.emit(Op.STORE_LOCAL, arrSlot); bytecode.emit(Op.POP)
        for (a in args) {
            if (a is Unary && a.op == "...") {
                // arr = arr.concat(iter)
                bytecode.emit(Op.LOAD_LOCAL, arrSlot)
                bytecode.emit(Op.DUP)
                bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("concat"))
                compileExpr(a.arg)
                bytecode.emit(Op.CALL_METHOD, 1)
                bytecode.emit(Op.STORE_LOCAL, arrSlot); bytecode.emit(Op.POP)
            } else {
                // arr.push(value)
                bytecode.emit(Op.LOAD_LOCAL, arrSlot)
                bytecode.emit(Op.DUP)
                bytecode.emit(Op.LOAD_PROP, bytecode.strIdx("push"))
                compileExpr(a)
                bytecode.emit(Op.CALL_METHOD, 1)
                bytecode.emit(Op.POP)   // discard push's return (length)
            }
        }
        bytecode.emit(Op.LOAD_LOCAL, arrSlot)
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
