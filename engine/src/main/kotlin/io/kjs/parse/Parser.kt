package io.kjs.parse

import io.kjs.lex.Lexer
import io.kjs.lex.Token
import io.kjs.lex.TokenType as T

class ParseError(msg: String, val line: Int, val col: Int) : RuntimeException("$msg @ $line:$col")

/**
 * Recursive-descent parser producing the [Program] AST.
 * Supports a pragmatic ES5.1 subset (+ arrow fns, let/const, templates as plain strings).
 */
class Parser(source: String) {
    private val tokens: List<Token> = Lexer(source).tokenize()
    private var pos = 0

    fun parseProgram(): Program {
        val stmts = mutableListOf<Stmt>()
        while (!at(T.EOF)) stmts.add(statement())
        return Program(stmts).also { it.line = 1 }
    }

    // --- utilities ---
    private fun peek(o: Int = 0): Token = tokens[(pos + o).coerceAtMost(tokens.size - 1)]
    private fun at(t: T): Boolean = peek().type == t
    private fun at(vararg ts: T): Boolean = ts.contains(peek().type)
    private fun eat(): Token = tokens[pos++]
    private fun eat(t: T): Token {
        if (!at(t)) {
            val p = peek()
            throw ParseError("Expected $t, got ${p.type} ('${p.value}')", p.line, p.col)
        }
        return eat()
    }
    private fun match(t: T): Boolean = if (at(t)) { eat(); true } else false
    private fun <N : Node> N.pos(tok: Token): N { this.line = tok.line; this.col = tok.col; return this }

    /**
     * After a `.`, ES allows reserved words and contextual keywords to be used as member names
     * (e.g. `Array.of`, `Symbol.for`, `foo.class`). Accept any token that parses as a "word".
     */
    private fun memberNameOrKeyword(): String {
        val p = peek()
        return when (p.type) {
            T.IDENT, T.VAR, T.LET, T.CONST, T.FUNCTION, T.RETURN,
            T.IF, T.ELSE, T.WHILE, T.DO, T.FOR, T.BREAK, T.CONTINUE,
            T.TRY, T.CATCH, T.FINALLY, T.THROW,
            T.NEW, T.DELETE, T.TYPEOF, T.INSTANCEOF, T.IN, T.VOID,
            T.THIS, T.CLASS, T.EXTENDS, T.SUPER,
            T.TRUE, T.FALSE, T.NULL, T.UNDEFINED,
            T.IMPORT, T.EXPORT, T.DEFAULT, T.OF -> { eat().value }
            else -> eat(T.IDENT).value
        }
    }

    // --- statements ---
    private fun statement(): Stmt {
        val tok = peek()
        // labeled statement: IDENT ":" stmt
        if (tok.type == T.IDENT && peek(1).type == T.COLON) {
            val name = eat().value; eat(T.COLON)
            return Labeled(name, statement()).pos(tok)
        }
        return when (tok.type) {
            T.LBRACE -> block()
            T.VAR, T.LET, T.CONST -> varDecl().also { match(T.SEMI) }
            T.IF -> ifStmt()
            T.WHILE -> whileStmt()
            T.DO -> doWhileStmt()
            T.FOR -> forStmt()
            T.RETURN -> { eat(); val e = if (at(T.SEMI, T.RBRACE, T.EOF)) null else expression(); match(T.SEMI); Return(e).pos(tok) }
            T.BREAK -> { eat(); val l = if (at(T.IDENT)) eat().value else null; match(T.SEMI); Break(l).pos(tok) }
            T.CONTINUE -> { eat(); val l = if (at(T.IDENT)) eat().value else null; match(T.SEMI); Continue(l).pos(tok) }
            T.THROW -> { eat(); val e = expression(); match(T.SEMI); Throw(e).pos(tok) }
            T.TRY -> tryStmt()
            T.FUNCTION -> funcDecl()
            T.SEMI -> { eat(); EmptyStmt().pos(tok) }
            else -> { val e = expression(); match(T.SEMI); ExprStmt(e).pos(tok) }
        }
    }

    private fun block(): Block {
        val tok = eat(T.LBRACE)
        val stmts = mutableListOf<Stmt>()
        while (!at(T.RBRACE) && !at(T.EOF)) stmts.add(statement())
        eat(T.RBRACE)
        return Block(stmts).pos(tok)
    }

