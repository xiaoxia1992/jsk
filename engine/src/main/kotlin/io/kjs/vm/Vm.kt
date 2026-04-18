package io.kjs.vm

import io.kjs.ir.Bytecode
import io.kjs.ir.Compiler
import io.kjs.ir.OP_VALUES
import io.kjs.ir.Op
import io.kjs.ir.upvalueInfo
import io.kjs.runtime.*

/** Runtime binding for a captured variable. Mutable box so multiple closures share state. */
class Upvalue(var value: Any?)

/** A compiled user function ready for the VM. Mirrors [JsFunction.user] but carries bytecode. */
class VmClosure(
    val bc: Bytecode,
    val closureEnv: Environment?,   // nullable for top-level
    val upvalues: Array<Upvalue>,
) {
    /** Invocation counter used to decide when to JIT-compile. */
    @JvmField var hotness: Int = 0
    /** Non-null once JIT'd; then execution bypasses the opcode dispatch loop.
     *  Volatile because the background compiler thread publishes this field. */
    @JvmField @Volatile var compiled: Compiled? = null
    /** Sticks at `true` if canCompile() refused; prevents repeated attempts. */
    @JvmField @Volatile var jitRejected: Boolean = false
    /** True between "compilation requested" and "result published or failed". */
    @JvmField @Volatile var compilePending: Boolean = false
    /** How many times we've called the JIT-compiled code so far (for telemetry). */
    @JvmField var jitCalls: Int = 0
}

/**
 * Stack-based bytecode VM. One [Frame] per active call.
 *
 * Dispatch is a hot `while (true) when (op)` block. Kotlin compiles `when` over
 * an Int-valued expression into a tableswitch, which is the best the JVM can do
 * without dedicated intrinsics. This is plenty fast for the M2 baseline; M5's
 * IC and template JIT layer on top without changing this outer shape.
 */
class Vm(val realm: Realm) {
    /** Optional narrative tracer; when non-null the VM reports each opcode step. */
    var tracer: io.kjs.Tracer? = null
    private var frameDepth = 0

    companion object {
        /** Sentinel upvalue box used for free globals. */
        private val EMPTY_UPVALS = emptyArray<Upvalue>()
    }

    private class Frame(
        val bc: Bytecode,
        val stack: Array<Any?>,
        val locals: Array<Any?>,
        val closureEnv: Environment?,
        val upvalues: Array<Upvalue>,
        val thisVal: Any?,
        val args: Array<Any?>,
    ) {
        var sp: Int = 0
        var pc: Int = 0
        var lastResult: Any? = JsValues.UNDEFINED
        var pendingThrow: Any? = null
        // Handler stack: packed as [catchPc, finallyPc, enterSp] triples.
        val handlers = IntArray(64)
        var handlerTop = 0

        fun push(v: Any?) { stack[sp++] = v }
        fun pop(): Any? { sp--; val v = stack[sp]; stack[sp] = null; return v }
        fun peek(): Any? = stack[sp - 1]
    }

    /** Run a top-level (no-arg) bytecode program. Returns its `lastResult`. */
    fun run(bc: Bytecode): Any? {
        val closure = VmClosure(bc, realm.globalEnv, EMPTY_UPVALS)
        val topFn = JsFunction.native("<main>") { _, _ -> JsValues.UNDEFINED }
        // Instead install as callable via execClosure.
        return execClosure(closure, thisVal = realm.globalObject, args = emptyList())
    }

    /** Create a JsFunction that, when called, dispatches back into the VM. */
    fun makeJsFunction(bc: Bytecode, closureEnv: Environment?, upvalues: Array<Upvalue>): JsFunction {
        val name = bc.name
        val fn = JsFunction.native(name, bc.paramCount) { _, _ -> JsValues.UNDEFINED }
        fn.set("name", name); fn.set("length", bc.paramCount.toDouble())
        if (!bc.isArrow) {
            val protoObj = JsObject(realm.objectProto); protoObj.set("constructor", fn)
            fn.set("prototype", protoObj)
        } else fn.set("__arrow__", true)
        fn.proto = realm.functionProto
        val vc = VmClosure(bc, closureEnv, upvalues)
        fn.vmClosure = vc                                    // fast-path slot; avoids property lookup
        fn.invoker = { _, thisVal, args -> execClosure(vc, thisVal, args) }
        return fn
    }

