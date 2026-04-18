package io.kjs.vm

import io.kjs.runtime.JsFunction
import io.kjs.runtime.Realm

/**
 * Runtime-generated JIT code implements this interface. One instance per
 * compiled function (or top-level program).
 *
 * The JIT generates a subclass of [Compiled] via ASM whose `invoke` method
 * contains the translated bytecode. The VM calls it directly when a function's
 * hotness counter crosses the threshold, bypassing the opcode dispatch loop.
 */
abstract class Compiled {
    /**
     * Execute the compiled function.
     *
     * @param vm        the VM (for calling back into the interpreter when we meet
     *                  an opcode the JIT refuses to compile)
     * @param realm     runtime realm (globals, intrinsics)
     * @param closure   captured environment + upvalues (the same VmClosure the
     *                  interpreter would use)
     * @param thisVal   `this` binding
     * @param args      positional arguments
     */
    abstract fun invoke(
        vm: Vm, realm: Realm, closure: VmClosure,
        thisVal: Any?, args: Array<Any?>,
    ): Any?
}
