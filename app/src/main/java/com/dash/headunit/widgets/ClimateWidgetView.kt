package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.ClimateHub
import com.dash.headunit.car.DemoData
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard
import com.dash.headunit.ui.DashDialog
import java.util.Locale

/**
 * Виджет «Климат» в виде панели Jarvis: слева блок температуры
 * (− градиентный слайдер +, под ним «за бортом»), затем блок обдува
 * (полоски + «Обдув»), затем круглые тумблеры A/C · SYNC · Рецирк. ·
 * MAX · Лобовое · Сиденья · Режим · Питание.
 *
 * Данные и запись — через [ClimateHub] (Desay SV binder, группа 16).
 * Значения без сервиса рисуются как «—», кнопки остаются живыми.
 */
class ClimateWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private val locale = Locale("ru")
    private val hubListener: () -> Unit = { render() }

    /** Локальное демо-состояние, пока в [ClimateHub] нет данных. */
    private var demoState: ClimateHub.ClimateState? = null

    private fun isDemo(): Boolean = ClimateHub.data?.tempDriverC == null

    private fun currentState(): ClimateHub.ClimateState {
        ClimateHub.data?.let {
            if (it.tempDriverC != null) {
                demoState = null
                return it
            }
        }
        return demoState ?: DemoData.climate().also { demoState = it }
    }

    private fun demoSet(fn: (ClimateHub.ClimateState) -> ClimateHub.ClimateState): Boolean {
        if (!isDemo()) return false
        demoState = fn(currentState())
        render()
        return true
    }

    private val tempValue = TextView(context)
    private lateinit var slider: TempSlider
    private val outsideText = TextView(context)
    private lateinit var fanBars: FanBars

    private lateinit var tAuto: Toggle
    private lateinit var tSync: Toggle
    private lateinit var tAc: Toggle
    private lateinit var tRecirc: Toggle
    private lateinit var tMax: Toggle
    private lateinit var tHeat: Toggle
    private lateinit var tSeats: Toggle
    private lateinit var tMode: Toggle
    private lateinit var tPower: Toggle

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10))

        addView(stripRow(), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        render()
    }

    // ---------- сборка ----------

    private fun stripRow(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(tempGroup())
        addView(divider())
        addView(fanGroup())
        addView(divider())
        addView(toggles())
    }

    private fun tempGroup(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        val minus = stepButton("−") { bumpTemp(-0.5f) }
        val plus = stepButton("+") { bumpTemp(+0.5f) }

        val center = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)

            tempValue.apply {
                setTextColor(theme.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            }
            slider = TempSlider(context, theme) { setTempSlider(it) }
            outsideText.apply {
                setTextColor(theme.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
            }
            addView(tempValue)
            addView(slider, LayoutParams(dp(190), dp(22)).apply { topMargin = dp(4) })
            addView(outsideText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
        }

        addView(minus)
        addView(center)
        addView(plus)
    }

    private fun fanGroup(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(6), 0)

        val minus = stepButton("−") { bumpFan(-1) }
        val plus = stepButton("+") { bumpFan(+1) }

        val center = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)

            fanBars = FanBars(context, theme)
            val label = TextView(context).apply {
                text = context.getString(R.string.cl_fan)
                setTextColor(theme.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
            }
            addView(fanBars, LayoutParams(dp(104), dp(40)))
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
        }

        addView(minus)
        addView(center)
        addView(plus)
    }

    private fun toggles(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, 0, 0)

        tAuto = toggle(R.drawable.ic_auto, getString(R.string.cl_auto)) {
            if (!demoSet { it.copy(auto = !it.auto.orFalse()) }) {
                ClimateHub.setAuto(!ClimateHub.data?.auto.orFalse())
            }
        }
        tSync = toggle(R.drawable.ic_sync, getString(R.string.cl_sync)) {
            if (!demoSet { it.copy(sync = !it.sync.orFalse()) }) {
                ClimateHub.setSync(!ClimateHub.data?.sync.orFalse())
            }
        }
        tAc = toggle(R.drawable.ic_snow, getString(R.string.cl_ac)) {
            if (!demoSet { it.copy(ac = !it.ac.orFalse()) }) {
                ClimateHub.setAc(!ClimateHub.data?.ac.orFalse())
            }
        }
        tRecirc = toggle(R.drawable.ic_recirc, "Рецирк.") {
            if (!demoSet { it.copy(recirc = !it.recirc.orFalse()) }) {
                ClimateHub.setRecirc(!ClimateHub.data?.recirc.orFalse())
            }
        }
        tMax = toggle(R.drawable.ic_defrost_front, getString(R.string.cl_max)) {
            if (!demoSet { it.copy(defrostFront = !it.defrostFront.orFalse()) }) {
                ClimateHub.setDefrostFront(!ClimateHub.data?.defrostFront.orFalse())
            }
        }
        tHeat = toggle(R.drawable.ic_windshield_heat, "Лобовое") {
            if (!demoSet { it.copy(windshieldHeat = !it.windshieldHeat.orFalse()) }) {
                ClimateHub.setWindshieldHeat(!ClimateHub.data?.windshieldHeat.orFalse())
            }
        }
        tSeats = toggle(R.drawable.ic_seat_heat, getString(R.string.cl_seats)) { showSeatsDialog() }
        tMode = toggle(R.drawable.ic_mode_face, getString(R.string.cl_mode)) { cycleMode() }
        tPower = toggle(R.drawable.ic_power, getString(R.string.cl_power)) {
            if (!demoSet { it.copy(power = !it.power.orFalse()) }) {
                ClimateHub.setPower(!ClimateHub.data?.power.orFalse())
            }
        }

        listOf(tAuto, tSync, tAc, tRecirc, tMax, tHeat, tSeats, tMode, tPower).forEach { addView(it) }
    }

    private fun stepButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = ThemeCard.card(context, theme)
            layoutParams = LayoutParams(dp(56), dp(56))
            setOnClickListener { onClick() }
        }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(theme.cardBorder)
        layoutParams = LayoutParams(dp(1), dp(110)).apply { marginStart = dp(14); marginEnd = dp(14) }
    }

    private fun toggle(iconRes: Int, label: String, onClick: () -> Unit): Toggle =
        Toggle(context, theme, iconRes, label, onClick)

    // ---------- действия ----------

    private fun bumpTemp(delta: Float) {
        val cur = currentState().tempDriverC ?: 21f
        val next = (cur + delta).coerceIn(16f, 32f)
        setTemp(next)
    }

    private fun setTempSlider(fraction: Float) {
        val raw = 16f + fraction * 16f
        val snapped = (Math.round(raw * 2f) / 2f).coerceIn(16f, 32f)
        setTemp(snapped)
    }

    private fun setTemp(c: Float) {
        if (!demoSet { it.copy(tempDriverC = c) }) {
            ClimateHub.setTempDriver(c)
        }
        render()
    }

    private fun bumpFan(delta: Int) {
        val cur = currentState().fanSpeed ?: 0
        val next = (cur + delta).coerceIn(0, 7)
        if (!demoSet { it.copy(fanSpeed = next) }) {
            ClimateHub.setFanSpeed(next)
        }
        render()
    }

    private fun cycleMode() {
        val cur = currentState().fanDirection
        val next = when (cur) {
            1 -> 2
            2 -> 5
            else -> 1
        }
        if (!demoSet { it.copy(fanDirection = next) }) {
            ClimateHub.setFanDirection(next)
        }
        render()
    }

    private fun showSeatsDialog() {
        val current = currentState().seatHeatDriver
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        val names = listOf(getString(R.string.cl_level_off), "1", "2", "3")
        var dialog: android.app.Dialog? = null
        names.forEachIndexed { level, name ->
            val active = current == level
            val b = TextView(context).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
                setTextColor(if (active) theme.background else theme.primaryText)
                background = if (active) ThemeCard.accent(context, theme) else ThemeCard.card(context, theme)
                layoutParams = LayoutParams(dp(84), dp(64)).apply { marginEnd = dp(10) }
                setOnClickListener {
                    if (!demoSet { it.copy(seatHeatDriver = level) }) {
                        ClimateHub.setSeatHeatDriver(level)
                    }
                    render()
                    dialog?.dismiss()
                }
            }
            row.addView(b)
        }
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
            addView(TextView(context).apply {
                text = getString(R.string.cl_seat_heat_driver)
                setTextColor(theme.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, 0, 0, dp(10))
            })
            addView(row)
        }
        dialog = DashDialog.Builder(context)
            .setTitle(getString(R.string.cl_seats))
            .setView(box)
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .showFullScreen()
    }

    // ---------- отрисовка ----------

    private fun render() {
        val s = currentState()

        val t = s.tempDriverC
        if (t != null) {
            tempValue.text = formatTemp(t)
            tempValue.setTextColor(theme.primaryText)
            slider.fraction = ((t - 16f) / 16f).coerceIn(0f, 1f)
        } else {
            tempValue.text = "—"
            tempValue.setTextColor(theme.secondaryText)
        }
        slider.invalidate()

        val outside = CarHub.data?.outsideTempC
            ?: if (isDemo()) DemoData.car().outsideTempC else null
        outsideText.text = if (outside == null) {
            "${getString(R.string.cl_outside)} —"
        } else {
            "${getString(R.string.cl_outside)} ${formatTemp(outside)}"
        }

        fanBars.filled = s.fanSpeed ?: 0
        fanBars.invalidate()

        tAuto.setState(s.auto)
        tSync.setState(s.sync)
        tAc.setState(s.ac)
        tRecirc.setState(s.recirc)
        tMax.setState(s.defrostFront)
        tHeat.setState(s.windshieldHeat)
        tSeats.setState(s.seatHeatDriver != null && s.seatHeatDriver > 0)

        val dir = s.fanDirection
        tMode.label = when (dir) {
            1 -> getString(R.string.cl_mode_face)
            2 -> getString(R.string.cl_mode_feet)
            5 -> getString(R.string.cl_mode_windshield)
            else -> getString(R.string.cl_mode)
        }
        tMode.setState(if (dir == null) null else true)

        tPower.setState(s.power)
    }

    private fun formatTemp(c: Float): String =
        if (c % 1f == 0f) "%.0f°".format(locale, c) else "%.1f°".format(locale, c)

    private fun getString(res: Int): String = context.getString(res)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ClimateHub.init(context)
        ClimateHub.addListener(hubListener)
        ClimateHub.ensurePolling()
        CarHub.addListener(hubListener)
    }

    override fun onDetachedFromWindow() {
        ClimateHub.removeListener(hubListener)
        CarHub.removeListener(hubListener)
        super.onDetachedFromWindow()
    }
}

