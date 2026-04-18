package io.kjs.bench

import io.kjs.Engine
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class EngineBenchmarks {
    private lateinit var vm: Engine
    private lateinit var walker: Engine

    @Setup(Level.Trial)
    fun setup() {
        vm = Engine(Engine.Backend.Vm); walker = Engine(Engine.Backend.Walker)
        // warm global state (define fib once per engine)
        val fib = "function fib(n){ return n < 2 ? n : fib(n-1)+fib(n-2) } "
        vm.eval(fib); walker.eval(fib)
    }

    @Benchmark fun vmFib25()     = vm.eval("fib(25)")
    @Benchmark fun walkerFib25() = walker.eval("fib(25)")

    @Benchmark fun vmSumLoop() = vm.eval("var s=0; for (var i=0;i<10000;i=i+1) s=s+i; s")
    @Benchmark fun walkerSumLoop() = walker.eval("var s=0; for (var i=0;i<10000;i=i+1) s=s+i; s")

    @Benchmark fun vmObjPropHot() = vm.eval("var o={a:1,b:2,c:3,d:4}; var s=0; for (var i=0;i<1000;i=i+1) s = s + o.a + o.b + o.c + o.d; s")
}
