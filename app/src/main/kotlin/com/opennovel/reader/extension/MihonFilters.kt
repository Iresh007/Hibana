package com.opennovel.reader.extension

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * Read-only description of a Mihon source's search filters, flattened for rendering. Each editable
 * item keeps its native [Filter] (the source owns the semantics and reads `state` itself when
 * searching), so a UI mutates `native.state` and passes the same [FilterList] to
 * [MihonSourceAdapter.searchNovels]. No Compose UI lives here.
 */
sealed interface SourceFilterItem {
    val name: String

    data class Header(override val name: String) : SourceFilterItem

    data class Separator(override val name: String) : SourceFilterItem

    data class Text(
        override val name: String,
        val value: String,
        val native: Filter<String>,
    ) : SourceFilterItem

    data class CheckBox(
        override val name: String,
        val value: Boolean,
        val native: Filter<Boolean>,
    ) : SourceFilterItem

    /** [state]: 0 = ignored, 1 = included, 2 = excluded. */
    data class TriState(
        override val name: String,
        val state: Int,
        val native: Filter<Int>,
    ) : SourceFilterItem

    data class Select(
        override val name: String,
        val options: List<String>,
        val selected: Int,
        val native: Filter<Int>,
    ) : SourceFilterItem

    data class Sort(
        override val name: String,
        val options: List<String>,
        val index: Int,
        val ascending: Boolean,
        val native: Filter<Filter.Sort.Selection?>,
    ) : SourceFilterItem

    data class Group(
        override val name: String,
        val children: List<SourceFilterItem>,
    ) : SourceFilterItem
}

/** Flattens a source's native [FilterList] into [SourceFilterItem]s. */
fun FilterList.describe(): List<SourceFilterItem> = mapNotNull { describeFilter(it) }

private fun describeFilter(filter: Filter<*>): SourceFilterItem? = when (filter) {
    is Filter.Header -> SourceFilterItem.Header(filter.name)
    is Filter.Separator -> SourceFilterItem.Separator(filter.name)
    is Filter.Text -> SourceFilterItem.Text(filter.name, filter.state, filter)
    is Filter.CheckBox -> SourceFilterItem.CheckBox(filter.name, filter.state, filter)
    is Filter.TriState -> SourceFilterItem.TriState(filter.name, filter.state, filter)
    is Filter.Select<*> -> SourceFilterItem.Select(
        name = filter.name,
        options = filter.values.map { it.toString() },
        selected = filter.state,
        native = filter,
    )
    is Filter.Sort -> SourceFilterItem.Sort(
        name = filter.name,
        options = filter.values.toList(),
        index = filter.state?.index ?: -1,
        ascending = filter.state?.ascending ?: true,
        native = filter,
    )
    is Filter.Group<*> -> SourceFilterItem.Group(
        name = filter.name,
        children = filter.state.mapNotNull { (it as? Filter<*>)?.let(::describeFilter) },
    )
}
