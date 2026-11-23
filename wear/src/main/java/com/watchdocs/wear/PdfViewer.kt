package com.watchdocs.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.Closeable
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f

/** Sharp tiles are rendered at one of these zoom levels (the next one up from the current zoom). */
private val TILE_LEVELS = floatArrayOf(1.5f, 2f, 3f, 4f, 6f)
private const val TILE_PX = 256
private const val MAX_TILES = 90
private const val MAX_IMAGE_DIM = 2048

// ---------------------------------------------------------------- page sources

/** Something with pages that can be rendered: a PDF, or a single image. */
interface PageSource : Closeable {
    val pageCount: Int
    val isImage: Boolean get() = false

    /** [width, height] in source units (PDF points / image pixels). May be refined by [loadExactSizes]. */
    fun pageSize(i: Int): IntArray

    /** Measures every page; returns true if any size differs from the initial estimate. */
    suspend fun loadExactSizes(): Boolean = false

    /** Renders a page into a w×h bitmap. A null matrix fits the whole page into the bitmap. */
    suspend fun render(index: Int, w: Int, h: Int, matrix: Matrix?): ImageBitmap?
}

class PdfDoc(file: File) : PageSource {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(pfd)
    } catch (e: Exception) {
        pfd.close(); throw e
    }
    override val pageCount = renderer.pageCount

    // Only page 1 is measured up front (opening every page is slow for long PDFs);
    // the rest are assumed to be the same size until loadExactSizes() runs.
    private val sizes: Array<IntArray>

    // PdfRenderer allows only one open page at a time; keep the last one open since
    // consecutive tiles usually come from the same page.
    private val mutex = Mutex()
    private var closed = false
    private var openPage: PdfRenderer.Page? = null

    init {
        val first = if (pageCount > 0) renderer.openPage(0).use { measure(it) } else intArrayOf(1, 1)
        sizes = Array(pageCount) { first }
    }

    private fun measure(p: PdfRenderer.Page) = intArrayOf(p.width.coerceAtLeast(1), p.height.coerceAtLeast(1))

    private fun page(i: Int): PdfRenderer.Page {
        openPage?.let { if (it.index == i) return it }
        openPage?.close()
        return renderer.openPage(i).also { openPage = it }
    }

    override fun pageSize(i: Int) = sizes[i]

    override suspend fun loadExactSizes(): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        var i = 1
        while (i < pageCount) {
            mutex.withLock {
                if (closed) return@withContext changed
                val end = min(i + 16, pageCount)
                while (i < end) {
                    val s = measure(page(i))
                    if (!s.contentEquals(sizes[i])) {
                        sizes[i] = s
                        changed = true
                    }
                    i++
                }
            }
            yield() // let pending renders in between
        }
        changed
    }

    override suspend fun render(index: Int, w: Int, h: Int, matrix: Matrix?): ImageBitmap? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (closed) return@withLock null
                val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page(index).render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp.asImageBitmap()
            }
        }

    override fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                closed = true
                openPage?.close()
                openPage = null
                renderer.close()
                pfd.close()
            }
        }
    }
}

class ImageDoc(file: File) : PageSource {
    private val bitmap: Bitmap

    init {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Not an image")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_DIM) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Couldn't decode image")
        val rotation = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        bitmap = if (rotation == 0f) decoded else
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true)
    }

    private val size = intArrayOf(bitmap.width, bitmap.height)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    override val pageCount = 1
    override val isImage = true
    override fun pageSize(i: Int) = size

    override suspend fun render(index: Int, w: Int, h: Int, matrix: Matrix?): ImageBitmap =
        withContext(Dispatchers.Default) {
            val out = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            out.eraseColor(android.graphics.Color.BLACK)
            val m = matrix ?: Matrix().apply { setScale(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height) }
            AndroidCanvas(out).drawBitmap(bitmap, m, paint)
            out.asImageBitmap()
        }

    override fun close() {}
}

// ---------------------------------------------------------------- geometry & view state

/** Pages stacked vertically in "document pixels" (fit-to-width size, scale 1). */
private class PdfLayout(doc: PageSource, val fitW: Float, gap: Float) {
    val count = doc.pageCount
    val heights = FloatArray(count) { doc.pageSize(it).let { s -> fitW * s[1] / s[0] } }
    val pointsToDoc = FloatArray(count) { fitW / doc.pageSize(it)[0] }
    val tops = FloatArray(count)
    val docH: Float

