package com.lawfirm.callagent.calling

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.lawfirm.callagent.Api
import com.lawfirm.callagent.PortalActivity
import com.lawfirm.callagent.R
import com.lawfirm.callagent.SecureStore
import org.linphone.core.Call
import java.util.Calendar

/**
 * The call screen, shown over the lock screen and dark like a phone's own
 * dialer: who is calling (initials, name, number) and what Ledger knows
 * about them — a client's cases, how far along, who handles them, the next
 * step; a colleague; a new number (CallerInfo). Answer / Decline and
 * "Remind me" while it rings; mute, keypad, loudspeaker, hang up and the
 * time while it goes on — and open the client, add them as a client or book
 * a consultation in Ledger while the call carries on. Closes itself when
 * the call ends.
 */
class CallActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var initials: TextView
    private lateinit var name: TextView
    private lateinit var number: TextView
    private lateinit var options: View
    private lateinit var remind: View
    private lateinit var keypad: View
    private lateinit var digits: TextView
    private lateinit var mute: ImageButton
    private lateinit var keypadToggle: ImageButton
    private lateinit var speaker: ImageButton
    private lateinit var end: ImageButton
    private lateinit var endLabel: TextView
    private lateinit var answerBox: View
    private lateinit var kind: TextView
    private lateinit var detail: TextView
    private lateinit var card: LinearLayout
    private lateinit var cardScroll: View
    private lateinit var actions: View
    private lateinit var openClient: View
    private lateinit var addClient: View
    private lateinit var book: View
    private var caller: Api.CallerCard? = null
    private var callerFor: String? = null
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
        // Dark bars over the dark screen, light icons on them.
        window.statusBarColor = Color.parseColor("#25324A")
        window.navigationBarColor = Color.parseColor("#0E131B")
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(R.layout.activity_call)
        status = findViewById(R.id.callStatus)
        initials = findViewById(R.id.callInitials)
        name = findViewById(R.id.callName)
        number = findViewById(R.id.callNumber)
        options = findViewById(R.id.callOptions)
        remind = findViewById(R.id.callRemind)
        keypad = findViewById(R.id.callKeypad)
        digits = findViewById(R.id.callDigits)
        mute = findViewById(R.id.callMute)
        keypadToggle = findViewById(R.id.callKeypadToggle)
        speaker = findViewById(R.id.callSpeaker)
        end = findViewById(R.id.callEnd)
        endLabel = findViewById(R.id.callEndLabel)
        answerBox = findViewById(R.id.callAnswerBox)
        kind = findViewById(R.id.callKind)
        detail = findViewById(R.id.callDetail)
        card = findViewById(R.id.callCard)
        cardScroll = findViewById(R.id.callCardScroll)
        actions = findViewById(R.id.callActions)
        openClient = findViewById(R.id.callOpenClient)
        addClient = findViewById(R.id.callAddClient)
        book = findViewById(R.id.callBook)
        openClient.setOnClickListener { caller?.clientId?.let { openInLedger("/clients/$it") } }
        addClient.setOnClickListener { info?.let { openInLedger("/clients?new=1&phone=${Uri.encode(it.number)}") } }
        book.setOnClickListener { bookConsultation() }

        findViewById<View>(R.id.callAnswer).setOnClickListener { answerCall() }
        end.setOnClickListener {
            if (info?.let { ringing(it) } == true) LedgerPhone.decline() else LedgerPhone.hangUp()
        }
        mute.setOnClickListener { LedgerPhone.toggleMute() }
        speaker.setOnClickListener { LedgerPhone.toggleSpeaker() }
        keypadToggle.setOnClickListener { showKeypad(keypad.visibility != View.VISIBLE) }
        remind.setOnClickListener { chooseReminder() }
        buildKeys(findViewById(R.id.callKeys))
        proximity = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "ledger:call")
        handleAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    // "Javob" pressed on the notification.
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
        loadCaller(info.number)
        // Ledger's name for them (a client, a colleague) over what the line sent.
        val known = caller?.name?.takeIf { caller?.kind == "client" || caller?.kind == "colleague" }
        val shownName = known ?: info.name ?: info.number.ifBlank { getString(R.string.call_unknown) }
        name.text = shownName
        number.text = if (known != null || info.name != null) info.number else ""
        initials.text = initialsOf(known ?: info.name) ?: "#"
        val isRinging = ringing(info)
        renderActions(isRinging)
        answerBox.visibility = if (isRinging) View.VISIBLE else View.GONE
        remind.visibility = if (isRinging && info.number.isNotBlank()) View.VISIBLE else View.GONE
        options.visibility = if (isRinging) View.INVISIBLE else View.VISIBLE
        if (isRinging) showKeypad(false)
        endLabel.text = getString(if (isRinging) R.string.call_decline else R.string.call_end)
        mute.isActivated = info.muted
        speaker.isActivated = info.speaker
        status.text = statusText(info)
        // Screen off against the ear, unless on the loudspeaker.
        if (!isRinging && info.connectedAtMs != null && !info.speaker && keypad.visibility != View.VISIBLE) holdProximity() else releaseProximity()
    }

    // "Aliyev Sardor" -> "AS"; a number has none.
    private fun initialsOf(name: String?): String? {
        val words = name?.split(' ', '\t')?.filter { it.isNotBlank() && it.first().isLetter() } ?: return null
        if (words.isEmpty()) return null
        return words.take(2).joinToString("") { it.first().uppercase() }
    }

    private fun statusText(info: LedgerPhone.CallInfo): String = when {
        ringing(info) -> getString(R.string.call_incoming)
        info.connectedAtMs != null -> clock((System.currentTimeMillis() - info.connectedAtMs) / 1000)
        !info.incoming -> getString(R.string.call_outgoing)
        else -> getString(R.string.call_connecting)
    }

    private val tick = object : Runnable {
        override fun run() {
            info?.let { status.text = statusText(it) }
            ticker.postDelayed(this, 1000)
        }
    }

    private fun clock(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)

    // The keypad, for menus that say "press 1" — each key a tone to the other side.
    private fun buildKeys(grid: GridLayout) {
        val size = (72 * resources.displayMetrics.density).toInt()
        val gap = (10 * resources.displayMetrics.density).toInt()
        for (key in "123456789*0#") {
            val button = TextView(this).apply {
                text = key.toString()
                textSize = 28f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(this@CallActivity, R.drawable.bg_call_key)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    LedgerPhone.sendDtmf(key)
                    digits.append(key.toString())
                }
            }
            grid.addView(button, GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(gap, gap, gap, gap)
            })
        }
    }

    private fun showKeypad(show: Boolean) {
        keypad.visibility = if (show) View.VISIBLE else View.GONE
        cardScroll.visibility = if (show) View.GONE else View.VISIBLE
        keypadToggle.isActivated = show
        if (show) releaseProximity()
    }

    // ------------------------------------------------ who's on the line

    private fun loadCaller(number: String) {
        if (number.isBlank() || callerFor == number) return
        callerFor = number
        CallerInfo.get(this, number) { found ->
            if (isDestroyed || callerFor != number) return@get
            caller = found
            showCaller(found)
            info?.let { render(it) }
        }
    }

    private fun showCaller(c: Api.CallerCard) {
        val (label, color, dark) = when (c.kind) {
            "client" -> Triple(R.string.call_kind_client, "#34C759", false)
            "colleague" -> Triple(R.string.call_kind_colleague, "#0A84FF", false)
            "restricted" -> Triple(R.string.call_kind_restricted, "#FF9F0A", false)
            else -> Triple(R.string.call_kind_unknown, "#FFFFFF", true)
        }
        kind.setText(label)
        kind.backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))
        kind.setTextColor(if (dark) Color.parseColor("#1D1D1F") else Color.WHITE)
        kind.visibility = View.VISIBLE

        val line = when (c.kind) {
            "client" -> c.via?.let { getString(R.string.call_via, it) }
            else -> c.detail
        }
        detail.text = line ?: ""
        detail.visibility = if (line != null) View.VISIBLE else View.GONE

        card.removeAllViews()
        c.cases.forEachIndexed { i, k ->
            if (i > 0) card.addView(divider())
            card.addView(text(k.title ?: k.status, 16f, "#FFFFFF", bold = true))
            listOfNotNull(k.status.takeIf { k.title != null }, k.stage).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                card.addView(text(it, 14f, "#CCFFFFFF", top = 2))
            }
            k.people?.let { card.addView(text(it, 13f, "#99FFFFFF", top = 4)) }
        }
        if (c.nextStep != null) {
            if (c.cases.isNotEmpty()) card.addView(divider())
            card.addView(text(getString(R.string.call_next_step).uppercase(), 11f, "#99FFFFFF", bold = true))
            val whenText = CallerInfo.whenText(c.nextStepAt)
            card.addView(text(listOfNotNull(c.nextStep, whenText).joinToString(" · "), 15f, "#FFFFFF", top = 2))
        }
        card.visibility = if (card.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun renderActions(isRinging: Boolean) {
        val c = caller
        val ended = info == null
        openClient.visibility = if (c?.clientId != null) View.VISIBLE else View.GONE
        addClient.visibility = if (c != null && c.kind == "unknown" && c.canAddClient) View.VISIBLE else View.GONE
        book.visibility = if (c != null && c.canBook && (c.kind == "client" || c.kind == "unknown")) View.VISIBLE else View.GONE
        val any = openClient.visibility == View.VISIBLE || addClient.visibility == View.VISIBLE || book.visibility == View.VISIBLE
        // While it rings: just answer or not.
        actions.visibility = if (any && !isRinging && !ended) View.VISIBLE else View.GONE
    }

    private fun text(value: String, size: Float, color: String, bold: Boolean = false, top: Int = 0) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (top > 0) setPadding(0, (top * resources.displayMetrics.density).toInt(), 0, 0)
        }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#26FFFFFF"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            val gap = (12 * resources.displayMetrics.density).toInt()
            setMargins(0, gap, 0, gap)
        }
    }

    // A page of Ledger over the call (the call carries on; a green bar on
    // top of Ledger brings it back).
    private fun openInLedger(path: String) {
        startActivity(PortalActivity.openIntent(this, path))
    }

    private fun bookConsultation() {
        val current = info ?: return
        val c = caller
        val query = buildList {
            c?.clientId?.let { add("clientId=$it") }
            c?.name?.takeIf { c.kind == "client" }?.let { add("name=${Uri.encode(it)}") }
            if (current.number.isNotBlank()) add("phone=${Uri.encode(current.number)}")
        }.joinToString("&")
        openInLedger("/calendar?$query")
    }

    // "Remind me": decline now, and Ledger reminds this person to call back.
    private fun chooseReminder() {
        val current = info ?: return
        val choices = arrayOf(
            getString(R.string.remind_15m),
            getString(R.string.remind_1h),
            getString(R.string.remind_3h),
            getString(R.string.remind_tomorrow),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.remind_title)
            .setItems(choices) { _, which ->
                val minutes = when (which) {
                    0 -> 15
                    1 -> 60
                    2 -> 180
                    else -> minutesUntilTomorrowMorning()
                }
                LedgerPhone.decline()
                remindLater(applicationContext, current.number, current.name, minutes)
            }
            .setNegativeButton(R.string.remind_cancel, null)
            .show()
    }

    private fun minutesUntilTomorrowMorning(): Int {
        val now = Calendar.getInstance()
        val at = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        return ((at.timeInMillis - now.timeInMillis) / 60000).toInt().coerceAtLeast(1)
    }

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

        // Sent after the call screen has closed, so not tied to it.
        private fun remindLater(context: Context, phone: String, name: String?, minutes: Int) {
            val token = SecureStore.token(context) ?: return
            val main = Handler(Looper.getMainLooper())
            Thread {
                val ok = try {
                    Api.remindMe(token, phone, name, minutes)
                    true
                } catch (e: Exception) {
                    false
                }
                main.post { Toast.makeText(context, if (ok) R.string.remind_saved else R.string.remind_failed, Toast.LENGTH_LONG).show() }
            }.start()
        }
    }
}
