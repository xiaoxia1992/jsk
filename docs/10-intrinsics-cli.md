# D10 · 内置库与宿主嵌入 / CLI

> 前置知识：D5、D6。本篇讲"JS 世界之外的砖块"：内置函数怎么用 Kotlin 实现、宿主怎么嵌入引擎、
> 以及命令行入口长什么样。

## 1. 内置函数 = JsNativeFn

所有 `Object` / `Array` / `console` / `Math` 等内置，本质都是 `JsNativeFn`
（`runtime/JsFunction.kt`）——用 Kotlin lambda 实现、直接跑、无字节码：

```kotlin
30:50:engine/src/main/kotlin/io/kjs/runtime/JsFunction.kt
class JsNativeFn(
    val name: String,
    val fn: (vm: Vm, args: List<Any?>, thisVal: Any?) -> Any?,
) : JsFunction() {
    override fun call(vm: Vm, args: List<Any?>, thisVal: Any?): Any? = fn(vm, args, thisVal)
}
```

`call` 直接进 Kotlin 函数，是性能热点（如 `Array.prototype.map`）。宿主/标准库作者只需写 Kotlin
lambda，就能给 JS 暴露原生能力。

## 2. Intrinsics：标准库实现

`Intrinsics`（`runtime/Intrinsics.kt:1`）安装核心内置到 `Realm`：

```kotlin
1:40:engine/src/main/kotlin/io/kjs/runtime/Intrinsics.kt
fun installIntrinsics(realm: Realm) {
    val g = realm.globalObj
    // 全局对象
    g.set("globalThis", g)
    g.set("Object", makeObjectCtor(realm))
    g.set("Array", makeArrayCtor(realm))
    g.set("Function", makeFunctionCtor(realm))
    g.set("String", makeStringCtor(realm))
    g.set("Number", makeNumberCtor(realm))
    g.set("Boolean", makeBooleanCtor(realm))
    g.set("Symbol", makeSymbolCtor(realm))
    g.set("Error", makeErrorCtor(realm))
    g.set("Math", makeMath(realm))
    g.set("JSON", makeJson(realm))
    g.set("console", makeConsole(realm))
    // 原型方法
    installObjectProto(realm.objectProto)
    installArrayProto(realm.arrayProto)
    installStringProto(realm.stringProto)
    ...
}
```

每个构造器/原型方法都是 `JsNativeFn`。示例——`Array.prototype.map`：

```kotlin
42:70:engine/src/main/kotlin/io/kjs/runtime/Intrinsics.kt
fun installArrayProto(proto: JsObject) {
    proto.set("map", JsNativeFn("map") { vm, args, thisVal ->
        val arr = thisVal as JsArray
        val cb = args[0] as JsFunction
        val selfThis = if (args.size > 1) args[1] else Undefined
        val out = JsArray()
        for (i in 0 until arr.length) {
            out.push(cb.call(vm, listOf(arr.get(i), i.toDouble(), arr), selfThis))
        }
        out
    })
    proto.set("forEach", ...)
    proto.set("push", ...)
    proto.set("filter", ...)
    proto.set("reduce", ...)
}
```

注意 `thisVal`：原型方法经 `CALL_METHOD` 拿到正确的 `this`（调用它的数组），这正是 D5 §3 的
`CALL_METHOD` 约定落地的地方。

`console.log` 用 `println` + 自定义 `repr`，`Math.*` 调 Kotlin `kotlin.math`，
`JSON.stringify/parse` 手写序列化/反序列化，全部走 `JsNativeFn`。

## 3. IntrinsicsExt：扩展内置

`IntrinsicsExt`（`runtime/IntrinsicsExt.kt:1`）放**非核心**但仍内置的 API（如 `Reflect`、
`Proxy` 部分、`Promise` 雏形、或项目特定的宿主 API）。与 `Intrinsics` 分离便于按需开启：

```kotlin
1:30:engine/src/main/kotlin/io/kjs/runtime/IntrinsicsExt.kt
fun installIntrinsicsExt(realm: Realm) {
    realm.globalObj.set("Reflect", makeReflect(realm))
    // 上次改动聚焦在这里：优化全局符号查找（配合 GlobalIc）
    realm.globalObj.set("__kjs", makeHostApi(realm))
}
```

> 本工作区中 `IntrinsicsExt.kt` 与 `GlobalIc.kt` 是最近改动的重点——前者暴露宿主 API，
> 后者加速这些全局名的解析（见 D7 §5）。

## 4. KjsNamespace：宿主命名空间

`KjsNamespace`（`runtime/KjsNamespace.kt:1`）把"引擎能力"打包成宿主可调用的 Kotlin API，
让嵌引擎的 App（如 `cat-video-*` 之外的脚本宿主）能：

