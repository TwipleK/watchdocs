package com.watchdocs.mobile

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class Status { QUEUED, SENDING, WAITING, DONE, FAILED }

data class Transfer(
    val id: String,
    val name: String,
    val size: Long,
    val status: Status,
    val progress: Float = 0f,
    val message: String? = null,
)

sealed interface WatchState {
    data object Searching : WatchState
    data class Ready(val name: String) : WatchState
    data class AppMissing(val name: String) : WatchState
    data object NoWatch : WatchState
    data class Error(val message: String) : WatchState
}

private const val CAPABILITY = "watchdocs_viewer"
private val SUPPORTED = setOf("pdf", "docx", "xlsx")

class TransferViewModel(app: Application) : AndroidViewModel(app) {
    private val channelClient = Wearable.getChannelClient(app)
    private val messageClient = Wearable.getMessageClient(app)
    private val capabilityClient = Wearable.getCapabilityClient(app)
    private val nodeClient = Wearable.getNodeClient(app)

    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers

    private val _watch = MutableStateFlow<WatchState>(WatchState.Searching)
    val watch: StateFlow<WatchState> = _watch

    private val queue = Channel<Pair<String, Uri>>(Channel.UNLIMITED)
    private val acks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val ackListener = MessageClient.OnMessageReceivedListener { event ->
        val id = String(event.data)
        when (event.path) {
            "/doc-ack" -> acks.remove(id)?.complete(true)
            "/doc-nack" -> acks.remove(id)?.complete(false)
        }
    }

    init {
        messageClient.addListener(ackListener)
        refreshWatch()
        viewModelScope.launch {
            for ((id, uri) in queue) process(id, uri)
        }
    }

    override fun onCleared() {
        messageClient.removeListener(ackListener)
    }

    fun refreshWatch() {
        viewModelScope.launch {
            _watch.value = WatchState.Searching
            _watch.value = try {
                val node = findWatch()
                if (node != null) {
                    WatchState.Ready(node.displayName)
                } else {
                    val connected = nodeClient.connectedNodes.await()
                    if (connected.isEmpty()) WatchState.NoWatch
                    else WatchState.AppMissing(connected.first().displayName)
                }
            } catch (e: Exception) {
                WatchState.Error(e.message ?: "Wear OS services unavailable")
            }
        }
    }

    private suspend fun findWatch(): Node? {
        val nodes = capabilityClient
            .getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .await().nodes
        return nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
    }

    fun enqueue(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        for (uri in uris) {
            var name = "document"
            var size = -1L
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            c.getString(0)?.let { name = it }
                            if (!c.isNull(1)) size = c.getLong(1)
                        }
                    }
            }
            if (name == "document") uri.lastPathSegment?.substringAfterLast('/')?.let { name = it }

            val id = UUID.randomUUID().toString()
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in SUPPORTED) {
                _transfers.update {
                    listOf(Transfer(id, name, size, Status.FAILED, message = "Only PDF, DOCX and XLSX are supported")) + it
                }
                continue
            }
            _transfers.update { listOf(Transfer(id, name, size, Status.QUEUED)) + it }
            queue.trySend(id to uri)
        }
    }

    private fun set(id: String, change: Transfer.() -> Transfer) {
        _transfers.update { list -> list.map { if (it.id == id) it.change() else it } }
    }

    private suspend fun process(id: String, uri: Uri) {
        val t = _transfers.value.firstOrNull { it.id == id } ?: return
        set(id) { copy(status = Status.SENDING, progress = 0f) }
        val ack = CompletableDeferred<Boolean>()
        acks[id] = ack
        try {
            val node = findWatch() ?: error("No watch with WatchDocs installed is reachable")
            val path = "/doc/$id/${t.size}/${Uri.encode(t.name)}"
            val channel = channelClient.openChannel(node.id, path).await()
            val out = channelClient.getOutputStream(channel).await()
            withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                val input = resolver.openInputStream(uri) ?: error("Can't read the file")
                input.use { inp ->
                    out.use { o ->
                        val buf = ByteArray(64 * 1024)
                        var sent = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            sent += n
                            if (t.size > 0 && sent - lastReport > 128 * 1024) {
                                lastReport = sent
                                val p = sent.toFloat() / t.size
                                set(id) { copy(progress = p) }
                            }
                        }
                        o.flush()
                    }
                }
            }
            set(id) { copy(status = Status.WAITING, progress = 1f) }
            when (withTimeoutOrNull(120_000) { ack.await() }) {
                true -> set(id) { copy(status = Status.DONE, message = "Saved on ${node.displayName}") }
                false -> set(id) { copy(status = Status.FAILED, message = "The watch couldn't save the file") }
                null -> set(id) { copy(status = Status.DONE, message = "Sent (no confirmation from watch)") }
            }
        } catch (e: Exception) {
            set(id) { copy(status = Status.FAILED, message = e.message ?: e.javaClass.simpleName) }
        } finally {
            acks.remove(id)
        }
    }

    fun clearFinished() {
        _transfers.update { list -> list.filter { it.status != Status.DONE && it.status != Status.FAILED } }
    }
}
