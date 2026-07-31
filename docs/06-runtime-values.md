# D6 · 运行时值模型 Runtime Values

这一章讲 KJS 内部"值"长什么样——JS 里的数字、字符串、对象、函数，在 KJS 的实现里到底用什么来装。JS 本身只有几种值类型：number / string / boolean / null / undefined / object（含数组、函数）。KJS 用 Kotlin 的 `Any?`（一个什么都能装的盒子，类似 Java 的 `Object`）来统一装这些值，数字用 `Double`（双精度浮点），对象用 `JsObject`。值在计算机里怎么装，是引擎设计里一个基础而关键的取舍：有的引擎（如 V8）用 **NaN-boxing**——把值塞进一个 64 位整数里，利用"NaN 的位模式很特殊"来标记非数字，省内存但难写；有的用"带标签的指针"，从指针里借几位当类型标签。KJS 选了最简单的 `Any?` 装箱，实现容易、读起来直观，代价是连数字也要走对象、占内存多一点（M2 计划换成 NaN-boxing）。往下，§3 讲对象怎么通过"原型链"找属性（这是 JS 继承的秘密），§5 讲函数和作用域（闭包的地基）。

> 前置知识：D0（总览）、D5（VM）。
>
> 本篇拆解 `runtime/` 下的 `JsValue`/`JsObject`/`Realm`/`JsFunction`/Environment：KJS 用什么表示
> JS 的"值"，原型链如何实现，以及类型强制如何集中处理。它是 VM 与 Walker 两个后端共享的地基。

## 1. 统一装箱：一切皆 `Any?`

"装箱"这个词，意思是把所有类型的值都装进同一种"容器"里，这样栈、数组、返回值都能用同一个类型来装。KJS 用 Kotlin 的 `Any?` 当作这个容器。`undefined` 和 `null` 虽然都表示"空"，但其实是两回事：`undefined` 是一个特制的哨兵对象，`null` 就是真正的空，下面用代码和表格把这点说清。

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

### 2.1 `typeof`：值的"类型标签"

在所有 JS 运算符里，`typeof` 是少数几个对"未声明变量"也不会抛错的——`typeof x` 即使 `x` 从没定义，也只会安心返回 `"undefined"`。它的返回值在 KJS 里由 `JsValues.typeOf`（`JsValue.kt:15`）按 Kotlin 的实际运行类型分发：

| 运行时值 | `typeof` 结果 |
|---|---|
| `Undefined`（哨兵单例） | `"undefined"` |
| `null` | `"object"`（历史遗留：JS 规范里 `null` 的 typeof 就是 object） |
| `Boolean` | `"boolean"` |
| `Double` / `Int` / `Long` | `"number"` |
| `java.math.BigInteger` | `"bigint"` |
| `String` | `"string"` |
| `JsFunction` | `"function"` |
| `JsObject`（`callable != null`） | `"function"` |
| `JsObject`（其余） | `"object"` |

几点值得留意。第一，`typeof null === "object"` 是 JS 一直没改掉的"老 bug"，KJS 照规范实现（`JsValue.kt:17` 直接返回 `"object"`）。第二，函数本质上也是对象，只因为它身上 `callable` 槽非空，`typeof` 就回报 `"function"`——这和 §3 里"函数即对象"的设定一脉相承。第三，表里那行 `className == "Symbol"` 的分支（`JsValue.kt:25`）是为将来支持 Symbol 预留的：M1 并没有真正提供 `Symbol` 构造器，所以实战里跑不出 `"symbol"`，但值模型层已经给它留好了位置。

### 2.2 `in` 与 `instanceof`：两个依赖值模型的运算符

另外两个常被忽略、但底层都靠值模型的运算符：

