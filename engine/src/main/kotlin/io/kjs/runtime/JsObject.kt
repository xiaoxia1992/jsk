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
