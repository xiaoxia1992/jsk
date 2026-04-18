package io.kjs.runtime

/**
 * Wires up ECMAScript built-ins into a [Realm]. Kept flat and compact on purpose;
 * every entry is a native [JsFunction] or a plain property on a prototype/global.
 */
object Intrinsics {
    fun install(realm: Realm) {
        installObject(realm)
        installFunction(realm)
        installArray(realm)
        installString(realm)
        installNumber(realm)
        installMath(realm)
        installJson(realm)
        installErrors(realm)
        installConsole(realm)
        installGlobals(realm)
    }

    // ---------- helpers ----------
    private fun arg(args: List<Any?>, i: Int): Any? = if (i < args.size) args[i] else JsValues.UNDEFINED
    private fun defineFn(target: JsObject, name: String, arity: Int, fn: (Any?, List<Any?>) -> Any?) {
        target.set(name, JsFunction.native(name, arity, fn))
    }

    // ---------- Object ----------
    private fun installObject(r: Realm) {
        val ctor = JsFunction.native("Object", 1) { _, args ->
            val v = arg(args, 0)
            if (v == null || v == JsValues.UNDEFINED) JsObject(r.objectProto) else v
        }
        ctor.set("prototype", r.objectProto)
        r.objectProto.set("constructor", ctor)
        defineFn(ctor, "keys", 1) { _, args ->
            val o = arg(args, 0) as? JsObject ?: return@defineFn JsArray()
            val arr = JsArray(); for (k in o.keys()) arr.push(k); arr
        }
        defineFn(ctor, "values", 1) { _, args ->
            val o = arg(args, 0) as? JsObject ?: return@defineFn JsArray()
            val arr = JsArray(); for (k in o.keys()) arr.push(o.get(k)); arr
        }
        defineFn(ctor, "entries", 1) { _, args ->
            val o = arg(args, 0) as? JsObject ?: return@defineFn JsArray()
            val arr = JsArray()
            for (k in o.keys()) {
                val pair = JsArray(); pair.push(k); pair.push(o.get(k)); arr.push(pair)
            }
            arr
        }
        defineFn(ctor, "assign", 2) { _, args ->
            val target = arg(args, 0) as? JsObject ?: return@defineFn JsValues.UNDEFINED
            for (i in 1 until args.size) {
                val src = args[i] as? JsObject ?: continue
                for (k in src.keys()) target.set(k, src.get(k))
            }
            target
        }
        defineFn(ctor, "getPrototypeOf", 1) { _, args -> (arg(args, 0) as? JsObject)?.proto ?: JsValues.NULL }
        defineFn(r.objectProto, "hasOwnProperty", 1) { self, args ->
            (self as? JsObject)?.hasOwn(JsValues.toStr(arg(args, 0))) ?: false
        }
        defineFn(r.objectProto, "toString", 0) { self, _ -> "[object ${(self as? JsObject)?.className ?: "Object"}]" }
        defineFn(r.objectProto, "valueOf", 0) { self, _ -> self }

        r.globalObject.set("Object", ctor)
        r.globalEnv.declare("Object", ctor)
    }

    // ---------- Function ----------
    private fun installFunction(r: Realm) {
        val ctor = JsFunction.native("Function", 1) { _, _ -> JsValues.UNDEFINED /* no eval in M1 */ }
        ctor.set("prototype", r.functionProto)
        r.functionProto.set("constructor", ctor)
        defineFn(r.functionProto, "call", 1) { self, args ->
            val fn = self as? JsFunction ?: return@defineFn JsValues.UNDEFINED
            fn.call(arg(args, 0), args.drop(1))
        }
        defineFn(r.functionProto, "apply", 2) { self, args ->
            val fn = self as? JsFunction ?: return@defineFn JsValues.UNDEFINED
            val a = arg(args, 1)
            val list: List<Any?> = when (a) {
                is JsArray -> (0 until a.length).map { a.get(it.toString()) }
                null, JsValues.UNDEFINED -> emptyList()
                else -> emptyList()
            }
            fn.call(arg(args, 0), list)
        }
        defineFn(r.functionProto, "bind", 1) { self, args ->
            val fn = self as? JsFunction ?: return@defineFn JsValues.UNDEFINED
            val boundThis = arg(args, 0)
            val boundArgs = args.drop(1)
            JsFunction.native("bound ${fn.get("name")}", 0) { _, a -> fn.call(boundThis, boundArgs + a) }
        }
        r.globalObject.set("Function", ctor)
        r.globalEnv.declare("Function", ctor)
    }

