package com.lawfirm.callagent

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Phone setup, for people whose calls are collected (the portal's
 * per-person switch) and for people who call through Ledger's line. One
 * step per screen, in plain words, with exactly what to tap:
 *
 *   collected calls:  call log, recordings (all files), automatic call
 *                     recording in the Phone app
 *   Ledger's line:    microphone, notifications (Android 13+), calls over
 *                     the lock screen (Android 14+)
 *   both:             battery; auto-launch — Honor / Huawei / Xiaomi only
 *                     (their own battery manager; without it Google push
 *                     can't wake Ledger for a call either)
 *
 * When the person comes back from a system screen with the step done, the
 * next step opens by itself. Auto-launch can't be checked by an app, so the
 * person confirms it. Opened again from the portal's Profile ("review"),
 * it walks through every step, showing which are already done.
 */
class PermissionsActivity : AppCompatActivity() {

    private class Step(
        val icon: Int,
        val title: Int,
        val text: Int,
        val howTo: Int,
        val action: Int,
        val required: Boolean,
        val isDone: () -> Boolean,
        val open: () -> Unit,
        /** For steps the app can't check: the person says they did it. */
        val confirm: (() -> Unit)? = null,
    )

    private lateinit var steps: List<Step>
    private var index = 0
    private var review = false

    // Set when a system screen was opened for the current step, so coming
    // back with it done moves on by itself.
    private var awaiting = false

    // Permission prompts shown this time: after two refusals Android stops
    // showing one, and only the app's settings page can allow it.
    private val asked = mutableSetOf<String>()

    private val collecting by lazy { Session.collectCalls(this) }
    private val calling by lazy { Session.calling(this) != null }

    private val runtimeRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onReturned() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)
        review = intent.getBooleanExtra(EXTRA_REVIEW, false)
        steps = buildSteps()
        index = savedInstanceState?.getInt(STATE_INDEX) ?: if (review) 0 else nextIndex(-1)

        findViewById<Button>(R.id.laterButton).setOnClickListener {
            AppFlow.permissionsPostponed = true
            openPortal()
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_INDEX, index)
    }

    // Back from a system screen (all-files access, battery, auto-launch).
    override fun onResume() {
        super.onResume()
        onReturned()
    }

    private fun onReturned() {
        if (awaiting && index < steps.size && steps[index].isDone()) {
            awaiting = false
            index = nextIndex(index)
        }
        render()
    }

    private fun buildSteps(): List<Step> {
        val list = mutableListOf<Step>()
        if (collecting) list += collectSteps()
        // Everyone: chat, reminders, missed calls (and calls) come as
        // notifications — Android 13+ shows none until the app is allowed.
        if (Build.VERSION.SDK_INT >= 33 || !Permissions.notifications(this)) {
            list += Step(
                R.drawable.ic_step_notify, R.string.setup_notify_title, R.string.setup_notify_text,
                R.array.setup_notify_howto, R.string.setup_allow, required = true,
                isDone = { Permissions.notifications(this) },
                open = { requestNotifications() },
            )
        }
        if (calling) list += callingSteps()
        list += Step(
            R.drawable.ic_step_battery, R.string.setup_battery_title, R.string.setup_battery_text,
            R.array.setup_battery_howto, R.string.setup_allow, required = false,
            isDone = { Permissions.battery(this) },
            open = { openBattery() },
        )
        if (PhoneMaker.needsAutoLaunch) {
            list.add(
                Step(
                    R.drawable.ic_step_launch, R.string.setup_launch_title, R.string.setup_launch_text,
                    PhoneMaker.autoLaunchHowTo, R.string.setup_open_settings, required = false,
                    isDone = { PhoneMaker.autoLaunchConfirmed(this) },
                    open = { PhoneMaker.openAutoLaunch(this) },
                    confirm = { PhoneMaker.confirmAutoLaunch(this) },
                )
            )
        }
        return list
    }

    private fun collectSteps(): List<Step> {
        return listOf(
            Step(
                R.drawable.ic_step_calls, R.string.setup_calls_title, R.string.setup_calls_text,
                R.array.setup_calls_howto, R.string.setup_allow, required = true,
                isDone = { Permissions.callLog(this) },
                open = { requestCallLog() },
            ),
            Step(
                R.drawable.ic_step_files, R.string.setup_files_title, R.string.setup_files_text,
                R.array.setup_files_howto, R.string.setup_open_settings, required = true,
                isDone = { Permissions.files(this) },
                open = { openFilesAccess() },
            ),
            // Ledger sends the recordings the phone itself makes: without
            // automatic recording on, calls arrive with no recording.
            Step(
                R.drawable.ic_step_record, R.string.setup_record_title, R.string.setup_record_text,
                PhoneMaker.recordingHowTo, R.string.setup_open_phone, required = false,
                isDone = { PhoneMaker.recordingConfirmed(this) },
                open = { PhoneMaker.openPhoneApp(this) },
                confirm = { PhoneMaker.confirmRecording(this) },
            ),
        )
    }

    private fun callingSteps(): List<Step> {
        val list = mutableListOf(
            Step(
                R.drawable.ic_step_mic, R.string.setup_mic_title, R.string.setup_mic_text,
                R.array.setup_mic_howto, R.string.setup_allow, required = true,
                isDone = { Permissions.mic(this) },
                open = { requestRuntime(Manifest.permission.RECORD_AUDIO) },
            ),
        )
        if (Build.VERSION.SDK_INT >= 34) {
            list += Step(
                R.drawable.ic_step_lock, R.string.setup_lock_title, R.string.setup_lock_text,
                R.array.setup_lock_howto, R.string.setup_open_settings, required = false,
                isDone = { Permissions.fullScreenCalls(this) },
                open = { openFullScreenCalls() },
            )
        }
        return list
    }

    // The next step to show after `from`: in the normal flow, skip what's
    // already done; in review, show every step.
    private fun nextIndex(from: Int): Int {
        var i = from + 1
        while (!review && i < steps.size && steps[i].isDone()) i++
        return i
    }

    private fun render() {
        val counter = findViewById<TextView>(R.id.stepCounter)
        val icon = findViewById<ImageView>(R.id.stepIcon)
        val title = findViewById<TextView>(R.id.stepTitle)
        val text = findViewById<TextView>(R.id.stepText)
        val howTo = findViewById<LinearLayout>(R.id.stepHowTo)
        val doneLabel = findViewById<View>(R.id.stepDone)
        val primary = findViewById<Button>(R.id.primaryButton)
        val secondary = findViewById<Button>(R.id.secondaryButton)
        val later = findViewById<View>(R.id.laterButton)
        renderProgress(findViewById(R.id.stepProgress))

        if (index >= steps.size) {
            counter.visibility = View.INVISIBLE
            icon.setImageResource(R.drawable.ic_step_done)
            title.setText(R.string.setup_ready_title)
            text.setText(
                when {
                    collecting -> R.string.setup_ready_text
                    calling -> R.string.setup_ready_text_calling
                    else -> R.string.setup_ready_text_notify
                }
            )
            howTo.visibility = View.GONE
            doneLabel.visibility = View.GONE
            primary.setText(R.string.setup_open_portal)
            primary.setOnClickListener { finishSetup() }
            secondary.visibility = View.GONE
            later.visibility = View.GONE
            return
        }

        val step = steps[index]
        val done = step.isDone()
        counter.visibility = View.VISIBLE
        counter.text = getString(R.string.setup_counter, index + 1, steps.size)
        icon.setImageResource(step.icon)
        title.setText(step.title)
        text.setText(step.text)
        fillHowTo(howTo, step.howTo)
        howTo.visibility = if (done) View.GONE else View.VISIBLE
        doneLabel.visibility = if (done) View.VISIBLE else View.GONE
        later.visibility = View.VISIBLE

        primary.setText(if (done) R.string.setup_next else step.action)
        primary.setOnClickListener {
            if (step.isDone()) {
                goTo(nextIndex(index))
            } else {
                awaiting = true
                step.open()
            }
        }

        val confirm = step.confirm
        when {
            !done && confirm != null -> {
                secondary.visibility = View.VISIBLE
                secondary.setText(R.string.setup_did_it)
                secondary.setOnClickListener {
                    confirm()
                    goTo(nextIndex(index))
                }
            }
            !done && !step.required -> {
                secondary.visibility = View.VISIBLE
                secondary.setText(R.string.setup_skip)
                secondary.setOnClickListener { goTo(nextIndex(index)) }
            }
            else -> secondary.visibility = View.GONE
        }
    }

    private fun goTo(next: Int) {
        awaiting = false
        index = next
        render()
    }

    private fun renderProgress(bar: LinearLayout) {
        bar.removeAllViews()
        val gap = (3 * resources.displayMetrics.density).toInt()
        for (i in steps.indices) {
            val segment = View(this)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            params.marginStart = gap
            params.marginEnd = gap
            segment.layoutParams = params
            segment.setBackgroundResource(if (i <= index) R.drawable.bg_progress_on else R.drawable.bg_progress_off)
            bar.addView(segment)
        }
    }

    private fun fillHowTo(container: LinearLayout, lines: Int) {
        container.removeAllViews()
        resources.getStringArray(lines).forEachIndexed { i, line ->
            val row = layoutInflater.inflate(R.layout.item_howto, container, false)
            row.findViewById<TextView>(R.id.howToNumber).text = (i + 1).toString()
            row.findViewById<TextView>(R.id.howToText).text = line
            container.addView(row)
        }
    }

    private fun requestCallLog() =
        requestRuntime(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE)

    // The system prompt — or, once Android has stopped showing it, the app's
    // settings page.
    private fun requestRuntime(vararg permissions: String) {
        val first = permissions.first()
        if (first in asked && !shouldShowRequestPermissionRationale(first)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            asked += first
            runtimeRequest.launch(arrayOf(*permissions))
        }
    }

    private fun requestNotifications() {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val canAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED &&
            (permission !in asked || shouldShowRequestPermissionRationale(permission))
        if (canAsk) {
            asked += permission
            runtimeRequest.launch(arrayOf(permission))
        } else {
            // Allowed but switched off, or refused twice: Ledger's notification settings.
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun openFullScreenCalls() {
        if (Build.VERSION.SDK_INT < 34) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun openFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            runtimeRequest.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    @SuppressLint("BatteryLife")
    private fun openBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun finishSetup() {
        if (collecting && Permissions.requiredGranted(this)) {
            SyncState.ensureInstallFloor(this)
            SyncScheduler.schedule(this)
            SyncScheduler.runNow(this)
            DiagnosticLog.append(this, "setup done (${PhoneMaker.kind}) — sync scheduled")
        }
        openPortal()
    }

    private fun openPortal() {
        // Opened from the portal (collection switched on while it was open,
        // or "Phone setup" in Profile): just go back to it.
        if (!intent.getBooleanExtra(EXTRA_FROM_PORTAL, false)) {
            startActivity(Intent(this, PortalActivity::class.java))
        }
        finish()
    }

    companion object {
        const val EXTRA_FROM_PORTAL = "from_portal"
        const val EXTRA_REVIEW = "review"
        private const val STATE_INDEX = "step"
    }
}
