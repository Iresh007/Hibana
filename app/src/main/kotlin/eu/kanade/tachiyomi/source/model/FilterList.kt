// Copied unmodified from mihonapp/mihon v0.21.3 (Apache License 2.0, https://github.com/mihonapp/mihon).
package eu.kanade.tachiyomi.source.model

import androidx.compose.runtime.Stable

@Stable
data class FilterList(val list: List<Filter<*>>) : List<Filter<*>> by list {

    constructor(vararg fs: Filter<*>) : this(if (fs.isNotEmpty()) fs.asList() else emptyList())

    override fun equals(other: Any?): Boolean {
        return false
    }

    override fun hashCode(): Int {
        return list.hashCode()
    }
}
