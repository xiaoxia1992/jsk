package io.kjs.lex

enum class TokenType {
    // Literals
    NUMBER, STRING, TEMPLATE_STRING, REGEX, IDENT,
    TRUE, FALSE, NULL, UNDEFINED,

    // Keywords
    VAR, LET, CONST, FUNCTION, RETURN,
    IF, ELSE, WHILE, DO, FOR, BREAK, CONTINUE,
    TRY, CATCH, FINALLY, THROW,
    NEW, DELETE, TYPEOF, INSTANCEOF, IN, VOID,
    THIS, CLASS, EXTENDS, SUPER,
    IMPORT, EXPORT, DEFAULT, OF,

    // Punctuation
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACK, RBRACK,
    COMMA, SEMI, COLON, DOT, ELLIPSIS, ARROW, QUESTION,

    // Operators
    ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, MUL_ASSIGN, DIV_ASSIGN, MOD_ASSIGN,
    AND_ASSIGN, OR_ASSIGN, XOR_ASSIGN, SHL_ASSIGN, SHR_ASSIGN, USHR_ASSIGN,
    PLUS, MINUS, STAR, SLASH, PERCENT, POW,
    INC, DEC,
    EQ, NEQ, SEQ, SNEQ, LT, LE, GT, GE,
    AND, OR, NOT, NULLISH,
    BITAND, BITOR, BITXOR, BITNOT, SHL, SHR, USHR,

    // Special
    EOF
}

data class Token(
    val type: TokenType,
    val value: String,
    val line: Int,
    val col: Int,
    val numberValue: Double = 0.0,
)

class LexError(msg: String, val line: Int, val col: Int) : RuntimeException("$msg @ $line:$col")

class Lexer(private val source: String) {
    private var pos = 0
    private var line = 1
    private var col = 1
    // Previous significant token type; used to disambiguate `/` as division vs regex start.
    private var prevType: TokenType? = null

    private val keywords = mapOf(
        "var" to TokenType.VAR, "let" to TokenType.LET, "const" to TokenType.CONST,
        "function" to TokenType.FUNCTION, "return" to TokenType.RETURN,
        "if" to TokenType.IF, "else" to TokenType.ELSE,
        "while" to TokenType.WHILE, "do" to TokenType.DO, "for" to TokenType.FOR,
        "break" to TokenType.BREAK, "continue" to TokenType.CONTINUE,
        "try" to TokenType.TRY, "catch" to TokenType.CATCH, "finally" to TokenType.FINALLY,
        "throw" to TokenType.THROW,
        "new" to TokenType.NEW, "delete" to TokenType.DELETE,
        "typeof" to TokenType.TYPEOF, "instanceof" to TokenType.INSTANCEOF,
        "in" to TokenType.IN, "void" to TokenType.VOID,
        "this" to TokenType.THIS, "class" to TokenType.CLASS,
        "extends" to TokenType.EXTENDS, "super" to TokenType.SUPER,
        "true" to TokenType.TRUE, "false" to TokenType.FALSE,
        "null" to TokenType.NULL, "undefined" to TokenType.UNDEFINED,
        "import" to TokenType.IMPORT, "export" to TokenType.EXPORT,
        "default" to TokenType.DEFAULT, "of" to TokenType.OF,
    )

    fun tokenize(): List<Token> {
        val out = mutableListOf<Token>()
        while (true) {
            val t = next()
            out.add(t)
            if (t.type == TokenType.EOF) break
        }
        return out
    }

    private fun peek(o: Int = 0): Char =
        if (pos + o < source.length) source[pos + o] else '\u0000'

    private fun advance(): Char {
        val c = source[pos++]
        if (c == '\n') { line++; col = 1 } else col++
        return c
    }

    private fun match(c: Char): Boolean {
        if (peek() == c) { advance(); return true }
        return false
    }

    private fun skipWhitespaceAndComments() {
        while (pos < source.length) {
            val c = peek()
            when {
                c == ' ' || c == '\t' || c == '\r' || c == '\n' -> advance()
                // Block comments are always unambiguous: `/*` can never start a regex (no regex begins with `*`).
                c == '/' && peek(1) == '*' -> {
                    advance(); advance()
                    while (pos < source.length && !(peek() == '*' && peek(1) == '/')) advance()
                    if (pos < source.length) { advance(); advance() }
                }
                // Line comments: `//` never forms a meaningful regex (empty pattern) so always strip.
                c == '/' && peek(1) == '/' -> while (pos < source.length && peek() != '\n') advance()
                else -> return
            }
        }
    }

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

