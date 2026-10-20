package com.dash.headunit.widgets

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.elm.ElmBtProbe
import com.dash.headunit.elm.ElmHub
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.ui.DashDialog

/**
 * Виджет «OBD (ELM)»: показания с Bluetooth-сканера ELM327 ([ElmHub]) —
 * температура ОЖ, напряжение бортовой сети, обороты и нагрузка двигателя.
 *
 * Тап по плитке — панель Bluetooth-диагностики ([ElmDiagPanel]): скан,
 * спаривание, выбор адаптера, тест подключения, журнал.
 */
class ElmWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private class Cell(val value: TextView, val caption: TextView)

    private val statusView = TextView(context)
    private val coolant: Cell
    private val voltage: Cell
    private val rpm: Cell
    private val load: Cell
    private val handler = Handler(Looper.getMainLooper())
    private var tickerRunning = false

    private val ticker: Runnable = object : Runnable {
        override fun run() {
            render()
            if (tickerRunning) handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        orientation = VERTICAL
        val pad = dp(6)
        setPadding(pad, pad, pad, pad)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = context.getString(R.string.elm_title)
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        statusView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.END
            maxLines = 1
        }
        header.addView(statusView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val top = row()
        val bottom = row()
        coolant = cell(top, R.string.elm_coolant)
        voltage = cell(top, R.string.elm_voltage)
        rpm = cell(bottom, R.string.elm_rpm)
        load = cell(bottom, R.string.elm_load)

        setOnClickListener { showDiagnostics() }
        render()
    }

    private fun row(): LinearLayout {
        val r = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        addView(r, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        return r
    }

    private fun cell(parent: LinearLayout, captionRes: Int): Cell {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
        val value = TextView(context).apply {
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        val caption = TextView(context).apply {
            text = context.getString(captionRes)
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }
        box.addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        box.addView(caption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        parent.addView(box, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        return Cell(value, caption)
    }

    private fun render() {
        val r = ElmHub.readings()
        show(coolant, r.coolantC?.let { "%.0f°".format(it) }, r.coolantC?.let { engineTempColor(it, theme.success) })
        show(voltage, r.voltage?.let { "%.1f В".format(it) }, r.voltage?.let { voltageColor(it, theme.success) })
        show(rpm, r.rpm?.let { "%.0f".format(it) }, null)
        show(load, r.loadPct?.let { "%.0f %%".format(it) }, null)

        val s = ElmHub.lastStatus
        statusView.text = when {
            ElmHub.address() == null -> context.getString(R.string.elm_no_adapter)
            ElmHub.connected && r != EMPTY -> context.getString(R.string.elm_link_ok)
            ElmHub.connected || s.startsWith("подключа") -> context.getString(R.string.elm_connecting)
            else -> context.getString(R.string.elm_no_link)
        }
    }

    private fun show(cell: Cell, text: String?, color: Int?) {
        cell.value.text = text ?: "—"
        cell.value.setTextColor(color ?: theme.primaryText)
    }

    private fun showDiagnostics() {
        DashDialog.Builder(context)
            .setTitle(R.string.elm_diag_title)
            .setView(ElmDiagPanel(context, theme))
            .setNegativeButton(R.string.dialog_close, null)
            .showFullScreen()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ElmHub.start(context)
        ElmBtProbe.start(context)
        tickerRunning = true
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        tickerRunning = false
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val REFRESH_MS = 500L
        val EMPTY = ElmHub.Readings(null, null, null, null)
    }
}
