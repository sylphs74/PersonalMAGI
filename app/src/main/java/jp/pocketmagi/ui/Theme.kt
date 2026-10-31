package jp.pocketmagi.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** One dark palette. Roles keep the names the screens already use. */
data class Palette(
    val id: String,
    val label: String,
    val bg: Color,
    val panel: Color,
    val accent: Color,      // main UI colour (frames, titles)
    val accentDim: Color,   // borders, secondary
    val approve: Color,
    val reject: Color,
    val pending: Color,
    val text: Color,
    val textDim: Color,
    val eye: Color,         // EYE / media highlights
    val inputBar: Color,
)

object Palettes {
    val Magi = Palette(
        id = "magi", label = "MAGI（標準）",
        bg = Color(0xFF070707), panel = Color(0xFF111111),
        accent = Color(0xFFFF9A1F), accentDim = Color(0xFF7A4A10),
        approve = Color(0xFF2BD67B), reject = Color(0xFFFF3D3D), pending = Color(0xFFFFC94A),
        text = Color(0xFFF2E6D0), textDim = Color(0xFF9A8F7C),
        eye = Color(0xFF4FD8E8), inputBar = Color(0xFF0E0E0E),
    )

    /** Tokyo Night (night variant) inspired. */
    val TokyoNight = Palette(
        id = "tokyonight", label = "Tokyo Night",
        bg = Color(0xFF1A1B26), panel = Color(0xFF24283B),
        accent = Color(0xFF7AA2F7), accentDim = Color(0xFF3B4261),
        approve = Color(0xFF9ECE6A), reject = Color(0xFFF7768E), pending = Color(0xFFE0AF68),
        text = Color(0xFFC0CAF5), textDim = Color(0xFF565F89),
        eye = Color(0xFFBB9AF7), inputBar = Color(0xFF16161E),
    )

    /** NERV-style: black, warning red-orange, terminal green. */
    val Nerv = Palette(
        id = "nerv", label = "NERV",
        bg = Color(0xFF000000), panel = Color(0xFF0B0B0B),
        accent = Color(0xFFE8431F), accentDim = Color(0xFF6A1C0C),
        approve = Color(0xFF39FF6A), reject = Color(0xFFFF1A1A), pending = Color(0xFFFFB000),
        text = Color(0xFFF5F0E8), textDim = Color(0xFF8C8073),
        eye = Color(0xFF39FF6A), inputBar = Color(0xFF050505),
    )

    val all = listOf(Magi, TokyoNight, Nerv)

    /** Built-in or user palette (made, imported from a file or from a VS Code theme). */
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: CustomPalettes.list.firstOrNull { it.id == id } ?: Magi
}

/**
 * Screens read colours through this object; it is backed by snapshot state,
 * so switching the theme recomposes everything that reads a colour.
 */
object MagiColors {
    var palette by mutableStateOf(Palettes.Magi)

    val Bg get() = palette.bg
    val Panel get() = palette.panel
    val Amber get() = palette.accent
    val AmberDim get() = palette.accentDim
    val Approve get() = palette.approve
    val Reject get() = palette.reject
    val Pending get() = palette.pending
    val Text get() = palette.text
    val TextDim get() = palette.textDim
    val Cyan get() = palette.eye
    val InputBar get() = palette.inputBar
}

/** Every text style carries the chosen fonts, so no part of the app falls back to the system font. */
private fun typography(body: FontFamily, head: FontFamily, custom: Boolean): Typography {
    val d = Typography()
    val t = Typography(
        displayLarge = d.displayLarge.copy(fontFamily = head),
        displayMedium = d.displayMedium.copy(fontFamily = head),
        displaySmall = d.displaySmall.copy(fontFamily = head),
        headlineLarge = TextStyle(fontFamily = head, fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = 6.sp),
        headlineMedium = d.headlineMedium.copy(fontFamily = head),
        headlineSmall = d.headlineSmall.copy(fontFamily = head),
        titleLarge = TextStyle(fontFamily = head, fontWeight = FontWeight.Bold, fontSize = 20.sp),
        titleMedium = TextStyle(fontFamily = head, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 2.sp),
        titleSmall = TextStyle(fontFamily = head, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp),
        labelSmall = TextStyle(fontFamily = head, fontSize = 11.sp, letterSpacing = 1.sp),
        labelMedium = TextStyle(fontFamily = head, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp),
        labelLarge = TextStyle(fontFamily = body, fontWeight = FontWeight.Medium, fontSize = 14.sp),
        bodyLarge = TextStyle(fontFamily = body, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = body, fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontFamily = body, fontSize = 13.sp, lineHeight = 19.sp),
    )
    if (!custom) return t
    // fonts with tall ascenders overlap the next line with fixed line heights: let the font decide
    fun f(s: TextStyle) = s.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = true))
    return Typography(
        displayLarge = f(t.displayLarge), displayMedium = f(t.displayMedium), displaySmall = f(t.displaySmall),
        headlineLarge = f(t.headlineLarge), headlineMedium = f(t.headlineMedium), headlineSmall = f(t.headlineSmall),
        titleLarge = f(t.titleLarge), titleMedium = f(t.titleMedium), titleSmall = f(t.titleSmall),
        bodyLarge = f(t.bodyLarge), bodyMedium = f(t.bodyMedium), bodySmall = f(t.bodySmall),
        labelLarge = f(t.labelLarge), labelMedium = f(t.labelMedium), labelSmall = f(t.labelSmall),
    )
}

@Composable
fun PocketMagiTheme(
    palette: Palette,
    bodyFont: FontFamily = FontFamily.Default,
    headFont: FontFamily = FontFamily.Monospace,
    content: @Composable () -> Unit,
) {
    // publish the palette outside of composition (screens read MagiColors.*)
    SideEffect { if (MagiColors.palette != palette) MagiColors.palette = palette }
    val scheme = darkColorScheme(
        primary = palette.accent,
        onPrimary = if (palette.id == "tokyonight") palette.bg
            else if (palette.accent.luminance() > 0.35f) Color.Black else Color.White,
        secondary = palette.eye,
        background = palette.bg,
        onBackground = palette.text,
        surface = palette.panel,
        onSurface = palette.text,
        surfaceVariant = palette.panel,
        onSurfaceVariant = palette.textDim,
        outline = palette.accentDim,
        error = palette.reject,
    )
    MaterialTheme(colorScheme = scheme,
        typography = typography(bodyFont, headFont, bodyFont != FontFamily.Default || headFont != FontFamily.Monospace), content = content)
}
