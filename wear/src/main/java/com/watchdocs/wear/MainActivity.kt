package com.watchdocs.wear

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.navigation.rememberSwipeDismissableNavHostState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        DocStore.refresh(this)
        setContent { App() }
    }

    override fun onResume() {
        super.onResume()
        DocStore.refresh(this)
    }
}

@Composable
fun App() {
    MaterialTheme {
        val nav = rememberSwipeDismissableNavController()
        val swipeState = rememberSwipeToDismissBoxState()
        CompositionLocalProvider(LocalSwipeState provides swipeState) {
        SwipeDismissableNavHost(
            navController = nav,
            startDestination = "list",
            state = rememberSwipeDismissableNavHostState(swipeToDismissBoxState = swipeState),
        ) {
            composable("list") {
                DocListScreen(
                    onOpen = { nav.navigate("view/" + Uri.encode(it)) },
                    onDelete = { nav.navigate("delete/" + Uri.encode(it)) },
                )
            }
            composable("view/{name}") { entry ->
                ViewerScreen(entry.arguments?.getString("name").orEmpty())
            }
            composable("delete/{name}") { entry ->
                val ctx = LocalContext.current
                val name = entry.arguments?.getString("name").orEmpty()
                DeleteScreen(
                    name = name,
                    onConfirm = { DocStore.delete(ctx, name); nav.popBackStack() },
                    onCancel = { nav.popBackStack() },
                )
            }
        }
        }
    }
}

@Composable
fun DocListScreen(onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    val docs by DocStore.docs.collectAsState()
    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { ListHeader { Text("Documents") } }
            if (docs.isEmpty()) {
                item {
                    Text(
                        "No documents yet.\n\nOpen WatchDocs on your phone and send a PDF, Word, Excel file or image.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.body2,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
            items(docs, key = { it.name }) { file ->
                DocItem(file, onClick = { onOpen(file.name) }, onLongClick = { onDelete(file.name) })
            }
            if (docs.isNotEmpty()) {
                item {
                    Text(
                        "Long-press to delete",
                        style = MaterialTheme.typography.caption3,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item { ListHeader { Text("Settings") } }
            item {
                ToggleChip(
                    checked = Settings.crownZooms,
                    onCheckedChange = { Settings.updateCrownZooms(it) },
                    label = { Text("Crown zooms") },
                    secondaryLabel = { Text(if (Settings.crownZooms) "Crown zooms PDFs & images" else "Crown scrolls") },
                    toggleControl = { Switch(checked = Settings.crownZooms) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocItem(file: File, onClick: () -> Unit, onLongClick: () -> Unit) {
    val type = DocType.of(file.name)
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colors.surface)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(type.color)),
        ) {
            Text(type.label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                file.nameWithoutExtension,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.button,
            )
            Text(formatSize(file.length()), style = MaterialTheme.typography.caption3, color = Color.LightGray)
        }
    }
}

@Composable
private fun DeleteScreen(name: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Delete?", style = MaterialTheme.typography.title3)
        Spacer(Modifier.height(4.dp))
        Text(
            name,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.body2,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCancel, colors = ButtonDefaults.secondaryButtonColors()) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel")
            }
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE53935))) {
                Icon(Icons.Filled.Check, contentDescription = "Delete")
            }
        }
    }
}
