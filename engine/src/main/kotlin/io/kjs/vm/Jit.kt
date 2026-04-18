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
 *  - One generated class per [Bytecode], subclassing [Compiled].
 *  - The VM's operand stack is mapped *directly* onto the JVM's own operand
 *    stack — KJS being a stack machine itself makes this 1:1 for many opcodes.
 *  - KJS locals/args live in JVM local variable slots wrapping them as `Any?`.
 *  - Only a pragmatic subset of opcodes is compiled: arithmetic, comparisons,
 *    local load/store, constants, unconditional + conditional jumps, RET.
 *    Anything else (CALL, property access, exceptions, closures, etc.) is
 *    routed back to the interpreter via `Vm.runOpcodeFromJit(pc)`.
 *
 * ## Why this helps
 *  The JVM's C2 then compiles our generated class to native code, inlining
 *  arithmetic, unrolling tight loops, and doing proper register allocation.
 *  We essentially piggy-back on HotSpot instead of writing a machine-code
 *  backend ourselves.
 *
 * ## Known limits (left for a future iteration)
 *  - Values are still boxed `Any?` (java.lang.Double etc.). True unboxed
 *    performance needs NaN-boxing into `LongArray` operand stacks.
 *  - The method itself is not re-compilable/deoptimizable.
 *  - Nested functions are JITed independently on their own hotness.
 */
object Jit {
    private val threshold: Int = System.getenv("KJS_JIT_THRESHOLD")?.toIntOrNull() ?: 3
    private val disabled: Boolean = System.getenv("KJS_JIT")?.lowercase() in setOf("0", "off", "false", "no")
    private val verbose: Boolean = System.getenv("KJS_JIT_VERBOSE")?.isNotEmpty() == true

    /** Called by Vm once a function crosses the hotness threshold. */
    fun shouldCompile(hotness: Int): Boolean = !disabled && hotness == threshold

    internal fun log(msg: () -> String) { if (verbose) System.err.println("[jit] ${msg()}") }

    // ---------- supported opcode predicate ----------
    // Conservative whitelist: arithmetic, comparisons, locals, unconditional +
    // conditional jumps, argument loads, and returns. The moment a function needs
    // a CALL, property access, closure construction, or exception handling we
    // leave it to the interpreter — implementing those correctly in raw JVM
    // bytecode is error-prone and rarely a speed win given HotSpot already
    // inlines the interpreter paths.
    private val supported = setOf(
        Op.NOP,
        Op.LOAD_UNDEF, Op.LOAD_NULL, Op.LOAD_TRUE, Op.LOAD_FALSE,
        Op.LOAD_ZERO, Op.LOAD_ONE, Op.LOAD_INT, Op.LOAD_CONST, Op.LOAD_STR,
        Op.LOAD_LOCAL, Op.STORE_LOCAL, Op.LOAD_ARG,
        Op.POP, Op.DUP, Op.SWAP,
        Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD,
        Op.NEG, Op.PLUS, Op.NOT, Op.TO_NUMBER, Op.TYPEOF,
        Op.EQ, Op.NEQ, Op.SEQ, Op.SNEQ, Op.LT, Op.LE, Op.GT, Op.GE,
        Op.JMP, Op.JT, Op.JF,
        Op.RET, Op.RET_UNDEF,
        Op.STASH_RESULT, Op.HALT,
        Op.GET_THIS,
        Op.PUSH_BLOCK, Op.POP_BLOCK,   // no-ops in the VM; emitted for lexical scopes
    )

    fun canCompile(bc: Bytecode): Boolean {
        if (disabled) return false
        for (i in 0 until bc.size) {
            val op = OP_VALUES[bc.codeA[i]]
            if (op !in supported) {
                log { "${bc.name}: unsupported opcode $op at pc=$i" }
                return false
            }
        }
        return true
    }

    // ---------- emitter ----------
    private val classCounter = java.util.concurrent.atomic.AtomicLong(0)

    private val OBJECT = Type.getInternalName(Any::class.java)
    private val COMPILED = Type.getInternalName(Compiled::class.java)
    private val VM = Type.getInternalName(Vm::class.java)
    private val REALM = Type.getInternalName(io.kjs.runtime.Realm::class.java)
    private val CLOSURE = Type.getInternalName(VmClosure::class.java)
    private val BRIDGE = Type.getInternalName(JitBridge::class.java)
    private val DOUBLE = Type.getInternalName(java.lang.Double::class.java)
    private val BOOLEAN = Type.getInternalName(java.lang.Boolean::class.java)