    init {
        var y = 0f
        for (i in 0 until count) {
            tops[i] = y
            y += heights[i] + gap
        }
        docH = (y - gap).coerceAtLeast(0f)
    }

    fun pageAt(y: Float): Int {
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun visible(top: Float, bottom: Float): IntRange {
        val a = pageAt(top)
        var b = a
        while (b + 1 < count && tops[b + 1] < bottom) b++
        return a..b
    }
}

/**
 * Viewport over the document: screen = (doc - v) * scale.
 * Padding values are in screen pixels so the margins look the same at every zoom.
 */
@Stable
private class PdfViewState(
    initialLayout: PdfLayout,
    val screenW: Float,
    val screenH: Float,
    centered: Boolean,
    private val scope: CoroutineScope,
) {
    var layout by mutableStateOf(initialLayout); private set
    var scale by mutableFloatStateOf(1f); private set
    var vx by mutableFloatStateOf(0f); private set
    var vy by mutableFloatStateOf(0f); private set

    private val topPad = if (centered) 0f else screenH * 0.14f
    private val bottomPad = if (centered) 0f else screenH * 0.25f
    private var anim: Job? = null

    val isFit get() = scale < 1.02f
    val center get() = Offset(screenW / 2f, screenH / 2f)
    private val sideMargin get() = (screenW - layout.fitW) / 2f

    /** Page under the middle of the screen (for the page counter). */
    val currentPage get() = layout.pageAt(vy + screenH / scale / 2f)

    /** Page at the top edge of the readable area (for prev/next navigation). */
    private val topPage get() = layout.pageAt(vy + topPad / scale + 1f)

    init {
        vx = -sideMargin
        vy = -topPad
        clamp()
    }

    private fun clamp() {
        val minX = -sideMargin / scale
        val maxX = layout.fitW + sideMargin / scale - screenW / scale
        vx = if (maxX <= minX) (minX + maxX) / 2f else vx.coerceIn(minX, maxX)
        val minY = -topPad / scale
        val maxY = layout.docH + bottomPad / scale - screenH / scale
        vy = if (maxY <= minY) (minY + maxY) / 2f else vy.coerceIn(minY, maxY)
    }

    /** Swap in a re-measured layout, keeping the same spot in the document on screen. */
    fun replaceLayout(newLayout: PdfLayout) {
        val (p, f, s) = position()
        layout = newLayout
        restore(p, f, s)
    }

    fun stop() {
        anim?.cancel()
    }

    fun panBy(screenDelta: Offset) {
        vx -= screenDelta.x / scale
        vy -= screenDelta.y / scale
        clamp()
    }

    /** Zoom so the document point under [focus] stays under it. */
    fun zoomAround(newScale: Float, focus: Offset) {
        val ns = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val dx = vx + focus.x / scale
        val dy = vy + focus.y / scale
        scale = ns
        vx = dx - focus.x / ns
        vy = dy - focus.y / ns
        clamp()
    }

    fun fling(v: Velocity) {
        stop()
        anim = scope.launch {
            var last = Offset.Zero
            Animatable(Offset.Zero, Offset.VectorConverter)
                .animateDecay(Offset(v.x, v.y), exponentialDecay(frictionMultiplier = 1.4f)) {
                    panBy(value - last)
                    last = value
                }
        }
    }

    fun animateZoom(target: Float, focus: Offset = center) {
        stop()
        val t = target.coerceIn(MIN_SCALE, MAX_SCALE)
        anim = scope.launch {
            animate(scale, t, animationSpec = tween(260)) { value, _ -> zoomAround(value, focus) }
        }
    }

    fun zoomStep(factor: Float) = animateZoom(scale * factor)

    fun goToPage(i: Int) {
        stop()
        val page = i.coerceIn(0, layout.count - 1)
        val target = layout.tops[page] - topPad / scale
        anim = scope.launch {
            animate(vy, target, animationSpec = tween(320)) { value, _ ->
                vy = value
                clamp()
            }
        }
    }

    fun nextPage() = goToPage(topPage + 1)

    fun prevPage() {
        val p = topPage
        // Mid-page: go back to the top of this page first.
        if (vy > layout.tops[p] - topPad / scale + 24f / scale) goToPage(p) else goToPage(p - 1)
    }

    fun crown(px: Float) {
        stop()
        panBy(Offset(0f, -px))
    }

    /** Crown in zoom mode: turning it "down" zooms in around the centre. */
    fun crownZoom(px: Float) {
        stop()
        zoomAround(scale * kotlin.math.exp(px * 0.004f), center)
    }

    fun position(): Triple<Int, Float, Float> {
        val y = vy + topPad / scale
        val p = layout.pageAt(y)
        return Triple(p, ((y - layout.tops[p]) / layout.heights[p]).coerceIn(0f, 1f), scale)
    }

    fun restore(page: Int, frac: Float, s: Float) {
        val p = page.coerceIn(0, layout.count - 1)
        scale = s.coerceIn(MIN_SCALE, MAX_SCALE)
        vx = layout.fitW / 2f - screenW / scale / 2f
        vy = layout.tops[p] + frac * layout.heights[p] - topPad / scale
        clamp()
    }
}

private data class TileKey(val page: Int, val level: Float, val tx: Int, val ty: Int)

/** A sharp piece of a page; x/y/w/h are document coordinates. */
private class Tile(val key: TileKey, val x: Float, val y: Float, val w: Float, val h: Float, val image: ImageBitmap)

private fun levelFor(scale: Float): Float? =
    if (scale < 1.08f) null else TILE_LEVELS.firstOrNull { it >= scale * 0.92f } ?: TILE_LEVELS.last()

/** Tiles covering the viewport (plus [margin] extra tiles around it), nearest to the centre first. */
private fun tilesFor(
    layout: PdfLayout, vx: Float, vy: Float, visW: Float, visH: Float, level: Float, margin: Int,
): List<TileKey> {
    val d = TILE_PX / level
    val left = vx - margin * d
    val right = vx + visW + margin * d
    val top = vy - margin * d
    val bottom = vy + visH + margin * d
    val cx = vx + visW / 2f
    val cy = vy + visH / 2f
    val out = ArrayList<Pair<TileKey, Float>>()
    for (p in layout.visible(top, bottom)) {
        val pageTop = layout.tops[p]
        val pageBottom = pageTop + layout.heights[p]
        val x0 = floor(max(left, 0f) / d).toInt()
        val x1 = floor((min(right, layout.fitW) - 0.01f) / d).toInt()
        val y0 = floor((max(top, pageTop) - pageTop) / d).toInt()
        val y1 = floor((min(bottom, pageBottom) - pageTop - 0.01f) / d).toInt()
        for (ty in y0..y1) for (tx in x0..x1) {
            val dx = (tx + 0.5f) * d - cx
            val dy = pageTop + (ty + 0.5f) * d - cy
            out += TileKey(p, level, tx, ty) to (dx * dx + dy * dy)
        }
    }
    return out.sortedBy { it.second }.map { it.first }
}

/** Remembers where you were in each document. */
private object PdfPositions {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pdf_positions", Context.MODE_PRIVATE)

