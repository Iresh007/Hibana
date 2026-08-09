package com.opennovel.reader.extension.lnreader

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * Rhino-backed host environment for LNReader JS plugins.
 *
 * Built plugins are minified **ES5** CommonJS modules: they call `require(...)`
 * and assign `exports.default`. Because the output is ES5, a pure-JVM engine is
 * enough — no QuickJS/NDK and no ABI splits. Rhino must run **interpreted**
 * (`optimizationLevel = -1`) on Android, since Dalvik cannot load the JVM
 * bytecode Rhino's compiler would emit.
 *
 * The host supplies the six modules real plugins import:
 *   `@libs/fetch` (fetchApi over OkHttp), `@libs/novelStatus`,
 *   `@libs/defaultCover`, `@libs/storage`, `cheerio` (Jsoup-backed), `dayjs`.
 *
 * Each plugin gets its own scope, so plugins cannot see or clobber each other.
 */
class RhinoJsRuntime(private val client: OkHttpClient) {

    /** Evaluates a plugin module and returns its `exports.default` object. */
    fun evaluatePlugin(source: String, pluginId: String): Scriptable? {
        val cx = enterContext()
        return try {
            val scope = cx.initSafeStandardObjects()
            installHostModules(cx, scope)

            // CommonJS wrapper: give the module its own `exports`/`module`.
            val exports = cx.newObject(scope)
            val module = cx.newObject(scope)
            ScriptableObject.putProperty(module, "exports", exports)
            ScriptableObject.putProperty(scope, "exports", exports)
            ScriptableObject.putProperty(scope, "module", module)

            cx.evaluateString(scope, source, pluginId, 1, null)

            val moduleExports = ScriptableObject.getProperty(module, "exports")
            val effective = (moduleExports as? Scriptable) ?: exports
            when (val def = ScriptableObject.getProperty(effective, "default")) {
                is Scriptable -> def
                else -> effective
            }
        } catch (t: Throwable) {
            null
        } finally {
            Context.exit()
        }
    }

    /** Calls a plugin method and returns the raw JS result. */
    fun callMethod(plugin: Scriptable, method: String, vararg args: Any?): Any? {
        val cx = enterContext()
        return try {
            val fn = ScriptableObject.getProperty(plugin, method) as? Function ?: return null
            val result = fn.call(cx, plugin, plugin, args.map { toJs(it) }.toTypedArray())
            awaitIfPromise(cx, plugin, result)
        } catch (t: Throwable) {
            null
        } finally {
            Context.exit()
        }
    }

    /**
     * Plugin methods are `async`, so results arrive as promises. Rhino has no
     * event loop; draining pending jobs resolves the already-completed chain that
     * our synchronous `fetchApi` produces.
     */
    private fun awaitIfPromise(cx: Context, scope: Scriptable, value: Any?): Any? {
        if (value !is Scriptable) return value
        val then = ScriptableObject.getProperty(value, "then")
        if (then !is Function) return value

        var resolved: Any? = null
        var settled = false
        val onFulfilled = object : BaseFunction() {
            override fun call(c: Context, s: Scriptable, t: Scriptable?, a: Array<out Any?>): Any? {
                resolved = a.firstOrNull(); settled = true; return Undefined.instance
            }
        }
        val onRejected = object : BaseFunction() {
            override fun call(c: Context, s: Scriptable, t: Scriptable?, a: Array<out Any?>): Any? {
                settled = true; return Undefined.instance
            }
        }
        then.call(cx, scope, value, arrayOf(onFulfilled, onRejected))
        // Nothing async is outstanding (fetch is synchronous), so one drain suffices.
        return if (settled) resolved else null
    }

    private fun enterContext(): Context = Context.enter().apply {
        // Mandatory on Android: Rhino cannot generate loadable bytecode for Dalvik.
        optimizationLevel = -1
        languageVersion = Context.VERSION_ES6
    }