    /**
     * Generate a [Compiled] for this bytecode; caller has already verified
     * [canCompile] returns true.
     */
    fun compile(bc: Bytecode): Compiled {
        val id = classCounter.incrementAndGet()
        // Sanitise name so symbols like "<main>" don't blow up class loading.
        val safe = bc.name.filter { it.isLetterOrDigit() }.ifEmpty { "anon" }
        val className = "io/kjs/jit/Jit_${safe}_$id"
        log { "compiling ${bc.name} (${bc.size} opcodes) → $className" }
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(V17, ACC_PUBLIC or ACC_FINAL, className, null, COMPILED, null)

        // public no-arg ctor
        run {
            val m = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
            m.visitCode()
            m.visitVarInsn(ALOAD, 0)
            m.visitMethodInsn(INVOKESPECIAL, COMPILED, "<init>", "()V", false)
            m.visitInsn(RETURN)
            m.visitMaxs(-1, -1)
            m.visitEnd()
        }

        // public Object invoke(Vm, Realm, VmClosure, Object this, Object[] args)
        run {
            val m = cw.visitMethod(
                ACC_PUBLIC,
                "invoke",
                "(L$VM;L$REALM;L$CLOSURE;L$OBJECT;[L$OBJECT;)L$OBJECT;",
                null, null,
            )
            m.visitCode()
            emitBody(m, bc, className)
            m.visitMaxs(-1, -1)
            m.visitEnd()
        }
        cw.visitEnd()

        val bytes = cw.toByteArray()
        val cls = JitClassLoader.define(className.replace('/', '.'), bytes)
        return cls.getDeclaredConstructor().newInstance() as Compiled
    }

