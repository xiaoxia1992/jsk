package io.kjs

import io.kjs.runtime.JsArray
import io.kjs.runtime.JsObject
import io.kjs.runtime.JsValues
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Runs a corpus of programs on both the tree-walking oracle and the bytecode VM,
 * comparing their stringified results. Any divergence surfaces here immediately.
 */
class VmVsWalkerTest {
    private val corpus: List<Pair<String, String>> = listOf(
        "basic arith"     to "1 + 2 * 3 - 4 / 2",
        "string concat"   to "'a' + 1 + true",
        "var block let"   to "let x = 1; { let x = 99; } x + 10",
        "if else"         to "var y; if (2 > 1) y = 'a'; else y = 'b'; y",
        "while loop"      to "var s = 0; var i = 0; while (i < 10) { s = s + i; i = i + 1 } s",
        "for loop"        to "var s = 0; for (var i = 1; i <= 5; i = i + 1) s = s + i; s",
        "for of"          to "var s = ''; for (var c of 'abcd') s = s + c + c; s",
        "for in"          to "var s = ''; var o = {a:1,b:2,c:3}; for (var k in o) s = s + k; s",
        "function decl"   to "function add(a,b){ return a+b } add(40, 2)",
        "closure"         to "function mk() { let n = 0; return function(){ n = n + 1; return n } } var c = mk(); c(); c(); c() + c()",
        "recursion"       to "function fib(n){ return n < 2 ? n : fib(n-1)+fib(n-2) } fib(12)",
        "object literal"  to "var p = { x: 3, y: 4, sum: function(){ return this.x + this.y } }; p.sum()",
        "array methods"   to "[1,2,3,4].map(function(x){return x*x}).reduce(function(a,b){return a+b}, 0)",
        "arrow"           to "var f = x => y => x + y; f(10)(32)",
        "try/catch"       to "try { throw 'x' } catch (e) { e + '!' }",
        "typeof undef"    to "typeof notDefined",
        "logical"         to "(null || 0 || 'fallback') + ' ' + (true && 'y')",
        "short circuit"   to "var s=''; (false && (s='A')); (true || (s='B')); s === '' ? 'ok' : 'bad'",
        "ternary"         to "var x = 5; (x > 0) ? 'pos' : 'neg'",
        "update"          to "var n = 10; n++; ++n; n += 5; n",
        "bitwise"         to "(5 & 3) + (5 | 3) + (5 ^ 3) + (~5) + (1 << 3) + (16 >> 2)",
        "new"             to "function Point(x,y){ this.x=x; this.y=y } Point.prototype.len = function(){ return Math.sqrt(this.x*this.x + this.y*this.y) }; (new Point(3,4)).len()",
        "json"            to "JSON.stringify({a:1, b:[2,3], c:'x'})",
    )

    @TestFactory
    fun oracleParity(): List<DynamicTest> = corpus.map { (name, src) ->
        DynamicTest.dynamicTest(name) {
            val walker = Engine(Engine.Backend.Walker).eval(src)
            val vm     = Engine(Engine.Backend.Vm).eval(src)
            assertEquals(normalize(walker), normalize(vm), "VM result diverges from walker for: $src")
        }
    }

    /** Collapses engine-specific object identities to structural strings for comparison. */
    private fun normalize(v: Any?): String = when (v) {
        is JsArray -> "[" + (0 until v.length).joinToString(",") { normalize(v.get(it.toString())) } + "]"
        is JsObject -> "{" + v.keys().joinToString(",") { "$it:" + normalize(v.get(it)) } + "}"
        else -> JsValues.toStr(v)
    }

    @Test fun `string method on literal`() {
        val a = Engine(Engine.Backend.Walker).eval("'hello world'.slice(0, 5).toUpperCase()")
        val b = Engine(Engine.Backend.Vm).eval("'hello world'.slice(0, 5).toUpperCase()")
        assertEquals(a, b)
        assertEquals("HELLO", a)
    }
}