    // ---------- Array ----------
    private fun installArray(r: Realm) {
        val ctor = JsFunction.native("Array", 1) { _, args ->
            val arr = JsArray().apply { proto = r.arrayProto }
            if (args.size == 1 && args[0] is Double) {
                arr.length = (args[0] as Double).toInt()
            } else args.forEach { arr.push(it) }
            arr
        }
        ctor.set("prototype", r.arrayProto)
        r.arrayProto.set("constructor", ctor)
        defineFn(ctor, "isArray", 1) { _, args -> args.firstOrNull() is JsArray }

        fun asArr(self: Any?) = self as? JsArray ?: error("TypeError: not an array")

        defineFn(r.arrayProto, "push", 1) { self, args -> val a = asArr(self); args.forEach { a.push(it) }; a.length.toDouble() }
        defineFn(r.arrayProto, "pop", 0) { self, _ ->
            val a = asArr(self); if (a.length == 0) return@defineFn JsValues.UNDEFINED
            val i = a.length - 1; val v = a.get(i.toString()); a.properties.remove(i.toString()); a.length = i; v
        }
        defineFn(r.arrayProto, "shift", 0) { self, _ ->
            val a = asArr(self); if (a.length == 0) return@defineFn JsValues.UNDEFINED
            val v = a.get("0")
            for (i in 1 until a.length) a.set((i - 1).toString(), a.get(i.toString()))
            a.properties.remove((a.length - 1).toString()); a.length = a.length - 1; v
        }
        defineFn(r.arrayProto, "unshift", 1) { self, args ->
            val a = asArr(self); val n = args.size
            for (i in a.length - 1 downTo 0) a.set((i + n).toString(), a.get(i.toString()))
            for (i in 0 until n) a.set(i.toString(), args[i])
            a.length = a.length + n; a.length.toDouble()
        }
        defineFn(r.arrayProto, "slice", 2) { self, args ->
            val a = asArr(self); val len = a.length
            var start = JsValues.toInt32(arg(args, 0)); if (start < 0) start = maxOf(0, len + start); start = minOf(start, len)
            var end = if (arg(args, 1) == JsValues.UNDEFINED) len else JsValues.toInt32(arg(args, 1))
            if (end < 0) end = maxOf(0, len + end); end = minOf(end, len)
            val out = JsArray().apply { proto = r.arrayProto }
            for (i in start until end) out.push(a.get(i.toString()))
            out
        }
        defineFn(r.arrayProto, "concat", 1) { self, args ->
            val a = asArr(self); val out = JsArray().apply { proto = r.arrayProto }
            for (i in 0 until a.length) out.push(a.get(i.toString()))
            for (x in args) {
                if (x is JsArray) for (i in 0 until x.length) out.push(x.get(i.toString())) else out.push(x)
            }
            out
        }
        defineFn(r.arrayProto, "join", 1) { self, args ->
            val a = asArr(self); val sep = if (arg(args, 0) == JsValues.UNDEFINED) "," else JsValues.toStr(arg(args, 0))
            (0 until a.length).joinToString(sep) { JsValues.toStr(a.get(it.toString())) }
        }
        defineFn(r.arrayProto, "indexOf", 1) { self, args ->
            val a = asArr(self); val target = arg(args, 0)
            for (i in 0 until a.length) if (JsValues.strictEq(a.get(i.toString()), target)) return@defineFn i.toDouble()
            (-1.0)
        }
        defineFn(r.arrayProto, "includes", 1) { self, args ->
            val a = asArr(self); val target = arg(args, 0)
            for (i in 0 until a.length) if (JsValues.strictEq(a.get(i.toString()), target)) return@defineFn true
            false
        }
        defineFn(r.arrayProto, "reverse", 0) { self, _ ->
            val a = asArr(self); val n = a.length
            for (i in 0 until n / 2) {
                val ki = i.toString(); val kj = (n - 1 - i).toString()
                val tmp = a.get(ki); a.set(ki, a.get(kj)); a.set(kj, tmp)
            }
            a
        }
        defineFn(r.arrayProto, "map", 1) { self, args ->
            val a = asArr(self); val fn = args.first() as JsFunction
            val out = JsArray().apply { proto = r.arrayProto }
            for (i in 0 until a.length) out.push(fn.call(JsValues.UNDEFINED, listOf(a.get(i.toString()), i.toDouble(), a)))
            out
        }
        defineFn(r.arrayProto, "filter", 1) { self, args ->
            val a = asArr(self); val fn = args.first() as JsFunction
            val out = JsArray().apply { proto = r.arrayProto }
            for (i in 0 until a.length) {
                val v = a.get(i.toString())
                if (JsValues.toBool(fn.call(JsValues.UNDEFINED, listOf(v, i.toDouble(), a)))) out.push(v)
            }
            out
        }
        defineFn(r.arrayProto, "forEach", 1) { self, args ->
            val a = asArr(self); val fn = args.first() as JsFunction
            for (i in 0 until a.length) fn.call(JsValues.UNDEFINED, listOf(a.get(i.toString()), i.toDouble(), a))
            JsValues.UNDEFINED
        }
        defineFn(r.arrayProto, "reduce", 2) { self, args ->
            val a = asArr(self); val fn = args.first() as JsFunction
            var acc: Any? = if (args.size > 1) args[1] else a.get("0")
            val start = if (args.size > 1) 0 else 1
            for (i in start until a.length) acc = fn.call(JsValues.UNDEFINED, listOf(acc, a.get(i.toString()), i.toDouble(), a))
            acc
        }
        defineFn(r.arrayProto, "find", 1) { self, args ->
            val a = asArr(self); val fn = args.first() as JsFunction
            for (i in 0 until a.length) {
                val v = a.get(i.toString())
                if (JsValues.toBool(fn.call(JsValues.UNDEFINED, listOf(v, i.toDouble(), a)))) return@defineFn v
            }
            JsValues.UNDEFINED
        }
        defineFn(r.arrayProto, "toString", 0) { self, _ ->
            val a = asArr(self); (0 until a.length).joinToString(",") { JsValues.toStr(a.get(it.toString())) }
        }

        r.globalObject.set("Array", ctor)
        r.globalEnv.declare("Array", ctor)
    }

