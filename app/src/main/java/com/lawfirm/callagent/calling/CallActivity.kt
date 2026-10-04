package com.lawfirm.callagent.calling

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lawfirm.callagent.R
import org.linphone.core.Call

/**
 * The call screen, shown over the lock screen: who is calling and Answer /
 * Decline while it rings; mute, loudspeaker, hang up and the time while it
 * goes on. Closes itself when the call ends.
 */
class CallActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var name: TextView
    private lateinit var number: TextView
    private lateinit var options: View
    private lateinit var mute: Button
    private lateinit var speaker: Button
    private lateinit var end: Button
    private lateinit var answer: Button
    private val ticker = Handler(Looper.getMainLooper())
    private var proximity: PowerManager.WakeLock? = null
    private var info: LedgerPhone.CallInfo? = null

    // Answering needs the microphone; asked once, then the call is answered.
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { LedgerPhone.accept() }

    private val listener = LedgerPhone.Listener { info ->
        if (info == null) {
            finishAndRemoveTask()
        } else {
            this.info = info
            render(info)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_call)
        status = findViewById(R.id.callStatus)
        name = findViewById(R.id.callName)
        number = findViewById(R.id.callNumber)
        options = findViewById(R.id.callOptions)
        mute = findViewById(R.id.callMute)
        speaker = findViewById(R.id.callSpeaker)
        end = findViewById(R.id.callEnd)
        answer = findViewById(R.id.callAnswer)

        answer.setOnClickListener { answerCall() }
        end.setOnClickListener {
            if (info?.let { ringing(it) } == true) LedgerPhone.decline() else LedgerPhone.hangUp()
        }
        mute.setOnClickListener { LedgerPhone.toggleMute() }
        speaker.setOnClickListener { LedgerPhone.toggleSpeaker() }
        proximity = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "ledger:call")
        handleAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    // "Javob berish" pressed on the notification.
    private fun handleAction(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER) answerCall()
    }

    private fun answerCall() {
        CallNotifications.cancelIncoming(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            LedgerPhone.accept()
        } else {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStart() {
        super.onStart()
        LedgerPhone.addListener(listener)
        ticker.post(tick)
        // On screen while it rings: the ringing banner isn't needed on top.
        if (LedgerPhone.currentInfo()?.let { ringing(it) } == true) CallNotifications.cancelIncoming(this)
    }

    override fun onStop() {
        LedgerPhone.removeListener(listener)
        ticker.removeCallbacks(tick)
        releaseProximity()
        // Left while it still rings (not the screen going off — that would
        // light it up again): the banner, to answer from anywhere.
        val screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (screenOn) LedgerPhone.currentInfo()?.takeIf { ringing(it) }?.let { CallNotifications.showIncoming(this, it) }
        super.onStop()
    }

    private fun ringing(info: LedgerPhone.CallInfo) =
        info.incoming && (info.state == Call.State.IncomingReceived || info.state == Call.State.IncomingEarlyMedia)

    private fun render(info: LedgerPhone.CallInfo) {
        name.text = info.name ?: info.number.ifBlank { getString(R.string.call_unknown) }
        number.text = if (info.name != null) info.number else ""
        val isRinging = ringing(info)
        answer.visibility = if (isRinging) View.VISIBLE else View.GONE
        options.visibility = if (isRinging) View.INVISIBLE else View.VISIBLE
        end.text = getString(if (isRinging) R.string.call_decline else R.string.call_end)
        mute.isActivated = info.muted
        speaker.isActivated = info.speaker
        status.text = statusText(info)
        // Screen off against the ear, unless on the loudspeaker.
        if (!isRinging && info.connectedAtMs != null && !info.speaker) holdProximity() else releaseProximity()
    }

    private fun statusText(info: LedgerPhone.CallInfo): String = when {
        ringing(info) -> getString(R.string.call_incoming)
        info.connectedAtMs != null -> clock((System.currentTimeMillis() - info.connectedAtMs) / 1000)
        else -> getString(R.string.call_connecting)
    }

    private val tick = object : Runnable {
        override fun run() {
            info?.let { status.text = statusText(it) }
            ticker.postDelayed(this, 1000)
        }
    }

    private fun clock(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun holdProximity() {
        val lock = proximity ?: return
        if (!lock.isHeld) lock.acquire(2 * 60 * 60 * 1000L)
    }

    private fun releaseProximity() {
        val lock = proximity ?: return
        if (lock.isHeld) lock.release()
    }

    companion object {
        const val ACTION_ANSWER = "com.lawfirm.callagent.call.ANSWER"

        /** The call screen, from anywhere (a call going out, or one coming in while Ledger is open). */
        fun open(context: Context) {
            context.startActivity(Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }
}
