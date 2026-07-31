# D7 · 内联缓存 Inline Cache

> **写给小白（本章导读）**：**内联缓存（Inline Cache，简称 IC）** 是让属性访问变快的"小记忆"。本章讲 VM 怎么记住"上次在这里读到的属性长什么样"，下次跳过慢查找。
> - **基础信息**：访问 `obj.x` 时，VM 默认要沿原型链一层层找（见 D6 §3），对象越深越慢。但同一行代码反复执行时，接收者的"形状"往往一样——比如循环里总读 `arr.length`，`arr` 一直是数组。IC 就记下"上次这个形状、在这个位置找到了"，下次先快查，命中就直接拿，O(1)。
> - **别的方案对比**：① **Hidden Class（隐藏类，V8 用）**：对象每次加属性就换一个"形状类"，属性偏移固定，访问直接算偏移，比 IC 更彻底但实现复杂；② **多态 IC**：记多个形状（KJS 记一个，超 10 次失效就放弃）。KJS 选了"每条指令一个单态缓存槽"，简单够用。
> - **进阶**：§2/§3 看 PropIc 和 GlobalIc 两种缓存，§4 看和 JIT 怎么共用同一份缓存。

> 前置知识：D5（VM）、D6（值模型）。
>
> 本篇拆解 `vm/PropIc.kt` 与 `vm/GlobalIc.kt`：VM 如何用**每指令一个缓存槽**记录"上次成功查找的
> 形状/拥有者"，把属性访问与原型链查找从 O(链长) 降为 O(1)。这是 VM 性能内核之一。

## 1. 为什么需要内联缓存

> **小白讲解**：先建立直觉：没有 IC 时，每次 `obj.x` 都要爬原型链，循环里成千上万次就明显慢。IC 把"这次查到的结果和形状"记在"这条指令自己身上"（每条指令一个缓存槽），下次先看缓存——这就是"内联"的意思（缓存内联在指令旁）。

`LOAD_PROP obj.x` 的慢路径（`propGet`，D5 §10）要沿 `obj` 的原型链逐层 `hasOwnProperty` 查找，
对象越深越慢。但现实里**同一处属性访问的接收者形状往往稳定**（如循环里反复读 `arr.length`，
`arr` 始终是 `JsArray`）。内联缓存（IC）就是："记住上次这次访问的形状和命中点，下次先快查，
命中就跳过原型链"。

KJS 的 IC 是**每字节码 PC 一个槽**（`bc.caches[pc]`，D5 §10），即每条 `LOAD_PROP` 指令独立缓存，
互不干扰。

## 2. PropIc：属性访问的单态缓存

> **小白讲解**：`PropIc` 是"属性访问的单态缓存"：记"接收者类型 + 属性名 → 上次在哪个原型层找到"。命中要过三道关（形状没变、属性没变、那个原型层还在链上），全过才跳过原型链直接拿；没命中就走慢路径并重新播种。注意 KJS 没有完整 Hidden Class，用"构造函数名"近似形状，所以加了二次校验防误命中。

`PropIc`（`PropIc.kt:14`）是单态（monomorphic）IC，记录"某接收者类名 + 属性名 → 命中的 owner"：

```kotlin
14:40:engine/src/main/kotlin/io/kjs/vm/PropIc.kt
class PropIc {
    private var cachedClass: String? = null       // 上次接收者的 className(形状键)
    private var cachedOwner: JsObject? = null      // 上次属性实际所在的原型层
    private var cachedValue: Any? = null
    private var cachedName: String? = null
    private var hits = 0; private var misses = 0
    private var megamorphic = false

    fun get(obj: JsObject?, name: String, slow: (JsObject?, String) -> Any?): Any? {
        if (obj == null) return slow(obj, name)
        if (!megamorphic) {
            val cls = obj.className
            if (cls == cachedClass && name == cachedName) {
                val owner = cachedOwner
                if (owner != null && owner.hasOwn(name)) {
                    var p: JsObject? = obj
                    // 校验 cachedOwner 仍是 obj 原型链上的祖先; 仍是则命中
                    while (p != null) { if (p === owner) { hits++; return owner.getOwn(name) }; p = p.proto }
                }
            }
        }
        val v = slow(obj, name)              // 未命中 → 慢路径
        if (!megamorphic) fillOrInvalidate(obj, name)
        return v
    }
}
```

