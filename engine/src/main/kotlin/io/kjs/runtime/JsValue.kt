package io.kjs.runtime

/**
 * JS values in M1 are represented as JVM objects (Any?) for simplicity.
 * The bytecode VM in M2 will switch to NaN-boxed Long. The semantics here
 * act as the oracle; we keep the coercion helpers centralized so both
 * interpreters can share them.
 */
object Undefined { override fun toString() = "undefined" }

object JsValues {
    val UNDEFINED: Any? = Undefined
    val NULL: Any? = null

    fun typeOf(v: Any?): String = when (v) {
        Undefined -> "undefined"
        null -> "object"
        is Boolean -> "boolean"
        is Double, is Int, is Long -> "number"
        is java.math.BigInteger -> "bigint"
        is String -> "string"
        is JsFunction -> "function"
        is JsObject -> when {
            v.callable != null -> "function"
            v.className == "Symbol" -> "symbol"
            else -> "object"
        }
        else -> "object"
    }

    fun toBool(v: Any?): Boolean = when (v) {
        Undefined, null -> false
        is Boolean -> v
        is Double -> !(v.isNaN() || v == 0.0)
        is Int -> v != 0
        is Long -> v != 0L
        is java.math.BigInteger -> v.signum() != 0
        is String -> v.isNotEmpty()
        else -> true
    }

    fun toNumber(v: Any?): Double = when (v) {
        Undefined -> Double.NaN
        null -> 0.0
        is Boolean -> if (v) 1.0 else 0.0
        is Double -> v
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is java.math.BigInteger -> v.toDouble()
        is String -> {
            val s = v.trim()
            if (s.isEmpty()) 0.0
            else s.toDoubleOrNull() ?: Double.NaN
        }
        is JsObject -> toNumber(v.defaultValue("number"))
        else -> Double.NaN
    }

    fun toInt32(v: Any?): Int {
        val n = toNumber(v)
        if (n.isNaN() || n.isInfinite()) return 0
        val l = n.toLong()
        return l.toInt()
    }

    fun toUint32(v: Any?): Long {
        return toInt32(v).toLong() and 0xFFFFFFFFL
    }

    fun toStr(v: Any?): String = when (v) {
        Undefined -> "undefined"
        null -> "null"
        is Boolean -> v.toString()
        is String -> v
        is Double -> numberToString(v)
        is Int -> v.toString()
        is Long -> v.toString()
        is java.math.BigInteger -> v.toString()   // note: spec would *not* add "n" suffix for toString
        is JsObject -> toStr(v.defaultValue("string"))
        else -> v.toString()
    }

    fun numberToString(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        if (d == 0.0) return "0"
        if (d == d.toLong().toDouble() && kotlin.math.abs(d) < 1e21) return d.toLong().toString()
        return d.toString()
    }

    /** Abstract equality (==) */
    fun looseEq(a: Any?, b: Any?): Boolean {
        if (sameType(a, b)) return strictEq(a, b)
        if ((a == null && b == Undefined) || (a == Undefined && b == null)) return true
        if (isNum(a) && b is String) return toNumber(a) == toNumber(b)
        if (a is String && isNum(b)) return toNumber(a) == toNumber(b)
        if (a is Boolean) return looseEq(toNumber(a), b)
        if (b is Boolean) return looseEq(a, toNumber(b))
        if ((isNum(a) || a is String) && b is JsObject) return looseEq(a, b.defaultValue("default"))
        if (a is JsObject && (isNum(b) || b is String)) return looseEq(a.defaultValue("default"), b)
        return false
    }

    fun strictEq(a: Any?, b: Any?): Boolean {
        if (!sameType(a, b)) return false
        if (a is Double && b is Double) {
            if (a.isNaN() || b.isNaN()) return false
            return a == b
        }
        return a === b || a == b
    }

    private fun isNum(v: Any?) = v is Double || v is Int || v is Long
    private fun sameType(a: Any?, b: Any?): Boolean {
        return typeOf(a) == typeOf(b) && ((a == null) == (b == null))
    }
}
