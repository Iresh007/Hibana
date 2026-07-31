package com.opennovel.reader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Book-cover aspect ratio (2:3), the near-universal convention. */
fun Modifier.aspectRatioCover(): Modifier = this.aspectRatio(2f / 3f)

/** Clickable that guarantees the ≥48dp minimum touch target (a11y). */
fun Modifier.clickableMinTouch(onClick: () -> Unit): Modifier =
    this.heightIn(min = 48.dp).clickable(onClick = onClick)
