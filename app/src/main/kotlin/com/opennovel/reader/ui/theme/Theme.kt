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

// Hibana brand palette — lamplight amber/gold on deep navy, from the lantern logo.
// 灯 lamplight warmth + 話 story: a story told by lamplight.
private val HibanaAmber = Color(0xFFF2A93B)   // lantern glow — primary
private val HibanaGoldSoft = Color(0xFFF5C77E) // soft candlelight
private val HibanaEmber = Color(0xFFE0552F)    // maple-leaf ember accent — tertiary
private val HibanaCream = Color(0xFFF3ECD9)    // warm paper cream — reading text
private val HibanaNavy = Color(0xFF0A0E18)     // deep night navy

private val LightColors = lightColorScheme(
    primary = Color(0xFFB4741A),
    onPrimary = Color.White,
    secondary = Color(0xFFC2571F),
    background = Color(0xFFFDF9F1),
    surface = Color(0xFFFFFDF8),
    onSurface = Color(0xFF211C12),
    surfaceVariant = Color(0xFFF1E7D4),
)

/** Primary Hibana experience: warm amber/gold accents on deep navy. */
private val DarkColors = darkColorScheme(
    primary = HibanaAmber,
    onPrimary = Color(0xFF2A1A05),
    secondary = HibanaGoldSoft,
    onSecondary = Color(0xFF2A1E08),
    tertiary = HibanaEmber,
    background = HibanaNavy,
    surface = Color(0xFF121623),
    onSurface = HibanaCream,
    surfaceVariant = Color(0xFF1E2333),
    onSurfaceVariant = HibanaGoldSoft,
    primaryContainer = Color(0xFF3A2C12),
    onPrimaryContainer = Color(0xFFFBEBCF),
)

/** Near-black scheme for OLED / distraction-free night reading. */
private val BlackColors = darkColorScheme(
    primary = HibanaAmber,
    secondary = HibanaGoldSoft,
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFEADFC7),
    surfaceVariant = Color(0xFF14110B),
    onSurfaceVariant = HibanaGoldSoft,
)

@Composable
fun OpenNovelTheme(
    themeMode: ThemeMode,
    // Off by default so the Hibana brand palette always shows, rather than being
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
