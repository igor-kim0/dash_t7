package com.dash.headunit.widgets

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dash.headunit.R
import com.dash.headunit.car.ActionLearner.Known
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.CarScaling
import com.dash.headunit.car.DesayCarReader
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Виджет «Кнопки»: пульт шины Desay SV — стеклоподъёмники (все 4 стекла),
 * люк, шторка люка.
 *
 * Команды — те же, что инжектит штатный исполнитель голосовых команд ГУ
 * (com.desaysv.vehiclesetting, VrControlMgr): группа 3 = ModuleID.ID_VR,
 * Request 120 — окна (1/2 водитель, 3/4 пассажир, 5/6 зад. левое,
 * 7/8 зад. правое; нечётное — опустить, чётное — поднять), 122 — люк,
 * 124 — шторка (значения 1/2 — гипотеза). Опускание водительского стекла
 * подтверждено обучением (learn/win_drv_down*, 19.10).
 *
 * Состояния (только чтение): 0,100..103 окна (подтверждено: 2 закрыто,
 * 4 открыто, прочие — «в движении»), 0,5+0,6 люк (закрыт 2,1 / открыт 1,12),
 * 0,10 шторка.
 *
 * На ходу (скорость > [MAX_SPEED_KMH]) кнопки отказывают тостом,
 * как в [ActionLearner].
 */