    fun save(ctx: Context, key: String, pos: Triple<Int, Float, Float>) {
        prefs(ctx).edit().putString(key, "${pos.first};${pos.second};${pos.third}").apply()
    }

    fun load(ctx: Context, key: String): Triple<Int, Float, Float>? {
        val parts = prefs(ctx).getString(key, null)?.split(';') ?: return null
        if (parts.size != 3) return null
        return Triple(
            parts[0].toIntOrNull() ?: return null,
            parts[1].toFloatOrNull() ?: return null,
            parts[2].toFloatOrNull() ?: return null,
        )
    }
}

// ---------------------------------------------------------------- UI

@Composable
fun PdfViewer(file: File) {
    PagedViewer(file, open = { PdfDoc(it) }) {
        if (it is SecurityException) "This PDF is password-protected and can't be opened."
        else "Couldn't open this PDF."
    }
}

@Composable
fun ImageViewer(file: File) {
    PagedViewer(file, open = { ImageDoc(it) }) { "Couldn't open this image." }
}

@Composable
private fun PagedViewer(file: File, open: (File) -> PageSource, errorText: (Throwable) -> String) {
    val result by produceState<Result<PageSource>?>(null, file) {
        val r = withContext(Dispatchers.IO) { runCatching { open(file) } }
        value = r
        awaitDispose { r.getOrNull()?.close() }
    }
    when (val r = result) {
        null -> Loading()
        else -> r.fold(
            onSuccess = {
                if (it.pageCount == 0) ErrorMessage("This document has no pages")
                else PagedScreen(it, "${file.name}:${file.length()}")
            },
            onFailure = { ErrorMessage(errorText(it)) },
        )
    }
}

@Composable
private fun PagedScreen(doc: PageSource, positionKey: String) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val ctx = LocalContext.current
        val haptics = LocalHapticFeedback.current
        val sw = constraints.maxWidth.toFloat()
        val sh = constraints.maxHeight.toFloat()
        val scope = rememberCoroutineScope()
        val gap = with(density) { 8.dp.toPx() }
        val fitW = remember(doc, sw, sh) {
            if (doc.isImage) {
                // Whole image visible: fit inside the screen, not just to its width.
                val s = doc.pageSize(0)
                min(sw * 0.92f, sh * 0.92f * s[0] / s[1])
            } else {
                sw * 0.88f
            }
        }
        val state = remember(doc, sw, sh) {
            PdfViewState(PdfLayout(doc, fitW, gap), sw, sh, centered = doc.isImage, scope)
        }
        val pageBackground = if (doc.isImage) Color.Black else Color.White

