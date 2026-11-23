package com.ootruffle.clientmetics.compat.compose

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.polyfrost.compose.render.PolyColor
import org.polyfrost.compose.text.Font

/**
 * The wardrobe's colours and shapes, matching OneConfig's built-in themes (Polyfrosted and
 * Minecraft, dark and light). With OneConfig installed it follows OneConfig's active theme and
 * accent; without it, it uses OneConfig's defaults (Polyfrosted Dark with the blue accent).
 */
internal class WardrobeTheme(
    val name: String,
    val pageBackground: Color,
    val sidebarBackground: Color,
    val chipBackground: Color,
    val modCardBackground: Color,
    val componentBackground: Color,
    val borderColor: Color,
    val textColor: Color,
    val textColorSecondary: Color,
    val accentTextColor: Color,
    val shadowColor: Color,
    val shadowEnabled: Boolean,
    val backgroundShape: Shape,
    val navEntryShape: Shape,
    val modCardShape: Shape,
    val checkBoxShape: Shape,
    val buttonShape: Shape,
    val circleShape: Shape,
    val fontFamily: FontFamily,
)

/** How see-through OneConfig's window is (its Transparency preferences, in percent). */
internal class Appearance(val pageOpacity: Float, val sidebarOpacity: Float, val glowOpacity: Float)

private val Inter by lazy {
    FontFamily(
        Font("assets/polycompose/fonts/inter/Inter-Regular.ttf", FontWeight.Normal),
        Font("assets/polycompose/fonts/inter/Inter-Medium.ttf", FontWeight.Medium),
        Font("assets/polycompose/fonts/inter/Inter-SemiBold.ttf", FontWeight.SemiBold),
        Font("assets/polycompose/fonts/inter/Inter-Bold.ttf", FontWeight.Bold),
    )
}

private val Round = listOf(20.dp, 8.dp, 16.dp, 6.dp, 8.dp).map { RoundedCornerShape(it) }
private val Square = RoundedCornerShape(0.dp)

private fun dark(name: String, glass: Boolean) = WardrobeTheme(
    name,
    pageBackground = if (glass) Color(0xC411171C) else Color(0xFF11171C),
    sidebarBackground = if (glass) Color(0xB3151C22) else Color(0xFF11171C),
    chipBackground = Color(0xB2232D32),
    modCardBackground = Color(0x59232D32),
    componentBackground = Color(0xFF1A2229),
    borderColor = Color(0x1AFFFFFF),
    textColor = Color(0xFFD5DBFF),
    textColorSecondary = Color(0xFF757883),
    accentTextColor = Color(0xFFFFFFFF),
    shadowColor = Color(0xFF000000),
    shadowEnabled = glass,
    backgroundShape = if (glass) Round[0] else Square,
    navEntryShape = if (glass) Round[1] else Square,
    modCardShape = if (glass) Round[2] else Square,
    checkBoxShape = if (glass) Round[3] else Square,
    buttonShape = if (glass) Round[4] else Square,
    circleShape = if (glass) CircleShape else Square,
    fontFamily = Inter,
)

private fun light(name: String, glass: Boolean) = WardrobeTheme(
    name,
    pageBackground = if (glass) Color(0xB0D0D7F3) else Color(0xFFD0D7F3),
    sidebarBackground = if (glass) Color(0xB2D0D7F3) else Color(0xFFD0D7F3),
    chipBackground = Color(0x59EBF2FF),
    modCardBackground = Color(0x59EBF2FF),
    componentBackground = Color(0xB2EBF2FF),
    borderColor = Color(0x4DA3A3A3),
    textColor = Color.Black,
    textColorSecondary = Color(0xFF4E5661),
    accentTextColor = Color(0xFFD5DBFF),
    shadowColor = Color(0xFF000000),
    shadowEnabled = glass,
    backgroundShape = if (glass) Round[0] else Square,
    navEntryShape = if (glass) Round[1] else Square,
    modCardShape = if (glass) Round[2] else Square,
    checkBoxShape = if (glass) Round[3] else Square,
    buttonShape = if (glass) Round[4] else Square,
    circleShape = if (glass) CircleShape else Square,
    fontFamily = Inter,
)

