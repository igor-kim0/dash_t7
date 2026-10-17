package com.dash.headunit.widgets

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.car.ClusterRecorder
import com.dash.headunit.car.ProbeSession
import com.dash.headunit.ui.DashDialog
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard

/**
 * Виджет «Test claude»: пульт сессии поиска ([ProbeSession]).
 *
 * «Начать запись» — пишется всё, что ГУ знает о машине (шина Desay,
 * VHAL, приборка). Кнопки величин — метка «что сейчас показывает
 * машина»: водитель смотрит на приборку/ГУ и вводит число. По меткам
 * потом ищется ячейка, которая меняется вместе с показанием.
 * Метка без запущенной записи сама запускает запись.
 */
class ClaudeTestWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private val recBtn = button("")
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        recBtn.setOnClickListener {
            if (ProbeSession.running) ProbeSession.stop() else ProbeSession.start(context)
            render()
        }
        header.addView(recBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Величины — сеткой по 4 в ряд.
        TARGETS.chunked(COLS).forEach { row ->
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            row.forEach { t ->
                line.addView(button(t.label).apply {
                    setOnClickListener { askMark(t) }
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            // Неполный ряд — пустышки, чтобы кнопки не растягивались.
            repeat(COLS - row.size) {
                line.addView(TextView(context), LayoutParams(0, 1, 1f))
            }
            addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        status.setTextColor(theme.secondaryText)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        val scroll = android.widget.ScrollView(context).apply { addView(status) }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        render()
    }

    private fun button(text: String) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(theme.primaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = ThemeCard.card(context, theme)
    }

    /** Диалог «что показывает машина» для величины [t]. */
    private fun askMark(t: Target) {
        val input = EditText(context).apply {
            hint = t.hint
            inputType = if (t.numeric) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            // Стандартный цифровой ввод не пускает запятую — а её наберут.
            if (t.numeric) keyListener = DigitsKeyListener.getInstance("0123456789.,-")
            setSingleLine()
        }
        val box = LinearLayout(context).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        DashDialog.Builder(context)
            .setTitle(t.question)
            .setView(box)
            .setPositiveButton(MARK) { _, _ ->
                if (!ProbeSession.running) ProbeSession.start(context)
                ProbeSession.mark(t.key, input.text.toString().trim().replace(',', '.'))
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
        input.requestFocus()
    }

    private fun render() {
        val running = ProbeSession.running
        if (running) {
            val s = ProbeSession.elapsedSec
            recBtn.text = "■ СТОП  %d:%02d".format(s / 60, s % 60)
            recBtn.setTextColor(REC_RED)
        } else {
            recBtn.text = "● Начать запись"
            recBtn.setTextColor(theme.primaryText)
        }
        status.text = buildString {
            append("Шина/VHAL: ").append(ProbeSession.status).append('\n')
            append("Приборка: ").append(ClusterRecorder.status)
            ProbeSession.dir?.let { append('\n').append(it.absolutePath) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        // Запись не останавливаем: она живёт в ProbeSession, дашборд может пересоздаваться.
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** Что ищем: [key] — имя в метке, [question] — заголовок диалога. */
    private class Target(
        val label: String,
        val key: String,
        val question: String,
        val hint: String,
        val numeric: Boolean = true
    )

    companion object {
        private const val TITLE = "Test claude"
        private const val MARK = "Отметить"
        private const val COLS = 4
        private const val TICK_MS = 1000L
        private val REC_RED = 0xFFE53935.toInt()

        private val TARGETS = listOf(
            Target("ОЖ", "ОЖ", "Температура ОЖ на приборке, °C", "например 87"),
            Target("Мгн. расход", "мгн_расход", "Мгновенный расход на приборке", "л/100 км или л/ч, например 7.4"),
            Target("Пробег", "пробег_поездки", "Пробег после запуска, км", "например 12.6"),
            Target("Расход", "расход_поездки", "Средний расход после запуска, л/100 км", "например 8.1"),
            Target("Ср. скорость", "ср_скорость", "Средняя скорость после запуска, км/ч", "например 46"),
            Target("Шины", "шины", "Давление в шинах ПЛ ПП ЗЛ ЗП (и t°, если есть)", "2.3 2.3 2.4 2.4", numeric = false),
            Target("За бортом", "за_бортом", "Температура за бортом, °C", "например 12.5"),
            Target("Событие", "событие", "Что произошло", "пуск / сброс поездки / заправка…", numeric = false)
        )
    }
}
