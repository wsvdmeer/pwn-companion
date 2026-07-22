package com.wsvdmeer.pwncompanion.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.wsvdmeer.pwncompanion.R

/**
 * Share Tech Mono has a smaller x-height than the system font, so after switching the
 * whole app to it every `sp` size read too small. Rather than bump dozens of hardcoded
 * sizes, scale ALL text up by this factor in one place (see [PwnCompanionTheme]). Only
 * `sp` (text) scales — `dp` layout is untouched — and it multiplies onto the user's own
 * system font scale, so accessibility settings are still respected.
 */
private const val TERMINAL_FONT_SCALE = 1.2f

// ── PwnCompanion phosphor-terminal palette ────────────────────────────────────
// Fixed monochrome-green CRT theme. Dynamic (wallpaper) color is OFF on purpose:
// a Wi-Fi attack tool should look like a terminal, not a pastel Material app.
// Everything is a shade of phosphor green on near-black; red is reserved for
// destructive/error states only.

private val PhosphorBright = Color(0xFF3DFF6E)  // primary  — active text, prompts, focus
private val PhosphorDim    = Color(0xFF21A848)  // secondary — labels, secondary data
private val PhosphorPale   = Color(0xFF8BFFA8)  // tertiary  — highlights
private val SignalRed      = Color(0xFFFF4452)  // error/destructive only

// Monochrome base + green highlights (per the CIPHER reference): body text is
// white/grey, and green (primary) is reserved for labels, prompts, active values
// and "online"/AI accents — not the whole UI.
private val CrtBlack    = Color(0xFF030504)  // app background — near-black
private val PanelBlack   = Color(0xFF0A0C0B)  // surface / panels
private val PanelRaised  = Color(0xFF141816)  // surfaceVariant
private val TerminalText = Color(0xFFE8E8E8)  // onSurface — off-white (primary text)
private val DimText      = Color(0xFF8A8F8C)  // onSurfaceVariant — neutral grey (labels/secondary)
private val GridLine     = Color(0xFF2A312D)  // outline — neutral dim border/grid

private val HackerColorScheme = darkColorScheme(
    primary             = PhosphorBright,
    onPrimary           = Color.Black,
    primaryContainer    = Color(0xFF06301A),
    onPrimaryContainer  = PhosphorBright,
    secondary           = PhosphorDim,
    onSecondary         = Color.Black,
    secondaryContainer  = Color(0xFF062612),
    onSecondaryContainer = PhosphorPale,
    tertiary            = PhosphorPale,
    onTertiary          = Color.Black,
    tertiaryContainer   = Color(0xFF06301A),
    onTertiaryContainer = PhosphorPale,
    background          = CrtBlack,
    onBackground        = TerminalText,
    surface             = PanelBlack,
    onSurface           = TerminalText,
    surfaceVariant      = PanelRaised,
    onSurfaceVariant    = DimText,
    error               = SignalRed,
    onError             = Color.Black,
    errorContainer      = Color(0xFF2E0408),
    onErrorContainer    = SignalRed,
    outline             = GridLine,
    outlineVariant      = PanelRaised,
    scrim               = Color.Black,
)

/** Flat, near-square corners for the terminal "box" look (no soft Material rounding). */
val TerminalBoxShape = RoundedCornerShape(2.dp)

/**
 * Subtle CRT scanline overlay — faint horizontal dark lines drawn over content.
 * Apply to full-screen surfaces/panels for the phosphor-monitor feel.
 */
fun Modifier.scanlines(
    lineColor: Color = Color.Black.copy(alpha = 0.10f),
    gap: Float = 4f,
): Modifier = drawWithContent {
    drawContent()
    var y = 0f
    while (y < size.height) {
        drawRect(
            color = lineColor,
            topLeft = androidx.compose.ui.geometry.Offset(0f, y),
            size = androidx.compose.ui.geometry.Size(size.width, 1f),
        )
        y += gap
    }
}

// ── Monospace "terminal" typography ───────────────────────────────────────────
// Bundled terminal typeface (Share Tech Mono, OFL) so numerics (CH##, dBm, %,
// IPs, ports) and AI text read like a real console — not the system mono.
// Swap the res/font/terminal_mono.ttf file to change the look (e.g. VT323 for CRT).
private val Mono = FontFamily(Font(R.font.terminal_mono))

/** Public handle to the bundled terminal face, for the few call sites that must set it
 *  explicitly (e.g. BasicTextField styles that don't inherit LocalTextStyle). */
val TerminalMono = Mono

private val HackerTypography: Typography = Typography().let { base ->
    Typography(
        displayLarge   = base.displayLarge.copy(fontFamily = Mono),
        displayMedium  = base.displayMedium.copy(fontFamily = Mono),
        displaySmall   = base.displaySmall.copy(fontFamily = Mono),
        headlineLarge  = base.headlineLarge.copy(fontFamily = Mono),
        headlineMedium = base.headlineMedium.copy(fontFamily = Mono),
        headlineSmall  = base.headlineSmall.copy(fontFamily = Mono),
        titleLarge     = base.titleLarge.copy(fontFamily = Mono),
        titleMedium    = base.titleMedium.copy(fontFamily = Mono),
        titleSmall     = base.titleSmall.copy(fontFamily = Mono),
        bodyLarge      = base.bodyLarge.copy(fontFamily = Mono),
        bodyMedium     = base.bodyMedium.copy(fontFamily = Mono),
        bodySmall      = base.bodySmall.copy(fontFamily = Mono),
        labelLarge     = base.labelLarge.copy(fontFamily = Mono),
        labelMedium    = base.labelMedium.copy(fontFamily = Mono),
        labelSmall     = base.labelSmall.copy(fontFamily = Mono),
    )
}

/**
 * PwnCompanion theme — fixed dark neon-terminal look.
 *
 * Dynamic color is deliberately NOT supported: the hacker aesthetic is part of
 * the product identity, so it must not be overridden by the device wallpaper.
 */
@Composable
fun PwnCompanionTheme(
    content: @Composable () -> Unit
) {
    // Scale all text up to compensate for Share Tech Mono's small x-height, on top of
    // the user's own system font scale.
    val base = LocalDensity.current
    val scaled = Density(density = base.density, fontScale = base.fontScale * TERMINAL_FONT_SCALE)

    MaterialTheme(
        colorScheme = HackerColorScheme,
        typography = HackerTypography,
    ) {
        CompositionLocalProvider(LocalDensity provides scaled) {
            // Make the WHOLE app monospace by default. Most console Text() calls pass only
            // size/color and no fontFamily, so without this they fell back to system
            // sans-serif — which also broke every ASCII block-bar and padEnd() column that
            // assumes fixed-width cells. One provider fixes alignment everywhere; call sites
            // that set their own fontFamily still win.
            ProvideTextStyle(TextStyle(fontFamily = Mono, color = TerminalText), content)
        }
    }
}
