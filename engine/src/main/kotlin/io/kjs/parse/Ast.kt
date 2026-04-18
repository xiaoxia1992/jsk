package io.kjs.parse

/**
 * AST node definitions for a useful subset of ES5.1 + a few ES2015 niceties.
 * Kept small on purpose; the bytecode compiler in M2 will consume this tree.
 */
sealed class Node { var line: Int = 0; var col: Int = 0 }

// --- Program / statements ---
class Program(val body: List<Stmt>) : Node()

sealed class Stmt : Node()
class Block(val body: List<Stmt>) : Stmt()
class ExprStmt(val expr: Expr) : Stmt()
class VarDecl(val kind: String /* var/let/const */, val declarators: List<Declarator>) : Stmt()
class Declarator(val name: String, val init: Expr?) : Node()
class If(val test: Expr, val cons: Stmt, val alt: Stmt?) : Stmt()
class While(val test: Expr, val body: Stmt) : Stmt()
class DoWhile(val body: Stmt, val test: Expr) : Stmt()
class ForC(val init: Node?, val test: Expr?, val update: Expr?, val body: Stmt) : Stmt()
class ForIn(val leftKind: String?, val left: Expr, val right: Expr, val body: Stmt) : Stmt()
class ForOf(val leftKind: String?, val left: Expr, val right: Expr, val body: Stmt) : Stmt()
class Return(val arg: Expr?) : Stmt()
class Break(val label: String?) : Stmt()
class Continue(val label: String?) : Stmt()
class Throw(val arg: Expr) : Stmt()
class Try(val block: Block, val catchParam: String?, val catchBody: Block?, val finallyBody: Block?) : Stmt()
class FunctionDecl(val name: String, val params: List<String>, val body: Block) : Stmt()
class EmptyStmt : Stmt()
class Labeled(val label: String, val body: Stmt) : Stmt()

// --- Expressions ---
sealed class Expr : Node()
class NumberLit(val value: Double) : Expr()
class StringLit(val value: String) : Expr()
class BoolLit(val value: Boolean) : Expr()
object NullLit : Expr()
object UndefinedLit : Expr()
object ThisExpr : Expr()
class Ident(val name: String) : Expr()
class ArrayLit(val elements: List<Expr?>) : Expr()
class ObjectLit(val props: List<Pair<String, Expr>>) : Expr()
class FunctionExpr(val name: String?, val params: List<String>, val body: Block) : Expr()
class ArrowFn(val params: List<String>, val body: Node /* Expr or Block */) : Expr()
class Unary(val op: String, val arg: Expr, val prefix: Boolean) : Expr()
class Update(val op: String /* ++, -- */, val arg: Expr, val prefix: Boolean) : Expr()
class Binary(val op: String, val left: Expr, val right: Expr) : Expr()
class Logical(val op: String, val left: Expr, val right: Expr) : Expr()
class Assign(val op: String, val target: Expr, val value: Expr) : Expr()
class Conditional(val test: Expr, val cons: Expr, val alt: Expr) : Expr()
class Member(val obj: Expr, val prop: String, val computed: Boolean, val computedExpr: Expr? = null) : Expr()
class Call(val callee: Expr, val args: List<Expr>) : Expr()
class NewExpr(val callee: Expr, val args: List<Expr>) : Expr()
class Sequence(val items: List<Expr>) : Expr()
class TemplateLit(val raw: String) : Expr()
