package com.lawfirm.callagent

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
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

/**
 * Phone setup, only for people whose calls are collected (the portal's
 * per-person switch). One step per screen, in plain words, with exactly
 * what to tap:
 *
 *   1. call log   2. recordings (all files)   3. battery
 *   4. auto-launch — Honor / Huawei / Xiaomi only (their own battery manager)
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

    // Whether the call-log prompt was shown this time: after two refusals
    // Android stops showing it, and only the app's settings page can allow it.
    private var callLogAsked = false

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
        val list = mutableListOf(
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
            Step(
                R.drawable.ic_step_battery, R.string.setup_battery_title, R.string.setup_battery_text,
                R.array.setup_battery_howto, R.string.setup_allow, required = false,
                isDone = { Permissions.battery(this) },
                open = { openBattery() },
            ),
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
            text.setText(R.string.setup_ready_text)
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

    private fun requestCallLog() {
        if (callLogAsked && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALL_LOG)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            callLogAsked = true
            runtimeRequest.launch(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE))
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
        if (Permissions.requiredGranted(this)) {
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
