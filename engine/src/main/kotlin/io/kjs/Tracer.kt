package io.kjs

import io.kjs.ir.Bytecode
import io.kjs.ir.OP_VALUES
import io.kjs.ir.Op
import io.kjs.lex.Token
import io.kjs.parse.*
import io.kjs.runtime.JsValues

/**
 * Educational tracer: if attached to an [Engine], it prints a narrative of what the
 * lexer, parser, compiler and VM are doing. Disabled by default — turn on via
 * `Engine(trace = true)` or the `--trace` CLI flag.
 *
 * Output is written to stderr so it doesn't mix with user `console.log` output.
 */
class Tracer(val out: java.io.PrintStream = System.err) {
    var enabled: Boolean = true

    private fun h(title: String) { if (enabled) { out.println(); out.println("── $title ─────────────────────────────") } }
    private fun l(s: String) { if (enabled) out.println(s) }

    fun onTokens(tokens: List<Token>) {
        h("1. 词法分析 (Lexer)")
        l("把源码切成一个个记号 (token)。类型 + 字面量：")
        for (t in tokens) {
            if (t.type.name == "EOF") { l(String.format("  %2d: %-12s", tokens.indexOf(t), t.type.name)); break }
            l(String.format("  %2d: %-12s  \"%s\"", tokens.indexOf(t), t.type.name, t.value))
        }
    }

    fun onAst(program: Program) {
        h("2. 语法分析 (Parser → AST)")
        l("把 token 流按运算符优先级组装成抽象语法树 (AST)。")
        for (s in program.body) l(astLines(s, 1))
    }

    private fun astLines(n: Node, depth: Int): String {
        val pad = "  ".repeat(depth)
        return when (n) {
            is ExprStmt -> "${pad}ExprStmt\n" + astLines(n.expr, depth + 1)
            is Call -> {
                val sb = StringBuilder("${pad}Call\n")
                sb.append(pad).append("  callee:\n").append(astLines(n.callee, depth + 2)).append("\n")
                for ((i, a) in n.args.withIndex()) {
                    sb.append(pad).append("  arg[$i]:\n").append(astLines(a, depth + 2))
                    if (i != n.args.lastIndex) sb.append("\n")
                }
                sb.toString()
            }
            is Member -> "${pad}Member .${n.prop}\n" + astLines(n.obj, depth + 1)
            is Binary -> "${pad}Binary(${n.op})\n" + astLines(n.left, depth + 1) + "\n" + astLines(n.right, depth + 1)
            is NumberLit -> "${pad}NumberLit(${n.value})"
            is StringLit -> "${pad}StringLit(\"${n.value}\")"
            is Ident -> "${pad}Ident(${n.name})"
            is BoolLit -> "${pad}BoolLit(${n.value})"
            NullLit -> "${pad}NullLit"
            UndefinedLit -> "${pad}UndefinedLit"
            else -> "${pad}${n.javaClass.simpleName}"
        }
    }

    fun onBytecode(bc: Bytecode) {
        h("3. 字节码编译 (Compiler → Bytecode)")
        l("把 AST 线性化为栈式虚拟机指令。每条指令有 op + 两个操作数槽。")
        l(formatBytecode(bc))
    }

    private fun formatBytecode(bc: Bytecode): String {
        val sb = StringBuilder()
        sb.append("  [常量池] ").append(bc.constants.joinToString(", ") { "\"$it\"" }).append('\n')
        sb.append("  [字符串池] ").append(bc.strings.joinToString(", ") { "\"$it\"" }).append('\n')
        sb.append("  [指令]\n")
        for (pc in 0 until bc.size) {
            val op = OP_VALUES[bc.code[pc]]
            val a = bc.aOps[pc]; val b = bc.bOps[pc]
            val annot = when (op) {
                Op.LOAD_INT -> "             ; push $a"
                Op.LOAD_CONST -> "             ; push ${bc.constants[a]}"
                Op.LOAD_STR -> "             ; push \"${bc.strings[a]}\""
                Op.LOAD_ONE -> "             ; push 1"
                Op.LOAD_ZERO -> "             ; push 0"
                Op.LOAD_GLOBAL -> "             ; push globals[\"${bc.strings[a]}\"]"
                Op.LOAD_PROP -> "             ; replace top obj with obj.${bc.strings[a]}"
                Op.CALL_METHOD -> "             ; method call with $a args"
                Op.CALL -> "             ; call with $a args"
                Op.ADD -> "             ; pop r, pop l, push l + r"
                Op.MUL -> "             ; pop r, pop l, push l * r"
                Op.STASH_RESULT -> "             ; save top as program result"
                Op.HALT -> "             ; end of program"
                else -> ""
            }
            sb.append(String.format("   %3d: %-14s %4d %4d%s%n", pc, op.name, a, b, annot))
        }
        return sb.toString()
    }

    // ---- VM step-by-step ----
    fun onVmEnter(bc: Bytecode) {
        h("4. 字节码执行 (VM)")
        l("栈机按 pc 顺序逐条执行指令；括号内是「执行后」的栈状态（栈顶在右）。")
    }

    fun onVmStep(pc: Int, op: Op, a: Int, b: Int, stackSnapshot: String) {
        if (!enabled) return
        l(String.format("   pc=%-3d %-14s a=%-4d   stack = [%s]", pc, op.name, a, stackSnapshot))
    }

    fun onResult(result: Any?) {
        h("5. 结果")
        l("  最终 lastResult = ${JsValues.toStr(result)}")
    }
}