private fun Boolean?.orFalse(): Boolean = this == true

/** Круглый тумблер: иконка + подпись, акцентная заливка при включении. */
private class Toggle(
    context: Context,
    private val theme: DashTheme,
    iconRes: Int,
    initialLabel: String,
    private val onClick: () -> Unit
) : LinearLayout(context) {

    private val icon = ImageView(context)
    private val text = TextView(context)
    private var on: Boolean? = null

    var label: String
        get() = text.text.toString()
        set(value) {
            text.text = value
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        layoutParams = LayoutParams(dp(78), LayoutParams.WRAP_CONTENT)
        setPadding(dp(4), dp(10), dp(4), dp(10))
        background = ThemeCard.card(context, theme)

        icon.setImageResource(iconRes)
        icon.setColorFilter(theme.secondaryText)
        icon.layoutParams = LayoutParams(dp(30), dp(30))
        text.text = initialLabel
        text.setTextColor(theme.secondaryText)
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        text.gravity = Gravity.CENTER
        text.maxLines = 1
        addView(icon)
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

        setOnClickListener {
            onClick()
            ClimateHub.refresh()
        }
    }

    fun setState(state: Boolean?) {
        on = state
        if (state == true) {
            background = ThemeCard.accent(context, theme)
            icon.setColorFilter(theme.background)
            text.setTextColor(theme.background)
        } else {
            background = ThemeCard.card(context, theme)
            icon.setColorFilter(theme.secondaryText)
            text.setTextColor(theme.secondaryText)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
}

/** Градиентный слайдер температуры (синий → красный) с круглым ползунком. */
private class TempSlider(
    context: Context,
    private val theme: DashTheme,
    private val onSet: (Float) -> Unit
) : View(context) {

    var fraction: Float = 0.5f

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.primaryText }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h / 2f
        val th = dp(10f)
        rect.set(0f, cy - th / 2f, w, cy + th / 2f)
        track.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(COLOR_COLD, COLOR_HOT), null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, th, th, track)
        val x = fraction.coerceIn(0f, 1f) * w
        canvas.drawCircle(x, cy, dp(11f), thumb)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                fraction = (event.x / width).coerceIn(0f, 1f)
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onSet(fraction)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
        )

    companion object {
        private val COLOR_COLD = 0xFF3D7BFF.toInt()
        private val COLOR_HOT = 0xFFFF4D4D.toInt()
    }
}

/** Полоски скорости обдува (заполнено акцентом). */
private class FanBars(
    context: Context,
    private val theme: DashTheme
) : View(context) {

    var filled: Int = 0

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val count = 7
        val gap = dp(6f)
        val barW = (w - gap * (count - 1)) / count
        for (i in 0 until count) {
            val left = i * (barW + gap)
            bar.color = if (i < filled) theme.accent else theme.cardBorder
            val barH = h * (0.45f + 0.55f * (i + 1) / count)
            canvas.drawRoundRect(
                left, h - barH, left + barW, h,
                barW / 2f, barW / 2f, bar
            )
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
        )
}
