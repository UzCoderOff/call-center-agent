package com.lawfirm.callagent.calling

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.lawfirm.callagent.BuildConfig
import com.lawfirm.callagent.DiagnosticLog
import com.lawfirm.callagent.R
import com.lawfirm.callagent.SecureStore
import com.lawfirm.callagent.Session
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.MediaEncryption
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType

/**
 * Calling through Ledger's phone line (call-center-backend/pbx/README.md):
 * the Linphone engine, signed in to the firm's own phone system over TLS with
 * this phone's phone ID, audio encrypted (SRTP), telephone codecs only.
 *
 * It runs only while it's needed — Ledger open on screen, a call coming in
 * (woken by Google push, LedgerMessagingService), or a call going on — and
 * stops (signing out of the line) a minute after, so it costs no battery
 * in between. While it's stopped, a call for this phone makes the server
 * push it awake first.
 *
 * Main thread only.
 */
object LedgerPhone {

    private val HD_AND_PHONE_CODECS = setOf("OPUS", "G722", "PCMA", "PCMU")
    /** What a screen shows about the call going on. */
    data class CallInfo(
        val incoming: Boolean,
        val state: Call.State,
        val number: String,
        val name: String?,
        val connectedAtMs: Long?,
        val muted: Boolean,
        val speaker: Boolean,
        /** What the line says about this ring (passed on, came back, straight to you…). */
        val note: LineNote? = null,
    )

    /**
     * The line's word on a ring (X-Ledger-Transfer, [ledger-headers]):
     *   from    a colleague passed the call on to this phone (name: who)
     *   back    passed on to someone who didn't answer, so it came back (name: who)
     *   direct  a client in work, sent straight to their coordinator
     *   missed  a client whose coordinator didn't answer, now for the call center (name: who)
     */
    data class LineNote(val kind: String, val name: String?)

    fun interface Listener {
        fun onChanged(info: CallInfo?)
    }

    private const val IDLE_STOP_MS = 60_000L
    private val RETRY_DELAYS_MS = longArrayOf(3_000L, 10_000L, 30_000L)
    private var signInRetries = 0
    private val handler = Handler(Looper.getMainLooper())
    private var core: Core? = null
    private var appContext: Context? = null
    private var inForeground = false
    private val listeners = mutableSetOf<Listener>()
    private var connectedAtMs: Long? = null

    /** Whether this phone can call through the line: switched on and signed in. */
    fun isReady(context: Context): Boolean = Session.calling(context) != null && SecureStore.sipSecret(context) != null

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onChanged(currentInfo())
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Ledger is (not) on screen: on screen, it stays signed in so calls ring at once. */
    fun setForeground(context: Context, foreground: Boolean) {
        inForeground = foreground
        if (foreground && isReady(context)) start(context) else scheduleIdleStop()
    }

    /**
     * Starts the engine and signs in to the line (if it isn't already).
     * Returns null when this phone can't call (switched off, not signed in).
     */
    fun start(context: Context): Core? {
        core?.let {
            cancelIdleStop()
            it.refreshRegisters()
            return it
        }
        val calling = Session.calling(context) ?: return null
        val secret = SecureStore.sipSecret(context) ?: return null
        val ctx = context.applicationContext
        appContext = ctx
        val factory = Factory.instance()
        factory.setLoggerDomain("LedgerPhone")
        factory.enableLogcatLogs(BuildConfig.DEBUG)
        val c = factory.createCore(null, null, ctx)

        // TLS only, verified against Ledger's own certificate authority.
        val transports = c.transports
        transports.udpPort = 0
        transports.tcpPort = 0
        transports.tlsPort = -1
        c.transports = transports
        c.setRootCaData(ctx.resources.openRawResource(R.raw.ledger_pbx_ca).bufferedReader().use { it.readText() })
        c.verifyServerCertificates(true)
        c.verifyServerCn(true)
        // Encrypted audio, required. HD voice first (Opus, then G.722) so the
        // staff side is recorded clearly for the call's text; telephone audio
        // (PCMA/PCMU) when HD can't be used.
        c.mediaEncryption = MediaEncryption.SRTP
        c.isMediaEncryptionMandatory = true
        for (payload in c.audioPayloadTypes) payload.enable(payload.mimeType.uppercase() in HD_AND_PHONE_CODECS)
        for (payload in c.videoPayloadTypes) payload.enable(false)
        c.isVideoCaptureEnabled = false
        c.isVideoDisplayEnabled = false
        c.isIpv6Enabled = false
        // The phone's own ringtone and vibration.
        c.isNativeRingingEnabled = true
        c.maxCalls = 1
        // Ledger wakes the phone for a call itself (LedgerMessagingService);
        // the engine's own push handling stays off, so the phone's push
        // address isn't handed to the line.
        c.isPushNotificationEnabled = false

        val params = c.createAccountParams()
        params.identityAddress = factory.createAddress("sip:${calling.ext}@${calling.host}")
        val server = factory.createAddress("sip:${calling.host}:${calling.port}")
        server?.transport = TransportType.Tls
        params.serverAddress = server
        params.isRegisterEnabled = true
        params.expires = 300
        params.pushNotificationAllowed = false
        params.remotePushNotificationAllowed = false
        val account = c.createAccount(params)
        c.addAuthInfo(factory.createAuthInfo(calling.ext, null, secret, null, calling.realm, calling.host))
        c.addAccount(account)
        c.defaultAccount = account
        c.addListener(coreListener)
        signInRetries = 0
        c.start()
        core = c
        DiagnosticLog.append(ctx, "phone line: started (${calling.ext})")
        return c
    }