    // ---------- String ----------
    private fun installString(r: Realm) {
        val ctor = JsFunction.native("String", 1) { _, args -> JsValues.toStr(arg(args, 0)) }
        ctor.set("prototype", r.stringProto)
        r.stringProto.set("constructor", ctor)

        fun selfStr(self: Any?): String = when (self) { is String -> self; else -> JsValues.toStr(self) }

        defineFn(r.stringProto, "charAt", 1) { self, args ->
            val s = selfStr(self); val i = JsValues.toInt32(arg(args, 0))
            if (i in s.indices) s[i].toString() else ""
        }
        defineFn(r.stringProto, "charCodeAt", 1) { self, args ->
            val s = selfStr(self); val i = JsValues.toInt32(arg(args, 0))
            if (i in s.indices) s[i].code.toDouble() else Double.NaN
        }
        defineFn(r.stringProto, "indexOf", 1) { self, args ->
            val s = selfStr(self); s.indexOf(JsValues.toStr(arg(args, 0)), JsValues.toInt32(arg(args, 1))).toDouble()
        }
        defineFn(r.stringProto, "includes", 1) { self, args -> selfStr(self).contains(JsValues.toStr(arg(args, 0))) }
        defineFn(r.stringProto, "startsWith", 1) { self, args -> selfStr(self).startsWith(JsValues.toStr(arg(args, 0))) }
        defineFn(r.stringProto, "endsWith", 1) { self, args -> selfStr(self).endsWith(JsValues.toStr(arg(args, 0))) }
        defineFn(r.stringProto, "slice", 2) { self, args ->
            val s = selfStr(self); val len = s.length
            var a = JsValues.toInt32(arg(args, 0)); if (a < 0) a = maxOf(0, len + a); a = minOf(a, len)
            var b = if (arg(args, 1) == JsValues.UNDEFINED) len else JsValues.toInt32(arg(args, 1))
            if (b < 0) b = maxOf(0, len + b); b = minOf(b, len)
            if (a >= b) "" else s.substring(a, b)
        }
        defineFn(r.stringProto, "substring", 2) { self, args ->
            val s = selfStr(self); val len = s.length
            var a = JsValues.toInt32(arg(args, 0)).coerceIn(0, len)
            var b = if (arg(args, 1) == JsValues.UNDEFINED) len else JsValues.toInt32(arg(args, 1)).coerceIn(0, len)
            if (a > b) { val t = a; a = b; b = t }
            s.substring(a, b)
        }
        defineFn(r.stringProto, "toLowerCase", 0) { self, _ -> selfStr(self).lowercase() }
        defineFn(r.stringProto, "toUpperCase", 0) { self, _ -> selfStr(self).uppercase() }
        defineFn(r.stringProto, "trim", 0) { self, _ -> selfStr(self).trim() }
        defineFn(r.stringProto, "split", 1) { self, args ->
            val s = selfStr(self); val sep = arg(args, 0)
            val parts = when (sep) {
                JsValues.UNDEFINED -> listOf(s)
                is String -> if (sep.isEmpty()) s.map { it.toString() } else s.split(sep)
                else -> s.split(JsValues.toStr(sep))
            }
            val arr = JsArray().apply { proto = r.arrayProto }
            parts.forEach { arr.push(it) }; arr
        }
        defineFn(r.stringProto, "repeat", 1) { self, args -> selfStr(self).repeat(JsValues.toInt32(arg(args, 0))) }
        defineFn(r.stringProto, "concat", 1) { self, args ->
            val sb = StringBuilder(selfStr(self))
            for (a in args) sb.append(JsValues.toStr(a))
            sb.toString()
        }
        defineFn(r.stringProto, "replace", 2) { self, args ->
            val s = selfStr(self); val pat = JsValues.toStr(arg(args, 0)); val repl = JsValues.toStr(arg(args, 1))
            val idx = s.indexOf(pat); if (idx < 0) s else s.substring(0, idx) + repl + s.substring(idx + pat.length)
        }
        defineFn(r.stringProto, "toString", 0) { self, _ -> selfStr(self) }
        defineFn(r.stringProto, "valueOf", 0) { self, _ -> selfStr(self) }

        defineFn(ctor, "fromCharCode", 1) { _, args ->
            val sb = StringBuilder()
            for (a in args) sb.append(JsValues.toInt32(a).toChar())
            sb.toString()
        }

        r.globalObject.set("String", ctor)
        r.globalEnv.declare("String", ctor)
    }

