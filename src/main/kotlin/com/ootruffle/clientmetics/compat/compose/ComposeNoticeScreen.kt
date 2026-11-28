package com.ootruffle.clientmetics.compat.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ootruffle.clientmetics.render.Notice
import kotlinx.coroutines.delay
import net.minecraft.client.gui.GuiScreen
import org.polyfrost.compose.minecraft.legacy.InputConstants
import org.polyfrost.compose.minecraft.screen.ComposeScreen
import org.polyfrost.compose.minecraft.screen.ComposeSupport
import java.util.function.Consumer

/** Java entry point for the PolyCompose notice (see [Notice]). */
object ComposeNotice {
    /** The screen, or null if PolyCompose's UI is unavailable here. */
    @JvmStatic
    fun createOrNull(title: String, message: String, yes: String, no: String, delaySeconds: Int, onAnswer: Consumer<Boolean>): GuiScreen? =
        if (ComposeSupport.isAvailable) ComposeNoticeScreen(title, message, yes, no, delaySeconds, onAnswer) else null
}

// Sized against OneConfig's window (1391x700), scaled to fit the screen the same way, so it matches the wardrobe.
private val RefWidth = 1391.dp
private val RefHeight = 700.dp
private val DialogWidth = 620.dp
private val DialogMaxHeight = 640.dp

/**
 * [Notice] in OneConfig's style: a window with the accent glow, a title, the message (scrolling if
 * it's long) and the two buttons, over the blurred game. The buttons count down until they work,
 * Escape doesn't close it, and answering plays the closing animation before [onAnswer] runs.
 */