    private fun toJs(value: Any?): Any? = value ?: Undefined.instance

    // --- host module registry ---

    private fun installHostModules(cx: Context, scope: Scriptable) {
        val modules = mutableMapOf<String, Any?>()

        modules["@libs/fetch"] = cx.newObject(scope).also { m ->
            ScriptableObject.putProperty(m, "fetchApi", fetchApiFunction(cx, scope))
            ScriptableObject.putProperty(m, "fetchText", fetchTextFunction(cx, scope))
        }
        modules["@libs/novelStatus"] = cx.newObject(scope).also { m ->
            val status = cx.newObject(scope)
            listOf(
                "Ongoing" to "Ongoing", "Completed" to "Completed",
                "Unknown" to "Unknown", "OnHiatus" to "OnHiatus",
                "Cancelled" to "Cancelled", "Licensed" to "Licensed",
                "PublishingFinished" to "PublishingFinished",
            ).forEach { (k, v) -> ScriptableObject.putProperty(status, k, v) }
            ScriptableObject.putProperty(m, "NovelStatus", status)
        }
        modules["@libs/defaultCover"] = cx.newObject(scope).also { m ->
            ScriptableObject.putProperty(m, "defaultCover", DEFAULT_COVER)
        }
        modules["@libs/storage"] = cx.newObject(scope).also { m ->
            ScriptableObject.putProperty(m, "storage", inMemoryStorage(cx, scope))
            ScriptableObject.putProperty(m, "localStorage", inMemoryStorage(cx, scope))
        }
        modules["cheerio"] = cx.newObject(scope).also { m ->
            ScriptableObject.putProperty(m, "load", cheerioLoadFunction())
        }
        modules["dayjs"] = dayjsFunction()

        val require = object : BaseFunction() {
            override fun call(c: Context, s: Scriptable, t: Scriptable?, args: Array<out Any?>): Any? {
                val name = args.firstOrNull() as? String ?: return Undefined.instance
                return modules[name] ?: c.newObject(s)
            }
        }
        ScriptableObject.putProperty(scope, "require", require)
        ScriptableObject.putProperty(scope, "console", consoleObject(cx, scope))
    }