- **`in`**：`key in obj` 等价于"obj 自身或其原型链上是否存在名为 key 的属性"。KJS 在 `evalBinary` 里把它转成 `(r as? JsObject)?.has(key)`（`Interpreter.kt:291`），VM 侧则走对象 `has` 的内联缓存路径。`in` 只看"有没有这个属性名"，不关心值是多少。
- **`instanceof`**：`x instanceof Ctor` 检查的是"x 的原型链上是否出现过 `Ctor.prototype` 这个对象"，而不是检查"类型"。实现是 `protoChainContains(x, Ctor.get("prototype"))`（`Interpreter.kt:292`）：从 `x.proto` 一路向上走，碰到与 `Ctor.prototype` 同一个对象就返回真。正因为比较的是"原型对象身份"，所以用不同 Realm 的构造函数去判断会得到 `false`——这是 `instanceof` 最容易踩坑的地方。

### 2.3 经典面试题拆解：`[] == ![]` 为什么是 true？

这道题几乎出现在每一场 JS 面试里，答案不是"JS 很坑"，而是 `looseEq` 的算法确实会一步步把它算成 `true`。用 KJS 的 `JsValues.looseEq`（`JsValue.kt:92`）走一遍：

**第一步：算 `![]`**。`!` 是逻辑非，先调 `toBool([])`。数组是对象，`toBool` 对对象一律返回 `true`（`JsValue.kt:35`），所以 `!true = false`。式子变成 `[] == false`。

**第二步：类型不同，走 `looseEq` 的抽象相等算法**。左边是 `JsArray`（对象），右边是 `Boolean`。按规范，布尔先转数字：`toNumber(false) = 0`。式子变成 `[] == 0`。

**第三步：对象 × 数字**。`looseEq` 遇到"对象 × 原始值"时，把对象走 `ToPrimitive` 拆箱（`JsValue.kt:98`）：`[]` 调 `defaultValue("number")`，先找 `valueOf`——数组的 `valueOf` 返回数组自身（还是对象），不行；再试 `toString`，`[].toString()` 返回 `""`（空字符串）。式子变成 `"" == 0`。

**第四步：字符串 × 数字**。`looseEq` 把字符串转数字：`toNumber("") = 0`。式子变成 `0 == 0`。

**第五步：同类型数字比较**。`0.0 === 0.0`，`true`。

整个过程：`[] == ![]` → `[] == false` → `[] == 0` → `"" == 0` → `0 == 0` → `true`。如果全程用 `===`，第一步就因类型不同而 `false`，根本走不到后面的拆箱。这就是"能用 `===` 就别用 `==`"的底层原因——`looseEq` 的隐式转换链太长，每一步都可能出意外。

同理可以解释 `'' == false`（`true`）、`'0' == false`（`true`）、`0 == '0'`（`true`）这些"反直觉"结果：它们都是 `looseEq` 算法按规范一步步算出来的，不是 bug。

## 3. JsObject：属性表 + 原型链

JS 并没有传统面向对象语言那种"类继承"机制（ES6 的 `class` 只是语法糖），对象之间是靠一条"原型链"串起来的：访问 `obj.x` 时，如果自己身上没有，就顺着 `__proto__` 往上游找。下面要讲的 `get` 方法，本质就是"沿链向上委托"。作为对比：有些语言选择直接把父类的属性拷贝过来（类式继承），而 JS 选了"链上委托"，更省内存，也允许在运行时动态改原型。

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

### 3.2 ToPrimitive 与 `defaultValue`：对象怎么"变"成原始值

当运算符需要把对象当成数字或字符串时（例如 `1 + obj`、`obj == 5`、`String(obj)`），JS 会先调 ToPrimitive 把对象"拆箱"成原始值。KJS 把这件事放在 `JsObject.defaultValue(hint)`（`JsObject.kt:45`）：

- `hint` 是 `"number"` 还是 `"string"`，决定调用 `valueOf` / `toString` 的**顺序**：数字语境先 `valueOf` 后 `toString`，字符串语境反过来（规范如此——因为 `obj + ""` 想要字符串、`+obj` 想要数字）。
- 候选方法必须是函数、且返回值**不是对象**才被采用，否则继续试下一个；两个都失败也有兜底：返回 `"[object ClassName]"`（`JsObject.kt:55`），所以未重载的对象永远有个字符串表示。
- `defaultValue` 调用候选方法时传的是 `f.call(this, ...)`（`JsObject.kt:51`）——也就是说拆箱过程中 `this` 指向对象自身，重载 `toString` 时能正常用 `this`（这正是 §5.1 要讲的 `this` 在方法调用里的样子）。

