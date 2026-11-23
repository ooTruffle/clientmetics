package com.ootruffle.clientmetics.compat.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.minecraft.client.Minecraft
import net.minecraft.client.audio.PositionedSoundRecord
import net.minecraft.util.ResourceLocation
import org.polyfrost.compose.backend.drawPoly
import org.polyfrost.compose.host.LocalUiOversample
import org.polyfrost.compose.host.OversampledSvgPainter
import org.polyfrost.compose.minecraft.mc.McPlayerFaces
import org.polyfrost.compose.minecraft.platform.TextInput
import org.polyfrost.compose.render.PolyColor
import org.polyfrost.compose.render.PolyShadow
import java.util.concurrent.ConcurrentHashMap

// The small set of OneConfig-style widgets the wardrobe uses, built on plain Compose and
// PolyCompose so they work without OneConfig.

/** Text in the theme's font. */
@Composable
internal fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit? = null,
    fontWeight: FontWeight = FontWeight.Normal,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    colorProducer: ColorProducer? = null,
) {
    BasicText(
        text,
        modifier,
        style = TextStyle(
            color = color, fontSize = fontSize, fontWeight = fontWeight, fontFamily = LocalTheme.current.fontFamily,
            lineHeight = lineHeight ?: TextUnit.Unspecified, textAlign = textAlign,
        ),
        overflow = overflow,
        maxLines = maxLines,
        color = colorProducer,
    )
}

private object IconBytes {
    private val cache = ConcurrentHashMap<String, ByteArray>()

    fun get(name: String): ByteArray? = cache[name] ?: IconBytes::class.java
        .getResourceAsStream("/assets/clientmetics/icons/$name.svg")?.use { it.readBytes() }?.also { cache[name] = it }
}

private class TintedPainter(private val base: Painter, var tint: () -> Color) : Painter() {
    private var alpha = 1f
    override val intrinsicSize get() = base.intrinsicSize

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }

    override fun DrawScope.onDraw() {
        with(base) { draw(size, alpha, ColorFilter.tint(tint())) }
    }
}

/** One of the wardrobe's icons ({@code assets/clientmetics/icons/<name>.svg}), 18dp unless [modifier] sizes it. */
@Composable
internal fun Icon(name: String, color: Color = Color.Unspecified, modifier: Modifier = Modifier, tint: (() -> Color)? = null) {
    val oversample = LocalUiOversample.current
    val painter = remember(name, oversample) {
        IconBytes.get(name)?.let { runCatching { OversampledSvgPainter(it, oversample, "clientmetics:$name") }.getOrNull() }
    } ?: return
    val resolved = if (color == Color.Unspecified) LocalTheme.current.textColor else color
    val tinted = remember(painter) { TintedPainter(painter) { resolved } }
    tinted.tint = tint ?: { resolved }
    // The caller's size (if any) wins; padding goes around the 18dp default.
    Image(tinted, null, modifier.size(18.dp))
}

/**
 * A ghost icon button (the glyph fills [size]), or with [hoverBorder] a framed one whose glyph sits
 * 4dp inside an outline that shows on hover, like OneConfig's header buttons.
 */
@Composable
internal fun IconButton(
    icon: String,
    size: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hoverBorder: Boolean = false,
    onClick: () -> Unit,
) {
    val theme = LocalTheme.current
    val interaction = rememberInteractionSource()
    val hovered by interaction.collectIsHoveredAsState()
    val color by animateColorAsState(
        when {
            !enabled -> theme.textColorSecondary.copy(alpha = 0.5f)
            hovered -> theme.textColor
            else -> theme.textColor.copy(alpha = 0.8f)
        }
    )
    val border by animateColorAsState(if (hoverBorder && hovered && enabled) theme.borderColor else theme.borderColor.copy(alpha = 0f))
    Box(
        modifier.size(size)
            .clip(theme.buttonShape)
            .border(1.dp, border, theme.buttonShape)
            .onClick(interaction, enabled, onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, modifier = Modifier.size(if (hoverBorder) size - 8.dp else size), tint = { color })
    }
}

