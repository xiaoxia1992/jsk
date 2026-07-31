# D1 · 词法分析器 Lexer

词法分析器（Lexer）是 KJS 流水线的第一道工序：它把你写的一长串字符（例如 `var x = 1 + 2;`）切成一个个"带类型的小块"，也就是 Token。可以把它类比成中文分词——把"我爱编程"分成"我/爱/编程"——只不过 Lexer 还会给每个词贴上类型标签（是数字、是名字、还是加号？），并记住它出现在第几行第几列，以便出错时精确定位。切词这件事有多种做法：有的语言用一串正则表达式一次性切出来（写起来短，却很难处理像 `/` 这种"既是除号又是正则"的歧义）；有的用 flex 之类的"词法生成器"从规则自动生成切词代码。KJS 选择手写一个带光标的扫描器——代码长了些，但每一步逻辑都摊在眼前、便于调试。本章先列出全部 Token 类型，再重点拆解 `/` 歧义这个 JS 词法里最经典的坑（§7），最后在 §12 把设计选择和别的引擎做个对比。

> 前置知识：D0（管线概览）。
>
> 本篇拆解 `lex/Lexer.kt`：Token 模型、`/` 除号/正则歧义、数字/字符串/模板的扫描要点，以及
> 标点歧义消解。读完能理解"字符流如何被正确切分成 Token 流"，并能在改动词法器时预判会破坏什么。
>
> 词法分析（Lexing）是把"人写的一段字符序列"转换成"有类型的、带位置的符号序列"的第一道关卡。
> 它本身不做语法判断（不关心括号是否配对、语句是否合法），只负责：① 把连续字符切成最小有意义的
> 词（token）；② 给字面量预求值；③ 在 JS 特有的歧义处（最典型是 `/`）做"上下文敏感"的消歧。

## 1. Token 模型

Token 是切词得到的最小有意义单元，每个 Token 都带一个类型标签——`123` 是数字词、`x` 是标识符词、`+` 是运算符词。下面这张清单把 KJS 支持的全部词类型列全了，不必死记，当作字典随时查即可。

`TokenType`（`Lexer.kt:3`）把 JS 词法分为四类：字面量、关键字、标点、运算符，外加哨兵 `EOF`。
完整清单（注意 KJS 支持到 ES2015 实用子集）：

- **字面量**：`NUMBER`、`BIGINT`、`STRING`、`TEMPLATE_STRING`、`REGEX`、`IDENT`、
  `TRUE`、`FALSE`、`NULL`、`UNDEFINED`。
- **关键字**：`VAR/LET/CONST`、`FUNCTION/RETURN`、`IF/ELSE/WHILE/DO/FOR/BREAK/CONTINUE`、
  `TRY/CATCH/FINALLY/THROW`、`NEW/DELETE/TYPEOF/INSTANCEOF/IN/VOID`、`THIS`、
  `CLASS/EXTENDS/SUPER`、`IMPORT/EXPORT/DEFAULT/OF`。
- **标点**：`LPAREN/RPAREN/LBRACE/RBRACE/LBRACK/RBRACK`、`COMMA/SEMI/COLON/DOT/ELLIPSIS/
  ARROW/QUESTION`。
- **运算符**：赋值族（`ASSIGN/PLUS_ASSIGN/.../USHR_ASSIGN`）、算术（`PLUS/MINUS/STAR/SLASH/
  PERCENT/POW/INC/DEC`）、比较（`EQ/NEQ/SEQ/SNEQ/LT/LE/GT/GE`）、逻辑（`AND/OR/NOT/NULLISH`）、
  位运算（`BITAND/BITOR/BITXOR/BITNOT/SHL/SHR/USHR`）。
- **特殊**：`EOF`（哨兵，表示流结束）。

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

`numberValue`/`bigIntValue` 的设计意图：词法器是唯一需要把 `"123"` 解析成数字的地方；Parser、
Compiler 直接 `token.numberValue` 即可，省去每层重复 `toDouble()`。`LexError`（`Lexer.kt:42`）
继承 `RuntimeException`，携带 `line/col`，让"未闭合字符串""非法字符"等错误能精确报告位置。

