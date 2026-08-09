package com.opennovel.reader.extension.lnreader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * Minimal `cheerio` implementation backed by Jsoup, exposed to Rhino.
 *
 * LNReader plugins `require('cheerio')` and use `load(html)` to get a jQuery-like
 * `$`. Rather than run cheerio's own JS (which needs Node builtins and modern
 * syntax Rhino lacks), this maps the subset plugins actually use onto Jsoup:
 *
 *   $(selector) / $(element) / $(element).find(sel)
 *   .each(fn) .map(fn) .text() .html() .attr(name) .first() .last() .eq(i)
 *   .parent() .children() .next() .prev() .remove() .length .toArray() .get()
 *
 * Selectors are CSS, which Jsoup speaks natively. Anything unsupported returns an
 * empty selection rather than throwing, so one unusual plugin degrades to "no
 * results" instead of taking down the host.
 */
class CheerioShim(private val document: Document) {

    /** The `$` function handed to plugin code. */
    fun asFunction(scope: Scriptable): Function = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any {
            val arg = args.firstOrNull()
            val elements: List<Element> = when {
                arg == null || arg is Undefined -> emptyList()
                arg is String -> runCatching { document.select(arg).toList() }.getOrDefault(emptyList())
                arg is Selection -> arg.elements
                arg is NodeWrapper -> listOf(arg.element)
                else -> emptyList()
            }
            return Selection(elements, scope)
        }
    }

    companion object {
        fun load(html: String, baseUri: String = ""): CheerioShim =
            CheerioShim(Jsoup.parse(html, baseUri))
    }
}

/** Wraps a single Jsoup [Element] so it can be passed back into `$()`. */
class NodeWrapper(val element: Element) : ScriptableObject() {
    override fun getClassName(): String = "CheerioNode"
    override fun get(name: String, start: Scriptable): Any = when (name) {
        "name" -> element.tagName()
        "attribs" -> element.attributes().associate { it.key to it.value }
        else -> NOT_FOUND
    }
}

/**
 * A jQuery-style selection over Jsoup elements. Methods mirror cheerio's naming
 * and chaining so plugin code runs unmodified.
 */
class Selection(
    val elements: List<Element>,
    private val parentScope: Scriptable?,
) : ScriptableObject() {

    override fun getClassName(): String = "CheerioSelection"

    private fun sel(list: List<Element>) = Selection(list, parentScope)

    override fun get(name: String, start: Scriptable): Any {
        return when (name) {
            "length" -> elements.size
            "text" -> fn { _, _ -> elements.joinToString("") { it.text() } }
            "html" -> fn { _, _ -> elements.firstOrNull()?.html() ?: "" }
            "attr" -> fn { args, _ ->
                val key = args.firstOrNull() as? String ?: return@fn Undefined.instance
                elements.firstOrNull()?.let { el ->
                    if (el.hasAttr(key)) el.attr(key) else Undefined.instance
                } ?: Undefined.instance
            }
            "find" -> fn { args, _ ->
                val q = args.firstOrNull() as? String ?: return@fn sel(emptyList())
                sel(elements.flatMap { runCatching { it.select(q).toList() }.getOrDefault(emptyList()) })
            }
            "first" -> fn { _, _ -> sel(elements.take(1)) }
            "last" -> fn { _, _ -> sel(elements.takeLast(1)) }
            "eq" -> fn { args, _ ->
                val i = (args.firstOrNull() as? Number)?.toInt() ?: 0
                val idx = if (i < 0) elements.size + i else i
                sel(listOfNotNull(elements.getOrNull(idx)))
            }
            "parent" -> fn { _, _ -> sel(elements.mapNotNull { it.parent() }) }
            "children" -> fn { _, _ -> sel(elements.flatMap { it.children().toList() }) }
            "next" -> fn { _, _ -> sel(elements.mapNotNull { it.nextElementSibling() }) }
            "prev" -> fn { _, _ -> sel(elements.mapNotNull { it.previousElementSibling() }) }
            "remove" -> fn { _, _ -> elements.forEach { it.remove() }; sel(emptyList()) }
            "toArray", "get" -> fn { _, cx -> toJsArray(cx) }
            "each" -> fn { args, cx ->
                val cb = args.firstOrNull() as? Function ?: return@fn this
                elements.forEachIndexed { index, el ->
                    val scope = parentScope ?: return@forEachIndexed
                    runCatching {
                        cb.call(cx, scope, scope, arrayOf(index, NodeWrapper(el)))
                    }
                }
                this
            }
            "map" -> fn { args, cx ->
                val cb = args.firstOrNull() as? Function ?: return@fn sel(emptyList())
                val scope = parentScope
                val mapped = elements.mapIndexedNotNull { index, el ->
                    if (scope == null) null
                    else runCatching { cb.call(cx, scope, scope, arrayOf(index, NodeWrapper(el))) }.getOrNull()
                }
                cx.newArray(parentScope ?: this, mapped.toTypedArray())
            }
            else -> NOT_FOUND
        }
    }

    private fun toJsArray(cx: Context): Any {
        val wrapped = elements.map { NodeWrapper(it) }.toTypedArray<Any>()
        return cx.newArray(parentScope ?: this, wrapped)
    }

    /** Builds a Rhino-callable function from a Kotlin lambda. */
    private fun fn(body: (Array<out Any?>, Context) -> Any?): Function = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? =
            body(args, cx)
    }
}

/** Converts a JS array back to a Kotlin list for marshalling results out. */
fun NativeArray.toKotlinList(): List<Any?> = (0 until size).map { get(it, this) }
