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

// Yomiku brand palette — violet → blue on deep navy, from the logo.
private val YomikuVioletSoft = Color(0xFFA78BFA)
private val YomikuBlue = Color(0xFF4EA8FF)
private val YomikuLavender = Color(0xFFC4B5FD)
private val YomikuNavy = Color(0xFF050913)

private val LightColors = lightColorScheme(
    primary = Color(0xFF7C3AED),
    onPrimary = Color.White,
    secondary = Color(0xFF2563EB),
    background = Color(0xFFFAFAFF),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17141F),
    surfaceVariant = Color(0xFFECEAF5),
)

/** Primary Yomiku experience: violet/blue accents on deep navy. */
private val DarkColors = darkColorScheme(
    primary = YomikuVioletSoft,
    onPrimary = Color(0xFF1A1030),
    secondary = YomikuBlue,
    onSecondary = Color(0xFF06121F),
    tertiary = YomikuLavender,
    background = YomikuNavy,
    surface = Color(0xFF12101F),
    onSurface = Color(0xFFE7E4F5),
    surfaceVariant = Color(0xFF1E1B2E),
    onSurfaceVariant = YomikuLavender,
    primaryContainer = Color(0xFF2A2440),
    onPrimaryContainer = Color(0xFFEDE9FF),
)

/** Near-black scheme for OLED / distraction-free night reading. */
private val BlackColors = darkColorScheme(
    primary = YomikuVioletSoft,
    secondary = YomikuBlue,
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFDAD6EA),
    surfaceVariant = Color(0xFF141018),
    onSurfaceVariant = YomikuLavender,
)

@Composable
fun OpenNovelTheme(
    themeMode: ThemeMode,
    // Off by default so the Yomiku brand palette always shows, rather than being
    // replaced by Material You wallpaper colors on Android 12+.
    dynamicColor: Boolean = false,
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