命中条件有三道关：`cls == cachedClass`（形状没变）、`name == cachedName`（属性没变）、且
`cachedOwner` 仍然是 `obj` 原型链的祖先（`PropIc.kt:29` 的 `while` 校验）。只有三者全满足才直接
返回 `owner.getOwn(name)`，**完全跳过原型链**。

> **`className` 即"形状键"**：KJS 没有完整 HiddenClass，用构造函数名近似形状。同名不同结构对象可能
> 同 `className`，所以加了 `cachedOwner.hasOwn(name)` 二次校验（D6 §6 提到的局限与兜底）。

### 2.1 未命中时的播种与失效

`fillOrInvalidate`（`PropIc.kt:42`）沿原型链找到真正 owner，然后：

- 若还没缓存过（`cachedClass == null`）：记下 `className/owner/name`，成为单态。
- 若已缓存但**形状或属性变了**（`className != cachedClass || name != cachedName`）：计入 `misses`；
  若 `misses > 10` 标记 `megamorphic`（完全放弃缓存，永远走慢路径），否则**重新播种**（reseeded）
  到新形状。

```kotlin
42:56:engine/src/main/kotlin/io/kjs/vm/PropIc.kt
private fun fillOrInvalidate(obj: JsObject, name: String) {
    var p: JsObject? = obj
    while (p != null) { if (p.hasOwn(name)) break; p = p.proto }
    if (p == null) { misses++; if (misses > 10) megamorphic = true; return }
    if (cachedClass == null) { /* 首次 → 播种 */ cachedClass = obj.className; cachedOwner = p; cachedName = name; cachedValue = p.getOwn(name); return }
    if (cachedClass != obj.className || cachedName != name) {
        misses++
        if (misses > 10) { megamorphic = true; return }
        cachedClass = obj.className; cachedOwner = p; cachedName = name; cachedValue = p.getOwn(name)  // 重新播种
    }
}
```

### 2.2 与 VM/JIT 的关系

- **VM 端**：`LOAD_PROP` 惰性建 `PropIc` 放 `bc.caches[pc]`（`D5 §10` 的 `ic.get(obj, name){...}`）。
- **JIT 端**：`JitBridge.loadProp`（`JitBridge.kt:98`）**复用同一个 `bc.caches[pc]` 的 `PropIc`**，
  因为编译产物与原 `Bytecode` 共享对象。JIT 生成的代码调 `loadProp` 时仍走同一个单态缓存，HotSpot
  能把这次调用内联，单态站点几乎零开销（`Jit.kt` 注释强调 IC "highly inline-friendly"）。

```mermaid
flowchart TD
    LOAD["LOAD_PROP x @pc"] --> IC{caches[pc] 存在?}
    IC -->|否| NEW["new PropIc 放 caches[pc]"]
    IC -->|是| HIT{className/name 不变 且 owner 仍在链上?}
    NEW --> SLOW
    HIT -->|是| FAST["直接 owner.getOwn(name) O(1)"]
    HIT -->|否| SLOW["propGet 走原型链"]
    SLOW --> FILL["fillOrInvalidate: 播种/重播; miss 过多→megamorphic"]
```

## 3. GlobalIc：全局变量访问的缓存

> **小白讲解**：`LOAD_GLOBAL`（读全局变量如 `Math`）也要沿"环境链"找拥有者。`GlobalIc` 缓存的是"拥有这个变量的那张环境表本身"而非值——因为变量的值会变、`=` 后要立刻可见。这和 PropIc 缓存"值"是故意不同的选择（属性可能带 getter 有副作用，不能乱缓存值）。

`LOAD_GLOBAL` 的慢路径要沿 `Environment` 父链找拥有者（D6 §5）。`GlobalIc`（`GlobalIc.kt:26`）
针对"每条全局访问站点"缓存**拥有该名的 `Environment` 本身**（而非值）：

