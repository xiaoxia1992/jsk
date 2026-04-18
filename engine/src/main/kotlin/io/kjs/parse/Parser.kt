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
            T.CLASS -> classDecl()
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
            val d = if (at(T.LBRACK) || at(T.LBRACE)) {
                val pat = bindingPattern()
                val init = if (match(T.ASSIGN)) assignment() else null
                Declarator(null, pat, init).pos(tok)
            } else {
                val n = eat(T.IDENT).value
                val init = if (match(T.ASSIGN)) assignment() else null
                Declarator(n, null, init).pos(tok)
            }
            list.add(d)
        } while (match(T.COMMA))
        return VarDecl(kind, list).pos(tok)
    }

    // --- destructuring patterns ---
    private fun bindingPattern(): Pattern = when {
        at(T.LBRACK) -> arrayBindingPattern()
        at(T.LBRACE) -> objectBindingPattern()
        else -> {
            val tok = eat(T.IDENT)
            val def = if (match(T.ASSIGN)) assignment() else null
            IdentPattern(tok.value, def).pos(tok)
        }
    }

    private fun arrayBindingPattern(): Pattern {
        val tok = eat(T.LBRACK)
        val elts = mutableListOf<Pattern?>()
        var rest: Pattern? = null
        while (!at(T.RBRACK)) {
            if (at(T.COMMA)) { elts.add(null); eat(T.COMMA); continue }
            if (match(T.ELLIPSIS)) {
                rest = bindingPatternNoDefault()
                break
            }
            elts.add(bindingPattern())
            if (!at(T.RBRACK)) eat(T.COMMA)
        }
        eat(T.RBRACK)
        return ArrayPattern(elts, rest).pos(tok)
    }

    private fun objectBindingPattern(): Pattern {
        val tok = eat(T.LBRACE)
        val props = mutableListOf<ObjectPatternProp>()
        var rest: IdentPattern? = null
        while (!at(T.RBRACE)) {
            if (match(T.ELLIPSIS)) {
                val rtok = peek()
                val rname = eat(T.IDENT).value
                rest = IdentPattern(rname).pos(rtok)
                break
            }
            val ktok = peek()
            val key = when (ktok.type) {
                T.IDENT, T.STRING, T.NUMBER -> eat().value
                else -> eat(T.IDENT).value
            }
            val valuePat: Pattern = if (match(T.COLON)) {
                bindingPattern()
            } else {
                // shorthand: { x } — binding named "x", optional default
                val def = if (match(T.ASSIGN)) assignment() else null
                IdentPattern(key, def).pos(ktok)
            }
            props.add(ObjectPatternProp(key, valuePat).pos(ktok))
            if (!at(T.RBRACE)) eat(T.COMMA)
        }
        eat(T.RBRACE)
        return ObjectPattern(props, rest).pos(tok)
    }

    /** Rest target cannot itself have a default in ES spec. */
    private fun bindingPatternNoDefault(): Pattern = when {
        at(T.LBRACK) -> arrayBindingPattern()
        at(T.LBRACE) -> objectBindingPattern()
        else -> { val tok = eat(T.IDENT); IdentPattern(tok.value, null).pos(tok) }
    }

    /**
     * Convert an already-parsed Expr (which was parsed as a normal expression
     * because the parser didn't yet know it would be an assignment target) into
     * a destructuring Pattern.  Used for `[a, b] = arr` and `({a} = obj)`.
     */
    private fun exprToPattern(e: Expr): Pattern = when (e) {
        is Ident -> IdentPattern(e.name).also { it.line = e.line; it.col = e.col }
        is ArrayLit -> {
            val items = e.elements
            val elts = mutableListOf<Pattern?>()
            var rest: Pattern? = null
            for ((idx, el) in items.withIndex()) {
                if (el == null) { elts.add(null); continue }
                // rest handled via Unary "..." below
                if (el is Unary && el.op == "..." && idx == items.size - 1) {
                    rest = exprToPattern(el.arg)
                } else if (el is Assign && el.op == "=") {
                    val inner = exprToPattern(el.target)
                    elts.add(patternWithDefault(inner, el.value))
                } else {
                    elts.add(exprToPattern(el))
                }
            }
            ArrayPattern(elts, rest).also { it.line = e.line; it.col = e.col }
        }
        is ObjectLit -> {
            val props = mutableListOf<ObjectPatternProp>()
            var rest: IdentPattern? = null
            for ((k, v) in e.props) {
                if (k == "__rest__") { rest = exprToPattern(v) as IdentPattern; continue }
                val (valPat, _) = if (v is Assign && v.op == "=") {
                    exprToPattern(v.target) to v.value
                } else exprToPattern(v) to null
                val defAttached = if (v is Assign && v.op == "=") patternWithDefault(valPat, v.value) else valPat
                props.add(ObjectPatternProp(k, defAttached).also { it.line = e.line; it.col = e.col })
            }
            ObjectPattern(props, rest).also { it.line = e.line; it.col = e.col }
        }
        is Member -> AssignTargetPattern(e).also { it.line = e.line; it.col = e.col }
        else -> AssignTargetPattern(e).also { it.line = e.line; it.col = e.col }
    }

    private fun patternWithDefault(p: Pattern, def: Expr): Pattern = when (p) {
        is IdentPattern -> IdentPattern(p.name, def).also { it.line = p.line; it.col = p.col }
        is AssignTargetPattern -> AssignTargetPattern(p.target, def).also { it.line = p.line; it.col = p.col }
        else -> p   // array/object pattern defaults are wrapped at the caller level
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
            val d0 = init.declarators[0]
            // For pattern-bound `for (var [a,b] of …)` we synthesise a hidden temp
            // identifier and wrap `body` in a destructuring assignment — handled
            // later by the Compiler which recognises the Labeled tag.  For now we
            // require a simple identifier; complex patterns trigger a TODO-style
            // error that points at the correct source location.
            val leftName = if (d0.name != null) Ident(d0.name).pos(tok)
            else throw ParseError("destructuring in for-in/of not yet supported", tok.line, tok.col)
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

    // ---- class declaration ----
    private fun classDecl(): Stmt {
        val tok = eat(T.CLASS)
        val name = if (at(T.IDENT)) eat().value else null
        val decl = classBody(name, tok)
        return decl
    }

    private fun classBody(name: String?, tok: Token): ClassDecl {
        var superClass: Expr? = null
        if (match(T.EXTENDS)) {
            superClass = leftHandSide()
        }
        eat(T.LBRACE)
        val members = mutableListOf<ClassMember>()
        var ctor: ClassMember? = null
        while (!at(T.RBRACE) && !at(T.EOF)) {
            if (match(T.SEMI)) continue
            val mtok = peek()
            // static prefix
            var isStatic = false
            if (peek().type == T.IDENT && peek().value == "static" && peek(1).type != T.LPAREN && peek(1).type != T.ASSIGN) {
                eat()
                isStatic = true
            }
            // getter / setter
            var kind = MemberKind.METHOD
            if (peek().type == T.IDENT && (peek().value == "get" || peek().value == "set")
                && peek(1).type != T.LPAREN && peek(1).type != T.ASSIGN && peek(1).type != T.SEMI
                && peek(1).type != T.RBRACE) {
                kind = if (peek().value == "get") MemberKind.GETTER else MemberKind.SETTER
                eat()
            }
            // private?
            val isPrivate = peek().type == T.IDENT && peek().value.startsWith("#") // lexer doesn't emit # — we use a hack
            // Actual private-field support (`#field`) requires lexer changes; skip for now.
            // Name
            val nameTok = peek()
            val memberName = when (nameTok.type) {
                T.IDENT, T.STRING, T.NUMBER -> eat().value
                else -> eat(T.IDENT).value
            }
            if (memberName == "constructor" && !isStatic) {
                // constructor(…) { body }
                val params = paramList()
                val body = block()
                val fn = FunctionExpr(null, params, body).pos(mtok) as FunctionExpr
                ctor = ClassMember("constructor", MemberKind.METHOD, false, false, fn, null).pos(mtok)
                continue
            }
            // field declaration: name = init ; or name ;
            if (at(T.ASSIGN) || at(T.SEMI) || at(T.RBRACE)) {
                val init = if (match(T.ASSIGN)) assignment() else null
                match(T.SEMI)
                members.add(ClassMember(memberName, MemberKind.FIELD, isStatic, isPrivate, null, init).pos(mtok))
                continue
            }
            // method
            val params = paramList()
            val body = block()
            val fn = FunctionExpr(null, params, body).pos(mtok) as FunctionExpr
            members.add(ClassMember(memberName, kind, isStatic, isPrivate, fn, null).pos(mtok))
        }
        eat(T.RBRACE)
        return ClassDecl(name, superClass, ctor, members).pos(tok)
    }

    private fun paramList(): List<Param> {
        eat(T.LPAREN)
        val ps = mutableListOf<Param>()
        if (!at(T.RPAREN)) {
            do {
                val tok = peek()
                if (match(T.ELLIPSIS)) {
                    // ...name — rest parameter
                    val nm = eat(T.IDENT).value
                    ps.add(Param(nm, null, null, rest = true).pos(tok))
                    break   // rest must be the last param
                }
                val pat = if (at(T.LBRACK) || at(T.LBRACE)) bindingPattern() else null
                val name = if (pat == null) eat(T.IDENT).value else null
                val def = if (match(T.ASSIGN)) assignment() else null
                ps.add(Param(name, pat, def, rest = false).pos(tok))
            } while (match(T.COMMA))
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
        // Destructuring assignment: [a,b] = rhs  or  ({a,b} = rhs).
        if (op == "=" && (left is ArrayLit || left is ObjectLit)) {
            return DestructuringAssign(exprToPattern(left), right).pos(tok)
        }
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
        if (!at(T.RPAREN)) do {
            if (at(T.ELLIPSIS)) {
                val tok = eat()
                a.add(Unary("...", assignment(), true).pos(tok))
            } else {
                a.add(assignment())
            }
        } while (match(T.COMMA))
        eat(T.RPAREN)
        return a
    }

    private fun primary(): Expr {
        val tok = peek()
        return when (tok.type) {
            T.NUMBER -> { eat(); NumberLit(tok.numberValue).pos(tok) }
            T.BIGINT -> { eat(); BigIntLit(tok.bigIntValue!!).pos(tok) }
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
            T.SUPER -> {
                eat()
                val next = peek()
                when (next.type) {
                    T.DOT -> {
                        eat()
                        val prop = memberNameOrKeyword()
                        SuperMember(prop, false).pos(tok)
                    }
                    T.LBRACK -> {
                        eat()
                        val k = expression()
                        eat(T.RBRACK)
                        SuperMember("", true, k).pos(tok)
                    }
                    T.LPAREN -> {
                        val args = callArgs()
                        SuperCall(args).pos(tok)
                    }
                    else -> throw ParseError("'super' must be followed by '.', '[' or '('", tok.line, tok.col)
                }
            }
            T.CLASS -> {
                eat()
                val nm = if (at(T.IDENT)) eat().value else null
                val decl = classBody(nm, tok)
                ClassExpr(decl).pos(tok)
            }
            T.IDENT -> {
                // Check for arrow fn (x) => ... / x => ...
                if (peek(1).type == T.ARROW) {
                    val p = eat().value
                    eat(T.ARROW)
                    val body: Node = if (at(T.LBRACE)) block() else assignment()
                    return ArrowFn(listOf(Param.simple(p).pos(tok)), body).pos(tok)
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
            return ArrowFn(names.map { Param.simple(it).pos(tok) }, body).pos(tok)
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
            if (at(T.ELLIPSIS)) {
                val etok = eat()
                val arg = assignment()
                items.add(Unary("...", arg, true).pos(etok))
            } else {
                items.add(assignment())
            }
            if (!at(T.RBRACK)) eat(T.COMMA)
        }
        eat(T.RBRACK)
        return ArrayLit(items).pos(tok)
    }

    private fun objectLit(tok: Token): Expr {
        eat(T.LBRACE)
        val props = mutableListOf<Pair<String, Expr>>()
        while (!at(T.RBRACE)) {
            if (at(T.ELLIPSIS)) {
                val etok = eat()
                val arg = assignment()
                // Mark rest/spread with a sentinel key; Compiler / exprToPattern handle it.
                props.add("__rest__" to Unary("...", arg, true).pos(etok))
                if (!at(T.RBRACE)) eat(T.COMMA)
                continue
            }
            val kTok = peek()
            val key = when (kTok.type) {
                T.IDENT, T.STRING, T.NUMBER -> eat().value
                else -> eat(T.IDENT).value
            }
            // shorthand {x}
            if (at(T.COMMA) || at(T.RBRACE)) {
                props.add(key to Ident(key).pos(kTok))
            } else if (match(T.ASSIGN)) {
                // {x = 1}: used only as pattern; we represent the "default" via Assign node
                props.add(key to Assign("=", Ident(key).pos(kTok), assignment()).pos(kTok))
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
