package com.watchdocs.mobile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat

private val MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "image/*",
)

class MainActivity : ComponentActivity() {
    private val vm: TransferViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { AppTheme { HomeScreen(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshWatch()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.enqueue(uris)
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(vm: TransferViewModel) {
    val watch by vm.watch.collectAsState()
    val transfers by vm.transfers.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.enqueue(uris)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("WatchDocs") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WatchCard(watch, onRefresh = vm::refreshWatch)
            Button(
                onClick = { picker.launch(MIME_TYPES) },
                enabled = watch is WatchState.Ready,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Send documents to watch")
            }
            Text(
                "PDF, Word (.docx), Excel (.xlsx) and images (big photos are shrunk for the watch). " +
                    "You can also use Share → WatchDocs from any app. " +
                    "Manage and delete documents on the watch (long-press in the list).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (transfers.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Transfers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::clearFinished) { Text("Clear") }
                }
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(transfers, key = { it.id }) { TransferRow(it) }
            }
        }
    }
}

@Composable
private fun WatchCard(state: WatchState, onRefresh: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                WatchState.Searching -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                is WatchState.Ready -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                else -> Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val (title, body) = when (state) {
                    WatchState.Searching -> "Looking for your watch…" to null
                    is WatchState.Ready -> "Connected to ${state.name}" to "Ready to receive documents"
                    is WatchState.AppMissing -> "${state.name} is connected" to
                        "But WatchDocs isn't installed on the watch yet."
                    WatchState.NoWatch -> "No watch connected" to "Make sure your watch is paired and nearby."
                    is WatchState.Error -> "Wear OS unavailable" to state.message
                }
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (body != null) Text(body, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
        }
    }
}

@Composable
private fun TransferRow(t: Transfer) {
    ListItem(
        headlineContent = { Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                val text = when (t.status) {
                    Status.QUEUED -> "Waiting…"
                    Status.SENDING -> if (t.size > 0) "Sending ${(t.progress * 100).toInt()}%" else "Sending…"
                    Status.WAITING -> "Saving on watch…"
                    Status.DONE -> t.message ?: "Done"
                    Status.FAILED -> "Failed: ${t.message}"
                }
                Text(
                    text,
                    color = if (t.status == Status.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (t.status == Status.SENDING) {
                    if (t.size > 0) LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        },
        trailingContent = {
            when (t.status) {
                Status.DONE -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Status.FAILED -> Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
                else -> {}
            }
        },
    )
}
