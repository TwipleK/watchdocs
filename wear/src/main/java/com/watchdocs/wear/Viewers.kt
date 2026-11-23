package com.watchdocs.wear

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.SwipeToDismissBoxState
import androidx.wear.compose.foundation.edgeSwipeToDismiss
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** Provided by the nav host so horizontally scrolling viewers can still be swiped away from the left edge. */
val LocalSwipeState = staticCompositionLocalOf<SwipeToDismissBoxState?> { null }

// ---------------------------------------------------------------- shared helpers

@OptIn(ExperimentalWearFoundationApi::class)
@Composable
fun Modifier.crownScroll(state: ScrollableState): Modifier {
    val focusRequester = rememberActiveFocusRequester()
    return this.rotaryScrollable(RotaryScrollableDefaults.behavior(scrollableState = state), focusRequester)
}

@Composable
fun Modifier.edgeDismiss(): Modifier {
    val state = LocalSwipeState.current
    return if (state != null) this.edgeSwipeToDismiss(state) else this
}

@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorMessage(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.body2)
    }
}

@Composable
private fun <T> loadInBackground(file: File, load: (File) -> T): Result<T>? {
    val result by produceState<Result<T>?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { load(file) } }
    }
    return result
}

@Composable
fun ViewerScreen(name: String) {
    val ctx = LocalContext.current
    val file = remember(name) { DocStore.file(ctx, name) }
    KeepScreenOn()
    if (!file.exists()) {
        ErrorMessage("File not found")
        return
    }
    when (DocType.of(name)) {
        DocType.PDF -> PdfViewer(file)
        DocType.DOCX -> DocxViewer(file)
        DocType.XLSX -> XlsxViewer(file)
        DocType.UNKNOWN -> ErrorMessage("Unsupported file type")
    }
}

// ---------------------------------------------------------------- DOCX

private val TEXT_SIZES = listOf(13f, 16f, 20f)

@Composable
fun DocxViewer(file: File) {
    when (val r = loadInBackground(file) { DocxParser.parse(it) }) {
        null -> Loading()
        else -> r.fold(
            onSuccess = { TextDoc(it) },
            onFailure = { ErrorMessage("Couldn't read this Word document.") },
        )
    }
}

@Composable
private fun TextDoc(paras: List<Para>) {
    val config = LocalConfiguration.current
    val screenW = config.screenWidthDp.dp
    val screenH = config.screenHeightDp.dp
    var sizeIdx by rememberSaveable { mutableIntStateOf(1) }
    val base = TEXT_SIZES[sizeIdx]
    val listState = rememberLazyListState()

    Scaffold(positionIndicator = { PositionIndicator(lazyListState = listState) }) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .crownScroll(listState)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { sizeIdx = (sizeIdx + 1) % TEXT_SIZES.size })
                },
            contentPadding = PaddingValues(
                start = screenW * 0.12f, end = screenW * 0.12f,
                top = screenH * 0.15f, bottom = screenH * 0.3f,
            ),
            verticalArrangement = Arrangement.spacedBy((base * 0.6f).dp),
        ) {
            item {
                Text(
                    "double-tap to change text size",
                    fontSize = 9.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (paras.isEmpty()) item { Text("This document has no text.") }
            items(paras) { ParaText(it, base) }
        }
    }
}

@Composable
private fun ParaText(p: Para, base: Float) {
    when (p.style) {
        ParaStyle.TITLE -> Text(p.text, fontSize = (base * 1.45f).sp, fontWeight = FontWeight.Bold)
        ParaStyle.HEADING -> Text(
            p.text, fontSize = (base * 1.2f).sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF90CAF9),
        )
        ParaStyle.LIST -> Text("•  " + p.text, fontSize = base.sp, lineHeight = (base * 1.35f).sp)
        ParaStyle.TABLE_ROW -> Text(
            p.text,
            fontSize = (base * 0.85f).sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF202124), RoundedCornerShape(6.dp))
                .padding(6.dp),
        )
        ParaStyle.NORMAL -> Text(p.text, fontSize = base.sp, lineHeight = (base * 1.35f).sp)
    }
}