## 2. 词法器架构：光标与状态

`Lexer`（`Lexer.kt:44`）的核心是一只"光标"加一个轻量回溯状态：

- `pos/line/col`：当前字符位置与行列号。
- 关键状态 `prevType`（`Lexer.kt:49`）：记录上一个"有效 token"的类型，是后面消解 `/` 歧义的基础。
- `keywords` 映射表（`Lexer.kt:51`）：`String → TokenType`，O(1) 判定保留字。

`tokenize`（`Lexer.kt:70`）就是不断 `next()` 直到 `EOF`：

```kotlin
70:78:engine/src/main/kotlin/io/kjs/lex/Lexer.kt
fun tokenize(): List<Token> {
    val out = mutableListOf<Token>()
    while (true) {
        val t = next()
        out.add(t)
        if (t.type == TokenType.EOF) break
    }
    return out
}
```

### 2.1 底层光标操作

`peek(o)`（`Lexer.kt:80`）零开销前瞻第 `o` 个字符（越界返回 `'\u0000'`）；`advance`（`Lexer.kt:83`）
消费当前字符并维护行列（遇 `\n` 行号+1、列归 1）；`match(c)`（`Lexer.kt:89`）是"前瞻并消费"的
便捷封装，多字符运算符合并时大量使用。

### 2.2 `next()` 的单点分发

`next`（`Lexer.kt:112`）每次先 `skipWhitespaceAndComments`，再按首字符分派到对应扫描函数：