    /** Signs out of the line and stops the engine (nothing going on). */
    fun stop() {
        val c = core ?: return
        core = null
        c.removeListener(coreListener)
        c.stop()
        appContext?.let { DiagnosticLog.append(it, "phone line: stopped") }
    }

    /** Sign-out of Ledger, or calling switched off: stop and forget. */
    fun shutdown(context: Context) {
        stop()
        SecureStore.clearSipSecret(context)
    }

    /**
     * Calls `number` (a client's number or a colleague's phone ID) through the
     * line. False when this phone can't call through it.
     */
    fun call(context: Context, number: String): Boolean {
        val c = start(context) ?: return false
        val calling = Session.calling(context) ?: return false
        val address = Factory.instance().createAddress("sip:${dialable(number)}@${calling.host}") ?: return false
        val params = c.createCallParams(null) ?: return false
        params.mediaEncryption = MediaEncryption.SRTP
        c.inviteAddressWithParams(address, params) ?: return false
        CallerInfo.prefetch(context, dialable(number))
        DiagnosticLog.append(context, "phone line: calling out")
        CallService.update(context)
        CallActivity.open(context)
        return true
    }

    fun accept() {
        val call = current() ?: return
        val params = core?.createCallParams(call)
        if (params != null) call.acceptWithParams(params) else call.accept()
    }

    fun decline() {
        val call = current() ?: return
        if (call.state == Call.State.IncomingReceived || call.state == Call.State.IncomingEarlyMedia) call.decline(Reason.Busy) else call.terminate()
    }

    fun hangUp() {
        current()?.terminate()
    }

    fun toggleMute() {
        val c = core ?: return
        c.isMicEnabled = !c.isMicEnabled
        notifyChanged()
    }

    /** Loudspeaker on/off (earpiece otherwise; a Bluetooth headset if one is in use). */
    fun toggleSpeaker() {
        val c = core ?: return
        val call = current() ?: return
        val onSpeaker = call.outputAudioDevice?.type == AudioDevice.Type.Speaker
        val target = if (onSpeaker) {
            c.audioDevices.firstOrNull { it.type == AudioDevice.Type.Bluetooth } ?: c.audioDevices.firstOrNull { it.type == AudioDevice.Type.Earpiece }
        } else {
            c.audioDevices.firstOrNull { it.type == AudioDevice.Type.Speaker }
        }
        if (target != null) call.outputAudioDevice = target
        notifyChanged()
    }

    /** Sends a keypad tone (a menu asking "press 1"), and plays it here too. */
    fun sendDtmf(digit: Char) {
        val call = current() ?: return
        core?.playDtmf(digit, 120)
        call.sendDtmf(digit)
    }

    /** A call ringing or going on now. */
    fun inCall(): Boolean = core?.calls?.any { it.state !in ENDED } == true

    fun currentInfo(): CallInfo? {
        val call = current() ?: return null
        val remote = call.remoteAddress
        return CallInfo(
            incoming = call.dir == Call.Dir.Incoming,
            state = call.state,
            number = remote.username ?: "",
            name = remote.displayName?.takeIf { it.isNotBlank() },
            connectedAtMs = connectedAtMs,
            muted = core?.isMicEnabled == false,
            speaker = call.outputAudioDevice?.type == AudioDevice.Type.Speaker,
            note = lineNote(call),
        )
    }