```kotlin
26:53:engine/src/main/kotlin/io/kjs/vm/GlobalIc.kt
class GlobalIc {
    @JvmField var cachedStart: Environment? = null   // 起始查找环境(常为 closureEnv)
    @JvmField var cachedOwner: Environment? = null    // 实际拥有该绑定的环境
    @JvmField var cachedName: String? = null

    fun get(start: Environment, name: String): Any? {
        val owner = if (cachedStart === start && cachedName === name) cachedOwner
                    else resolveAndFill(start, name)             // 失效则重解
        if (owner == null) return SENTINEL_MISSING
        return owner.vars[name]                                // 一次 HashMap.get
    }
    private fun resolveAndFill(start: Environment, name: String): Environment? {
        val owner = start.resolveOwner(name)                    // 沿 Environment 父链找拥有者
        cachedStart = start; cachedName = name; cachedOwner = owner
        return owner
    }
}
```

要点：

- **验证键是 `(start 环境, name)`**（`GlobalIc.kt:36`）：只有"从同一环境、查同一名字"才命中缓存的
  owner。新 `closureEnv` 或名字被遮蔽（shadow）→ 失效重解。
- **缓存"拥有者 map"而非值**（`GlobalIc.kt:18` 注释）：`owner.vars[name]` 每次都是一次 `HashMap.get`，
  所以 `=` 之后的新值**立即可见**，无需失效。这是与 PropIc 相对的设计选择（PropIc 缓存值是因为
  getter 不一定纯）。
- **`SENTINEL_MISSING`**：用专属对象区分"缓存说没找到"与"值是 JVM `null`"，避免把缺失误判成 `null`
  值（`GlobalIc.kt:52`）。
- **`tolerate` 语义**：缺失且 `tolerate != 0` 仍返回 `undefined`；否则由调用方抛 `ReferenceError`
  （D5 §11）。慢路径 `resolveOwner` 由 `Environment.resolveOwner` 提供（D6 §5）。

> 热点：`for` 循环里反复读全局函数（如 `Math.sqrt`）几乎总是单态——`closureEnv` 不变、名字不改，
> 每次直接 `owner.vars[name]`，零链遍历。

## 4. 设计取舍

> **小白讲解**：总结 IC 的设计权衡：每指令一个槽（互不污染）、用类名当形状键（轻量）、PropIc 缓存值 / GlobalIc 缓存表（各取所需）、失态过多直接放弃缓存（防抖动）、VM 和 JIT 共用一份缓存（一处优化两处受益）。

- **每 PC 一个 IC 槽**：指令间互不污染，单态/多态按站点独立判断；代价是 `caches` 数组按字节码长度
  分配（惰性创建，无属性访问的函数不分配，D5 §10）。
- **className 当形状键**：轻量；配合 owner 链校验兜底同名异结构对象。
- **PropIc 缓存值、GlobalIc 缓存 map**：取决于 getter 是否纯——属性 getter 可能含副作用，故
  缓存 owner 后仍需重算；全局变量是普通槽，故缓存 map。
- **megamorphic 退化**：多态过多直接弃缓存放慢路径，避免"缓存抖动"反而更慢。
- **VM/JIT 共用同一 `bc.caches`**：一份 IC 两后端受益，JIT 还能被 HotSpot 内联。

## 5. 常见坑

- **形状键精度**：仅 `className` 不足以区分所有结构；依赖 `cachedOwner.hasOwn(name)` 兜底，否则
  可能返回错误值（尤其代理对象/proto 动态修改）。
- **`megamorphic` 不可恢复**：一旦 `misses > 10` 永久退化为慢路径；写微基准时若混用多形状输入会
  触发，导致"测不准"热点性能。
- **owner 链校验缺失**：忘记 `while (p === owner)` 校验会缓存"曾经命中但已被改 proto"的 owner，
  返回过期值。
- **`SENTINEL_MISSING` 泄漏**：GlobalIc 的 `SENTINEL_MISSING` 绝不能当正常值返回给 JS 层，必须
  在调用方转成 `undefined` 或抛错。
- **IC 槽生命周期**：`caches` 挂在 `Bytecode` 上，多个 `Frame` 并发执行同一函数**共享同一 IC**（本
  VM 单线程，未加锁）；若未来多线程执行需考虑并发安全。
