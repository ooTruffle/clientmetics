package com.ootruffle.clientmetics.compat.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.maxLengthReject
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ootruffle.clientmetics.asset.Catalog
import com.ootruffle.clientmetics.compat.OneConfigCompat
import com.ootruffle.clientmetics.cosmetic.CosmeticOptions
import com.ootruffle.clientmetics.cosmetic.LunarAccount
import com.ootruffle.clientmetics.render.PreviewRenderer
import com.ootruffle.clientmetics.render.WardrobeDev
import com.ootruffle.clientmetics.render.WardrobeSource
import kotlinx.coroutines.delay
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.renderer.GlStateManager
import org.lwjgl.opengl.GL11
import org.polyfrost.compose.backend.drawPoly
import org.polyfrost.compose.minecraft.legacy.InputConstants
import org.polyfrost.compose.minecraft.render.OffscreenTarget
import org.polyfrost.compose.minecraft.render.TextureRenderer
import org.polyfrost.compose.minecraft.screen.ComposeScreen

/** Java entry point for the wardrobe screen. */
object ComposeWardrobe {
    @JvmStatic
    fun create(): GuiScreen = ComposeWardrobeScreen()

    /**
     * Dev autotest only: switches the wardrobe to [theme] (a theme name such as "Minecraft Light",
     * or null to keep it) and the accent to [accent] (hex RGB, or null), returning what puts them back.
     */
    @JvmStatic
    fun devTheme(theme: String?, accent: String?): Runnable {
        WardrobeThemes.all.firstOrNull { it.name.equals(theme, true) }?.let { WardrobeThemes.themeOverride = it }
        if (accent != null) WardrobeThemes.accentOverride = 0xFF000000.toInt() or accent.removePrefix("#").toInt(16)
        return Runnable {
            WardrobeThemes.themeOverride = null
            WardrobeThemes.accentOverride = null
        }
    }
}

// OneConfig's own shell measurements (Shell.kt, Sidebar.kt, Header.kt, Mods.kt in OneConfig 1.6).
private val ShellWidth = 1391.dp
private val ShellHeight = 700.dp
private val SidebarWidth = 264.dp
private val PageGutter = 25.dp
private val CardGap = 19.dp
private val CardHeight = 160.dp
private val CardFooter = 36.dp
private val CardGlow = 50.dp
private val RightColumn = 280.dp

// The page's background glow: two soft accent spots, fading out over GLOW_EXTENT pixels.
private const val GLOW_EXTENT = 1400f
private val GlowCenters = listOf(Offset(147f, -199f), Offset(893f, 736f))
private val GlowStops = floatArrayOf(0f, .2f, .4f, .5f, .6f, .7f, .8f, 1f)
private val GlowAlphas = floatArrayOf(.95f, .77f, .42f, .25f, .13f, .055f, .018f, 0f)

/** The equipped-only chip's key in the filter (categories are plain names). */
private const val EQUIPPED = "\u0000equipped"

/**
 * The wardrobe in OneConfig's style, drawn with PolyCompose (used when PolyCompose is installed;
 * OneConfig isn't needed). It's laid out like a page of OneConfig's menu: the same window, glow,
 * scaling and sidebar (accounts, plus links to the settings page and OneConfig's menu when
 * OneConfig is installed), a header with back, title, search and close,
 * a row of chips for the categories, and cards shaped like OneConfig's mod cards, whose footer
 * lights up with the accent when the cosmetic is equipped. The outfit preview sits on the right.
 *
 * Each 3D preview is rendered with [PreviewRenderer] into its own offscreen framebuffer just before
 * Compose draws, and Compose paints that texture inside the card (as PolyPlus does for its player
 * preview), so the models sit in the UI rather than on top of it. Equipping goes through
 * [WardrobeSource]. With OneConfig installed it follows
 * OneConfig's theme, accent and window preferences (see [WardrobeThemes]).
 */
class ComposeWardrobeScreen : ComposeScreen(ComposeScreen.RenderMode.CONTINUOUS), WardrobeDev {

    /** One offscreen preview: its size in pixels (from layout) and its framebuffer. */
    private class Thumb {
        var width = 0
        var height = 0
        var target: OffscreenTarget? = null
        var ready = false
    }