它和 `JsValues.toNumber` / `toStr` 是串起来的：`toNumber(obj)` 最终会落到 `toNumber(obj.defaultValue("number"))`，`toStr(obj)` 同理走 `defaultValue("string")`。于是 `1 + {}` 变成 `1 + "[object Object]"`（数字遇见字符串，整体按字符串拼接），而 `{}.x` 这类表达式的玄学结果，根子往往就在 ToPrimitive 的顺序上。

### 3.3 `__proto__` 与 `prototype`：最容易搞混的一对概念

几乎每个学 JS 的人都曾在某个深夜对着这两个词发呆：它们到底什么关系？

- **`__proto__`**（KJS 里对应 `JsObject.proto` 字段）：是**对象身上的一个内部链接**，指向"我的父亲是谁"。当你写 `obj.foo` 而 `obj` 自己身上没有 `foo` 时，引擎就顺着 `obj.proto` 去找。`__proto__` 是**实例**的属性，描述的是"我从谁那继承"。
- **`prototype`**：是**函数身上的一个普通属性**，指向"我生的孩子该认谁当父亲"。当你 `new Foo()` 时，引擎造出的新实例的 `proto` 就被设成 `Foo.prototype`。`prototype` 是**构造函数**的属性，描述的是"我制造出来的实例该继承谁"。

用 KJS 的代码说就是（`Interpreter.kt:403`）：

```kotlin
val proto = callee.get("prototype") as? JsObject ?: realm.objectProto
val instance = JsObject(proto)   // instance.proto = Foo.prototype
```

`Foo.prototype` 本身也是一个普通对象（`JsObject`），它也有自己的 `proto`——通常指向 `Object.prototype`。所以原型链是：`实例 → Foo.prototype → Object.prototype → null`。

**`Function.prototype` 是个特例**：它本身也是函数（`typeof Function.prototype === "function"`），这是 JS 规范里一个历史遗留的奇怪设计。KJS 里 `functionProto` 的 `callable` 被设为一个空函数，所以 `typeof` 回报 `"function"`。

**`Object.create(null)` 造出的对象没有原型链**：它的 `proto = null`，所以连 `toString`、`hasOwnProperty` 这些 `Object.prototype` 上的方法都没有。在 KJS 里就是 `JsObject(null)`，访问任何属性都直接返回 `UNDEFINED`，不会沿链查找。

### 3.4 `hasOwnProperty` 与 `in`：自有属性 vs 原型链属性

`obj.hasOwnProperty('x')` 和 `'x' in obj` 看起来很相似，实际检查的范围完全不同：

- `hasOwnProperty`（`JsObject.kt:37`）：只查 `properties` 这个自有属性表，**不**看原型链。`obj.hasOwnProperty('toString')` 对普通对象永远返回 `false`，因为 `toString` 在 `Object.prototype` 上，不在对象自己身上。
- `in`（`Interpreter.kt:291`）：调的是 `obj.has(key)`，先查自有属性，没有就沿 `proto` 链一路向上问。`'toString' in {}` 返回 `true`，因为链上能找到。

这个区别在遍历对象时尤其重要：`for (let k in obj)` 会枚举原型链上的所有可枚举属性（包括继承来的），而 `Object.keys(obj)` 只返回自有属性名。KJS 的 `keys()` 实现（`Intrinsics.kt:35`）就是只读 `properties.keys()`，不碰原型链。

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

### 4.1 Realm 隔离：多实例与跨 Realm 陷阱

`Realm` 的设计目的是**隔离**：每个 `Realm` 有自己独立的全局对象、全局环境和一整套内置原型。在浏览器里，每个 iframe 就是一个独立的 Realm；在 KJS 里，你可以创建多个 `Realm` 实例来模拟这种隔离。

