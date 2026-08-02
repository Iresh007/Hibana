package uy.kohesive.injekt

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minimal host implementation of the Injekt service locator the Tachiyomi
 * extensions-lib uses. Extensions resolve host singletons (chiefly
 * `NetworkHelper` and the app `Context`) via `injectLazy()` / `Injekt.get<T>()`.
 * The host registers those singletons before loading any extension.
 */
object Injekt {
    @PublishedApi
    internal val singletons = CopyOnWriteArrayList<Any>()

    fun addSingleton(instance: Any) {
        singletons.removeAll { it.javaClass == instance.javaClass }
        singletons.add(instance)
    }

    /** First registered singleton assignable to [cls]. */
    fun getByClass(cls: Class<*>): Any =
        singletons.firstOrNull { cls.isInstance(it) }
            ?: throw IllegalStateException("Injekt: no singleton bound for ${cls.name}")
}

/** `val network: NetworkHelper by injectLazy()` — the ubiquitous extension idiom. */
inline fun <reified T : Any> injectLazy(): Lazy<T> = lazy { Injekt.getByClass(T::class.java) as T }
