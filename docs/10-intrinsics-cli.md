# D10 · 内置函数与 CLI

> **写给小白（本章导读）**：**内置函数**就是 JS "自带的函数"，比如 `console.log`、`Array.prototype.map`、`JSON.parse`、`Math.sqrt`。这一章讲它们是怎么"挂进" KJS 引擎的，以及命令行怎么驱动引擎。
> - **基础信息**：在 KJS 里，一个内置函数就是一个用 Kotlin 写的 lambda，外面套一层 `JsFunction.native(...)` 包装成 JS 函数。VM 调用任何函数都走同一条路，不分"你写的"还是"引擎自带的"，所以加一个内置 = 写个 lambda 挂到全局对象或某个原型上，**完全不用改编译器或 VM**。
> - **别的方案对比**：① 把内置写死进字节码/VM（如把 `console.log` 当特殊 opcode）——快但难扩展；② 用宿主语言注册（KJS 的做法，灵活、零侵入）；③ 用 JS 自身实现内置（如用 JS 写 `map`）——纯但慢、且启动依赖。KJS 选②，把核心库和扩展库拆成两层文件保持可读。
> - **进阶**：§3.1 看 `for-of` 怎么靠"迭代协议"统一数组/Map/Set 的遍历；§3.3 注意 Promise 目前是"同步模拟"的已知简化。

> 前置知识：D5（VM）、D6（值模型）、D9（双后端）。
>
> 本篇拆解 `runtime/Intrinsics.kt`、`runtime/IntrinsicsExt.kt`、`runtime/KjsNamespace.kt` 与
> `cli/Main.kt`：KJS 的 `Object/Array/JSON/Map/...` 这些"语言自带函数"是怎么接进引擎的，以及
> 命令行与 REPL 如何驱动引擎。读完能理解"宿主功能如何零侵入地挂进 VM"。

## 1. 核心机制：内置就是 `JsFunction.native`

> **小白讲解**：核心就一句：内置 = 一个 `native` 函数（Kotlin lambda 包成 JS 函数）。VM 的 `CALL` 不区分用户函数和宿主函数，都走 `JsFunction.call`。所以"挂载内置"只是 `target.set("map", nativeFn)`，干净利落。

所有内置函数都是 `JsFunction.native(name, arity, lambda)`（`D6 §5`）——一个 Kotlin lambda 包成 JS 函数。
VM 的 `CALL`/`CALL_METHOD` 调用任何 `JsFunction` 时都走 `JsFunction.call`（`D5 §4`），**不区分用户函数
还是宿主函数**（D5 §4 的 `invokeFast` 最终 `fn.call`）。所以"挂一个内置"= 创建 `native` 函数并设到
全局对象或某原型上，无需改动编译器或 VM。

两个统一的辅助（`Intrinsics.kt:22`）：

```kotlin
22:25:engine/src/main/kotlin/io/kjs/runtime/Intrinsics.kt
private fun arg(args: List<Any?>, i: Int): Any? = if (i < args.size) args[i] else JsValues.UNDEFINED
private fun defineFn(target: JsObject, name: String, arity: Int, fn: (Any?, List<Any?>) -> Any?) {
    target.set(name, JsFunction.native(name, arity, fn))
}
```

