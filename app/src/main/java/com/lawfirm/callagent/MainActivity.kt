package com.lawfirm.callagent

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var employeeIdInput: EditText
    private lateinit var serverUrlInput: EditText
    private lateinit var statusText: TextView

    private val prefs by lazy { getSharedPreferences(Prefs.NAME, MODE_PRIVATE) }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        toast(if (denied.isEmpty()) "Permissions granted" else "Still missing: ${denied.joinToString()}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        employeeIdInput = findViewById(R.id.employeeIdInput)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        statusText = findViewById(R.id.statusText)

        employeeIdInput.setText(prefs.getString(Prefs.EMPLOYEE_ID, ""))
        serverUrlInput.setText(prefs.getString(Prefs.SERVER_URL, ""))

        findViewById<Button>(R.id.saveButton).setOnClickListener { saveAndSchedule() }
        findViewById<Button>(R.id.permissionsButton).setOnClickListener { requestAllPermissions() }
        findViewById<Button>(R.id.batteryButton).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.syncNowButton).setOnClickListener { syncNow() }
    }

    private fun saveAndSchedule() {
        val employeeId = employeeIdInput.text.toString().trim()
        val serverUrl = serverUrlInput.text.toString().trim().trimEnd('/')

        if (employeeId.isEmpty() || serverUrl.isEmpty()) {
            toast("Fill in both fields first")
            return
        }

        // Keep IDs predictable server-side: letters, numbers, underscore, dash only.
        if (!employeeId.matches(Regex("^[A-Za-z0-9_-]{2,64}$"))) {
            toast("Employee ID: letters, numbers, - and _ only (2-64 chars)")
            return
        }

        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            toast("Server URL must start with http:// or https://")
            return
        }

        prefs.edit()
            .putString(Prefs.EMPLOYEE_ID, employeeId)
            .putString(Prefs.SERVER_URL, serverUrl)
            .apply()

        // 12-hour periodic sync. "UPDATE" so re-saving (e.g. after changing the
        // server URL) doesn't create a second, duplicate schedule.
        val request = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "call_sync", ExistingPeriodicWorkPolicy.UPDATE, request
        )

        statusText.text = "Saved. Syncing every 12 hours."
        toast("Saved")
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions.launch(perms.toTypedArray())

        // All-files access is a special permission granted via a Settings screen,
        // not the runtime dialog above.
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } else {
            toast("Already exempt")
        }
    }

    private fun syncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
        WorkManager.getInstance(this)
            .enqueueUniqueWork("manual_sync", ExistingWorkPolicy.REPLACE, request)

        statusText.text = "Syncing…"
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(request.id)
            .observe(this) { info ->
                if (info == null) return@observe
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        val uploaded = info.outputData.getInt("uploaded", 0)
                        statusText.text = "Last sync OK — $uploaded file(s) uploaded"
                    }
                    WorkInfo.State.FAILED -> statusText.text =
                        "Last sync failed — check server URL / permissions"
                    else -> {}
                }
            }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
