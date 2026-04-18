package io.kjs.runtime

import io.kjs.parse.*

/** Thrown by `throw` statements; carries a JS value. */
class JsThrown(val value: Any?) : RuntimeException(JsValues.toStr(value))

private class BreakSignal(val label: String?) : RuntimeException() { override fun fillInStackTrace() = this }
private class ContinueSignal(val label: String?) : RuntimeException() { override fun fillInStackTrace() = this }
private class ReturnSignal(val value: Any?) : RuntimeException() { override fun fillInStackTrace() = this }

/**
 * Tree-walking interpreter. Used as the M1 execution engine and as an oracle
 * against which the bytecode VM will be validated in M2.
 */
class Interpreter(val realm: Realm) {

    fun exec(program: Program): Any? {
        hoist(realm.globalEnv, program.body)
        var result: Any? = JsValues.UNDEFINED
        for (s in program.body) result = execStmt(s, realm.globalEnv) ?: result
        return result
    }

    /** Build a user JS function with the canonical wiring:
     *  - proto = Function.prototype
     *  - own `prototype` = fresh object (whose own `constructor` points back to the fn)
     *  - arrow fns skip the `prototype` slot and are marked with `__arrow__`.
     */
    fun mkUserFunction(name: String, params: List<io.kjs.parse.Param>, body: Block, env: Environment, isArrow: Boolean): JsFunction {
        // Walker backend supports simple identifier params only. Complex patterns /
        // defaults are handled by the bytecode VM.
        val flatNames = params.map { it.name ?: "__pat${params.indexOf(it)}" }
        val fn = JsFunction.user(name, flatNames, body, env)
        fn.invoker = ::callUserFn
        fn.proto = realm.functionProto
        fn.set("name", name)
        fn.set("length", params.size.toDouble())
        if (isArrow) {
            fn.set("__arrow__", true)
        } else {
            val protoObj = JsObject(realm.objectProto)
            protoObj.set("constructor", fn)
            fn.set("prototype", protoObj)
        }
        return fn
    }

    // --- hoisting (var declarations + function decls) ---
    private fun hoist(env: Environment, body: List<Stmt>) {
        for (s in body) when (s) {
            is FunctionDecl -> {
                val fn = mkUserFunction(s.name, s.params, s.body, env, isArrow = false)
                env.declare(s.name, fn)
            }
            is VarDecl -> if (s.kind == "var") for (d in s.declarators) {
                val name = d.name ?: continue   // destructuring: only bytecode VM supports it
                if (!env.has(name)) env.declare(name, JsValues.UNDEFINED)
            }
            is If -> { hoist(env, listOf(s.cons)); s.alt?.let { hoist(env, listOf(it)) } }
            is Block -> hoist(env, s.body)
            is While -> hoist(env, listOf(s.body))
            is DoWhile -> hoist(env, listOf(s.body))
            is ForC -> hoist(env, listOf(s.body))
            is ForIn -> hoist(env, listOf(s.body))
            is ForOf -> hoist(env, listOf(s.body))
            is Try -> { hoist(env, s.block.body); s.catchBody?.let { hoist(env, it.body) }; s.finallyBody?.let { hoist(env, it.body) } }
            else -> {}
        }
    }

