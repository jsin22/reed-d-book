package dev.reedd.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

// Teal-and-ink: quiet enough to sit behind a page of text for an hour.
private val Teal = Color(0xFF1B4B54)
private val TealLight = Color(0xFF7FD4C1)
private val Sand = Color(0xFFF2F6F5)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = TealLight,
    onPrimaryContainer = Color(0xFF06262C),
    secondary = Color(0xFF4A635F),
    background = Sand,
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF06262C),
    primaryContainer = Color(0xFF2A5F68),
    onPrimaryContainer = Sand,
    secondary = Color(0xFFB1CCC7),
    background = Color(0xFF0F1416),
    surface = Color(0xFF161C1E),
)

/**
 * The e-ink palette: a warm grey page with near-black text.
 *
 * Not white, and not pure black text — the point of an e-ink screen is low contrast
 * between paper and ink compared with a backlit display, which is what makes it
 * restful for an hour of reading. These same two values are handed to Readium as
 * the page background and text colour, so the reader's chrome and the page it
 * frames are the same shade rather than a grey page in a white window.
 */
object PaperPalette {
    val Page = Color(0xFFD8D4C8)
    val Ink = Color(0xFF1B1B18)
    val Muted = Color(0xFF5A574E)
    val Edge = Color(0xFFC6C1B4)

    val pageArgb: Int get() = Page.toArgb()
    val inkArgb: Int get() = Ink.toArgb()
}

/** Material colours matching [PaperPalette], for the reader's bars and sheets. */
fun paperColorScheme() = lightColorScheme(
    primary = Color(0xFF3A4A44),
    onPrimary = PaperPalette.Page,
    primaryContainer = PaperPalette.Edge,
    onPrimaryContainer = PaperPalette.Ink,
    secondary = PaperPalette.Muted,
    background = PaperPalette.Page,
    onBackground = PaperPalette.Ink,
    surface = PaperPalette.Page,
    onSurface = PaperPalette.Ink,
    surfaceVariant = PaperPalette.Edge,
    onSurfaceVariant = PaperPalette.Muted,
    outline = PaperPalette.Muted,
)

@Composable
fun ReeddTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