/** OneConfig's category chip: the accent fills it when selected, and tints it on hover. */
@Composable
internal fun Chip(label: String, selected: Boolean, onClick: () -> Unit, icon: String? = null) {
    val theme = LocalTheme.current
    val interaction = rememberInteractionSource()
    val hovered by interaction.collectIsHoveredAsState()
    val accentAlpha by animateFloatAsState(if (selected) 1f else if (hovered) 0.5f else 0f)
    val content by animateColorAsState(if (selected) theme.accentTextColor else theme.textColor)
    Row(
        Modifier.heightIn(min = 32.dp)
            .animatedFrame(theme.navEntryShape, background = theme.chipBackground, accentAlpha = { accentAlpha }, border = { theme.borderColor })
            .onClick(interaction, true, onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, modifier = Modifier.size(16.dp), tint = { content })
        Text(label, color = Color.Unspecified, colorProducer = { content }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Your skin's face with the hat layer, drawn by PolyCompose on the render thread (the default
 * skin until yours has loaded). Redrawn a few times a second so the real skin shows once it arrives.
 */
@Composable
internal fun PlayerHead(size: Dp, modifier: Modifier = Modifier) {
    val tick by produceState(0) { while (true) { delay(500); value++ } }
    val shape = LocalTheme.current.buttonShape
    Box(
        modifier.size(size).clip(shape).drawBehind {
            @Suppress("UNUSED_EXPRESSION") tick
            val w = size.toPx()
            drawPoly { ctx ->
                runCatching {
                    // Offline sessions have no profile ID; the player's own UUID still gets the default skin.
                    val id = McPlayerFaces.localPlayerId() ?: Minecraft.getMinecraft().thePlayer?.uniqueID ?: return@runCatching
                    McPlayerFaces.layers(id)?.let { McPlayerFaces.draw(ctx, it, 0f, 0f, w, w) }
                }
            }
        },
    )
}

@Composable
internal fun rememberInteractionSource(): MutableInteractionSource = remember { MutableInteractionSource() }

/** Clickable without a ripple, with Minecraft's button click. */
internal fun Modifier.onClick(interaction: MutableInteractionSource, enabled: Boolean = true, action: () -> Unit): Modifier =
    clickable(interaction, indication = null, enabled = enabled) {
        runCatching {
            Minecraft.getMinecraft().soundHandler.playSound(PositionedSoundRecord.create(ResourceLocation("gui.button.press"), 1f))
        }
        action()
    }

/** A frame in [shape]: [background], the accent fading in with [accentAlpha], and a [border]. */
internal fun Modifier.animatedFrame(
    shape: Shape,
    background: Color = Color.Transparent,
    accentAlpha: () -> Float,
    border: () -> Color,
): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    if (background.alpha > 0f) drawOutline(outline, background)
    val a = accentAlpha().coerceIn(0f, 1f)
    if (a > 0f) drawOutline(outline, AccentBrush, alpha = a)
    val b = border()
    if (b.alpha > 0f) drawOutline(outline, b, style = Stroke(1.dp.toPx()))
}

/** A line along the top edge. */
internal fun Modifier.topBorder(color: Color, width: Float): Modifier =
    drawBehind { drawLine(color, Offset(0f, width / 2f), Offset(size.width, width / 2f), width) }

/** Paints the content with the accent gradient, if OneConfig's accent is one (otherwise leaves it alone). */
internal fun Modifier.accentGradientFill(): Modifier =
    if (!AccentGradient) this
    else graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.horizontalGradient(listOf(Accent, AccentEnd)), blendMode = BlendMode.Modulate)
        }

/**
 * A soft drop shadow around [shape], drawn by PolyCompose's renderer with the shape's own area left
 * out, so a see-through window doesn't show its shadow through itself.
 */
internal fun Modifier.windowShadow(shape: Shape, color: Color, blur: Dp, spread: Dp, offset: DpOffset, alpha: Float): Modifier =
    drawBehind {
        if (size.width <= 0f || size.height <= 0f) return@drawBehind
        val outline = shape.createOutline(size, layoutDirection, this)
        val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
        val s = spread.toPx()
        val ox = offset.x.toPx()
        val oy = offset.y.toPx()
        val w = size.width
        val h = size.height
        val poly = PolyShadow(PolyColor(color.toArgb()).multiplyAlpha(alpha), blur.toPx(), 0f, 0f, 0f)
        drawPoly { ctx ->
            ctx.renderer.shadowWithHole(ox - s, oy - s, w + s * 2f, h + s * 2f, radius + s, poly, 0f, 0f, w, h, radius)
        }
    }

/**
 * Turns on text input while the field is focused. On 1.8.9 PolyCompose reads typing through SDL,
 * which only sends text while this is on.
 */
@Composable
internal fun Modifier.textInputWhileFocused(): Modifier {
    val owner = remember { Any() }
    DisposableEffect(owner) { onDispose { runCatching { TextInput.stop(owner) } } }
    return onFocusChanged { runCatching { if (it.isFocused) TextInput.start(owner) else TextInput.stop(owner) } }
}