    // --- statements ---
    private fun execStmt(s: Stmt, env: Environment): Any? {
        return when (s) {
            is Block -> {
                val blockEnv = Environment(env)
                hoist(blockEnv, s.body)
                var last: Any? = null
                for (st in s.body) {
                    val r = execStmt(st, blockEnv)
                    if (r != null) last = r
                }
                last
            }
            is ExprStmt -> evalExpr(s.expr, env)
            is VarDecl -> {
                for (d in s.declarators) {
                    val name = d.name
                        ?: throw JsThrown("destructuring declarations only supported on the bytecode VM")
                    val v = if (d.init != null) evalExpr(d.init, env) else JsValues.UNDEFINED
                    if (s.kind == "var") {
                        if (!env.has(name)) env.declare(name, v) else env.set(name, v)
                    } else env.declare(name, v)
                }
                null
            }
            is If -> if (JsValues.toBool(evalExpr(s.test, env))) execStmt(s.cons, env)
                    else s.alt?.let { execStmt(it, env) }
            is While -> {
                whileLoop@ while (JsValues.toBool(evalExpr(s.test, env))) {
                    try { execStmt(s.body, env) }
                    catch (e: BreakSignal) { if (e.label != null) throw e; break@whileLoop }
                    catch (e: ContinueSignal) { if (e.label != null) throw e /* next iteration */ }
                }
                null
            }
            is DoWhile -> {
                doLoop@ do {
                    try { execStmt(s.body, env) }
                    catch (e: BreakSignal) { if (e.label != null) throw e; break@doLoop }
                    catch (e: ContinueSignal) { if (e.label != null) throw e /* continue to condition */ }
                } while (JsValues.toBool(evalExpr(s.test, env)))
                null
            }
            is ForC -> execForC(s, env)
            is ForIn -> execForIn(s, env)
            is ForOf -> execForOf(s, env)
            is Return -> throw ReturnSignal(s.arg?.let { evalExpr(it, env) } ?: JsValues.UNDEFINED)
            is Break -> throw BreakSignal(s.label)
            is Continue -> throw ContinueSignal(s.label)
            is Throw -> throw JsThrown(evalExpr(s.arg, env))
            is Try -> execTry(s, env)
            is FunctionDecl -> null // already hoisted
            is Labeled -> {
                try { execStmt(s.body, env) }
                catch (e: BreakSignal) { if (e.label != s.label) throw e }
                null
            }
            is EmptyStmt -> null
        }
    }

    private fun execForC(s: ForC, env: Environment): Any? {
        val loopEnv = Environment(env)
        when (val i = s.init) {
            is VarDecl -> execStmt(i, loopEnv)
            is ExprStmt -> evalExpr(i.expr, loopEnv)
            null -> {}
            else -> {}
        }
        while (s.test == null || JsValues.toBool(evalExpr(s.test, loopEnv))) {
            try { execStmt(s.body, loopEnv) } catch (e: BreakSignal) { if (e.label != null) throw e; return null } catch (e: ContinueSignal) { if (e.label != null) throw e }
            if (s.update != null) evalExpr(s.update, loopEnv)
        }
        return null
    }

    private fun execForIn(s: ForIn, env: Environment): Any? {
        val iterEnv = Environment(env)
        val obj = evalExpr(s.right, iterEnv)
        if (obj !is JsObject) return null
        val leftAsIdent = s.left as? Ident
        if (s.leftKind != null && leftAsIdent != null) iterEnv.declare(leftAsIdent.name, JsValues.UNDEFINED)
        for (k in obj.keys()) {
            assignTarget(s.left, k, iterEnv, declareKind = s.leftKind)
            try { execStmt(s.body, iterEnv) } catch (e: BreakSignal) { if (e.label != null) throw e; return null } catch (e: ContinueSignal) { if (e.label != null) throw e }
        }
        return null
    }

    private fun execForOf(s: ForOf, env: Environment): Any? {
        val iterEnv = Environment(env)
        val rhs = evalExpr(s.right, iterEnv)
        val leftAsIdent = s.left as? Ident
        if (s.leftKind != null && leftAsIdent != null) iterEnv.declare(leftAsIdent.name, JsValues.UNDEFINED)
        val items: List<Any?> = when (rhs) {
            is String -> rhs.map { it.toString() }
            is JsArray -> (0 until rhs.length).map { rhs.get(it.toString()) }
            is JsObject -> rhs.keys().map { rhs.get(it) }
            else -> emptyList()
        }
        for (v in items) {
            assignTarget(s.left, v, iterEnv, declareKind = s.leftKind)
            try { execStmt(s.body, iterEnv) } catch (e: BreakSignal) { if (e.label != null) throw e; return null } catch (e: ContinueSignal) { if (e.label != null) throw e }
        }
        return null
    }

    private fun execTry(s: Try, env: Environment): Any? {
        var result: Any? = null
        try {
            result = execStmt(s.block, env)
        } catch (e: JsThrown) {
            if (s.catchBody != null) {
                val cenv = Environment(env)
                if (s.catchParam != null) cenv.declare(s.catchParam, e.value)
                result = execStmt(s.catchBody, cenv)
            } else if (s.finallyBody == null) throw e
        } finally {
            if (s.finallyBody != null) execStmt(s.finallyBody, env)
        }
        return result
    }

