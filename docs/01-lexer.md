# D1 · 词法分析器 Lexer

> 前置知识：D0（管线概览）。
>
> 本篇拆解 `lex/Lexer.kt`：Token 模型、`/` 除号/正则歧义、数字/字符串/模板的扫描要点，以及
> 标点歧义消解。读完能理解"字符流如何被正确切分成 Token 流"。

## 1. Token 模型

`TokenType`（`Lexer.kt:3`）把 JS 词法分为五类：字面量（`NUMBER/BIGINT/STRING/TEMPLATE_STRING/
REGEX/IDENT/TRUE/FALSE/NULL/UNDEFINED`）、关键字（`VAR/LET/.../CLASS/...`）、标点、运算符、
以及 `EOF`。

`Token`（`Lexer.kt:33`）是值类，带 `type`、`value`（原始文本）、`line/col`（用于报错定位），
外加 `numberValue: Double` 和 `bigIntValue: BigInteger?`——**数字在词法阶段就直接算成值**，
避免后续重复解析：

```kotlin
33:40:engine/src/main/kotlin/io/kjs/lex/Lexer.kt
data class Token(
    val type: TokenType,
    val value: String,
    val line: Int,
    val col: Int,
    val numberValue: Double = 0.0,
    val bigIntValue: java.math.BigInteger? = null,
)
```

## 2. 主循环：`next()` 的状态机

`Lexer`（`Lexer.kt:44`）持有 `pos/line/col`，以及一个**关键状态 `prevType`**（`Lexer.kt:49`）——
它记录上一个"有效 token"的类型，是后面消解 `/` 歧义的基础。`tokenize`（`Lexer.kt:70`）就是
不断 `next()` 直到 `EOF`：

```kotlin
112:125:engine/src/main/kotlin/io/kjs/lex/Lexer.kt
private fun next(): Token {
    skipWhitespaceAndComments()
    if (pos >= source.length) return setPrev(Token(TokenType.EOF, "", line, col))
    val startLine = line; val startCol = col
    val c = peek()
    return when {
        c.isDigit() || (c == '.' && peek(1).isDigit()) -> number(startLine, startCol)
        c == '"' || c == '\'' -> string(c, startLine, startCol)
        c == '`' -> templateString(startLine, startCol)
        c.isLetter() || c == '_' || c == '$' -> identOrKw(startLine, startCol)
        else -> punct(startLine, startCol)
    }
}
```

每个 `setPrev`（`Lexer.kt:127`）在返回 Token 前更新 `prevType`，使下次 `next` 能据此判断上下文。
`peek(o)`/`advance`/`match`（`Lexer.kt:80`）是底层光标操作：`advance` 推进 `pos` 并维护行列，
`match` 是"前瞻并消费"的便捷封装。

## 3. 核心难点：`/` 是除号还是正则？

JS 里 `/` 既可能是除法（`a / b`），也可能是正则字面量（`/foo/g`）。光看字符无法区分——
**必须知道"前一个 token"是否处于"表达式位置"**：

```kotlin
234:244:engine/src/main/kotlin/io/kjs/lex/Lexer.kt
private fun canStartRegex(): Boolean {
    val p = prevType ?: return true
    return when (p) {
        TokenType.IDENT, TokenType.NUMBER, TokenType.STRING,
        TokenType.TEMPLATE_STRING, TokenType.RPAREN, TokenType.RBRACK,
        TokenType.TRUE, TokenType.FALSE, TokenType.NULL, TokenType.UNDEFINED,
        TokenType.THIS, TokenType.INC, TokenType.DEC -> false
        else -> true
    }
}
```

原则：若上一个 token 是**值/标识符/右括号**（`a`、`3`、`)`、`]` …），说明 `/` 紧接在表达式后，
那它一定是**除号**；否则（如关键字后、左括号后、运算符后）则是**正则起始**。

在 `punct` 里据此分支（`Lexer.kt:283`）：

```kotlin
283:292:engine/src/main/kotlin/io/kjs/lex/Lexer.kt
'/' -> {
    if (canStartRegex()) {
        pos--; col--          // 回退一格，让 regex() 看到 '/'
        regex(l, cc)
    } else when {
        match('=') -> setPrev(Token(TokenType.DIV_ASSIGN, "/=", l, cc))
        else -> setPrev(Token(TokenType.SLASH, "/", l, cc))
    }
}
```

`regex`（`Lexer.kt:246`）随后扫描主体，注意用 `inClass` 标志区分字符类 `[...]` 内的 `/`（类内
的 `/` 不是结束符），遇到换行报错，结尾再吃 flags（`gi` 等）。

> 这是 JS 词法最经典、也最易错的歧义，KJS 用"上一个 token 类型"这一轻量状态干净地处理。

## 4. 各类字面量的扫描要点

### 4.1 数字（`Lexer.kt:132`）

- **十六进制**：`0x…`，跳过 `0x` 后吃 hex 字符；若尾随 `n` 则为 `BigInt`（`BigInteger`），否则
  转 `Long` 再变 `Double`。
- **小数/科学计数**：整数部分后若有 `.` 且后面是数字则吃小数部分；`e/E` 后可带正负号与指数。
- **BigInt 后缀**：仅整数字面量允许尾随 `n`（`sb.none { it == '.' || it == 'e' }`），转 `BigInteger`。
- 最终值直接存入 `Token.numberValue`（或 `bigIntValue`），Parser 无需再解析数字。

### 4.2 字符串（`Lexer.kt:168`）

处理转义：`\n \t \r \\ \" \' \` \0 \b \f \v`，十六进制 `\xHH`，以及 Unicode `\uHHHH` 与
`\u{...}`（变长码点，用 `appendCodePoint` 写入）。未闭合则抛 `LexError`。**字符串不在此求值**
（如 `${}` 仅在模板串里处理），原样存 `value`。