    // ---------- Number ----------
    private fun installNumber(r: Realm) {
        val ctor = JsFunction.native("Number", 1) { _, args -> if (args.isEmpty()) 0.0 else JsValues.toNumber(args[0]) }
        ctor.set("prototype", r.numberProto)
        ctor.set("NaN", Double.NaN)
        ctor.set("POSITIVE_INFINITY", Double.POSITIVE_INFINITY)
        ctor.set("NEGATIVE_INFINITY", Double.NEGATIVE_INFINITY)
        ctor.set("MAX_SAFE_INTEGER", 9007199254740991.0)
        ctor.set("MIN_SAFE_INTEGER", -9007199254740991.0)
        ctor.set("EPSILON", 2.220446049250313e-16)
        defineFn(ctor, "isNaN", 1) { _, a -> val v = arg(a, 0); v is Double && v.isNaN() }
        defineFn(ctor, "isFinite", 1) { _, a -> val v = arg(a, 0); v is Double && !v.isNaN() && !v.isInfinite() }
        defineFn(ctor, "isInteger", 1) { _, a -> val v = arg(a, 0); v is Double && !v.isNaN() && !v.isInfinite() && v == Math.floor(v) }
        defineFn(ctor, "parseFloat", 1) { _, a -> JsValues.toStr(arg(a, 0)).trim().toDoubleOrNull() ?: Double.NaN }
        defineFn(ctor, "parseInt", 2) { _, a ->
            val s = JsValues.toStr(arg(a, 0)).trim()
            val radix = if (arg(a, 1) == JsValues.UNDEFINED) 10 else JsValues.toInt32(arg(a, 1))
            try { java.lang.Long.parseLong(s, radix).toDouble() } catch (_: Throwable) { Double.NaN }
        }

        defineFn(r.numberProto, "toString", 1) { self, args ->
            val n = JsValues.toNumber(self)
            if (arg(args, 0) == JsValues.UNDEFINED) JsValues.numberToString(n)
            else java.lang.Long.toString(n.toLong(), JsValues.toInt32(arg(args, 0)))
        }
        defineFn(r.numberProto, "toFixed", 1) { self, args ->
            val n = JsValues.toNumber(self); val d = JsValues.toInt32(arg(args, 0))
            String.format("%.${d}f", n)
        }
        defineFn(r.numberProto, "valueOf", 0) { self, _ -> JsValues.toNumber(self) }

        r.globalObject.set("Number", ctor)
        r.globalEnv.declare("Number", ctor)
    }