    // --- expressions ---
    fun evalExpr(e: Expr, env: Environment): Any? = when (e) {
        is NumberLit -> e.value
        is StringLit -> e.value
        is BoolLit -> e.value
        NullLit -> null
        UndefinedLit -> JsValues.UNDEFINED
        ThisExpr -> env.get("this")
        is Ident -> {
            if (!env.has(e.name)) throw JsThrown("ReferenceError: ${e.name} is not defined")
            env.get(e.name)
        }
        is ArrayLit -> JsArray().apply {
            proto = realm.arrayProto
            e.elements.forEach { el -> push(el?.let { evalExpr(it, env) } ?: JsValues.UNDEFINED) }
        }
        is ObjectLit -> {
            val o = JsObject(realm.objectProto)
            for ((k, v) in e.props) o.set(k, evalExpr(v, env))
            o
        }
        is FunctionExpr -> mkUserFunction(e.name ?: "", e.params, e.body, env, isArrow = false)
        is ArrowFn -> {
            val body = e.body
            val block: Block = if (body is Block) body
                else Block(listOf(Return(body as Expr)))
            mkUserFunction("", e.params, block, env, isArrow = true)
        }
        is Unary -> evalUnary(e, env)
        is Update -> evalUpdate(e, env)
        is Binary -> evalBinary(e, env)
        is Logical -> evalLogical(e, env)
        is Assign -> evalAssign(e, env)
        is Conditional -> if (JsValues.toBool(evalExpr(e.test, env))) evalExpr(e.cons, env) else evalExpr(e.alt, env)
        is Member -> evalMember(e, env).second
        is Call -> evalCall(e, env)
        is NewExpr -> evalNew(e, env)
        is Sequence -> { var last: Any? = JsValues.UNDEFINED; for (it in e.items) last = evalExpr(it, env); last }
        is TemplateLit -> e.raw
        is DestructuringAssign -> throw JsThrown("destructuring assignment only supported on the bytecode VM")
    }

    private fun evalUnary(e: Unary, env: Environment): Any? = when (e.op) {
        "!" -> !JsValues.toBool(evalExpr(e.arg, env))
        "-" -> -JsValues.toNumber(evalExpr(e.arg, env))
        "+" -> JsValues.toNumber(evalExpr(e.arg, env))
        "~" -> (JsValues.toInt32(evalExpr(e.arg, env)).inv()).toDouble()
        "typeof" -> {
            val argExpr = e.arg
            val v = if (argExpr is Ident && !env.has(argExpr.name)) JsValues.UNDEFINED else evalExpr(argExpr, env)
            JsValues.typeOf(v)
        }
        "void" -> { evalExpr(e.arg, env); JsValues.UNDEFINED }
        "delete" -> if (e.arg is Member) {
            val (obj, _) = evalMember(e.arg, env)
            val k = memberKey(e.arg, env)
            (obj as? JsObject)?.delete(k) ?: true
        } else true
        else -> throw IllegalStateException("Unknown unary ${e.op}")
    }

    private fun evalUpdate(e: Update, env: Environment): Any? {
        val old = JsValues.toNumber(evalExpr(e.arg, env))
        val new = if (e.op == "++") old + 1 else old - 1
        assignTarget(e.arg, new, env)
        return if (e.prefix) new else old
    }

