package com.dash.headunit.widgets

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.car.DesayCarReader
import com.dash.headunit.car.HvacShade
import com.dash.headunit.theme.DashTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Виджет «Подогрев» — как в штатном приложении: сетка зон
 * (стекло | руль / водитель | пассажир / задний левый | задний правый).
 * Включённое подсвечивается жёлтым; у сиденья уровень 1..3 — число
 * горящих волн на иконке.
 *
 * Команды — как в Jarvis (ib/hb → f2.r): пишем в Request-ячейку, через
 * [VERIFY_MS] перечитываем Response-ячейку; не изменилась — пишем запасной
 * вариант. Ячейки — CarSettingID/HvacID из SVCarInfoService (HU-непроверено):
 *
 *   сиденье    запись 0,83 вод. / 0,84 пасс. (запасные 0,135 / 0,136),
 *              задние 0,139 / 0,141; значение = уровень + 1 (выкл → 1);
 *              состояние 0,95 / 0,96 (0..3), задние — те же 139 / 141
 *   стекло     обогрев: запись 16,53, состояние 16,65 (1 = вкл)
 *   руль       запись и состояние 16,70
 *
 * Значение вкл/выкл для стекла и руля в прошивке не найдено: шлём 2/1
 * (как у сидений: 1 = выкл), а если состояние не сдвинулось — 1/2.
 * 16,24 — это НЕ стекло: в Request это скорость вентилятора, в Response —
 * MAX обдув.
 */
class HeatWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    /** Сиденье: ячейки шины + текущий уровень и иконка. */
    private inner class Seat(
        val name: String,
        val write: Int,
        val writeAlt: Int?,
        val read: Int
    ) {
        var level: Int? = null
        /** Незавершённых записей; пока > 0, опрос не перетирает уровень из тапа. */
        var pending = 0
        val layers = seatLayers()
        val icon = ImageView(context).apply { setImageDrawable(LayerDrawable(layers)) }
    }

    /** Вкл/выкл (стекло, руль). */
    private inner class Toggle(val name: String, val write: Int, val read: Int, iconRes: Int) {
        var on: Boolean? = null
        var pending = 0
        val icon = ImageView(context).apply { setImageResource(iconRes) }
    }

    private val seats = listOf(
        Seat("водитель", 83, 135, 95),
        Seat("пассажир", 84, 136, 96),
        Seat("задний левый", 139, null, 139),
        Seat("задний правый", 141, null, 141)
    )
    private val glass = Toggle("стекло", 53, 65, R.drawable.ic_defrost_front)
    private val wheel = Toggle("руль", 70, 70, R.drawable.ic_wheel_heat)

    private var reader: DesayCarReader? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER

        addView(pair(
            control(glass.icon, R.string.heat_glass) { toggle(glass) },
            control(wheel.icon, R.string.heat_wheel) { toggle(wheel) }
        ), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(pair(
            control(seats[0].icon, R.string.heat_driver) { tapSeat(seats[0]) },
            control(seats[1].icon, R.string.heat_pass) { tapSeat(seats[1]) }
        ), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(pair(
            control(seats[2].icon, R.string.heat_rear_left) { tapSeat(seats[2]) },
            control(seats[3].icon, R.string.heat_rear_right) { tapSeat(seats[3]) }
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

    /** Слои иконки сиденья: контур + три волны, красятся по отдельности. */
    private fun seatLayers(): Array<Drawable> = arrayOf(
        R.drawable.ic_seat_heat_base,
        R.drawable.ic_seat_heat_wave1,
        R.drawable.ic_seat_heat_wave2,
        R.drawable.ic_seat_heat_wave3
    ).map { context.getDrawable(it)!!.mutate() }.toTypedArray()

    /** Контур жёлтый при уровне > 0; горят первые [level] волн, остальные тусклые. */
    private fun paintSeat(layers: Array<Drawable>, level: Int) {
        layers[0].setTint(if (level > 0) ON_YELLOW else theme.secondaryText)
        val dim = (theme.secondaryText and 0x00FFFFFF) or (OFF_WAVE_ALPHA shl 24)
        for (i in 1..LEVELS) layers[i].setTint(if (i <= level) ON_YELLOW else dim)
    }

    private fun control(
        icon: ImageView,
        labelRes: Int,
        onClick: () -> Unit
    ): LinearLayout {
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
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

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    private fun render() {
        for (seat in seats) {
            paintSeat(seat.layers, seat.level?.coerceIn(0, LEVELS) ?: 0)
            seat.icon.invalidate()
        }
        for (t in listOf(glass, wheel)) {
            t.icon.setColorFilter(if (t.on == true) ON_YELLOW else theme.secondaryText)
        }
    }

    /** Читатель шины виджета (лениво, как в [refresh]). */
    private fun readerReady(): DesayCarReader? {
        val r = reader ?: DesayCarReader(context.applicationContext).also { reader = it }
        return if (r.isAvailable()) r else null
    }

    /** Тап по сиденью: 0→1→2→3→0. Уровень на иконке меняется сразу. */
    private fun tapSeat(seat: Seat) {
        val next = ((seat.level ?: 0) + 1) % (LEVELS + 1)
        seat.level = next
        seat.pending++
        render()
        HvacShade.prepare(context)
        WRITER.execute {
            readerReady()?.let { r ->
                val value = next + 1
                sendVerified(r, "сиденье ${seat.name} → $next", 0, seat.read, next,
                    0 to seat.write, value, seat.writeAlt?.let { 0 to it }, value)
            }
            post { seat.pending--; refresh() }
        }
    }

    /** Тап по стеклу/рулю: вкл ↔ выкл. */
    private fun toggle(t: Toggle) {
        val next = t.on != true
        t.on = next
        t.pending++
        render()
        HvacShade.prepare(context)
        WRITER.execute {
            readerReady()?.let { r ->
                val (value, altValue) = if (next) 2 to 1 else 1 to 2
                sendVerified(r, "${t.name} → ${if (next) "вкл" else "выкл"}", 16, t.read,
                    if (next) 1 else 0, 16 to t.write, value, 16 to t.write, altValue)
            }
            post { t.pending--; refresh() }
        }
    }

    /**
     * Запись с проверкой (как f2.r в Jarvis): пишем [main]=[value], ждём,
     * перечитываем ([readGroup],[readId]); не изменилось и не равно
     * ожидаемому [expected] — [alt]=[altValue].
     * Вызывать только с потока [WRITER].
     */
    private fun sendVerified(
        r: DesayCarReader,
        what: String,
        readGroup: Int,
        readId: Int,
        expected: Int,
        main: Pair<Int, Int>,
        value: Int,
        alt: Pair<Int, Int>?,
        altValue: Int
    ) {
        val before = runCatching { r.readInt(readGroup, readId) }.getOrNull()
        HvacShade.beforeWrite(context)
        r.write(main.first, main.second, value)
        HvacShade.afterWrite(context)
        SystemClock.sleep(VERIFY_MS)
        val after = runCatching { r.readInt(readGroup, readId) }.getOrNull()
        var log = "$what: ${main.first},${main.second}=$value · " +
            "$readGroup,$readId $before → $after"
        if (alt != null && before != null && after == before && after != expected) {
            HvacShade.beforeWrite(context)
            r.write(alt.first, alt.second, altValue)
            HvacShade.afterWrite(context)
            SystemClock.sleep(VERIFY_MS)
            val again = runCatching { r.readInt(readGroup, readId) }.getOrNull()
            log += " · не изменилось, ${alt.first},${alt.second}=$altValue → $again"
        }
        Log.i(TAG, log)
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
            val seatLevels = withContext(Dispatchers.IO) {
                seats.map { s -> runCatching { r.readInt(0, s.read) }.getOrNull()?.takeIf { it in 0..LEVELS } }
            }
            val toggles = withContext(Dispatchers.IO) {
                listOf(glass, wheel).map { t -> runCatching { r.readInt(16, t.read) }.getOrNull()?.takeIf { it >= 0 }?.let { it == 1 } }
            }
            if (!isAttachedToWindow) return@launch
            var changed = false
            seats.forEachIndexed { i, s ->
                if (s.pending == 0 && seatLevels[i] != s.level) {
                    s.level = seatLevels[i]
                    changed = true
                }
            }
            listOf(glass, wheel).forEachIndexed { i, t ->
                if (t.pending == 0 && toggles[i] != t.on) {
                    t.on = toggles[i]
                    changed = true
                }
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
        private const val TAG = "HeatWidget"
        private const val POLL_MS = 3000L

        /** Пауза между записью и перечитыванием состояния. */
        private const val VERIFY_MS = 1500L

        /** Все записи по очереди: быстрые тапы не перемешивают проверки. */
        private val WRITER = Executors.newSingleThreadExecutor()

        /** Жёлтая подсветка включённого подогрева. */
        private val ON_YELLOW = 0xFFFDD835.toInt()

        /** Непрозрачность негорящих волн сиденья (из 255). */
        private const val OFF_WAVE_ALPHA = 0x55

        /** Ступеней подогрева сиденья (3 волны на иконке). */
        private const val LEVELS = 3
    }
}
