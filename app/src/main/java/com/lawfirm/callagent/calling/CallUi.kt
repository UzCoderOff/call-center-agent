package com.lawfirm.callagent.calling

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lawfirm.callagent.Api
import com.lawfirm.callagent.R

/**
 * The pieces the call screen and the transfer panel share: a person's
 * coloured circle with their initials, a row for someone the call can go to,
 * section titles — built in code, in the call screen's dark style.
 */
object CallUi {
    // Calm two-colour circles; a person always gets the same one.
    private val GRADIENTS = arrayOf(
        intArrayOf(0xFF5E5CE6.toInt(), 0xFFBF5AF2.toInt()),
        intArrayOf(0xFF0A84FF.toInt(), 0xFF64D2FF.toInt()),
        intArrayOf(0xFF30B0C7.toInt(), 0xFF63E6BE.toInt()),
        intArrayOf(0xFFFF9F0A.toInt(), 0xFFFFC94D.toInt()),
        intArrayOf(0xFFFF375F.toInt(), 0xFFFF8A65.toInt()),
        intArrayOf(0xFF3478F6.toInt(), 0xFF7D7AFF.toInt()),
    )
    private val NEUTRAL = intArrayOf(0xFF3A4560.toInt(), 0xFF5A647C.toInt())

    const val GREEN = "#30D158"
    const val AMBER = "#FF9F0A"
    const val GREY = "#8E8E93"

    /** A circle for `seed` (a name); null: someone unknown, in grey. */
    fun avatar(seed: String?): GradientDrawable {
        val colors = if (seed.isNullOrBlank()) NEUTRAL else GRADIENTS[Math.floorMod(seed.lowercase().hashCode(), GRADIENTS.size)]
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { shape = GradientDrawable.OVAL }
    }

    /**
     * An Uzbek number with its +998, however it came ("901234567" from the
     * line or a phone, "998901234567", "8 90 123 45 67"); anything else as
     * it was, digits and + only.
     */
    fun e164(raw: String?): String {
        val digits = raw.orEmpty().filter { it.isDigit() }
        return when {
            digits.length == 12 && digits.startsWith("998") -> "+$digits"
            digits.length == 9 -> "+998$digits"
            digits.length == 10 && digits.startsWith("8") -> "+998${digits.drop(1)}"
            else -> raw.orEmpty().filter { it.isDigit() || it == '+' }
        }
    }

    /** "+998901234567" -> "+998 90 123 45 67"; short numbers (a phone ID, 600…) as they are. */
    fun formatPhone(raw: String?): String {
        val n = e164(raw)
        if (!Regex("""^\+998\d{9}$""").matches(n)) return raw.orEmpty()
        val d = n.drop(4)
        return "+998 ${d.take(2)} ${d.substring(2, 5)} ${d.substring(5, 7)} ${d.substring(7)}"
    }

    /** "Aliyev Sardor" -> "AS"; a number has none. */
    fun initialsOf(name: String?): String? {
        val words = name?.split(' ', '\t')?.filter { it.isNotBlank() && it.first().isLetter() } ?: return null
        if (words.isEmpty()) return null
        return words.take(2).joinToString("") { it.first().uppercase() }
    }

    /** The line's word on a ring, as the call screen and the ringing notification say it. */
    fun noteText(context: Context, note: LedgerPhone.LineNote?): String? = when (note?.kind) {
        "from" -> note.name?.let { context.getString(R.string.call_transferred_by, it) }
        "back" -> note.name?.let { context.getString(R.string.call_came_back, it) }
        "direct" -> context.getString(R.string.call_direct)
        "mine" -> context.getString(if (note.name == "second") R.string.call_mine_second else R.string.call_mine_thinking)
        "missed" -> note.name?.let { context.getString(R.string.call_coordinator_missed, it) }
        "missedop" -> note.name?.let { context.getString(R.string.call_operator_missed, it) }
        else -> null
    }

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun text(context: Context, value: String, size: Float, color: String, bold: Boolean = false, top: Int = 0) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            if (top > 0) setPadding(0, dp(context, top), 0, 0)
        }

    /** "BU MIJOZ BILAN ISHLAYDI" over a group of rows. */
    fun section(context: Context, title: String, top: Int = 18) = text(context, title.uppercase(), 12f, "#80FFFFFF", bold = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(context, 12), dp(context, top), dp(context, 12), dp(context, 6))
    }

    fun statusText(context: Context, status: String) = context.getString(
        when (status) {
            "free" -> R.string.transfer_free
            "busy" -> R.string.transfer_busy
            else -> R.string.transfer_off
        }
    )

    fun statusColor(status: String) = when (status) {
        "free" -> GREEN
        "busy" -> AMBER
        else -> GREY
    }

    /**
     * Someone the call can go to: their circle, name, job and whether they're
     * free — and "Ulash". Someone not on the line is shown, dimmed, so the
     * operator knows whose client it is, but can't take the call.
     */
    fun personRow(context: Context, person: Api.TransferPerson, compact: Boolean = false, onTransfer: (Api.TransferPerson) -> Unit): View {
        val canTake = person.status != "off" && person.ext != null
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, if (compact) 56 else 64)
            setPadding(dp(context, if (compact) 0 else 10), dp(context, 6), dp(context, 6), dp(context, 6))
            if (!compact) background = ContextCompat.getDrawable(context, R.drawable.bg_call_row)
            isClickable = canTake
            if (canTake) setOnClickListener { onTransfer(person) }
        }
        val size = dp(context, if (compact) 36 else 42)
        row.addView(
            TextView(context).apply {
                text = initialsOf(person.name) ?: "#"
                gravity = Gravity.CENTER
                textSize = if (compact) 13f else 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = avatar(person.name)
                alpha = if (canTake) 1f else 0.55f
            },
            LinearLayout.LayoutParams(size, size),
        )
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        words.addView(
            text(context, person.name, if (compact) 15f else 16f, if (canTake) "#FFFFFF" else "#99FFFFFF", bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        val meta = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 3), 0, 0)
        }
        meta.addView(
            View(context).apply {
                background = ContextCompat.getDrawable(context, R.drawable.bg_call_dot)
                backgroundTintList = ColorStateList.valueOf(Color.parseColor(statusColor(person.status)))
            },
            LinearLayout.LayoutParams(dp(context, 7), dp(context, 7)).apply { marginEnd = dp(context, 6) },
        )
        meta.addView(
            text(context, listOfNotNull(person.role, statusText(context, person.status)).joinToString(" · "), 13f, "#99FFFFFF").apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        words.addView(meta)
        row.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(context, 12) })
        row.addView(
            TextView(context).apply {
                text = context.getString(R.string.call_transfer)
                textSize = 14f
                setTextColor(if (canTake) Color.WHITE else Color.parseColor("#66FFFFFF"))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(context, R.drawable.bg_call_transfer_button)
                isEnabled = canTake
                setPadding(dp(context, 16), 0, dp(context, 16), 0)
                if (canTake) setOnClickListener { onTransfer(person) }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(context, 36)).apply { marginStart = dp(context, 8) },
        )
        return row
    }
}