internal object WardrobeThemes {
    val PolyfrostedDark = dark("Polyfrosted Dark", true)
    val PolyfrostedLight = light("Polyfrosted Light", true)
    val MinecraftDark = dark("Minecraft Dark", false)
    val MinecraftLight = light("Minecraft Light", false)
    val all = listOf(PolyfrostedDark, PolyfrostedLight, MinecraftDark, MinecraftLight)

    /** OneConfig's default accent. */
    const val DEFAULT_ACCENT = 0xFF2B4BFF.toInt()

    /** Dev autotest overrides; null follows OneConfig (or the defaults). */
    @Volatile var themeOverride: WardrobeTheme? = null
    @Volatile var accentOverride: Int? = null

    fun current(): WardrobeTheme {
        themeOverride?.let { return it }
        val name = OneConfigPrefs.value("activeTheme") as? String ?: return PolyfrostedDark
        all.firstOrNull { it.name.equals(name, true) }?.let { return it }
        // A custom OneConfig theme: get as close as the built-in ones allow.
        val lightTheme = name.contains("light", true)
        val minecraft = name.contains("minecraft", true)
        return when {
            minecraft && lightTheme -> MinecraftLight
            minecraft -> MinecraftDark
            lightTheme -> PolyfrostedLight
            else -> PolyfrostedDark
        }
    }

    /** The accent start and end colours (the same unless OneConfig's accent is a gradient). */
    fun accent(): Pair<Color, Color> {
        accentOverride?.let { return Color(it) to Color(it) }
        val color = OneConfigPrefs.value("accentColor") as? PolyColor ?: return Color(DEFAULT_ACCENT) to Color(DEFAULT_ACCENT)
        val start = Color(color.argb)
        val end = if (color.gradientEnabled && !color.chroma) Color(color.gradientArgb) else start
        return start to end
    }

    fun appearance() = Appearance(
        OneConfigPrefs.float("pageOpacity", 88f),
        OneConfigPrefs.float("sidebarOpacity", 80f),
        OneConfigPrefs.float("glowOpacity", 25f),
    )
}

internal val LocalTheme = staticCompositionLocalOf { WardrobeThemes.PolyfrostedDark }
internal val LocalAppearance = staticCompositionLocalOf { WardrobeThemes.appearance() }

private var accentState by mutableStateOf(WardrobeThemes.accent())

/** The accent colour (the gradient's start). */
internal val Accent: Color get() = accentState.first

/** The accent gradient's end (the same as [Accent] without a gradient). */
internal val AccentEnd: Color get() = accentState.second

internal val AccentGradient: Boolean get() = accentState.first != accentState.second

internal val AccentBrush: Brush
    get() = if (AccentGradient) Brush.horizontalGradient(listOf(Accent, AccentEnd)) else SolidColor(Accent)

/** Provides the theme, refreshing it from OneConfig a few times a second so changes apply while open. */
@Composable
internal fun WardrobeThemeProvider(content: @Composable () -> Unit) {
    var theme by androidx.compose.runtime.remember { mutableStateOf(WardrobeThemes.current()) }
    var appearance by androidx.compose.runtime.remember { mutableStateOf(WardrobeThemes.appearance()) }
    LaunchedEffect(Unit) {
        while (true) {
            WardrobeThemes.accent().let { if (it != accentState) accentState = it }
            WardrobeThemes.current().let { if (it !== theme) theme = it }
            WardrobeThemes.appearance().let {
                if (it.pageOpacity != appearance.pageOpacity || it.sidebarOpacity != appearance.sidebarOpacity || it.glowOpacity != appearance.glowOpacity) {
                    appearance = it
                }
            }
            delay(250)
        }
    }
    CompositionLocalProvider(LocalTheme provides theme, LocalAppearance provides appearance, content = content)
}

/** The colour at [percent] opacity (OneConfig's page and sidebar opacity preferences replace the theme's alpha). */
internal fun Color.withOpacityPercent(percent: Float): Color = copy(alpha = (percent / 100f).coerceIn(0f, 1f))