    private fun setPrev(t: Token): Token {
        prevType = t.type
        return t
    }

    private fun number(l: Int, cc: Int): Token {
        val sb = StringBuilder()
        // hex
        if (peek() == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            sb.append(advance()); sb.append(advance())
            while (peek().isLetterOrDigit()) sb.append(advance())
            val v = java.lang.Long.parseLong(sb.substring(2), 16).toDouble()
            return setPrev(Token(TokenType.NUMBER, sb.toString(), l, cc, v))
        }
        while (peek().isDigit()) sb.append(advance())
        if (peek() == '.' && peek(1).isDigit()) {
            sb.append(advance())
            while (peek().isDigit()) sb.append(advance())
        } else if (peek() == '.') {
            sb.append(advance())
        }
        if (peek() == 'e' || peek() == 'E') {
            sb.append(advance())
            if (peek() == '+' || peek() == '-') sb.append(advance())
            while (peek().isDigit()) sb.append(advance())
        }
        val v = sb.toString().toDouble()
        return setPrev(Token(TokenType.NUMBER, sb.toString(), l, cc, v))
    }

    private fun string(quote: Char, l: Int, cc: Int): Token {
        advance() // opening
        val sb = StringBuilder()
        while (pos < source.length && peek() != quote) {
            val ch = advance()
            if (ch == '\\') {
                val esc = advance()
                sb.append(when (esc) {
                    'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
                    '\\' -> '\\'; '\'' -> '\''; '"' -> '"'; '`' -> '`'
                    '0' -> '\u0000'; 'b' -> '\b'; 'f' -> '\u000C'; 'v' -> '\u000B'
                    'x' -> {
                        val h = "" + advance() + advance()
                        h.toInt(16).toChar()
                    }
                    'u' -> {
                        if (peek() == '{') {
                            advance()
                            val hb = StringBuilder()
                            while (peek() != '}') hb.append(advance())
                            advance()
                            val cp = hb.toString().toInt(16)
                            sb.appendCodePoint(cp)
                            continue
                        } else {
                            val h = "" + advance() + advance() + advance() + advance()
                            h.toInt(16).toChar()
                        }
                    }
                    else -> esc
                })
            } else sb.append(ch)
        }
        if (pos >= source.length) throw LexError("Unterminated string", l, cc)
        advance() // closing
        return setPrev(Token(TokenType.STRING, sb.toString(), l, cc))
    }

    private fun templateString(l: Int, cc: Int): Token {
        // Keep the raw template body (without surrounding back-ticks); interpolation
        // segments like `${...}` are preserved as-is and later parsed by the Parser.
        advance() // opening `
        val sb = StringBuilder()
        var depth = 0
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
        advance() // closing `
        return setPrev(Token(TokenType.TEMPLATE_STRING, sb.toString(), l, cc))
    }

    private fun identOrKw(l: Int, cc: Int): Token {
        val sb = StringBuilder()
        while (peek().isLetterOrDigit() || peek() == '_' || peek() == '$') sb.append(advance())
        val s = sb.toString()
        val kw = keywords[s]
        return setPrev(Token(kw ?: TokenType.IDENT, s, l, cc))
    }

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

    private fun regex(l: Int, cc: Int): Token {
        // Consume the leading '/' (we got here with pos pointing at it).
        advance()
        val sb = StringBuilder("/")
        var inClass = false
        while (pos < source.length) {
            val ch = peek()
            if (ch == '\\') { sb.append(advance()); if (pos < source.length) sb.append(advance()); continue }
            if (ch == '[') inClass = true
            else if (ch == ']') inClass = false
            else if (ch == '/' && !inClass) break
            else if (ch == '\n') throw LexError("Unterminated regex", l, cc)
            sb.append(advance())
        }
        if (pos >= source.length) throw LexError("Unterminated regex", l, cc)
        sb.append(advance()) // closing /
        while (peek().isLetter()) sb.append(advance()) // flags
        return setPrev(Token(TokenType.REGEX, sb.toString(), l, cc))
    }

