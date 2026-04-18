package io.kjs.runtime

/**
 * Minimal, functional JS object:
 *  - string-keyed properties in insertion order (LinkedHashMap)
 *  - prototype chain via [proto]
 *  - `callable` slot to model functions as objects (for typeof 'function')
 *
 * This is intentionally simple for M1. In M2 we add Shape/HiddenClass +
 * inline caches for attribute access speedups.
 */
open class JsObject(
    var proto: JsObject? = null,
) {
    val properties: LinkedHashMap<String, Any?> = LinkedHashMap()
    var callable: JsFunction? = null
    var className: String = "Object"
    var extensible: Boolean = true

    /** Own + prototype lookup. */
    open fun get(key: String): Any? {
        var o: JsObject? = this
        while (o != null) {
            if (o.properties.containsKey(key)) return o.properties[key]
            o = o.proto
        }
        return JsValues.UNDEFINED
    }

    open fun getOwn(key: String): Any? = properties[key]

    open fun hasOwn(key: String): Boolean = properties.containsKey(key)

    open fun has(key: String): Boolean {
        var o: JsObject? = this
        while (o != null) { if (o.properties.containsKey(key)) return true; o = o.proto }
        return false
    }

    open fun set(key: String, value: Any?) { properties[key] = value }

    open fun delete(key: String): Boolean = properties.remove(key) != null || !properties.containsKey(key)

    /** Used by ToPrimitive in JsValues. Default: stringified object. */
    open fun defaultValue(hint: String): Any? {
        val preferString = hint == "string"
        val order = if (preferString) listOf("toString", "valueOf") else listOf("valueOf", "toString")
        for (m in order) {
            val f = get(m)
            if (f is JsFunction) {
                val r = f.call(this, emptyList())
                if (r !is JsObject) return r
            }
        }
        return "[object $className]"
    }

    open fun keys(): List<String> = properties.keys.toList()
}

/**
 * ES2015 Proxy object.  Intercepts get / set / has / deleteProperty / ownKeys
 * via a handler object; any other operation defers to the underlying target.
 *
 * This is intentionally a subset of the full spec — enough for common patterns
 * like logging, defaulting, and virtualization.
 */
class JsProxy(val target: JsObject, val handler: JsObject) : JsObject(target.proto) {
    init {
        className = target.className
        extensible = target.extensible
    }

    private fun trap(name: String): JsFunction? = handler.get(name) as? JsFunction

    override fun get(key: String): Any? {
        val t = trap("get") ?: return target.get(key)
        return t.call(handler, listOf(target, key, this))
    }

    override fun getOwn(key: String): Any? = target.getOwn(key)

    override fun hasOwn(key: String): Boolean = target.hasOwn(key)

    override fun has(key: String): Boolean {
        val t = trap("has") ?: return target.has(key)
        return JsValues.toBool(t.call(handler, listOf(target, key)))
    }

    override fun set(key: String, value: Any?) {
        val t = trap("set") ?: run { target.set(key, value); return }
        t.call(handler, listOf(target, key, value, this))
    }

    override fun delete(key: String): Boolean {
        val t = trap("deleteProperty") ?: return target.delete(key)
        return JsValues.toBool(t.call(handler, listOf(target, key)))
    }

    override fun keys(): List<String> {
        val t = trap("ownKeys") ?: return target.keys()
        val r = t.call(handler, listOf(target))
        if (r is JsArray) return (0 until r.length).map { JsValues.toStr(r.get(it.toString())) }
        return target.keys()
    }
}

class JsArray : JsObject() {
    init { className = "Array" }
    var length: Int
        get() = (properties["length"] as? Double)?.toInt() ?: 0
        set(v) { properties["length"] = v.toDouble() }

    init { properties["length"] = 0.0 }

    fun push(v: Any?) {
        val idx = length
        set(idx.toString(), v)
        length = idx + 1
    }
}
