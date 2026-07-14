package dev.rossslaney.expocallkit

import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.Instant
import java.time.format.DateTimeFormatter
import org.json.JSONObject

/**
 * Bridges native call events to JS with per-event replay buffers, mirroring
 * the iOS `EventHub`.
 *
 * Two escape hatches exist for events fired while no JS listener is mounted:
 * - Replayable events (see [replayLimits]) keep their most recent occurrence
 *   and are flushed with `meta.flushed = true` once JS subscribes.
 * - Terminal events ([CKEvents.CALL_ENDED]) additionally fire a
 *   package-internal [CKIntents.ACTION_CALL_EVENT] broadcast whenever they
 *   could not be delivered live, so a JS-less process (e.g. the user declined
 *   from a notification while the app was dead) can still notify a backend
 *   via a manifest receiver. Broadcast handlers should dedupe against the
 *   flushed JS replay using the embedded call/session ids.
 */
object EventHub {
    private const val TAG = "ExpoCallKit"

    private data class Buffered(val body: Map<String, Any?>, val firedAt: Instant)

    private val lock = Any()
    private val observed = mutableSetOf<String>()
    private val buffers = mutableMapOf<String, MutableList<Buffered>>()

    private val replayLimits = EventReplayPolicy.limits

    private val broadcastableEvents = setOf(CKEvents.CALL_ENDED)

    @Volatile
    private var sender: ((String, Map<String, Any?>) -> Unit)? = null

    @Volatile
    private var broadcastContext: Context? = null

    /** Connects (or disconnects, with null) the live Expo module sender. */
    fun attachSender(value: ((String, Map<String, Any?>) -> Unit)?) {
        sender = value
    }

    /** Enables the out-of-band broadcast path for terminal events. */
    fun enableBroadcasts(context: Context) {
        broadcastContext = context.applicationContext
    }

    /** Emits an event to JS, buffering and/or broadcasting when it can't land. */
    fun emit(name: String, body: Map<String, Any?> = emptyMap()) {
        val firedAt = Instant.now()
        val liveSender = sender
        val deliveredLive = synchronized(lock) {
            if (observed.contains(name) && liveSender != null) {
                true
            } else {
                val limit = replayLimits[name] ?: 0
                if (limit > 0) {
                    val buffer = buffers.getOrPut(name) { mutableListOf() }
                    buffer += Buffered(body, firedAt)
                    while (buffer.size > limit) {
                        buffer.removeAt(0)
                    }
                }
                false
            }
        }

        if (deliveredLive) {
            liveSender?.invoke(name, decorate(body, flushed = false, firedAt = firedAt))
        } else if (name in broadcastableEvents) {
            broadcast(name, decorate(body, flushed = false, firedAt = firedAt))
        }
    }

    /** Marks an event observed and replays anything buffered for it. */
    fun startObserving(name: String) {
        val pending: List<Buffered>
        synchronized(lock) {
            observed.add(name)
            pending = buffers.remove(name) ?: emptyList()
        }
        val liveSender = sender ?: return
        pending.forEach { item ->
            liveSender(name, decorate(item.body, flushed = true, firedAt = item.firedAt))
        }
    }

    fun stopObserving(name: String) {
        synchronized(lock) { observed.remove(name) }
    }

    private fun decorate(
        body: Map<String, Any?>,
        flushed: Boolean,
        firedAt: Instant,
    ): Map<String, Any?> {
        val decorated = body.toMutableMap()
        decorated["meta"] = mapOf(
            "flushed" to flushed,
            "timestamp" to DateTimeFormatter.ISO_INSTANT.format(firedAt),
        )
        return decorated
    }

    private fun broadcast(name: String, body: Map<String, Any?>) {
        val context = broadcastContext ?: return
        try {
            val intent = Intent(CKIntents.ACTION_CALL_EVENT)
                .setPackage(context.packageName)
                .putExtra("eventName", name)
                .putExtra("payload", JSONObject(body).toString())
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Call-event broadcast failed for $name: ${e.message}")
        }
    }
}