    // ---------- Math ----------
    private fun installMath(r: Realm) {
        val m = JsObject(r.objectProto)
        m.set("PI", Math.PI); m.set("E", Math.E)
        m.set("LN2", Math.log(2.0)); m.set("LN10", Math.log(10.0))
        m.set("LOG2E", 1.0 / Math.log(2.0)); m.set("LOG10E", 1.0 / Math.log(10.0))
        m.set("SQRT2", Math.sqrt(2.0))
        val ds: (Any?) -> Double = { JsValues.toNumber(it) }
        defineFn(m, "abs", 1)   { _, a -> Math.abs(ds(arg(a, 0))) }
        defineFn(m, "floor", 1) { _, a -> Math.floor(ds(arg(a, 0))) }
        defineFn(m, "ceil", 1)  { _, a -> Math.ceil(ds(arg(a, 0))) }
        defineFn(m, "round", 1) { _, a -> Math.round(ds(arg(a, 0))).toDouble() }
        defineFn(m, "trunc", 1) { _, a -> val v = ds(arg(a, 0)); if (v < 0) Math.ceil(v) else Math.floor(v) }
        defineFn(m, "sign", 1)  { _, a -> val v = ds(arg(a, 0)); if (v > 0) 1.0 else if (v < 0) -1.0 else v }
        defineFn(m, "sqrt", 1)  { _, a -> Math.sqrt(ds(arg(a, 0))) }
        defineFn(m, "cbrt", 1)  { _, a -> Math.cbrt(ds(arg(a, 0))) }
        defineFn(m, "exp", 1)   { _, a -> Math.exp(ds(arg(a, 0))) }
        defineFn(m, "log", 1)   { _, a -> Math.log(ds(arg(a, 0))) }
        defineFn(m, "log2", 1)  { _, a -> Math.log(ds(arg(a, 0))) / Math.log(2.0) }
        defineFn(m, "log10", 1) { _, a -> Math.log10(ds(arg(a, 0))) }
        defineFn(m, "sin", 1)   { _, a -> Math.sin(ds(arg(a, 0))) }
        defineFn(m, "cos", 1)   { _, a -> Math.cos(ds(arg(a, 0))) }
        defineFn(m, "tan", 1)   { _, a -> Math.tan(ds(arg(a, 0))) }
        defineFn(m, "asin", 1)  { _, a -> Math.asin(ds(arg(a, 0))) }
        defineFn(m, "acos", 1)  { _, a -> Math.acos(ds(arg(a, 0))) }
        defineFn(m, "atan", 1)  { _, a -> Math.atan(ds(arg(a, 0))) }
        defineFn(m, "atan2", 2) { _, a -> Math.atan2(ds(arg(a, 0)), ds(arg(a, 1))) }
        defineFn(m, "pow", 2)   { _, a -> Math.pow(ds(arg(a, 0)), ds(arg(a, 1))) }
        defineFn(m, "min", 2)   { _, a -> if (a.isEmpty()) Double.POSITIVE_INFINITY else a.map(ds).let { if (it.any { x -> x.isNaN() }) Double.NaN else it.min() } }
        defineFn(m, "max", 2)   { _, a -> if (a.isEmpty()) Double.NEGATIVE_INFINITY else a.map(ds).let { if (it.any { x -> x.isNaN() }) Double.NaN else it.max() } }
        defineFn(m, "random", 0) { _, _ -> Math.random() }
        defineFn(m, "hypot", 2) { _, a -> Math.sqrt(a.sumOf { val d = ds(it); d * d }) }

        r.globalObject.set("Math", m)
        r.globalEnv.declare("Math", m)
    }

