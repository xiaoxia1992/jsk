# D6 · 运行时值模型 Runtime Values

> 前置知识：D0（总览）、D5（VM）。
>
> 本篇拆解 `runtime/` 下的 `JsValue`/`JsObject`/`Realm`/`JsFunction`/Environment：KJS 用什么表示
> JS 的"值"，原型链如何实现，以及类型强制如何集中处理。它是 VM 与 Walker 两个后端共享的地基。

## 1. 统一装箱：一切皆 `Any?`

KJS（M1）不做 NaN-boxing，而是用 Kotlin 的 `Any?` 直接承载所有 JS 值，靠"类型即标签"区分：

| Kotlin 类型 | 表示的 JS 值 | 说明 |
|---|---|---|
| `Double` | `number` | 所有数字统一为双精度（含整数） |
| `BigInteger` | `bigint` | `123n` 字面量 |
| `String` | `string` | |
| `Boolean` | `boolean` | |
| `null` | `null` | `JsValues.NULL` 就是 `null` 本身 |
| `Undefined`（单例对象） | `undefined` | `JsValues.UNDEFINED`，不是 `null` |
| `JsObject` | 对象 / 数组 | 含原型链 |
| `JsFunction`（继承 `JsObject`） | 函数 | `typeof` 为 `"function"` |

`Undefined` 用一个**哨兵单例对象**（`JsValue.kt:9`）而不是 `null`，这样 `undefined` 与 `null` 能
并存且区分（`null` 就是 JVM `null`）。`JsObject` 与 `JsFunction` 也走 `Any?`，于是属性、数组元素、
栈上值、返回值全都是同一个 `Any?` 类型——VM 的 `stack: Array<Any?>` 与此一致（D5 §2）。

```kotlin
9:13:engine/src/main/kotlin/io/kjs/runtime/JsValue.kt
object Undefined { override fun toString() = "undefined" }
object JsValues {
    val UNDEFINED: Any? = Undefined
    val NULL: Any? = null
```

## 2. JsValues：集中式类型强制（"oracle"）

所有 `toBool/toNumber/toStr/looseEq/strictEq` 都收口在 `JsValues`（`JsValue.kt:11`），VM 与 Walker
共享同一套语义，保证两个后端结果一致（D9）。

- **`toNumber`**（`JsValue.kt:42`）：`undefined→NaN`、`null→0`、布尔→`1/0`、字符串 `trim` 后解析、
  `JsObject` 走 `defaultValue("number")`。这是 JS `+`、比较、位运算的统一入口。
- **`toInt32/toUint32`**（`JsValue.kt:59`）：位运算的 `ToInt32` 抽象操作，`toUint32` 用 `and 0xFFFFFFFFL`
  得到无符号 32 位（`>>>` 用）。
- **`toStr`**（`JsValue.kt:70`）：数字经 `numberToString`（NaN/Inf/-0/整数特例，避免 `1.0`→`"1.0"`）。
- **`looseEq`（==）**（`JsValue.kt:92`）：先 `sameType` 走 `strictEq`；`null`/`undefined` 互等；
  数字×字符串→数字比；布尔→数字；对象×原始→拆箱比。这是 JS 最复杂的抽象相等算法。
- **`strictEq`（===）**（`JsValue.kt:104`）：同类型才相等；`Double.NaN` 与自身不等（`NaN !== NaN` 语义）。

> 把强制集中在一处，是"双后端对拍"能成立的前提——VM 的 `ADD`/比较与 Walker 的 `evalBinary` 都调
> 同一个 `JsValues`，自然一致。

## 3. JsObject：属性表 + 原型链

`JsObject`（`JsObject.kt:12`）是最小可用的 JS 对象：

```kotlin
12:40:engine/src/main/kotlin/io/kjs/runtime/JsObject.kt
open class JsObject(var proto: JsObject? = null) {
    val properties: LinkedHashMap<String, Any?> = LinkedHashMap()   // 自有属性, 保序
    var callable: JsFunction? = null                                // 是否为可调用(函数)
    var className: String = "Object"
    var extensible: Boolean = true

    open fun get(key: String): Any? {                                // 自有 + 原型链查找
        var o: JsObject? = this
        while (o != null) {
            if (o.properties.containsKey(key)) return o.properties[key]
            o = o.proto
        }
        return JsValues.UNDEFINED
    }
    open fun set(key: String, value: Any?) { properties[key] = value }
    // has / hasOwn / delete / keys 同构
}
```

