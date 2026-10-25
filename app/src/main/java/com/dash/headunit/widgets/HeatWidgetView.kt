package com.dash.headunit.widgets

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dash.headunit.R
import com.dash.headunit.car.DesayCarReader
import com.dash.headunit.theme.DashTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Виджет «Подогрев» — как в штатном приложении: сетка зон
 * (стекло | руль / водитель | пассажир / задний левый | задний правый).
 * Включённое подсвечивается жёлтым.
 *
 * Проверено шиной: сиденье водителя (0,95: 0→2→0) и лобовое (16,24).
 * Остальные адреса НЕ найдены — кнопки гаснут с подсказкой
 * (нужен помеченный REC с вкл/выкл каждой зоны).
 */
class HeatWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private var seatLevel: Int? = null
    private var defrost: Boolean? = null

    private val seatIcon = ImageView(context)
    private val glassIcon = ImageView(context)
    private val seatBars = mutableListOf<View>()

    private var reader: DesayCarReader? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER

        seatIcon.setImageResource(R.drawable.ic_seat_heat)
        glassIcon.setImageResource(R.drawable.ic_defrost_front)
        addView(pair(
            control(glassIcon, R.string.heat_glass, ::toggleGlass),
            control(newIcon(R.drawable.ic_wheel_heat), R.string.heat_wheel, ::wheelUnknown)
        ), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // Сиденье водителя — единственное с известным адресом (0,95).
        // Остальные сиденья показывают 0 рисок (адреса неизвестны).
        addView(pair(
            control(seatIcon, R.string.heat_driver, ::toggleSeat, withBars = true, barsOut = seatBars),
            control(newIcon(R.drawable.ic_seat_heat), R.string.heat_pass, ::zoneUnknown, withBars = true)
        ), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(pair(
            control(newIcon(R.drawable.ic_seat_heat), R.string.heat_rear_left, ::zoneUnknown, withBars = true),
            control(newIcon(R.drawable.ic_seat_heat), R.string.heat_rear_right, ::zoneUnknown, withBars = true)
        ), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        render()
    }

    /** Пара кнопок в ряд. */
    private fun pair(left: LinearLayout, right: LinearLayout): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(left, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(right, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun newIcon(res: Int): ImageView = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(theme.secondaryText)
        alpha = 0.5f
    }

    private fun unknownToast() {
        Toast.makeText(context, R.string.heat_zone_unknown, Toast.LENGTH_SHORT).show()
    }

    private fun wheelUnknown() {
        Toast.makeText(context, R.string.heat_wheel_unknown, Toast.LENGTH_SHORT).show()
    }

    private fun zoneUnknown() = unknownToast()

    private fun control(
        icon: ImageView,
        labelRes: Int,
        onClick: () -> Unit,
        withBars: Boolean = false,
        barsOut: MutableList<View>? = null
    ): LinearLayout {
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        if (withBars) {
            val bars = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            repeat(LEVELS) {
                val bar = View(context)
                val w = dp(18)
                val h = dp(8)
                bar.layoutParams = LayoutParams(w, h).apply {
                    leftMargin = dp(3)
                    rightMargin = dp(3)
                    bottomMargin = dp(6)
                }
                bars.addView(bar)
                barsOut?.add(bar)
            }
            col.addView(bars)
        }
        val size = dp(48)
        col.addView(icon, LayoutParams(size, size))
        col.addView(TextView(context).apply {
            text = context.getString(labelRes)
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        })
        col.isClickable = true
        col.isFocusable = true
        col.setOnClickListener { onClick() }
        return col
    }

    /** Заливка рисок уровня: первые [level] жёлтые, остальные — контурные. */
    private fun paintBars(bars: List<View>, level: Int) {
        bars.forEachIndexed { i, bar ->
            bar.background = roundRect(if (i < level) ON_YELLOW else theme.cardBorder)
        }
    }

    private fun roundRect(color: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(4).toFloat()
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    private fun render() {
        val level = seatLevel?.coerceIn(0, LEVELS) ?: 0
        seatIcon.setColorFilter(if (level > 0) ON_YELLOW else theme.secondaryText)
        paintBars(seatBars, level)
        glassIcon.setColorFilter(if (defrost == true) ON_YELLOW else theme.secondaryText)
    }

    /** Читатель шины виджета (лениво, как в [refresh]). */
    private fun readerReady(): DesayCarReader? {
        val r = reader ?: DesayCarReader(context.applicationContext).also { reader = it }
        return if (r.isAvailable()) r else null
    }

    /** Тап по сиденью: 0→1→2→3→0. Дисплей синхронизируется опросом шины. */
    private fun toggleSeat() {
        val next = ((seatLevel ?: 0) + 1) % (LEVELS + 1)
        Thread {
            val r = readerReady() ?: return@Thread
            runCatching { r.write(0, 95, next) }
            post { refresh() }
        }.start()
    }

    /** Лобовое: 16,24 вкл/выкл; при включении — режим 5 и A/C (сценарий ГУ). */
    private fun toggleGlass() {
        val next = defrost != true
        Thread {
            val r = readerReady() ?: return@Thread
            runCatching {
                r.write(16, 24, if (next) 1 else 0)
                if (next) {
                    r.write(16, 6, 5)
                    r.write(16, 1, 1)
                }
            }
            post { refresh() }
        }.start()
    }

    private fun ensurePolling() {
        if (pollJob == null) {
            pollJob = scope.launch {
                while (true) {
                    refresh()
                    delay(POLL_MS)
                }
            }
        }
    }

    private fun refresh() {
        val app = context.applicationContext
        val r = reader ?: DesayCarReader(app).also { reader = it }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { r.isAvailable() }
            if (!ok) return@launch
            val seat = withContext(Dispatchers.IO) {
                runCatching { r.readInt(0, 95) }.getOrNull()?.takeIf { it >= 0 }
            }
            val glass = withContext(Dispatchers.IO) {
                runCatching { r.readInt(16, 24) }.getOrNull()?.takeIf { it >= 0 }?.let { it != 0 }
            }
            if (!isAttachedToWindow) return@launch
            var changed = false
            if (seat != seatLevel) {
                seatLevel = seat
                changed = true
            }
            if (glass != defrost) {
                defrost = glass
                changed = true
            }
            if (changed) render()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        pollJob = null
        ensurePolling()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        pollJob = null
        reader = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val POLL_MS = 3000L

        /** Жёлтая подсветка включённого подогрева. */
        private val ON_YELLOW = 0xFFFDD835.toInt()

        /** Ступеней подогрева сиденья (как в штатном: 3 риски). */
        private const val LEVELS = 3
    }
}