隔离带来的最直接后果是 **`instanceof` 跨 Realm 失效**。假设 Realm A 里有个数组 `arr`，你把它传到 Realm B 去执行 `arr instanceof Array`——结果是 `false`。为什么？因为 B 的 `Array` 构造函数的 `prototype` 是一个**不同的对象**（B 的 `arrayProto`），而 `arr.proto` 指向的是 A 的 `arrayProto`。`protoChainContains` 在 B 的链上永远找不到 B 的 `Array.prototype`，所以返回 `false`。

KJS 的 `Array.isArray()`（`Intrinsics.kt:110`）用 `args.firstOrNull() is JsArray` 直接检查运行时类型，不受 Realm 影响——这就是为什么跨 Realm 时 `Array.isArray(arr)` 比 `arr instanceof Array` 更可靠。

另一个跨 Realm 陷阱是**构造函数判断**：`obj.constructor === Array` 在跨 Realm 时也会失败，因为 `obj.constructor` 指向 A 的 `Array`，而你在 B 里拿到的 `Array` 是另一个对象。`===` 比较的是对象身份，不是"是不是同一个类的概念"。

## 5. JsFunction 与 Environment：函数与词法作用域

Environment（环境）就是一张"名字 → 值"的表，而且表与表之间可以一层套一层（通过父环境指针），正好对应 JS 里"作用域嵌套"的现实：函数内部之所以能读到外层变量，靠的就是沿着父环境一路往上找。`closure` 把这份环境封存进函数对象，于是函数哪怕被传到别处调用，也找得到定义它时的那些变量——这就是"闭包"的实现原理。想看另一视角的实现，可以对照 D5 §9 讲的 Upvalue 盒子。

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

### 5.1 `this` 绑定：函数调用时如何确定 `this`

`this` 大概是 JS 里最容易被人讲玄的概念，但在引擎实现里它一点也不神秘：**`this` 就是函数被调用时、由调用方送进来的"第一个隐含参数"**。它的值完全取决于"这个函数是怎么被调用的"，跟函数定义在哪几乎没有关系——箭头函数除外。

把 `__arrow__` 标记先放一边，看 KJS 支持的四种调用形态各自把什么塞进 `this`：

1. **方法调用 `obj.method(...)`**——`this` 被绑定成 `obj`。
   - Walker：`evalCall`（`Interpreter.kt:390`）看到被调表达式是 `Member`（即 `obj.method`），就把 `obj` 算出来当作 `thisVal`；对普通函数 `self = thisVal ?: realm.globalObject`（`Interpreter.kt:397`）。
   - VM：`CALL_METHOD` 从栈上先弹出 `obj`、再弹出 `fn`，`thisRef` 取 `obj`（箭头函数除外，改用 `f.thisVal`）（`Vm.kt:480`）。
2. **普通调用 `foo(...)`**——没有"点"左边的对象，`thisVal` 是 `null`，于是退化成**全局对象**（KJS 没有严格模式，永远走非严格语义）。
   - Walker：`thisVal` 为 `null` 时 `self = realm.globalObject`（`Interpreter.kt:397`）。
   - VM：`CALL` 直接传 `realm.globalObject`（`Vm.kt:472`）。
3. **构造调用 `new Foo(...)`**——`this` 是一个**全新创建、原型指向 `Foo.prototype` 的空对象**（实例）。构造函数里对 `this` 的赋值，都是在装修这个新家。
   - Walker：`evalNew`（`Interpreter.kt:401`）先 `JsObject(proto)` 造实例，再 `callee.call(instance, args)`（`Interpreter.kt:406`）。
   - VM：`NEW_OP`（`Vm.kt:483`）同样造 `instance` 后 `ctor.call(instance, ...)`；若构造函数返回一个对象，则以返回值为准（`if (res is JsObject) res else instance`）。
4. **`call` / `apply` / `bind`**——由调用方**显式指定** `this`。
   - `Function.prototype.call(thisArg, ...args)` 把第一个参数当 `this`：`fn.call(arg(args,0), args.drop(1))`（`Intrinsics.kt:77`）。
   - `apply(thisArg, arrayLike)` 把第二个参数（数组）展开成参数列表（`Intrinsics.kt:87`）。
   - `bind(thisArg, ...a)` 返回一个新函数，固化 `this` 与前几个参数：`{ _, a -> fn.call(boundThis, boundArgs + a) }`（`Intrinsics.kt:93`）——这是"永久锁定 this"的办法，返回的函数再怎么调用 `this` 都不变。