    private class VmClosureHolder(val c: VmClosure)

    private fun closureOf(fn: JsFunction): VmClosure? =
        (fn.getOwn("__vm_closure__") as? VmClosureHolder)?.c

    private fun invokeVmFunction(fn: JsFunction, thisVal: Any?, args: List<Any?>): Any? {
        val c = closureOf(fn) ?: error("not a vm closure: ${fn.get("name")}")
        return execClosure(c, thisVal, args)
    }

    // Pools of reusable arrays; reduces allocation pressure for recursive workloads.
    private val stackPool = ArrayDeque<Array<Any?>>()
    private val localsPool = ArrayDeque<Array<Any?>>()
    private fun borrowStack(): Array<Any?> = if (stackPool.isNotEmpty()) stackPool.removeLast() else arrayOfNulls(256)
    private fun borrowLocals(minSize: Int): Array<Any?> {
        while (localsPool.isNotEmpty()) {
            val a = localsPool.removeLast()
            if (a.size >= minSize) return a
        }
        return arrayOfNulls(maxOf(minSize, 8))
    }
    private fun releaseStack(a: Array<Any?>, spAtReturn: Int) {
        // Null out any residue so we don't leak references into pooled memory.
        for (i in 0 until spAtReturn) a[i] = null
        if (stackPool.size < 64) stackPool.addLast(a)
    }
    private fun releaseLocals(a: Array<Any?>, used: Int) {
        for (i in 0 until used) a[i] = null
        if (localsPool.size < 64) localsPool.addLast(a)
    }

    private fun execClosure(c: VmClosure, thisVal: Any?, args: List<Any?>): Any? {
        val arr = Array<Any?>(args.size) { args[it] }
        return execClosureArr(c, thisVal, arr)
    }

    private fun execClosureArr(c: VmClosure, thisVal: Any?, argsArr: Array<Any?>): Any? {
        // JIT fast path: if already compiled, invoke the generated class directly.
        val already = c.compiled
        if (already != null) {
            c.jitCalls++
            when {
                Jit.logLevel >= 2 -> Jit.trace { "→ ${c.bc.name}() [JIT, #${c.jitCalls} call on compiled code]" }
                Jit.logLevel >= 1 && c.jitCalls == 1 ->
                    Jit.log { "✓ ${c.bc.name} now running on JIT-compiled code (first call)" }
                Jit.logLevel >= 1 && c.jitCalls > 0 && c.jitCalls % 10000 == 0 ->
                    Jit.log { "· ${c.bc.name} reached ${c.jitCalls} JIT calls" }
            }
            return already.invoke(this, realm, c, thisVal, argsArr)
        }

        // Interpreter path — maybe triggering compilation.
        if (!c.jitRejected && c.compiled == null) {
            c.hotness++
            if (Jit.logLevel >= 2) {
                val remaining = Jit.threshold - c.hotness
                val tag = when {
                    c.hotness < Jit.threshold -> "→ ${c.bc.name}() [interp, hotness ${c.hotness}/${Jit.threshold}, $remaining more to trigger JIT]"
                    c.hotness == Jit.threshold -> "→ ${c.bc.name}() [interp, hotness ${c.hotness}/${Jit.threshold} — scheduling compile]"
                    else                       -> "→ ${c.bc.name}() [interp, hotness ${c.hotness}, compile in flight]"
                }
                Jit.trace { tag }
            }
            if (Jit.shouldCompile(c.hotness)) {
                // Schedule compilation on the background thread (or synchronous
                // if KJS_JIT_ASYNC=off). This call never blocks the hot path.
                Jit.requestCompile(c)
                // If async-disabled (synchronous), `c.compiled` may already be
                // non-null here; take the fast path below. Otherwise fall
                // through to the interpreter for this invocation.
                val justPublished = c.compiled
                if (justPublished != null) {
                    c.jitCalls = 1
                    return justPublished.invoke(this, realm, c, thisVal, argsArr)
                }
            }
        }

        val bc = c.bc
        val localsSize = maxOf(bc.localCount, bc.paramCount) + 4
        val stack = borrowStack()
        val locals = borrowLocals(localsSize)
        val frame = Frame(
            bc = bc,
            stack = stack,
            locals = locals,
            closureEnv = c.closureEnv,
            upvalues = c.upvalues,
            thisVal = thisVal,
            args = argsArr,
        )
        // bind params into slot 0..paramCount-1
        val pc = bc.paramCount
        for (i in 0 until pc) frame.locals[i] = if (i < argsArr.size) argsArr[i] else JsValues.UNDEFINED
        try {
            return runFrame(frame)
        } finally {
            releaseStack(stack, frame.sp)
            releaseLocals(locals, localsSize)
        }
    }

