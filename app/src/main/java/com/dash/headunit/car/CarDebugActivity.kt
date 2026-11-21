package com.dash.headunit.car

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран диагностики (кнопка в колонке дашборда). Сейчас на нём только
 * лог борткомпьютера ([TripLog]): поездки, моточасы, жизнь процесса —
 * разбор «иногда не считается» — и проба вида приборки
 * ([com.dash.headunit.diag.ClusterViewTest]: «Карты» → AR → «Карты»).
 * Прежние пробы (шины, яркость, радио) убраны — их классы в `diag/` и история в git.
 */
class CarDebugActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarHub.init(this)
        CarHub.ensurePolling()
        TripLog.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Лог поездок и моточасов"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnToggle = Button(this)
        val btnClear = Button(this).apply { text = "Очистить" }
        buttons.addView(btnToggle)
        buttons.addView(btnClear)
        root.addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        val info = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextIsSelectable(true)
        }
        root.addView(info)
        val logView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(logView)

        root.addView(TextView(this).apply {
            text = "Вид приборки"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, dp(24), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "Рамка калибровки, затем область навигации 1 («Карты») → 2 (AR) → 1, " +
                "по 15 с. На каждом шаге сфотографируйте приборку. " +
                "Отчёт — files/cluster/view_*.txt."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        root.addView(Button(this).apply {
            text = "Проверить вид приборки"
            setOnClickListener { com.dash.headunit.diag.ClusterViewTest.start(this@CarDebugActivity) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(android.widget.ScrollView(this).apply { addView(root) })

        fun toggleLabel() {
            btnToggle.text = if (TripLog.enabled) "Лог поездок: выключить" else "Лог поездок: включить"
        }
        toggleLabel()
        val refresh: () -> Unit = {
            scope.launch {
                val (sum, lines) = withContext(Dispatchers.IO) {
                    TripLog.summary(this@CarDebugActivity) to TripLog.tail(this@CarDebugActivity, TAIL_LINES)
                }
                info.text = (if (TripLog.enabled) "Пишется. " else "Выключен. ") + sum
                logView.text = lines.asReversed().joinToString("\n")
            }
        }
        btnToggle.setOnClickListener {
            val on = !TripLog.enabled
            scope.launch {
                withContext(Dispatchers.IO) { TripLog.setEnabled(this@CarDebugActivity, on) }
                toggleLabel()
                refresh()
            }
        }
        btnClear.setOnClickListener {
            TripLog.clear(this)
            scope.launch { delay(200); refresh() }
        }

        scope.launch {
            while (true) {
                refresh()
                delay(2000)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()

    private companion object {
        const val TAIL_LINES = 150
    }
}
