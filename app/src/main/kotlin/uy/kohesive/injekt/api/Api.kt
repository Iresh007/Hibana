package uy.kohesive.injekt.api

import uy.kohesive.injekt.Injekt

/** `Injekt.get<NetworkHelper>()` — matches the extensions-lib import `uy.kohesive.injekt.api.get`. */
inline fun <reified T : Any> Injekt.get(): T = getByClass(T::class.java) as T
