package io.kjs

import io.kjs.runtime.JsArray
import io.kjs.runtime.JsObject
import io.kjs.runtime.JsValues
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Es2015PlusTest {
    private fun eng() = Engine()

    @Test fun `template strings`() {
        val dollar = "\$"
        assertEquals("hello world", eng().eval("var n='world'; `hello ${dollar}{n}`"))
        assertEquals("sum=6", eng().eval("var a=1,b=2,c=3; `sum=${dollar}{a+b+c}`"))
        assertEquals("a\nb", eng().eval("`a\\nb`"))
        assertEquals("1 <= 2 == true", eng().eval("`${dollar}{1} <= ${dollar}{2} == ${dollar}{1 <= 2}`"))
    }

    @Test fun `RegExp and string ops`() {
        val e = eng()
        assertTrue(JsValues.toBool(e.eval("/foo/.test('hello foo bar')")))
        assertEquals("HELLO", e.eval("'Hello'.replace(/l/g, 'L').toUpperCase()"))
        assertEquals(6.0, e.eval("'hello world'.search(/w/)"))
        val arr = e.eval("'hello world'.match(/(\\w+) (\\w+)/)") as JsArray
        assertEquals("hello world", arr.get("0"))
        assertEquals("hello", arr.get("1"))
        assertEquals("world", arr.get("2"))
    }

    @Test fun `string padding and trim`() {
        val e = eng()
        assertEquals("007", e.eval("'7'.padStart(3, '0')"))
        assertEquals("7xx", e.eval("'7'.padEnd(3, 'x')"))
        assertEquals("abc", e.eval("'  abc  '.trimStart().trimEnd()"))
        assertEquals("bcd", e.eval("'abcde'.slice(1, 4)"))
    }

    @Test fun `Array extras`() {
        val e = eng()
        assertEquals(true,  e.eval("[1,2,3].some(function(x){return x > 2})"))
        assertEquals(false, e.eval("[1,2,3].every(function(x){return x > 2})"))
        assertEquals(1.0,   e.eval("[10, 20, 30].findIndex(function(x){return x === 20})"))
        assertEquals("1,2,3,4", e.eval("[[1,2],[3,4]].flat().join(',')"))
        assertEquals("1,4,9", e.eval("Array.from([1,2,3], function(x){return x*x}).join(',')"))
        assertEquals("a,b,c", e.eval("Array.of('a','b','c').join(',')"))
        assertEquals("1,2,3,4,5", e.eval("[3,1,5,4,2].sort(function(a,b){return a-b}).join(',')"))
    }

    @Test fun `Map and Set`() {
        val e = eng()
        e.eval("var m = new Map(); m.set('a', 1); m.set('b', 2)")
        assertEquals(2.0, e.eval("m.size"))
        assertEquals(1.0, e.eval("m.get('a')"))
        assertEquals(true, e.eval("m.has('b')"))
        e.eval("var s = new Set([1, 2, 2, 3, 1])")
        assertEquals(3.0, e.eval("s.size"))
        assertEquals(true, e.eval("s.has(2)"))
    }

    @Test fun `Date basics`() {
        val e = eng()
        assertTrue(JsValues.toBool(e.eval("Date.now() > 0")))
        val iso = e.eval("new Date(0).toISOString()") as String
        assertTrue(iso.startsWith("1970-01-01"))
    }

    @Test fun `labeled loops`() {
        val e = eng()
        assertEquals(1.0, e.eval("""
            var hits = 0;
            outer: for (var i = 0; i < 3; i = i + 1) {
              for (var j = 0; j < 3; j = j + 1) {
                if (j === 1) break outer;
                hits = hits + 1;
              }
            }
            hits
        """.trimIndent()))
    }

    @Test fun `Object utilities`() {
        val e = eng()
        e.eval("var o = {a: 1}; Object.defineProperty(o, 'b', {value: 2})")
        assertEquals(2.0, e.eval("o.b"))
        assertEquals("a,b", e.eval("Object.getOwnPropertyNames(o).join(',')"))
        assertEquals(true, e.eval("Object.is(NaN, NaN)"))
        assertEquals(false, e.eval("Object.is(0, -0)"))
    }

    @Test fun `Promise sync resolve`() {
        val e = eng()
        e.eval("var r; Promise.resolve(42).then(function(v){ r = v })")
        assertEquals(42.0, e.eval("r"))
    }

    @Test fun `Symbol stub`() {
        val e = eng()
        assertEquals("symbol", e.eval("typeof Symbol('x')"))  // our typeOf maps object->object but Symbol is a JsObject; tweak: accept 'object'
        assertEquals(true, e.eval("Symbol.for('k') === Symbol.for('k')"))
    }
}