```kotlin
112:125:engine/src/main/kotlin/io/kjs/lex/Lexer.kt
private fun next(): Token {
    skipWhitespaceAndComments()
    if (pos >= source.length) return setPrev(Token(TokenType.EOF, "", line, col))
    val startLine = line
    val startCol = col
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
**所有 token 类型在一个 `when` 里分流**，新增 token 类型只改这一处，符合"开放封闭"的最小改动面。

## 3. 数字扫描（`Lexer.kt:132`）

数字扫描需处理四种子形态：十六进制、十进制（含小数）、科学计数、BigInt 后缀。算法要点：

```kotlin
132:166:engine/src/main/kotlin/io/kjs/lex/Lexer.kt   (节选)
private fun number(l: Int, cc: Int): Token {
    val sb = StringBuilder()
    // ① 十六进制
    if (peek() == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
        sb.append(advance()); sb.append(advance())
        while (peek().let { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) sb.append(advance())
        if (peek() == 'n') { /* → BigInteger(hex) */ return BIGINT }
        return NUMBER(toDouble(parseLong(hex, 16)))
    }
    // ② 整数部分
    while (peek().isDigit()) sb.append(advance())
    // ③ 小数（仅当后面仍是数字，避免把 `1.` 当小数吃掉点号）
    if (peek() == '.' && peek(1).isDigit()) { sb.append(advance()); while (peek().isDigit()) sb.append(advance()) }
    else if (peek() == '.') sb.append(advance())   // 孤立的 '.'，留给上层当 DOT
    // ④ 科学计数
    if (peek() == 'e' || peek() == 'E') { sb.append(advance()); if (peek()=='+'||peek()=='-') sb.append(advance()); while (peek().isDigit()) sb.append(advance()) }
    // ⑤ BigInt 后缀：仅整数字面量允许尾随 n（sb 里无 '.' 与 'e'）
    if (peek() == 'n' && sb.none { it == '.' || it == 'e' || it == 'E' }) { /* → BigInteger */ return BIGINT }
    return NUMBER(sb.toString().toDouble())
}
```

设计细节与真实限制：

- **十六进制**：吞掉 `0x` 后吃 hex 字符；若尾随 `n` 走 `BigInt`（`BigInteger(radix=16)`），否则
  `Long.parseLong(hex,16).toDouble()`。注意 KJS **不支持 `0o`（八进制）与 `0b`（二进制）前缀**，
  这是 M1 简化。
- **小数边界**：`1.5` 的小数点后面必须是数字才吃；`1.` 这种孤立点号只吞点、不当小数，把 `.` 留给
  上层当 `DOT`（这是 JS `1..toString()` 能成立的原因，也与 ECMA 文法一致）。
- **科学计数**：`e/E` 后可带正负号与数字指数，`1e3`/`2.5E-2` 都覆盖。
- **BigInt 后缀**：只有**整数字面量**允许尾随 `n`（`sb.none { '.' / 'e' }`），`1.0n` 非法。
- 最终值直接存入 `Token.numberValue`（或 `bigIntValue`），Parser/Compiler 零重复解析。

## 4. 字符串扫描（`Lexer.kt:168`）

字符串扫描吃掉引号后逐字符处理转义，未闭合则抛 `LexError`：

```kotlin
168:204:engine/src/main/kotlin/io/kjs/lex/Lexer.kt   (节选)
private fun string(quote: Char, l: Int, cc: Int): Token {
    advance() // 吃开头引号
    val sb = StringBuilder()
    while (pos < source.length && peek() != quote) {
        val ch = advance()
        if (ch == '\\') {
            val esc = advance()
            sb.append(when (esc) {
                'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
                '\\' -> '\\'; '\'' -> '\''; '"' -> '"'; '`' -> '`'
                '0' -> '\u0000'; 'b' -> '\b'; 'f' -> '\u000C'; 'v' -> '\u000B'
                'x' -> ("" + advance() + advance()).toInt(16).toChar()        // \xHH
                'u' -> if (peek() == '{') {                                    // \u{...} 变长码点
                    advance(); val cp = buildString { while (peek() != '}') append(advance()) }.toInt(16)
                    sb.appendCodePoint(cp); advance(); continue
                } else { ("" + advance()+advance()+advance()+advance()).toInt(16).toChar() }  // \uHHHH
                else -> esc
            })
        } else sb.append(ch)
    }
    if (pos >= source.length) throw LexError("Unterminated string", l, cc)
    advance() // 吃结尾引号
    return setPrev(Token(TokenType.STRING, sb.toString(), l, cc))
}
```

转义处理要点：`\xHH`（两位十六进制）、`\uHHHH`（四位）、`\u{...}`（变长码点，用
`appendCodePoint` 写 UTF-32）、`\0` 写成 NUL。**字符串不在此求值**（`${}` 仅在模板串里处理），
原样存 `value`。`continue` 在 `u{...}` 分支里用于跳过后手写的 `advance()`，避免多吞一个字符。

## 5. 模板字符串（`Lexer.kt:206`）

反引号串整体作为一个 `TEMPLATE_STRING` 切出，**内部 `${...}` 保留原样**由 Parser 二次解析：

```kotlin
206:224:engine/src/main/kotlin/io/kjs/lex/Lexer.kt   (节选)
private fun templateString(l: Int, cc: Int): Token {
    advance() // 吃开头 `
    val sb = StringBuilder()
    var depth = 0                      // 插值表达式嵌套深度
    while (pos < source.length) {
        val ch = peek()
        if (depth == 0 && ch == '`') break
        if (ch == '\\' && pos + 1 < source.length) { sb.append(advance()); sb.append(advance()); continue }
        if (ch == '$' && peek(1) == '{') { depth++; sb.append(advance()); sb.append(advance()); continue }
        if (depth > 0 && ch == '}') { depth--; sb.append(advance()); continue }
        if (depth > 0 && ch == '{') { depth++; sb.append(advance()); continue }
        sb.append(advance())
    }
    if (pos >= source.length) throw LexError("Unterminated template", l, cc)
    advance() // 吃结尾 `
    return setPrev(Token(TokenType.TEMPLATE_STRING, sb.toString(), l, cc))
}
```

`depth` 计数保证 `${ a ? ${b} : c }` 这类嵌套插值不会被提前的 `}` 截断。词法阶段只负责"切出反引号
整体"，复杂度下沉到 Parser 的 `expandTemplate`，职责清晰、不污染词法（D2）。

## 6. 标识符与关键字（`Lexer.kt:226`）

`identOrKw` 吃 `[A-Za-z0-9_$]`，再用 `keywords` 映射表把保留字转成对应 `TokenType`，否则为
`IDENT`：

```kotlin
226:232:engine/src/main/kotlin/io/kjs/lex/Lexer.kt
private fun identOrKw(l: Int, cc: Int): Token {
    val sb = StringBuilder()
    while (peek().isLetterOrDigit() || peek() == '_' || peek() == '$') sb.append(advance())
    val s = sb.toString()
    val kw = keywords[s]
    return setPrev(Token(kw ?: TokenType.IDENT, s, l, cc))
}
```

**真实限制**：KJS 的标识符字符集是 ASCII 的 `[A-Za-z0-9_$]`，**不支持 Unicode 标识符**（如中文变量名
`const 数 = 1` 会失败）。这是 M1 简化，符合 ES5.1 的 ASCII 子集取向；若要支持 ES2015 的全 Unicode
标识符，需放宽 `isLetterOrDigit` 判定（并相应扩展关键字表）。

## 7. 核心难点：`/` 是除号还是正则？

在 JS 里，`/` 既可能是除法 `a/b`，也可能是正则表达式 `/foo/g`，单看字符根本分不清，必须看它前面紧跟的是什么。KJS 用了一个极轻量的办法：记住"上一个词的类型"来判断。这是 JS 词法里最经典、也最容易写错的坑。下面用一张判定表，把"每类词之后 `/` 该当除号还是正则"讲清楚。顺便提一句对比：有些引擎干脆让语法分析器也参与切词（词法语法不分家），这样边界会变模糊；KJS 坚持把这件事关在 Lexer 内部、用一个状态解决，职责更清晰。

JS 里 `/` 既可能是除法（`a / b`），也可能是正则字面量（`/foo/g`）。光看字符无法区分——**必须知道
"前一个 token"是否处于"表达式位置"**：

```kotlin
234:244:engine/src/main/kotlin/io/kjs/lex/Lexer.kt
/** Regex starts only in expression context. */
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

