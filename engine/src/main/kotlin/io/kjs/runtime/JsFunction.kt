package io.kjs.runtime

import io.kjs.parse.Block

/**
 * JS function. Either user-defined (AST body + closure env) or native (host lambda).
 */
class JsFunction private constructor(
    val name: String,
    val params: List<String>,
    val body: Block?,
    val closure: Environment?,
    val native: ((thisVal: Any?, args: List<Any?>) -> Any?)?,
) : JsObject() {

    init { className = "Function"; callable = this }

    /** Callback the interpreter uses to execute this function. Set at runtime. */
    var invoker: ((JsFunction, Any?, List<Any?>) -> Any?)? = null

    /**
     * Fast-path handle for the VM's own compiled closure. When non-null the VM's
     * CALL opcode may dispatch directly instead of going through [invoker]. Opaque
     * to all other subsystems.
     */
    var vmClosure: Any? = null

    fun call(thisVal: Any?, args: List<Any?>): Any? {
        // Prefer the custom invoker (used by VM closures so they keep their own
        // dispatch path) over the native lambda. Plain native functions have no
        // invoker and fall through.
        invoker?.let { return it(this, thisVal, args) }
        if (native != null) return native.invoke(thisVal, args)
        error("Function '$name' has no body")
    }

    companion object {
        fun user(name: String, params: List<String>, body: Block, closure: Environment) =
            JsFunction(name, params, body, closure, null)

        fun native(name: String, arity: Int = 0, fn: (Any?, List<Any?>) -> Any?): JsFunction {
            val f = JsFunction(name, List(arity) { "a$it" }, null, null, fn)
            f.set("name", name)
            f.set("length", arity.toDouble())
            return f
        }
    }
}

/** Lexical environment / scope. */
class Environment(val parent: Environment? = null) {
    private val vars = HashMap<String, Any?>()

    fun declare(name: String, value: Any?) { vars[name] = value }
    fun has(name: String): Boolean { var e: Environment? = this; while (e != null) { if (e.vars.containsKey(name)) return true; e = e.parent }; return false }
    fun get(name: String): Any? {
        var e: Environment? = this
        while (e != null) { if (e.vars.containsKey(name)) return e.vars[name]; e = e.parent }
        return JsValues.UNDEFINED
    }
    fun set(name: String, value: Any?): Boolean {
        var e: Environment? = this
        while (e != null) { if (e.vars.containsKey(name)) { e.vars[name] = value; return true }; e = e.parent }
        return false
    }
    /** Used by `=` on undeclared identifier (non-strict): create on global root. */
    fun setOrDeclareGlobal(name: String, value: Any?) {
        if (!set(name, value)) {
            var e: Environment = this; while (e.parent != null) e = e.parent!!
            e.vars[name] = value
        }
    }
}