    private fun varDecl(): VarDecl {
        val tok = eat() // var/let/const
        val kind = tok.value
        val list = mutableListOf<Declarator>()
        do {
            val n = eat(T.IDENT).value
            val init = if (match(T.ASSIGN)) assignment() else null
            list.add(Declarator(n, init).pos(tok))
        } while (match(T.COMMA))
        return VarDecl(kind, list).pos(tok)
    }

    private fun ifStmt(): Stmt {
        val tok = eat(T.IF); eat(T.LPAREN); val c = expression(); eat(T.RPAREN)
        val cons = statement()
        val alt = if (match(T.ELSE)) statement() else null
        return If(c, cons, alt).pos(tok)
    }

    private fun whileStmt(): Stmt {
        val tok = eat(T.WHILE); eat(T.LPAREN); val c = expression(); eat(T.RPAREN)
        return While(c, statement()).pos(tok)
    }

    private fun doWhileStmt(): Stmt {
        val tok = eat(T.DO); val body = statement()
        eat(T.WHILE); eat(T.LPAREN); val c = expression(); eat(T.RPAREN); match(T.SEMI)
        return DoWhile(body, c).pos(tok)
    }

    private fun forStmt(): Stmt {
        val tok = eat(T.FOR); eat(T.LPAREN)
        // init
        val init: Node? = when {
            at(T.SEMI) -> null
            at(T.VAR, T.LET, T.CONST) -> varDecl()
            else -> ExprStmt(expression()).pos(peek())
        }
        // for-in / for-of detection
        if (init is VarDecl && init.declarators.size == 1 && init.declarators[0].init == null && (at(T.IN) || at(T.OF))) {
            val isOf = at(T.OF); eat()
            val right = expression(); eat(T.RPAREN)
            val body = statement()
            val leftName = Ident(init.declarators[0].name).pos(tok)
            return if (isOf) ForOf(init.kind, leftName, right, body).pos(tok)
                   else ForIn(init.kind, leftName, right, body).pos(tok)
        }
        if (init is ExprStmt && (at(T.IN) || at(T.OF))) {
            val isOf = at(T.OF); eat()
            val right = expression(); eat(T.RPAREN)
            val body = statement()
            return if (isOf) ForOf(null, init.expr, right, body).pos(tok)
                   else ForIn(null, init.expr, right, body).pos(tok)
        }
        eat(T.SEMI)
        val test = if (at(T.SEMI)) null else expression()
        eat(T.SEMI)
        val update = if (at(T.RPAREN)) null else expression()
        eat(T.RPAREN)
        val body = statement()
        return ForC(init, test, update, body).pos(tok)
    }

    private fun tryStmt(): Stmt {
        val tok = eat(T.TRY); val b = block()
        var param: String? = null; var cb: Block? = null
        if (match(T.CATCH)) {
            if (match(T.LPAREN)) { param = eat(T.IDENT).value; eat(T.RPAREN) }
            cb = block()
        }
        val fb = if (match(T.FINALLY)) block() else null
        return Try(b, param, cb, fb).pos(tok)
    }

    private fun funcDecl(): Stmt {
        val tok = eat(T.FUNCTION)
        val name = eat(T.IDENT).value
        val params = paramList()
        val body = block()
        return FunctionDecl(name, params, body).pos(tok)
    }

    private fun paramList(): List<String> {
        eat(T.LPAREN)
        val ps = mutableListOf<String>()
        if (!at(T.RPAREN)) {
            do { ps.add(eat(T.IDENT).value) } while (match(T.COMMA))
        }
        eat(T.RPAREN)
        return ps
    }

    // --- expressions (Pratt-ish, classic precedence climbing) ---
    private fun expression(): Expr {
        val first = assignment()
        if (!at(T.COMMA)) return first
        val list = mutableListOf(first)
        while (match(T.COMMA)) list.add(assignment())
        return Sequence(list).pos(tokens[pos - 1])
    }

