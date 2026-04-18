package io.kjs.runtime

/**
 * A Realm holds all intrinsic prototypes + the global object + global environment.
 * Keeping this separate makes it easy to spin up multiple isolated engines.
 */
class Realm {
    val objectProto = JsObject(null)
    val functionProto = JsObject(objectProto)
    val arrayProto = JsObject(objectProto)
    val stringProto = JsObject(objectProto)
    val numberProto = JsObject(objectProto)
    val booleanProto = JsObject(objectProto)
    val errorProto = JsObject(objectProto)

    val globalObject = JsObject(objectProto)
    val globalEnv = Environment()

    init {
        globalEnv.declare("this", globalObject)
        globalEnv.declare("undefined", JsValues.UNDEFINED)
        globalEnv.declare("globalThis", globalObject)
        Intrinsics.install(this)
        IntrinsicsExt.install(this)
        KjsNamespace.install(this)
    }
}