- 注册自定义全局（`ns.expose("myApi", JsNativeFn{...})`）；
- 读/写 JS 全局（`ns.get("x")`、`ns.set("x", 1.0)`）；
- 调用 JS 函数并拿到返回值；
- 注入 Java/Kotlin 对象给 JS 当原生值。

```kotlin
1:40:engine/src/main/kotlin/io/kjs/runtime/KjsNamespace.kt
class KjsNamespace(val engine: Engine) {
    fun expose(name: String, fn: (Vm, List<Any?>, Any?) -> Any?) {
        engine.realm.globalObj.set(name, JsNativeFn(name, fn))
    }
    fun eval(src: String): Any? = engine.eval(src)
    fun callGlobal(fnName: String, vararg args: Any?): Any? { ... }
}
```

这是"把 KJS 当脚本引擎嵌入自家程序"的正式接口。

## 5. CLI / REPL（cli/Main.kt）

`Main`（`cli/src/main/kotlin/io/kjs/cli/Main.kt:1`）是命令行入口：

```kotlin
1:50:cli/src/main/kotlin/io/kjs/cli/Main.kt
fun main(args: Array<String>) {
    val engine = Engine(trace = args.contains("--trace"))
    when {
        args.contains("-e") -> {            // 内联代码
            val code = args[args.indexOf("-e") + 1]
            println(repr(engine.eval(code)))
        }
        args.contains("--trace") -> runFile(engine, firstFile(args), trace = true)
        args.isEmpty() -> repl(engine)       // 进入 REPL
        else -> runFile(engine, args.last()) // 运行文件
    }
}
fun repl(engine: Engine) {
    while (true) {
        print("kjs> "); val line = readLine() ?: break
        try { println(repr(engine.eval(line))) }
        catch (e: Throwable) { println("Error: ${e.message}") }
    }
}
```

- `-e 'code'`：执行内联代码并打印结果（用 `repr` 规范化输出）。
- `--trace`：把 `Engine` 设成 trace 模式，运行同时打印 token/AST/字节码/VM 步骤（见 D0 §5）。
- 无参：进入 REPL，逐行 `eval`，错误不退出。
- 文件路径：读文件 `eval`，等价于 `node script.js`。

`Engine` 的 `trace` 开关驱动 `Tracer`（`Engine.kt:38` 注入 `vm.tracer`）；
`--trace` 让 `eval` 内部每个编译阶段都回调 `Tracer`，输出到 stdout。

## 6. 宿主嵌入示例

```kotlin
val engine = Engine()
engine.realm.globalObj.set("greet",
    JsNativeFn("greet") { _, args, _ -> "hi ${args[0]}" })
val r = engine.eval("greet('world')")   // "hi world"
// 或用 KjsNamespace 更结构化
val ns = KjsNamespace(engine)
ns.expose("add") { _, a, _ -> (a[0] as Double) + (a[1] as Double) }
println(engine.eval("add(2,3)"))         // 5.0
```

## 7. 设计取舍

- **标准库用 Kotlin lambda 而非 JS 实现**：`map`/`filter` 等热路径直接走 Kotlin，避免自举解释开销。
- **Intrinsics / IntrinsicsExt 分离**：核心与扩展解耦，可按平台裁剪（如浏览器无 `console` 可省）。
- **KjsNamespace 作为嵌入面**：把"暴露 API / 读全局 / 调函数"收敛到一个类，宿主无需碰 `Realm` 内部。
- **CLI 复用 Engine**：REPL 与文件运行共用同一 `eval`，行为一致。

## 8. 常见坑

- **`thisVal` 在原生方法里可能是 `undefined`**：如 `const m = arr.map; m(arr)` 直接调会丢 `this`，
  `installArrayProto` 里必须处理 `thisVal is JsArray` 否则抛类型错误——JS 语义要求如此。
- **原生函数返回值必须是 JS 值**：Kotlin 的 `Int`/`Long` 不能直接上栈，需转 `Double`；
  `Unit` 应转 `Undefined`，否则 VM 栈上出现非 `Any?` JS 值导致后续强制崩溃。
- **全局名与 IC**：新增全局（`IntrinsicsExt.expose`）会被 `GlobalIc` 缓存；同一 `Realm` 内改名/删名
  需让 IC 失效（通常重新 `new Engine` 最简单）。
- **`repr` 递归深度**：`console.log` 打印嵌套对象需限制深度/环检测，否则栈溢出。
- **CLI 的 `-e` 与文件二义**：`-e` 后的参数才算代码，其余当作文件名——顺序敏感，需小心解析。