    /** Fast path for VM closures: avoid the lambda indirection + List.toList allocation. */
    internal fun invokeFast(fn: JsFunction, thisVal: Any?, argsArr: Array<Any?>): Any? {
        val vc = fn.vmClosure as? VmClosure
        if (vc != null) return execClosureArr(vc, thisVal, argsArr)
        // Fallback to generic path (native functions, bound functions, etc.).
        return fn.call(thisVal, argsArr.toList())
    }

    // ---- main dispatch loop ----
    private fun runFrame(f: Frame): Any? {
        val bc = f.bc
        val code = bc.codeA; val aOps = bc.aOpsA; val bOps = bc.bOpsA
        val strings = bc.strings; val constants = bc.constants
        frameDepth++
        val traceHere = tracer != null && frameDepth == 1
        try {
            return runLoop(f, bc, code, aOps, bOps, strings, constants, traceHere)
        } finally {
            frameDepth--
        }
    }

    private fun runLoop(
        f: Frame, bc: Bytecode,
        code: IntArray, aOps: IntArray, bOps: IntArray,
        strings: ArrayList<String>, constants: ArrayList<Any?>,
        traceHere: Boolean,
    ): Any? {
        while (true) {
            val pc = f.pc
            val op = OP_VALUES[code[pc]]
            val a = aOps[pc]; val b = bOps[pc]
            if (traceHere) tracer!!.onVmStep(pc, op, a, b, snapshot(f))
            f.pc = pc + 1
            try {
                when (op) {
                    Op.NOP -> {}
                    Op.LOAD_UNDEF -> f.push(JsValues.UNDEFINED)
                    Op.LOAD_NULL -> f.push(null)
                    Op.LOAD_TRUE -> f.push(true)
                    Op.LOAD_FALSE -> f.push(false)
                    Op.LOAD_ZERO -> f.push(0.0)
                    Op.LOAD_ONE -> f.push(1.0)
                    Op.LOAD_INT -> f.push(a.toDouble())
                    Op.LOAD_CONST -> f.push(constants[a])
                    Op.LOAD_STR -> f.push(strings[a])

                    Op.LOAD_LOCAL -> {
                        val raw = f.locals[a]
                        f.push(if (raw is Upvalue) raw.value else (raw ?: JsValues.UNDEFINED))
                    }
                    Op.STORE_LOCAL -> {
                        val raw = f.locals[a]
                        val v = f.peek()
                        if (raw is Upvalue) raw.value = v else f.locals[a] = v
                    }
                    Op.LOAD_ARG -> f.push(if (a < f.args.size) f.args[a] else JsValues.UNDEFINED)
                    Op.STORE_ARG -> { if (a < f.args.size) f.args[a] = f.peek() }

                    Op.LOAD_GLOBAL -> {
                        val name = strings[a]
                        val tolerateUndef = b != 0
                        val e = f.closureEnv ?: realm.globalEnv
                        if (!e.has(name)) {
                            if (tolerateUndef) f.push(JsValues.UNDEFINED)
                            else jsThrowName(realm, "ReferenceError", "$name is not defined")
                        } else f.push(e.get(name))
                    }
                    Op.STORE_GLOBAL -> {
                        val name = strings[a]
                        (f.closureEnv ?: realm.globalEnv).setOrDeclareGlobal(name, f.peek())
                    }
                    Op.DECL_GLOBAL -> {
                        val name = strings[a]
                        (f.closureEnv ?: realm.globalEnv).declare(name, f.peek())
                        f.pop()
                    }

                    Op.LOAD_UPVAL -> f.push(f.upvalues[a].value)
                    Op.STORE_UPVAL -> { f.upvalues[a].value = f.peek() }

                    Op.LOAD_PROP -> {
                        val obj = f.pop()
                        val name = strings[a]
                        val v = if (obj is JsObject) {
                            val caches = bc.caches ?: arrayOfNulls<Any?>(code.size).also { bc.caches = it }
                            val ic = caches[pc] as? PropIc ?: PropIc().also { caches[pc] = it }
                            ic.get(obj, name) { o, n -> if (o == null) JsValues.UNDEFINED else o.get(n) }
                        } else propGet(obj, name)
                        f.push(v)
                    }
                    Op.STORE_PROP -> {
                        val value = f.pop(); val obj = f.pop()
                        propSet(obj, strings[a], value); f.push(value)
                    }
                    Op.LOAD_ELEM -> {
                        val key = f.pop(); val obj = f.pop()
                        f.push(propGet(obj, JsValues.toStr(key)))
                    }
                    Op.STORE_ELEM -> {
                        val value = f.pop(); val key = f.pop(); val obj = f.pop()
                        propSet(obj, JsValues.toStr(key), value); f.push(value)
                    }
                    Op.DELETE_PROP -> {
                        val obj = f.pop(); f.push((obj as? JsObject)?.delete(strings[a]) ?: true)
                    }
                    Op.DELETE_ELEM -> {
                        val key = f.pop(); val obj = f.pop()
                        f.push((obj as? JsObject)?.delete(JsValues.toStr(key)) ?: true)
                    }

                    Op.MAKE_OBJECT -> {
                        val n = a
                        val o = JsObject(realm.objectProto)
                        // stack has 2n entries: k1,v1,k2,v2,...
                        val base = f.sp - 2 * n
                        for (i in 0 until n) {
                            val k = JsValues.toStr(f.stack[base + 2 * i])
                            val v = f.stack[base + 2 * i + 1]
                            o.set(k, v)
                        }
                        // pop 2n
                        for (i in 0 until 2 * n) { f.sp--; f.stack[f.sp] = null }
                        f.push(o)
                    }
                    Op.MAKE_ARRAY -> {
                        val n = a; val arr = JsArray().apply { proto = realm.arrayProto }
                        val base = f.sp - n
                        for (i in 0 until n) arr.push(f.stack[base + i])
                        for (i in 0 until n) { f.sp--; f.stack[f.sp] = null }
                        f.push(arr)
                    }
                    Op.MAKE_CLOSURE -> {
                        val childBc = bc.functions[a]
                        val upInfos = childBc.upvalueInfo
                        val ups = if (upInfos.isEmpty()) EMPTY_UPVALS else Array(upInfos.size) { i ->
                            val info = upInfos[i]
                            if (info.parentIsLocal) {
                                // Open upvalue: share a single [Upvalue] box between the parent local
                                // slot and every closure that captures it. If the slot already holds
                                // an Upvalue (from a prior MAKE_CLOSURE), reuse it.
                                val cur = f.locals[info.parentIndex]
                                val box = if (cur is Upvalue) cur else Upvalue(cur).also { f.locals[info.parentIndex] = it }
                                box
                            } else {
                                f.upvalues[info.parentIndex]
                            }
                        }
                        val closureEnv = f.closureEnv ?: realm.globalEnv
                        f.push(makeJsFunction(childBc, closureEnv, ups))
                    }
                    Op.DUP -> f.push(f.peek())
                    Op.POP -> f.pop()
                    Op.SWAP -> { val t = f.stack[f.sp - 1]; f.stack[f.sp - 1] = f.stack[f.sp - 2]; f.stack[f.sp - 2] = t }

                    Op.ADD -> {
                        val r = f.pop(); val l = f.pop()
                        f.push(
                            if (l is Double && r is Double) l + r
                            else if (l is String || r is String) JsValues.toStr(l) + JsValues.toStr(r)
                            else JsValues.toNumber(l) + JsValues.toNumber(r)
                        )
                    }
                    Op.SUB -> {
                        val r = f.pop(); val l = f.pop()
                        f.push(if (l is Double && r is Double) l - r else JsValues.toNumber(l) - JsValues.toNumber(r))
                    }
                    Op.MUL -> {
                        val r = f.pop(); val l = f.pop()
                        f.push(if (l is Double && r is Double) l * r else JsValues.toNumber(l) * JsValues.toNumber(r))
                    }
                    Op.DIV -> {
                        val r = f.pop(); val l = f.pop()
                        f.push(if (l is Double && r is Double) l / r else JsValues.toNumber(l) / JsValues.toNumber(r))
                    }
                    Op.MOD -> {
                        val r = f.pop(); val l = f.pop()
                        f.push(if (l is Double && r is Double) l % r else JsValues.toNumber(l) % JsValues.toNumber(r))
                    }
                    Op.POW -> {
                        val r = f.pop(); val l = f.pop()
                        f.push(Math.pow(JsValues.toNumber(l), JsValues.toNumber(r)))
                    }
                    Op.LT -> {
                        val r = f.pop(); val l = f.pop()
                        f.push(
                            if (l is Double && r is Double) l < r
                            else numericCompare(l, r) { x, y -> x < y }
                        )
                    }
                    Op.NEG -> f.push(-JsValues.toNumber(f.pop()))
                    Op.PLUS -> f.push(JsValues.toNumber(f.pop()))
                    Op.NOT -> f.push(!JsValues.toBool(f.pop()))
                    Op.BITNOT -> f.push(JsValues.toInt32(f.pop()).inv().toDouble())
                    Op.TYPEOF -> f.push(JsValues.typeOf(f.pop()))
                    Op.VOID_OP -> { f.pop(); f.push(JsValues.UNDEFINED) }
                    Op.TO_NUMBER -> f.push(JsValues.toNumber(f.pop()))
                    Op.BITAND -> { val r = f.pop(); val l = f.pop(); f.push((JsValues.toInt32(l) and JsValues.toInt32(r)).toDouble()) }
                    Op.BITOR -> { val r = f.pop(); val l = f.pop(); f.push((JsValues.toInt32(l) or JsValues.toInt32(r)).toDouble()) }
                    Op.BITXOR -> { val r = f.pop(); val l = f.pop(); f.push((JsValues.toInt32(l) xor JsValues.toInt32(r)).toDouble()) }
                    Op.SHL -> { val r = f.pop(); val l = f.pop(); f.push((JsValues.toInt32(l) shl (JsValues.toInt32(r) and 31)).toDouble()) }
                    Op.SHR -> { val r = f.pop(); val l = f.pop(); f.push((JsValues.toInt32(l) shr (JsValues.toInt32(r) and 31)).toDouble()) }
                    Op.USHR -> { val r = f.pop(); val l = f.pop(); f.push((JsValues.toUint32(l) ushr (JsValues.toInt32(r) and 31)).toDouble()) }
                    Op.EQ -> { val r = f.pop(); val l = f.pop(); f.push(JsValues.looseEq(l, r)) }
                    Op.NEQ -> { val r = f.pop(); val l = f.pop(); f.push(!JsValues.looseEq(l, r)) }
                    Op.SEQ -> { val r = f.pop(); val l = f.pop(); f.push(JsValues.strictEq(l, r)) }
                    Op.SNEQ -> { val r = f.pop(); val l = f.pop(); f.push(!JsValues.strictEq(l, r)) }
                    Op.LE -> { val r = f.pop(); val l = f.pop(); f.push(if (l is Double && r is Double) l <= r else numericCompare(l, r) { x, y -> x <= y }) }
                    Op.GT -> { val r = f.pop(); val l = f.pop(); f.push(if (l is Double && r is Double) l > r else numericCompare(l, r) { x, y -> x > y }) }
                    Op.GE -> { val r = f.pop(); val l = f.pop(); f.push(if (l is Double && r is Double) l >= r else numericCompare(l, r) { x, y -> x >= y }) }
                    Op.INSTANCEOF -> {
                        val ctor = f.pop(); val obj = f.pop()
                        f.push(obj is JsObject && ctor is JsFunction && protoChainContains(obj, ctor.get("prototype")))
                    }
                    Op.IN_OP -> {
                        val o = f.pop(); val k = f.pop()
                        f.push((o as? JsObject)?.has(JsValues.toStr(k)) ?: false)
                    }

                    Op.JMP -> f.pc = a
                    Op.JT -> { val v = f.pop(); if (JsValues.toBool(v)) f.pc = a }
                    Op.JF -> { val v = f.pop(); if (!JsValues.toBool(v)) f.pc = a }
                    Op.JT_KEEP -> { if (JsValues.toBool(f.peek())) f.pc = a else f.pop() }
                    Op.JF_KEEP -> { if (!JsValues.toBool(f.peek())) f.pc = a else f.pop() }

                    Op.CALL -> {
                        val argc = a
                        val argsArr = Array<Any?>(argc) { JsValues.UNDEFINED }
                        for (i in argc - 1 downTo 0) argsArr[i] = f.pop()
                        val callee = f.pop()
                        val fn = callee as? JsFunction ?: jsThrowTypeError(realm, "value is not a function")
                        f.push(invokeFast(fn, realm.globalObject, argsArr))
                    }
                    Op.CALL_METHOD -> {
                        val argc = a
                        val argsArr = Array<Any?>(argc) { JsValues.UNDEFINED }
                        for (i in argc - 1 downTo 0) argsArr[i] = f.pop()
                        val fn = f.pop() as? JsFunction ?: jsThrowTypeError(realm, "value is not a function")
                        val obj = f.pop()
                        val thisRef = if (fn.getOwn("__arrow__") == true) f.thisVal else obj
                        f.push(invokeFast(fn, thisRef, argsArr))
                    }
                    Op.NEW_OP -> {
                        val argc = a
                        val argsArr = Array<Any?>(argc) { JsValues.UNDEFINED }
                        for (i in argc - 1 downTo 0) argsArr[i] = f.pop()
                        val ctor = f.pop() as? JsFunction ?: jsThrowTypeError(realm, "not a constructor")
                        val proto = ctor.get("prototype") as? JsObject ?: realm.objectProto
                        val instance = JsObject(proto)
                        val res = ctor.call(instance, argsArr.toList())
                        f.push(if (res is JsObject) res else instance)
                    }
                    Op.RET -> return f.pop()
                    Op.RET_UNDEF -> return JsValues.UNDEFINED

                    Op.GET_THIS -> f.push(f.thisVal ?: realm.globalObject)
                    Op.LOAD_ARGUMENTS -> {
                        val arr = JsArray().apply { proto = realm.arrayProto }
                        for (v in f.args) arr.push(v)
                        f.push(arr)
                    }

                    Op.THROW -> { val v = f.pop(); throw JsThrown(v) }
                    Op.TRY_ENTER -> { f.handlers[f.handlerTop++] = a; f.handlers[f.handlerTop++] = b; f.handlers[f.handlerTop++] = f.sp }
                    Op.TRY_EXIT -> { f.handlerTop -= 3 }
                    Op.END_FINALLY -> {
                        if (f.pendingThrow != null) {
                            val t = f.pendingThrow!!; f.pendingThrow = null; throw JsThrown(t)
                        }
                    }

                    Op.FOR_IN_INIT -> {
                        val obj = f.pop()
                        val keys = when (obj) {
                            is JsObject -> obj.keys()
                            else -> emptyList()
                        }
                        f.push(IterState(keys, 0))
                    }
                    Op.FOR_IN_NEXT -> {
                        val it = f.peek() as IterState
                        if (it.idx >= it.items.size) f.pc = a
                        else { f.push(it.items[it.idx]); it.idx++ }
                    }
                    Op.FOR_OF_INIT -> {
                        val obj = f.pop()
                        val items = when (obj) {
                            is String -> obj.map { it.toString() as Any? }
                            is JsArray -> (0 until obj.length).map { obj.get(it.toString()) }
                            is JsObject -> obj.keys().map { obj.get(it) as Any? }
                            else -> emptyList()
                        }
                        f.push(IterState(items, 0))
                    }
                    Op.FOR_OF_NEXT -> {
                        val it = f.peek() as IterState
                        if (it.idx >= it.items.size) f.pc = a
                        else { f.push(it.items[it.idx]); it.idx++ }
                    }

                    Op.PUSH_BLOCK, Op.POP_BLOCK -> {}

                    Op.STASH_RESULT -> { f.lastResult = f.pop() }
                    Op.HALT -> return f.lastResult

                    // Logical runtime ops not emitted by compiler (using JT_KEEP/JF_KEEP).
                    Op.AND_LOG, Op.OR_LOG -> error("unreachable")
                }
            } catch (thr: JsThrown) {
                if (f.handlerTop == 0) throw thr
                val sp0 = f.handlers[f.handlerTop - 1]
                val finallyPc = f.handlers[f.handlerTop - 2]
                val catchPc = f.handlers[f.handlerTop - 3]
                f.handlerTop -= 3
                // unwind stack to handler-entry sp
                while (f.sp > sp0) { f.sp--; f.stack[f.sp] = null }
                if (catchPc >= 0) {
                    f.push(thr.value)
                    f.pc = catchPc
                } else if (finallyPc >= 0) {
                    f.pendingThrow = thr.value
                    f.pc = finallyPc
                } else throw thr
            }
        }
        @Suppress("UNREACHABLE_CODE") return JsValues.UNDEFINED
    }

