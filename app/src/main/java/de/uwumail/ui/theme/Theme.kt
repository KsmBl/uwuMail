package de.uwumail.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import de.uwumail.core.AppTheme

private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF7D5260)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8)
)

/**
 * Catppuccin Mocha.
 *
 * The palette is fixed rather than derived, so the surfaces are mapped by hand
 * onto Material's ladder: base is the page, mantle and crust go under it, and
 * the three surfaces climb above it. Anything left to Material to work out
 * would be tinted from the primary and stop being Mocha.
 */
private val MochaColors = darkColorScheme(
    primary = Color(0xFFCBA6F7),
    onPrimary = Color(0xFF1E1E2E),
    primaryContainer = Color(0xFF45475A),
    onPrimaryContainer = Color(0xFFCDD6F4),
    inversePrimary = Color(0xFF8839EF),

    secondary = Color(0xFF89B4FA),
    onSecondary = Color(0xFF1E1E2E),
    secondaryContainer = Color(0xFF313244),
    onSecondaryContainer = Color(0xFFCDD6F4),

    tertiary = Color(0xFFF5C2E7),
    onTertiary = Color(0xFF1E1E2E),
    tertiaryContainer = Color(0xFF45475A),
    onTertiaryContainer = Color(0xFFF5C2E7),

    background = Color(0xFF1E1E2E),
    onBackground = Color(0xFFCDD6F4),
    surface = Color(0xFF1E1E2E),
    onSurface = Color(0xFFCDD6F4),
    surfaceVariant = Color(0xFF313244),
    onSurfaceVariant = Color(0xFFBAC2DE),
    surfaceTint = Color(0xFFCBA6F7),
    inverseSurface = Color(0xFFCDD6F4),
    inverseOnSurface = Color(0xFF1E1E2E),

    surfaceContainerLowest = Color(0xFF11111B),
    surfaceContainerLow = Color(0xFF181825),
    surfaceContainer = Color(0xFF1E1E2E),
    surfaceContainerHigh = Color(0xFF313244),
    surfaceContainerHighest = Color(0xFF45475A),

    error = Color(0xFFF38BA8),
    onError = Color(0xFF1E1E2E),
    errorContainer = Color(0xFF533B4A),
    onErrorContainer = Color(0xFFF38BA8),

    outline = Color(0xFF6C7086),
    outlineVariant = Color(0xFF45475A),
    scrim = Color(0xFF11111B)
)

/**
 * Cute Femboy: light blue on white, with just enough black to read by.
 *
 * Fixed by hand for the same reason Mocha is — left to Material the surfaces
 * would be tinted from the primary and the white would stop being white. The
 * blue climbs very slightly as the surfaces stack, so a sticky day heading and
 * a selected row separate from the page without any of them going grey; the
 * black is kept for text, outlines and the one dark accent rather than being
 * spread about as shadow.
 */
private val FemboyColors = lightColorScheme(
    primary = Color(0xFF3F9FD8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCBE9F9),
    onPrimaryContainer = Color(0xFF06222F),
    inversePrimary = Color(0xFF9BD8F5),

    secondary = Color(0xFF5FB3DE),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCF1FB),
    onSecondaryContainer = Color(0xFF06222F),

    // The bit of black, used as an accent rather than as a background.
    tertiary = Color(0xFF23262B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDFE4E8),
    onTertiaryContainer = Color(0xFF16181C),

    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF16181C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16181C),
    surfaceVariant = Color(0xFFE7F3FA),
    onSurfaceVariant = Color(0xFF41525C),
    surfaceTint = Color(0xFF3F9FD8),
    inverseSurface = Color(0xFF1A2A33),
    inverseOnSurface = Color(0xFFEAF5FB),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7FCFE),
    surfaceContainer = Color(0xFFF0F8FC),
    surfaceContainerHigh = Color(0xFFE7F3FA),
    surfaceContainerHighest = Color(0xFFDCEDF7),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    outline = Color(0xFF74838D),
    outlineVariant = Color(0xFFC5DBE7),
    scrim = Color(0xFF000000)
)

/**
 * Applies the chosen theme.
 *
 * Material You's dynamic colour is only used for [AppTheme.SYSTEM]: picking a
 * theme by name and then having the wallpaper override it would make the choice
 * meaningless.
 */
@Composable
fun UwuMailTheme(
    theme: AppTheme = AppTheme.SYSTEM,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val colors = when (theme) {
        AppTheme.SYSTEM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (systemDark) DarkColors else LightColors
            }
        AppTheme.LIGHT -> LightColors
        AppTheme.DARK -> DarkColors
        AppTheme.MOCHA -> MochaColors
        AppTheme.FEMBOY -> FemboyColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
