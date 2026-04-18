# KJS — A Kotlin/JVM JavaScript Engine

**🌐 Language / 语言:** **English** · [简体中文](./README.zh-CN.md)

---

**KJS** is a from-scratch JavaScript engine written in pure Kotlin on the JVM, architected along the same lines as [QuickJS](https://bellard.org/quickjs/): a hand-written lexer/parser that produces an AST, a bytecode compiler, and a stack-based virtual machine with property inline caches.

It runs a pragmatic subset of **ES5 + ES2015+**, ships with a narrated "teaching mode" that prints every step from tokens to stack transitions, and reaches roughly **2× faster than a tree-walking interpreter** on recursive and tight-loop workloads.

---

## Highlights

- **Dual backends behind the same API** — the default is the bytecode VM; a tree-walking interpreter is retained as a correctness oracle and runs all 24 parity test cases with zero divergence.
- **60+ opcode stack machine** — instructions are stored in three parallel `IntArray` lanes (opcode / operand A / operand B) for cache-friendly dispatch.
- **Real closures** — captured locals are promoted to shared `Upvalue` boxes so multiple closures and the parent frame all see the latest value.
- **Monomorphic inline caches** for property access, with a megamorphic fallback after repeated misses.
- **Narrated `--trace` mode** — prints the token stream, AST, bytecode disassembly, and per-instruction stack state; designed for learners.
- **Embeddable** — host code can expose its own namespaces and native functions (see the built-in `kjs.rand / kjs.ms / kjs.assert / kjs.repeat` example).
- **Test262-compatible runner** — parses YAML frontmatter, loads `harness/` includes, honours `negative`/`features` directives.
- **55 passing unit tests** across basics, ES2015+ surface, VM/walker parity, and Test262-style fixtures.

## Language Surface

**Core (ES5):** `var`/`let`/`const`, block scoping, `if`/`while`/`do-while`/`for`/`for-in`/`for-of`, labeled `break`/`continue`, `try`/`catch`/`finally`, `typeof`/`delete`/`in`/`void`, full prototype chain, `new`, `instanceof`.

**ES2015+:** template literals with `${…}` interpolation, arrow functions (with lexical `this`), regex literals, `Object.defineProperty`/`freeze`/`create`/`setPrototypeOf`/`is`, `Array.from`/`of`/`flat`/`flatMap`/`sort`, `String.padStart`/`padEnd`/`match`/`replace` (regex), `Map`/`Set`, `Date`, `Symbol` (minimal), synchronous `Promise`.

## Architecture

```
  source
     │
     ▼                            ┌─── Tracer (optional, --trace) ───┐
   Lexer      ──► tokens          │                                  │
     │                            │   prints tokens                  │
     ▼                            │   prints AST                     │
  Parser      ──► AST ────────────│   prints bytecode                │
     │                            │   prints VM step-by-step         │
     ▼                            │                                  │
  Compiler   ──► Bytecode ────────┘                                  │
     │           (IntArray ops + const/string pools + nested fns)    │
     ▼                                                               │
   Stack VM  ──► runtime values (JsObject / JsArray / JsFunction)    │
     │                                                               │
     └─► PropIc (inline cache) ──► prototype chain walk (slow path) ─┘
```

Key design choices:

- **Stack-machine bytecode** — each AST node compiles to a post-order sequence of opcodes, making the VM a tight `while (true) when (op)` loop that Kotlin compiles down to a JVM `tableswitch`.
- **Open upvalues** — when a closure captures a parent local, the frame's slot is replaced in place with a shared box; all readers and writers (parent + every closure) go through the same reference.
- **Frame pooling** — operand stacks and locals arrays are recycled across calls to keep GC pressure off the hot recursive path.
- **Fast-path call dispatch** — JS-level calls to VM-compiled functions skip the generic `JsFunction.call` indirection via a cached `VmClosure` handle.

## Module Layout

| Module | Contents |
|---|---|
| `engine` | Lexer, parser, AST, IR (opcode/bytecode/compiler), stack VM, runtime values, built-ins |
| `cli` | REPL and script runner (`./kjs foo.js`, `./kjs -e '…'`, `./kjs --trace …`) |
| `tests/unit` | JUnit 5 suites — basics, ES2015+, VM↔walker parity, Test262 fixtures |
| `tests/test262-runner` | Driver for the upstream [tc39/test262](https://github.com/tc39/test262) corpus |
| `tests/bench` | JMH benchmarks |

## Quick Start

```bash
# Build
./gradlew build

# Run tests (55 cases across 4 suites)
./gradlew :tests:unit:test

# REPL
./kjs

# Run a script
./kjs demo.js

# Run an inline snippet
./kjs -e "console.log([1,2,3].map(x => x*x))"

# See the engine narrate itself (tokens → AST → bytecode → VM steps)
./kjs --trace -e "console.log(1 + 2 * 19)"

# Switch to the AST interpreter (for cross-checking or debugging)
KJS_BACKEND=walker ./kjs foo.js

# Run the Test262 runner against an upstream checkout
git clone https://github.com/tc39/test262 third_party/test262
./gradlew :tests:test262-runner:run --args="third_party/test262 test/language/expressions/addition"

# JMH benchmarks
./gradlew :tests:bench:jmh
```

## Performance

Measured on an Apple-silicon MacBook with JDK 17. All scripts run five times after two warmup iterations; times are the wall-clock sum in milliseconds.

| Benchmark | Tree-walker | KJS VM | Speedup |
|---|---:|---:|---:|
| `fib(28)` × 5 | ~800 ms | ~430 ms | **1.9×** |
| Tight loop (1 M iters) | ~720 ms | ~460 ms | **1.6×** |
| Property-heavy hot loop | ~260 ms | ~200 ms | **1.3×** |
| String build (10 K concat) | ~95 ms | ~70 ms | **1.4×** |

KJS is still an interpreter — a modern JIT engine like V8 remains roughly **30–100× faster**. Closing that gap is what the remaining roadmap items target (see below).

## Not Yet Implemented (Roadmap)

- **NaN-boxing** — pack all JS values into a 64-bit `Long` to eliminate autoboxing
- **Shape / hidden-class polymorphic ICs** — today's IC is monomorphic on class name only
- **Template JIT** — compile hot bytecode sequences to `MethodHandle` chains via ASM
- **ES language gaps** — destructuring, `class`, rest/spread, generators, `async/await`, ES modules

## License

MIT