    private fun evalBinary(e: Binary, env: Environment): Any? {
        val l = evalExpr(e.left, env); val r = evalExpr(e.right, env)
        return when (e.op) {
            "+" -> if (l is String || r is String) JsValues.toStr(l) + JsValues.toStr(r) else JsValues.toNumber(l) + JsValues.toNumber(r)
            "-" -> JsValues.toNumber(l) - JsValues.toNumber(r)
            "*" -> JsValues.toNumber(l) * JsValues.toNumber(r)
            "/" -> JsValues.toNumber(l) / JsValues.toNumber(r)
            "%" -> JsValues.toNumber(l) % JsValues.toNumber(r)
            "**" -> Math.pow(JsValues.toNumber(l), JsValues.toNumber(r))
            "<" -> numericCompare(l, r) { a, b -> a < b }
            "<=" -> numericCompare(l, r) { a, b -> a <= b }
            ">" -> numericCompare(l, r) { a, b -> a > b }
            ">=" -> numericCompare(l, r) { a, b -> a >= b }
            "==" -> JsValues.looseEq(l, r)
            "!=" -> !JsValues.looseEq(l, r)
            "===" -> JsValues.strictEq(l, r)
            "!==" -> !JsValues.strictEq(l, r)
            "&" -> (JsValues.toInt32(l) and JsValues.toInt32(r)).toDouble()
            "|" -> (JsValues.toInt32(l) or JsValues.toInt32(r)).toDouble()
            "^" -> (JsValues.toInt32(l) xor JsValues.toInt32(r)).toDouble()
            "<<" -> (JsValues.toInt32(l) shl (JsValues.toInt32(r) and 31)).toDouble()
            ">>" -> (JsValues.toInt32(l) shr (JsValues.toInt32(r) and 31)).toDouble()
            ">>>" -> (JsValues.toUint32(l) ushr (JsValues.toInt32(r) and 31)).toDouble()
            "in" -> (r as? JsObject)?.has(JsValues.toStr(l)) ?: false
            "instanceof" -> l is JsObject && r is JsFunction && protoChainContains(l, r.get("prototype"))
            else -> throw IllegalStateException("Unknown binary ${e.op}")
        }
    }

    private fun protoChainContains(o: JsObject, proto: Any?): Boolean {
        if (proto !is JsObject) return false
        var p = o.proto
        while (p != null) { if (p === proto) return true; p = p.proto }
        return false
    }

    private fun numericCompare(a: Any?, b: Any?, cmp: (Double, Double) -> Boolean): Boolean {
        if (a is String && b is String) {
            val c = a.compareTo(b)
            return cmp(c.toDouble(), 0.0)
        }
        val na = JsValues.toNumber(a); val nb = JsValues.toNumber(b)
        if (na.isNaN() || nb.isNaN()) return false
        return cmp(na, nb)
    }

    private fun evalLogical(e: Logical, env: Environment): Any? {
        val l = evalExpr(e.left, env)
        return when (e.op) {
            "&&" -> if (!JsValues.toBool(l)) l else evalExpr(e.right, env)
            "||" -> if (JsValues.toBool(l)) l else evalExpr(e.right, env)
            "??" -> if (l == null || l == JsValues.UNDEFINED) evalExpr(e.right, env) else l
            else -> throw IllegalStateException("Unknown logical ${e.op}")
        }
    }

    private fun evalAssign(e: Assign, env: Environment): Any? {
        val newValue = if (e.op == "=") evalExpr(e.value, env) else {
            val cur = evalExpr(e.target, env)
            val rhs = evalExpr(e.value, env)
            when (e.op) {
                "+=" -> if (cur is String || rhs is String) JsValues.toStr(cur) + JsValues.toStr(rhs) else JsValues.toNumber(cur) + JsValues.toNumber(rhs)
                "-=" -> JsValues.toNumber(cur) - JsValues.toNumber(rhs)
                "*=" -> JsValues.toNumber(cur) * JsValues.toNumber(rhs)
                "/=" -> JsValues.toNumber(cur) / JsValues.toNumber(rhs)
                "%=" -> JsValues.toNumber(cur) % JsValues.toNumber(rhs)
                "&=" -> (JsValues.toInt32(cur) and JsValues.toInt32(rhs)).toDouble()
                "|=" -> (JsValues.toInt32(cur) or JsValues.toInt32(rhs)).toDouble()
                "^=" -> (JsValues.toInt32(cur) xor JsValues.toInt32(rhs)).toDouble()
                "<<=" -> (JsValues.toInt32(cur) shl (JsValues.toInt32(rhs) and 31)).toDouble()
                ">>=" -> (JsValues.toInt32(cur) shr (JsValues.toInt32(rhs) and 31)).toDouble()
                ">>>=" -> (JsValues.toUint32(cur) ushr (JsValues.toInt32(rhs) and 31)).toDouble()
                else -> throw IllegalStateException("Unknown op ${e.op}")
            }
        }
        assignTarget(e.target, newValue, env)
        return newValue
    }