// ---------------------------------------------------------------- XLSX

@Composable
fun XlsxViewer(file: File) {
    when (val r = loadInBackground(file) { XlsxParser.parse(it) }) {
        null -> Loading()
        else -> r.fold(
            onSuccess = { SheetsView(it) },
            onFailure = { ErrorMessage("Couldn't read this Excel file.") },
        )
    }
}

@Composable
private fun SheetsView(sheets: List<Sheet>) {
    var idx by rememberSaveable { mutableIntStateOf(0) }
    val sheet = sheets[idx.coerceIn(sheets.indices)]
    androidx.compose.runtime.key(idx) {
        SheetGrid(
            sheet = sheet,
            sheetLabel = if (sheets.size > 1) "${sheet.name}  (${idx + 1}/${sheets.size}) ›" else null,
            onNextSheet = { idx = (idx + 1) % sheets.size },
        )
    }
}

private val numberRegex = Regex("^-?[\\d.,]+%?$")

@Composable
private fun SheetGrid(sheet: Sheet, sheetLabel: String?, onNextSheet: () -> Unit) {
    val config = LocalConfiguration.current
    val screenW = config.screenWidthDp.dp
    val screenH = config.screenHeightDp.dp
    val cols = sheet.columnCount
    val widths: List<Dp> = remember(sheet) {
        (0 until cols).map { c ->
            val len = sheet.rows.asSequence().take(300).maxOfOrNull { it.getOrNull(c)?.length ?: 0 } ?: 0
            (len * 7 + 12).coerceIn(44, 140).dp
        }
    }
    val rowNumW = 30.dp
    val gridW = widths.fold(rowNumW) { a, b -> a + b }
    val sidePad = screenW * 0.15f
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().edgeDismiss().horizontalScroll(hScroll)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .width(maxOf(screenW, gridW + sidePad * 2))
                    .fillMaxHeight()
                    .crownScroll(listState),
                contentPadding = PaddingValues(
                    start = sidePad, end = sidePad,
                    top = screenH * if (sheetLabel != null) 0.3f else 0.2f,
                    bottom = screenH * 0.3f,
                ),
            ) {
                if (sheet.rows.isEmpty()) {
                    item { Text("Empty sheet", color = Color.Gray) }
                } else {
                    item {
                        GridRow {
                            Cell("", rowNumW, header = true)
                            for (c in 0 until cols) Cell(columnName(c), widths[c], header = true, center = true)
                        }
                    }
                    itemsIndexed(sheet.rows) { r, row ->
                        GridRow {
                            Cell((r + 1).toString(), rowNumW, header = true, center = true)
                            for (c in 0 until cols) {
                                val v = row.getOrNull(c).orEmpty()
                                Cell(v, widths[c], alignEnd = numberRegex.matches(v))
                            }
                        }
                    }
                    if (sheet.truncated) {
                        item {
                            Text(
                                "Showing the first 2000 rows × 60 columns",
                                fontSize = 10.sp, color = Color.Gray,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
        if (sheetLabel != null) {
            Text(
                sheetLabel,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 22.dp)
                    .width(screenW * 0.6f)
                    .background(Color(0xFF2E7D32), RoundedCornerShape(14.dp))
                    .clickable(onClick = onNextSheet)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun GridRow(content: @Composable () -> Unit) {
    Row(Modifier.height(IntrinsicSize.Min).background(Color(0xFF3C4043))) { content() }
}

@Composable
private fun Cell(
    text: String,
    width: Dp,
    header: Boolean = false,
    center: Boolean = false,
    alignEnd: Boolean = false,
) {
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .padding(0.5.dp)
            .background(if (header) Color(0xFF202124) else Color.Black)
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            fontSize = 11.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            color = if (header) Color.Gray else Color.White,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = when {
                center -> TextAlign.Center
                alignEnd -> TextAlign.End
                else -> TextAlign.Start
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