    // ---------- JSON ----------
    private fun installJson(r: Realm) {
        val j = JsObject(r.objectProto)
        defineFn(j, "stringify", 1) { _, a -> jsonStringify(arg(a, 0)) ?: JsValues.UNDEFINED }
        defineFn(j, "parse", 1) { _, a -> JsonParser(JsValues.toStr(arg(a, 0)), r).parse() }
        r.globalObject.set("JSON", j)
        r.globalEnv.declare("JSON", j)
    }

    private fun jsonStringify(v: Any?): String? = when (v) {
        Undefined -> null
        null -> "null"
        is Boolean -> v.toString()
        is Double -> if (v.isNaN() || v.isInfinite()) "null" else JsValues.numberToString(v)
        is Int, is Long -> v.toString()
        is String -> jsonEncodeString(v)
        is JsArray -> {
            val parts = (0 until v.length).map { jsonStringify(v.get(it.toString())) ?: "null" }
            "[" + parts.joinToString(",") + "]"
        }
        is JsObject -> {
            val sb = StringBuilder("{"); var first = true
            for (k in v.keys()) {
                val sv = jsonStringify(v.get(k)) ?: continue
                if (!first) sb.append(","); first = false
                sb.append(jsonEncodeString(k)).append(":").append(sv)
            }
            sb.append("}"); sb.toString()
        }
        else -> null
    }

