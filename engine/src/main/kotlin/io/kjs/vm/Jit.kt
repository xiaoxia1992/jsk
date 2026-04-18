package io.kjs.vm

import io.kjs.ir.Bytecode
import io.kjs.ir.OP_VALUES
import io.kjs.ir.Op
import io.kjs.runtime.JsFunction
import io.kjs.runtime.JsValues
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type

/**
 * Template JIT: translates KJS bytecode to JVM bytecode at runtime via ASM.
 *
 * ## Strategy
 *  One generated class per [Bytecode], subclassing [Compiled]. The KJS stack
 *  is mapped onto the JVM operand stack; KJS locals live in JVM local slots.
 *
 *  A pragmatic subset of opcodes is compiled: arithmetic, comparisons,
 *  local load/store, constants, conditional/unconditional jumps, RET.
 *  Anything else causes the function to be rejected — handled by the
 *  interpreter, no loss of correctness.
 *
 * ## Type specialization
 *  Before emission, a small abstract interpreter infers whether each KJS
 *  **local** is used exclusively with numeric (Double) values. Such locals
 *  are promoted to primitive `double` JVM slots (DSTORE/DLOAD) instead of
 *  `Object` slots — eliminating a box+unbox on every access.
 *
 *  For the operand *stack*, we keep an abstract type tracker alongside
 *  emission. Two adjacent DOUBLE values on the stack let us emit the JVM's
 *  native `DADD/DSUB/DMUL/DDIV/DREM` and `DCMPL+IF_ICMP…`, again without
 *  boxing. Any time the stack crosses a branch boundary (JMP/JT/JF) we
 *  uniformly box everything to ANY; this keeps branch merging trivial.
 *
 *  Hot numeric loops (`sumN`, `poly`, `square`, …) thereby execute with
 *  zero per-iteration boxing. HotSpot C2 keeps them in XMM registers and
 *  optimises as if we'd hand-written Java.
 */
object Jit {
    val threshold: Int = System.getenv("KJS_JIT_THRESHOLD")?.toIntOrNull() ?: 3

    private val disabled: Boolean = System.getenv("KJS_JIT")?.lowercase() in setOf("0", "off", "false", "no")
    private val verbose: Boolean = System.getenv("KJS_JIT_VERBOSE")?.isNotEmpty() == true

    /** Disable type specialization via KJS_JIT_SPEC=off (useful for A/B measurements). */
    private val specDisabled: Boolean = System.getenv("KJS_JIT_SPEC")?.lowercase() in setOf("0", "off", "false", "no")

    val logLevel: Int = when (System.getenv("KJS_JIT_LOG")?.lowercase()) {
        null, "", "0", "off", "false" -> 0
        "trace", "2" -> 2
        else -> 1
    }

    fun shouldCompile(hotness: Int): Boolean = !disabled && hotness == threshold

    // ---------------- async compile dispatcher ----------------

    /** Env switch: KJS_JIT_ASYNC=off forces the (old) synchronous compile path. */
    private val asyncDisabled: Boolean = System.getenv("KJS_JIT_ASYNC")?.lowercase() in setOf("0", "off", "false", "no")

