package com.watchdocs.wear

import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File

/**
 * Receives documents streamed from the phone app.
 * Channel path: /doc/<transferId>/<sizeBytes or -1>/<url-encoded file name>
 * Replies with a message on /doc-ack or /doc-nack carrying the transferId.
 */
class DocReceiverService : WearableListenerService() {

    // WearableListenerService callbacks run on a background thread, so blocking here is fine.
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith(PREFIX)) return
        val parts = channel.path.removePrefix(PREFIX).split('/', limit = 3)
        if (parts.size < 3) return
        val transferId = parts[0]
        val expected = parts[1].toLongOrNull() ?: -1L
        val name = DocStore.sanitize(Uri.decode(parts[2]))

        val client = Wearable.getChannelClient(this)
        val dir = DocStore.dir(this)
        val tmp = File(dir, "$name.part")
        var ok = false
        try {
            Tasks.await(client.getInputStream(channel)).use { input ->
                tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            }
            if (expected >= 0 && tmp.length() != expected) {
                error("Incomplete transfer: got ${tmp.length()} of $expected bytes")
            }
            val dest = File(dir, name)
            dest.delete()
            ok = tmp.renameTo(dest)
        } catch (e: Exception) {
            Log.e(TAG, "Receiving $name failed", e)
        } finally {
            tmp.delete()
            runCatching { Tasks.await(client.close(channel)) }
        }
        DocStore.refresh(this)
        runCatching {
            Tasks.await(
                Wearable.getMessageClient(this).sendMessage(
                    channel.nodeId, if (ok) "/doc-ack" else "/doc-nack", transferId.toByteArray()
                )
            )
        }
    }

    companion object {
        private const val PREFIX = "/doc/"
        private const val TAG = "DocReceiver"
    }
}