### 4.3 模板字符串（`Lexer.kt:206`）

反引号串整体作为一个 `TEMPLATE_STRING` 切出，**内部 `${...}` 保留原样**由 Parser 二次解析
（`depth` 计数嵌套）。这样词法阶段不被表达式语法污染，复杂度下沉到 Parser 的 `expandTemplate`。

### 4.4 标识符与关键字（`Lexer.kt:226`）

`identOrKw` 吃 `[A-Za-z0-9_$]`，再用 `keywords` 映射表（`Lexer.kt:51`）把保留字转成对应
`TokenType`，否则为 `IDENT`。映射表覆盖全部关键字（`var/let/const/function/if/.../class/of`）。

## 5. 标点的歧义消解（`punct`，`Lexer.kt:266`）

`punct` 用 `match` 前瞻把多字符运算符合并：

- `=`→`==`→`===`；`!`→`!=`→`!==`；`>`→`>>`→`>>>` 及其 `>=`/`>>=`/`>>>=`；
- `&`→`&&`/`&=`；`|`→`||`/`|=`；`/`→`/=`（当非正则时）；`*`→`**`/`*=`；`+`→`+=`/`++`；`-`→`-=`/`--`；
- `.`→`...`（展开）；`?`→`??`（空值合并）。

每生成 token 都 `setPrev` 以维护 `prevType` 状态链。未知字符抛 `LexError`。

## 6. 注释与空白（`Lexer.kt:94`）

`skipWhitespaceAndComments` 在每次 `next` 前调用，跳过空格/换行、块注释 `/* */`、行注释 `//`。
设计上 `/*` 与 `//` 永远不会是合法正则起始（空模式无意义），因此**无需 `prevType` 即可安全剥离**。

## 7. 设计取舍

- **状态极轻**：仅 `prevType` 一个回溯状态，却解决了 JS 最难的词法歧义（除号 vs 正则）。
- **数字即求值**：词法阶段直接算出 `Double/BigInteger` 值，Parser/Compiler 零重复解析。
- **模板串下沉到 Parser**：词法只负责"切出反引号整体"，`${}` 表达式交由 Parser 的 `expandTemplate`
  递归解析，职责清晰。
- **单一 `next()` 分发**：所有 token 类型在一个 `when` 里分流，新增 token 类型只改一处。

## 8. 常见坑

- **正则误判**：忘记更新 `prevType`，或在 `)`/`]` 后可开始正则处误判为除号 → 正则字面量被拆成
  除法 + 标识符。新增加值/位置 token 后需同步审视 `canStartRegex`。
- **模板内 `${}` 嵌套**：`depth` 计数必须配对，否则把插值表达式吞掉或提前结束。
- **多字符运算符顺序**：`>>>=` 这类需从最长匹配优先（`match('>')` 套三层），否则拆错。
- **未闭合字面量**：字符串/正则/模板未闭合应抛 `LexError` 并带行列，否则读到文件尾才崩，难定位。