    private fun assignment(): Expr {
        val left = conditional()
        val tok = peek()
        val op = when (tok.type) {
            T.ASSIGN -> "="
            T.PLUS_ASSIGN -> "+="
            T.MINUS_ASSIGN -> "-="
            T.MUL_ASSIGN -> "*="
            T.DIV_ASSIGN -> "/="
            T.MOD_ASSIGN -> "%="
            T.AND_ASSIGN -> "&="
            T.OR_ASSIGN -> "|="
            T.XOR_ASSIGN -> "^="
            T.SHL_ASSIGN -> "<<="
            T.SHR_ASSIGN -> ">>="
            T.USHR_ASSIGN -> ">>>="
            else -> return left
        }
        eat()
        val right = assignment()
        return Assign(op, left, right).pos(tok)
    }

    private fun conditional(): Expr {
        val c = logicalOr()
        if (!at(T.QUESTION)) return c
        val tok = eat()
        val cons = assignment()
        eat(T.COLON)
        val alt = assignment()
        return Conditional(c, cons, alt).pos(tok)
    }

    private fun logicalOr(): Expr {
        var left = logicalAnd()
        while (at(T.OR) || at(T.NULLISH)) {
            val t = eat(); val r = logicalAnd()
            left = Logical(t.value, left, r).pos(t)
        }
        return left
    }

    private fun logicalAnd(): Expr {
        var left = bitOr()
        while (at(T.AND)) { val t = eat(); val r = bitOr(); left = Logical("&&", left, r).pos(t) }
        return left
    }

    private fun bitOr(): Expr { var l = bitXor(); while (at(T.BITOR)) { val t = eat(); val r = bitXor(); l = Binary("|", l, r).pos(t) }; return l }
    private fun bitXor(): Expr { var l = bitAnd(); while (at(T.BITXOR)) { val t = eat(); val r = bitAnd(); l = Binary("^", l, r).pos(t) }; return l }
    private fun bitAnd(): Expr { var l = equality(); while (at(T.BITAND)) { val t = eat(); val r = equality(); l = Binary("&", l, r).pos(t) }; return l }

    private fun equality(): Expr {
        var l = relational()
        while (at(T.EQ, T.NEQ, T.SEQ, T.SNEQ)) { val t = eat(); val r = relational(); l = Binary(t.value, l, r).pos(t) }
        return l
    }

    private fun relational(): Expr {
        var l = shift()
        while (at(T.LT, T.LE, T.GT, T.GE, T.INSTANCEOF, T.IN)) { val t = eat(); val r = shift(); l = Binary(t.value, l, r).pos(t) }
        return l
    }

    private fun shift(): Expr {
        var l = additive()
        while (at(T.SHL, T.SHR, T.USHR)) { val t = eat(); val r = additive(); l = Binary(t.value, l, r).pos(t) }
        return l
    }

    private fun additive(): Expr {
        var l = multiplicative()
        while (at(T.PLUS, T.MINUS)) { val t = eat(); val r = multiplicative(); l = Binary(t.value, l, r).pos(t) }
        return l
    }

    private fun multiplicative(): Expr {
        var l = exponent()
        while (at(T.STAR, T.SLASH, T.PERCENT)) { val t = eat(); val r = exponent(); l = Binary(t.value, l, r).pos(t) }
        return l
    }

    private fun exponent(): Expr {
        val l = unary()
        if (at(T.POW)) { val t = eat(); val r = exponent(); return Binary("**", l, r).pos(t) }
        return l
    }

    private fun unary(): Expr {
        val tok = peek()
        return when (tok.type) {
            T.NOT -> { eat(); Unary("!", unary(), true).pos(tok) }
            T.BITNOT -> { eat(); Unary("~", unary(), true).pos(tok) }
            T.PLUS -> { eat(); Unary("+", unary(), true).pos(tok) }
            T.MINUS -> { eat(); Unary("-", unary(), true).pos(tok) }
            T.TYPEOF -> { eat(); Unary("typeof", unary(), true).pos(tok) }
            T.VOID -> { eat(); Unary("void", unary(), true).pos(tok) }
            T.DELETE -> { eat(); Unary("delete", unary(), true).pos(tok) }
            T.INC -> { eat(); Update("++", unary(), true).pos(tok) }
            T.DEC -> { eat(); Update("--", unary(), true).pos(tok) }
            else -> postfix()
        }
    }

    private fun postfix(): Expr {
        val e = leftHandSide()
        val tok = peek()
        if (at(T.INC) || at(T.DEC)) { eat(); return Update(tok.value, e, false).pos(tok) }
        return e
    }

