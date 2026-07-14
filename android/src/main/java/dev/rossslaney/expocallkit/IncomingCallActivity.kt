package dev.rossslaney.expocallkit

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Minimal lock-screen ring UI launched by the incoming notification's
 * full-screen intent. Shows caller name with answer/decline buttons and
 * closes itself when the call leaves the ringing state.
 */
class IncomingCallActivity : Activity() {
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watchJob: Job? = null
    private var callId: UUID? = null
    private var isAnswering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        callId = intent.getStringExtra(CKIntents.EXTRA_CALL_ID)?.let {
            try {
                UUID.fromString(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val id = callId
        if (id == null) {
            finish()
            return
        }

        showOverLockScreen()
        val call = CallEngine.sessions.value[id]
        buildUi(call?.remoteParty?.displayName ?: "Unknown", call?.hasVideo == true)
        watchCall(id)
    }

    override fun onDestroy() {
        watchJob?.cancel()
        uiScope.cancel()
        super.onDestroy()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON,
        )
    }

    private fun buildUi(callerName: String, hasVideo: Boolean) {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B1220"))
            setPadding(dp(32), dp(64), dp(32), dp(64))
        }

        root.addView(
            TextView(this).apply {
                text = callerName
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            },
        )

        root.addView(
            TextView(this).apply {
                text = if (hasVideo) "Incoming video call" else "Incoming call"
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(48))
            },
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        buttons.addView(
            Button(this).apply {
                text = "Decline"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#DC2626"))
                setPadding(dp(24), dp(16), dp(24), dp(16))
                setOnClickListener { decline() }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            },
        )

        buttons.addView(
            Button(this).apply {
                text = "Answer"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#16A34A"))
                setPadding(dp(24), dp(16), dp(24), dp(16))
                setOnClickListener { answer() }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )

        root.addView(buttons)
        setContentView(root)
    }

    /** Closes this screen once the call is no longer ringing here. */
    private fun watchCall(id: UUID) {
        watchJob = uiScope.launch {
            CallEngine.sessions.collect { snapshot ->
                val call = snapshot[id]
                val gone = call == null || call.status == CallStatus.ENDED
                val leftRinging = call != null &&
                    call.status != CallStatus.RINGING &&
                    !isAnswering
                if (gone || leftRinging) {
                    finish()
                }
            }
        }
    }

    private fun answer() {
        val id = callId ?: return
        isAnswering = true
        CallEngine.handleAnswer(id)
        unlockAndOpenApp()
        finish()
    }

    private fun decline() {
        val id = callId ?: return
        try {
            CallEngine.endCall(id)
        } catch (_: Exception) {
            // Call already gone.
        }
        finish()
    }

    private fun unlockAndOpenApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    launch?.let { startActivity(it) }
                }

                override fun onDismissCancelled() {
                    launch?.let { startActivity(it) }
                }

                override fun onDismissError() {
                    launch?.let { startActivity(it) }
                }
            },
        )
    }
}
