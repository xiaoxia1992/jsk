package io.kjs.vm

import io.kjs.runtime.JsObject
import io.kjs.runtime.JsValues

/**
 * Monomorphic inline-cache record for a property access site.
 *
 * Design: one cache entry per bytecode PC; keyed by the first encountered
 * receiver class name + a reference to the object on which the property was
 * found (to disambiguate own-prop vs. prototype). When the miss rate exceeds a
 * small threshold the cache is marked *megamorphic* and bypassed.
 */
class PropIc {
    private var cachedClass: String? = null
    private var cachedOwner: JsObject? = null
    private var cachedValue: Any? = null
    private var cachedName: String? = null
    private var hits: Int = 0
    private var misses: Int = 0
    private var megamorphic: Boolean = false

    /** Try a cached read; falls back to [slow]. */
    fun get(obj: JsObject?, name: String, slow: (JsObject?, String) -> Any?): Any? {
        if (obj == null) return slow(obj, name)
        if (!megamorphic) {
            val cls = obj.className
            if (cls == cachedClass && name == cachedName) {
                // Hit only if the owner is still a prototype-chain ancestor of obj and still holds the name.
                val owner = cachedOwner
                if (owner != null && owner.hasOwn(name)) {
                    var p: JsObject? = obj
                    while (p != null) { if (p === owner) { hits++; return owner.getOwn(name) }; p = p.proto }
                }
            }
        }
        val v = slow(obj, name)
        if (!megamorphic) fillOrInvalidate(obj, name)
        return v
    }

    private fun fillOrInvalidate(obj: JsObject, name: String) {
        // Find the owner on the prototype chain.
        var p: JsObject? = obj
        while (p != null) { if (p.hasOwn(name)) break; p = p.proto }
        if (p == null) { misses++; if (misses > 10) megamorphic = true; return }
        if (cachedClass == null) {
            cachedClass = obj.className; cachedOwner = p; cachedName = name; cachedValue = p.getOwn(name); return
        }
        if (cachedClass != obj.className || cachedName != name) {
            misses++
            if (misses > 10) { megamorphic = true; return }
            // Reseed
            cachedClass = obj.className; cachedOwner = p; cachedName = name; cachedValue = p.getOwn(name)
        }
    }
}
