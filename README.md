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
- **Template JIT** — hot functions are compiled to JVM bytecode at runtime via ASM, reaching **12–40× speedups** on arithmetic workloads (HotSpot's C2 then JITs the generated class to native code).
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

Measured on an Apple-silicon MacBook with JDK 17. Each benchmark runs five times after warmup; numbers are wall-clock totals in milliseconds.

**Pure-arithmetic hot loops (JIT kicks in):**

| Benchmark | Interpreter | **KJS JIT** | V8 (Node v24) | JIT speedup |
|---|---:|---:|---:|---:|
| Sum 1M integers | ~420 ms | **~35 ms** | ~3 ms | **12×** |
| Polynomial 1M | ~870 ms | **~22 ms** | ~6 ms | **40×** |
| Count primes ≤5000 | ~39 ms | **~7 ms** | ~0 ms | **5.5×** |
| Tight loop 1M | ~480 ms | **~41 ms** | — | **12×** |

**Mixed workloads (JIT bails out due to calls / property access):**

| Benchmark | Interpreter | KJS VM |
|---|---:|---:|
| `fib(28)` × 5 | ~800 ms | ~430 ms |
| Property-heavy hot loop | ~260 ms | ~200 ms |
| String build (10 K concat) | ~95 ms | ~70 ms |

### How the JIT works

When a function gets called a few times, KJS compiles its bytecode to JVM
bytecode via ASM, producing a `Compiled` subclass whose `invoke` method runs
on the native JVM operand stack. HotSpot's C2 then compiles *that* to machine
code, so you get a two-stage JIT for free: KJS → JVM → native.

The JIT is deliberately conservative — it only touches functions composed of
arithmetic, comparisons, locals, and control flow. The moment a function needs
a call, property access, closure allocation, or exception handling, it stays on
the interpreter. This trades coverage for safety; future work will push the
line further (see roadmap).

### JIT controls

```bash
./kjs foo.js                        # JIT enabled by default (threshold = 3)
KJS_JIT=off ./kjs foo.js            # disable JIT (pure interpreter)
KJS_JIT_THRESHOLD=10 ./kjs foo.js   # only compile after 10 calls
KJS_JIT_LOG=1 ./kjs foo.js          # milestones: compile / skip / first-call
KJS_JIT_LOG=trace ./kjs foo.js      # per-call countdown + JIT call counts
```

## Not Yet Implemented (Roadmap)

- **NaN-boxing** — pack all JS values into a 64-bit `Long` to eliminate autoboxing
- **Shape / hidden-class polymorphic ICs** — today's IC is monomorphic on class name only
- **Broader JIT coverage** — compile functions containing calls, property access, and closures; add on-stack replacement (OSR) so long-running loops can be JIT'd mid-flight
- **ES language gaps** — destructuring, `class`, rest/spread, generators, `async/await`, ES modules

## License

MIT
