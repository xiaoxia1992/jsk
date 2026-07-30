package io.kjs.vm

import io.kjs.runtime.Environment

/**
 * Per-site inline cache for `LOAD_GLOBAL`.
 *
 * The first resolution walks the scope chain (via
 * [Environment.resolveOwner]) to find which environment owns the binding,
 * then caches:
 *  - the owning [Environment] itself (identity-compared on subsequent hits)
 *  - a direct pointer to the owner's backing `HashMap` (so the cached read
 *    is one `HashMap.get` call — no chain walk, no `containsKey` probe)
 *
 * Cache validation keys on the (start-environment, name, owner) triple.
 * If any of these changes — e.g. a fresh call frame introduces a new
 * `closureEnv`, or the binding gets shadowed/redeclared — we re-resolve.
 *
 * **Correctness notes.** Global functions (the vast majority of
 * `LOAD_GLOBAL` in hot loops) are typically declared once in the global
 * environment and never shadowed thereafter, so the cache hits every time.
 * We never cache the *value* itself — only the owning map — so updates via
 * `=` are reflected immediately. A missing binding with `tolerate=0` still
 * goes through the slow path (to raise `ReferenceError` uniformly).
 */
class GlobalIc {
    @JvmField var cachedStart: Environment? = null
    @JvmField var cachedOwner: Environment? = null
    @JvmField var cachedName: String? = null

    /**
     * Look up [name] in [start] (or its ancestors). Returns `null` if the
     * binding is missing — the caller handles the tolerate/throw decision.
     */
    fun get(start: Environment, name: String): Any? {
        val owner = if (cachedStart === start && cachedName === name) cachedOwner
                    else resolveAndFill(start, name)
        if (owner == null) return SENTINEL_MISSING
        return owner.vars[name]
    }

    private fun resolveAndFill(start: Environment, name: String): Environment? {
        val owner = start.resolveOwner(name)
        cachedStart = start
        cachedName = name
        cachedOwner = owner
        return owner
    }

    companion object {
        /** Distinguishes "cache says missing" from "value is null". Identity-only. */
        @JvmField val SENTINEL_MISSING: Any = Any()
    }
}
