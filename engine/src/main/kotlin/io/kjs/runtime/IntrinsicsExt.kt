package io.kjs.runtime

/**
 * Extra ES5/ES2015+ built-ins layered on top of the M1 [Intrinsics].
 * Kept in a separate file so the core intrinsics stay readable.
 */
object IntrinsicsExt {
    fun install(realm: Realm) {
        extendObject(realm)
        extendString(realm)
        extendArray(realm)
        extendNumber(realm)
        installRegExp(realm)
        installDate(realm)
        installSymbol(realm)
        installMap(realm)
        installSet(realm)
        installPromise(realm)
    }

    private fun arg(args: List<Any?>, i: Int): Any? = if (i < args.size) args[i] else JsValues.UNDEFINED
    private fun fn(name: String, arity: Int, body: (Any?, List<Any?>) -> Any?) = JsFunction.native(name, arity, body)

    // ---- Object extensions ----
    private fun extendObject(r: Realm) {
        val ctor = r.globalEnv.get("Object") as JsObject
        ctor.set("freeze", fn("freeze", 1) { _, a -> (arg(a, 0) as? JsObject)?.apply { extensible = false } ?: arg(a, 0) })
        ctor.set("isFrozen", fn("isFrozen", 1) { _, a -> (arg(a, 0) as? JsObject)?.extensible == false })
        ctor.set("getOwnPropertyNames", fn("getOwnPropertyNames", 1) { _, a ->
            val o = arg(a, 0) as? JsObject ?: return@fn JsArray().also { it.proto = r.arrayProto }
            JsArray().also { arr -> arr.proto = r.arrayProto; o.keys().forEach { arr.push(it) } }
        })
        ctor.set("defineProperty", fn("defineProperty", 3) { _, a ->
            val o = arg(a, 0) as? JsObject ?: return@fn arg(a, 0)
            val k = JsValues.toStr(arg(a, 1))
            val desc = arg(a, 2) as? JsObject
            if (desc != null && desc.hasOwn("value")) o.set(k, desc.get("value"))
            o
        })
        ctor.set("create", fn("create", 2) { _, a ->
            val proto = arg(a, 0)
            val o = JsObject(proto as? JsObject)
            val props = arg(a, 1) as? JsObject
            if (props != null) for (k in props.keys()) {
                val d = props.get(k) as? JsObject ?: continue
                if (d.hasOwn("value")) o.set(k, d.get("value"))
            }
            o
        })
        ctor.set("setPrototypeOf", fn("setPrototypeOf", 2) { _, a ->
            val o = arg(a, 0) as? JsObject ?: return@fn arg(a, 0)
            o.proto = arg(a, 1) as? JsObject; o
        })
        ctor.set("is", fn("is", 2) { _, a ->
            val x = arg(a, 0); val y = arg(a, 1)
            if (x is Double && y is Double) {
                if (x.isNaN() && y.isNaN()) true
                else if (x == 0.0 && y == 0.0) 1.0 / x == 1.0 / y
                else x == y
            } else JsValues.strictEq(x, y)
        })
    }

