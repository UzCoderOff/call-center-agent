package com.lawfirm.callagent.calling

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.lawfirm.callagent.Api
import com.lawfirm.callagent.SecureStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What Ledger knows about the other side of a call (Api.caller): fetched
 * the moment a call starts ringing or going out, so the call screen has it
 * by the time it shows. Kept for the few numbers of the last calls.
 */
object CallerInfo {
    private val main = Handler(Looper.getMainLooper())
    private val cache = LinkedHashMap<String, Api.CallerCard>()
    private val waiting = mutableMapOf<String, MutableList<(Api.CallerCard) -> Unit>>()

    /** Starts fetching (no-op when known or on its way). */
    fun prefetch(context: Context, number: String) = get(context, number) {}

    /** The card for `number`, on the main thread — at once when already known. */
    fun get(context: Context, number: String, done: (Api.CallerCard) -> Unit) {
        if (number.isBlank()) return
        cache[number]?.let { done(it); return }
        val queue = waiting[number]
        if (queue != null) {
            queue += done
            return
        }
        waiting[number] = mutableListOf(done)
        val token = SecureStore.token(context) ?: return
        Thread {
            val card = try {
                Api.caller(token, number)
            } catch (e: Exception) {
                null
            }
            main.post {
                val callbacks = waiting.remove(number).orEmpty()
                if (card != null) {
                    cache[number] = card
                    while (cache.size > 10) cache.remove(cache.keys.first())
                    callbacks.forEach { it(card) }
                }
            }
        }.start()
    }

    private val MONTHS = arrayOf("yanvar", "fevral", "mart", "aprel", "may", "iyun", "iyul", "avgust", "sentabr", "oktabr", "noyabr", "dekabr")

    /** "Bugun 15:30", "Ertaga 10:00", "12-oktabr, 09:00" — from the server's ISO time. */
    fun whenText(iso: String?): String? {
        val at = try {
            Instant.parse(iso ?: return null).atZone(ZoneId.systemDefault())
        } catch (e: Exception) {
            return null
        }
        val time = "%02d:%02d".format(at.hour, at.minute)
        val today = LocalDate.now()
        return when (at.toLocalDate()) {
            today -> "Bugun $time"
            today.plusDays(1) -> "Ertaga $time"
            today.minusDays(1) -> "Kecha $time"
            else -> "${at.dayOfMonth}-${MONTHS[at.monthValue - 1]}, $time"
        }
    }
}
