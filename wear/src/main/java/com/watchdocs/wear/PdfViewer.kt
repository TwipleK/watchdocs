package com.watchdocs.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f

// ---------------------------------------------------------------- PDF document

class PdfDoc(file: File) : Closeable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(pfd)
    } catch (e: Exception) {
        pfd.close(); throw e
    }
    val pageCount = renderer.pageCount

    /** [width, height] in PDF points for every page. */
    val sizes: List<IntArray> = List(pageCount) { i ->
        renderer.openPage(i).use { intArrayOf(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1)) }
    }

    // PdfRenderer allows only one open page at a time.
    private val mutex = Mutex()
    private var closed = false

    /** Renders a page into a w×h bitmap. A null matrix fits the whole page into the bitmap. */
    suspend fun render(index: Int, w: Int, h: Int, matrix: Matrix?): ImageBitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock null
            renderer.openPage(index).use { page ->
                val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp.asImageBitmap()
            }
        }
    }

    override fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                closed = true
                renderer.close()
                pfd.close()
            }
        }
    }
}

// ---------------------------------------------------------------- geometry & view state

/** Pages stacked vertically in "document pixels" (fit-to-width size, scale 1). */
private class PdfLayout(sizes: List<IntArray>, val fitW: Float, gap: Float) {
    val heights = FloatArray(sizes.size) { fitW * sizes[it][1] / sizes[it][0] }
    val tops = FloatArray(sizes.size)
    val docH: Float
    val pointsToDoc = FloatArray(sizes.size) { fitW / sizes[it][0] }

    init {
        var y = 0f
        for (i in heights.indices) {
            tops[i] = y
            y += heights[i] + gap
        }
        docH = (y - gap).coerceAtLeast(0f)
    }

    fun pageAt(y: Float): Int {
        var lo = 0
        var hi = tops.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun visible(top: Float, bottom: Float): IntRange {
        val a = pageAt(top)
        var b = a
        while (b + 1 < tops.size && tops[b + 1] < bottom) b++
        return a..b
    }
}

/**
 * Viewport over the document: screen = (doc - v) * scale.
 * Padding values are in screen pixels so the margins look the same at every zoom.
 */
@Stable
private class PdfViewState(
    val layout: PdfLayout,
    val screenW: Float,
    val screenH: Float,
    private val scope: CoroutineScope,
) {
    var scale by mutableFloatStateOf(1f); private set
    var vx by mutableFloatStateOf(0f); private set
    var vy by mutableFloatStateOf(0f); private set

    private val sideMargin = (screenW - layout.fitW) / 2f
    private val topPad = screenH * 0.14f
    private val bottomPad = screenH * 0.25f
    private var anim: Job? = null

    val isFit get() = scale < 1.02f
    val center get() = Offset(screenW / 2f, screenH / 2f)

    /** Page under the middle of the screen (for the page counter). */
    val currentPage get() = layout.pageAt(vy + screenH / scale / 2f)

    /** Page at the top edge of the readable area (for prev/next navigation). */
    private val topPage get() = layout.pageAt(vy + topPad / scale + 1f)

    init {
        vx = -sideMargin
        vy = -topPad
    }

    private fun clamp() {
        val minX = -sideMargin / scale
        val maxX = layout.fitW + sideMargin / scale - screenW / scale
        vx = if (maxX <= minX) (minX + maxX) / 2f else vx.coerceIn(minX, maxX)
        val minY = -topPad / scale
        val maxY = layout.docH + bottomPad / scale - screenH / scale
        vy = if (maxY <= minY) minY else vy.coerceIn(minY, maxY)
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
        val page = i.coerceIn(0, layout.tops.size - 1)
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

    fun position(): Triple<Int, Float, Float> {
        val y = vy + topPad / scale
        val p = layout.pageAt(y)
        return Triple(p, ((y - layout.tops[p]) / layout.heights[p]).coerceIn(0f, 1f), scale)
    }

    fun restore(page: Int, frac: Float, s: Float) {
        val p = page.coerceIn(0, layout.tops.size - 1)
        scale = s.coerceIn(MIN_SCALE, MAX_SCALE)
        vx = layout.fitW / 2f - screenW / scale / 2f
        vy = layout.tops[p] + frac * layout.heights[p] - topPad / scale
        clamp()
    }
}

private class Tile(val page: Int, val x: Float, val y: Float, val w: Float, val h: Float, val image: ImageBitmap)

/** Remembers where you were in each PDF. */
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
    val result by produceState<Result<PdfDoc>?>(null, file) {
        val r = withContext(Dispatchers.IO) { runCatching { PdfDoc(file) } }
        value = r
        awaitDispose { r.getOrNull()?.close() }
    }
    when (val r = result) {
        null -> Loading()
        else -> r.fold(
            onSuccess = {
                if (it.pageCount == 0) ErrorMessage("This PDF has no pages")
                else PdfScreen(it, "${file.name}:${file.length()}")
            },
            onFailure = {
                ErrorMessage(
                    if (it is SecurityException) "This PDF is password-protected and can't be opened."
                    else "Couldn't open this PDF."
                )
            },
        )
    }
}

@Composable
private fun PdfScreen(doc: PdfDoc, positionKey: String) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val ctx = LocalContext.current
        val haptics = LocalHapticFeedback.current
        val sw = constraints.maxWidth.toFloat()
        val sh = constraints.maxHeight.toFloat()
        val scope = rememberCoroutineScope()
        val layout = remember(doc, sw) { PdfLayout(doc.sizes, sw * 0.88f, with(density) { 8.dp.toPx() }) }
        val state = remember(layout, sh) { PdfViewState(layout, sw, sh, scope) }

