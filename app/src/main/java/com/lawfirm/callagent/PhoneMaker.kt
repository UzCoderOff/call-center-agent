package com.lawfirm.callagent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Phone-maker specifics of setup. Honor (most of the firm's phones), Huawei
 * and Xiaomi run their own battery manager on top of Android's, which stops
 * background apps unless the app may "auto-launch" — a switch only the
 * person can turn on, so setup opens that screen and says exactly what to
 * tap. An app can't read that switch back, so the person confirms it.
 */
object PhoneMaker {
    enum class Kind { HONOR, HUAWEI, XIAOMI, OTHER }

    private const val PREFS = "ledger_setup"
    private const val AUTO_LAUNCH_DONE = "auto_launch_done"

    val kind: Kind by lazy {
        val name = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        when {
            "honor" in name -> Kind.HONOR
            "huawei" in name -> Kind.HUAWEI
            "xiaomi" in name || "redmi" in name || "poco" in name -> Kind.XIAOMI
            else -> Kind.OTHER
        }
    }

    val needsAutoLaunch: Boolean get() = kind != Kind.OTHER

    /** Step-by-step instructions for this maker's auto-launch screen. */
    val autoLaunchHowTo: Int
        get() = if (kind == Kind.XIAOMI) R.array.setup_launch_howto_xiaomi else R.array.setup_launch_howto_honor

    fun autoLaunchConfirmed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO_LAUNCH_DONE, false)

    fun confirmAutoLaunch(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTO_LAUNCH_DONE, true).apply()
    }

    private fun screen(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    // The makers' own "app launch" screens. They move between OS versions,
    // so each maker has a few candidates, tried in order.
    private fun autoLaunchScreens(): List<Intent> = when (kind) {
        Kind.HONOR -> listOf(
            screen("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            screen("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        Kind.HUAWEI -> listOf(
            screen("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            screen("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        Kind.XIAOMI -> listOf(
            screen("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        Kind.OTHER -> emptyList()
    }

    /** Opens the maker's auto-launch screen, or failing that this app's settings page. */
    fun openAutoLaunch(context: Context) {
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        for (intent in autoLaunchScreens() + appInfo) {
            try {
                context.startActivity(intent)
                DiagnosticLog.append(context, "setup: opened ${intent.component?.className ?: intent.action}")
                return
            } catch (e: Exception) {
                // Not on this phone, or not allowed — try the next one.
            }
        }
    }
}
