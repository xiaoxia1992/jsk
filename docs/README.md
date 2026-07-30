# KJS 引擎实现文档

> 一套面向**实现者**的 KJS 源码剖析文档，由浅入深，每篇都带可点击的源文件行号与 Mermaid 图。
> 目标不是"怎么用 KJS"，而是"KJS 内部怎么跑起来"。

## 执行管线总览

```
源码 String
  → Lexer.tokenize()        [D1 词法]
  → Parser.parseProgram()   [D2 语法/AST]
  → Compiler.compileProgram()  [D4 编译]
  → Bytecode                [D3 字节码]
  → Vm.run() / Interpreter.exec()   [D5 虚拟机] / [D9 双后端]
  → 结果 Any?
```

支撑机制：**运行时值模型（D6）** 是 VM 与 Walker 共享的地基；**内联缓存（D7）** 与
**模板 JIT（D8）** 是性能内核；**双后端对拍（D9）** 保证正确性。

## 文档导航

| 文件 | 主题 | 核心源文件 |
|---|---|---|
| [`00-overview.md`](00-overview.md) | 总览：定位、五段管线、模块地图、`Engine` 门面、如何运行 | `Engine.kt` |
| [`01-lexer.md`](01-lexer.md) | 词法：Token 模型、`/` 除号 vs 正则歧义、数字/字符串/模板 | `lex/Lexer.kt` |
| [`02-parser-ast.md`](02-parser-ast.md) | 语法：递归下降、优先级爬升、解构/箭头/`for-of` 解糖、AST 节点 | `parse/Parser.kt`、`Ast.kt` |
| [`03-bytecode.md`](03-bytecode.md) | 字节码：三条并行 IntArray 车道、常量池、跳转修补、反汇编 | `ir/Bytecode.kt`、`Opcode.kt` |
| [`04-compiler.md`](04-compiler.md) | 编译：作用域/槽位、Upvalue 闭包、提升、跳转修补、class 解糖 | `ir/Compiler.kt` |
| [`05-vm.md`](05-vm.md) | 虚拟机（核心）：Frame 模型、调用建退帧、参数/返回值/局部表、分发循环、闭包、IC、异常、迭代 | `vm/Vm.kt` |
| [`06-runtime-values.md`](06-runtime-values.md) | 值模型：`Any?` 装箱、`Undefined` 哨兵、原型链、`Environment`、类型强制 | `runtime/JsValue.kt` 等 |
| [`07-inline-cache.md`](07-inline-cache.md) | 内联缓存：单态 `PropIc`、多态/megamorphic、`GlobalIc` | `vm/PropIc.kt`、`GlobalIc.kt` |
| [`08-jit.md`](08-jit.md) | 模板 JIT：异步编译、类型特化消除装箱、栈类型追踪、ASM 生成 | `vm/Jit.kt`、`Compiled.kt`、`JitBridge.kt` |
| [`09-dual-backend.md`](09-dual-backend.md) | 双后端：树遍历 oracle、VM vs Walker 对拍测试 | `runtime/Interpreter.kt`、`tests/` |
| [`10-intrinsics-cli.md`](10-intrinsics-cli.md) | 内置函数与 CLI：`JsFunction.native`、Intrinsics/Ext、KjsNamespace、CLI/REPL | `runtime/Intrinsics*.kt`、`cli/` |

## 推荐阅读顺序

```
D0 总览 → D1 词法 → D2 语法 → D3 字节码 → D4 编译器 → D5 虚拟机
     → D6 值模型 → D7 内联缓存 → D8 模板 JIT → D9 双后端对拍 → D10 内置库与 CLI
```

前半段讲"源码如何变成可执行字节码"（D1–D4），D5 是执行核心，后半段讲支撑机制（值模型、IC、JIT）
与正确性保障（双后端）。每篇末尾都有"设计取舍"与"常见坑"。

## 配合 Tracer 学习

`Engine(trace = true)`（或 CLI 的 `kjs --trace -e '...'`）会把**词法 → 语法 → 编译 → VM 步进**
完整打印，是边读文档边看真实执行流的最佳方式。详见 [`00-overview.md`](00-overview.md) 与
[`10-intrinsics-cli.md`](10-intrinsics-cli.md)。