    private fun punct(l: Int, cc: Int): Token {
        val c = advance()
        return when (c) {
            '(' -> setPrev(Token(TokenType.LPAREN, "(", l, cc))
            ')' -> setPrev(Token(TokenType.RPAREN, ")", l, cc))
            '{' -> setPrev(Token(TokenType.LBRACE, "{", l, cc))
            '}' -> setPrev(Token(TokenType.RBRACE, "}", l, cc))
            '[' -> setPrev(Token(TokenType.LBRACK, "[", l, cc))
            ']' -> setPrev(Token(TokenType.RBRACK, "]", l, cc))
            ',' -> setPrev(Token(TokenType.COMMA, ",", l, cc))
            ';' -> setPrev(Token(TokenType.SEMI, ";", l, cc))
            ':' -> setPrev(Token(TokenType.COLON, ":", l, cc))
            '?' -> if (match('?')) setPrev(Token(TokenType.NULLISH, "??", l, cc)) else setPrev(Token(TokenType.QUESTION, "?", l, cc))
            '.' -> if (peek() == '.' && peek(1) == '.') { advance(); advance(); setPrev(Token(TokenType.ELLIPSIS, "...", l, cc)) } else setPrev(Token(TokenType.DOT, ".", l, cc))
            '+' -> when { match('=') -> setPrev(Token(TokenType.PLUS_ASSIGN, "+=", l, cc)); match('+') -> setPrev(Token(TokenType.INC, "++", l, cc)); else -> setPrev(Token(TokenType.PLUS, "+", l, cc)) }
            '-' -> when { match('=') -> setPrev(Token(TokenType.MINUS_ASSIGN, "-=", l, cc)); match('-') -> setPrev(Token(TokenType.DEC, "--", l, cc)); else -> setPrev(Token(TokenType.MINUS, "-", l, cc)) }
            '*' -> when { match('*') -> setPrev(Token(TokenType.POW, "**", l, cc)); match('=') -> setPrev(Token(TokenType.MUL_ASSIGN, "*=", l, cc)); else -> setPrev(Token(TokenType.STAR, "*", l, cc)) }
            '/' -> {
                if (canStartRegex()) {
                    // rewind so regex() sees '/'
                    pos--; col--
                    regex(l, cc)
                } else when {
                    match('=') -> setPrev(Token(TokenType.DIV_ASSIGN, "/=", l, cc))
                    else -> setPrev(Token(TokenType.SLASH, "/", l, cc))
                }
            }
            '%' -> if (match('=')) setPrev(Token(TokenType.MOD_ASSIGN, "%=", l, cc)) else setPrev(Token(TokenType.PERCENT, "%", l, cc))
            '=' -> when { match('=') -> if (match('=')) setPrev(Token(TokenType.SEQ, "===", l, cc)) else setPrev(Token(TokenType.EQ, "==", l, cc)); match('>') -> setPrev(Token(TokenType.ARROW, "=>", l, cc)); else -> setPrev(Token(TokenType.ASSIGN, "=", l, cc)) }
            '!' -> when { match('=') -> if (match('=')) setPrev(Token(TokenType.SNEQ, "!==", l, cc)) else setPrev(Token(TokenType.NEQ, "!=", l, cc)); else -> setPrev(Token(TokenType.NOT, "!", l, cc)) }
            '<' -> when { match('=') -> setPrev(Token(TokenType.LE, "<=", l, cc)); match('<') -> if (match('=')) setPrev(Token(TokenType.SHL_ASSIGN, "<<=", l, cc)) else setPrev(Token(TokenType.SHL, "<<", l, cc)); else -> setPrev(Token(TokenType.LT, "<", l, cc)) }
            '>' -> when {
                match('=') -> setPrev(Token(TokenType.GE, ">=", l, cc))
                match('>') -> when {
                    match('>') -> if (match('=')) setPrev(Token(TokenType.USHR_ASSIGN, ">>>=", l, cc)) else setPrev(Token(TokenType.USHR, ">>>", l, cc))
                    match('=') -> setPrev(Token(TokenType.SHR_ASSIGN, ">>=", l, cc))
                    else -> setPrev(Token(TokenType.SHR, ">>", l, cc))
                }
                else -> setPrev(Token(TokenType.GT, ">", l, cc))
            }
            '&' -> when { match('&') -> setPrev(Token(TokenType.AND, "&&", l, cc)); match('=') -> setPrev(Token(TokenType.AND_ASSIGN, "&=", l, cc)); else -> setPrev(Token(TokenType.BITAND, "&", l, cc)) }
            '|' -> when { match('|') -> setPrev(Token(TokenType.OR, "||", l, cc)); match('=') -> setPrev(Token(TokenType.OR_ASSIGN, "|=", l, cc)); else -> setPrev(Token(TokenType.BITOR, "|", l, cc)) }
            '^' -> if (match('=')) setPrev(Token(TokenType.XOR_ASSIGN, "^=", l, cc)) else setPrev(Token(TokenType.BITXOR, "^", l, cc))
            '~' -> setPrev(Token(TokenType.BITNOT, "~", l, cc))
            else -> throw LexError("Unexpected char '$c'", l, cc)
        }
    }
}