    private class IterState(val items: List<Any?>, var idx: Int)

    /** Short textual rendering of the current operand stack; used by the tracer. */
    private fun snapshot(f: Frame): String {
        if (f.sp == 0) return ""
        val sb = StringBuilder()
        for (i in 0 until f.sp) {
            if (i > 0) sb.append(", ")
            sb.append(briefValue(f.stack[i]))
        }
        return sb.toString()
    }
    private fun briefValue(v: Any?): String = when (v) {
        null -> "null"
        io.kjs.runtime.Undefined -> "undefined"
        is Boolean, is Double, is Int, is Long -> v.toString()
        is String -> "\"" + (if (v.length > 20) v.take(20) + "…" else v) + "\""
        is io.kjs.runtime.JsFunction -> "[fn ${v.get("name")}]"
        is io.kjs.runtime.JsObject -> "[${v.className}]"
        else -> v.toString()
    }

    private fun propGet(obj: Any?, key: String): Any? = when (obj) {
        null -> jsThrowTypeError(realm, "Cannot read property '$key' of null")
        Undefined -> jsThrowTypeError(realm, "Cannot read property '$key' of undefined")
        is String -> stringProp(obj, key)
        is JsObject -> obj.get(key)
        is Double, is Int, is Long -> realm.numberProto.get(key)
        is Boolean -> realm.booleanProto.get(key)
        else -> JsValues.UNDEFINED
    }
    private fun propSet(obj: Any?, key: String, value: Any?) {
        if (obj is JsObject) {
            obj.set(key, value)
            if (obj is JsArray) {
                val idx = key.toIntOrNull()
                if (idx != null && idx >= obj.length) obj.length = idx + 1
            }
        }
    }
    private fun stringProp(s: String, key: String): Any? {
        val idx = key.toIntOrNull()
        if (idx != null && idx in s.indices) return s[idx].toString()
        if (key == "length") return s.length.toDouble()
        return realm.stringProto.get(key)
    }

    private fun protoChainContains(o: JsObject, proto: Any?): Boolean {
        if (proto !is JsObject) return false
        var p = o.proto
        while (p != null) { if (p === proto) return true; p = p.proto }
        return false
    }

    private inline fun numericCompare(a: Any?, b: Any?, cmp: (Double, Double) -> Boolean): Boolean {
        if (a is String && b is String) return cmp(a.compareTo(b).toDouble(), 0.0)
        val na = JsValues.toNumber(a); val nb = JsValues.toNumber(b)
        if (na.isNaN() || nb.isNaN()) return false
        return cmp(na, nb)
    }
}

/** Throw a typed JS error. Never returns. */
internal fun jsThrowTypeError(realm: Realm, msg: String): Nothing {
    jsThrowName(realm, "TypeError", msg)
}
internal fun jsThrowName(realm: Realm, name: String, msg: String): Nothing {
    val ctor = realm.globalEnv.get(name) as? JsFunction
    val err = if (ctor != null) ctor.call(JsObject(realm.errorProto), listOf(msg)) as JsObject else JsObject(realm.errorProto).apply { set("name", name); set("message", msg) }
    throw JsThrown(err)
}
