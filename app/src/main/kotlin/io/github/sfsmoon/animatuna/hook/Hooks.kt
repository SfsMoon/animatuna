package io.github.sfsmoon.animatuna.hook

import java.lang.reflect.Executable

import io.github.libxposed.api.XposedInterface

import io.github.sfsmoon.animatuna.core.Logger

/**
 * The hooking primitives this module needs. Host patches depend on this rather
 * than on the Xposed API. A body that throws is reported and the host's own
 * code runs as if the hook were not there: a broken tweak must never take a
 * launcher method down with it.
 */
interface Hooks {

    /** Lets every call to [method] run, then runs [after] with the object and arguments. */
    fun after(method: Executable, after: (thisObject: Any?, args: List<Any?>) -> Unit)

    /** Runs every call to [method] with the arguments [transform] makes of the caller's. */
    fun replaceArguments(method: Executable, transform: (thisObject: Any?, args: List<Any?>) -> List<Any?>)

    /** Lets every call to [method] run, then returns what [transform] makes of its result. */
    fun replaceResult(method: Executable, transform: (thisObject: Any?, args: List<Any?>, result: Any?) -> Any?)

    /**
     * Runs [around] in place of every call to [method]; it calls `proceed` to
     * run the host's own code. Where [around] throws before proceeding, the
     * host's own code runs instead.
     */
    fun around(method: Executable, around: (thisObject: Any?, args: List<Any?>, proceed: () -> Any?) -> Any?)
}

/** [Hooks] over the modern Xposed API, as Vector implements it. */
class XposedHooks(private val xposed: XposedInterface, private val logger: Logger) : Hooks {

    override fun after(method: Executable, after: (thisObject: Any?, args: List<Any?>) -> Unit) {
        xposed.hook(method).intercept { chain ->
            val result = chain.proceed()
            runCatching { after(chain.thisObject, chain.args) }.onFailure { failed(method, it) }
            result
        }
        hooked(method)
    }

    override fun replaceArguments(method: Executable, transform: (thisObject: Any?, args: List<Any?>) -> List<Any?>) {
        xposed.hook(method).intercept { chain ->
            val args = runCatching { transform(chain.thisObject, chain.args) }
                .onFailure { failed(method, it) }
                .getOrDefault(chain.args)
            chain.proceed(args.toTypedArray())
        }
        hooked(method)
    }

    override fun replaceResult(method: Executable, transform: (thisObject: Any?, args: List<Any?>, result: Any?) -> Any?) {
        xposed.hook(method).intercept { chain ->
            val result = chain.proceed()
            runCatching { transform(chain.thisObject, chain.args, result) }
                .onFailure { failed(method, it) }
                .getOrDefault(result)
        }
        hooked(method)
    }

    override fun around(method: Executable, around: (thisObject: Any?, args: List<Any?>, proceed: () -> Any?) -> Any?) {
        xposed.hook(method).intercept { chain ->
            var proceeded = false
            val proceed = {
                proceeded = true
                chain.proceed()
            }
            try {
                around(chain.thisObject, chain.args, proceed)
            } catch (error: Throwable) {
                // The host's own failure, from within proceed, is the host's to throw.
                if (proceeded) throw error
                failed(method, error)
                chain.proceed()
            }
        }
        hooked(method)
    }

    private fun hooked(method: Executable) = logger.info("Hooked ${method.declaringClass.simpleName}.${method.name}")

    private fun failed(method: Executable, error: Throwable) =
        logger.warn("Hook body failed for ${method.declaringClass.simpleName}.${method.name}", error)
}