判定表的语义（为何每类如此）：

| 前一词法类型 | 处于表达式位置？ | `/` 含义 | 理由 |
|---|---|---|---|
| `IDENT` / `NUMBER` / `STRING` / `TEMPLATE_STRING` | 是 | 除号 | 这些值后面接 `/` 必然是二元除法 |
| `RPAREN` / `RBRACK` | 是 | 除号 | `)`/`]` 闭包后通常是表达式末尾，如 `(a+b)/c`、`arr[0]/2` |
| `TRUE`/`FALSE`/`NULL`/`UNDEFINED`/`THIS` | 是 | 除号 | 字面量/关键字值后面接 `/` 是除法 |
| `INC`/`DEC` | 是 | 除号 | 后缀 `i++ / 2` 中 `/` 在前置表达式后 |
| 其余（关键字、运算符、`LPAREN`、`LBRACE`、标点…） | 否 | 正则 | 如 `if(x)/re/.test(s)`、`return /a/`、`( /re/ )` |

`prevType == null`（流开头）默认 `true`，即允许正则起始（如脚本首行 `=/re/.exec(s)`）。

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

`regex`（`Lexer.kt:246`）扫描主体，用 `inClass` 标志区分字符类 `[...]` 内的 `/`（类内 `/` 不是结束符），
遇换行报 `LexError("Unterminated regex")`，结尾再吃 flags（`gi` 等）。`pos--; col--` 回退是为了让
`regex()` 重新从 `/` 开始扫描——这是"单字符决策"后让出扫描权的标准手法。

> 这是 JS 词法最经典、也最易错的歧义：KJS 用"上一个 token 类型"这一**轻量状态**干净处理，无需
> 把词法器升级成完整语法感知（那会模糊词法/语法边界）。

