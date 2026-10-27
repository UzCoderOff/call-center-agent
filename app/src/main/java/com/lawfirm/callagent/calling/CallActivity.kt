package com.lawfirm.callagent.calling

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
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
import androidx.activity.OnBackPressedCallback
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
 * The call screen, shown over the lock screen: who is calling and what
 * Ledger knows about them — a client's cases, how far along, who handles
 * them, the next step; a colleague; a new number (CallerInfo).
 *
 * While it rings: the person big, with rings rippling out, Answer / Decline
 * and "Remind me". Once connected: a compact header, so what Ledger knows
 * gets the room — with who works on this client and "Ulash" next to them —
 * and the controls: mute, keypad, loudspeaker; pass the call on
 * (TransferPanel), open or add the client, book a consultation (in Ledger,
 * the call carries on); hang up. Closes itself when the call ends.
 */
class CallActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var rec: View
    private lateinit var banner: View
    private lateinit var bannerText: TextView
    private lateinit var hero: View
    private lateinit var ring1: View
    private lateinit var ring2: View
    private lateinit var initials: TextView
    private lateinit var name: TextView
    private lateinit var number: TextView
    private lateinit var kind: TextView
    private lateinit var compact: View
    private lateinit var initialsSmall: TextView
    private lateinit var nameSmall: TextView
    private lateinit var numberSmall: TextView
    private lateinit var kindSmall: TextView
    private lateinit var detail: TextView
    private lateinit var card: LinearLayout
    private lateinit var workers: LinearLayout
    private lateinit var cardScroll: View
    private lateinit var keypad: View
    private lateinit var digits: TextView
    private lateinit var remind: View
    private lateinit var options: View
    private lateinit var mute: ImageButton
    private lateinit var keypadToggle: ImageButton
    private lateinit var speaker: ImageButton
    private lateinit var transferCell: View
    private lateinit var clientCell: View
    private lateinit var clientButton: ImageButton
    private lateinit var clientLabel: TextView
    private lateinit var bookCell: View
    private lateinit var endLabel: TextView
    private lateinit var answerBox: View
    private lateinit var answer: View
    private lateinit var panel: TransferPanel

    private var caller: Api.CallerCard? = null
    private var callerFor: String? = null
    private val ticker = Handler(Looper.getMainLooper())
    private var proximity: PowerManager.WakeLock? = null
    private var info: LedgerPhone.CallInfo? = null
    private var mode: Mode? = null
    private val animations = mutableListOf<ValueAnimator>()

    /** What the screen is showing; the rings and pulses change with it. */
    private enum class Mode { RINGING, DIALING, CONNECTED }

    // Answering needs the microphone; asked once, then the call is answered.
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { LedgerPhone.accept() }

    private val listener = LedgerPhone.Listener { info ->
        if (info == null) {
            // A talk with a new number on a call-center phone: Ledger opens to
            // ask what came of it, and waits for the answer (the portal's
            // AfterCallGate).
            val last = this.info
            if (last?.connectedAtMs != null && outside(last.number) && caller?.askAfter == true) openInLedger("/")
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
        // The bars take the screen's own colours, with light icons.
        window.statusBarColor = Color.parseColor("#1B2744")
        window.navigationBarColor = Color.parseColor("#070A12")
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(R.layout.activity_call)
        status = findViewById(R.id.callStatus)
        statusDot = findViewById(R.id.callStatusDot)
        rec = findViewById(R.id.callRec)
        banner = findViewById(R.id.callBanner)
        bannerText = findViewById(R.id.callBannerText)
        hero = findViewById(R.id.callHero)
        ring1 = findViewById(R.id.callRing1)
        ring2 = findViewById(R.id.callRing2)
        initials = findViewById(R.id.callInitials)
        name = findViewById(R.id.callName)
        number = findViewById(R.id.callNumber)
        kind = findViewById(R.id.callKind)
        compact = findViewById(R.id.callCompact)
        initialsSmall = findViewById(R.id.callInitialsSmall)
        nameSmall = findViewById(R.id.callNameSmall)
        numberSmall = findViewById(R.id.callNumberSmall)
        kindSmall = findViewById(R.id.callKindSmall)
        detail = findViewById(R.id.callDetail)
        card = findViewById(R.id.callCard)
        workers = findViewById(R.id.callWorkers)
        cardScroll = findViewById(R.id.callCardScroll)
        keypad = findViewById(R.id.callKeypad)
        digits = findViewById(R.id.callDigits)
        remind = findViewById(R.id.callRemind)
        options = findViewById(R.id.callOptions)
        mute = findViewById(R.id.callMute)
        keypadToggle = findViewById(R.id.callKeypadToggle)
        speaker = findViewById(R.id.callSpeaker)
        transferCell = findViewById(R.id.callTransferCell)
        clientCell = findViewById(R.id.callClientCell)
        clientButton = findViewById(R.id.callClient)
        clientLabel = findViewById(R.id.callClientLabel)
        bookCell = findViewById(R.id.callBookCell)
        endLabel = findViewById(R.id.callEndLabel)
        answerBox = findViewById(R.id.callAnswerBox)
        answer = findViewById(R.id.callAnswer)
        panel = TransferPanel(this, findViewById(R.id.callRoot)) { info?.number.orEmpty() }

        answer.setOnClickListener { answerCall() }
        findViewById<View>(R.id.callEnd).setOnClickListener {
            if (info?.let { ringing(it) } == true) LedgerPhone.decline() else LedgerPhone.hangUp()
        }
        mute.setOnClickListener { LedgerPhone.toggleMute() }
        speaker.setOnClickListener { LedgerPhone.toggleSpeaker() }
        keypadToggle.setOnClickListener { showKeypad(keypad.visibility != View.VISIBLE) }
        findViewById<View>(R.id.callKeypadHide).setOnClickListener { showKeypad(false) }
        findViewById<View>(R.id.callTransfer).setOnClickListener { panel.open() }
        clientButton.setOnClickListener { openOrAddClient() }
        findViewById<View>(R.id.callBook).setOnClickListener { bookConsultation() }
        remind.setOnClickListener { chooseReminder() }
        buildKeys(findViewById(R.id.callKeys))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    panel.isOpen -> panel.close()
                    keypad.visibility == View.VISIBLE -> showKeypad(false)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
        proximity = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "ledger:call")
        // Answer/decline from the notification: once, not again when Android
        // rebuilds the screen.
        if (savedInstanceState == null) handleAction(intent)
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
        stopAnimations()
        mode = null
        // Left while it still rings (not the screen going off — that would
        // light it up again): the banner, to answer from anywhere.
        val screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (screenOn) LedgerPhone.currentInfo()?.takeIf { ringing(it) }?.let { CallNotifications.showIncoming(this, it) }
        super.onStop()
    }

    private fun ringing(info: LedgerPhone.CallInfo) =
        info.incoming && (info.state == Call.State.IncomingReceived || info.state == Call.State.IncomingEarlyMedia)

    // A colleague's phone ID or the line's own numbers (600…): not a client.
    private fun outside(number: String) = number.count { it.isDigit() } > 4

    private fun render(info: LedgerPhone.CallInfo) {
        loadCaller(info.number)
        // Ledger's name for them (a client, a colleague) over what the line sent.
        val known = caller?.name?.takeIf { caller?.kind in setOf("client", "colleague", "service") }
        val shownName = known ?: info.name ?: CallUi.formatPhone(info.number).ifBlank { getString(R.string.call_unknown) }
        val shownNumber = if (known != null || info.name != null) CallUi.formatPhone(info.number) else ""
        val seed = (known ?: info.name)?.takeIf { caller?.kind != "unknown" || info.name != null }
        name.text = shownName
        nameSmall.text = shownName
        number.text = shownNumber
        number.visibility = if (shownNumber.isBlank()) View.GONE else View.VISIBLE
        numberSmall.text = shownNumber
        numberSmall.visibility = if (shownNumber.isBlank()) View.GONE else View.VISIBLE
        val letters = CallUi.initialsOf(known ?: info.name) ?: "#"
        initials.text = letters
        initialsSmall.text = letters
        initials.background = CallUi.avatar(seed)
        initialsSmall.background = CallUi.avatar(seed)

        val isRinging = ringing(info)
        val connected = info.connectedAtMs != null
        setMode(if (isRinging) Mode.RINGING else if (connected) Mode.CONNECTED else Mode.DIALING)

        // A call passed on to this phone, one that came back, a client sent
        // straight to their coordinator, or one their coordinator missed.
        val note = CallUi.noteText(this, info.note)
        bannerText.text = note ?: ""
        banner.visibility = if (note != null) View.VISIBLE else View.GONE

        val keypadOpen = keypad.visibility == View.VISIBLE
        hero.visibility = if (!connected && !keypadOpen) View.VISIBLE else View.GONE
        compact.visibility = if (connected && !keypadOpen) View.VISIBLE else View.GONE
        answerBox.visibility = if (isRinging) View.VISIBLE else View.GONE
        remind.visibility = if (isRinging && info.number.isNotBlank()) View.VISIBLE else View.GONE
        options.visibility = if (isRinging || keypadOpen) View.GONE else View.VISIBLE
        endLabel.text = getString(if (isRinging) R.string.call_decline else R.string.call_end)
        mute.isActivated = info.muted
        speaker.isActivated = info.speaker
        rec.visibility = if (connected && outside(info.number) && caller?.kind != "colleague") View.VISIBLE else View.GONE
        renderControls(info)
        renderWorkers(info)
        status.text = statusText(info)
        // Screen off against the ear, unless on the loudspeaker.
        if (!isRinging && connected && !info.speaker && !keypadOpen && !panel.isOpen) holdProximity() else releaseProximity()
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

    private fun clock(seconds: Long) = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%02d:%02d".format(seconds / 60, seconds % 60)

    // ------------------------------------------------------------ motion

    private fun setMode(next: Mode) {
        if (next == mode) return
        mode = next
        stopAnimations()
        val color = when (next) {
            Mode.RINGING -> CallUi.AMBER
            Mode.DIALING -> "#0A84FF"
            Mode.CONNECTED -> CallUi.GREEN
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))
        if (next == Mode.CONNECTED) return
        // Rings rippling out from the person; the status dot breathing.
        val period = if (next == Mode.RINGING) 1800L else 2400L
        listOf(ring1, ring2).forEachIndexed { i, ring ->
            animations += ObjectAnimator.ofPropertyValuesHolder(
                ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.6f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.6f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 0f),
            ).apply {
                duration = period
                startDelay = i * period / 2
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
        animations += ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.25f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        if (next == Mode.RINGING) {
            animations += ObjectAnimator.ofPropertyValuesHolder(
                answer,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f),
            ).apply {
                duration = 600
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun stopAnimations() {
        animations.forEach { it.cancel() }
        animations.clear()
        for (v in listOf(ring1, ring2)) {
            v.alpha = 0f
            v.scaleX = 1f
            v.scaleY = 1f
        }
        statusDot.alpha = 1f
        answer.scaleX = 1f
        answer.scaleY = 1f
    }

    // ------------------------------------------------------------ keypad

    // The keypad, for menus that say "press 1" — each key a tone to the other side.
    private fun buildKeys(grid: GridLayout) {
        val size = CallUi.dp(this, 68)
        val gap = CallUi.dp(this, 7)
        val letters = mapOf('2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO", '7' to "PQRS", '8' to "TUV", '9' to "WXYZ", '0' to "+")
        for (key in "123456789*0#") {
            val button = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(this@CallActivity, R.drawable.bg_call_key)
                isClickable = true
                isFocusable = true
                contentDescription = key.toString()
                addView(TextView(this@CallActivity).apply {
                    text = key.toString()
                    textSize = 28f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                })
                letters[key]?.let { l ->
                    addView(TextView(this@CallActivity).apply {
                        text = l
                        textSize = 9f
                        letterSpacing = 0.15f
                        setTextColor(Color.parseColor("#80FFFFFF"))
                        gravity = Gravity.CENTER
                    })
                }
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
        // The keypad takes the person's, the card's and the buttons' room, so
        // all four rows fit on any phone.
        cardScroll.visibility = if (show) View.GONE else View.VISIBLE
        keypadToggle.isActivated = show
        if (show) releaseProximity()
        info?.let { render(it) }
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
        // The line's own numbers (600, 601…): just their name, no badge.
        if (c.kind == "service") {
            kind.visibility = View.GONE
            kindSmall.visibility = View.GONE
            detail.visibility = View.GONE
            card.visibility = View.GONE
            return
        }
        val (label, color) = when (c.kind) {
            "client" -> R.string.call_kind_client to CallUi.GREEN
            "colleague" -> R.string.call_kind_colleague to "#64D2FF"
            "restricted" -> R.string.call_kind_restricted to "#FFB340"
            else -> R.string.call_kind_unknown to "#FFFFFF"
        }
        for (pill in listOf(kind, kindSmall)) {
            pill.setText(label)
            pill.setTextColor(Color.parseColor(color))
            pill.backgroundTintList = ColorStateList.valueOf(Color.parseColor(color)).withAlpha(0x2E)
            pill.visibility = View.VISIBLE
        }

        val line = when (c.kind) {
            "client" -> c.via?.let { getString(R.string.call_via, it) }
            else -> c.detail
        }
        detail.text = line ?: ""
        detail.visibility = if (line != null) View.VISIBLE else View.GONE

        card.removeAllViews()
        c.cases.forEachIndexed { i, k ->
            if (i > 0) card.addView(divider())
            card.addView(CallUi.text(this, k.title ?: k.status, 16f, "#FFFFFF", bold = true))
            listOfNotNull(k.status.takeIf { k.title != null }, k.stage).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                card.addView(CallUi.text(this, it, 14f, "#B3FFFFFF", top = 3))
            }
            k.people?.let { card.addView(CallUi.text(this, it, 13f, "#80FFFFFF", top = 6)) }
        }
        if (c.nextStep != null) {
            if (c.cases.isNotEmpty()) card.addView(divider())
            card.addView(CallUi.text(this, getString(R.string.call_next_step).uppercase(), 11f, "#80FFFFFF", bold = true).apply { letterSpacing = 0.08f })
            val whenText = CallerInfo.whenText(c.nextStepAt)
            card.addView(CallUi.text(this, listOfNotNull(c.nextStep, whenText).joinToString(" · "), 15f, "#FFFFFF", top = 4))
        }
        card.visibility = if (card.childCount > 0) View.VISIBLE else View.GONE
    }

    // Passing on works for a client's call that's on: the company line.
    private fun canTransfer(info: LedgerPhone.CallInfo) =
        info.connectedAtMs != null && info.incoming && outside(info.number) && caller?.kind != "colleague"

    private fun renderControls(info: LedgerPhone.CallInfo) {
        val c = caller
        val transfer = canTransfer(info)
        setEnabled(transferCell, transfer)
        if (transfer) panel.prefetch { this.info?.let { renderWorkers(it) } }
        val open = c?.clientId != null
        val add = c != null && c.kind == "unknown" && c.canAddClient
        clientButton.setImageResource(if (add && !open) R.drawable.ic_person_add else R.drawable.ic_person)
        clientLabel.setText(if (add && !open) R.string.call_add_client else R.string.call_open_client)
        setEnabled(clientCell, open || add)
        setEnabled(bookCell, c != null && c.canBook && (c.kind == "client" || c.kind == "unknown"))
    }

    private fun setEnabled(cell: View, on: Boolean) {
        cell.alpha = if (on) 1f else 0.32f
        (cell as LinearLayout).getChildAt(0).isEnabled = on
    }

    // "Bu mijoz bilan ishlaydi": the client's coordinator (lawyers…), with
    // "Ulash" right there — the quickest way to pass the call on.
    private fun renderWorkers(info: LedgerPhone.CallInfo) {
        val people = panel.forCaller.orEmpty()
        workers.removeAllViews()
        if (!canTransfer(info) || people.isEmpty() || keypad.visibility == View.VISIBLE) {
            workers.visibility = View.GONE
            // Someone else's client: the line "Koordinator: X" says the same.
            return
        }
        workers.addView(CallUi.text(this, getString(R.string.call_works_on).uppercase(), 11f, "#80FFFFFF", bold = true).apply {
            letterSpacing = 0.08f
            setPadding(0, 0, 0, CallUi.dp(this@CallActivity, 4))
        })
        people.take(3).forEach { workers.addView(CallUi.personRow(this, it, compact = true) { p -> panel.confirm(p) }) }
        workers.visibility = View.VISIBLE
        if (caller?.kind == "restricted") detail.visibility = View.GONE
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#1AFFFFFF"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            val gap = CallUi.dp(this@CallActivity, 12)
            setMargins(0, gap, 0, gap)
        }
    }

    // A page of Ledger over the call (the call carries on; a green bar on
    // top of Ledger brings it back).
    private fun openInLedger(path: String) {
        startActivity(PortalActivity.openIntent(this, path))
    }

    private fun openOrAddClient() {
        val c = caller ?: return
        val current = info ?: return
        if (c.clientId != null) openInLedger("/clients/${c.clientId}")
        else if (c.kind == "unknown" && c.canAddClient) openInLedger("/clients?new=1&phone=${Uri.encode(CallUi.e164(current.number))}")
    }

    private fun bookConsultation() {
        val current = info ?: return
        val c = caller
        val query = buildList {
            c?.clientId?.let { add("clientId=$it") }
            c?.name?.takeIf { c.kind == "client" }?.let { add("name=${Uri.encode(it)}") }
            if (current.number.isNotBlank()) add("phone=${Uri.encode(CallUi.e164(current.number))}")
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
        AlertDialog.Builder(this, R.style.CallDialog)
            .setTitle(R.string.remind_title)
            .setItems(choices) { _, which ->
                val minutes = when (which) {
                    0 -> 15
                    1 -> 60
                    2 -> 180
                    else -> minutesUntilTomorrowMorning()
                }
                LedgerPhone.decline()
                remindLater(applicationContext, CallUi.e164(current.number), current.name, minutes)
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