`arg` 处理"参数不足补 `undefined"`（统一参数语义）；`defineFn` 把 lambda 挂到 `target` 的 `name` 上
（原型或构造函数）。

## 2. Intrinsics：M1 核心内置库

`Intrinsics.install`（`Intrinsics.kt:8`）按构造器分组接线，每个组做三件事：建原生构造器 → 设
`prototype` 与 `constructor` 互指 → 在原型/全局上挂方法。涵盖 `Object/Function/Array/String/Number/
Math/JSON/Error/console/全局量`。

### 2.1 典型：Array.prototype 方法

`installArray`（`Intrinsics.kt:100`）把 `push/pop/shift/unshift/slice/concat/join/indexOf/includes/
reverse/map/filter/forEach/reduce/find/toString` 全挂到 `arrayProto`。例如 `map`（`Intrinsics.kt:170`）：

```kotlin
170:175:engine/src/main/kotlin/io/kjs/runtime/Intrinsics.kt
defineFn(r.arrayProto, "map", 1) { self, args ->
    val a = self as? JsArray ?: error("TypeError: not an array")
    val fn = args.first() as JsFunction
    val out = JsArray().apply { proto = r.arrayProto }
    for (i in 0 until a.length) out.push(fn.call(JsValues.UNDEFINED, listOf(a.get(i.toString()), i.toDouble(), a)))
    out
}
```

注意回调以 `undefined` 作 `this`、按 ES 顺序传 `(v, i, arr)`，与 JS 语义一致。`push/pop` 维护 `length`
（`Intrinsics.kt:114/117`）。

### 2.2 JSON：自带解析器

`JSON.parse` 用 Kotlin 手写的递归下降 `JsonParser`（`Intrinsics.kt:412`），`JSON.stringify` 用
`jsonStringify`（`Intrinsics.kt:380`）递归序列化（`undefined`/`function` 跳过、`NaN/Infinity→null`）。不
依赖外部 JSON 库，保证语义与 ES 一致。

### 2.3 错误与 console

`makeErrorCtor`（`Intrinsics.kt:456`）生产的 `Error/TypeError/...` 构造器在 `new` 时挂 `message`；`console`
的 `log/info/warn/error/debug`（`Intrinsics.kt:480`）只是 `System.out/err.println` 的封装，统一 `toStr` 参数。

### 2.4 全局量与 BigInt

`installGlobals`（`Intrinsics.kt:494`）挂 `NaN/Infinity/isNaN/isFinite/parseInt/parseFloat` 以及
`BigInt`（`Intrinsics.kt:507`）：把数字/字符串/布尔/BigInteger 强转成 `java.math.BigInteger`，非整数
数字抛 `RangeError`——这是 `123n` 字面量与 `BigInt()` 的落地。

## 3. IntrinsicsExt：ES2015+ 扩展库

`IntrinsicsExt`（`IntrinsicsExt.kt:8`）是**第二层**，刻意与 `Intrinsics` 分离以保持核心可读（文件头注释）。
它 `extend*` 既有原型并新增整组构造器：`Object.freeze/create/defineProperty/is`、`String.padStart/
regex 方法`、`Array.some/every/flat/sort/from/of`、`RegExp`、`Date`、`Symbol`、`Map/Set`、`Promise`、
`Proxy`、`Reflect`、`ArrayBuffer`、`TypedArray`、`DataView`。

### 3.1 迭代协议支撑 for-of（关键连接）

数组的 `for-of` 能力来自原型上的 `@@iterator`（`Intrinsics.kt:213`）返回一个实现了 ES 迭代协议的
对象。生成器是 `makeJsIterator`（`IntrinsicsExt.kt:43`）：

```kotlin
43:52:engine/src/main/kotlin/io/kjs/runtime/IntrinsicsExt.kt
internal fun makeJsIterator(r: Realm, it: Iterator<Any?>): JsObject {
    val o = JsObject(r.objectProto); o.className = "Iterator"
    o.set("next", JsFunction.native("next", 0) { _, _ ->
        if (it.hasNext()) iterResult(r, it.next(), false)
        else iterResult(r, JsValues.UNDEFINED, true)
    })
    o.set("@@iterator", JsFunction.native("@@iterator", 0) { self, _ -> self ?: JsValues.UNDEFINED })
    return o
}
```

它把 Kotlin `Iterator` 包成 `{ next() → {value, done} }` 对象。**VM 的 `for-of`（`D5 §15`）正是调用
这个 `next()`**——于是 `for (x of arr)`、`Map`/`Set` 的遍历都走同一套协议。字符串/数组原型注册的
`keys/values/entries` 也都用 `makeJsIterator`（`Intrinsics.kt:209`）。

### 3.2 Map / Set 的存储后端

`Map` 用 `MapHolder`（`IntrinsicsExt.kt:578`，内含 `LinkedHashMap`）作底层存储，`get/set/has/delete`
直接转发；`entries/keys/values` 用 `makeJsIterator` 暴露迭代器。`Set` 同构（`SetHolder` + `LinkedHashSet`）。
`size` 每次写后更新（`IntrinsicsExt.kt:538`）。

### 3.3 Promise（同步最小实现）

`installPromise`（`IntrinsicsExt.kt:627`）用 `_state/_value/_onFulfilled/_onRejected` 字段模拟 Promise
状态机，`then` 注册回调、`settle`（`IntrinsicsExt.kt:692`）在 resolve 时同步触发。这是**同步**实现
（无微任务队列），能满足基本 `then` 链但与真实事件循环时序不同——属已知简化。

### 3.4 其它

- `RegExp`：包成 `JsObject` 带 `source/flags`，`toRegex`（`IntrinsicsExt.kt:234`）把 flags 映射到 Kotlin
  `RegexOption`；`String.match/replace` 据此支持正则。
- `Proxy`：直接 `JsProxy(target, handler)`（D6 §3），其余 trap 由 `JsObject` 转发。
- `Date`/`Symbol`：轻量实现（`getFullYear` 等用 `Calendar`；`Symbol` 用 `__uniq__` 模拟唯一性）。
- `ArrayBuffer`/`TypedArray`/`DataView`：在 `ByteArray` 上实现，`JsTypedArray` 的 `get/set` 覆盖把
  下标路由到字节缓冲（D6 §3），V8 之外的 `LOAD_ELEM` 自动看到强类型值。

## 4. KjsNamespace：宿主如何扩展引擎（范例）

`KjsNamespace.install`（`KjsNamespace.kt:16`）演示**嵌入者如何零侵入地把应用 API 暴露给 JS**。它挂一个
`kjs` 全局对象，提供 `rand/ms/assert/repeat`：

- `assert`（`KjsNamespace.kt:31`）失败时用 `throw JsThrown(err)` 抛 JS 层异常（错误对象用 `errorProto`
  构造），证明原生函数能正常抛错被 VM 的 `try/catch` 捕获（D5 §14）。
- `repeat`（`KjsNamespace.kt:44`）接收 JS 函数作回调（`cb.call(...)`），证明原生函数可高阶调用 JS 函数。

文件头注释强调：**无需改编译器或 VM**——通用 `CALL_METHOD` 已能调用任何 `JsFunction.call` 实现。这是
KJS 可嵌入性的核心。

## 5. CLI / REPL（Main.kt）

> **小白讲解**：命令行入口极简：`kjs 文件.js` 读文件执行、`kjs -e '代码'` 执行内联代码、空参数进 REPL 交互。最有用的是 `kjs --trace -e '...'`——把"词法→语法→编译→VM 步进"全程打印，是边读文档边看真实执行流的最佳方式。

`cli/Main.kt` 是极简入口，演示如何驱动 `Engine`：

```kotlin
15:26:engine/src/main/kotlin/io/kjs/cli/Main.kt
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
```

- `kjs` → `repl`（交互式，`.exit` 退出，`readlnOrNull` 逐行 `engine.eval` 并打印）。
- `kjs script.js` → 读文件执行。
- `kjs -e 'code'` → 执行内联代码。
- `--trace` → `Engine(trace=true)`，把词法/语法/编译/VM 各阶段旁白打印（D0 §4 的 `Tracer`）。
- `runCode`/`repl` 都捕获 `JsThrown` 打印 `"Uncaught ..."`，与 `Engine.evalToString` 一致（`D0 §4`）。

## 6. 设计取舍

> **小白讲解**：总结：内置用 native 函数（零侵入）、核心库与扩展库分层、迭代协议统一、Promise 同步化（已知简化）、CLI 极简。代价是 Promise 时序非标准、扩展库要手动维护。

- **内置 = native JsFunction**：统一走 `JsFunction.call`，VM 无需特判，扩展内置零侵入。
- **两层库分离**：`Intrinsics`（M1 核心）与 `IntrinsicsExt`（ES2015+）分开，核心保持可读。
- **迭代协议统一 makeJsIterator**：数组/`Map`/`Set`/字符串共用一套 `{next}` 包装，`for-of` 一处实现。
- **Promise 同步化**：用状态字段模拟，省微任务队列，满足基本链但时序非标准（已知简化）。
- **CLI 极简**：`--trace` 直接接 `Engine` 的 `Tracer`，是学习内部运行的最佳入口。

## 7. 常见坑

- **`this` 绑定**：原型方法回调应以 `undefined` 作 `this`（如 `map` 的 `fn.call(UNDEFINED, ...)`），
  否则数组方法里 `this` 指向错误。
- **参数不足**：所有内置必须用 `arg(args, i)` 取值，否则 `args[i]` 越界抛 `IndexOutOfBounds`。
- **`length` 维护**：`push/pop/shift` 必须同步更新 `JsArray.length`，否则 `for-of`/`map` 遍历越界。
- **`NaN/Infinity` 在 JSON**：`JSON.stringify` 把 `NaN/Infinity` 写成 `"null"`，与 ES 一致，别误当数字。
- **Promise 时序**：当前是同步 resolve，不要依赖 `then` 的异步（微任务）语义。
- **原生抛错用 `JsThrown`**：宿主函数要抛 JS 异常必须 `throw JsThrown(value)`，否则 VM 的 `catch
  (JsThrown)`（D5 §14）捕获不到，变成宿主崩溃。
- **`makeJsIterator` 的 `@@iterator` 自引用**：迭代器对象自身也要注册 `@@iterator`（`IntrinsicsExt.kt:50`），
  否则 `for (x of iterator)` 不工作。