class ButtonsWidgetView(
    context: Context,
    private val theme: DashTheme
) : LinearLayout(context) {

    /** Строка управления: две кнопки Known-команд и ячейки состояния рядом. */
    private class RowDef(
        val label: String,
        val open: Known,
        val close: Known,
        val stateIds: List<Int>
    )

    private val handler = Handler(Looper.getMainLooper())
    /** Статусные TextView по ключу ячейки (группа 0): "100" и т.п. */
    private val stateViews = HashMap<String, TextView>()
    private val logView = TextView(context)
    private val polling = AtomicBoolean(false)
    private var pollReader: DesayCarReader? = null

    private val ticker = object : Runnable {
        override fun run() {
            poll()
            handler.postDelayed(this, POLL_MS)
        }
    }

    init {
        orientation = VERTICAL
        // Всё в 2 раза крупнее обычных виджетов — крупный пульт.
        val pad = dp(16)
        setPadding(pad, pad, pad, pad)

        val header = LinearLayout(context).apply { orientation = HORIZONTAL }
        header.addView(TextView(context).apply {
            text = context.getString(R.string.widget_buttons)
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(context).apply {
            text = "штатные команды (3,120/122/124)"
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(windowRow("Водитель", Known(3, 120, 1, listOf(100)), Known(3, 120, 2, listOf(100)), listOf(100)))
                addView(windowRow("Пассажир", Known(3, 120, 3, listOf(101)), Known(3, 120, 4, listOf(101)), listOf(101)))
                addView(windowRow("Зад. левое", Known(3, 120, 5, listOf(102)), Known(3, 120, 6, listOf(102)), listOf(102)))
                addView(windowRow("Зад. правое", Known(3, 120, 7, listOf(103)), Known(3, 120, 8, listOf(103)), listOf(103)))
                addView(windowRow("Люк (гипотеза)", Known(3, 122, 1, listOf(5, 6), sure = false), Known(3, 122, 2, listOf(5, 6), sure = false), listOf(5, 6)))
                addView(windowRow("Шторка (гипотеза)", Known(3, 124, 1, listOf(10), sure = false), Known(3, 124, 2, listOf(10), sure = false), listOf(10)))
            })
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        logView.setTextColor(theme.secondaryText)
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        addView(logView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        renderStates(emptyMap())
    }

    /** Ряд: имя + статус состояния + две кнопки (открыть/закрыть или ▼/▲). */
    private fun windowRow(label: String, open: Known, close: Known, stateIds: List<Int>): View {
        val isWindow = stateIds.size == 1 && stateIds[0] in 100..103
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(TextView(context).apply {
            text = label
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        })
        col.addView(TextView(context).apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            stateViews[stateIds.joinToString(",")] = this
        })
        row.addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val openLabel = if (isWindow) "▼" else "открыть"
        val closeLabel = if (isWindow) "▲" else "закрыть"
        row.addView(commandButton(openLabel, open), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        row.addView(commandButton(closeLabel, close), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        return row
    }

    private fun commandButton(text: String, known: Known): TextView {
        val b = TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            val hp = dp(20)
            setPadding(hp, dp(16), hp, dp(16))
            background = ThemeCard.card(context, theme)
            setOnClickListener { send(known) }
        }
        return b
    }

    /** Запись Known-команды: сначала запрет на ходу, для «гипотезы» — подтверждение. */
    private fun send(k: Known) {
        val speed = CarHub.data?.speedKmh
        if (speed != null && speed > MAX_SPEED_KMH) {
            Toast.makeText(context, "Машина едет (${speed.toInt()} км/ч) — запрещено", Toast.LENGTH_SHORT).show()
            return
        }
        val go = {
            log("пишу (${k.cell}) = ${k.value}…")
            Thread {
                val d = DesayCarReader(context.applicationContext)
                val ok = d.connect() && d.write(k.group, k.id, k.value)
                handler.post {
                    log(if (ok) "(${k.cell}) = ${k.value}: запись ушла" else "(${k.cell}) = ${k.value}: запись НЕ ушла")
                }
            }.start()
        }
        if (k.sure) go() else {
            com.dash.headunit.ui.DashDialog.Builder(context)
                .setTitle(R.string.buttons_send_title)
                .setMessage(
                    "Запишу в шину (${k.cell}) = ${k.value} — значение угадано, так «должен» делать " +
                        "исполнитель голосовых команд, но не проверено. " +
                        "Руки и предметы — подальше от люка."
                )
                .setPositiveButton(R.string.learn_try_go) { _, _ -> go() }
                .setNegativeButton(R.string.dialog_cancel, null)
                .showFullScreen()
        }
    }

    /** Чтение состояний (группа 0) в фоне; нить-одиночка — пропускаем тик. */
    private fun poll() {
        if (!polling.compareAndSet(false, true)) return
        Thread {
            try {
                val d = pollReader ?: DesayCarReader(context.applicationContext).also { pollReader = it }
                if (!d.connect()) {
                    handler.post { renderStates(emptyMap()) }
                    return@Thread
                }
                val ids = stateViews.keys
                    .flatMap { it.split(",").mapNotNull(String::toIntOrNull) }
                    .distinct()
                val vals = ids.associateWith { id -> d.readRaw(0, id, logErrors = false) }
                handler.post { renderStates(vals) }
            } finally {
                polling.set(false)
            }
        }.start()
    }

    private fun renderStates(vals: Map<Int, IntArray?>) {
        if (vals.isEmpty()) {
            for ((k, v) in stateViews) v.text = cellLabel(k) + ": нет связи"
            return
        }
        for ((k, v) in stateViews) {
            val ids = k.split(",").mapNotNull(String::toIntOrNull)
            v.text = ids.joinToString(" ") { id ->
                val raw = vals[id]?.takeIf { it.isNotEmpty() }?.let { CarScaling.bytesToInt(it) }
                stateText(id, raw)
            }
        }
        // Кнопки приглушаем, если последняя запись не ушла? Нет — только лог.
    }

    private fun cellLabel(k: String): String = k.split(",").joinToString(" + ") { "0,$it" }

    /** Короткий статус ячейки: сырые значения плюс расшифровка там, где известна. */
    private fun stateText(id: Int, raw: Int?): String {
        val v = raw?.let { it and 0xFF } ?: -1
        val s = if (v < 0) "?" else v.toString()
        val hint = when {
            v == 2 && id in 100..103 -> " закр"
            v == 4 && id in 100..103 -> " откр"
            id == 5 && v == 2 -> " закр"
            id == 5 && v == 1 -> " откр"
            id == 6 && v == 1 -> " закр"
            id == 6 && v == 12 -> " откр"
            else -> ""
        }
        return "0,$id=$s$hint"
    }

    private fun log(s: String) {
        logView.text = s
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val POLL_MS = 1000L
        const val MAX_SPEED_KMH = 3f
    }
}
