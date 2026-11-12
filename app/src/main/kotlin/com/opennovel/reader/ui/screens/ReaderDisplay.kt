package com.opennovel.reader.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.opennovel.reader.data.ColorFilterMode
import com.opennovel.reader.data.ImageScaleType
import com.opennovel.reader.data.ReaderOrientation
import com.opennovel.reader.data.ReaderSettings

/**
 * Reader display preferences that act on pixels or on the window: brightness,
 * colour filters, grayscale/invert, orientation and image scaling. Kept apart
 * from the reader's layout code so each preference has one obvious place.
 */

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Grayscale and/or inverted colours for page images, or null when neither is on. */
fun pageColorFilter(s: ReaderSettings): ColorFilter? {
    if (!s.grayscale && !s.invertedColors) return null
    val m = ColorMatrix()
    if (s.grayscale) m.setToSaturation(0f)
    if (s.invertedColors) {
        val invert = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        m *= invert
    }
    return ColorFilter.colorMatrix(m)
}

fun ImageScaleType.toContentScale(): ContentScale = when (this) {
    ImageScaleType.FIT_SCREEN, ImageScaleType.SMART_FIT -> ContentScale.Fit
    ImageScaleType.STRETCH -> ContentScale.FillBounds
    ImageScaleType.FIT_WIDTH -> ContentScale.FillWidth
    ImageScaleType.FIT_HEIGHT -> ContentScale.FillHeight
    ImageScaleType.ORIGINAL -> ContentScale.None
}

private fun ColorFilterMode.toBlendMode(): BlendMode = when (this) {
    ColorFilterMode.OVER -> BlendMode.SrcOver
    ColorFilterMode.MULTIPLY -> BlendMode.Multiply
    ColorFilterMode.SCREEN -> BlendMode.Screen
    ColorFilterMode.OVERLAY -> BlendMode.Overlay
    ColorFilterMode.LIGHTEN -> BlendMode.Lighten
    ColorFilterMode.DARKEN -> BlendMode.Darken
}

/**
 * Draws the colour tint and below-minimum dimming over everything beneath it.
 * Rendered into its own layer so blend modes affect only the reader content,
 * not the rest of the window.
 */
fun Modifier.readerTint(s: ReaderSettings): Modifier {
    val tint = if (s.colorFilter) Color(s.colorFilterValue) else null
    val dim = if (s.customBrightness && s.customBrightnessValue < 0f) {
        (-s.customBrightnessValue).coerceIn(0f, 0.75f)
    } else 0f
    if (tint == null && dim == 0f) return this
    val mode = s.colorFilterMode.toBlendMode()
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (tint != null) drawRect(tint, blendMode = mode)
            if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
        }
}

/** Applies window brightness and screen orientation while the reader is open. */
@Composable
fun ReaderWindowEffects(s: ReaderSettings) {
    val context = LocalContext.current

    DisposableEffect(s.customBrightness, s.customBrightnessValue) {
        val window = context.findActivity()?.window
        if (window != null && s.customBrightness && s.customBrightnessValue >= 0f) {
            val lp = window.attributes
            lp.screenBrightness = s.customBrightnessValue.coerceIn(0.01f, 1f)
            window.attributes = lp
        }
        onDispose {
            window?.let {
                val lp = it.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                it.attributes = lp
            }
        }
    }

    DisposableEffect(s.readerOrientation) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = when (s.readerOrientation) {
            ReaderOrientation.FREE -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ReaderOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ReaderOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ReaderOrientation.LOCKED -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }
        onDispose { previous?.let { activity.requestedOrientation = it } }
    }
}
