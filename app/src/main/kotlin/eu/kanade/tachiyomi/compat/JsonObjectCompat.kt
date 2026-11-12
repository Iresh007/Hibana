// Same shape as mihonapp/mihon v0.21.3 core/common JsonObject.kt (Apache License 2.0); the copied
// source-api files import `mihon.core.common.extensions.EMPTY`.
package mihon.core.common.extensions

import kotlinx.serialization.json.JsonObject

val JsonObjectEmpty = JsonObject(emptyMap())

val JsonObject.Companion.EMPTY: JsonObject
    inline get() = JsonObjectEmpty