    // ---- String extensions ----
    private fun extendString(r: Realm) {
        val p = r.stringProto
        fun selfStr(s: Any?): String = when (s) { is String -> s; else -> JsValues.toStr(s) }
        p.set("padStart", fn("padStart", 2) { s, a ->
            val str = selfStr(s); val target = JsValues.toInt32(arg(a, 0))
            val fill = if (arg(a, 1) == JsValues.UNDEFINED) " " else JsValues.toStr(arg(a, 1))
            if (str.length >= target || fill.isEmpty()) str else buildString {
                val need = target - str.length; var added = 0
                while (added < need) { append(fill); added += fill.length }
                setLength(need); append(str)
            }
        })
        p.set("padEnd", fn("padEnd", 2) { s, a ->
            val str = selfStr(s); val target = JsValues.toInt32(arg(a, 0))
            val fill = if (arg(a, 1) == JsValues.UNDEFINED) " " else JsValues.toStr(arg(a, 1))
            if (str.length >= target || fill.isEmpty()) str else buildString {
                append(str); val need = target - str.length; var added = 0
                while (added < need) { append(fill); added += fill.length }
                setLength(target)
            }
        })
        p.set("trimStart", fn("trimStart", 0) { s, _ -> selfStr(s).trimStart() })
        p.set("trimEnd", fn("trimEnd", 0) { s, _ -> selfStr(s).trimEnd() })
        p.set("at", fn("at", 1) { s, a ->
            val str = selfStr(s); var i = JsValues.toInt32(arg(a, 0))
            if (i < 0) i += str.length
            if (i in str.indices) str[i].toString() else JsValues.UNDEFINED
        })
        p.set("lastIndexOf", fn("lastIndexOf", 1) { s, a ->
            selfStr(s).lastIndexOf(JsValues.toStr(arg(a, 0))).toDouble()
        })
        p.set("codePointAt", fn("codePointAt", 1) { s, a ->
            val str = selfStr(s); val i = JsValues.toInt32(arg(a, 0))
            if (i in str.indices) str.codePointAt(i).toDouble() else JsValues.UNDEFINED
        })
        p.set("normalize", fn("normalize", 0) { s, _ -> selfStr(s) })
        p.set("match", fn("match", 1) { s, a ->
            val str = selfStr(s); val re = toRegex(arg(a, 0))
            val arr = JsArray().also { it.proto = r.arrayProto }
            val m = re.find(str) ?: return@fn JsValues.NULL
            arr.push(m.value)
            for (g in m.groupValues.drop(1)) arr.push(g)
            arr
        })
        p.set("search", fn("search", 1) { s, a ->
            val str = selfStr(s); val re = toRegex(arg(a, 0))
            (re.find(str)?.range?.first ?: -1).toDouble()
        })
        // override replace to accept RegExp
        p.set("replace", fn("replace", 2) { s, a ->
            val str = selfStr(s); val pat = arg(a, 0); val repl = arg(a, 1)
            val replStr: (MatchResult) -> String = { m ->
                when (repl) {
                    is JsFunction -> {
                        val args = mutableListOf<Any?>(m.value)
                        args.addAll(m.groupValues.drop(1))
                        args.add(m.range.first.toDouble()); args.add(str)
                        JsValues.toStr(repl.call(JsValues.UNDEFINED, args))
                    }
                    else -> JsValues.toStr(repl)
                }
            }
            when (pat) {
                is JsObject -> if (pat.className == "RegExp") {
                    val re = toRegex(pat)
                    val global = JsValues.toStr(pat.get("flags")).contains('g')
                    if (global) re.replace(str, replStr) else re.replaceFirst(str, replStr(re.find(str) ?: return@fn str))
                } else {
                    val needle = JsValues.toStr(pat)
                    val idx = str.indexOf(needle); if (idx < 0) str else str.substring(0, idx) + replStr(MatchFakeResult(needle, idx, str)) + str.substring(idx + needle.length)
                }
                else -> {
                    val needle = JsValues.toStr(pat)
                    val idx = str.indexOf(needle); if (idx < 0) str else str.substring(0, idx) + replStr(MatchFakeResult(needle, idx, str)) + str.substring(idx + needle.length)
                }
            }
        })
        // String.raw (template tag) — simplified
        val strCtor = r.globalEnv.get("String") as JsObject
        strCtor.set("raw", fn("raw", 1) { _, a ->
            val tpl = arg(a, 0) as? JsObject ?: return@fn ""
            val raw = tpl.get("raw") as? JsArray ?: return@fn ""
            val substitutions = a.drop(1)
            buildString {
                val len = raw.length
                for (i in 0 until len) {
                    append(JsValues.toStr(raw.get(i.toString())))
                    if (i < len - 1 && i < substitutions.size) append(JsValues.toStr(substitutions[i]))
                }
            }
        })
    }