核心机制：**`get` 沿 `proto` 链向上委托**（`JsObject.kt:21`）；`set` 只写自有表（不向上赋值，
符合 JS 默认语义）；`className` 用构造函数名充当"shape 标记"——这正是内联缓存（D7）判定
单态的形状键。`defaultValue`（`JsObject.kt:45`）实现 `ToPrimitive`：按 hint 顺序调 `valueOf`/
`toString`，否则返回 `"[object ClassName]"`。

### 3.1 特化子类型

- **`JsArray`**（`JsObject.kt:108`）：仅比 `JsObject` 多一个 `length`（写入下标 `>= length` 时自动
  扩长，D5 §11 的 `propSet` 也维护它）。索引存为字符串键（如 `"0"`），与对象共用 `get/set`。
- **`JsTypedArray`**（`JsObject.kt:128`）：在 `ByteArray` 上叠加 `get/set` 覆盖，把 `arr[i]` 路由到
  字节缓冲的强类型读写（`Int8`…`Float64`），VM 的 `LOAD_ELEM/STORE_ELEM` 透过 `get/set` 自动看到正确值。
- **`JsProxy`**（`JsObject.kt:68`）：拦截 `get/set/has/deleteProperty/ownKeys` 转发到 handler 的
  trap，未定义 trap 则 `target` 原样处理。这是 ES Proxy 的子集实现。

## 4. Realm：一次执行会话的全部全局状态

`Realm`（`Realm.kt:7`）把所有内置原型、全局对象、全局环境打包，便于创建互相隔离的引擎实例：

```kotlin
7:27:engine/src/main/kotlin/io/kjs/runtime/Realm.kt
class Realm {
    val objectProto = JsObject(null)
    val functionProto = JsObject(objectProto)
    val arrayProto = JsObject(objectProto)
    // numberProto / stringProto / booleanProto / errorProto ...
    val globalObject = JsObject(objectProto)
    val globalEnv = Environment()

    init {
        globalEnv.declare("this", globalObject)
        globalEnv.declare("undefined", JsValues.UNDEFINED)
        globalEnv.declare("globalThis", globalObject)
        Intrinsics.install(this)        // 内置函数(D10)
        IntrinsicsExt.install(this)
        KjsNamespace.install(this)
    }
}
```

原型链的"根"是 `objectProto`（`proto = null`）。每个构造器（`Object/Array/...`）在 `install` 时把
`ctor.prototype` 设为对应 proto，proto 的 `constructor` 指回 ctor，形成标准 JS 原型拓扑。`globalEnv`
是 `Environment`（见下），`LOAD_GLOBAL` 最终查它（D5 §11）。

## 5. JsFunction 与 Environment：函数与词法作用域

`JsFunction`（`JsFunction.kt:8`）继承 `JsObject`（`callable = this`），区分用户函数与宿主函数：

```kotlin
8:47:engine/src/main/kotlin/io/kjs/runtime/JsFunction.kt
class JsFunction private constructor(
    val name: String, val params: List<String>, val body: Block?,
    val closure: Environment?,                              // 用户函数的词法环境
    val native: ((thisVal: Any?, args: List<Any?>) -> Any?)?,  // 宿主函数 lambda
) : JsObject() {
    var invoker: ((JsFunction, Any?, List<Any?>) -> Any?)? = null   // VM 闭包走这里
    var vmClosure: Any? = null                                   // 已编译字节码的句柄(不透明)
    fun call(thisVal: Any?, args: List<Any?>): Any? {
        invoker?.let { return it(this, thisVal, args) }         // 优先 invoker(VM/用户)
        if (native != null) return native.invoke(thisVal, args)  // 否则宿主 lambda
        error("Function '$name' has no body")
    }
    // companion: user(...) / native(...) 工厂
}
```

