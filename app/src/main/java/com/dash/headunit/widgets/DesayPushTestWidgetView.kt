package com.dash.headunit.widgets

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.DesayPushProbe
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard

/**
 * Виджет «Тест push (Desay)»: проверка [вариант 2] — событийной подписки
 * на шину Desay. Кнопка поднимает/снимает подписки [DesayPushProbe];
 * на экране — что зарегистрировано, сколько событий пришло, что лежит в
 * паре `(a, b)`, какие id встречаются и что успел прочитать поллинг
 * ([CarHub]) в тот же момент.
 *
 * Ничего не сохраняет: живой лог на экране + logcat (тег `DesayPush`).
 */
class DesayPushTestWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private val startBtn = button("")
    private val status = TextView(context)
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        orientation = VERTICAL
        val pad = dp(8)
        setPadding(pad, pad, pad, pad)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = TITLE
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        startBtn.setOnClickListener {
            if (DesayPushProbe.running) DesayPushProbe.stop() else DesayPushProbe.start(context)
            render()
        }
        header.addView(startBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        status.setTextColor(theme.secondaryText)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        val scroll = ScrollView(context).apply { addView(status) }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        render()
    }

    private fun button(text: String) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(theme.primaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = ThemeCard.card(context, theme)
    }

    private fun render() {
        val running = DesayPushProbe.running
        startBtn.text = if (running) "■ Стоп" else "● Подписаться"
        startBtn.setTextColor(if (running) STOP_RED else theme.primaryText)

        val car = CarHub.data
        status.text = buildString {
            append(DesayPushProbe.status).append('\n')
            append("пресет: ").append(DesayPushProbe.preset.label)
            append(" · подписок: ").append(DesayPushProbe.registeredTags.joinToString(", ").ifEmpty { "нет" })
            append('\n')
            append("событий: ").append(DesayPushProbe.notifications)
            append(" · callback→чтение: ")
            append(DesayPushProbe.lastEventLatencyMs.let { if (it < 0) "—" else "$it мс" })
            append('\n')

            append("— значения ячеек —\n")
            for (key in DesayPushProbe.cellKeys) {
                val v = DesayPushProbe.cell(key)
                append("  ").append(key).append(": ").append(DesayPushProbe.cellText(key))
                if (v != null) append("  @").append(ageSec(v.atMs)).append("с")
                append('\n')
            }

            append("— CarHub (поллинг 5 с) —\n")
            append("  4,26=").append(car?.gearRaw ?: "—")
            append("  4,62=").append(car?.rpm ?: "—").append('\n')

            val top = DesayPushProbe.topIds()
            append("— id из callback (b) —\n")
            append("  ").append(top.joinToString(" ") { "${it.first}×${it.second}" }.ifEmpty { "нет событий" }).append('\n')

            val evs = DesayPushProbe.events().takeLast(EVENT_ROWS)
            append("— события —\n")
            if (evs.isEmpty()) {
                append("  нет\n")
            } else {
                for (e in evs) {
                    append("  ").append(hms(e.wallMs)).append(' ')
                        .append('[').append(e.tag).append("] (")
                        .append(e.a).append(", ").append(e.b).append(")\n")
                }
            }
        }
    }

    private fun ageSec(atMs: Long): String =
        if (atMs <= 0L) "?" else ((System.currentTimeMillis() - atMs) / 1000).toString()

    private fun hms(wallMs: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(wallMs))

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        // Подписку не снимаем: зонд живёт в синглтоне, дашборд может пересоздаваться.
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val TITLE = "Тест push (Desay)"
        private const val TICK_MS = 1000L
        private const val EVENT_ROWS = 6
        private val STOP_RED = 0xFFE53935.toInt()
    }
}
