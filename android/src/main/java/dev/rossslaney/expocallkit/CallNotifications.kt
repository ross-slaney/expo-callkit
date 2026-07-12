package dev.rossslaney.expocallkit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import java.util.UUID

/**
 * CallStyle notifications for every call phase.
 *
 * Incoming calls use a high-importance channel whose sound is the ringtone
 * plus a full-screen intent to [IncomingCallActivity] for the lock screen.
 * Ongoing/outgoing calls use a silent channel with a chronometer. Android
 * freezes channel sound after creation, so the incoming channel id embeds
 * the ringtone config and stale channels are deleted on change.
 */
object CallNotifications {
    private const val TAG = "ExpoCallKit"
    private const val NOTIFICATION_ID = 41400
    private const val INCOMING_CHANNEL_PREFIX = "expo_callkit_incoming"
    private const val ONGOING_CHANNEL = "expo_callkit_ongoing"
    private const val PREFS = "expo_callkit_prefs"
    private const val PREF_INCOMING_CHANNEL = "incoming_channel_id"

    private var incomingChannelId = "${INCOMING_CHANNEL_PREFIX}_default"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val ringtoneConfig = readRingtoneConfig(context)
        incomingChannelId = "${INCOMING_CHANNEL_PREFIX}_${ringtoneConfig ?: "default"}"

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(PREF_INCOMING_CHANNEL, null)
        if (previous != null && previous != incomingChannelId) {
            manager.deleteNotificationChannel(previous)
        }

        val incoming = NotificationChannel(
            incomingChannelId,
            "Incoming calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Ringing for incoming calls"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(
                resolveRingtoneUri(context, ringtoneConfig),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800)
        }
        manager.createNotificationChannel(incoming)
        prefs.edit().putString(PREF_INCOMING_CHANNEL, incomingChannelId).apply()

        val ongoing = NotificationChannel(
            ONGOING_CHANNEL,
            "Ongoing calls",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Active call status"
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(ongoing)
    }

    fun showIncoming(context: Context, callId: UUID, callerName: String?, hasVideo: Boolean) {
        val name = callerName ?: "Unknown"

        // Android 12+ forbids starting activities from background receivers,
        // so the answer action launches the app's main activity directly; the
        // module consumes the intent action (warm via OnNewIntent, cold via
        // the launch intent).
        val answerIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                action = CKIntents.ACTION_ANSWER
                putExtra(CKIntents.EXTRA_CALL_ID, callId.toString())
            } ?: Intent()
        val answerPI = PendingIntent.getActivity(
            context,
            callId.hashCode(),
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val declinePI = actionBroadcast(context, callId, CKIntents.ACTION_DECLINE, 1)

        val fullScreenIntent = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(CKIntents.EXTRA_CALL_ID, callId.toString())
        }
        val fullScreenPI = PendingIntent.getActivity(
            context,
            callId.hashCode() + 2,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = base(context, incomingChannelId, name)
            .setContentText(if (hasVideo) "Incoming video call" else "Incoming call")
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person(name), declinePI, answerPI))
            .setFullScreenIntent(fullScreenPI, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()

        post(context, notification)
    }

    fun showOutgoing(context: Context, callId: UUID, calleeName: String?) {
        val name = calleeName ?: "Unknown"
        val hangupPI = actionBroadcast(context, callId, CKIntents.ACTION_DECLINE, 3)

        val notification = base(context, ONGOING_CHANNEL, name)
            .setContentText("Calling…")
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(name), hangupPI))
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .build()

        post(context, notification)
    }

    fun showOngoing(context: Context, callId: UUID, remoteName: String?, connectedAtMs: Long) {
        val name = remoteName ?: "Unknown"
        val hangupPI = actionBroadcast(context, callId, CKIntents.ACTION_DECLINE, 3)

        val notification = base(context, ONGOING_CHANNEL, name)
            .setContentText("Ongoing call")
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(name), hangupPI))
            .setOngoing(true)
            .setAutoCancel(false)
            .setUsesChronometer(true)
            .setWhen(connectedAtMs)
            .build()

        post(context, notification)
    }

    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    // region Internals

    private fun actionBroadcast(
        context: Context,
        callId: UUID,
        action: String,
        requestOffset: Int,
    ): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java).apply {
            this.action = action
            putExtra(CKIntents.EXTRA_CALL_ID, callId.toString())
        }
        return PendingIntent.getBroadcast(
            context,
            callId.hashCode() + requestOffset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun base(context: Context, channelId: String, title: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(appIcon(context))
            .setContentTitle(title)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    private fun person(name: String): Person =
        Person.Builder().setName(name).setImportant(true).build()

    private fun post(context: Context, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // POST_NOTIFICATIONS not granted, or other posting failure.
            Log.w(TAG, "Failed to post call notification: ${e.message}")
        }
    }

    private fun readRingtoneConfig(context: Context): String? = try {
        val info = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )
        info.metaData?.getString(CKConfigKeys.RINGTONE)
            ?.takeIf { it.isNotBlank() && it != "default" }
    } catch (_: Throwable) {
        null
    }

    private fun resolveRingtoneUri(context: Context, config: String?): Uri {
        if (config == null) {
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }
        val resId = context.resources.getIdentifier(config, "raw", context.packageName)
        if (resId == 0) {
            Log.w(TAG, "Ringtone raw resource '$config' not found; using system default")
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }
        return Uri.parse("android.resource://${context.packageName}/$resId")
    }

    private fun appIcon(context: Context): Int = try {
        context.packageManager.getApplicationInfo(context.packageName, 0)
            .icon.takeIf { it != 0 } ?: android.R.drawable.sym_def_app_icon
    } catch (_: PackageManager.NameNotFoundException) {
        android.R.drawable.sym_def_app_icon
    }

    // endregion
}
