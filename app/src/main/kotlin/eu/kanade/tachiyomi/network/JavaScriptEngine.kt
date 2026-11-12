package eu.kanade.tachiyomi.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Undefined

/**
 * Util for evaluating JavaScript in sources. Same public shape as Mihon's
 * `eu.kanade.tachiyomi.network.JavaScriptEngine`, but evaluated with Rhino (already a dependency
 * for LNReader plugins) instead of QuickJS, which is only published through JitPack.
 *
 * Differences from QuickJS to be aware of: Rhino implements ES6 partially, and numbers come back
 * as Double (QuickJS returns Int for integral values), so integral results are narrowed below.
 */
@Suppress("UNUSED", "UNCHECKED_CAST")
class JavaScriptEngine(@Suppress("UNUSED_PARAMETER") context: Context) {

    /**
     * Evaluate arbitrary JavaScript code and get the result as a primitive type (e.g., String, Int).
     *
     * @since extensions-lib 1.4
     */
    suspend fun <T> evaluate(script: String): T = withContext(Dispatchers.Default) {
        val cx = RhinoContext.enter()
        try {
            // Dalvik cannot load the bytecode Rhino would otherwise generate.
            cx.optimizationLevel = -1
            cx.languageVersion = RhinoContext.VERSION_ES6
            val scope = cx.initSafeStandardObjects()
            val result = cx.evaluateString(scope, script, "source", 1, null)
            normalize(result) as T
        } finally {
            RhinoContext.exit()
        }
    }

    private fun normalize(value: Any?): Any? = when (value) {
        null, is Undefined -> null
        is Double -> if (value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) value.toInt() else value
        is CharSequence -> value.toString()
        is Number, is Boolean -> value
        else -> RhinoContext.toString(value)
    }
}
