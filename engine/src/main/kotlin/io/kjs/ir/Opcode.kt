package io.kjs.ir

/**
 * Bytecode instruction set for the KJS stack VM.
 *
 * Design goals:
 *  - Small (< 80 ops), fixed-width dispatch via [code] (Int, lower 8 bits = op).
 *  - Operands are packed into the remaining 24 bits or follow in a separate Int[] lane.
 *    In this implementation we use a simple parallel-array layout:
 *      - [Bytecode.code]    : IntArray of opcode ordinals
 *      - [Bytecode.a], [Bytecode.b] : IntArray of two operand slots per instruction
 *      - [Bytecode.constants], [Bytecode.strings], [Bytecode.functions] : pools
 *
 *   This is slightly fatter than QuickJS' varlen encoding but keeps the interpreter
 *   trivial; swap to a byte stream + LEB128 only if profiles demand it (M5).
 */
enum class Op {
    // --- constants & locals ---
    NOP,
    LOAD_UNDEF,
    LOAD_NULL,
    LOAD_TRUE,
    LOAD_FALSE,
    LOAD_ZERO,
    LOAD_ONE,
    LOAD_INT,        // a = int32 literal
    LOAD_CONST,      // a = constants[] index (Double/String/...)
    LOAD_STR,        // a = strings[] index

    LOAD_LOCAL,      // a = slot
    STORE_LOCAL,     // a = slot (pops)
    LOAD_ARG,        // a = arg index
    STORE_ARG,       // a = arg index (pops)

    LOAD_GLOBAL,     // a = strings[] index (name)
    STORE_GLOBAL,    // a = strings[] index (pops value)
    DECL_GLOBAL,     // a = strings[] index, declare (let/const -> env), pops initial value

    LOAD_UPVAL,      // a = upvalue index  (closure capture)
    STORE_UPVAL,     // a = upvalue index

    // --- object / member ---
    LOAD_PROP,       // a = strings[] index (name), consumes obj
    STORE_PROP,      // a = strings[] index, stack: [obj, value] -> value (leaves value)
    LOAD_ELEM,       // stack: [obj, key] -> value
    STORE_ELEM,      // stack: [obj, key, value] -> value
    DELETE_PROP,     // a = strings[] index, consumes obj, pushes bool
    DELETE_ELEM,     // stack: [obj, key] -> bool

    MAKE_OBJECT,     // a = number of key/value pairs on stack (keys as strings already)
    MAKE_ARRAY,      // a = number of elements on stack
    MAKE_CLOSURE,    // a = functions[] index, capture current env
    DUP, POP, SWAP,

    // --- arithmetic / logic ---
    ADD, SUB, MUL, DIV, MOD, POW,
    NEG, PLUS, NOT, BITNOT, TYPEOF, VOID_OP,
    AND_LOG, OR_LOG,    // not used at runtime; parser emits JMP_IF_* instead
    BITAND, BITOR, BITXOR, SHL, SHR, USHR,
    EQ, NEQ, SEQ, SNEQ, LT, LE, GT, GE,
    INSTANCEOF, IN_OP,

    TO_NUMBER,       // numeric coercion for ++/--

    // --- control flow ---
    JMP,             // a = offset (absolute pc)
    JT,              // jump if top truthy (pops)
    JF,              // jump if top falsy  (pops)
    JT_KEEP,         // logical ||: if truthy, keep on stack & jump; else pop & continue
    JF_KEEP,         // logical &&: if falsy, keep on stack & jump; else pop & continue

    // --- calls / functions ---
    CALL,            // a = argc; stack: [fn, arg1..argN] -> result
    CALL_METHOD,     // a = argc; stack: [obj, fn, arg1..argN] -> result (preserves `this`)
    NEW_OP,          // a = argc; stack: [fn, arg1..argN] -> instance
    RET,             // returns top
    RET_UNDEF,       // returns undefined

    GET_THIS,
    LOAD_ARGUMENTS,  // push `arguments` object (built on demand)

    // --- exceptions ---
    THROW,
    TRY_ENTER,       // a = catch pc, b = finally pc (-1 if none)
    TRY_EXIT,        // pop handler
    END_FINALLY,     // re-raise pending exception if any, else continue

    // --- iteration helpers ---
    FOR_IN_INIT,     // consume obj, push iterator handle (int boxed as Double)
    FOR_IN_NEXT,     // a = jump-on-done pc; push key
    FOR_OF_INIT,
    FOR_OF_NEXT,     // a = jump-on-done pc; push value

    // --- scope helpers (for let/const blocks) ---
    PUSH_BLOCK,
    POP_BLOCK,

    // --- terminator for program-level eval (leaves last value on a "result slot") ---
    STASH_RESULT,    // pops top into frame.lastResult
    HALT,
}

/** Reverse map for hot dispatch; built once. */
val OP_VALUES: Array<Op> = Op.entries.toTypedArray()