**箭头函数：词法的 `this`。** 普通函数的 `this` 是"每次调用重新算"，箭头函数则不同——它**不绑定自己的 `this`**，而是沿用定义它时所在的那个 `this`（词法捕获）。实现上有两处配合：

- 创建时打标：箭头函数被标上 `__arrow__`（Walker 路径在 `Interpreter.kt:40`，VM 侧则是 `bc.isArrow` 在 `Vm.kt:85` 处标记）。
- 调用时不重新设：Walker 的 `callUserFn` 只在**非**箭头时才 `localEnv.declare("this", thisVal)`（`Interpreter.kt:421`）；箭头函数故意不声明 `this` 这个名字，于是 `this` 表达式顺着环境链向上找，撞到的就是外层函数的 `this`。VM 更直接：`CALL_METHOD` / 新建帧时，箭头的 `thisRef` 直接取**外层帧的 `thisVal`**（`Vm.kt:480`、`Vm.kt:184`），新帧的 `thisVal` 就等于外层帧的 `thisVal`。

**引擎里 `this` 到底存在哪？** 两个后端位置不同，但思想一致——`this` 不是普通变量：

- Walker 把它当成局部环境里一个**具名绑定**：进入函数体时 `declare("this", thisVal)`，代码里的 `this` 表达式就是 `env.get("this")`（`Interpreter.kt:203`）。箭头函数"没声明"，于是自然继承外层。
- VM 把它放在 `Frame` 的**专用字段 `thisVal`** 里（`Vm.kt:56`），`GET_THIS` 指令直接 `f.push(f.thisVal ?: realm.globalObject)`（`Vm.kt:496`），不占 `locals` 格子、也不进 `closure`。这样 `this` 和参数、局部变量在内存里分开了，查找更快。

**顶层 `this`。** 在脚本最外层（不在任何函数里）写 `this`，指向全局对象。`Realm` 初始化时就在全局环境里 `globalEnv.declare("this", globalObject)`（`Realm.kt:20`），并同时挂了 `globalThis` 指向它——所以 `this === globalThis === 全局对象` 在顶层恒成立。

和主流引擎对照：V8 同样把 `this` 当作"调用时传入的隐含参数"，在 Ignition 字节码里用一个专门的寄存器/累加器承载，TurboFan 编译后也只是一个参数槽。KJS 的 VM 用 `Frame.thisVal` 字段，思路完全一样；差异只在 KJS 没有严格模式——真实 JS 在严格模式下普通调用的 `this` 是 `undefined` 而非全局对象，而 KJS 永远给全局对象（见 §7 常见坑）。

### 5.2 `arguments` 对象

在 KJS 里，每个非箭头用户函数被调用时，除了参数，还会拿到一个类数组的 `arguments`：它按索引装着本次调用传进来的全部实参，长度等于实参个数（而非形参个数）。

- Walker：进入 `callUserFn` 时构造 `val arguments = JsArray().apply { args.forEach { push(it) } }`，再 `localEnv.declare("arguments", arguments)`（`Interpreter.kt:428`）。
- VM：由 `LOAD_ARGUMENTS` 指令现造一个数组，把 `f.args` 依次塞进去（`Vm.kt:497`）。

和真实 JS 一样，KJS 的 `arguments` 是一个**普通 `JsArray`**（不是 ES5 严格模式下那种与形参联动的"神奇数组"——M1 没做联动，改了参数 `arguments` 不一定跟着变，反之亦然）。箭头函数因为在 Walker 里不经历 `callUserFn` 的这套绑定、VM 里也没有自己的 `arguments` 槽，所以箭头函数内部访问 `arguments` 会顺着作用域拿到**外层函数的 `arguments`**——这同样是真实 JS 的行为。

### 5.3 `var` 与 `let`：Environment 中的两种变量声明

