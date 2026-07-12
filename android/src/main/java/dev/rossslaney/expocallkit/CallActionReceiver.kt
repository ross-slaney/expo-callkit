package dev.rossslaney.expocallkit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.UUID

/**
 * Receives decline actions from call notifications. (Answer actions launch
 * the app's main activity directly — see [CallNotifications.showIncoming].)
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(CKIntents.EXTRA_CALL_ID)?.let {
            try {
                UUID.fromString(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        } ?: return

        when (intent.action) {
            CKIntents.ACTION_DECLINE -> {
                try {
                    CallEngine.endCall(id)
                } catch (e: Exception) {
                    Log.w("ExpoCallKit", "Decline for missing call $id: ${e.message}")
                }
            }

            CKIntents.ACTION_ANSWER -> {
                // Defensive: answers normally arrive as activity intents.
                CallEngine.handleAnswer(id)
            }
        }
    }
}