    private fun leftHandSide(): Expr {
        var e = if (at(T.NEW)) newExpr() else primary()
        while (true) {
            val tok = peek()
            e = when (tok.type) {
                T.DOT -> { eat(); val id = memberNameOrKeyword(); Member(e, id, false).pos(tok) }
                T.LBRACK -> { eat(); val idx = expression(); eat(T.RBRACK); Member(e, "", true, idx).pos(tok) }
                T.LPAREN -> { Call(e, callArgs()).pos(tok) }
                else -> return e
            }
        }
    }

    private fun newExpr(): Expr {
        val tok = eat(T.NEW)
        val callee = if (at(T.NEW)) newExpr() else primary()
        // optionally accept member accesses on the constructor
        var c: Expr = callee
        while (at(T.DOT) || at(T.LBRACK)) {
            val t = peek()
            c = when (t.type) {
                T.DOT -> { eat(); Member(c, memberNameOrKeyword(), false).pos(t) }
                T.LBRACK -> { eat(); val idx = expression(); eat(T.RBRACK); Member(c, "", true, idx).pos(t) }
                else -> c
            }
        }
        val args = if (at(T.LPAREN)) callArgs() else emptyList()
        return NewExpr(c, args).pos(tok)
    }

    private fun callArgs(): List<Expr> {
        eat(T.LPAREN)
        val a = mutableListOf<Expr>()
        if (!at(T.RPAREN)) do { a.add(assignment()) } while (match(T.COMMA))
        eat(T.RPAREN)
        return a
    }

    private fun primary(): Expr {
        val tok = peek()
        return when (tok.type) {
            T.NUMBER -> { eat(); NumberLit(tok.numberValue).pos(tok) }
            T.STRING -> { eat(); StringLit(tok.value).pos(tok) }
            T.TEMPLATE_STRING -> { eat(); expandTemplate(tok.value, tok) }
            T.REGEX -> {
                // Build `new RegExp(src, flags)` from the literal's text form.
                eat()
                val raw = tok.value  // e.g. "/foo/gi"
                val lastSlash = raw.lastIndexOf('/')
                val src = if (lastSlash > 0) raw.substring(1, lastSlash) else raw.substring(1)
                val flags = if (lastSlash in 0 until raw.length - 1) raw.substring(lastSlash + 1) else ""
                NewExpr(Ident("RegExp").pos(tok), listOf(StringLit(src).pos(tok), StringLit(flags).pos(tok))).pos(tok)
            }
            T.TRUE -> { eat(); BoolLit(true).pos(tok) }
            T.FALSE -> { eat(); BoolLit(false).pos(tok) }
            T.NULL -> { eat(); NullLit.also { it.line = tok.line; it.col = tok.col } }
            T.UNDEFINED -> { eat(); UndefinedLit.also { it.line = tok.line; it.col = tok.col } }
            T.THIS -> { eat(); ThisExpr.also { it.line = tok.line; it.col = tok.col } }
            T.IDENT -> {
                // Check for arrow fn (x) => ... / x => ...
                if (peek(1).type == T.ARROW) {
                    val p = eat().value
                    eat(T.ARROW)
                    val body: Node = if (at(T.LBRACE)) block() else assignment()
                    return ArrowFn(listOf(p), body).pos(tok)
                }
                eat(); Ident(tok.value).pos(tok)
            }
            T.LPAREN -> parenOrArrow(tok)
            T.LBRACK -> arrayLit(tok)
            T.LBRACE -> objectLit(tok)
            T.FUNCTION -> funcExpr(tok)
            else -> throw ParseError("Unexpected token ${tok.type} '${tok.value}'", tok.line, tok.col)
        }
    }