    /** Emit the instruction-by-instruction translation. */
    private fun emitBody(m: MethodVisitor, bc: Bytecode, className: String) {
        // Param local indices (this=0, Vm=1, Realm=2, Closure=3, thisVal=4, args=5).
        val LV_VM = 1
        val LV_REALM = 2
        val LV_CLOSURE = 3
        val LV_THIS = 4
        val LV_ARGS = 5
        // KJS locals live at JVM local slots starting from FIRST_LOCAL_SLOT.
        val FIRST_LOCAL_SLOT = 6
        val localSlot: (Int) -> Int = { FIRST_LOCAL_SLOT + it }

        // Initialise local slots used by the function from `args` (paramCount prefix).
        for (i in 0 until bc.paramCount) {
            // if (i < args.length) args[i] else Undefined
            val skipLabel = Label(); val doneLabel = Label()
            m.visitVarInsn(ALOAD, LV_ARGS)
            m.visitInsn(ARRAYLENGTH)
            m.visitIntInsn(SIPUSH, i)
            m.visitJumpInsn(IF_ICMPLE, skipLabel)        // if args.length <= i goto skip
            m.visitVarInsn(ALOAD, LV_ARGS)
            m.visitIntInsn(SIPUSH, i)
            m.visitInsn(AALOAD)
            m.visitJumpInsn(GOTO, doneLabel)
            m.visitLabel(skipLabel)
            m.visitFieldInsn(GETSTATIC, BRIDGE, "UNDEFINED", "L$OBJECT;")
            m.visitLabel(doneLabel)
            m.visitVarInsn(ASTORE, localSlot(i))
        }
        // Initialise remaining locals to null so JVM verifier is happy.
        for (i in bc.paramCount until bc.localCount) {
            m.visitInsn(ACONST_NULL)
            m.visitVarInsn(ASTORE, localSlot(i))
        }

        // One JVM label per pc (target of jumps).
        val labels = Array(bc.size + 1) { Label() }

        for (pc in 0 until bc.size) {
            m.visitLabel(labels[pc])
            val op = OP_VALUES[bc.codeA[pc]]
            val a = bc.aOpsA[pc]

            when (op) {
                Op.NOP -> {}
                Op.PUSH_BLOCK -> {}
                Op.POP_BLOCK -> {}
                Op.LOAD_UNDEF -> m.visitFieldInsn(GETSTATIC, BRIDGE, "UNDEFINED", "L$OBJECT;")
                Op.LOAD_NULL -> m.visitInsn(ACONST_NULL)
                Op.LOAD_TRUE -> pushBool(m, true)
                Op.LOAD_FALSE -> pushBool(m, false)
                Op.LOAD_ZERO -> pushDouble(m, 0.0)
                Op.LOAD_ONE -> pushDouble(m, 1.0)
                Op.LOAD_INT -> pushDouble(m, a.toDouble())
                Op.LOAD_CONST -> {
                    // bridge.constOf(closure.bc, idx)
                    m.visitVarInsn(ALOAD, LV_CLOSURE)
                    m.visitIntInsn(SIPUSH, a)
                    m.visitMethodInsn(INVOKESTATIC, BRIDGE, "constOf",
                        "(L$CLOSURE;I)L$OBJECT;", false)
                }
                Op.LOAD_STR -> {
                    m.visitVarInsn(ALOAD, LV_CLOSURE)
                    m.visitIntInsn(SIPUSH, a)
                    m.visitMethodInsn(INVOKESTATIC, BRIDGE, "strOf",
                        "(L$CLOSURE;I)L$OBJECT;", false)
                }
                Op.LOAD_LOCAL -> m.visitVarInsn(ALOAD, localSlot(a))
                Op.STORE_LOCAL -> {
                    // Leaves value on stack (like interpreter): DUP, store copy.
                    m.visitInsn(DUP)
                    m.visitVarInsn(ASTORE, localSlot(a))
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
                }
                Op.POP -> m.visitInsn(POP)
                Op.DUP -> m.visitInsn(DUP)
                Op.SWAP -> m.visitInsn(SWAP)

                Op.ADD -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "add", "(L$OBJECT;L$OBJECT;)L$OBJECT;", false)
                Op.SUB -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "sub", "(L$OBJECT;L$OBJECT;)L$OBJECT;", false)
                Op.MUL -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "mul", "(L$OBJECT;L$OBJECT;)L$OBJECT;", false)
                Op.DIV -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "div", "(L$OBJECT;L$OBJECT;)L$OBJECT;", false)
                Op.MOD -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "mod", "(L$OBJECT;L$OBJECT;)L$OBJECT;", false)
                Op.NEG -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "neg", "(L$OBJECT;)L$OBJECT;", false)
                Op.NOT -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "not", "(L$OBJECT;)L$OBJECT;", false)
                Op.TO_NUMBER -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "toNumberBoxed", "(L$OBJECT;)L$OBJECT;", false)
                Op.EQ -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "eq", "(L$OBJECT;L$OBJECT;)L$OBJECT;", false)
                Op.NEQ -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "neq", "(L$OBJECT;L$OBJECT;)L$OBJECT;", false)
                Op.SEQ -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "seq", "(L$OBJECT;L$OBJECT;)L$OBJECT;", false)
                Op.SNEQ -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "sneq", "(L$OBJECT;L$OBJECT;)L$OBJECT;", false)
                Op.LT -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "lt", "(L$OBJECT;L$OBJECT;)L$OBJECT;", false)
                Op.LE -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "le", "(L$OBJECT;L$OBJECT;)L$OBJECT;", false)
                Op.GT -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "gt", "(L$OBJECT;L$OBJECT;)L$OBJECT;", false)
                Op.GE -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "ge", "(L$OBJECT;L$OBJECT;)L$OBJECT;", false)

                Op.JMP -> m.visitJumpInsn(GOTO, labels[a])
                Op.JT -> {
                    // pop and branch if truthy
                    m.visitMethodInsn(INVOKESTATIC, BRIDGE, "toBool", "(L$OBJECT;)Z", false)
                    m.visitJumpInsn(IFNE, labels[a])
                }
                Op.JF -> {
                    m.visitMethodInsn(INVOKESTATIC, BRIDGE, "toBool", "(L$OBJECT;)Z", false)
                    m.visitJumpInsn(IFEQ, labels[a])
                }

                Op.RET -> m.visitInsn(ARETURN)
                Op.RET_UNDEF -> {
                    m.visitFieldInsn(GETSTATIC, BRIDGE, "UNDEFINED", "L$OBJECT;")
                    m.visitInsn(ARETURN)
                }
                Op.STASH_RESULT -> m.visitInsn(POP)       // top-level result handling is subsumed by final HALT returning undefined
                Op.HALT -> {
                    m.visitFieldInsn(GETSTATIC, BRIDGE, "UNDEFINED", "L$OBJECT;")
                    m.visitInsn(ARETURN)
                }

                Op.GET_THIS -> m.visitVarInsn(ALOAD, LV_THIS)

                Op.PLUS -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "toNumberBoxed", "(L$OBJECT;)L$OBJECT;", false)
                Op.TYPEOF -> m.visitMethodInsn(INVOKESTATIC, BRIDGE, "typeofOp", "(L$OBJECT;)L$OBJECT;", false)

                else -> error("JIT: unsupported opcode $op — canCompile() should have rejected")
            }
        }
        // End label (target of jumps that would overflow the array).
        m.visitLabel(labels[bc.size])
        m.visitFieldInsn(GETSTATIC, BRIDGE, "UNDEFINED", "L$OBJECT;")
        m.visitInsn(ARETURN)
    }

    private fun pushBool(m: MethodVisitor, v: Boolean) {
        m.visitFieldInsn(GETSTATIC, BOOLEAN, if (v) "TRUE" else "FALSE", "Ljava/lang/Boolean;")
    }
    private fun pushDouble(m: MethodVisitor, v: Double) {
        m.visitLdcInsn(v)
        m.visitMethodInsn(INVOKESTATIC, DOUBLE, "valueOf", "(D)Ljava/lang/Double;", false)
    }
}

/** Lightweight child-first classloader that defines the generated JIT class. */
private object JitClassLoader : ClassLoader(Jit::class.java.classLoader) {
    fun define(name: String, bytes: ByteArray): Class<*> =
        defineClass(name, bytes, 0, bytes.size)
}
