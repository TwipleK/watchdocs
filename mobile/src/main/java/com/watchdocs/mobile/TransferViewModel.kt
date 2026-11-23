package com.watchdocs.mobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
import java.io.File
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
private val DOCUMENTS = setOf("pdf", "docx", "xlsx")
private val IMAGES = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
private const val MAX_IMAGE_DIM = 2048

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
            var ext = name.substringAfterLast('.', "").lowercase()
            val mime = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
            if (ext !in DOCUMENTS && ext !in IMAGES && mime.startsWith("image/")) {
                // Shared images sometimes come without a file extension.
                ext = mime.removePrefix("image/").let { if (it == "jpeg") "jpg" else it }
                name = "$name.$ext"
            }
            if (ext !in DOCUMENTS && ext !in IMAGES) {
                _transfers.update {
                    listOf(Transfer(id, name, size, Status.FAILED, message = "Only PDF, Word, Excel and images are supported")) + it
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
        var temp: File? = null
        try {
            val node = findWatch() ?: error("No watch with WatchDocs installed is reachable")
            var src = uri
            var sendName = t.name
            var sendSize = t.size
            if (t.name.substringAfterLast('.', "").lowercase() in IMAGES) {
                withContext(Dispatchers.IO) { shrinkImage(uri, t.name, t.size) }?.let { (file, name) ->
                    temp = file
                    src = Uri.fromFile(file)
                    sendName = name
                    sendSize = file.length()
                    set(id) { copy(name = name, size = sendSize) }
                }
            }
            val path = "/doc/$id/$sendSize/${Uri.encode(sendName)}"
            val channel = channelClient.openChannel(node.id, path).await()
            val out = channelClient.getOutputStream(channel).await()
            withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                val input = resolver.openInputStream(src) ?: error("Can't read the file")
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
                            if (sendSize > 0 && sent - lastReport > 128 * 1024) {
                                lastReport = sent
                                val p = sent.toFloat() / sendSize
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
            temp?.delete()
        }
    }

    /**
     * Big photos are slow to send over Bluetooth and far larger than a watch screen needs:
     * scale them to at most 2048 px, apply the EXIF rotation, and re-encode.
     * Returns null when the original is already small enough to send as-is.
     */
    private fun shrinkImage(uri: Uri, name: String, size: Long): Pair<File, String>? {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (maxDim <= MAX_IMAGE_DIM && rotation == 0f && size in 0..1_500_000) return null

        var sample = 1
        while (maxDim / (sample * 2) >= MAX_IMAGE_DIM) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = minOf(1f, MAX_IMAGE_DIM.toFloat() / maxOf(decoded.width, decoded.height))
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation)
        }
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        val keepAlpha = out.hasAlpha() && name.substringAfterLast('.', "").lowercase() in setOf("png", "webp", "gif")
        val outName = name.substringBeforeLast('.') + if (keepAlpha) ".png" else ".jpg"
        val file = File(app.cacheDir, "send_${System.nanoTime()}.tmp")
        file.outputStream().use { out.compress(if (keepAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, it) }
        return file to outName
    }

    fun clearFinished() {
        _transfers.update { list -> list.filter { it.status != Status.DONE && it.status != Status.FAILED } }
    }
}