## 8. 标点与多字符运算符（`punct`，`Lexer.kt:266`）

`punct` 用 `match` 前瞻做**最长匹配**把多字符运算符合并：

```kotlin
294:295:engine/src/main/kotlin/io/kjs/lex/Lexer.kt   (节录)
'=' -> when { match('=') -> if (match('=')) SEQ("===") else EQ("=="); match('>') -> ARROW("=>"); else ASSIGN("=") }
'!' -> when { match('=') -> if (match('=')) SNEQ("!==") else NEQ("!="); else NOT("!") }
'<' -> when { match('=') -> LE("<="); match('<') -> if (match('=')) SHL_ASSIGN("<<=") else SHL("<<"); else LT("<") }
'>' -> when {
    match('=') -> GE(">=")
    match('>') -> when { match('>') -> if (match('=')) USHR_ASSIGN(">>>=") else USHR(">>>"); match('=') -> SHR_ASSIGN(">>="); else SHR(">>") }
    else -> GT(">")
}
```

全部多字符运算符的合并规则：

| 首字符 | 合并形态 | 备注 |
|---|---|---|
| `=` | `=` → `==` → `===`；`=>`（`ARROW`） | `===`/`!==` 走"最长优先"三层 `match` |
| `!` | `!=` → `!==`；`!` | |
| `>` | `>=`；`>>`→`>>=`/`>>>`→`>>>=` | 右移族必须 `match('>')` 套三层 |
| `<` | `<=`；`<<`→`<<=` | |
| `&`/`|` | `&&`/`||`；`&=`/`|=` | |
| `*` | `**`（`POW`）/`*=`；`/`→`/=`（非正则时）；`+`→`+=`/`++`；`-`→`-=`/`--` | |
| `.` | `...`（`ELLIPSIS`）；`.` | `?.` 未实现（M1 简化） |
| `?` | `??`（`NULLISH`）；`?` | |

每生成 token 都 `setPrev` 以维护 `prevType` 状态链。未知字符抛 `LexError("Unexpected char")`。

## 9. 注释与空白（`Lexer.kt:94`）

`skipWhitespaceAndComments` 在每次 `next` 前调用，跳过空格/换行、块注释 `/* */`、行注释 `//`：

```kotlin
94:110:engine/src/main/kotlin/io/kjs/lex/Lexer.kt
private fun skipWhitespaceAndComments() {
    while (pos < source.length) {
        val c = peek()
        when {
            c == ' ' || c == '\t' || c == '\r' || c == '\n' -> advance()
            c == '/' && peek(1) == '*' -> { advance(); advance(); while (!(peek()=='*' && peek(1)=='/')) advance(); if (pos < source.length){advance();advance()} }
            c == '/' && peek(1) == '/' -> while (peek() != '\n') advance()
            else -> return
        }
    }
}
```

设计上 `/*` 与 `//` 永远不会是合法正则起始（空模式/以 `*` 开头无意义），因此**无需 `prevType` 即可
安全剥离**。块注释嵌套不被支持（首个 `*/` 即结束），符合 C 风格注释惯例。

## 10. 错误处理：`LexError`

词法层三类硬错误：`Unterminated string`、`Unterminated template`、`Unterminated regex`、`Unexpected char`。
统一抛 `LexError(msg, line, col)`，异常携带位置；`Engine` 层（或 REPL）捕获后格式化展示，不会把
错误闷在文件末尾才崩。这是"错误必须可定位"的工程底线——`line/col` 从 `next()` 的 `startLine/
startCol` 透传到 Token 再透传到异常。

## 11. 一个完整的 tokenize 追踪：看 `/` 如何在同一行里既是除号又是正则

为了把上面的规则落到实处，这一节带你单步跟踪一段真实输入 `a / b; /re/g.test(x)`：你会发现同一个字符 `/`，在第 3 步被切成除号，到了第 8 步却被切成正则——仅仅因为前面的词不同。跟着表格走一遍，§7 的判定规则就不再是纸面规则，而是真正活起来了。

