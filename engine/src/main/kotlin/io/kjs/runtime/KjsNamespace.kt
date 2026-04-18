package io.kjs.runtime

/**
 * Example of a host-provided namespace. Mirrors how a real embedder would expose
 * their application API to guest JS (e.g. `fs`, `net`, `graphics` in a game engine).
 *
 * Each function follows the same recipe:
 *  1. Build a Kotlin lambda of shape (thisVal, args) -> Any?
 *  2. Wrap it with JsFunction.native("name", arity, lambda)
 *  3. Hang it on a JsObject at a well-known name
 *
 * No changes to the compiler or VM are needed — the generic CALL_METHOD opcode
 * already knows how to invoke anything that implements JsFunction.call().
 */
object KjsNamespace {
    fun install(realm: Realm) {
        val ns = JsObject(realm.objectProto)
        ns.className = "KjsNamespace"

        ns.set("rand", JsFunction.native("rand", 1) { _, args ->
            val n = if (args.isEmpty()) 1.0 else JsValues.toNumber(args[0])
            if (n <= 0.0 || n.isNaN()) 0.0
            else kotlin.math.floor(Math.random() * n)
        })

        ns.set("ms", JsFunction.native("ms", 0) { _, _ ->
            System.currentTimeMillis().toDouble()
        })

        // Demonstrate that natives can also throw JS-side exceptions.
        ns.set("assert", JsFunction.native("assert", 2) { _, args ->
            val cond = args.firstOrNull()
            if (!JsValues.toBool(cond)) {
                val msg = if (args.size > 1) JsValues.toStr(args[1]) else "assertion failed"
                val err = JsObject(realm.errorProto).apply {
                    set("name", "AssertionError"); set("message", msg); className = "Error"
                }
                throw JsThrown(err)
            }
            JsValues.UNDEFINED
        })

        // Natives can even take other JS functions as callbacks (higher-order).
        ns.set("repeat", JsFunction.native("repeat", 2) { _, args ->
            val times = JsValues.toInt32(args.getOrNull(0))
            val cb = args.getOrNull(1) as? JsFunction ?: return@native JsValues.UNDEFINED
            for (i in 0 until times) cb.call(JsValues.UNDEFINED, listOf(i.toDouble()))
            JsValues.UNDEFINED
        })

        realm.globalObject.set("kjs", ns)
        realm.globalEnv.declare("kjs", ns)
    }
}
