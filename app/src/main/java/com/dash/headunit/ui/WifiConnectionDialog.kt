package com.dash.headunit.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.dash.headunit.R
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeStore
import com.dash.headunit.wifi.WifiHub

/**
 * Диалог управления Wi-Fi подключением ГУ: вкл/выкл, сохранённые
 * сети, сети из эфира и добавление сети (SSID/пароль) — всё без
 * системного экрана настроек (на ГУ он обрезан).
 * Один и тот же диалог открывают виджет «Wi-Fi» и кнопка Wi-Fi
 * на боковой панели.
 */
object WifiConnectionDialog {

    private fun dp(ctx: Context, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    /** Показать диалог управления подключениями. */
    fun show(context: Context) {
        WifiHub.init(context)
        WifiHub.ensurePolling()
        val theme = ThemeStore(ThemeStore.PREFS, context).current()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), 0, dp(context, 16), dp(context, 16))
        }
        val listener: () -> Unit = { renderRows(context, theme, list) }
        renderRows(context, theme, list)
        WifiHub.addListener(listener)
        WifiHub.refresh()

        val dialog = DashDialog.Builder(context)
            .setTitle(R.string.wifi_title)
            .setView(list)
            .setPositiveButton(R.string.dialog_ok, null)
            .showFullScreen()
        dialog.setOnDismissListener { WifiHub.removeListener(listener) }
    }

    // ---------- список ----------

    private fun renderRows(context: Context, theme: DashTheme, host: LinearLayout) {
        host.removeAllViews()
        val st = WifiHub.state ?: run {
            host.addView(note(context, theme, context.getString(R.string.loading)))
            return
        }

        // Строка-переключатель: тап по ней включает/выключает Wi-Fi.
        host.addView(
            row(
                context, theme,
                title = context.getString(R.string.wifi_toggle_row),
                note = context.getString(if (st.enabled) R.string.wifi_on else R.string.wifi_off),
                noteAccent = st.enabled,
                onClick = { WifiHub.setEnabled(!st.enabled) }
            )
        )
        if (st.enabled && st.ip != null) {
            host.addView(note(context, theme, context.getString(R.string.wifi_ip_note, st.ip)))
        }
        if (!st.enabled) {
            host.addView(note(context, theme, context.getString(R.string.wifi_note_off)))
            return
        }

        val savedBySsid = st.saved.associateBy { it.ssid }
        val connected = st.connectedSsid

        if (st.saved.isNotEmpty()) {
            host.addView(section(context, theme, context.getString(R.string.wifi_saved_title)))
            // Текущее подключение — первым.
            st.saved.sortedByDescending { it.ssid == connected }.forEach { net ->
                host.addView(
                    row(
                        context, theme,
                        title = net.ssid,
                        note = context.getString(
                            if (net.ssid == connected) R.string.wifi_connected_note
                            else R.string.wifi_saved_note
                        ),
                        noteAccent = net.ssid == connected,
                        onClick = {
                            if (net.ssid != connected) {
                                WifiHub.connect(net.netId)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.wifi_connecting, net.ssid),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onLongClick = { showForgetConfirm(context, theme, net.ssid, net.netId) }
                    )
                )
            }
        }

        host.addView(
            section(
                context, theme,
                context.getString(R.string.wifi_visible_title, st.visible.size)
            )
        )
        when {
            !st.canSeeVisible ->
                host.addView(note(context, theme, context.getString(R.string.wifi_note_no_loc)))
            st.visible.isEmpty() ->
                host.addView(note(context, theme, context.getString(R.string.wifi_note_no_visible)))
            else -> st.visible.forEach { vis ->
                val savedNetId = savedBySsid[vis.ssid]?.netId
                host.addView(
                    row(
                        context, theme,
                        title = vis.ssid,
                        note = if (vis.secure) {
                            context.getString(R.string.wifi_rssi_note, vis.rssi)
                        } else {
                            context.getString(R.string.wifi_open_note)
                        },
                        noteAccent = vis.ssid == connected,
                        onClick = {
                            when {
                                savedNetId != null && vis.ssid != connected ->
                                    WifiHub.connect(savedNetId)
                                savedNetId == null ->
                                    showAddNetwork(context, theme, prefSsid = vis.ssid)
                            }
                        },
                        onLongClick = savedNetId?.let { id ->
                            { showForgetConfirm(context, theme, vis.ssid, id) }
                        }
                    )
                )
            }
        }

        host.addView(
            row(
                context, theme,
                title = "＋ " + context.getString(R.string.wifi_add_row),
                note = "",
                noteAccent = false,
                onClick = { showAddNetwork(context, theme, prefSsid = "") }
            )
        )
    }

    /** Строка сети: название, сноска справа, тап и долгий тап. */
    private fun row(
        context: Context,
        theme: DashTheme,
        title: String,
        note: String,
        noteAccent: Boolean,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(context, 56)
        isClickable = true
        isLongClickable = onLongClick != null
        background = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(theme.accent, 0x40)),
            null, ColorDrawable(Color.WHITE)
        )
        addView(
            TextView(context).apply {
                text = title
                textSize = 18f
                setTextColor(theme.primaryText)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (note.isNotBlank()) {
            addView(
                TextView(context).apply {
                    text = note
                    textSize = 14f
                    setTextColor(if (noteAccent) theme.accent else theme.secondaryText)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(context, 12) }
            )
        }
        setOnClickListener { onClick() }
        onLongClick?.let { long -> setOnLongClickListener { long(); true } }
    }

    private fun section(context: Context, theme: DashTheme, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            letterSpacing = 0.08f
            setTextColor(theme.secondaryText)
            setPadding(dp(context, 12), dp(context, 14), dp(context, 12), dp(context, 4))
        }

    private fun note(context: Context, theme: DashTheme, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(theme.secondaryText)
            setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 8))
        }

    // ---------- добавление сети ----------

    private fun showAddNetwork(context: Context, theme: DashTheme, prefSsid: String) {
        val pad = dp(context, 8)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun addField(label: String): EditText {
            root.addView(
                TextView(context).apply {
                    text = label
                    textSize = 14f
                    setTextColor(theme.secondaryText)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(context, 6), 0, dp(context, 2)) }
            )
            return EditText(context).apply {
                hint = label
                isSingleLine = true
                textSize = 18f
                root.addView(
                    this,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(context, 10) }
                )
            }
        }
        val ssidField = addField(context.getString(R.string.wifi_add_ssid)).apply {
            setText(prefSsid)
            setSelection(prefSsid.length)
        }
        val passField = addField(context.getString(R.string.wifi_add_pass)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ssidField.requestFocus()

        DashDialog.Builder(context)
            .setTitle(R.string.wifi_add_title)
            .setView(root)
            .setPositiveButton(R.string.wifi_add_connect) { _, _ ->
                WifiHub.addAndConnect(ssidField.text.toString().trim(), passField.text.toString()) { ok ->
                    Toast.makeText(
                        context,
                        context.getString(if (ok) R.string.wifi_added else R.string.wifi_add_fail),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    private fun showForgetConfirm(context: Context, theme: DashTheme, ssid: String, netId: Int) {
        DashDialog.Builder(context)
            .setTitle(R.string.wifi_forget_title)
            .setMessage(context.getString(R.string.wifi_forget_text, ssid))
            .setPositiveButton(R.string.wifi_forget) { _, _ -> WifiHub.forget(netId) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }
}
