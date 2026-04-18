package io.kjs.ir

import io.kjs.parse.Block

/**
 * Compiled form of a single JavaScript function (or the top-level program).
 *
 * Instructions are stored in three parallel IntArray lanes — one for the opcode
 * ordinal and two operand slots. Keeping them separate lets the VM read lanes
 * contiguously (cache-friendly) and avoids boxing altogether on the hot path.
 */
class Bytecode(
    val name: String,
    val paramCount: Int,
    val isArrow: Boolean,
) {
    // Writable during compilation; frozen (copied to IntArray) by freeze() once done.
    val code = ArrayList<Int>()
    val aOps = ArrayList<Int>()
    val bOps = ArrayList<Int>()
    val lines = ArrayList<Int>()

    // Hot-path read-only views. Populated by freeze(); the VM's dispatch loop
    // reads these directly so every instruction fetch stays primitive (no boxing).
    lateinit var codeA: IntArray
        private set
    lateinit var aOpsA: IntArray
        private set
    lateinit var bOpsA: IntArray
        private set

    val constants = ArrayList<Any?>()           // Double, Boolean, null, Undefined — never String
    val strings = ArrayList<String>()           // names, string literals
    val functions = ArrayList<Bytecode>()       // nested compiled functions

    /** Total number of locals used by this function (including params). */
    var localCount: Int = 0

    /** Try-handler table is implicit (via TRY_ENTER in code); kept here for tooling. */
    val handlers = ArrayList<Handler>()

    /** For source-level debugging / error messages. */
    var source: String = ""
    var originalBody: Block? = null  // retained only for the tree-walker fallback

    /** Inline-cache slots, one per instruction. Allocated lazily by the VM. */
    var caches: Array<Any?>? = null

    data class Handler(val start: Int, val end: Int, val catchPc: Int, val finallyPc: Int)

    val size: Int get() = code.size

    fun emit(op: Op, a: Int = 0, b: Int = 0, line: Int = 0): Int {
        val pc = code.size
        code.add(op.ordinal); aOps.add(a); bOps.add(b); lines.add(line)
        return pc
    }

    /** Patch the A operand of an already-emitted instruction (used for forward jumps). */
    fun patchA(pc: Int, a: Int) { aOps[pc] = a }
    fun patchB(pc: Int, b: Int) { bOps[pc] = b }

    /**
     * Snapshot the growable ArrayLists into primitive IntArrays for fast VM
     * dispatch. Must be called exactly once per Bytecode, after emission and
     * all patches are done. Also freezes nested function Bytecodes recursively.
     */
    fun freeze() {
        codeA = code.toIntArray()
        aOpsA = aOps.toIntArray()
        bOpsA = bOps.toIntArray()
        for (f in functions) if (!f::codeA.isInitialized) f.freeze()
    }

    fun constIdx(v: Any?): Int {
        // Linear scan is fine for the sizes we deal with; the compiler dedupes on insert.
        val i = constants.indexOfFirst { sameConst(it, v) }
        if (i >= 0) return i
        constants.add(v); return constants.size - 1
    }

    fun strIdx(s: String): Int {
        val i = strings.indexOf(s)
        if (i >= 0) return i
        strings.add(s); return strings.size - 1
    }

    fun fnIdx(fn: Bytecode): Int { functions.add(fn); return functions.size - 1 }

    private fun sameConst(a: Any?, b: Any?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return a == b
        if (a is Double && b is Double) return a.toRawBits() == b.toRawBits()
        return a == b
    }

    /** Human-readable disassembly (for debugging). */
    fun disasm(): String {
        val sb = StringBuilder()
        sb.append("=== $name/$paramCount locals=$localCount ===\n")
        for (pc in 0 until code.size) {
            val op = OP_VALUES[code[pc]]
            sb.append(String.format("%4d  %-14s %5d %5d", pc, op.name, aOps[pc], bOps[pc]))
            when (op) {
                Op.LOAD_CONST -> sb.append("   ; ${constants[aOps[pc]]}")
                Op.LOAD_STR, Op.LOAD_GLOBAL, Op.STORE_GLOBAL, Op.DECL_GLOBAL,
                Op.LOAD_PROP, Op.STORE_PROP, Op.DELETE_PROP -> sb.append("   ; \"${strings[aOps[pc]]}\"")
                else -> {}
            }
            sb.append('\n')
        }
        for ((i, f) in functions.withIndex()) sb.append("\n-- fn[$i] --\n").append(f.disasm())
        return sb.toString()
    }
}
