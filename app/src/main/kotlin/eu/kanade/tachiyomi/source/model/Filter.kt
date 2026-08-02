package eu.kanade.tachiyomi.source.model

/**
 * Tachiyomi extensions-lib filter hierarchy. Extensions subclass these to build
 * their search UI (e.g. `class Genre : Filter.CheckBox("Action")`), so the shapes
 * must match the extensions-lib exactly.
 */
sealed class Filter<T>(val name: String, var state: T) {
    open class Header(name: String) : Filter<Any?>(name, null)
    open class Separator(name: String = "") : Filter<Any?>(name, null)
    open class Select<V>(name: String, val values: Array<V>, state: Int = 0) : Filter<Int>(name, state)
    open class Text(name: String, state: String = "") : Filter<String>(name, state)
    open class CheckBox(name: String, state: Boolean = false) : Filter<Boolean>(name, state)

    open class TriState(name: String, state: Int = STATE_IGNORE) : Filter<Int>(name, state) {
        val isIgnored: Boolean get() = state == STATE_IGNORE
        val isIncluded: Boolean get() = state == STATE_INCLUDE
        val isExcluded: Boolean get() = state == STATE_EXCLUDE

        companion object {
            const val STATE_IGNORE = 0
            const val STATE_INCLUDE = 1
            const val STATE_EXCLUDE = 2
        }
    }

    open class Group<V>(name: String, val filters: List<V>) : Filter<List<V>>(name, filters)

    open class Sort(name: String, val values: Array<String>, state: Selection? = null) :
        Filter<Sort.Selection?>(name, state) {
        data class Selection(val index: Int, val ascending: Boolean)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Filter<*>
        return name == other.name && state == other.state
    }

    override fun hashCode(): Int = name.hashCode() + 31 * (state?.hashCode() ?: 0)
}

class FilterList(list: List<Filter<*>>) : List<Filter<*>> by list {
    constructor(vararg fs: Filter<*>) : this(fs.toList())
}