对输入 `a / b; /re/g.test(x)`，逐步跟踪 `prevType` 与产出：

| 步骤 | 首字符 | `prevType`（决策前） | 决策 | 产出 Token |
|---|---|---|---|---|
| 1 | `a` | `null` | 标识符 | `IDENT("a")` → prevType=`IDENT` |
| 2 | 空格 | `IDENT` | 跳过 | — |
| 3 | `/` | `IDENT` | `canStartRegex`=`false`（表达式位置）→ 除号 | `SLASH("/")` → prevType=`SLASH` |
| 4 | 空格 | `SLASH` | 跳过 | — |
| 5 | `b` | `SLASH` | 标识符 | `IDENT("b")` → prevType=`IDENT` |
| 6 | `;` | `IDENT` | 标点 | `SEMI(";")` → prevType=`SEMI` |
| 7 | 空格 | `SEMI` | 跳过 | — |
| 8 | `/` | `SEMI` | `canStartRegex`=`true`（非表达式位置）→ 正则 | `REGEX("/re/g")` → prevType=`REGEX` |

关键点：**第 3 步与第 8 步是同一个字符 `/`**，仅因前一个 token 类型不同，分别被切成了 `SLASH` 与
`REGEX`。这正是 `prevType` 状态机的价值——它把"文法层面无法用纯正则描述的 JS 词法歧义"用 O(1)
状态判明，且不污染语法分析器。

## 12. 设计取舍

这一节回过头来总结设计取舍，并和别的做法对照。两条主线值得留意：一是 KJS 在词法阶段就把数字直接算成值（避免后面重复计算）；二是像模板串里的 `${...}` 这种复杂结构，直接丢给后面的语法分析器去处理——各司其职。如果想看真实报错是怎么定位到行列号的，可以跳到 §10 的 `LexError`。

- **状态极轻**：仅 `prevType` 一个回溯状态，却解决了 JS 最难的词法歧义（除号 vs 正则）；`pos/line/
  col` 三个位置变量即完整描述"光标"，无需回溯整个输入。
- **数字即求值**：词法阶段直接算出 `Double/BigInteger` 值，Parser/Compiler 零重复解析，且把"数字
  合法性"错误提前到词法期（如非法 `0x` 字符）。
- **模板串下沉到 Parser**：词法只负责"切出反引号整体"，`${}` 表达式交由 Parser 的 `expandTemplate`
  递归解析，词法器不被表达式语法污染。
- **单一 `next()` 分发**：所有 token 类型在一个 `when` 里分流，新增 token 类型只改一处；多字符
  运算符用 `match` 统一做最长匹配。
- **`prevType` 而非完整语法栈**：用"前一个 token 类型"近似"表达式位置"，足够消解 `/` 歧义，且
  保持词法器对语法的无知（职责边界清晰）。

## 13. 常见坑

- **正则误判**：忘记更新 `prevType`，或在 `)`/`]` 后可开始正则处误判为除号 → 正则字面量被拆成
  除法 + 标识符。新增值/位置 token 后需同步审视 `canStartRegex` 的 `false` 列表。
- **模板内 `${}` 嵌套**：`depth` 计数必须配对，否则把插值表达式吞掉或提前结束；`${a ? `x${b}` : c}`
  这类混合嵌套尤其易错。
- **多字符运算符顺序**：`>>>=` 这类需从最长匹配优先（`match('>')` 套三层），否则拆成 `>>=` + `>`
  或其它错误序列。
- **未闭合字面量**：字符串/正则/模板未闭合应抛 `LexError` 并带行列，否则读到文件尾才崩，难定位。
- **ASCII 标识符限制**：引入非 ASCII 变量名或 Unicode 转义 `\u` 标识符会失败；扩展时需同步放宽
  `identOrKw` 的字符判定与关键字表。
- **数字前缀限制**：`0o17`/`0b101` 八进制/二进制字面量不被支持，会解析成 `0` + 标识符，需显式支持
  才能在词法层拦截。