class ComposeNoticeScreen(
    private val title: String,
    private val message: String,
    private val yes: String,
    private val no: String,
    delaySeconds: Int,
    private val onAnswer: Consumer<Boolean>,
) : ComposeScreen(ComposeScreen.RenderMode.CONTINUOUS), Notice.Screen {

    private val openedAt = System.currentTimeMillis()
    private val readyAt = openedAt + delaySeconds * 1000L

    private var visible by mutableStateOf(false)
    private var closingAt = 0L
    private var answer: Boolean? = null

    private fun openMillis(): Long =
        if (OneConfigPrefs.bool("guiOpenAnimation", true)) (OneConfigPrefs.float("animationTime", 0.6f) * 1000f).toLong().coerceAtLeast(1L) else 1L

    private fun closeMillis(): Long =
        if (OneConfigPrefs.bool("guiClosingAnimation", true)) (OneConfigPrefs.float("animationTime", 0.6f) * 1000f).toLong().coerceIn(1L, 160L) else 0L

    /** Starts closing; the answer is handed on once the animation is done (see [drawScreen]). */
    private fun answer(yes: Boolean) {
        if (answer != null || System.currentTimeMillis() < readyAt) return
        answer = yes
        closingAt = System.currentTimeMillis()
        visible = false
    }

    override fun blurAlpha(): Float {
        if (!OneConfigPrefs.bool("enableBackgroundBlur", true)) return 0f
        val now = System.currentTimeMillis()
        return if (closingAt != 0L) {
            1f - easeOutExpo((now - closingAt).toFloat() / closeMillis().coerceAtLeast(1L))
        } else {
            easeOutExpo((now - openedAt).toFloat() / openMillis())
        }
    }

    // No Escape: the question has to be answered.
    override fun handleKeyPressed(key: Int, modifiers: Int): Boolean = key == InputConstants.KEY_ESCAPE

    override fun doesGuiPauseGame(): Boolean = false

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        val picked = answer
        // Answered outside Compose's event handling, since it usually opens another screen.
        if (picked != null && closingAt != 0L && System.currentTimeMillis() - closingAt >= closeMillis()) {
            closingAt = 0L
            onAnswer.accept(picked)
            return
        }
        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    @Composable
    override fun compose() {
        WardrobeThemeProvider {
            LaunchedEffect(Unit) {
                withFrameNanos { }
                visible = true
            }
            val shown by animateFloatAsState(
                if (visible) 1f else 0f,
                if (visible) tween(openMillis().toInt(), easing = Easing { easeOutExpo(it) })
                else tween(closeMillis().toInt().coerceAtLeast(1), easing = EaseOutCubic),
            )
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val fit = minOf(maxWidth * 0.9f / RefWidth, maxHeight * 0.9f / RefHeight)
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density * fit, density.fontScale)) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            alpha = shown
                            scaleX = 0.95f + 0.05f * shown
                            scaleY = scaleX
                        },
                        contentAlignment = Alignment.Center,
                    ) { Dialog() }
                }
            }
        }
    }

    @Composable
    private fun Dialog() {
        val theme = LocalTheme.current
        val appearance = LocalAppearance.current
        val glowAlpha = (appearance.glowOpacity / 100f).coerceIn(0f, 1f)
        val pageColor = theme.pageBackground.withOpacityPercent(appearance.pageOpacity)
        val shape = theme.backgroundShape
        val secondsLeft by produceState(secondsLeft()) {
            while (value > 0) {
                delay(100)
                value = secondsLeft()
            }
        }
        val ready = secondsLeft == 0

        Column(
            Modifier.width(DialogWidth).heightIn(max = DialogMaxHeight)
                .then(if (theme.shadowEnabled) Modifier.windowShadow(shape, theme.shadowColor, 50.dp, 4.dp, DpOffset(0.dp, 13.dp), .42f) else Modifier)
                .clip(shape)
                .border(1.dp, theme.borderColor, shape)
                .drawBehind {
                    // OneConfig's accent glow, from the top corner.
                    if (glowAlpha > 0f) {
                        val accent = Accent.copy(alpha = 1f)
                        drawRect(
                            Brush.radialGradient(
                                0f to accent.copy(alpha = .8f * glowAlpha),
                                .5f to accent.copy(alpha = .2f * glowAlpha),
                                1f to accent.copy(alpha = 0f),
                                center = Offset(0f, 0f),
                                radius = size.width * 1.1f,
                            ),
                        )
                    }
                    drawRect(pageColor)
                }
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                title, Modifier.accentGradientFill(), color = theme.textColor, fontSize = 22.sp, lineHeight = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
            MessageBody(Modifier.weight(1f, fill = false))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NoticeButton(no, primary = false, enabled = ready) { answer(false) }
                NoticeButton(if (ready) yes else "$yes ($secondsLeft)", primary = true, enabled = ready) { answer(true) }
            }
        }
    }

    private fun secondsLeft(): Int = ((readyAt - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)

    /** The message, scrolling when it doesn't fit, with a fade along the bottom while there's more. */
    @Composable
    private fun MessageBody(modifier: Modifier) {
        val theme = LocalTheme.current
        val scroll = rememberScrollState()
        val fadeTo = theme.pageBackground.copy(alpha = 1f)
        Box(
            modifier.fillMaxWidth().drawWithContent {
                drawContent()
                if (scroll.canScrollForward) {
                    val h = 32.dp.toPx().coerceAtMost(size.height)
                    drawRect(
                        Brush.verticalGradient(listOf(fadeTo.copy(alpha = 0f), fadeTo.copy(alpha = .85f)), startY = size.height - h, endY = size.height),
                        topLeft = Offset(0f, size.height - h),
                    )
                }
            },
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Blank lines split paragraphs; single line breaks stay inside one.
                for (paragraph in message.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }) {
                    Text(paragraph, color = theme.textColor.copy(alpha = .82f), fontSize = 14.sp, lineHeight = 21.sp)
                }
            }
        }
    }

    /** A button like OneConfig's: the accent for [primary], otherwise a framed one that the accent tints on hover. */
    @Composable
    private fun NoticeButton(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
        val theme = LocalTheme.current
        val interaction = rememberInteractionSource()
        val hovered by interaction.collectIsHoveredAsState()
        val accentAlpha by animateFloatAsState(
            when {
                primary && !enabled -> .45f
                primary -> if (hovered) 1f else .85f
                enabled && hovered -> .3f
                else -> 0f
            },
        )
        val content by animateColorAsState(
            when {
                primary -> theme.accentTextColor.copy(alpha = if (enabled) 1f else .6f)
                enabled -> theme.textColor
                else -> theme.textColorSecondary
            },
        )
        Box(
            Modifier.heightIn(min = 38.dp).widthIn(min = 120.dp)
                .animatedFrame(
                    theme.buttonShape,
                    background = if (primary) Color.Transparent else theme.componentBackground,
                    accentAlpha = { accentAlpha },
                    border = { if (primary) Color.Transparent else theme.borderColor },
                )
                .onClick(interaction, enabled, onClick)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label, color = Color.Unspecified, colorProducer = { content }, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
        }
    }
}
