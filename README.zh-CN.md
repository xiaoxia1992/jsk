# KJS — Kotlin/JVM 的 JavaScript 引擎

**🌐 Language / 语言:** [English](./README.md) · **简体中文**

---

**KJS** 是一个用纯 Kotlin 在 JVM 上从零实现的 JavaScript 引擎，整体架构对标 [QuickJS](https://bellard.org/quickjs/)：手写的词法/语法分析器产出 AST，编译器翻译成字节码，最终由带属性内联缓存的栈式虚拟机执行。

它支持 **ES5 + ES2015+ 的实用子集**，自带一个"教学模式"（`--trace`）能把从 token 到栈状态的每一步都打印出来，在递归与紧循环场景下比 tree-walking 解释器 **快约 2 倍**。

---

## 亮点

- **同一 API 两套后端** — 默认走字节码 VM；Tree-walking 解释器保留为对拍 oracle，24 个 VM↔Walker 对拍用例零分歧。
- **60+ opcode 栈式字节码** — 指令存在三条并行的 `IntArray`（opcode / 操作数 A / 操作数 B）中，对 CPU cache 友好。
- **真闭包** — 被捕获的 local 会就地替换成共享的 `Upvalue` box，多个闭包和父函数看到的永远是同一份值。
- **单态属性内联缓存**（`PropIc`）— 命中时直接按 slot 读取；重复未命中后降级为 megamorphic。
- **模板 JIT** — 热函数会在运行时通过 ASM 被编译成 JVM 字节码，再由 HotSpot C2 进一步编译到机器码，算术热循环实测 **12–40× 加速**。
- **`--trace` 教学模式** — 打印 token 流、AST、字节码反汇编、以及 VM 每一步执行后的栈状态，便于学习。
- **可嵌入** — 宿主（Kotlin）代码可以注入自己的命名空间和 native 函数，内置 `kjs.rand / kjs.ms / kjs.assert / kjs.repeat` 就是示例。
- **Test262 兼容运行器** — 解析 YAML frontmatter、加载 `harness/` includes、支持 `negative` / `features` 指令。
- **55 个单元测试全绿**，覆盖 basics、ES2015+、VM/Walker 对拍、Test262 风格 fixtures 四个维度。

## 支持的语言特性

**ES5 核心**：`var` / `let` / `const`、块级作用域、`if` / `while` / `do-while` / `for` / `for-in` / `for-of`、带标签的 `break` / `continue`、`try` / `catch` / `finally`、`typeof` / `delete` / `in` / `void`、完整的原型链、`new`、`instanceof`。

**ES2015+**：带 `${…}` 插值的模板字符串、带词法 `this` 的箭头函数、正则字面量、`Object.defineProperty` / `freeze` / `create` / `setPrototypeOf` / `is`、`Array.from` / `of` / `flat` / `flatMap` / `sort`、`String.padStart` / `padEnd` / `match` / `replace`（支持正则）、`Map` / `Set`、`Date`、`Symbol`（最小占位）、同步版 `Promise`。

## 架构

```
  源代码
     │
     ▼                            ┌─── Tracer (可选, --trace) ─────────┐
   Lexer      ──► tokens          │                                    │
     │                            │   打印 token 流                    │
     ▼                            │   打印 AST                         │
  Parser      ──► AST ────────────│   打印字节码反汇编                 │
     │                            │   打印 VM 每一步的栈状态           │
     ▼                            │                                    │
  Compiler   ──► Bytecode ────────┘                                    │
     │           (IntArray 指令 + 常量池 / 字符串池 + 嵌套函数)        │
     ▼                                                                 │
   Stack VM  ──► 运行时值 (JsObject / JsArray / JsFunction)            │
     │                                                                 │
     └─► PropIc (内联缓存) ──► 原型链查找 (慢路径) ────────────────────┘
```

关键设计：

- **栈式字节码** — AST 节点按后序遍历翻译成指令序列，VM 主循环是一个紧凑的 `while (true) when (op)`，Kotlin 会把它编译成 JVM `tableswitch`。
- **Open upvalue** — 当内层函数捕获外层 local 时，对应的 frame slot 会就地替换成一个共享 box；父函数和所有闭包都通过同一个引用读写，保证语义正确。
- **Frame 池化** — 操作数栈和 locals 数组跨调用复用，递归密集场景下不会给 GC 制造压力。
- **调用快路径** — VM 编译的函数之间互相调用时绕过通用的 `JsFunction.call` 间接层，直接用缓存的 `VmClosure` 句柄 dispatch。

## 模块布局

| 模块 | 内容 |
|---|---|
| `engine` | 词法、解析、AST、IR（opcode / bytecode / compiler）、栈式 VM、运行时值、内置对象 |
| `cli` | REPL 和脚本执行器（`./kjs foo.js`、`./kjs -e '…'`、`./kjs --trace …`） |
| `tests/unit` | JUnit 5 测试套件 — basics、ES2015+、VM↔Walker 对拍、Test262 fixtures |
| `tests/test262-runner` | 驱动上游 [tc39/test262](https://github.com/tc39/test262) 测试集 |
| `tests/bench` | JMH 性能基准 |

## 快速开始

```bash
# 构建
./gradlew build

# 跑测试（4 套 suite，合计 55 个用例）
./gradlew :tests:unit:test

# 启动 REPL
./kjs

# 跑脚本文件
./kjs demo.js

# 跑一行代码
./kjs -e "console.log([1,2,3].map(x => x*x))"

# 让引擎把自己讲一遍（tokens → AST → 字节码 → VM 每步）
./kjs --trace -e "console.log(1 + 2 * 19)"

# 切到 AST 解释器（对拍或排障时用）
KJS_BACKEND=walker ./kjs foo.js

# 跑上游 Test262 的某个子目录
git clone https://github.com/tc39/test262 third_party/test262
./gradlew :tests:test262-runner:run --args="third_party/test262 test/language/expressions/addition"

# JMH 基准
./gradlew :tests:bench:jmh
```

## 性能

测试环境：Apple Silicon MacBook + JDK 17。每段脚本预热后跑 5 次计时，下表是 5 次累计墙钟时间（毫秒）。

**纯算术热循环（JIT 触发）：**

| 基准 | 解释器 | **KJS JIT** | V8 (Node v24) | JIT 加速 |
|---|---:|---:|---:|---:|
| 累加 100 万次 | ~420 ms | **~35 ms** | ~3 ms | **12×** |
| 多项式 100 万次 | ~870 ms | **~22 ms** | ~6 ms | **40×** |
| 数 5000 内素数 | ~39 ms | **~7 ms** | ~0 ms | **5.5×** |
| 紧循环 100 万次 | ~480 ms | **~41 ms** | — | **12×** |

**混合场景（JIT 回落到解释器）：**

| 基准 | 解释器 | KJS VM |
|---|---:|---:|
| `fib(28)` × 5 | ~800 ms | ~430 ms |
| 属性密集热循环 | ~260 ms | ~200 ms |
| 字符串拼接 1 万次 | ~95 ms | ~70 ms |

### JIT 原理

函数被调用几次以后，KJS 会通过 ASM 把它的字节码翻译成 **JVM 字节码**——生成一个 `Compiled` 子类，其 `invoke` 方法直接运行在 JVM 原生操作数栈上。接着 HotSpot C2 会把这个类进一步编译成机器码，相当于**两级 JIT 接力**：KJS → JVM → native。

当前 JIT 策略保守——只 JIT 由算术、比较、局部变量、控制流组成的函数。一旦碰到函数调用、属性访问、闭包构造、异常处理，就回落解释器。这是为了稳妥推进；后续把这条线继续外推见 roadmap。

### JIT 开关

```bash
./kjs foo.js                        # 默认开启（阈值 3）
KJS_JIT=off ./kjs foo.js            # 关闭 JIT
KJS_JIT_THRESHOLD=10 ./kjs foo.js   # 调用 10 次后才编译
KJS_JIT_LOG=1 ./kjs foo.js          # 里程碑日志：编译、拒绝、首次调用、每 10000 次
KJS_JIT_LOG=trace ./kjs foo.js      # 详细日志：每次调用的倒计时与 JIT 计数
```

## 尚未实现 / 后续规划

- **NaN-boxing** — 把所有 JS 值塞进 64-bit `Long`，消除 JVM 装箱开销
- **Shape / 隐藏类 + 多态 IC** — 当前 IC 只做单态匹配（按 className）
- **JIT 覆盖扩大** — 支持含 CALL、属性访问、闭包的函数；加入 OSR 让正在跑的长循环也能被 JIT
- **ES 语言缺口** — 解构、`class`、rest/spread、generator、`async/await`、ES 模块

## 许可

MIT
