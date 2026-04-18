package io.kjs

import io.kjs.runtime.JsThrown
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Drives the in-tree Test262-style fixtures under `tests/test262-runner/fixtures/`
 * to prove the runner & engine can ingest the real format (frontmatter, negative
 * tests, etc.) without needing the full upstream tc39/test262 checkout.
 *
 * The real Test262 corpus is wired through `:tests:test262-runner:run` — see
 * the module's README for how to point it at a cloned tc39/test262.
 */
class Test262FixtureTest {
    private val fixturesDir = File("../test262-runner/fixtures")
    private val fmRegex = Regex("/\\*---(.*?)---\\*/", RegexOption.DOT_MATCHES_ALL)

    @TestFactory
    fun fixtures(): List<DynamicTest> {
        assertTrue(fixturesDir.isDirectory, "fixtures dir exists at ${fixturesDir.absolutePath}")
        return fixturesDir.listFiles { f -> f.extension == "js" }!!.sorted().map { f ->
            DynamicTest.dynamicTest(f.nameWithoutExtension) {
                val src = f.readText()
                val fm = fmRegex.find(src)?.groupValues?.get(1).orEmpty()
                val negType = Regex("type:\\s*(\\w+)").find(fm)?.groupValues?.get(1)
                val engine = Engine()
                if (negType != null) {
                    val threw = runCatching { engine.eval(src) }.isFailure
                    assertTrue(threw, "expected to throw $negType but script completed: ${f.name}")
                } else {
                    try { engine.eval(src) }
                    catch (e: JsThrown) { throw AssertionError("${f.name} threw: ${e.message}") }
                }
            }
        }
    }
}
