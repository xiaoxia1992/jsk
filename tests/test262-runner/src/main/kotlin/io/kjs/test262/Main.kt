package io.kjs.test262

import io.kjs.Engine
import io.kjs.runtime.JsThrown
import java.io.File

/**
 * Minimal Test262 driver.
 *
 * Usage:
 *   ./gradlew :tests:test262-runner:run --args="/path/to/test262 test/language/expressions/addition"
 *
 * It parses each `.js` file's YAML frontmatter (`/*---` ... `---*/`), loads any
 * declared `includes:` from `harness/`, runs the test, compares against the
 * declared `negative:` phase/type if any, and prints a summary.
 *
 * The goal for M1 is a working runner; by M3 we'll have a curated whitelist that
 * target >= 80% pass in selected directories.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: test262-runner <test262-root> [sub-path...]")
        return
    }
    val root = File(args[0])
    val harness = File(root, "harness")
    if (!harness.isDirectory) { System.err.println("Invalid test262 root: missing harness/"); return }

    val subs = if (args.size > 1) args.drop(1) else listOf("test/language/expressions")
    val targets = subs.map { File(root, it) }

    val files = targets.flatMap { dir ->
        if (dir.isFile) listOf(dir)
        else dir.walkTopDown().filter { it.isFile && it.extension == "js" && !it.name.endsWith("_FIXTURE.js") }.toList()
    }

    var pass = 0; var fail = 0; var skip = 0
    val failures = mutableListOf<Pair<File, String>>()
    val start = System.currentTimeMillis()

    for (f in files) {
        val src = f.readText()
        val meta = parseFrontmatter(src)
        if (meta.skip) { skip++; continue }
        // M1 unsupported features list
        val unsupported = setOf("async-iteration", "class", "BigInt", "Proxy", "Reflect", "Symbol", "generators", "async-functions", "dynamic-import", "import-assertions", "regexp-named-groups")
        if (meta.features.any { it in unsupported }) { skip++; continue }

        val engine = Engine()
        // load harness includes (assert.js + sta.js are always loaded by convention when includes listed or omitted)
        try {
            val baseIncludes = listOf("assert.js", "sta.js")
            for (inc in (baseIncludes + meta.includes).distinct()) {
                val h = File(harness, inc); if (h.exists()) engine.eval(h.readText())
            }
        } catch (_: Throwable) {
            // harness may use features we don't support (e.g. Symbol) — mark as skip
            skip++; continue
        }

        val result = runOne(engine, src, meta)
        when (result) {
            TestOutcome.Pass -> pass++
            is TestOutcome.Fail -> { fail++; failures.add(f to result.reason) }
        }
    }

    val ms = System.currentTimeMillis() - start
    println("---- Test262 summary ----")
    println("pass  = $pass")
    println("fail  = $fail")
    println("skip  = $skip")
    println("total = ${pass + fail + skip}")
    println("time  = ${ms}ms")
    if (failures.isNotEmpty()) {
        println("\nFirst 20 failures:")
        failures.take(20).forEach { (f, why) -> println("  ${f.relativeTo(root)} :: $why") }
    }
}

private sealed class TestOutcome {
    object Pass : TestOutcome()
    data class Fail(val reason: String) : TestOutcome()
}

private fun runOne(engine: Engine, src: String, meta: Frontmatter): TestOutcome {
    // Handle negative tests
    if (meta.negativeType != null) {
        return try {
            engine.eval(src); TestOutcome.Fail("expected negative ${meta.negativeType} but script ran cleanly")
        } catch (e: JsThrown) {
            val name = extractErrorName(e.value)
            if (name == meta.negativeType) TestOutcome.Pass
            else TestOutcome.Fail("negative mismatch: got $name expected ${meta.negativeType}")
        } catch (e: Throwable) {
            if (meta.negativeType == "SyntaxError") TestOutcome.Pass
            else TestOutcome.Fail("internal error: ${e.message}")
        }
    }
    // Positive test
    return try {
        engine.eval(src); TestOutcome.Pass
    } catch (e: JsThrown) {
        TestOutcome.Fail("threw ${io.kjs.runtime.JsValues.toStr(e.value)}")
    } catch (e: Throwable) {
        TestOutcome.Fail("internal ${e.javaClass.simpleName}: ${e.message}")
    }
}

private fun extractErrorName(v: Any?): String? {
    val o = v as? io.kjs.runtime.JsObject ?: return null
    val n = o.get("name")
    return if (n is String) n else null
}

private data class Frontmatter(
    val includes: List<String>,
    val features: List<String>,
    val negativeType: String?,
    val skip: Boolean,
)

private val fmRegex = Regex("/\\*---(.*?)---\\*/", RegexOption.DOT_MATCHES_ALL)

private fun parseFrontmatter(src: String): Frontmatter {
    val m = fmRegex.find(src) ?: return Frontmatter(emptyList(), emptyList(), null, false)
    val body = m.groupValues[1]
    val includes = extractList(body, "includes")
    val features = extractList(body, "features")
    val flagsLine = extractLine(body, "flags") ?: ""
    val flags = flagsLine.trim().removePrefix("[").removeSuffix("]").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val skip = flags.contains("module") || flags.contains("async") || flags.contains("raw")  // not supported yet
    val negativeType = Regex("negative:[\\s\\S]*?type:\\s*(\\w+)").find(body)?.groupValues?.getOrNull(1)
    return Frontmatter(includes, features, negativeType, skip)
}

private fun extractLine(body: String, key: String): String? {
    val r = Regex("(?m)^\\s*$key:\\s*(.*)$").find(body) ?: return null
    return r.groupValues[1]
}

private fun extractList(body: String, key: String): List<String> {
    val line = extractLine(body, key) ?: return emptyList()
    val inline = line.trim()
    if (inline.startsWith("[") && inline.endsWith("]")) {
        return inline.removePrefix("[").removeSuffix("]").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    // block form:   key:\n  - a\n  - b
    val idx = body.indexOf("$key:")
    if (idx < 0) return emptyList()
    val tail = body.substring(idx + key.length + 1)
    val items = mutableListOf<String>()
    for (raw in tail.lineSequence()) {
        val t = raw.trim()
        if (t.startsWith("-")) items.add(t.removePrefix("-").trim())
        else if (t.isBlank()) continue
        else break
    }
    return items
}