    private fun toRegex(v: Any?): Regex = when (v) {
        is JsObject -> if (v.className == "RegExp") {
            val src = JsValues.toStr(v.get("source")); val flags = JsValues.toStr(v.get("flags"))
            compileRegex(src, flags)
        } else compileRegex(JsValues.toStr(v), "")
        else -> compileRegex(JsValues.toStr(v), "")
    }
    private fun compileRegex(src: String, flags: String): Regex {
        val opts = mutableSetOf<RegexOption>()
        if (flags.contains('i')) opts.add(RegexOption.IGNORE_CASE)
        if (flags.contains('m')) opts.add(RegexOption.MULTILINE)
        if (flags.contains('s')) opts.add(RegexOption.DOT_MATCHES_ALL)
        return try { Regex(src, opts) } catch (_: Throwable) { Regex(Regex.escape(src), opts) }
    }
    private class MatchFakeResult(val v: String, val start: Int, val src: String) : MatchResult {
        override val destructured: MatchResult.Destructured get() = throw UnsupportedOperationException()
        override val groupValues: List<String> get() = listOf(v)
        override val groups: MatchGroupCollection get() = throw UnsupportedOperationException()
        override val range: IntRange get() = start until (start + v.length)
        override val value: String get() = v
        override fun next(): MatchResult? = null
    }

    // ---- Array extensions ----
    private fun extendArray(r: Realm) {
        val p = r.arrayProto
        fun asArr(self: Any?) = self as? JsArray ?: error("not an array")
        p.set("some", fn("some", 1) { self, a ->
            val arr = asArr(self); val f = a.first() as JsFunction
            for (i in 0 until arr.length) if (JsValues.toBool(f.call(JsValues.UNDEFINED, listOf(arr.get(i.toString()), i.toDouble(), arr)))) return@fn true
            false
        })
        p.set("every", fn("every", 1) { self, a ->
            val arr = asArr(self); val f = a.first() as JsFunction
            for (i in 0 until arr.length) if (!JsValues.toBool(f.call(JsValues.UNDEFINED, listOf(arr.get(i.toString()), i.toDouble(), arr)))) return@fn false
            true
        })
        p.set("findIndex", fn("findIndex", 1) { self, a ->
            val arr = asArr(self); val f = a.first() as JsFunction
            for (i in 0 until arr.length) if (JsValues.toBool(f.call(JsValues.UNDEFINED, listOf(arr.get(i.toString()), i.toDouble(), arr)))) return@fn i.toDouble()
            (-1.0)
        })
        p.set("flat", fn("flat", 0) { self, a ->
            val arr = asArr(self); val depth = if (arg(a, 0) == JsValues.UNDEFINED) 1 else JsValues.toInt32(arg(a, 0))
            flatten(arr, depth, r)
        })
        p.set("flatMap", fn("flatMap", 1) { self, a ->
            val arr = asArr(self); val f = a.first() as JsFunction
            val mapped = JsArray().also { it.proto = r.arrayProto }
            for (i in 0 until arr.length) mapped.push(f.call(JsValues.UNDEFINED, listOf(arr.get(i.toString()), i.toDouble(), arr)))
            flatten(mapped, 1, r)
        })
        p.set("sort", fn("sort", 1) { self, a ->
            val arr = asArr(self); val cmp = a.firstOrNull() as? JsFunction
            val items = (0 until arr.length).map { arr.get(it.toString()) }.toMutableList()
            items.sortWith(Comparator { x, y ->
                if (cmp != null) {
                    val r2 = JsValues.toNumber(cmp.call(JsValues.UNDEFINED, listOf(x, y)))
                    if (r2 < 0) -1 else if (r2 > 0) 1 else 0
                } else JsValues.toStr(x).compareTo(JsValues.toStr(y))
            })
            for (i in items.indices) arr.set(i.toString(), items[i])
            arr
        })
        p.set("fill", fn("fill", 1) { self, a ->
            val arr = asArr(self); val v = arg(a, 0)
            for (i in 0 until arr.length) arr.set(i.toString(), v)
            arr
        })
        p.set("copyWithin", fn("copyWithin", 2) { self, _ -> self })  // minimal
        p.set("at", fn("at", 1) { self, a ->
            val arr = asArr(self); var i = JsValues.toInt32(arg(a, 0))
            if (i < 0) i += arr.length
            if (i in 0 until arr.length) arr.get(i.toString()) else JsValues.UNDEFINED
        })
        p.set("lastIndexOf", fn("lastIndexOf", 1) { self, a ->
            val arr = asArr(self); val target = arg(a, 0)
            for (i in arr.length - 1 downTo 0) if (JsValues.strictEq(arr.get(i.toString()), target)) return@fn i.toDouble()
            (-1.0)
        })
        val arrCtor = r.globalEnv.get("Array") as JsObject
        arrCtor.set("from", fn("from", 1) { _, a ->
            val src = arg(a, 0); val map = a.getOrNull(1) as? JsFunction
            val out = JsArray().also { it.proto = r.arrayProto }
            fun addMapped(v: Any?, i: Int) {
                val mv = if (map != null) map.call(JsValues.UNDEFINED, listOf(v, i.toDouble())) else v
                out.push(mv)
            }
            when (src) {
                is String -> for ((i, c) in src.withIndex()) addMapped(c.toString(), i)
                is JsArray -> for (i in 0 until src.length) addMapped(src.get(i.toString()), i)
                is JsObject -> {
                    val len = JsValues.toInt32(src.get("length"))
                    for (i in 0 until len) addMapped(src.get(i.toString()), i)
                }
                else -> {}
            }
            out
        })
        arrCtor.set("of", fn("of", 0) { _, a ->
            val out = JsArray().also { it.proto = r.arrayProto }
            for (v in a) out.push(v); out
        })
    }

