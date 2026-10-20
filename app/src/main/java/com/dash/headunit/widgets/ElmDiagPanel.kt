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
import com.dash.headunit.theme.ThemeCard
import com.dash.headunit.ui.DashDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Панель Bluetooth-диагностики ELM (диалог по тапу на виджете «OBD»).
 *
 * Состояние BT автомобиля, скан ([ElmBtProbe.scan]), список спаренных/
 * найденных устройств, спаривание (`createBond`), выбор адаптера для
 * [ElmHub] и тест-подключение по SPP/RFCOMM с подробным журналом.
 */
class ElmDiagPanel(
    context: Context,
    private val theme: DashTheme
) : LinearLayout(context) {

    private val linkView = TextView(context)
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

        linkView.apply {
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        btStatusView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, 0)
        }
        addView(linkView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(btStatusView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        listOf(scanBtn, pickBtn, testBtn, pinBtn).forEach {
            controls.addView(it, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            })
        }
        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        logView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        addView(logView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        scanBtn.setOnClickListener {
            if (ElmBtProbe.scanning) ElmBtProbe.stopScan() else ElmBtProbe.scan()
            render()
        }
        pickBtn.setOnClickListener { showDevices() }
        pinBtn.setOnClickListener { showPin() }
        // Пустой адрес — зонд сам подскажет «выберите устройство».
        testBtn.setOnClickListener { ElmBtProbe.testConnect(ElmHub.address() ?: "") }

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
        val addr = ElmHub.address()
        linkView.text = buildString {
            append("Адаптер: ")
            append(if (addr == null) "не выбран" else "${ElmHub.name() ?: "?"} ($addr)")
            ElmHub.method()?.let { append(" · ").append(it) }
            append('\n').append("ELM: ").append(ElmHub.lastStatus)
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
        const val LOG_ROWS = 14
    }
}
