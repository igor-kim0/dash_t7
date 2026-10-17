package com.dash.headunit.widgets

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.elm.ElmBtProbe
import com.dash.headunit.elm.ElmHub
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard
import com.dash.headunit.ui.DashDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Виджет «OBD (ELM)»: температура ОЖ по PID 0105 и панель Bluetooth-диагностики.
 *
 * Помимо ОЖ показывает состояние BT автомобиля и даёт «поработать» с ним:
 * скан ([ElmBtProbe.scan]), список спаренных/найденных устройств, спаривание
 * (`createBond`) и тест-подключение по SPP/RFCOMM с подробным журналом.
 * Это нужно, когда машина видит сканер, но не спаривает его.
 */
class ElmWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private val labelView = TextView(context)
    private val tempView = TextView(context)
    private val statusView = TextView(context)
    private val btStatusView = TextView(context)
    private val scanBtn = button("Скан")
    private val pickBtn = button("Устройства")
    private val testBtn = button("Тест ELM")
    private val pinBtn = button("PIN")
    private val logView = TextView(context)
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

        labelView.apply {
            text = context.getString(R.string.elm_title)
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        tempView.apply {
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            gravity = Gravity.CENTER
        }
        statusView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        btStatusView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }
        addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(tempView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(statusView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(btStatusView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }
        controls.addView(scanBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(pickBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(testBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(pinBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        logView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        addView(
            ScrollView(context).apply { addView(logView) },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )

        scanBtn.setOnClickListener {
            if (ElmBtProbe.scanning) ElmBtProbe.stopScan() else ElmBtProbe.scan()
            render()
        }
        pickBtn.setOnClickListener { showDevices() }
        pinBtn.setOnClickListener { showPin() }
        testBtn.setOnClickListener {
            val addr = ElmHub.address()
            if (addr == null) {
                ElmBtProbe.testConnect("") // подскажет «неверный адрес»
            } else {
                ElmBtProbe.testConnect(addr)
            }
        }
        // Тап по значению ОЖ — как раньше: поднять связь с ELM.
        tempView.setOnClickListener { ElmHub.start(context) }

        render()
    }

    private fun button(text: String) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(theme.primaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = ThemeCard.card(context, theme)
    }

    private fun render() {
        val coolant = ElmHub.coolantC()
        if (coolant != null) {
            tempView.text = "%.0f°".format(coolant)
            tempView.setTextColor(engineTempColor(coolant, theme.success))
            statusView.text = context.getString(R.string.elm_link_ok)
        } else {
            tempView.text = "—"
            tempView.setTextColor(theme.primaryText)
            val s = ElmHub.lastStatus
            statusView.text = when {
                ElmHub.address() == null -> context.getString(R.string.elm_no_adapter)
                s.startsWith("подключа") || s.startsWith("подключился") ||
                    s.startsWith("ELM настроен") -> context.getString(R.string.elm_connecting)
                else -> context.getString(R.string.elm_no_link)
            }
        }
        scanBtn.text = if (ElmBtProbe.scanning) "Стоп" else "Скан"
        pinBtn.text = "PIN ${ElmBtProbe.pin}"
        btStatusView.text = ElmBtProbe.status
        logView.text = ElmBtProbe.log().takeLast(LOG_ROWS).joinToString("\n") { "${hms(it.at)} ${it.text}" }
    }

    /** Список спаренных и найденных устройств: тап — спарить или выбрать. */
    private fun showDevices() {
        val list = ElmBtProbe.devices()
        if (list.isEmpty()) {
            ElmBtProbe.scan()
            render()
            return
        }
        val items = list.map { d ->
            val state = if (d.bonded) "спарен" else ElmBtProbe.bondName(d) + (d.rssi?.let { " $it dBm" } ?: "")
            "${d.name ?: d.address} — ${d.address} ($state)"
        }.toTypedArray()
        DashDialog.Builder(context)
            .setTitle("BT: устройства (${list.size})")
            .setItems(items) { _, which ->
                val d = list[which]
                if (d.bonded) {
                    ElmHub.setDevice(context, d.address, d.name)
                    ElmHub.start(context)
                } else {
                    ElmBtProbe.pair(d.address)
                }
                render()
            }
            .setNeutralButton("Забыть") { _, _ ->
                val bonded = list.filter { it.bonded }
                if (bonded.isNotEmpty()) {
                    DashDialog.Builder(context)
                        .setTitle("Отменить пару")
                        .setItems(bonded.map { "${it.name ?: it.address} — ${it.address}" }.toTypedArray()) { _, i ->
                            ElmBtProbe.unpair(bonded[i].address)
                            render()
                        }
                        .showFullScreen()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /** Выбор PIN, которым зонд сам ответит на запрос спаривания. */
    private fun showPin() {
        val pins = arrayOf("1234", "0000", "1111", "9999", "123456", "000000")
        val items = pins.map { it + if (it == ElmBtProbe.pin) "  ✓" else "" }.toTypedArray()
        DashDialog.Builder(context)
            .setTitle("PIN для спаривания (сейчас ${ElmBtProbe.pin})")
            .setItems(items) { _, i ->
                ElmBtProbe.setPin(pins[i])
                render()
            }
            .setNeutralButton(if (ElmBtProbe.autoAnswerPairing) "Автоответ: вкл" else "Автоответ: выкл") { _, _ ->
                ElmBtProbe.setAutoAnswerPairing(!ElmBtProbe.autoAnswerPairing)
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
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

    private fun hms(wallMs: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(wallMs))

    private companion object {
        const val REFRESH_MS = 1_000L
        const val LOG_ROWS = 10
    }
}