        val base = remember(doc) { mutableStateMapOf<Int, ImageBitmap>() }      // whole pages, sharp at 1×
        val tiles = remember(doc) { mutableStateMapOf<TileKey, Tile>() }        // sharp pieces when zoomed

        // Remember position per document.
        LaunchedEffect(state) {
            PdfPositions.load(ctx, positionKey)?.let { (p, f, s) -> state.restore(p, f, s) }
        }
        DisposableEffect(state) {
            onDispose { PdfPositions.save(ctx, positionKey, state.position()) }
        }

        // Measure the remaining pages in the background; re-layout only if sizes differ.
        LaunchedEffect(state) {
            if (doc.loadExactSizes()) {
                state.replaceLayout(PdfLayout(doc, fitW, gap))
                base.clear()
                tiles.clear()
            }
        }

        // One render worker (PdfRenderer is single-threaded) that always does the most useful job next:
        // visible pages → visible sharp tiles → neighbouring pages → tiles just off-screen.
        LaunchedEffect(state) {
            val wake = Channel<Unit>(Channel.CONFLATED)
            launch {
                snapshotFlow { arrayOf<Any>(state.scale, state.vx, state.vy, state.layout) }
                    .collect { wake.trySend(Unit) }
            }
            while (true) {
                val layout = state.layout
                val s = state.scale
                val vx = state.vx
                val vy = state.vy
                val visW = sw / s
                val visH = sh / s
                val vis = layout.visible(vy, vy + visH)
                val level = levelFor(s)

                val visibleBase = vis.firstOrNull { base[it] == null }
                val visibleTile = level?.let { l -> tilesFor(layout, vx, vy, visW, visH, l, 0).firstOrNull { it !in tiles } }
                val nearBase = if (visibleBase == null && visibleTile == null) {
                    listOf(vis.last + 1, vis.first - 1, vis.last + 2).firstOrNull { it in 0 until layout.count && base[it] == null }
                } else null
                val nearTile = if (visibleBase == null && visibleTile == null && nearBase == null) {
                    level?.let { l -> tilesFor(layout, vx, vy, visW, visH, l, 1).firstOrNull { it !in tiles } }
                } else null

                val pageJob = visibleBase ?: nearBase
                val tileJob = if (visibleBase != null) null else visibleTile ?: nearTile
                when {
                    pageJob != null && (visibleBase != null || tileJob == null) -> {
                        val img = doc.render(pageJob, layout.fitW.roundToInt(), layout.heights[pageJob].roundToInt(), null)
                            ?: break
                        if (state.layout === layout) {
                            base[pageJob] = img
                            val c = state.currentPage
                            base.keys.filter { abs(it - c) > 4 }.forEach { base.remove(it) }
                        }
                    }
                    tileJob != null -> {
                        val tile = renderTile(doc, layout, tileJob) ?: break
                        if (state.layout === layout) {
                            tiles[tileJob] = tile
                            if (tiles.size > MAX_TILES) evictFarTiles(tiles, state.vx + visW / 2f, state.vy + visH / 2f)
                        }
                    }
                    else -> wake.receive() // everything useful is rendered; wait for the view to change
                }
            }
        }

