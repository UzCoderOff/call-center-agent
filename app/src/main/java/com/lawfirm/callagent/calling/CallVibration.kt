package com.lawfirm.callagent.calling

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The buzz while a call rings, as the phone buzzes for an ordinary call.
 * The calling engine plays the phone's ringtone only when its sound is on,
 * and never vibrates — so on a phone set to vibrate, a call came in with no
 * sound and no buzz at all. Marked as a ringtone, Android applies the phone's
 * own rules to it: it buzzes on vibrate, with the sound on only if "vibrate
 * for calls" is on, and never on silent.
 *
 * Main thread only (LedgerPhone).
 */
object CallVibration {
    // Buzz 1 s, pause 1 s, over and over.
    private val PATTERN = longArrayOf(0L, 1_000L, 1_000L)
    private var on = false

    fun start(context: Context) {
        if (on) return
        val vibrator = vibrator(context)?.takeIf { it.hasVibrator() } ?: return
        val effect = VibrationEffect.createWaveform(PATTERN, 1)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
            on = true
        } catch (e: Exception) {
            // No buzz (rare): the call still rings and shows.
        }
    }

    fun stop(context: Context) {
        if (!on) return
        on = false
        vibrator(context)?.cancel()
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