    // The line's X-Ledger-Transfer header: "from:<name>", "back:<name>",
    // "direct" (a client of this coordinator), "mine:thinking" / "mine:second"
    // (this operator's caller), "missed:<coordinator>" / "missedop:<operator>"
    // (theirs, not answered) — LineNote.
    private fun lineNote(call: Call): LineNote? {
        if (call.dir != Call.Dir.Incoming) return null
        val raw = try {
            call.remoteParams?.getCustomHeader("X-Ledger-Transfer")
        } catch (e: Exception) {
            null
        } ?: return null
        val text = try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (e: Exception) {
            raw
        }
        val kind = text.substringBefore(':').trim()
        val name = text.substringAfter(':', "").trim().takeIf { it.isNotBlank() }
        return if (kind in setOf("from", "back", "direct", "mine", "missed", "missedop")) LineNote(kind, name) else null
    }

    // The call that's on — not one still ending: a call passed on comes back
    // ([ledger-transfer]) while the old one is being let go.
    private fun current(): Call? = core?.currentCall ?: core?.calls?.firstOrNull { it.state !in ENDED } ?: core?.calls?.firstOrNull()

    private val ENDED = setOf(Call.State.End, Call.State.Released, Call.State.Error)

    // "+998 90 123-45-67" -> "+998901234567"; a phone ID stays as it is.
    private fun dialable(number: String): String = number.filter { it.isDigit() || it == '+' }

    private val coreListener = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            val ctx = appContext ?: return
            when (state) {
                Call.State.IncomingReceived -> {
                    cancelIdleStop()
                    connectedAtMs = null
                    call.remoteAddress.username?.let { CallerInfo.prefetch(ctx, it) }
                    DiagnosticLog.append(ctx, "phone line: incoming call")
                    // Ledger on screen: straight to the call screen. Otherwise
                    // the ringing notification (full screen when locked).
                    if (inForeground) CallActivity.open(ctx) else CallNotifications.showIncoming(ctx, currentInfo())
                    CallService.update(ctx)
                }
                Call.State.Connected, Call.State.StreamsRunning -> {
                    if (connectedAtMs == null) connectedAtMs = System.currentTimeMillis()
                    CallNotifications.cancelIncoming(ctx)
                    CallService.update(ctx)
                }
                Call.State.End, Call.State.Released, Call.State.Error -> {
                    connectedAtMs = null
                    CallNotifications.cancelIncoming(ctx)
                    if (core.callsNb == 0) {
                        CallService.stop(ctx)
                        scheduleIdleStop()
                    }
                }
                else -> {}
            }
            notifyChanged()
        }

        override fun onAccountRegistrationStateChanged(core: Core, account: org.linphone.core.Account, state: RegistrationState?, message: String) {
            val ctx = appContext ?: return
            when (state) {
                RegistrationState.Ok -> signInRetries = 0
                RegistrationState.Failed -> {
                    DiagnosticLog.append(ctx, "phone line: sign-in failed ($message)")
                    // Try again a few times, later each time (the line may
                    // just be catching up with a new sign-in).
                    if (signInRetries < RETRY_DELAYS_MS.size) {
                        val delay = RETRY_DELAYS_MS[signInRetries++]
                        handler.postDelayed({ if (this@LedgerPhone.core === core) core.refreshRegisters() }, delay)
                    }
                }
                else -> {}
            }
        }
    }

    private fun notifyChanged() {
        val info = currentInfo()
        for (listener in listeners.toList()) listener.onChanged(info)
    }

    private val idleStop = Runnable {
        val c = core ?: return@Runnable
        if (!inForeground && c.callsNb == 0) stop()
    }

    private fun scheduleIdleStop() {
        handler.removeCallbacks(idleStop)
        handler.postDelayed(idleStop, IDLE_STOP_MS)
    }

    private fun cancelIdleStop() {
        handler.removeCallbacks(idleStop)
    }

    /** Woken by Google push for a call: sign in now, stop again if nothing comes. */
    fun wakeForCall(context: Context) {
        if (start(context) == null) return
        scheduleIdleStop()
    }
}