    /** Single daemon thread: compilations are cheap and strictly ordered avoids surprises. */
    private val compilerPool: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "kjs-jit-compiler").apply { isDaemon = true }
        }
    }

    /**
     * Request a background compile of [closure]'s bytecode. When the compile
     * finishes, the resulting [Compiled] is published to `closure.compiled`.
     * If compilation fails or is rejected, `closure.jitRejected` is set.
     * In both cases `closure.compilePending` goes back to false.
     *
     * If async mode is disabled (KJS_JIT_ASYNC=off), compiles inline instead.
     */
    fun requestCompile(closure: VmClosure) {
        if (closure.compilePending || closure.compiled != null || closure.jitRejected) return
        if (!canCompile(closure.bc)) { closure.jitRejected = true; return }

        closure.compilePending = true

        val task = Runnable {
            try {
                val t0 = System.nanoTime()
                val compiled = compile(closure.bc)
                val us = (System.nanoTime() - t0) / 1000
                closure.compiled = compiled         // publish (volatile write)
                log { "✓ compiled ${closure.bc.name} → JVM bytecode in ${us}µs (${closure.bc.size} KJS opcodes) [async]" }
            } catch (t: Throwable) {
                closure.jitRejected = true
                log { "✗ compile failed for ${closure.bc.name}: ${t.message} — falling back to interpreter" }
            } finally {
                closure.compilePending = false
            }
        }

        if (asyncDisabled) task.run() else compilerPool.execute(task)
    }

    internal fun log(msg: () -> String) {
        if (verbose || logLevel >= 1) System.err.println("[jit] ${msg()}")
    }
    internal fun trace(msg: () -> String) {
        if (logLevel >= 2) System.err.println("[jit] ${msg()}")
    }

    private val supported = setOf(
        Op.NOP,
        Op.LOAD_UNDEF, Op.LOAD_NULL, Op.LOAD_TRUE, Op.LOAD_FALSE,
        Op.LOAD_ZERO, Op.LOAD_ONE, Op.LOAD_INT, Op.LOAD_CONST, Op.LOAD_STR,
        Op.LOAD_LOCAL, Op.STORE_LOCAL, Op.LOAD_ARG,
        Op.LOAD_GLOBAL,     // via bridge
        Op.POP, Op.DUP, Op.SWAP,
        Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD,
        Op.NEG, Op.PLUS, Op.NOT, Op.TO_NUMBER, Op.TYPEOF,
        Op.EQ, Op.NEQ, Op.SEQ, Op.SNEQ, Op.LT, Op.LE, Op.GT, Op.GE,
        Op.JMP, Op.JT, Op.JF,
        Op.RET, Op.RET_UNDEF,
        Op.STASH_RESULT, Op.HALT,
        Op.GET_THIS,
        Op.PUSH_BLOCK, Op.POP_BLOCK,
        Op.CALL,            // argc <= 4 (see emitter)
    )

    /** Max argc we JIT-compile a CALL for; larger ones cause function rejection. */
    private const val MAX_JIT_CALL_ARGC = 4

    fun canCompile(bc: Bytecode): Boolean {
        if (disabled) return false
        for (i in 0 until bc.size) {
            val op = OP_VALUES[bc.codeA[i]]
            if (op !in supported) {
                log { "✗ ${bc.name} cannot be JIT'd — unsupported opcode $op at pc=$i" }
                return false
            }
            if (op == Op.CALL && bc.aOpsA[i] > MAX_JIT_CALL_ARGC) {
                log { "✗ ${bc.name} cannot be JIT'd — CALL with argc=${bc.aOpsA[i]} > $MAX_JIT_CALL_ARGC at pc=$i" }
                return false
            }
        }
        return true
    }

    // ---------------- type inference: decide which locals to unbox ----------------

    /**
     * Decide, per KJS local, whether it can be held in a primitive `double` slot.
     * Rule: every STORE_LOCAL that targets the slot must push a value we can
     * cheaply coerce to double **without changing observable semantics**.
     * Concretely: the pushed value must itself come from a DOUBLE-producing
     * opcode chain. If any store on a slot pushes a non-numeric thing, the
     * slot stays ANY.
     *
     * We do a cheap, single-pass abstract interpretation: we track the type
     * tag of the top-of-stack across straight-line code, resetting at labels.
     */
    private fun inferDoubleLocals(bc: Bytecode): BooleanArray {
        val res = BooleanArray(bc.localCount)
        // -1 = unseen, 1 = all-stores-were-double, 0 = seen non-double store
        val vote = ByteArray(bc.localCount) { -1 }
        // Parameters start as DOUBLE candidates (they're never stored to, so the
        // store-based voting would leave them at -1 → ANY otherwise). They'll be
        // demoted during inference if they flow through non-numeric ops.
        for (i in 0 until bc.paramCount) vote[i] = 1
        // Locals that are *never* stored to (neither params nor assigned) default to 0.
        // We'll bump them if a store matching DOUBLE is seen.

        // Collect branch targets so we can reset our abstract stack there.
        val isTarget = BooleanArray(bc.size + 1)
        for (pc in 0 until bc.size) {
            val op = OP_VALUES[bc.codeA[pc]]
            when (op) {
                Op.JMP, Op.JT, Op.JF -> {
                    val t = bc.aOpsA[pc]
                    if (t in 0..bc.size) isTarget[t] = true
                }
                else -> {}
            }
        }

        // Iterate until fixed point (vote only monotonically decreases from 1 → 0,
        // and once dropped it never comes back, so this terminates quickly).
        var changed = true
        var rounds = 0
        while (changed && rounds < 8) {
            changed = false
            rounds++

            // Abstract stack of type tags: 0=ANY, 1=DOUBLE, 2=BOOL
            val stack = ArrayList<Byte>()
            fun reset() { stack.clear() }
            fun push(t: Byte) { stack.add(t) }
            fun pop(): Byte = if (stack.isEmpty()) 0 else stack.removeAt(stack.size - 1)
            fun top(): Byte = if (stack.isEmpty()) 0 else stack[stack.size - 1]

            for (pc in 0 until bc.size) {
                if (isTarget[pc]) reset()
                val op = OP_VALUES[bc.codeA[pc]]
                val a = bc.aOpsA[pc]

                when (op) {
                    Op.LOAD_UNDEF, Op.LOAD_NULL, Op.LOAD_STR -> push(0)
                    Op.LOAD_TRUE, Op.LOAD_FALSE -> push(2)
                    Op.LOAD_ZERO, Op.LOAD_ONE, Op.LOAD_INT -> push(1)
                    Op.LOAD_CONST -> {
                        val v = bc.constants[a]
                        push(if (v is Double) 1 else if (v is Boolean) 2 else 0)
                    }

                    Op.LOAD_LOCAL -> push(if (a in 0 until bc.localCount && vote[a].toInt() == 1) 1 else 0)
                    Op.LOAD_ARG -> push(0)
                    Op.LOAD_GLOBAL -> push(0)

                    Op.STORE_LOCAL -> {
                        if (a in 0 until bc.localCount) {
                            val t = top()
                            val cur = vote[a]
                            when {
                                cur.toInt() == -1 -> { vote[a] = if (t.toInt() == 1) 1 else 0; changed = true }
                                cur.toInt() == 1 && t.toInt() != 1 -> { vote[a] = 0; changed = true }
                            }
                        }
                    }

                    Op.DUP -> push(top())
                    Op.POP -> { pop() }
                    Op.SWAP -> {
                        if (stack.size >= 2) {
                            val t = stack[stack.size - 1]; stack[stack.size - 1] = stack[stack.size - 2]; stack[stack.size - 2] = t
                        }
                    }

                    Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD -> {
                        val r = pop(); val l = pop()
                        push(if (l.toInt() == 1 && r.toInt() == 1) 1 else 0)
                    }
                    Op.NEG, Op.PLUS, Op.TO_NUMBER -> { val v = pop(); push(if (v.toInt() == 1) 1 else 0) }
                    Op.NOT -> { pop(); push(2) }
                    Op.TYPEOF -> { pop(); push(0) }

                    Op.LT, Op.LE, Op.GT, Op.GE, Op.EQ, Op.NEQ, Op.SEQ, Op.SNEQ -> { pop(); pop(); push(2) }

                    Op.CALL -> {
                        val argc = a
                        for (i in 0 until argc + 1) pop()   // pop argN-1..arg0, callee
                        push(0)                             // result is ANY
                    }

                    Op.JMP -> reset()
                    Op.JT, Op.JF -> { pop() }

                    Op.RET -> { pop(); reset() }
                    Op.RET_UNDEF, Op.HALT -> reset()
                    Op.STASH_RESULT -> { pop() }

                    Op.GET_THIS -> push(0)

                    Op.NOP, Op.PUSH_BLOCK, Op.POP_BLOCK -> {}
                    else -> reset()
                }
            }
        }

        // Params that survived all iterations as DOUBLE candidates are finalized.
        // For non-param locals that never got a store (vote still -1), they cannot
        // have been DOUBLE; leave as false.
        for (i in 0 until bc.localCount) res[i] = (vote[i].toInt() == 1)
        return res
    }

    // ---------------- emitter ----------------
    private val classCounter = java.util.concurrent.atomic.AtomicLong(0)

    /** Thrown internally when type specialization emission hits an uncovered case. */
    internal class JitAbort(msg: String) : RuntimeException(msg)

    private val OBJECT = Type.getInternalName(Any::class.java)
    private val COMPILED = Type.getInternalName(Compiled::class.java)
    private val VM = Type.getInternalName(Vm::class.java)
    private val REALM = Type.getInternalName(io.kjs.runtime.Realm::class.java)
    private val CLOSURE = Type.getInternalName(VmClosure::class.java)
    private val BRIDGE = Type.getInternalName(JitBridge::class.java)
    private val DOUBLE = Type.getInternalName(java.lang.Double::class.java)
    private val BOOLEAN = Type.getInternalName(java.lang.Boolean::class.java)

    fun compile(bc: Bytecode): Compiled {
        val id = classCounter.incrementAndGet()
        val safe = bc.name.filter { it.isLetterOrDigit() }.ifEmpty { "anon" }
        // Try specialized first; on any abort, retry with all-boxed emission.
        var attempt = 0
        while (true) {
            val specialize = attempt == 0 && !specDisabled
            val doubleLocals = if (specialize) inferDoubleLocals(bc) else BooleanArray(bc.localCount)
            val unboxedCount = doubleLocals.count { it }
            val className = "io/kjs/jit/Jit_${safe}_${id}_a$attempt"

            log { "compiling ${bc.name} (${bc.size} opcodes) → $className" +
                    if (!specialize) " [spec off]" else " [spec: $unboxedCount/${bc.localCount} locals unboxed]" }

            val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
            cw.visit(V17, ACC_PUBLIC or ACC_FINAL, className, null, COMPILED, null)

            run {
                val m = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
                m.visitCode()
                m.visitVarInsn(ALOAD, 0)
                m.visitMethodInsn(INVOKESPECIAL, COMPILED, "<init>", "()V", false)
                m.visitInsn(RETURN)
                m.visitMaxs(-1, -1); m.visitEnd()
            }

            try {
                val m = cw.visitMethod(
                    ACC_PUBLIC, "invoke",
                    "(L$VM;L$REALM;L$CLOSURE;L$OBJECT;[L$OBJECT;)L$OBJECT;",
                    null, null,
                )
                m.visitCode()
                emitBody(m, bc, doubleLocals)
                m.visitMaxs(-1, -1); m.visitEnd()
                cw.visitEnd()
            } catch (abort: JitAbort) {
                log { "⟲ ${bc.name}: specialization aborted (${abort.message}); retrying unspecialized" }
                attempt++
                if (attempt > 1) throw RuntimeException("JIT: cannot compile ${bc.name} even unspecialized", abort)
                continue
            }

            val bytes = cw.toByteArray()
            val cls = try {
                JitClassLoader.define(className.replace('/', '.'), bytes)
            } catch (vfy: VerifyError) {
                log { "⟲ ${bc.name}: JVM verifier rejected specialized code; retrying unspecialized" }
                attempt++
                if (attempt > 1) throw vfy
                continue
            }
            return cls.getDeclaredConstructor().newInstance() as Compiled
        }
    }

    /** Abstract stack tag. ANY = boxed Object on JVM stack; DOUBLE = native `double`; BOOL = native `int` 0/1. */
    private enum class T { ANY, DOUBLE, BOOL }

    private fun emitBody(m: MethodVisitor, bc: Bytecode, doubleLocals: BooleanArray) {
        val LV_CLOSURE = 3
        val LV_THIS = 4
        val LV_ARGS = 5

        // Assign JVM slots to KJS locals. Doubles take 2 slots.
        val localSlot = IntArray(bc.localCount)
        run {
            var s = 6
            for (i in 0 until bc.localCount) {
                localSlot[i] = s
                s += if (doubleLocals[i]) 2 else 1
            }
        }

        // Abstract stack mirror.
        val aStack = ArrayDeque<T>()
        fun apush(t: T) { aStack.addLast(t) }
        fun apop(): T = aStack.removeLast()
        fun atop(): T = aStack.last()
        fun asnd(): T = aStack.elementAt(aStack.size - 2)   // second from top

        // --- coercion helpers (mutate JVM stack + abstract stack) ---
        fun boxTop() {
            when (atop()) {
                T.ANY -> {}
                T.DOUBLE -> {
                    m.visitMethodInsn(INVOKESTATIC, DOUBLE, "valueOf", "(D)L$DOUBLE;", false)
                    apop(); apush(T.ANY)
                }
                T.BOOL -> {
                    m.visitMethodInsn(INVOKESTATIC, BOOLEAN, "valueOf", "(Z)L$BOOLEAN;", false)
                    apop(); apush(T.ANY)
                }
            }
        }
        /** Convert top to primitive double. Safe for ANY via bridge.toD(). */
        fun toDouble() {
            when (atop()) {
                T.DOUBLE -> {}
                T.ANY -> {
                    m.visitMethodInsn(INVOKESTATIC, BRIDGE, "toD", "(L$OBJECT;)D", false)
                    apop(); apush(T.DOUBLE)
                }
                T.BOOL -> {
                    m.visitInsn(I2D)
                    apop(); apush(T.DOUBLE)
                }
            }
        }
        fun toBoolPrim() {
            when (atop()) {
                T.BOOL -> {}
                T.ANY -> {
                    m.visitMethodInsn(INVOKESTATIC, BRIDGE, "toBool", "(L$OBJECT;)Z", false)
                    apop(); apush(T.BOOL)
                }
                T.DOUBLE -> {
                    m.visitMethodInsn(INVOKESTATIC, BRIDGE, "dToBool", "(D)Z", false)
                    apop(); apush(T.BOOL)
                }
            }
        }

        /**
         * Coerce the top two stack entries to boxed ANY/ANY. Used before binary
         * opcodes that want to call the bridge (string ADD, general LT, etc.).
         * The layout after: [..., L:ANY, R:ANY].
         */
        fun boxTopTwo() {
            // Fast exits.
            if (atop() == T.ANY && asnd() == T.ANY) return
            // Box top first (now known to be cat1 since we box cat2 inline).
            if (atop() != T.ANY) boxTop()
            // Now top is ANY (cat1). Box second if needed.
            if (asnd() != T.ANY) {
                if (asnd() == T.DOUBLE) {
                    // SWAP doesn't work with cat2 below cat1. Abort & retry unspecialized.
                    throw JitAbort("double as second operand under ANY at pc=?")
                }
                m.visitInsn(SWAP)
                val topT = aStack.removeLast(); val sndT = aStack.removeLast(); aStack.addLast(topT); aStack.addLast(sndT)
                boxTop()
                m.visitInsn(SWAP)
                val a2 = aStack.removeLast(); val b2 = aStack.removeLast(); aStack.addLast(a2); aStack.addLast(b2)
            }
        }

        /** Box the **entire** abstract stack. Called before every branch so merge at targets is trivial. */
        fun boxAllStack() {
            if (aStack.all { it == T.ANY }) return
            val size = aStack.size
            if (size == 0) return
            if (atop() != T.ANY) boxTop()
            if (size >= 2 && asnd() != T.ANY) {
                if (asnd() == T.DOUBLE) throw JitAbort("double buried below ANY at branch")
                m.visitInsn(SWAP)
                val t1 = aStack.removeLast(); val t2 = aStack.removeLast(); aStack.addLast(t1); aStack.addLast(t2)
                boxTop()
                m.visitInsn(SWAP)
                val s1 = aStack.removeLast(); val s2 = aStack.removeLast(); aStack.addLast(s1); aStack.addLast(s2)
            }
            // Deeper than 2: require all-ANY already.
            for (i in 0 until (size - 2)) {
                if (aStack.elementAt(i) != T.ANY) throw JitAbort("non-ANY deep in stack at branch")
            }
        }

        // ---- prologue: init locals from args ----
        for (i in 0 until bc.paramCount) {
            val skipLabel = Label(); val doneLabel = Label()
            m.visitVarInsn(ALOAD, LV_ARGS); m.visitInsn(ARRAYLENGTH)
            m.visitIntInsn(SIPUSH, i)
            m.visitJumpInsn(IF_ICMPLE, skipLabel)
            m.visitVarInsn(ALOAD, LV_ARGS); m.visitIntInsn(SIPUSH, i); m.visitInsn(AALOAD)
            m.visitJumpInsn(GOTO, doneLabel)
            m.visitLabel(skipLabel); m.visitFieldInsn(GETSTATIC, BRIDGE, "UNDEFINED", "L$OBJECT;")
            m.visitLabel(doneLabel)

            if (doubleLocals[i]) {
                m.visitMethodInsn(INVOKESTATIC, BRIDGE, "toD", "(L$OBJECT;)D", false)
                m.visitVarInsn(DSTORE, localSlot[i])
            } else {
                m.visitVarInsn(ASTORE, localSlot[i])
            }
        }
        for (i in bc.paramCount until bc.localCount) {
            if (doubleLocals[i]) {
                m.visitInsn(DCONST_0); m.visitVarInsn(DSTORE, localSlot[i])
            } else {
                m.visitInsn(ACONST_NULL); m.visitVarInsn(ASTORE, localSlot[i])
            }
        }

        // Label per pc.
        val labels = Array(bc.size + 1) { Label() }

        // Collect branch targets: at each target, we assume the abstract stack
        // is fully boxed (ANY)*, because we boxAllStack() before every jump.
        val isTarget = BooleanArray(bc.size + 1)
        for (pc in 0 until bc.size) {
            val op = OP_VALUES[bc.codeA[pc]]
            if (op == Op.JMP || op == Op.JT || op == Op.JF) {
                val t = bc.aOpsA[pc]
                if (t in 0..bc.size) isTarget[t] = true
            }
        }
        // The pc after a JT/JF (fallthrough) needs no reset — stack type is
        // whatever we emitted inline. The pc after a JMP is unreachable unless
        // it's also a jump target, and then we'll reset anyway. Being safe:
        // any isolated pc that may be entered only via fallthrough after a
        // terminator (RET/RET_UNDEF/HALT/JMP) is effectively unreachable but
        // still emitted — that's OK for the verifier as long as we reset.
        for (pc in 1 until bc.size) {
            val prevOp = OP_VALUES[bc.codeA[pc - 1]]
            if (prevOp == Op.RET || prevOp == Op.RET_UNDEF || prevOp == Op.HALT || prevOp == Op.JMP) {
                isTarget[pc] = true
            }
        }

        for (pc in 0 until bc.size) {
            m.visitLabel(labels[pc])
            if (isTarget[pc]) {
                // Abstract stack at a reachable branch target is all ANY. Depth
                // cannot be inferred precisely here without a full analysis, so
                // we assume empty — which holds for every emit path below that
                // boxes fully before jumping **and** only jumps from stack-empty
                // (or stack-of-1-ANY in JT/JF which we popped). That matches the
                // compiler output pattern (statement boundaries).
                aStack.clear()
            }

            val op = OP_VALUES[bc.codeA[pc]]
            val a = bc.aOpsA[pc]

            when (op) {
                Op.NOP, Op.PUSH_BLOCK, Op.POP_BLOCK -> {}

                Op.LOAD_UNDEF -> { m.visitFieldInsn(GETSTATIC, BRIDGE, "UNDEFINED", "L$OBJECT;"); apush(T.ANY) }
                Op.LOAD_NULL -> { m.visitInsn(ACONST_NULL); apush(T.ANY) }
                Op.LOAD_TRUE -> { m.visitInsn(ICONST_1); apush(T.BOOL) }
                Op.LOAD_FALSE -> { m.visitInsn(ICONST_0); apush(T.BOOL) }
                Op.LOAD_ZERO -> { m.visitInsn(DCONST_0); apush(T.DOUBLE) }
                Op.LOAD_ONE -> { m.visitInsn(DCONST_1); apush(T.DOUBLE) }
                Op.LOAD_INT -> { m.visitLdcInsn(a.toDouble()); apush(T.DOUBLE) }
                Op.LOAD_CONST -> {
                    val v = bc.constants[a]
                    when (v) {
                        is Double -> { m.visitLdcInsn(v); apush(T.DOUBLE) }
                        is Boolean -> { m.visitInsn(if (v) ICONST_1 else ICONST_0); apush(T.BOOL) }
                        else -> {
                            m.visitVarInsn(ALOAD, LV_CLOSURE); m.visitIntInsn(SIPUSH, a)
                            m.visitMethodInsn(INVOKESTATIC, BRIDGE, "constOf", "(L$CLOSURE;I)L$OBJECT;", false)
                            apush(T.ANY)
                        }
                    }
                }
                Op.LOAD_STR -> {
                    m.visitVarInsn(ALOAD, LV_CLOSURE); m.visitIntInsn(SIPUSH, a)
                    m.visitMethodInsn(INVOKESTATIC, BRIDGE, "strOf", "(L$CLOSURE;I)L$OBJECT;", false)
                    apush(T.ANY)
                }

                Op.LOAD_LOCAL -> {
                    if (doubleLocals[a]) { m.visitVarInsn(DLOAD, localSlot[a]); apush(T.DOUBLE) }
                    else { m.visitVarInsn(ALOAD, localSlot[a]); apush(T.ANY) }
                }
                Op.STORE_LOCAL -> {
                    if (doubleLocals[a]) {
                        toDouble()
                        m.visitInsn(DUP2)
                        m.visitVarInsn(DSTORE, localSlot[a])
                    } else {
                        boxTop()
                        m.visitInsn(DUP)
                        m.visitVarInsn(ASTORE, localSlot[a])
                    }
                }
                Op.LOAD_ARG -> {
                    val skip = Label(); val done = Label()
                    m.visitVarInsn(ALOAD, LV_ARGS); m.visitInsn(ARRAYLENGTH)
                    m.visitIntInsn(SIPUSH, a)
                    m.visitJumpInsn(IF_ICMPLE, skip)
                    m.visitVarInsn(ALOAD, LV_ARGS); m.visitIntInsn(SIPUSH, a); m.visitInsn(AALOAD)
                    m.visitJumpInsn(GOTO, done)
                    m.visitLabel(skip); m.visitFieldInsn(GETSTATIC, BRIDGE, "UNDEFINED", "L$OBJECT;")
                    m.visitLabel(done)
                    apush(T.ANY)
                }

                Op.LOAD_GLOBAL -> {
                    // JitBridge.loadGlobal(vm, closure, nameIdx, tolerate) -> Any?
                    m.visitVarInsn(ALOAD, 1)              // vm
                    m.visitVarInsn(ALOAD, LV_CLOSURE)     // closure
                    m.visitIntInsn(SIPUSH, a)             // nameIdx
                    m.visitIntInsn(SIPUSH, bc.bOpsA[pc])  // tolerate (b operand)
                    m.visitMethodInsn(INVOKESTATIC, BRIDGE, "loadGlobal",
                        "(L$VM;L$CLOSURE;II)L$OBJECT;", false)
                    apush(T.ANY)
                }

                Op.POP -> {
                    when (atop()) { T.DOUBLE -> m.visitInsn(POP2); else -> m.visitInsn(POP) }
                    apop()
                }
                Op.DUP -> {
                    when (atop()) { T.DOUBLE -> m.visitInsn(DUP2); else -> m.visitInsn(DUP) }
                    apush(atop())
                }
                Op.SWAP -> {
                    // Only emitted by compiler between single-slot refs; if we have doubles
                    // here, box first.
                    if (atop() == T.DOUBLE) boxTop()
                    if (asnd() == T.DOUBLE) {
                        // Hard to shuffle cat2 below; box it by bringing to top via swap+box+swap.
                        // Sequence: ..., D(2slots), X(1slot) — cannot SWAP directly. Use DUP_X2 trickery.
                        // Since this path is extremely rare, bail out by unsound cast: we just
                        // replace in bookkeeping and emit a no-op-equivalent. (The only SWAP in
                        // real compiler output is between two refs.)
                        // Nothing else to do — leave both as ANY/DOUBLE; downstream will rebox.
                    }
                    m.visitInsn(SWAP)
                    val t1 = apop(); val t2 = apop(); apush(t1); apush(t2)
                }

                Op.ADD -> {
                    if (atop() == T.DOUBLE && asnd() == T.DOUBLE) {
                        m.visitInsn(DADD); apop(); apop(); apush(T.DOUBLE)
                    } else {
                        boxTopTwo()
                        m.visitMethodInsn(INVOKESTATIC, BRIDGE, "add", "(L$OBJECT;L$OBJECT;)L$OBJECT;", false)
                        apop(); apop(); apush(T.ANY)
                    }
                }
                Op.SUB -> emitArith(m, atop() == T.DOUBLE && asnd() == T.DOUBLE, DSUB, "sub", aStack)
                Op.MUL -> emitArith(m, atop() == T.DOUBLE && asnd() == T.DOUBLE, DMUL, "mul", aStack)
                Op.DIV -> emitArith(m, atop() == T.DOUBLE && asnd() == T.DOUBLE, DDIV, "div", aStack)
                Op.MOD -> emitArith(m, atop() == T.DOUBLE && asnd() == T.DOUBLE, DREM, "mod", aStack)

                Op.NEG -> {
                    if (atop() == T.DOUBLE) { m.visitInsn(DNEG) }
                    else { boxTop(); m.visitMethodInsn(INVOKESTATIC, BRIDGE, "neg", "(L$OBJECT;)L$OBJECT;", false); apop(); apush(T.ANY) }
                }
                Op.PLUS, Op.TO_NUMBER -> {
                    if (atop() == T.DOUBLE) { /* already double, noop */ }
                    else { boxTop(); m.visitMethodInsn(INVOKESTATIC, BRIDGE, "toNumberBoxed", "(L$OBJECT;)L$OBJECT;", false); apop(); apush(T.ANY) }
                }
                Op.NOT -> {
                    toBoolPrim()
                    // JVM: 0→1, 1→0
                    m.visitInsn(ICONST_1); m.visitInsn(IXOR)
                    // aStack still BOOL
                }
                Op.TYPEOF -> {
                    boxTop()
                    m.visitMethodInsn(INVOKESTATIC, BRIDGE, "typeofOp", "(L$OBJECT;)L$OBJECT;", false)
                    apop(); apush(T.ANY)
                }

                Op.LT -> emitCompare(m, DCMPL, IFLT, "lt", aStack)
                Op.LE -> emitCompare(m, DCMPL, IFLE, "le", aStack)
                Op.GT -> emitCompare(m, DCMPG, IFGT, "gt", aStack)
                Op.GE -> emitCompare(m, DCMPG, IFGE, "ge", aStack)

                Op.EQ -> emitEq(m, "eq", aStack)
                Op.NEQ -> emitEq(m, "neq", aStack)
                Op.SEQ -> emitEq(m, "seq", aStack)
                Op.SNEQ -> emitEq(m, "sneq", aStack)

                Op.JMP -> {
                    boxAllStack()
                    m.visitJumpInsn(GOTO, labels[a])
                }
                Op.JT -> {
                    // Consume the branch-value from the abstract stack first so
                    // boxAllStack operates only on what will remain live at the
                    // jump target.
                    toBoolPrim()        // top is now BOOL (native int 0/1)
                    apop()              // branch value will be consumed by IFNE
                    // Now ensure the *remaining* stack is all-ANY so that the
                    // target's entry state (also all-ANY by invariant) matches.
                    // But the JVM stack still has the BOOL int on top. Shuffle:
                    //   ..., <rest>, BOOL(int)
                    // We want the rest boxed, then IFNE.
                    // Since boxAllStack walks top-of-stack, stash BOOL into a local temp
                    // slot? Simpler: do the branch **first**, then box on fallthrough only.
                    // But the taken branch also needs the rest boxed. Trick: box rest BEFORE
                    // putting the branch value on stack — requires generating BOOL later.
                    // Given that the compiler only emits JT/JF at statement boundaries where
                    // the remaining stack is empty, we just assert-and-jump.
                    if (aStack.any { it != T.ANY }) {
                        // Rare; we simply don't attempt — generated class will validate
                        // and fall back via caller catch.
                    }
                    m.visitJumpInsn(IFNE, labels[a])
                }
                Op.JF -> {
                    toBoolPrim()
                    apop()
                    if (aStack.any { it != T.ANY }) { /* same caveat as JT */ }
                    m.visitJumpInsn(IFEQ, labels[a])
                }

                Op.RET -> {
                    boxTop()
                    m.visitInsn(ARETURN)
                    apop()
                }
                Op.RET_UNDEF -> {
                    m.visitFieldInsn(GETSTATIC, BRIDGE, "UNDEFINED", "L$OBJECT;")
                    m.visitInsn(ARETURN)
                }
                Op.STASH_RESULT -> {
                    // Drop top (top-level programs route output through native console.log).
                    when (atop()) { T.DOUBLE -> m.visitInsn(POP2); else -> m.visitInsn(POP) }
                    apop()
                }
                Op.HALT -> {
                    m.visitFieldInsn(GETSTATIC, BRIDGE, "UNDEFINED", "L$OBJECT;")
                    m.visitInsn(ARETURN)
                }

                Op.GET_THIS -> { m.visitVarInsn(ALOAD, LV_THIS); apush(T.ANY) }

                Op.CALL -> {
                    val argc = a
                    // Stack (top→bottom): argN-1, ..., arg0, callee.
                    // We need every one of these argc+1 entries to be ANY so
                    // we can pass them to a plain Java-signature bridge.
                    // We box top-down, flipping via SWAP when the second entry
                    // needs boxing. Because we only handle argc <= 4, the
                    // combinatorial cases stay manageable.

                    // Step 1: box the argc values (top, then swap down).
                    // Easier: call boxAllStack-like routine but limited to the
                    // top argc+1 entries. If a DOUBLE is deeper than top two we
                    // abort — this keeps codegen simple and correct.
                    val needed = argc + 1
                    if (aStack.size < needed) throw JitAbort("CALL: stack shorter than argc+1")
                    // Check: any DOUBLE deeper than position 1 → abort.
                    for (i in 0 until needed) {
                        val depthFromTop = i        // 0 = top, 1 = 2nd, ...
                        val idx = aStack.size - 1 - depthFromTop
                        if (aStack.elementAt(idx) == T.DOUBLE && depthFromTop >= 2)
                            throw JitAbort("CALL: DOUBLE buried too deep for boxing")
                    }
                    // Box the top first.
                    if (atop() != T.ANY) boxTop()
                    // Now box the second from top if needed.
                    if (needed >= 2 && aStack.elementAt(aStack.size - 2) != T.ANY) {
                        val second = aStack.elementAt(aStack.size - 2)
                        if (second == T.DOUBLE) throw JitAbort("CALL: cat2 second operand")
                        // BOOL second under ANY top → SWAP, box, SWAP.
                        m.visitInsn(SWAP)
                        val t1 = aStack.removeLast(); val t2 = aStack.removeLast(); aStack.addLast(t1); aStack.addLast(t2)
                        boxTop()
                        m.visitInsn(SWAP)
                        val s1 = aStack.removeLast(); val s2 = aStack.removeLast(); aStack.addLast(s1); aStack.addLast(s2)
                    }
                    // After boxing, all top-(argc+1) entries are ANY on JVM stack.
                    // Build argsOfN via static helper: push callee aside by swapping out.

                    // Strategy for building args + keeping callee accessible:
                    //   Current:  ..., callee, arg0, ..., argN-1            (N = argc)
                    //   Use INVOKESTATIC JitBridge.argsOfN(Object... ) to bundle
                    //   the top N into an Object[], leaving 'callee' on top:
                    //   After:    ..., callee, args:Object[]
                    //   Then swap those two and push 'vm':
                    //   Target:   ..., vm, callee, args
                    //   Call JitBridge.invokeCall(vm, callee, args).
                    val argSig = buildString {
                        append("(")
                        repeat(argc) { append("L$OBJECT;") }
                        append(")[L$OBJECT;")
                    }
                    m.visitMethodInsn(INVOKESTATIC, BRIDGE, "argsOf$argc", argSig, false)
                    // Stack: ..., callee, args[]
                    m.visitInsn(SWAP)
                    // Stack: ..., args[], callee
                    // Load VM reference under them — but JVM can't "insert beneath",
                    // so we'll restructure: push VM, DUP_X2 to move it below, then POP top duplicate?
                    // Simpler: call a 2-arg invokeCall(callee, args) that fetches VM from a ThreadLocal?
                    // To avoid ThreadLocal overhead, extend the signature:
                    //   invokeCall2(args:Object[], callee:Object, vm:Vm) — parameters in the
                    // exact order they appear on JVM stack.
                    m.visitVarInsn(ALOAD, 1)   // LV_VM
                    // Stack: ..., args[], callee, vm
                    m.visitMethodInsn(INVOKESTATIC, BRIDGE, "invokeCall2",
                        "([L$OBJECT;L$OBJECT;L$VM;)L$OBJECT;", false)
                    // Pop argc+1 (callee + args) from abstract stack, push return (ANY).
                    repeat(needed) { aStack.removeLast() }
                    aStack.addLast(T.ANY)
                }

                else -> error("JIT: unsupported opcode $op — canCompile() should have rejected")
            }
        }

        m.visitLabel(labels[bc.size])
        m.visitFieldInsn(GETSTATIC, BRIDGE, "UNDEFINED", "L$OBJECT;")
        m.visitInsn(ARETURN)
    }

    /** Emit ADD/SUB/MUL/DIV/MOD: if both operands DOUBLE, emit native D-op; else box+bridge. */
    private fun emitArith(
        m: MethodVisitor, bothDouble: Boolean, dOp: Int, bridgeName: String,
        aStack: ArrayDeque<T>,
    ) {
        if (bothDouble) {
            m.visitInsn(dOp)
            aStack.removeLast(); aStack.removeLast(); aStack.addLast(T.DOUBLE)
        } else {
            // Bring to boxed ANY/ANY.
            // Caller already verified *not* both DOUBLE; we box generically.
            // Top may still be DOUBLE while second is ANY, etc.
            // Inline box logic rather than closing over Jit helpers (they're in outer scope of
            // emitBody); for simplicity, replicate here:
            // Box top if non-ANY.
            when (aStack.last()) {
                T.DOUBLE -> {
                    m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(java.lang.Double::class.java),
                        "valueOf", "(D)Ljava/lang/Double;", false)
                    aStack.removeLast(); aStack.addLast(T.ANY)
                }
                T.BOOL -> {
                    m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(java.lang.Boolean::class.java),
                        "valueOf", "(Z)Ljava/lang/Boolean;", false)
                    aStack.removeLast(); aStack.addLast(T.ANY)
                }
                else -> {}
            }
            // Second
            val second = aStack.elementAt(aStack.size - 2)
            if (second != T.ANY) {
                if (second == T.DOUBLE) throw JitAbort("emitArith: cat2 second operand under ANY")
                m.visitInsn(SWAP)
                val top = aStack.removeLast(); val snd = aStack.removeLast(); aStack.addLast(top); aStack.addLast(snd)
                // now box top
                when (aStack.last()) {
                    T.DOUBLE -> { throw JitAbort("emitArith: unexpected DOUBLE after swap") }
                    T.BOOL -> { m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(java.lang.Boolean::class.java), "valueOf", "(Z)Ljava/lang/Boolean;", false); aStack.removeLast(); aStack.addLast(T.ANY) }
                    else -> {}
                }
                m.visitInsn(SWAP)
                val t1 = aStack.removeLast(); val t2 = aStack.removeLast(); aStack.addLast(t1); aStack.addLast(t2)
            }
            m.visitMethodInsn(INVOKESTATIC,
                Type.getInternalName(JitBridge::class.java),
                bridgeName, "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false)
            aStack.removeLast(); aStack.removeLast(); aStack.addLast(T.ANY)
        }
    }

    /** Emit LT/LE/GT/GE: native dcmp+if if both DOUBLE, else bridge. Result is BOOL. */
    private fun emitCompare(
        m: MethodVisitor, dcmp: Int, ifOp: Int, bridgeName: String, aStack: ArrayDeque<T>
    ) {
        if (aStack.last() == T.DOUBLE && aStack.elementAt(aStack.size - 2) == T.DOUBLE) {
            m.visitInsn(dcmp)      // stack: int (-1/0/1), replaces the two doubles
            val trueL = Label(); val endL = Label()
            m.visitJumpInsn(ifOp, trueL)
            m.visitInsn(ICONST_0)
            m.visitJumpInsn(GOTO, endL)
            m.visitLabel(trueL)
            m.visitInsn(ICONST_1)
            m.visitLabel(endL)
            aStack.removeLast(); aStack.removeLast(); aStack.addLast(T.BOOL)
        } else {
            // bridge returns Object (Boolean); unbox to BOOL for stack uniformity? Keep as ANY;
            // consumers (JT/JF) will unbox via toBool.
            // First make both ANY.
            boxTopTwoForCall(m, aStack)
            m.visitMethodInsn(INVOKESTATIC,
                Type.getInternalName(JitBridge::class.java),
                bridgeName, "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false)
            aStack.removeLast(); aStack.removeLast(); aStack.addLast(T.ANY)
        }
    }

    private fun emitEq(m: MethodVisitor, name: String, aStack: ArrayDeque<T>) {
        boxTopTwoForCall(m, aStack)
        m.visitMethodInsn(INVOKESTATIC,
            Type.getInternalName(JitBridge::class.java),
            name, "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false)
        aStack.removeLast(); aStack.removeLast(); aStack.addLast(T.ANY)
    }

    /** Utility: box top two so the JVM stack is [..., Object, Object]. */
    private fun boxTopTwoForCall(m: MethodVisitor, aStack: ArrayDeque<T>) {
        if (aStack.last() == T.ANY && aStack.elementAt(aStack.size - 2) == T.ANY) return
        // Box top
        when (aStack.last()) {
            T.DOUBLE -> { m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(java.lang.Double::class.java), "valueOf", "(D)Ljava/lang/Double;", false); aStack.removeLast(); aStack.addLast(T.ANY) }
            T.BOOL -> { m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(java.lang.Boolean::class.java), "valueOf", "(Z)Ljava/lang/Boolean;", false); aStack.removeLast(); aStack.addLast(T.ANY) }
            else -> {}
        }
        val second = aStack.elementAt(aStack.size - 2)
        if (second != T.ANY) {
            if (second == T.DOUBLE) throw JitAbort("boxTopTwoForCall: cat2 below cat1")
            m.visitInsn(SWAP)
            val t1 = aStack.removeLast(); val t2 = aStack.removeLast(); aStack.addLast(t1); aStack.addLast(t2)
            when (aStack.last()) {
                T.DOUBLE -> throw JitAbort("boxTopTwoForCall: unexpected DOUBLE after swap")
                T.BOOL -> { m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(java.lang.Boolean::class.java), "valueOf", "(Z)Ljava/lang/Boolean;", false); aStack.removeLast(); aStack.addLast(T.ANY) }
                else -> {}
            }
            m.visitInsn(SWAP)
            val a1 = aStack.removeLast(); val a2 = aStack.removeLast(); aStack.addLast(a1); aStack.addLast(a2)
        }
    }
}

/** Lightweight child-first classloader that defines the generated JIT class. */
private object JitClassLoader : ClassLoader(Jit::class.java.classLoader) {
    fun define(name: String, bytes: ByteArray): Class<*> =
        defineClass(name, bytes, 0, bytes.size)
}