    private fun assignTarget(target: Expr, value: Any?, env: Environment, declareKind: String? = null) {
        when (target) {
            is Ident -> if (declareKind != null) env.declare(target.name, value) else env.setOrDeclareGlobal(target.name, value)
            is Member -> {
                val obj = evalExpr(target.obj, env)
                val key = memberKey(target, env)
                if (obj is JsObject) {
                    obj.set(key, value)
                    if (obj is JsArray) {
                        val idx = key.toIntOrNull()
                        if (idx != null && idx >= obj.length) obj.length = idx + 1
                    }
                }
            }
            else -> throw JsThrown("SyntaxError: Invalid assignment target")
        }
    }

    private fun memberKey(m: Member, env: Environment): String =
        if (m.computed) JsValues.toStr(evalExpr(m.computedExpr!!, env)) else m.prop

    private fun evalMember(m: Member, env: Environment): Pair<Any?, Any?> {
        val obj = evalExpr(m.obj, env)
        val key = memberKey(m, env)
        val v = when (obj) {
            null -> throw JsThrown("TypeError: Cannot read property '$key' of null")
            Undefined -> throw JsThrown("TypeError: Cannot read property '$key' of undefined")
            is String -> stringProp(obj, key)
            is JsObject -> obj.get(key)
            is Double, is Int, is Long -> realm.numberProto.get(key)
            is Boolean -> realm.booleanProto.get(key)
            else -> JsValues.UNDEFINED
        }
        return obj to v
    }

    private fun stringProp(s: String, key: String): Any? {
        val idx = key.toIntOrNull()
        if (idx != null && idx in s.indices) return s[idx].toString()
        if (key == "length") return s.length.toDouble()
        return realm.stringProto.get(key)
    }

    private fun evalCall(e: Call, env: Environment): Any? {
        val (thisVal, fn) = when (val c = e.callee) {
            is Member -> evalMember(c, env)
            else -> null to evalExpr(c, env)
        }
        val args = e.args.map { evalExpr(it, env) }
        val f = fn as? JsFunction ?: throw JsThrown("TypeError: '${describeCallee(e.callee)}' is not a function")
        val self = if (f.getOwn("__arrow__") == true) env.get("this") else (thisVal ?: realm.globalObject)
        return f.call(self, args)
    }

    private fun evalNew(e: NewExpr, env: Environment): Any? {
        val callee = evalExpr(e.callee, env) as? JsFunction ?: throw JsThrown("TypeError: Not a constructor")
        val proto = callee.get("prototype") as? JsObject ?: realm.objectProto
        val instance = JsObject(proto)
        val args = e.args.map { evalExpr(it, env) }
        val res = callee.call(instance, args)
        return if (res is JsObject) res else instance
    }

    private fun describeCallee(c: Expr): String = when (c) {
        is Ident -> c.name
        is Member -> (if (c.obj is Ident) (c.obj as Ident).name + "." else "") + c.prop
        else -> "<expr>"
    }

    // --- user fn call: the entry point functions use for their body ---
    fun callUserFn(fn: JsFunction, thisVal: Any?, args: List<Any?>): Any? {
        val body = fn.body ?: return JsValues.UNDEFINED
        val localEnv = Environment(fn.closure)
        // bind `this` unless arrow
        if (fn.getOwn("__arrow__") != true) localEnv.declare("this", thisVal)
        // params
        for ((i, p) in fn.params.withIndex()) {
            localEnv.declare(p, if (i < args.size) args[i] else JsValues.UNDEFINED)
        }
        // arguments object
        val arguments = JsArray().apply { args.forEach { push(it) } }
        localEnv.declare("arguments", arguments)
        hoist(localEnv, body.body)
        return try {
            for (s in body.body) execStmt(s, localEnv)
            JsValues.UNDEFINED
        } catch (r: ReturnSignal) { r.value }
    }
}