    private fun jsonEncodeString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) sb.append(when (c) {
            '"' -> "\\\""; '\\' -> "\\\\"; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; '\b' -> "\\b"; '\u000C' -> "\\f"
            else -> if (c.code < 0x20) String.format("\\u%04x", c.code) else c.toString()
        })
        sb.append('"'); return sb.toString()
    }

    private class JsonParser(val s: String, val r: Realm) {
        var p = 0
        fun parse(): Any? { skip(); val v = value(); skip(); return v }
        private fun skip() { while (p < s.length && s[p].isWhitespace()) p++ }
        private fun value(): Any? {
            skip()
            if (p >= s.length) throw JsThrown("SyntaxError: Unexpected end of JSON")
            return when (s[p]) {
                '{' -> obj(); '[' -> arr()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> num()
            }
        }
        private fun expect(w: String) { if (!s.regionMatches(p, w, 0, w.length)) throw JsThrown("SyntaxError: expected $w"); p += w.length }
        private fun obj(): JsObject { p++; skip(); val o = JsObject(r.objectProto)
            if (p < s.length && s[p] == '}') { p++; return o }
            while (true) { skip(); val k = str(); skip(); if (s[p] != ':') throw JsThrown("SyntaxError: ':' expected"); p++; val v = value(); o.set(k, v); skip()
                if (s[p] == ',') { p++; continue }; if (s[p] == '}') { p++; return o }; throw JsThrown("SyntaxError: ',' or '}' expected") } }
        private fun arr(): JsArray { p++; skip(); val a = JsArray().apply { proto = r.arrayProto }
            if (p < s.length && s[p] == ']') { p++; return a }
            while (true) { val v = value(); a.push(v); skip(); if (s[p] == ',') { p++; continue }; if (s[p] == ']') { p++; return a }; throw JsThrown("SyntaxError: ',' or ']' expected") } }
        private fun str(): String { if (s[p] != '"') throw JsThrown("SyntaxError: string expected"); p++
            val sb = StringBuilder()
            while (p < s.length && s[p] != '"') {
                val c = s[p++]; if (c == '\\') { val e = s[p++]; sb.append(when (e) {
                    '"' -> '"'; '\\' -> '\\'; '/' -> '/'; 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'b' -> '\b'; 'f' -> '\u000C'
                    'u' -> { val h = s.substring(p, p + 4); p += 4; h.toInt(16).toChar() }
                    else -> throw JsThrown("SyntaxError: bad escape")
                }) } else sb.append(c)
            }
            if (p >= s.length) throw JsThrown("SyntaxError: unterminated string"); p++; return sb.toString()
        }
        private fun num(): Double { val start = p
            if (s[p] == '-') p++
            while (p < s.length && (s[p].isDigit() || s[p] == '.' || s[p] == 'e' || s[p] == 'E' || s[p] == '+' || s[p] == '-')) p++
            return s.substring(start, p).toDouble()
        }
    }

    // ---------- Errors ----------
    private fun installErrors(r: Realm) {
        fun makeErrorCtor(name: String): JsFunction {
            val proto = JsObject(r.errorProto); proto.set("name", name)
            val ctor = JsFunction.native(name, 1) { thisVal, args ->
                val o = (thisVal as? JsObject)?.takeIf { it.proto === proto } ?: JsObject(proto)
                o.className = "Error"; o.set("message", JsValues.toStr(arg(args, 0)))
                o
            }
            ctor.set("prototype", proto); proto.set("constructor", ctor)
            r.globalObject.set(name, ctor); r.globalEnv.declare(name, ctor)
            return ctor
        }
        r.errorProto.set("name", "Error")
        defineFn(r.errorProto, "toString", 0) { self, _ ->
            val o = self as? JsObject ?: return@defineFn "Error"
            val n = JsValues.toStr(o.get("name")); val m = JsValues.toStr(o.get("message"))
            if (m.isEmpty()) n else "$n: $m"
        }
        makeErrorCtor("Error")
        makeErrorCtor("TypeError"); makeErrorCtor("RangeError"); makeErrorCtor("SyntaxError"); makeErrorCtor("ReferenceError")
    }

    // ---------- console ----------
    private fun installConsole(r: Realm) {
        val c = JsObject(r.objectProto)
        fun printing(stream: java.io.PrintStream) = { _: Any?, args: List<Any?> ->
            stream.println(args.joinToString(" ") { JsValues.toStr(it) })
            JsValues.UNDEFINED
        }
        c.set("log", JsFunction.native("log", 0, printing(System.out)))
        c.set("info", JsFunction.native("info", 0, printing(System.out)))
        c.set("warn", JsFunction.native("warn", 0, printing(System.err)))
        c.set("error", JsFunction.native("error", 0, printing(System.err)))
        c.set("debug", JsFunction.native("debug", 0, printing(System.out)))
        r.globalObject.set("console", c)
        r.globalEnv.declare("console", c)
    }

    // ---------- globals ----------
    private fun installGlobals(r: Realm) {
        r.globalObject.set("NaN", Double.NaN); r.globalEnv.declare("NaN", Double.NaN)
        r.globalObject.set("Infinity", Double.POSITIVE_INFINITY); r.globalEnv.declare("Infinity", Double.POSITIVE_INFINITY)
        r.globalEnv.declare("isNaN", JsFunction.native("isNaN", 1) { _, a -> JsValues.toNumber(arg(a, 0)).isNaN() })
        r.globalEnv.declare("isFinite", JsFunction.native("isFinite", 1) { _, a -> val n = JsValues.toNumber(arg(a, 0)); !n.isNaN() && !n.isInfinite() })
        r.globalEnv.declare("parseInt", JsFunction.native("parseInt", 2) { _, a ->
            val s = JsValues.toStr(arg(a, 0)).trim()
            val radix = if (arg(a, 1) == JsValues.UNDEFINED) 10 else JsValues.toInt32(arg(a, 1))
            try { java.lang.Long.parseLong(s, radix).toDouble() } catch (_: Throwable) { Double.NaN }
        })
        r.globalEnv.declare("parseFloat", JsFunction.native("parseFloat", 1) { _, a -> JsValues.toStr(arg(a, 0)).trim().toDoubleOrNull() ?: Double.NaN })

        // BigInt(v) — coerce to BigInteger.  Accepts strings, numbers (must be integer), booleans, BigInteger.
        val bigIntCtor = JsFunction.native("BigInt", 1) { _, a ->
            when (val v = arg(a, 0)) {
                is java.math.BigInteger -> v
                is Boolean -> if (v) java.math.BigInteger.ONE else java.math.BigInteger.ZERO
                is Double -> {
                    if (v.isNaN() || v.isInfinite() || v != Math.floor(v))
                        throw JsThrown("RangeError: The number ${JsValues.toStr(v)} cannot be converted to BigInt")
                    java.math.BigInteger.valueOf(v.toLong())
                }
                is Int -> java.math.BigInteger.valueOf(v.toLong())
                is Long -> java.math.BigInteger.valueOf(v)
                is String -> try { java.math.BigInteger(v.trim()) }
                             catch (_: Throwable) { throw JsThrown("SyntaxError: Cannot convert '$v' to BigInt") }
                else -> throw JsThrown("TypeError: Cannot convert to BigInt")
            }
        }
        r.globalObject.set("BigInt", bigIntCtor); r.globalEnv.declare("BigInt", bigIntCtor)
    }
}