    private fun parenOrArrow(tok: Token): Expr {
        // Heuristic: look ahead for `)` followed by `=>`.
        val start = pos
        eat(T.LPAREN)
        // empty-param arrow: () =>
        if (at(T.RPAREN) && peek(1).type == T.ARROW) {
            eat(T.RPAREN); eat(T.ARROW)
            val body: Node = if (at(T.LBRACE)) block() else assignment()
            return ArrowFn(emptyList(), body).pos(tok)
        }
        // Try parse comma list of idents for arrow
        val snapshot = pos
        var allIdents = true
        val names = mutableListOf<String>()
        while (true) {
            if (at(T.IDENT)) {
                names.add(eat().value)
                if (match(T.COMMA)) continue
                if (at(T.RPAREN)) break
                allIdents = false; break
            } else { allIdents = false; break }
        }
        if (allIdents && at(T.RPAREN) && peek(1).type == T.ARROW) {
            eat(T.RPAREN); eat(T.ARROW)
            val body: Node = if (at(T.LBRACE)) block() else assignment()
            return ArrowFn(names, body).pos(tok)
        }
        // rollback to plain parenthesized expression
        pos = snapshot
        val e = expression()
        eat(T.RPAREN)
        return e
    }

    private fun arrayLit(tok: Token): Expr {
        eat(T.LBRACK)
        val items = mutableListOf<Expr?>()
        while (!at(T.RBRACK)) {
            if (at(T.COMMA)) { items.add(null); eat(T.COMMA); continue }
            items.add(assignment())
            if (!at(T.RBRACK)) eat(T.COMMA)
        }
        eat(T.RBRACK)
        return ArrayLit(items).pos(tok)
    }

    private fun objectLit(tok: Token): Expr {
        eat(T.LBRACE)
        val props = mutableListOf<Pair<String, Expr>>()
        while (!at(T.RBRACE)) {
            val kTok = peek()
            val key = when (kTok.type) {
                T.IDENT, T.STRING, T.NUMBER -> eat().value
                else -> eat(T.IDENT).value
            }
            // shorthand {x}
            if (at(T.COMMA) || at(T.RBRACE)) {
                props.add(key to Ident(key).pos(kTok))
            } else {
                eat(T.COLON)
                props.add(key to assignment())
            }
            if (!at(T.RBRACE)) eat(T.COMMA)
        }
        eat(T.RBRACE)
        return ObjectLit(props).pos(tok)
    }

    private fun funcExpr(tok: Token): Expr {
        eat(T.FUNCTION)
        val name = if (at(T.IDENT)) eat().value else null
        val params = paramList()
        val body = block()
        return FunctionExpr(name, params, body).pos(tok)
    }

    /**
     * Splits a template-literal body on `${...}` and builds a `"s0" + expr0 + "s1" + ...`
     * chain of [Binary] nodes. Expressions inside the `${}` are reparsed recursively
     * via a fresh [Parser]. Unescapes standard `\n \t \r \\ \` \$` pairs.
     */
    private fun expandTemplate(raw: String, tok: Token): Expr {
        val parts = ArrayList<Expr>()
        val lit = StringBuilder()
        var i = 0
        fun flushLit() {
            parts.add(StringLit(unescapeTemplate(lit.toString())).pos(tok))
            lit.clear()
        }
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) { lit.append(c); lit.append(raw[i + 1]); i += 2; continue }
            if (c == '$' && i + 1 < raw.length && raw[i + 1] == '{') {
                flushLit()
                var depth = 1; val exprSb = StringBuilder(); i += 2
                while (i < raw.length && depth > 0) {
                    val d = raw[i]
                    if (d == '{') { depth++; exprSb.append(d); i++; continue }
                    if (d == '}') { depth--; if (depth == 0) { i++; break }; exprSb.append(d); i++; continue }
                    exprSb.append(d); i++
                }
                val inner = Parser(exprSb.toString()).parseProgram()
                val expr: Expr = when (val first = inner.body.firstOrNull()) {
                    is ExprStmt -> first.expr
                    null -> StringLit("").pos(tok)
                    else -> throw ParseError("Invalid template expression", tok.line, tok.col)
                }
                parts.add(expr)
            } else { lit.append(c); i++ }
        }
        flushLit()
        if (parts.isEmpty()) return StringLit("").pos(tok)
        var acc = parts[0]
        for (k in 1 until parts.size) {
            // coerce first operand to string so the whole chain is string-concatenation
            val leftAsStr = if (k == 1 && acc !is StringLit) Binary("+", StringLit("").pos(tok), acc).pos(tok) else acc
            acc = Binary("+", leftAsStr, parts[k]).pos(tok)
        }
        return acc
    }

    private fun unescapeTemplate(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(when (val e = s[i + 1]) {
                    'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
                    '\\' -> '\\'; '`' -> '`'; '$' -> '$'
                    else -> e
                })
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