    private fun flatten(src: JsArray, depth: Int, r: Realm): JsArray {
        val out = JsArray().also { it.proto = r.arrayProto }
        fun rec(a: JsArray, d: Int) {
            for (i in 0 until a.length) {
                val v = a.get(i.toString())
                if (v is JsArray && d > 0) rec(v, d - 1) else out.push(v)
            }
        }
        rec(src, depth); return out
    }

    // ---- Number extensions ----
    private fun extendNumber(r: Realm) {
        val p = r.numberProto
        p.set("toPrecision", fn("toPrecision", 1) { self, a ->
            val n = JsValues.toNumber(self)
            if (arg(a, 0) == JsValues.UNDEFINED) JsValues.numberToString(n)
            else String.format("%.${JsValues.toInt32(arg(a, 0))}g", n)
        })
        p.set("toExponential", fn("toExponential", 1) { self, a ->
            val n = JsValues.toNumber(self); val d = if (arg(a, 0) == JsValues.UNDEFINED) 6 else JsValues.toInt32(arg(a, 0))
            String.format("%.${d}e", n)
        })
    }

    // ---- RegExp ----
    private fun installRegExp(r: Realm) {
        val proto = JsObject(r.objectProto); proto.className = "RegExp"
        val ctor = fn("RegExp", 2) { _, a ->
            val src = JsValues.toStr(arg(a, 0)); val flags = if (arg(a, 1) == JsValues.UNDEFINED) "" else JsValues.toStr(arg(a, 1))
            val o = JsObject(proto); o.className = "RegExp"
            o.set("source", src); o.set("flags", flags)
            o.set("global", flags.contains('g')); o.set("ignoreCase", flags.contains('i')); o.set("multiline", flags.contains('m'))
            o.set("lastIndex", 0.0)
            o
        }
        ctor.set("prototype", proto); proto.set("constructor", ctor)
        proto.set("test", fn("test", 1) { self, a ->
            val re = toRegex(self); re.containsMatchIn(JsValues.toStr(arg(a, 0)))
        })
        proto.set("exec", fn("exec", 1) { self, a ->
            val re = toRegex(self); val m = re.find(JsValues.toStr(arg(a, 0))) ?: return@fn JsValues.NULL
            val arr = JsArray().also { it.proto = r.arrayProto }
            arr.push(m.value); for (g in m.groupValues.drop(1)) arr.push(g)
            arr.set("index", m.range.first.toDouble())
            arr
        })
        proto.set("toString", fn("toString", 0) { self, _ ->
            val o = self as? JsObject ?: return@fn ""
            "/" + JsValues.toStr(o.get("source")) + "/" + JsValues.toStr(o.get("flags"))
        })
        r.globalObject.set("RegExp", ctor); r.globalEnv.declare("RegExp", ctor)
    }

