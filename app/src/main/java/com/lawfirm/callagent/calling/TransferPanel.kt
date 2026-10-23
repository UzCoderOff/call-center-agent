package com.lawfirm.callagent.calling

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lawfirm.callagent.Api
import com.lawfirm.callagent.R
import com.lawfirm.callagent.SecureStore

/**
 * Passing a client's call on to a colleague — "Ulash" on the call screen
 * (backend: services/pbx/transfer.js). A panel slides up over the call:
 * the people who work on this client first (their coordinator, lawyers),
 * then clients found by name or number with whoever works on them, then
 * everyone on the line. A person, after a yes, gets the call: the client
 * waits with music, and if nobody answers the call comes back here.
 *
 * An operator sees only who works on a client — never the case itself.
 */
class TransferPanel(
    private val activity: AppCompatActivity,
    root: View,
    /** The other side of the call (the client's number). */
    private val number: () -> String,
) {
    private val scrim: View = root.findViewById(R.id.transferScrim)
    private val sheet: View = root.findViewById(R.id.transferSheet)
    private val search: EditText = root.findViewById(R.id.transferSearch)
    private val list: LinearLayout = root.findViewById(R.id.transferList)
    private val main = Handler(Looper.getMainLooper())
    private var request = 0
    private var sending = false

    /** The people who work on this client (null: not known yet), for the call screen's own card. */
    var forCaller: List<Api.TransferPerson>? = null
        private set
    private var onForCaller: (() -> Unit)? = null
    private var loadedFor: String? = null

    val isOpen get() = sheet.visibility == View.VISIBLE

    init {
        scrim.setOnClickListener { close() }
        root.findViewById<View>(R.id.transferClose).setOnClickListener { close() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                main.removeCallbacks(searchSoon)
                main.postDelayed(searchSoon, 300)
            }
        })
    }

    private val searchSoon = Runnable { load(search.text.toString().trim()) }

    /** Fetches who works on this client once the call is on (shown on the call screen too). */
    fun prefetch(changed: () -> Unit) {
        onForCaller = changed
        val n = number()
        if (n.isBlank() || loadedFor == n) return
        loadedFor = n
        load("")
    }

    fun open() {
        if (isOpen) return
        if (search.text.isNotEmpty()) search.setText("")
        // Most of the screen, leaving the call's top in sight; with the
        // keyboard up the panel shrinks above it (the search stays visible).
        val screen = (sheet.parent as View).height
        sheet.layoutParams = (sheet.layoutParams as FrameLayout.LayoutParams).apply {
            height = FrameLayout.LayoutParams.MATCH_PARENT
            topMargin = (screen * 0.14f).toInt()
        }
        scrim.visibility = View.VISIBLE
        sheet.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(180).setListener(null).start()
        sheet.translationY = screen.toFloat()
        sheet.animate().translationY(0f).setDuration(260).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).setListener(null).start()
        showMessage(R.string.transfer_loading)
        load("")
    }

    fun close() {
        if (!isOpen) return
        hideKeyboard()
        scrim.animate().alpha(0f).setDuration(160).start()
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(200).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                sheet.visibility = View.GONE
                scrim.visibility = View.GONE
            }
        }).start()
    }

    private fun load(query: String) {
        val token = SecureStore.token(activity) ?: return
        val id = ++request
        val n = number()
        Thread {
            val result = try {
                Api.transferTargets(token, n, query)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (activity.isDestroyed || id != request) return@post
                if (result != null && query.isEmpty()) {
                    forCaller = result.forCaller
                    onForCaller?.invoke()
                }
                if (!isOpen) return@post
                if (result == null) showMessage(R.string.transfer_load_failed) else render(result, query)
            }
        }.start()
    }

    private fun render(t: Api.TransferTargets, query: String) {
        list.removeAllViews()
        val ctx: Context = activity
        if (query.isEmpty() && t.forCaller.isNotEmpty()) {
            list.addView(CallUi.section(ctx, ctx.getString(R.string.call_works_on), top = 10))
            t.forCaller.forEach { list.addView(CallUi.personRow(ctx, it) { p -> confirm(p) }) }
        }
        if (t.clients.isNotEmpty()) {
            list.addView(CallUi.section(ctx, ctx.getString(R.string.transfer_clients)))
            for (c in t.clients) {
                val head = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(CallUi.dp(ctx, 12), CallUi.dp(ctx, 8), CallUi.dp(ctx, 12), CallUi.dp(ctx, 2))
                }
                head.addView(CallUi.text(ctx, c.name, 15f, "#FFFFFF", bold = true))
                c.phone?.let { head.addView(CallUi.text(ctx, "  $it", 13f, "#80FFFFFF")) }
                list.addView(head)
                val people = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(CallUi.dp(ctx, 8), 0, 0, 0)
                }
                c.people.forEach { people.addView(CallUi.personRow(ctx, it) { p -> confirm(p) }) }
                list.addView(people)
            }
        }
        if (t.colleagues.isNotEmpty()) {
            list.addView(CallUi.section(ctx, ctx.getString(R.string.transfer_colleagues)))
            // Who can take it now first.
            t.colleagues.sortedBy { if (it.status == "off") 1 else 0 }.forEach { list.addView(CallUi.personRow(ctx, it) { p -> confirm(p) }) }
        }
        if (list.childCount == 0) showMessage(R.string.transfer_empty)
    }

    private fun showMessage(text: Int) {
        list.removeAllViews()
        list.addView(
            CallUi.text(activity, activity.getString(text), 14f, "#99FFFFFF").apply {
                gravity = Gravity.CENTER
                setPadding(0, CallUi.dp(activity, 36), 0, CallUi.dp(activity, 36))
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    /** "Pass the call to Aziza?" — then it goes. */
    fun confirm(person: Api.TransferPerson) {
        if (sending) return
        if (person.status == "off" || person.ext == null) {
            Toast.makeText(activity, R.string.transfer_target_off, Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboard()
        val who = listOfNotNull(person.name, person.role).joinToString(", ")
        val message = if (person.status == "busy") activity.getString(R.string.transfer_confirm_busy, who) else activity.getString(R.string.transfer_confirm, who)
        AlertDialog.Builder(activity, R.style.CallDialog)
            .setTitle(R.string.transfer_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.call_transfer) { _, _ -> send(person) }
            .setNegativeButton(R.string.transfer_cancel, null)
            .show()
    }

    private fun send(person: Api.TransferPerson) {
        val token = SecureStore.token(activity) ?: return
        sending = true
        val app = activity.applicationContext
        Toast.makeText(app, R.string.transfer_sending, Toast.LENGTH_SHORT).show()
        Thread {
            val error = try {
                Api.transfer(token, person.employeeId)
                null
            } catch (e: Api.HttpError) {
                e.code ?: "failed"
            } catch (e: Exception) {
                "failed"
            }
            main.post {
                sending = false
                val text = when (error) {
                    null -> app.getString(R.string.transfer_done, person.name)
                    "no_call" -> app.getString(R.string.transfer_no_call)
                    "target_off", "no_target" -> app.getString(R.string.transfer_target_off)
                    else -> app.getString(R.string.transfer_failed)
                }
                // The call screen closes by itself when the call moves on.
                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
                if (error == null && !activity.isDestroyed) close()
            }
        }.start()
    }

    private fun hideKeyboard() {
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }
}
