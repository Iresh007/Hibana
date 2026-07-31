package com.opennovel.reader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.opennovel.reader.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color.White,
    secondary = Color(0xFF5C7CFA),
    background = Color(0xFFFAFAFB),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1B1E),
    surfaceVariant = Color(0xFFEDEEF2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF91A7FF),
    onPrimary = Color(0xFF11151F),
    secondary = Color(0xFF748FFC),
    background = Color(0xFF121316),
    surface = Color(0xFF1A1B1E),
    onSurface = Color(0xFFE7E8EA),
    surfaceVariant = Color(0xFF2A2C31),
)

/** Near-black scheme for OLED / distraction-free night reading. */
private val BlackColors = darkColorScheme(
    primary = Color(0xFF91A7FF),
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFD5D6D8),
    surfaceVariant = Color(0xFF161616),
)

@Composable
fun OpenNovelTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current

    val useDark = when (themeMode) {
        ThemeMode.LIGHT, ThemeMode.SEPIA -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
        ThemeMode.SYSTEM -> systemDark
    }

    val colorScheme = when {
        themeMode == ThemeMode.BLACK -> BlackColors
        themeMode == ThemeMode.SEPIA -> SepiaColors
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        useDark -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                colorScheme.background.luminance() > 0.5f
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