    // ---- Date (minimal) ----
    private fun installDate(r: Realm) {
        val proto = JsObject(r.objectProto); proto.className = "Date"
        val ctor = fn("Date", 0) { _, a ->
            val o = JsObject(proto); o.className = "Date"
            val t = if (a.isEmpty()) System.currentTimeMillis().toDouble()
                    else if (a.size == 1) JsValues.toNumber(a[0])
                    else {
                        val cal = java.util.Calendar.getInstance()
                        cal.set(
                            JsValues.toInt32(a[0]),
                            if (a.size > 1) JsValues.toInt32(a[1]) else 0,
                            if (a.size > 2) JsValues.toInt32(a[2]) else 1,
                            if (a.size > 3) JsValues.toInt32(a[3]) else 0,
                            if (a.size > 4) JsValues.toInt32(a[4]) else 0,
                            if (a.size > 5) JsValues.toInt32(a[5]) else 0,
                        ); cal.timeInMillis.toDouble()
                    }
            o.set("_time", t); o
        }
        ctor.set("prototype", proto); proto.set("constructor", ctor)
        ctor.set("now", fn("now", 0) { _, _ -> System.currentTimeMillis().toDouble() })
        ctor.set("parse", fn("parse", 1) { _, a ->
            try { java.time.Instant.parse(JsValues.toStr(arg(a, 0))).toEpochMilli().toDouble() } catch (_: Throwable) { Double.NaN }
        })
        proto.set("getTime", fn("getTime", 0) { self, _ -> (self as? JsObject)?.get("_time") ?: Double.NaN })
        proto.set("valueOf", fn("valueOf", 0) { self, _ -> (self as? JsObject)?.get("_time") ?: Double.NaN })
        proto.set("toISOString", fn("toISOString", 0) { self, _ ->
            val t = JsValues.toNumber((self as? JsObject)?.get("_time") ?: Double.NaN)
            if (t.isNaN()) "Invalid Date" else java.time.Instant.ofEpochMilli(t.toLong()).toString()
        })
        proto.set("toString", fn("toString", 0) { self, _ ->
            val t = JsValues.toNumber((self as? JsObject)?.get("_time") ?: Double.NaN)
            if (t.isNaN()) "Invalid Date" else java.util.Date(t.toLong()).toString()
        })
        r.globalObject.set("Date", ctor); r.globalEnv.declare("Date", ctor)
    }

    // ---- Symbol (lightweight stub) ----
    private fun installSymbol(r: Realm) {
        val proto = JsObject(r.objectProto); proto.className = "Symbol"
        val ctor = fn("Symbol", 1) { _, a ->
            val desc = if (arg(a, 0) == JsValues.UNDEFINED) "" else JsValues.toStr(arg(a, 0))
            val o = JsObject(proto); o.className = "Symbol"
            o.set("description", desc); o.set("__uniq__", System.nanoTime().toDouble())
            o
        }
        ctor.set("prototype", proto); proto.set("constructor", ctor)
        ctor.set("iterator", run { val s = JsObject(proto); s.className = "Symbol"; s.set("description", "Symbol.iterator"); s })
        ctor.set("asyncIterator", run { val s = JsObject(proto); s.className = "Symbol"; s.set("description", "Symbol.asyncIterator"); s })
        ctor.set("for", fn("for", 1) { _, a ->
            val key = JsValues.toStr(arg(a, 0))
            val reg = r.globalObject.get("__symbolRegistry__") as? JsObject ?: JsObject(r.objectProto).also { r.globalObject.set("__symbolRegistry__", it) }
            val existing = reg.get(key); if (existing is JsObject) existing else {
                val s = JsObject(proto); s.className = "Symbol"; s.set("description", key); reg.set(key, s); s
            }
        })
        proto.set("toString", fn("toString", 0) { self, _ ->
            "Symbol(" + JsValues.toStr((self as? JsObject)?.get("description")) + ")"
        })
        r.globalObject.set("Symbol", ctor); r.globalEnv.declare("Symbol", ctor)
    }