    private fun cheerioLoadFunction(): Function = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any {
            val html = args.firstOrNull() as? String ?: ""
            return CheerioShim.load(html).asFunction(scope)
        }
    }

    /**
     * `fetchApi(url, init)` → a Response-like object. Deliberately synchronous:
     * plugin code always awaits it, and Rhino has no event loop to pump, so a
     * blocking call keeps the promise chain resolvable in one drain.
     */
    private fun fetchApiFunction(cx: Context, scope: Scriptable): Function = object : BaseFunction() {
        override fun call(c: Context, s: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any {
            val url = args.firstOrNull() as? String ?: return c.newObject(s)
            val init = args.getOrNull(1) as? Scriptable

            val builder = Request.Builder().url(url)
            var method = "GET"
            var body: String? = null
            if (init != null) {
                (ScriptableObject.getProperty(init, "method") as? String)?.let { method = it.uppercase() }
                (ScriptableObject.getProperty(init, "body") as? String)?.let { body = it }
                (ScriptableObject.getProperty(init, "headers") as? Scriptable)?.let { headers ->
                    headers.ids.forEach { id ->
                        val key = id as? String ?: return@forEach
                        val v = ScriptableObject.getProperty(headers, key)
                        if (v is String) builder.header(key, v)
                    }
                }
            }
            builder.header("User-Agent", USER_AGENT)
            when (method) {
                "POST", "PUT", "PATCH" -> builder.method(method, (body ?: "").toRequestBody())
                else -> builder.get()
            }

            val (ok, status, text) = runCatching {
                client.newCall(builder.build()).execute().use { resp ->
                    Triple(resp.isSuccessful, resp.code, resp.body?.string().orEmpty())
                }
            }.getOrElse { Triple(false, 0, "") }

            return responseObject(c, s, ok, status, text)
        }
    }

    private fun fetchTextFunction(cx: Context, scope: Scriptable): Function = object : BaseFunction() {
        override fun call(c: Context, s: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any {
            val url = args.firstOrNull() as? String ?: return ""
            return runCatching {
                client.newCall(
                    Request.Builder().url(url).header("User-Agent", USER_AGENT).build(),
                ).execute().use { it.body?.string().orEmpty() }
            }.getOrDefault("")
        }
    }

    private fun responseObject(cx: Context, scope: Scriptable, ok: Boolean, status: Int, text: String): Scriptable {
        val resp = cx.newObject(scope)
        ScriptableObject.putProperty(resp, "ok", ok)
        ScriptableObject.putProperty(resp, "status", status)
        ScriptableObject.putProperty(resp, "text", object : BaseFunction() {
            override fun call(c: Context, s: Scriptable, t: Scriptable?, a: Array<out Any?>) = text
        })
        ScriptableObject.putProperty(resp, "json", object : BaseFunction() {
            override fun call(c: Context, s: Scriptable, t: Scriptable?, a: Array<out Any?>): Any? =
                runCatching {
                    val json = ScriptableObject.getProperty(s, "JSON") as Scriptable
                    val parse = ScriptableObject.getProperty(json, "parse") as Function
                    parse.call(c, s, json, arrayOf(text))
                }.getOrElse { Undefined.instance }
        })
        return resp
    }

    private fun inMemoryStorage(cx: Context, scope: Scriptable): Scriptable {
        val store = HashMap<String, Any?>()
        val obj = cx.newObject(scope)
        ScriptableObject.putProperty(obj, "get", object : BaseFunction() {
            override fun call(c: Context, s: Scriptable, t: Scriptable?, a: Array<out Any?>): Any? =
                store[a.firstOrNull() as? String] ?: Undefined.instance
        })
        ScriptableObject.putProperty(obj, "set", object : BaseFunction() {
            override fun call(c: Context, s: Scriptable, t: Scriptable?, a: Array<out Any?>): Any? {
                val k = a.firstOrNull() as? String ?: return Undefined.instance
                store[k] = a.getOrNull(1)
                return Undefined.instance
            }
        })
        return obj
    }

    /** `dayjs(x)` — plugins only format/parse release dates, so pass the value through. */
    private fun dayjsFunction(): Function = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any {
            val value = args.firstOrNull()
            val obj = cx.newObject(scope)
            ScriptableObject.putProperty(obj, "format", object : BaseFunction() {
                override fun call(c: Context, s: Scriptable, t: Scriptable?, a: Array<out Any?>) =
                    value?.toString() ?: ""
            })
            ScriptableObject.putProperty(obj, "valueOf", object : BaseFunction() {
                override fun call(c: Context, s: Scriptable, t: Scriptable?, a: Array<out Any?>) =
                    System.currentTimeMillis()
            })
            return obj
        }
    }

    private fun consoleObject(cx: Context, scope: Scriptable): Scriptable {
        val console = cx.newObject(scope)
        val noop = object : BaseFunction() {
            override fun call(c: Context, s: Scriptable, t: Scriptable?, a: Array<out Any?>): Any =
                Undefined.instance
        }
        listOf("log", "warn", "error", "info", "debug").forEach {
            ScriptableObject.putProperty(console, it, noop)
        }
        return console
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        const val DEFAULT_COVER =
            "https://raw.githubusercontent.com/lnreader/lnreader-plugins/main/public/static/coverNotAvailable.webp"
    }
}

/** Reads a property off a JS object as a Kotlin String, or null. */
fun Scriptable.stringOrNull(name: String): String? =
    when (val v = ScriptableObject.getProperty(this, name)) {
        is String -> v.ifBlank { null }
        is NativeObject -> null
        Scriptable.NOT_FOUND, is Undefined, null -> null
        else -> v.toString().ifBlank { null }
    }
