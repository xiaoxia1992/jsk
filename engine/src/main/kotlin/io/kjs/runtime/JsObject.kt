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

/**
 * TypedArray view over a byte buffer.  Indexed access (`arr[i]`) is routed
 * through `get(key)` / `set(key, value)` overrides so the usual `LOAD_ELEM`
 * / `STORE_ELEM` opcodes see the correct coerced value.
 */
class JsTypedArray(
    val bytes: ByteArray,
    val byteOffset: Int,
    val lengthInElements: Int,
    val elementSize: Int,
    val kind: Kind,
) : JsObject() {
    enum class Kind { INT8, UINT8, INT16, UINT16, INT32, UINT32, FLOAT32, FLOAT64 }

    init {
        className = when (kind) {
            Kind.INT8 -> "Int8Array"; Kind.UINT8 -> "Uint8Array"
            Kind.INT16 -> "Int16Array"; Kind.UINT16 -> "Uint16Array"
            Kind.INT32 -> "Int32Array"; Kind.UINT32 -> "Uint32Array"
            Kind.FLOAT32 -> "Float32Array"; Kind.FLOAT64 -> "Float64Array"
        }
        properties["length"] = lengthInElements.toDouble()
        properties["byteLength"] = (lengthInElements * elementSize).toDouble()
        properties["byteOffset"] = byteOffset.toDouble()
    }

    private val bb: java.nio.ByteBuffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)

    override fun get(key: String): Any? {
        val idx = key.toIntOrNull()
        if (idx != null && idx in 0 until lengthInElements) return readElement(idx)
        return super.get(key)
    }

    override fun getOwn(key: String): Any? {
        val idx = key.toIntOrNull()
        if (idx != null && idx in 0 until lengthInElements) return readElement(idx)
        return super.getOwn(key)
    }

    override fun set(key: String, value: Any?) {
        val idx = key.toIntOrNull()
        if (idx != null && idx in 0 until lengthInElements) { writeElement(idx, value); return }
        super.set(key, value)
    }

    private fun readElement(i: Int): Any? {
        val off = byteOffset + i * elementSize
        return when (kind) {
            Kind.INT8 -> bytes[off].toDouble()
            Kind.UINT8 -> (bytes[off].toInt() and 0xFF).toDouble()
            Kind.INT16 -> bb.getShort(off).toDouble()
            Kind.UINT16 -> (bb.getShort(off).toInt() and 0xFFFF).toDouble()
            Kind.INT32 -> bb.getInt(off).toDouble()
            Kind.UINT32 -> (bb.getInt(off).toLong() and 0xFFFFFFFFL).toDouble()
            Kind.FLOAT32 -> bb.getFloat(off).toDouble()
            Kind.FLOAT64 -> bb.getDouble(off)
        }
    }
    private fun writeElement(i: Int, v: Any?) {
        val n = JsValues.toNumber(v)
        val off = byteOffset + i * elementSize
        when (kind) {
            Kind.INT8 -> bytes[off] = n.toInt().toByte()
            Kind.UINT8 -> bytes[off] = (n.toInt() and 0xFF).toByte()
            Kind.INT16 -> bb.putShort(off, n.toInt().toShort())
            Kind.UINT16 -> bb.putShort(off, (n.toInt() and 0xFFFF).toShort())
            Kind.INT32 -> bb.putInt(off, n.toInt())
            Kind.UINT32 -> bb.putInt(off, (n.toLong() and 0xFFFFFFFFL).toInt())
            Kind.FLOAT32 -> bb.putFloat(off, n.toFloat())
            Kind.FLOAT64 -> bb.putDouble(off, n)
        }
    }

    override fun keys(): List<String> = (0 until lengthInElements).map { it.toString() }
}