JS 里 `var` 和 `let` 的行为差异，根源在它们如何与 `Environment` 交互。KJS 的 Walker 后端在 `execStmt`（`Interpreter.kt:86`）里区分处理：

- **`var`**：先经过 `hoist`（`Interpreter.kt:50`）在**当前环境**里预声明为 `undefined`；执行到赋值语句时，如果当前环境已有该名（hoist 时创建的），就 `set` 更新值（`Interpreter.kt:92`）。这意味着 `var` 的作用域是**函数级**的——函数内所有 `var` 都挂在同一个 `Environment` 上，块级 `{}` 挡不住它。
- **`let`**：不走 hoist，执行到声明语句时直接在**当前环境** `declare`（`Interpreter.kt:95`）。如果当前环境（比如一个块级 `Block` 新建的子环境）已经存在同名变量，会重复声明报错（TDZ 的简化版）。块级 `{}` 在 Walker 里会新建一个 `Environment(env)` 子环境（`Interpreter.kt:76`），`let` 就挂在这个子环境上，出块后子环境销毁，变量自然不可见。

简单说：`var` 是"函数作用域、提升、可重复声明"，`let` 是"块作用域、不提升、不可重复声明"，而 KJS 的实现差异只在"hoist 时是否预声明"和"赋值时走 `set` 还是 `declare`"这两行代码。

**函数声明的提升**也在 `hoist` 里处理（`Interpreter.kt:53`）：遇到 `function foo() {}` 时，立刻用 `mkUserFunction` 创建函数对象并 `env.declare("foo", fn)`。所以函数可以在定义之前调用——这不是魔法，是编译器在跑代码前先把所有函数名登记好了。

## 6. 设计取舍

这一节总结全篇的取舍：用 `Any?` 装箱图的是省事，用 `JsValues` 集中处理类型转换是为了让两个后端结果一致，用 `className` 当形状键则是为了驱动内联缓存。每种选择都附了代价说明，M2 是后续的优化方向。

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
- **`0.1 + 0.2 !== 0.3`**：KJS 用 `Double` 表示所有数字，和真实 JS 一样受 IEEE 754 浮点精度限制。`0.1 + 0.2` 在二进制层面无法精确表示，结果是 `0.30000000000000004`。这是语言层面的限制，不是 KJS 的 bug——所有用 `Double`/`f64` 的引擎都一样。
- **`Function.prototype` 也是函数**：`typeof Function.prototype === "function"` 是规范要求的，KJS 里 `functionProto.callable` 被设为一个空 lambda（`Intrinsics.kt:71`）。这导致一些奇怪现象：你可以写 `Function.prototype()` 调用它（返回 `undefined`），也可以给它加属性——它本质上就是个 callable 的 `JsObject`。
- **函数 `length` 属性**：`function foo(a, b) {}` 的 `foo.length` 是 `2`，表示形参个数。KJS 在 `mkUserFunction` 时把 `params.size` 存到函数对象的 `length` 属性上（`Interpreter.kt:30` 附近）。注意 `rest` 参数和默认参数会影响这个值，但 M1 的 Walker 不支持复杂参数模式。
- **`Object.create(null)` 的对象没有 `toString`**：`proto = null` 意味着它不在任何原型链上，所以 `obj.toString` 直接返回 `undefined`，而不是 `"[object Object]"`。这在用对象当字典（hash map）时很有用——不用担心键名和原型链上的方法冲突。
- **`this` 的后端分歧（已知）**：箭头函数的 `this` 在 Walker 经 `evalCall` 取词法 `this`（`Interpreter.kt:397`），而 VM 的 `CALL`（标识符形式的"裸调用"）目前一律传 `realm.globalObject`（`Vm.kt:472`），只有 `CALL_METHOD`（点调用）才会复用外层帧的 `thisVal`（`Vm.kt:480`）。因此"嵌套函数里、以裸标识符调用的箭头函数"，VM 目前会给 `this = 全局对象`，与 Walker 的词法 `this` 不一致——这是 M2 收口双后端语义时要修的点，对拍测试通常走方法调用路径所以暂未暴露。