        // Remember position per document.
        LaunchedEffect(state) {
            PdfPositions.load(ctx, positionKey)?.let { (p, f, s) -> state.restore(p, f, s) }
        }
        DisposableEffect(state) {
            onDispose { PdfPositions.save(ctx, positionKey, state.position()) }
        }

        // Low-res page bitmaps (exactly sharp at fit-to-width), kept for nearby pages only.
        val base = remember(doc) { mutableStateMapOf<Int, ImageBitmap>() }
        LaunchedEffect(state) {
            snapshotFlow { layout.visible(state.vy, state.vy + sh / state.scale) }
                .distinctUntilChanged()
                .collectLatest { vis ->
                    val first = (vis.first - 1).coerceAtLeast(0)
                    val last = (vis.last + 1).coerceAtMost(doc.pageCount - 1)
                    base.keys.filter { it < first - 3 || it > last + 3 }.forEach { base.remove(it) }
                    for (i in vis.toList() + listOf(vis.last + 1, vis.first - 1)) {
                        if (i !in first..last || base[i] != null) continue
                        doc.render(i, layout.fitW.roundToInt(), layout.heights[i].roundToInt(), null)
                            ?.let { base[i] = it }
                    }
                }
        }

        // High-res tiles of exactly what's on screen, re-rendered once you stop moving.
        var tiles by remember(doc) { mutableStateOf<List<Tile>>(emptyList()) }
        LaunchedEffect(state) {
            snapshotFlow { Triple(state.scale, state.vx, state.vy) }.collectLatest { (s, vx, vy) ->
                delay(120)
                if (s < 1.05f) {
                    tiles = emptyList()
                    return@collectLatest
                }
                val visW = sw / s
                val visH = sh / s
                val out = mutableListOf<Tile>()
                for (i in layout.visible(vy, vy + visH)) {
                    val l = maxOf(vx, 0f)
                    val r = minOf(vx + visW, layout.fitW)
                    val t = maxOf(vy, layout.tops[i])
                    val b = minOf(vy + visH, layout.tops[i] + layout.heights[i])
                    if (r <= l || b <= t) continue
                    val k = layout.pointsToDoc[i] * s
                    val m = Matrix().apply {
                        setScale(k, k)
                        postTranslate(-l * s, (layout.tops[i] - t) * s)
                    }
                    val w = ceil((r - l) * s).toInt()
                    val h = ceil((b - t) * s).toInt()
                    doc.render(i, w, h, m)?.let { out += Tile(i, l, t, r - l, b - t, it) }
                }
                tiles = out
            }
        }

        // Controls overlay.
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
                    state.crown(it.verticalScrollPixels)
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
            val s = state.scale
            val vx = state.vx
            val vy = state.vy
            for (i in layout.visible(vy, vy + size.height / s)) {
                val x = -vx * s
                val y = (layout.tops[i] - vy) * s
                val w = layout.fitW * s
                val h = layout.heights[i] * s
                drawRect(Color.White, Offset(x, y), Size(w, h))
                base[i]?.let { img ->
                    drawImage(
                        img,
                        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
            for (t in tiles) {
                drawImage(
                    t.image,
                    dstOffset = IntOffset(((t.x - vx) * s).roundToInt(), ((t.y - vy) * s).roundToInt()),
                    dstSize = IntSize((t.w * s).roundToInt(), (t.h * s).roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }

        // Small page counter while scrolling with the controls hidden.
        AnimatedVisibility(
            visible = pillVisible && !hudVisible && !pickerOpen,
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