    // Keyed by WardrobeSource.Item.key; BIG is the outfit preview. Render thread only.
    private val thumbs = HashMap<String, Thumb>()

    /** The equipped Lunar cosmetic whose settings are open on the right, or null for the list. */
    private var selected by mutableStateOf<Int?>(null)

    private var previewYaw = 0f
    private var lastDragAt = 0L
    private var lastFrame = 0L

    init {
        WardrobeSource.current().ensureLoaded()
    }

    private val openedAt = System.currentTimeMillis()

    /** False until the first frame (so the open animation plays) and again while closing. */
    private var visible by mutableStateOf(false)

    /** When the close animation started, or 0 while open. */
    private var closingAt = 0L

    // OneConfig's animation preferences (or its defaults), read when used so changes apply straight away.
    private fun openMillis(): Long =
        if (OneConfigPrefs.bool("guiOpenAnimation", true)) (OneConfigPrefs.float("animationTime", 0.6f) * 1000f).toLong().coerceAtLeast(1L) else 1L

    private fun closeMillis(): Long =
        if (OneConfigPrefs.bool("guiClosingAnimation", true)) (OneConfigPrefs.float("animationTime", 0.6f) * 1000f).toLong().coerceIn(1L, 160L) else 0L

    /** Closes with OneConfig's closing animation (or at once, if that's turned off). */
    private fun requestClose() {
        if (closingAt != 0L) return
        if (closeMillis() <= 0L) {
            Minecraft.getMinecraft().displayGuiScreen(null)
            return
        }
        closingAt = System.currentTimeMillis()
        visible = false
    }

    /** Blurs the world behind if OneConfig's background blur is on, fading with the window. */
    override fun blurAlpha(): Float {
        if (!OneConfigPrefs.bool("enableBackgroundBlur", true)) return 0f
        val now = System.currentTimeMillis()
        return if (closingAt != 0L) {
            1f - easeOutExpo((now - closingAt).toFloat() / closeMillis())
        } else {
            easeOutExpo((now - openedAt).toFloat() / openMillis())
        }
    }

    override fun handleKeyPressed(key: Int, modifiers: Int): Boolean {
        if (key == InputConstants.KEY_ESCAPE) {
            requestClose()
            return true
        }
        return false
    }

    override fun doesGuiPauseGame(): Boolean = OneConfigPrefs.bool("pauseGame", false)

