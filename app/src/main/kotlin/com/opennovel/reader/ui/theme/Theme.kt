package com.opennovel.reader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.opennovel.reader.data.AppTheme
import com.opennovel.reader.data.ThemeMode

// Hibana brand palette — lamplight amber/gold on deep navy, from the lantern logo.
// 灯 lamplight warmth + 話 story: a story told by lamplight.
private val HibanaAmber = Color(0xFFF2A93B)   // lantern glow — primary
private val HibanaGoldSoft = Color(0xFFF5C77E) // soft candlelight
private val HibanaEmber = Color(0xFFE0552F)    // maple-leaf ember accent — tertiary
private val HibanaCream = Color(0xFFF3ECD9)    // warm paper cream — reading text
private val HibanaNavy = Color(0xFF0A0E18)     // deep night navy

private val LightColors = lightColorScheme(
    primary = Color(0xFF965C10),
    onPrimary = Color.White,
    secondary = Color(0xFFA84A18),
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

@Composable
fun OpenNovelTheme(
    themeMode: ThemeMode,
    // Off by default so the Hibana brand palette always shows, rather than being
    // replaced by Material You wallpaper colors on Android 12+.
    dynamicColor: Boolean = false,
    appTheme: AppTheme = AppTheme.DEFAULT,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current

    val useDark = when (themeMode) {
        ThemeMode.LIGHT, ThemeMode.SEPIA -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
        ThemeMode.SYSTEM -> systemDark
    }

    val wantDynamic = (dynamicColor || appTheme == AppTheme.MONET) &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        themeMode == ThemeMode.SEPIA -> SepiaColors.withTonalSurfaces(dark = false)
        // AMOLED keeps pure-black surfaces but still honours the chosen accent
        // theme (or the wallpaper colours when Material You is on).
        themeMode == ThemeMode.BLACK -> {
            val base = if (wantDynamic) dynamicDarkColorScheme(context)
            else DarkColors.withAppTheme(appTheme, dark = true)
            base.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceVariant = Color(0xFF121212),
            ).withTonalSurfaces(dark = true)
        }
        // Wallpaper colours already ship a complete tonal set.
        wantDynamic ->
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> (if (useDark) DarkColors else LightColors)
            .withAppTheme(appTheme, useDark)
            .withTonalSurfaces(useDark)
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
    ) {
        // Root surface: gives every Text/Icon outside a Scaffold the right content
        // colour (otherwise it falls back to black on the dark background).
        androidx.compose.material3.Surface(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            color = colorScheme.background,
            contentColor = colorScheme.onBackground,
            content = content,
        )
    }
}

/** Accent triple (primary, secondary, tertiary) for a named app theme. */
private fun ColorScheme.withAppTheme(theme: AppTheme, dark: Boolean): ColorScheme = when (theme) {
    AppTheme.DEFAULT, AppTheme.MONET -> this
    AppTheme.LAVENDER -> accents(dark, Color(0xFFB69DF8), Color(0xFF6F52B8), Color(0xFFD0BCFF), Color(0xFF8C6FD6), Color(0xFFF0A6CA), Color(0xFFB04C80))
    AppTheme.MIDNIGHT_DUSK -> accents(dark, Color(0xFFF02475), Color(0xFFB0124F), Color(0xFFF79CBB), Color(0xFFD9406E), Color(0xFFFF8C5A), Color(0xFFC25A2D))
    AppTheme.GREEN_APPLE -> accents(dark, Color(0xFF7BD66E), Color(0xFF2E7D32), Color(0xFFB5E8A8), Color(0xFF558B2F), Color(0xFFE6E36A), Color(0xFF9E9D24))
    AppTheme.STRAWBERRY -> accents(dark, Color(0xFFFF8A9B), Color(0xFFC62840), Color(0xFFFFB3BE), Color(0xFFE05468), Color(0xFFFFCB8A), Color(0xFFD08428))
    AppTheme.TAKO -> accents(dark, Color(0xFFF3B375), Color(0xFFA85F1A), Color(0xFFC8A6E8), Color(0xFF7B5AA8), Color(0xFF9BC7D8), Color(0xFF3F7F99))
    AppTheme.YIN_YANG -> accents(dark, Color(0xFFE6E6E6), Color(0xFF303030), Color(0xFFBDBDBD), Color(0xFF616161), Color(0xFF9E9E9E), Color(0xFF424242))
}

private fun ColorScheme.accents(
    dark: Boolean,
    pDark: Color, pLight: Color,
    sDark: Color, sLight: Color,
    tDark: Color, tLight: Color,
): ColorScheme {
    val p = if (dark) pDark else pLight
    val s = if (dark) sDark else sLight
    val t = if (dark) tDark else tLight
    fun on(c: Color) = if (c.luminance() > 0.5f) Color(0xFF1B1B1B) else Color.White
    return copy(
        primary = p, onPrimary = on(p),
        secondary = s, onSecondary = on(s),
        tertiary = t, onTertiary = on(t),
        primaryContainer = p.copy(alpha = 1f).let { if (dark) it.copy(red = it.red * .3f, green = it.green * .3f, blue = it.blue * .3f) else it.copy(red = 1f - (1f - it.red) * .25f, green = 1f - (1f - it.green) * .25f, blue = 1f - (1f - it.blue) * .25f) },
    )
}

/**
 * Fills in the Material 3 tonal tokens (surface containers, secondary container,
 * outlines) from the scheme's own surface/accent colours. Without this, components
 * such as the navigation bar, chips and cards fall back to M3's stock purple-grey
 * defaults, which clash with the amber/navy brand and read as "wrong colours".
 */
private fun ColorScheme.withTonalSurfaces(dark: Boolean): ColorScheme {
    val toward = if (dark) Color.White else Color.Black
    fun surf(amount: Float) = lerp(surface, toward, amount)
    return copy(
        surfaceContainerLowest = lerp(background, if (dark) Color.Black else Color.White, 0.5f),
        surfaceContainerLow = surf(if (dark) 0.03f else 0.015f),
        surfaceContainer = surf(if (dark) 0.06f else 0.03f),
        surfaceContainerHigh = surf(if (dark) 0.09f else 0.05f),
        surfaceContainerHighest = surf(if (dark) 0.12f else 0.07f),
        surfaceDim = lerp(background, Color.Black, if (dark) 0.2f else 0.06f),
        surfaceBright = surf(if (dark) 0.14f else 0f),
        // The selected-tab pill and filled chips: a clear tint of the accent.
        secondaryContainer = lerp(surface, secondary, if (dark) 0.30f else 0.22f),
        onSecondaryContainer = onSurface,
        tertiaryContainer = lerp(surface, tertiary, if (dark) 0.30f else 0.22f),
        onTertiaryContainer = onSurface,
        onSurfaceVariant = lerp(surface, onSurface, 0.78f),
        outline = lerp(surface, onSurface, 0.55f),
        outlineVariant = lerp(surface, onSurface, 0.22f),
    )
}