        // Controls overlay.
        val multiPage = doc.pageCount > 1
        var hudVisible by remember { mutableStateOf(true) }
        var hudTick by remember { mutableIntStateOf(0) }
        var pickerOpen by remember { mutableStateOf(false) }
        LaunchedEffect(hudVisible, hudTick, pickerOpen) {
            if (hudVisible && !pickerOpen) {
                delay(3500)
                hudVisible = false
            }
        }
        val poke = { hudTick++ }
        val page by remember { derivedStateOf { state.currentPage } }
        var pillVisible by remember { mutableStateOf(false) }
        LaunchedEffect(page) {
            pillVisible = true
            delay(1300)
            pillVisible = false
        }

        val canvasFocus = remember { FocusRequester() }
        LaunchedEffect(pickerOpen) { if (!pickerOpen) canvasFocus.requestFocus() }

        val edgePx = with(density) { 18.dp.toPx() }
        Canvas(
            Modifier
                .fillMaxSize()
                .onRotaryScrollEvent {
                    if (Settings.crownZooms) state.crownZoom(it.verticalScrollPixels)
                    else state.crown(it.verticalScrollPixels)
                    true
                }
                .focusRequester(canvasFocus)
                .focusable()
                .pointerInput(state) {
                    detectTapGestures(
                        onTap = { hudVisible = !hudVisible },
                        onDoubleTap = { pos ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            state.animateZoom(if (state.scale < 1.6f) 2.5f else 1f, pos)
                        },
                    )
                }
                .pointerInput(state) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        state.stop()
                        // Leave swipes from the left edge to the system "swipe back" gesture.
                        if (down.position.x < edgePx) return@awaitEachGesture
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var moving = false
                        var multiTouch = false
                        var travelled = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            if (pressed > 1) multiTouch = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (!moving) {
                                travelled += pan
                                if (multiTouch && abs(zoom - 1f) > 0.01f) {
                                    moving = true
                                } else if (travelled.getDistance() > slop) {
                                    // Not zoomed in + swipe right = go back, like everywhere else on Wear OS.
                                    if (!multiTouch && state.isFit && travelled.x > abs(travelled.y)) {
                                        return@awaitEachGesture
                                    }
                                    moving = true
                                }
                            }
                            if (moving) {
                                if (zoom != 1f) state.zoomAround(state.scale * zoom, event.calculateCentroid(useCurrent = true))
                                state.panBy(pan)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            event.changes.firstOrNull { it.id == down.id && it.pressed }
                                ?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                        }
                        if (moving && !multiTouch) state.fling(tracker.calculateVelocity())
                    }
                },
        ) {
            val layout = state.layout
            val s = state.scale
            val vx = state.vx
            val vy = state.vy
            val visH = size.height / s
            val visW = size.width / s

            fun drawPiece(img: ImageBitmap, x: Float, y: Float, w: Float, h: Float) {
                // Round each edge separately so neighbouring tiles meet without gaps.
                val l = ((x - vx) * s).roundToInt()
                val t = ((y - vy) * s).roundToInt()
                val r = ((x + w - vx) * s).roundToInt()
                val b = ((y + h - vy) * s).roundToInt()
                drawImage(img, dstOffset = IntOffset(l, t), dstSize = IntSize(r - l, b - t), filterQuality = FilterQuality.Medium)
            }

            for (i in layout.visible(vy, vy + visH)) {
                drawRect(pageBackground, Offset(-vx * s, (layout.tops[i] - vy) * s), Size(layout.fitW * s, layout.heights[i] * s))
                base[i]?.let { drawPiece(it, 0f, layout.tops[i], layout.fitW, layout.heights[i]) }
            }
            // Tiles from other zoom levels first (as a better-than-nothing fallback), current level on top.
            val level = levelFor(s)
            tiles.values
                .filter { it.x < vx + visW && it.x + it.w > vx && it.y < vy + visH && it.y + it.h > vy }
                .sortedBy { if (it.key.level == level) Float.MAX_VALUE else it.key.level }
                .forEach { drawPiece(it.image, it.x, it.y, it.w, it.h) }
        }

        // Small page counter while scrolling with the controls hidden.
        AnimatedVisibility(
            visible = multiPage && pillVisible && !hudVisible && !pickerOpen,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        ) {
            Pill("${page + 1} / ${doc.pageCount}")
        }

        AnimatedVisibility(
            visible = hudVisible && !pickerOpen,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (multiPage) {
                    Pill(
                        "Page ${page + 1} / ${doc.pageCount}  ▾",
                        Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                    ) { pickerOpen = true }
                    RoundButton("‹", Modifier.align(Alignment.CenterStart).padding(start = 6.dp)) {
                        state.prevPage(); poke()
                    }
                    RoundButton("›", Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)) {
                        state.nextPage(); poke()
                    }
                }
                Pill(
                    if (Settings.crownZooms) "Crown: zoom" else "Crown: scroll",
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 58.dp),
                ) { Settings.updateCrownZooms(!Settings.crownZooms); poke() }
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton("−") { state.zoomStep(1f / 1.5f); poke() }
                    Pill(if (state.isFit) "Fit" else "${(state.scale * 100).roundToInt()}%") {
                        state.animateZoom(1f); poke()
                    }
                    RoundButton("+") { state.zoomStep(1.5f); poke() }
                }
            }
        }

        if (pickerOpen) {
            PagePicker(
                count = doc.pageCount,
                initial = page,
                onGo = {
                    state.goToPage(it)
                    pickerOpen = false
                    poke()
                },
                onDismiss = { pickerOpen = false },
            )
        }
    }
}