    override fun devSelectEquipped(id: Int) {
        selected = if (id < 0) null else id
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
            // OneConfig's menu scaling: the window fills 90% of the screen, whatever the GUI scale.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val fit = minOf(maxWidth * 0.9f / ShellWidth, maxHeight * 0.9f / ShellHeight)
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density * fit, density.fontScale)) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            alpha = shown
                            scaleX = 0.95f + 0.05f * shown
                            scaleY = scaleX
                        },
                    ) { Wardrobe() }
                }
            }
        }
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        if (closingAt != 0L && System.currentTimeMillis() - closingAt >= closeMillis()) {
            Minecraft.getMinecraft().displayGuiScreen(null)
            return
        }
        renderThumbs()
        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    override fun onGuiClosed() {
        super.onGuiClosed()
        for (t in thumbs.values) t.target?.dispose()
        thumbs.clear()
    }

    // ---- offscreen previews ----

    private fun thumb(id: String): Thumb = thumbs.getOrPut(id) { Thumb() }

    private fun releaseThumb(id: String) {
        thumbs.remove(id)?.target?.dispose()
    }

    /** Renders every preview that's on screen into its framebuffer. */
    private fun renderThumbs() {
        val mc = Minecraft.getMinecraft()
        val player = mc.thePlayer ?: return
        val source = WardrobeSource.current()
        val byKey = source.owned().associateBy { it.key }
        val now = Minecraft.getSystemTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceAtMost(0.1f)
        lastFrame = now
        if (now - lastDragAt > 2500) previewYaw += dt * 18f
        for ((id, t) in thumbs) {
            if (t.width <= 0 || t.height <= 0) continue
            val item = if (id == BIG) null else byKey[id]
            if (id != BIG && item == null) continue // From the other source; released when its card goes.
            if (item != null && !item.drawable) { t.ready = false; continue } // The card says "No preview".
            val target = t.target ?: OffscreenTarget().also { t.target = it }
            if (!target.resolve(t.width, t.height)) {
                t.ready = false
                continue
            }
            val fb = target.target ?: continue
            fb.bindFramebuffer(true)
            GlStateManager.clearColor(0f, 0f, 0f, 0f)
            GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT)
            GlStateManager.matrixMode(GL11.GL_PROJECTION)
            GlStateManager.pushMatrix()
            GlStateManager.loadIdentity()
            GlStateManager.ortho(0.0, t.width.toDouble(), t.height.toDouble(), 0.0, 1000.0, 3000.0)
            GlStateManager.matrixMode(GL11.GL_MODELVIEW)
            GlStateManager.pushMatrix()
            GlStateManager.loadIdentity()
            GlStateManager.translate(0f, 0f, -2000f)
            try {
                if (item == null) {
                    source.drawOutfit(player, 0, 0, t.width, t.height, previewYaw)
                } else {
                    source.drawItem(player, item, 0, 0, t.width, t.height)
                }
                t.ready = true
            } finally {
                GlStateManager.popMatrix()
                GlStateManager.matrixMode(GL11.GL_PROJECTION)
                GlStateManager.popMatrix()
                GlStateManager.matrixMode(GL11.GL_MODELVIEW)
                mc.framebuffer.bindFramebuffer(true)
            }
        }
    }

    /** A box that shows preview [id]: reports its size for rendering and paints the framebuffer. */
    @Composable
    private fun PreviewBox(id: String, modifier: Modifier, content: @Composable () -> Unit = {}) {
        // Reading the frame clock redraws the box every frame, so animations play.
        val frame by produceState(0L) { while (true) withFrameNanos { value = it } }
        DisposableEffect(id) { onDispose { releaseThumb(id) } }
        Box(
            modifier
                .onGloballyPositioned { val t = thumb(id); t.width = it.size.width; t.height = it.size.height }
                .drawBehind {
                    if (frame == 0L) return@drawBehind
                    val t = thumbs[id] ?: return@drawBehind
                    val fb = t.target?.target ?: return@drawBehind
                    if (!t.ready) return@drawBehind
                    val w = size.width
                    val h = size.height
                    drawPoly { ctx -> (ctx.renderer as? TextureRenderer)?.texture(fb, 0f, 0f, w, h, 0f, 1f, 1f, 0f, 1f) }
                },
        ) { content() }
    }

    // ---- UI ----

    /** Plain Java account state, read again a few times a second. */
    private class View(
        val source: WardrobeSource,
        val sources: List<WardrobeSource>,
        val loaded: Boolean,
        val busy: Boolean,
        val status: String,
        val owned: List<WardrobeSource.Item>,
        val equipped: List<WardrobeSource.Item>,
        val lunarState: LunarAccount.State?,
    )

    @Composable
    private fun Wardrobe() {
        val tick by produceState(0) { while (true) { delay(150); value++ } }
        var sourceVersion by remember { mutableStateOf(0) }
        val view = remember(tick, sourceVersion) {
            val source = WardrobeSource.current()
            val sources = WardrobeSource.all()
            // With every source off, `source` is only a placeholder: don't show its connection status.
            View(
                source, sources, source.loaded(), source.busy(), if (sources.isEmpty()) "" else source.status(),
                source.owned(), source.equipped(), if (source.hasSettings()) LunarAccount.state() else null,
            )
        }
        var filter by remember { mutableStateOf<String?>(null) }
        val search = rememberTextFieldState()
        val query = search.text.toString()

        val categories = view.owned.groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        val items = view.owned
            .filter { filter == null || (filter == EQUIPPED && it.equipped) || it.category == filter }
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            .sortedWith(compareBy({ !it.equipped }, { it.name.lowercase() }))

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Window {
                Sidebar(view) { s ->
                    if (s !== view.source) {
                        WardrobeSource.select(s)
                        sourceVersion++
                        filter = null
                        selected = null
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(vertical = 19.dp),
                    verticalArrangement = Arrangement.spacedBy(19.dp),
                ) {
                    Header(if (view.sources.isEmpty()) "" else "${view.source.name()} account", search, Modifier.padding(horizontal = PageGutter))
                    Row(
                        Modifier.padding(horizontal = PageGutter).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Like OneConfig's mod categories: clicking the selected chip goes back to All.
                        val pick = { key: String? -> filter = if (filter == key) null else key }
                        Chip("All", filter == null, onClick = { filter = null })
                        Chip("Equipped", filter == EQUIPPED, onClick = { pick(EQUIPPED) }, icon = "star")
                        for (category in categories) {
                            Chip(WardrobeSource.pretty(category), filter == category, onClick = { pick(category) })
                        }
                    }
                    Row(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = PageGutter),
                        horizontalArrangement = Arrangement.spacedBy(CardGap),
                    ) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (items.isEmpty()) {
                                Label(
                                    when {
                                        !com.ootruffle.clientmetics.Config.get().enabled ->
                                            "Clientmetics is turned off. Turn \"Enabled\" on in the Clientmetics settings."
                                        view.sources.isEmpty() ->
                                            "Lunar, Dawn and Essential cosmetics are all turned off. Turn one on in the Clientmetics settings."
                                        !view.loaded && view.busy -> "Loading your ${view.source.name()} cosmetics..."
                                        !view.loaded -> view.status.ifEmpty { "Not loaded yet." }
                                        filter == EQUIPPED && query.isBlank() -> "Nothing equipped."
                                        else -> "No cosmetics match."
                                    },
                                    14.sp, LocalTheme.current.textColorSecondary,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(4),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(CardGap),
                                    verticalArrangement = Arrangement.spacedBy(CardGap),
                                ) {
                                    items(items, key = { it.key }) { e -> Card(view.source, e, view.busy) }
                                }
                            }
                        }
                        PreviewColumn(view)
                    }
                }
            }
        }
    }

    /**
     * OneConfig's window: 1391x700 with its drop shadow, border, the accent glow behind the sidebar
     * and page, and both backgrounds at the opacities set in OneConfig's preferences.
     */
    @Composable
    private fun Window(content: @Composable RowScope.() -> Unit) {
        val theme = LocalTheme.current
        val appearance = LocalAppearance.current
        val glowAlpha = (appearance.glowOpacity / 100f).coerceIn(0f, 1f)
        val sidebarColor = theme.sidebarBackground.withOpacityPercent(appearance.sidebarOpacity)
        val pageColor = theme.pageBackground.withOpacityPercent(appearance.pageOpacity)
        Row(
            Modifier.size(ShellWidth, ShellHeight)
                .then(
                    if (theme.shadowEnabled) {
                        Modifier.windowShadow(theme.backgroundShape, theme.shadowColor, 50.dp, 4.dp, DpOffset(0.dp, 13.dp), .42f)
                    } else Modifier,
                )
                .clip(theme.backgroundShape)
                .border(1.dp, theme.borderColor, theme.backgroundShape)
                .drawBehind {
                    val sw = SidebarWidth.toPx()
                    if (glowAlpha > 0f) {
                        val accent = Accent.copy(alpha = 1f)
                        for (center in GlowCenters) {
                            val stops = Array(GlowStops.size) { i -> GlowStops[i] to accent.copy(alpha = GlowAlphas[i] * glowAlpha) }
                            drawRect(Brush.radialGradient(*stops, center = center, radius = GLOW_EXTENT))
                        }
                    }
                    drawRect(sidebarColor, Offset.Zero, Size(sw, size.height))
                    drawRect(pageColor, Offset(sw, 0f), Size(size.width - sw, size.height))
                    drawLine(theme.borderColor, Offset(sw, 0f), Offset(sw, size.height), 1f)
                },
            content = content,
        )
    }

    // ---- sidebar ----

    @Composable
    private fun Sidebar(view: View, onSource: (WardrobeSource) -> Unit) {
        Column(Modifier.fillMaxHeight().width(SidebarWidth), verticalArrangement = Arrangement.SpaceBetween) {
            Column(
                Modifier.fillMaxWidth().padding(top = 28.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Where OneConfig has its logo: a wordmark in the accent.
                Text(
                    "WARDROBE", Modifier.accentGradientFill(), color = Accent, fontSize = 20.sp,
                    lineHeight = 20.sp, fontWeight = FontWeight.Bold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // The settings page and mod list are OneConfig's, so they're only linked with it.
                    val oneConfig = OneConfigCompat.isActive()
                    if (oneConfig) {
                        NavSection(null) {
                            NavEntry("settings", "Clientmetics Settings", false) { OneConfigCompat.openSettings() }
                        }
                    }
                    NavSection("Accounts") {
                        for (s in view.sources) {
                            NavEntry(if (s === WardrobeSource.DAWN) "sun" else "moon", s.name(), s === view.source) { onSource(s) }
                        }
                    }
                    if (oneConfig) {
                        NavSection("OneConfig") {
                            NavEntry("dots-grid", "Mods", false) { OneConfigCompat.openMods() }
                        }
                    }
                }
            }
            AccountFooter(view)
        }
    }

    @Composable
    private fun NavSection(title: String?, content: @Composable () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.Start) {
            if (title != null) {
                Text(
                    title.uppercase(), Modifier.padding(start = 6.dp, bottom = 2.dp), color = LocalTheme.current.textColor,
                    fontSize = 11.sp, fontWeight = FontWeight.Medium,
                )
            }
            content()
        }
    }

    /** OneConfig's sidebar entry: 225x33, the accent fades in when selected and a border on hover. */
    @Composable
    private fun NavEntry(icon: String, title: String, selected: Boolean, onSelect: () -> Unit) {
        val theme = LocalTheme.current
        val interaction = rememberInteractionSource()
        val hovered by interaction.collectIsHoveredAsState()
        val selectedAlpha = animateFloatAsState(if (selected) 1f else 0f)
        val color = animateColorAsState(
            when {
                selected -> theme.accentTextColor
                hovered -> theme.textColor
                else -> theme.textColor.copy(0.8f)
            }
        )
        val border = animateColorAsState(if (hovered || selected) theme.borderColor else theme.borderColor.copy(0f))
        Row(
            Modifier.size(225.dp, 33.dp)
                .animatedFrame(theme.navEntryShape, accentAlpha = { selectedAlpha.value }, border = { border.value })
                .onClick(interaction) { onSelect() }
                .pointerHoverIcon(PointerIcon.Hand),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, modifier = Modifier.padding(start = 12.dp), tint = { color.value })
            Text(title, color = Color.Unspecified, colorProducer = { color.value }, maxLines = 1)
        }
    }

    /** OneConfig's account footer: your head and name, the owned count, and refresh where the bell is. */
    @Composable
    private fun AccountFooter(view: View) {
        val theme = LocalTheme.current
        Row(
            Modifier.fillMaxWidth().height(73.dp).topBorder(theme.borderColor, 1f).padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                PlayerHead(32.dp, Modifier.offset(x = 2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(Minecraft.getMinecraft().session.username, color = theme.textColor, maxLines = 1)
                    Text(
                        when {
                            view.loaded -> "${view.source.ownedCount()} ${view.source.name()} cosmetics"
                            view.busy -> "Loading..."
                            else -> "Not loaded"
                        },
                        color = theme.textColorSecondary, fontSize = 11.sp, maxLines = 1,
                    )
                }
            }
            IconButton("refresh", 18.dp, enabled = !view.busy) { view.source.refresh() }
        }
    }

    // ---- header ----

    /** OneConfig's header: back and forward, the title with a byline, then search and close. */
    @Composable
    private fun Header(subtitle: String, search: TextFieldState, modifier: Modifier) {
        val theme = LocalTheme.current
        Row(
            modifier.fillMaxWidth().height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (OneConfigCompat.isActive()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Back goes to OneConfig's menu, the page this one sits beside.
                        IconButton("left-arrow", 32.dp, hoverBorder = true) { OneConfigCompat.openMods() }
                        IconButton("right-arrow", 32.dp, enabled = false, hoverBorder = true) {}
                    }
                    Spacer(Modifier.size(24.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Wardrobe", color = theme.textColor, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    Text(subtitle, color = theme.textColorSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                SearchField(search)
                IconButton("close", 32.dp, hoverBorder = true) { requestClose() }
            }
        }
    }

    /** OneConfig's search bar. */
    @Composable
    private fun SearchField(state: TextFieldState) {
        val value = state.text
        val theme = LocalTheme.current
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val style = TextStyle(
            color = theme.textColorSecondary, fontWeight = FontWeight.Medium, fontSize = 12.sp,
            fontFamily = theme.fontFamily, lineHeight = 12.sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
        )
        BasicTextField(
            state = state,
            inputTransformation = InputTransformation.maxLengthReject(40),
            lineLimits = TextFieldLineLimits.SingleLine,
            textStyle = style,
            cursorBrush = SolidColor(theme.textColorSecondary),
            interactionSource = interaction,
            modifier = Modifier.textInputWhileFocused(),
            decorator = { inner ->
                Box(
                    Modifier.width(256.dp).height(32.dp)
                        .background(theme.chipBackground, theme.navEntryShape)
                        .border(1.dp, theme.borderColor, theme.navEntryShape),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon("search", theme.textColorSecondary, Modifier.size(14.dp))
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty() && !focused) {
                                Text("Search Cosmetics...", color = theme.textColorSecondary, fontSize = 12.sp, lineHeight = 12.sp)
                            }
                            inner()
                        }
                        if (value.isNotEmpty()) IconButton("close", 16.dp) { state.clearText() }
                    }
                }
            },
        )
    }

    // ---- cards ----

    /**
     * OneConfig's mod card: preview on top, the name in a footer that fills with the accent when
     * equipped (like a mod that's turned on), with the same vignette and bottom glow, and the dot at
     * the top right lit while equipped. Clicking anywhere equips or unequips.
     */
    @Composable
    private fun Card(source: WardrobeSource, entry: WardrobeSource.Item, busy: Boolean) {
        val theme = LocalTheme.current
        val shape = theme.modCardShape
        val interaction = rememberInteractionSource()
        val equipped = entry.equipped
        val accent by animateFloatAsState(if (equipped) 1f else 0f)
        val footerText by animateColorAsState(if (equipped) theme.accentTextColor else theme.textColor.copy(alpha = 0.75f))
        val border = remember(theme.borderColor) { Brush.verticalGradient(listOf(theme.borderColor, theme.borderColor.copy(0f))) }
        Box(
            Modifier.fillMaxWidth().height(CardHeight)
                .background(theme.modCardBackground, shape)
                .border(1.dp, border, shape)
                .onClick(interaction, !busy && source.canToggle()) { source.toggle(entry) }
                .pointerHoverIcon(if (source.canToggle()) PointerIcon.Hand else PointerIcon.Default)
                .clip(shape),
        ) {
            Column(Modifier.fillMaxSize()) {
                PreviewBox(entry.key, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (!entry.drawable) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No preview", color = theme.textColorSecondary, fontSize = 13.sp)
                        }
                    }
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = CardFooter)
                        .background(theme.textColor.copy(alpha = 0.08f * (1f - accent)))
                        .background(AccentBrush, alpha = accent)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        entry.name, Modifier.fillMaxWidth(), color = footerText, fontSize = 16.sp, lineHeight = 16.sp,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (theme.shadowEnabled) CardShading(accent * 0.5f)
            // The type at the top left (with the settings button for equipped Lunar cosmetics), like
            // the mod card's star; the dot at the top right.
            Row(Modifier.align(Alignment.TopStart).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (equipped && source.hasSettings()) {
                    IconButton("cog", 18.dp, Modifier.padding(3.dp)) { selected = entry.lunarId }
                }
                Text(
                    WardrobeSource.pretty(entry.category), Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                    color = theme.textColorSecondary, fontSize = 11.sp,
                )
            }
            EquippedDot(equipped, Modifier.align(Alignment.TopEnd).padding(11.dp))
        }
    }

    /** The mod card's soft vignette, and the accent glow along the bottom while equipped. */
    @Composable
    private fun CardShading(glow: Float) {
        val vignette = LocalTheme.current.textColor
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val radial = Brush.radialGradient(
                    listOf(vignette.copy(alpha = 0f), vignette.copy(alpha = 0.04f), vignette.copy(alpha = 0.08f)),
                    center = Offset(size.width / 2f, size.height / 2f), radius = size.minDimension * 0.9f,
                )
                val height = CardGlow.toPx().coerceAtMost(size.height)
                val top = size.height - height
                // The accent, faded in towards the bottom.
                val fade = Brush.verticalGradient(
                    0f to Accent.copy(alpha = 0f), 0.4f to Accent.copy(alpha = 0.2f), 1f to Accent.copy(alpha = 0.4f),
                    startY = top, endY = size.height,
                )
                onDrawBehind {
                    drawRect(radial)
                    if (glow > 0f) drawRect(fade, Offset(0f, top), Size(size.width, height), alpha = glow)
                }
            },
        )
    }

    /** The mod card's toggle dot: an accent ring and dot while equipped, grey otherwise. */
    @Composable
    private fun EquippedDot(on: Boolean, modifier: Modifier) {
        val circle = LocalTheme.current.circleShape // Square on the Minecraft themes.
        val off = Color(0xFF4F505B)
        val shade = Color(0xFF181600)
        Box(
            modifier.size(12.dp).drawWithCache {
                val ringStart = if (on) lerp(Accent, shade, 0.42f) else off.copy(alpha = 0.5f)
                val ringEnd = if (on) lerp(AccentEnd, shade, 0.42f) else ringStart
                val ring = lerp(ringStart, ringEnd, 0.5f)
                val glowRadius = (6.dp + 5.dp).toPx()
                val inner = size.minDimension / 2f / glowRadius
                val glow = Brush.radialGradient(
                    inner to ring.copy(alpha = 0.41f), inner + (1f - inner) * 0.4f to ring.copy(alpha = 0.25f),
                    inner + (1f - inner) * 0.8f to ring.copy(alpha = 0.09f), 1f to Color.Transparent,
                    center = Offset(size.width / 2f, size.height / 2f), radius = glowRadius,
                )
                val dot = 6.dp.toPx()
                val ringBrush = Brush.linearGradient(listOf(ringStart, ringEnd), Offset.Zero, Offset(size.width, size.height))
                val dotBrush: Brush = if (on) Brush.linearGradient(listOf(Accent, AccentEnd)) else SolidColor(off)
                val ringOutline = circle.createOutline(size, layoutDirection, this)
                val dotOutline = circle.createOutline(Size(dot, dot), layoutDirection, this)
                val inset = (size.minDimension - dot) / 2f
                onDrawBehind {
                    if (on) drawCircle(glow, radius = glowRadius)
                    drawOutline(ringOutline, ringBrush)
                    translate(inset, inset) { drawOutline(dotOutline, dotBrush) }
                }
            },
        )
    }

    // ---- right column: preview, equipped, settings ----

    @Composable
    private fun PreviewColumn(view: View) {
        val theme = LocalTheme.current
        val shape = theme.modCardShape
        val border = remember(theme.borderColor) { Brush.verticalGradient(listOf(theme.borderColor, theme.borderColor.copy(0f))) }
        Column(Modifier.width(RightColumn).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(CardGap)) {
            Box(
                Modifier.fillMaxWidth().weight(1f).background(theme.modCardBackground, shape).border(1.dp, border, shape).clip(shape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            previewYaw += drag.x * 0.6f
                            lastDragAt = Minecraft.getSystemTime()
                            change.consume()
                        }
                    },
            ) {
                PreviewBox(BIG, Modifier.fillMaxSize().padding(top = 8.dp, bottom = 26.dp))
                Text(
                    "Drag to turn", Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    color = theme.textColorSecondary, fontSize = 11.sp,
                )
            }
            Column(
                Modifier.fillMaxWidth().background(theme.modCardBackground, shape).border(1.dp, theme.borderColor, shape)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val open = selected?.takeIf { view.lunarState?.isEquipped(it) == true }
                if (open != null && view.lunarState != null) {
                    Settings(view.lunarState, open, view.busy)
                } else {
                    Text("EQUIPPED", color = theme.textColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    when {
                        !view.loaded -> Label(if (view.busy) "Loading..." else "Not loaded", 13.sp, theme.textColorSecondary)
                        view.equipped.isEmpty() -> Label("Nothing yet", 13.sp, theme.textColorSecondary)
                        else -> for (c in view.equipped.take(6)) {
                            ListRow(c.name, if (view.source.hasSettings()) ({ selected = c.lunarId }) else null)
                        }
                    }
                }
                if (view.status.isNotEmpty()) {
                    // Green when saved, amber otherwise, darker on light themes so they stay readable.
                    val light = theme.pageBackground.luminance() > 0.5f
                    val color = if (view.status.startsWith("Saved")) {
                        if (light) Color(0xFF1E7B36) else Color(0xFF7CE89A)
                    } else {
                        if (light) Color(0xFF8A5A00) else Color(0xFFFFD966)
                    }
                    Label(view.status, 12.sp, color, maxLines = 3)
                }
            }
        }
    }

    /** A list row; clickable rows show a chevron. */
    @Composable
    private fun ListRow(name: String, onClick: (() -> Unit)?) {
        val theme = LocalTheme.current
        val interaction = rememberInteractionSource()
        val hovered by interaction.collectIsHoveredAsState()
        val base = Modifier.fillMaxWidth().clip(theme.buttonShape)
            .background(if (hovered && onClick != null) theme.componentBackground else Color.Transparent)
        Row(
            (if (onClick != null) base.onClick(interaction) { onClick() }.pointerHoverIcon(PointerIcon.Hand) else base)
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("• $name", 13.sp, theme.textColor, maxLines = 1, modifier = Modifier.weight(1f))
            if (onClick != null) Icon("right-arrow", theme.textColorSecondary, Modifier.size(12.dp))
        }
    }

    /** The selected equipped Lunar cosmetic's settings, saved to the account as they change. */
    @Composable
    private fun Settings(state: LunarAccount.State, id: Int, busy: Boolean) {
        val theme = LocalTheme.current
        val entry = Catalog.get()?.entry(id)
        val cosmetic = CosmeticOptions.equipped(state, id) ?: return
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton("left-arrow", 16.dp) { selected = null }
            Label(entry?.name ?: "#$id", 13.sp, theme.textColor, FontWeight.Medium, maxLines = 1)
        }
        val flags = CosmeticOptions.flags(entry)
        val height = CosmeticOptions.height(entry)
        if (flags.isEmpty() && height == null) {
            Label("No settings for this item", 13.sp, theme.textColorSecondary)
            return
        }
        for (flag in flags) {
            val on = flag.get(cosmetic)
            val interaction = remember(flag) { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Row(
                Modifier.fillMaxWidth().onClick(interaction, !busy) { flag.toggle(cosmetic) }.pointerHoverIcon(PointerIcon.Hand),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(16.dp).clip(theme.checkBoxShape)
                        .then(if (on) Modifier.background(AccentBrush) else Modifier.background(theme.componentBackground))
                        .border(1.dp, theme.borderColor, theme.checkBoxShape),
                    contentAlignment = Alignment.Center,
                ) { if (on) Icon("tick", theme.accentTextColor, Modifier.size(10.dp)) }
                Label(flag.label, 13.sp, if (hovered) theme.textColor else theme.textColorSecondary, maxLines = 1)
            }
        }
        if (height != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("Height", 13.sp, theme.textColorSecondary)
                IconButton("minus", 22.dp, enabled = !busy) { height.nudge(cosmetic, -1) }
                Label(String.format(java.util.Locale.ROOT, "%+.2f", cosmetic.hatHeightOffset), 13.sp, theme.textColor)
                IconButton("plus", 22.dp, enabled = !busy) { height.nudge(cosmetic, 1) }
                Spacer(Modifier.weight(1f))
                IconButton("refresh", 22.dp, enabled = !busy) { height.reset(cosmetic) }
            }
        }
    }

    @Composable
    private fun Label(
        text: String,
        size: TextUnit,
        color: Color,
        weight: FontWeight = FontWeight.Normal,
        modifier: Modifier = Modifier,
        maxLines: Int = Int.MAX_VALUE,
    ) {
        Text(text, modifier, color = color, fontSize = size, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

private fun easeOutExpo(x: Float): Float = if (x >= 1f) 1f else if (x <= 0f) 0f else 1f - Math.pow(2.0, -10.0 * x).toFloat()

/** The outfit preview's key in the thumbnail map (item keys start with "lunar:" or "dawn:"). */
private const val BIG = "outfit"
