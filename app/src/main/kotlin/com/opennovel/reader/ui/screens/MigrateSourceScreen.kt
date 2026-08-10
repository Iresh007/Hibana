package com.opennovel.reader.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider

/**
 * Migrates library entries belonging to one source.
 *
 * Hands straight off to [MigrationScreen] with the source id so the wizard opens
 * on entry selection — the flow is otherwise identical whether migration started
 * from a source or from a library selection, which keeps one code path and one
 * set of behaviours.
 */
@Composable
fun MigrateSourceScreen(
    sourceId: Long,
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
) {
    MigrationScreen(
        novelIds = emptyList(),
        factory = factory,
        onBack = onBack,
        sourceId = sourceId,
    )
}
