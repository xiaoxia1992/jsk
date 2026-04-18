package io.kjs

import io.kjs.ir.Compiler
import io.kjs.lex.Lexer
import io.kjs.parse.Parser
import io.kjs.runtime.Interpreter
import io.kjs.runtime.JsThrown
import io.kjs.runtime.JsValues
import io.kjs.runtime.Realm
import io.kjs.vm.Vm

/**
 * Public facade of the KJS engine.
 *
 * ```
 * val eng = Engine()
 * val r = eng.eval("1 + 2 * 3") // => 7.0
 * ```
 *
 * Two execution backends exist behind the same API:
 *  - [Backend.Vm] (default) — compiles to bytecode and runs on the stack VM.
 *  - [Backend.Walker] — direct AST interpreter; retained as oracle / fallback.
 *
 * Pass `trace = true` to print a narrated walk-through of the lexer, parser,
 * compiler and VM steps — useful for learning how the engine works.
 */
class Engine(
    backend: Backend = defaultBackend(),
    trace: Boolean = false,
) {
    enum class Backend { Vm, Walker }

    val realm: Realm = Realm()
    private val walker: Interpreter = Interpreter(realm)
    private val vm: Vm = Vm(realm)
    private var mode: Backend = backend

    val tracer: Tracer? = if (trace) Tracer() else null
    init { vm.tracer = tracer }

    val backend: Backend get() = mode
    fun setBackend(b: Backend) { mode = b }

    fun eval(source: String): Any? {
        // Lex
        val tokens = Lexer(source).tokenize()
        tracer?.onTokens(tokens)
        // Parse (Parser re-lexes internally for now; fine for demo purposes)
        val program = Parser(source).parseProgram()
        tracer?.onAst(program)
        return when (mode) {
            Backend.Walker -> walker.exec(program).also { tracer?.onResult(it) }
            Backend.Vm -> {
                val bc = Compiler.compileProgram(program, source)
                tracer?.onBytecode(bc)
                tracer?.onVmEnter(bc)
                vm.run(bc).also { tracer?.onResult(it) }
            }
        }
    }

    /** Evaluate returning a stringified form (for REPL / assertions). */
    fun evalToString(source: String): String = try {
        JsValues.toStr(eval(source))
    } catch (e: JsThrown) {
        "Uncaught " + JsValues.toStr(e.value)
    }

    companion object {
        private fun defaultBackend(): Backend =
            when (System.getenv("KJS_BACKEND")?.lowercase()) {
                "walker", "ast" -> Backend.Walker
                else -> Backend.Vm
            }
    }
}
