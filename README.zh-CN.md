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

测试环境：Apple Silicon MacBook + JDK 17。每段脚本 2 次预热 + 5 次计时，表中是 5 次的累计墙钟时间（毫秒）。

| 基准 | Tree-walker | KJS VM | 加速比 |
|---|---:|---:|---:|
| `fib(28)` × 5 | ~800 ms | ~430 ms | **1.9×** |
| 紧循环 100 万次 | ~720 ms | ~460 ms | **1.6×** |
| 属性密集热循环 | ~260 ms | ~200 ms | **1.3×** |
| 字符串拼接 1 万次 | ~95 ms | ~70 ms | **1.4×** |

KJS 目前仍是解释器，现代 JIT 引擎（例如 V8）还是要再快 **30–100 倍**。拉近这个差距就是下面 roadmap 的主要目标。

## 尚未实现 / 后续规划

- **NaN-boxing** — 把所有 JS 值塞进 64-bit `Long`，消除 JVM 装箱开销
- **Shape / 隐藏类 + 多态 IC** — 当前 IC 只做单态匹配（按 className）
- **模板 JIT** — 把热字节码序列用 ASM 编译成 `MethodHandle` 链
- **ES 语言缺口** — 解构、`class`、rest/spread、generator、`async/await`、ES 模块

## 许可

MIT