    // ---- Map ----
    private fun installMap(r: Realm) {
        val proto = JsObject(r.objectProto); proto.className = "Map"
        val ctor = fn("Map", 0) { _, a ->
            val o = JsObject(proto); o.className = "Map"
            val store = java.util.LinkedHashMap<Any?, Any?>()
            o.set("__store__", MapHolder(store)); o.set("size", 0.0)
            val init = arg(a, 0)
            if (init is JsArray) for (i in 0 until init.length) {
                val pair = init.get(i.toString())
                if (pair is JsArray && pair.length >= 2) {
                    store[pair.get("0")] = pair.get("1"); o.set("size", store.size.toDouble())
                }
            }
            o
        }
        ctor.set("prototype", proto); proto.set("constructor", ctor)
        fun store(self: Any?): java.util.LinkedHashMap<Any?, Any?> = ((self as JsObject).get("__store__") as MapHolder).m
        proto.set("get", fn("get", 1) { self, a -> store(self)[arg(a, 0)] ?: JsValues.UNDEFINED })
        proto.set("set", fn("set", 2) { self, a -> val s = store(self); s[arg(a, 0)] = arg(a, 1); (self as JsObject).set("size", s.size.toDouble()); self })
        proto.set("has", fn("has", 1) { self, a -> store(self).containsKey(arg(a, 0)) })
        proto.set("delete", fn("delete", 1) { self, a -> val s = store(self); val r2 = s.remove(arg(a, 0)) != null; (self as JsObject).set("size", s.size.toDouble()); r2 })
        proto.set("clear", fn("clear", 0) { self, _ -> store(self).clear(); (self as JsObject).set("size", 0.0); JsValues.UNDEFINED })
        proto.set("forEach", fn("forEach", 1) { self, a ->
            val f = a.first() as JsFunction
            for ((k, v) in store(self)) f.call(JsValues.UNDEFINED, listOf(v, k, self))
            JsValues.UNDEFINED
        })
        r.globalObject.set("Map", ctor); r.globalEnv.declare("Map", ctor)
    }
    private class MapHolder(val m: java.util.LinkedHashMap<Any?, Any?>)

    // ---- Set ----
    private fun installSet(r: Realm) {
        val proto = JsObject(r.objectProto); proto.className = "Set"
        val ctor = fn("Set", 0) { _, a ->
            val o = JsObject(proto); o.className = "Set"
            val store = java.util.LinkedHashSet<Any?>()
            o.set("__store__", SetHolder(store)); o.set("size", 0.0)
            val init = arg(a, 0)
            if (init is JsArray) for (i in 0 until init.length) { store.add(init.get(i.toString())); o.set("size", store.size.toDouble()) }
            o
        }
        ctor.set("prototype", proto); proto.set("constructor", ctor)
        fun store(self: Any?): java.util.LinkedHashSet<Any?> = ((self as JsObject).get("__store__") as SetHolder).s
        proto.set("add", fn("add", 1) { self, a -> val s = store(self); s.add(arg(a, 0)); (self as JsObject).set("size", s.size.toDouble()); self })
        proto.set("has", fn("has", 1) { self, a -> store(self).contains(arg(a, 0)) })
        proto.set("delete", fn("delete", 1) { self, a -> val s = store(self); val r2 = s.remove(arg(a, 0)); (self as JsObject).set("size", s.size.toDouble()); r2 })
        proto.set("clear", fn("clear", 0) { self, _ -> store(self).clear(); (self as JsObject).set("size", 0.0); JsValues.UNDEFINED })
        proto.set("forEach", fn("forEach", 1) { self, a ->
            val f = a.first() as JsFunction
            for (v in store(self)) f.call(JsValues.UNDEFINED, listOf(v, v, self))
            JsValues.UNDEFINED
        })
        r.globalObject.set("Set", ctor); r.globalEnv.declare("Set", ctor)
    }
    private class SetHolder(val s: java.util.LinkedHashSet<Any?>)

