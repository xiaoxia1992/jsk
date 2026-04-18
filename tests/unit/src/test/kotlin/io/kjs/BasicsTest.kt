package io.kjs

import io.kjs.runtime.JsArray
import io.kjs.runtime.JsValues
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BasicsTest {
    private fun eng() = Engine()

    @Test fun `arithmetic`() {
        val e = eng()
        assertEquals(7.0, e.eval("1 + 2 * 3"))
        assertEquals(1.0, e.eval("10 % 3"))
        assertEquals(8.0, e.eval("2 ** 3"))
        assertEquals(-5.0, e.eval("-(2 + 3)"))
    }

    @Test fun `string concat and coercion`() {
        val e = eng()
        assertEquals("a1", e.eval("'a' + 1"))
        assertEquals("12", e.eval("'1' + 2"))
        assertEquals(3.0, e.eval("+'1' + +'2'"))
    }

    @Test fun `variables and scoping`() {
        val e = eng()
        assertEquals(3.0, e.eval("var x = 1; var y = 2; x + y"))
        assertEquals(10.0, e.eval("let x = 1; { let x = 10; x }"))
    }

    @Test fun `if while for`() {
        val e = eng()
        assertEquals(6.0, e.eval("let s = 0; for (let i = 1; i <= 3; i = i + 1) s = s + i; s"))
        assertEquals(10.0, e.eval("let s = 0; let i = 1; while (i <= 4) { s = s + i; i = i + 1 } s"))
        assertEquals("yes", e.eval("if (2 > 1) 'yes'; else 'no'"))
    }

    @Test fun `functions and closures`() {
        val e = eng()
        e.eval("""
            function makeCounter() {
              let n = 0;
              return function() { n = n + 1; return n; };
            }
            var c = makeCounter();
        """.trimIndent())
        assertEquals(1.0, e.eval("c()"))
        assertEquals(2.0, e.eval("c()"))
        assertEquals(3.0, e.eval("c()"))
    }

    @Test fun `recursion - fibonacci`() {
        val e = eng()
        e.eval("function fib(n) { return n < 2 ? n : fib(n-1) + fib(n-2) }")
        assertEquals(55.0, e.eval("fib(10)"))
        assertEquals(6765.0, e.eval("fib(20)"))
    }

    @Test fun `arrays`() {
        val e = eng()
        val arr = e.eval("[1, 2, 3].map(function(x){ return x * 2 })") as JsArray
        assertEquals(3, arr.length)
        assertEquals(2.0, arr.get("0"))
        assertEquals(6.0, arr.get("2"))
        assertEquals(6.0, e.eval("[1,2,3].reduce(function(a,b){return a+b}, 0)"))
        assertEquals("1,2,3", e.eval("[1,2,3].join(',')"))
    }

    @Test fun `objects and prototypes`() {
        val e = eng()
        e.eval("""
            function Point(x, y) { this.x = x; this.y = y }
            Point.prototype.sum = function() { return this.x + this.y }
            var p = new Point(3, 4);
        """.trimIndent())
        assertEquals(7.0, e.eval("p.sum()"))
        assertTrue(JsValues.toBool(e.eval("p instanceof Point")))
    }

    @Test fun `try catch throw`() {
        val e = eng()
        assertEquals("caught:boom", e.eval("""
            var r;
            try { throw 'boom' } catch (ex) { r = 'caught:' + ex }
            r
        """.trimIndent()))
    }

    @Test fun `JSON round trip`() {
        val e = eng()
        assertEquals("{\"a\":1,\"b\":[1,2,3]}", e.eval("JSON.stringify({a:1, b:[1,2,3]})"))
        assertEquals(2.0, e.eval("JSON.parse('{\"x\":2}').x"))
    }

    @Test fun `Math`() {
        val e = eng()
        assertEquals(3.0, e.eval("Math.max(1, 3, 2)"))
        assertEquals(2.0, e.eval("Math.sqrt(4)"))
        assertEquals(3.0, e.eval("Math.floor(3.9)"))
    }

    @Test fun `arrow functions`() {
        val e = eng()
        assertEquals(9.0, e.eval("var sq = x => x * x; sq(3)"))
        assertEquals(10.0, e.eval("[1,2,3,4].reduce((a,b) => a+b, 0)"))
    }

    @Test fun `string methods`() {
        val e = eng()
        assertEquals("HELLO", e.eval("'hello'.toUpperCase()"))
        assertEquals(5.0, e.eval("'hello'.length"))
        assertEquals("ell", e.eval("'hello'.slice(1, 4)"))
    }

    @Test fun `typeof`() {
        val e = eng()
        assertEquals("number", e.eval("typeof 1"))
        assertEquals("string", e.eval("typeof 'a'"))
        assertEquals("undefined", e.eval("typeof notDefined"))
        assertEquals("function", e.eval("typeof function(){}"))
        assertEquals("object", e.eval("typeof null"))
        assertEquals("object", e.eval("typeof {}"))
    }
}