- **`native` 宿主函数**：由 `JsFunction.native(name, arity, fn)` 创建，调 `call` 时走 `native.invoke`
  （D10 的内置库、CLI 都这样接入）。
- **用户函数**：`closure` 是定义处的 `Environment`；VM 用 `vmClosure` 挂编译产物（D5 §1）；Walker 用
  `invoker = ::callUserFn` 走树遍历（D9）。
- **`__arrow__` 标记**：箭头函数的 `this` 不重新绑定，VM 的 `CALL_METHOD` 与 Walker 的 `evalCall`
  据此沿用外层 `this`。

`Environment`（`JsFunction.kt:51`）是**词法作用域链**：`HashMap` 存名→值，`parent` 指外层环境：

```kotlin
51:88:engine/src/main/kotlin/io/kjs/runtime/JsFunction.kt
class Environment(val parent: Environment? = null) {
    fun has(name: String): Boolean { var e = this; while (e != null) { if (e.vars.containsKey(name)) return true; e = e.parent }; return false }
    fun get(name: String): Any? { var e = this; while (e != null) { if (e.vars.containsKey(name)) return e.vars[name]; e = e.parent }; return UNDEFINED }
    fun set(name: String, value: Any?): Boolean { /* 向上找已有绑定写入 */ }
    fun setOrDeclareGlobal(name: String, value: Any?) { if (!set(name, value)) { /* 走到根环境声明 */ } }
    fun resolveOwner(name: String): Environment? { /* 返回拥有该名的 Environment, 供 IC(D7) */ }
}
```

- `has/get/set` 都**沿 `parent` 链向上找**，实现嵌套作用域（块级 `let` 用 `Environment(env)` 子环境，
  D9 §3）。
- `setOrDeclareGlobal`（`JsFunction.kt:67`）：`=` 给未声明变量赋值时，沿链找不到就创建到**根环境**
  （非严格模式语义）。
- `resolveOwner`（`JsFunction.kt:80`）：返回拥有该名的 `Environment`，供 `GlobalIc`（D7 §2）缓存，
  "缓存的是拥有者 map"而非值——所以 `=` 后更新立即可见。

## 6. 设计取舍

- **`Any?` 统一装箱**：实现简单、VM/`Frame.locals` 直接复用；代价是数字也走堆对象（M2 计划换
  NaN-boxing 的 `Long`）。
- **强制集中 `JsValues`**：VM 与 Walker 共享，是双后端一致性的根基。
- **`className` 当 shape 键**：轻量形状标记，足够驱动单态 IC（D7），避免完整 HiddenClass。
- **原型链靠委托**：`get` 沿 `proto` 走，最简单直观；未做形状内联缓存的"隐藏类"优化（M2 方向）。
- **`Environment` 链即作用域**：与编译器的 scope 链（D4 §2）一一对应，`closure` 在 `JsFunction` 上
  封存，闭包捕获由此落地。

## 7. 常见坑

- **`undefined` vs `null`**：两者在 `Any?` 中是不同值（`Undefined` 单例 vs JVM `null`），`looseEq`
  故意让它们相等（`==`），但 `===` 不等，处理时别混淆。
- **`NaN` 比较**：`strictEq` 中 `NaN !== NaN`；`looseEq` 同理。涉及数字相等务必走 `JsValues`
  而非 Kotlin `==`。
- **原型链无限查找**：`get` 沿 `proto` 走，若原型环会死循环——KJS 原型链默认无环，但宿主挂 proto 时
  要注意。
- **数组 `length` 维护**：`set` 下标 `>= length` 自动扩长；`pop` 要显式 `properties.remove` + 降
  `length`，否则残留元素。
- **`className` 当 shape 键的局限**：同名不同结构的对象共享同一 `className`，`PropIc` 可能误命中
  （靠 `cachedOwner.hasOwn(name)` 二次校验兜底，D7 §1）。
- **`closure` 捕获的是 `Environment` 引用**：多个闭包共享同一 `Environment` 即共享其变量，配合
  Upvalue 盒子（D5 §9）实现捕获语义。
