# KJS 引擎架构（M1）

```
         源代码
           │
         Lexer (手写)
           │  tokens
         Parser (递归下降)
           │  AST
         Interpreter  ──→ Realm (globals/intrinsics)
           │                │
         运行时值          Environment (lexical scope)
                            │
                         JsObject / JsArray / JsFunction
```

## 值表示（M1 简版）
| JS 类型 | 宿主表示 |
|---|---|
| number | `Double`（整型也存为 Double，向 ES 对齐） |
| string | `String` |
| boolean | `Boolean` |
| null | `null` |
| undefined | `Undefined` 单例 |
| object | `JsObject` |
| array | `JsArray`（`JsObject` 子类） |
| function | `JsFunction`（`JsObject` 子类，`callable == this`） |

> M2 将切到 NaN-boxed `Long` 表示，字节码 VM 直接操作 `LongArray` 栈，GC 压力更小。

## 作用域
`Environment` 是一条父指针链表；每个函数调用、`let/const` 块、`for` 头都会新建一层。变量查找是线性扫描；M2 用编译期 slot 分配替换掉 HashMap，IC 命中时 `O(1)`。

## 异常控制流
- `throw` → `JsThrown(value)`（RuntimeException）
- `break/continue/return` 用一次性 RuntimeException，`fillInStackTrace()` 返回 `this`，消除开销。
- `try/catch/finally` 严格按规范顺序交接控制。

## 原型链
- `Realm` 持有所有 intrinsic proto：`objectProto / arrayProto / functionProto / stringProto / numberProto / booleanProto / errorProto`
- 取属性时先查 own，再沿 `proto` 链上溯。

## Intrinsics 安装顺序
`Object → Function → Array → String → Number → Math → JSON → Errors → console → globals`。顺序有意义：`Function.prototype.call/apply/bind` 依赖 `Function` 本身存在。

## 代码导航
- `lex/Lexer.kt`：词法，含 regex 歧义消解
- `parse/Parser.kt`：递归下降
- `parse/Ast.kt`：节点
- `runtime/Interpreter.kt`：`exec/evalExpr` 两大主循环
- `runtime/Intrinsics.kt`：内置对象装配
- `runtime/Realm.kt`：引擎全局状态
- `Engine.kt`：对外 API

## 下一步（M2 前置）
1. 新增 `ir/Opcode.kt`：定义 60 条字节码
2. 新增 `ir/Compiler.kt`：AST → `Bytecode`（常量池 + 字节数组）
3. 新增 `vm/Interpreter.kt`：`while (true) switch(op)` 大循环
4. 把 `Engine.eval` 切到 VM，保留 tree-walker 作为 `--walker` 选项以便对拍
