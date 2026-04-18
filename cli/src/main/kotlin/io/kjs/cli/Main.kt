package io.kjs.cli

import io.kjs.Engine
import io.kjs.runtime.JsThrown
import io.kjs.runtime.JsValues
import java.io.File

/**
 * Tiny REPL / file runner.
 *   kjs                 → interactive REPL
 *   kjs script.js       → run a file
 *   kjs -e 'code'       → run inline code
 *   kjs --trace -e '…'  → narrate lexer / parser / compiler / VM steps
 */
fun main(args: Array<String>) {
    var trace = false
    val rest = mutableListOf<String>()
    for (a in args) if (a == "--trace") trace = true else rest += a

    val engine = Engine(trace = trace)
    when {
        rest.isEmpty() -> repl(engine)
        rest[0] == "-e" && rest.size >= 2 -> runCode(engine, rest[1])
        else -> runCode(engine, File(rest[0]).readText())
    }
}

private fun runCode(engine: Engine, code: String) {
    try {
        engine.eval(code)
    } catch (e: JsThrown) {
        System.err.println("Uncaught " + JsValues.toStr(e.value))
    } catch (e: Throwable) {
        System.err.println(e.message)
    }
}

private fun repl(engine: Engine) {
    println("KJS v0.1.0 — type .exit to quit")
    while (true) {
        print("> "); System.out.flush()
        val line = readlnOrNull() ?: break
        if (line.trim() == ".exit") break
        if (line.isBlank()) continue
        try {
            val v = engine.eval(line)
            println(JsValues.toStr(v))
        } catch (e: JsThrown) {
            println("Uncaught " + JsValues.toStr(e.value))
        } catch (e: Throwable) {
            println(e.message ?: e.toString())
        }
    }
}