    // ---- Promise (synchronous minimal) ----
    private fun installPromise(r: Realm) {
        val proto = JsObject(r.objectProto); proto.className = "Promise"
        val ctor = fn("Promise", 1) { _, a ->
            val o = JsObject(proto); o.className = "Promise"
            o.set("_state", "pending"); o.set("_value", JsValues.UNDEFINED)
            o.set("_onFulfilled", JsArray().also { it.proto = r.arrayProto })
            o.set("_onRejected", JsArray().also { it.proto = r.arrayProto })
            val resolve = fn("resolve", 1) { _, b -> settle(o, "fulfilled", arg(b, 0)); JsValues.UNDEFINED }
            val reject = fn("reject", 1) { _, b -> settle(o, "rejected", arg(b, 0)); JsValues.UNDEFINED }
            val exec = a.firstOrNull() as? JsFunction
            try { exec?.call(JsValues.UNDEFINED, listOf(resolve, reject)) }
            catch (e: JsThrown) { settle(o, "rejected", e.value) }
            o
        }
        ctor.set("prototype", proto); proto.set("constructor", ctor)
        ctor.set("resolve", fn("resolve", 1) { _, a ->
            val o = JsObject(proto); o.className = "Promise"; o.set("_state", "fulfilled"); o.set("_value", arg(a, 0))
            o.set("_onFulfilled", JsArray().also { it.proto = r.arrayProto }); o.set("_onRejected", JsArray().also { it.proto = r.arrayProto })
            o
        })
        ctor.set("reject", fn("reject", 1) { _, a ->
            val o = JsObject(proto); o.className = "Promise"; o.set("_state", "rejected"); o.set("_value", arg(a, 0))
            o.set("_onFulfilled", JsArray().also { it.proto = r.arrayProto }); o.set("_onRejected", JsArray().also { it.proto = r.arrayProto })
            o
        })
        proto.set("then", fn("then", 2) { self, a ->
            val p = self as JsObject
            val onF = a.getOrNull(0) as? JsFunction; val onR = a.getOrNull(1) as? JsFunction
            val result = ctor.call(JsValues.UNDEFINED, listOf(fn("_e", 2) { _, b ->
                val resolve = b[0] as JsFunction; val reject = b[1] as JsFunction
                val handle = { state: String, value: Any? ->
                    try {
                        if (state == "fulfilled" && onF != null) resolve.call(JsValues.UNDEFINED, listOf(onF.call(JsValues.UNDEFINED, listOf(value))))
                        else if (state == "rejected" && onR != null) resolve.call(JsValues.UNDEFINED, listOf(onR.call(JsValues.UNDEFINED, listOf(value))))
                        else if (state == "fulfilled") resolve.call(JsValues.UNDEFINED, listOf(value))
                        else reject.call(JsValues.UNDEFINED, listOf(value))
                    } catch (e: JsThrown) { reject.call(JsValues.UNDEFINED, listOf(e.value)) }
                    JsValues.UNDEFINED
                }
                when (JsValues.toStr(p.get("_state"))) {
                    "fulfilled" -> handle("fulfilled", p.get("_value"))
                    "rejected" -> handle("rejected", p.get("_value"))
                    else -> {
                        (p.get("_onFulfilled") as JsArray).push(fn("", 1) { _, c -> handle("fulfilled", arg(c, 0)); JsValues.UNDEFINED })
                        (p.get("_onRejected") as JsArray).push(fn("", 1) { _, c -> handle("rejected", arg(c, 0)); JsValues.UNDEFINED })
                    }
                }
                JsValues.UNDEFINED
            }))
            result
        })
        proto.set("catch", fn("catch", 1) { self, a ->
            (self as JsObject).get("then")?.let { (it as JsFunction).call(self, listOf(JsValues.UNDEFINED, arg(a, 0))) } ?: JsValues.UNDEFINED
        })
        proto.set("finally", fn("finally", 1) { self, a ->
            val cb = a.firstOrNull() as? JsFunction
            (self as JsObject).get("then")?.let {
                (it as JsFunction).call(self, listOf(
                    fn("", 1) { _, b -> cb?.call(JsValues.UNDEFINED, emptyList()); arg(b, 0) },
                    fn("", 1) { _, b -> cb?.call(JsValues.UNDEFINED, emptyList()); throw JsThrown(arg(b, 0)) }
                ))
            } ?: JsValues.UNDEFINED
        })
        r.globalObject.set("Promise", ctor); r.globalEnv.declare("Promise", ctor)
    }
    private fun settle(p: JsObject, state: String, value: Any?) {
        if (JsValues.toStr(p.get("_state")) != "pending") return
        p.set("_state", state); p.set("_value", value)
        val cbs = (if (state == "fulfilled") p.get("_onFulfilled") else p.get("_onRejected")) as? JsArray ?: return
        for (i in 0 until cbs.length) (cbs.get(i.toString()) as? JsFunction)?.call(JsValues.UNDEFINED, listOf(value))
    }
}