private suspend fun renderTile(doc: PageSource, layout: PdfLayout, key: TileKey): Tile? {
    val d = TILE_PX / key.level
    val pageTop = layout.tops[key.page]
    val x = key.tx * d
    val y = pageTop + key.ty * d
    val w = min(d, layout.fitW - x)
    val h = min(d, pageTop + layout.heights[key.page] - y)
    if (w <= 0f || h <= 0f) return null
    val k = layout.pointsToDoc[key.page] * key.level
    val m = Matrix().apply {
        setScale(k, k)
        postTranslate(-key.tx * TILE_PX.toFloat(), -key.ty * TILE_PX.toFloat())
    }
    val img = doc.render(key.page, ceil(w * key.level).toInt(), ceil(h * key.level).toInt(), m) ?: return null
    return Tile(key, x, y, w, h, img)
}

private fun evictFarTiles(tiles: MutableMap<TileKey, Tile>, cx: Float, cy: Float) {
    val extra = tiles.size - MAX_TILES
    if (extra <= 0) return
    tiles.values
        .sortedByDescending { t ->
            val dx = t.x + t.w / 2 - cx
            val dy = t.y + t.h / 2 - cy
            dx * dx + dy * dy
        }
        .take(extra)
        .forEach { tiles.remove(it.key) }
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Text(
        text,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE6202124))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun RoundButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0xE6202124))
            .clickable(onClick = onClick),
    ) {
        Text(label, fontSize = 20.sp)
    }
}

@Composable
private fun PagePicker(count: Int, initial: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableIntStateOf(initial) }
    var acc by remember { mutableFloatStateOf(0f) }
    val focus = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun set(v: Int) {
        val nv = v.coerceIn(0, count - 1)
        if (nv != value) {
            value = nv
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF0000000))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
            .onRotaryScrollEvent {
                acc += it.verticalScrollPixels
                val step = 28f
                while (acc >= step) { set(value + 1); acc -= step }
                while (acc <= -step) { set(value - 1); acc += step }
                true
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Go to page", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton("−") { set(value - 1) }
                Text(
                    "${value + 1}",
                    fontSize = 34.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(84.dp),
                )
                RoundButton("+") { set(value + 1) }
            }
            Text("of $count · turn the crown", fontSize = 10.sp, color = Color.Gray)
            Spacer(Modifier.height(10.dp))
            Pill("Go", Modifier.width(80.dp)) { onGo(value) }
        }
    }
}
