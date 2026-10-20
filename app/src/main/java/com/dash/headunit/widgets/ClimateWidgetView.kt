package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.ClimateHub
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard
import java.util.Locale

/**
 * Виджет «Климат» в виде панели Jarvis: слева блок температуры
 * (треугольники −/+ вокруг крупной уставки, под ней «за бортом»),
 * затем блок обдува (полоски + «Обдув»), затем круглый тумблер AUTO.
 *
 * Данные и запись — через [ClimateHub] (Desay SV binder, группа 16).
 * Значения без данных рисуются как «—», кнопки остаются живыми.
 */
class ClimateWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private val locale = Locale("ru")
    private val hubListener: () -> Unit = { render() }

    private fun currentState(): ClimateHub.ClimateState = ClimateHub.data ?: ClimateHub.ClimateState.EMPTY

    private val tempValue = TextView(context)
    private val outsideText = TextView(context)
    private lateinit var fanBars: FanBars

    private lateinit var tAuto: TextToggle

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

        val minus = stepButton(pointingDown = true) { bumpTemp(-0.5f) }
        val plus = stepButton(pointingDown = false) { bumpTemp(+0.5f) }

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
            outsideText.apply {
                setTextColor(theme.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
            }
            addView(tempValue)
            addView(outsideText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
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

        val minus = fanStepButton(pointingDown = true) { bumpFan(-1) }
        val plus = fanStepButton(pointingDown = false) { bumpFan(+1) }

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

        tAuto = TextToggle(context, theme, getString(R.string.cl_auto)) {
            ClimateHub.setAuto(!ClimateHub.data?.auto.orFalse())
        }
        addView(tAuto)
    }

    private fun stepButton(pointingDown: Boolean, onClick: () -> Unit): View =
        ArrowButton(context, theme, pointingDown, onClick).apply {
            layoutParams = LayoutParams(dp(56), dp(56))
        }

    private fun fanStepButton(pointingDown: Boolean, onClick: () -> Unit): View =
        FanStepButton(context, theme, pointingDown, onClick).apply {
            layoutParams = LayoutParams(dp(56), dp(56))
        }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(theme.cardBorder)
        layoutParams = LayoutParams(dp(1), dp(110)).apply { marginStart = dp(14); marginEnd = dp(14) }
    }

    // ---------- действия ----------

    private fun bumpTemp(delta: Float) {
        val cur = currentState().tempDriverC ?: 21f
        val next = (cur + delta).coerceIn(16f, 32f)
        setTemp(next)
    }

    private fun setTemp(c: Float) {
        ClimateHub.setTempDriver(c)
        render()
    }

    private fun bumpFan(delta: Int) {
        val cur = currentState().fanSpeed ?: 0
        val next = (cur + delta).coerceIn(0, 7)
        ClimateHub.setFanSpeed(next)
        render()
    }

    // ---------- отрисовка ----------

    private fun render() {
        val s = currentState()

        val t = s.tempDriverC
        if (t != null) {
            tempValue.text = formatTemp(t)
            tempValue.setTextColor(theme.primaryText)
        } else {
            tempValue.text = "—"
            tempValue.setTextColor(theme.secondaryText)
        }

        val outside = CarHub.data?.outsideTempC
        outsideText.text = if (outside == null) {
            "${getString(R.string.cl_outside)} —"
        } else {
            "${getString(R.string.cl_outside)} ${formatTemp(outside)}"
        }

        fanBars.filled = s.fanSpeed ?: 0
        fanBars.invalidate()

        tAuto.setState(s.auto)
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

private val TRIANGLE_BLUE = 0xFF3D7BFF.toInt()
private val TRIANGLE_RED = 0xFFFF4D4D.toInt()

/** Кнопка-тумблер: только надпись, акцентная заливка при включении. */
private class TextToggle(
    context: Context,
    private val theme: DashTheme,
    initialLabel: String,
    onClick: () -> Unit
) : TextView(context) {

    init {
        text = initialLabel
        setTextColor(theme.secondaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        maxLines = 1
        layoutParams = ViewGroup.LayoutParams(dp(78), dp(46))
        background = ThemeCard.card(context, theme)
        setOnClickListener {
            onClick()
            ClimateHub.refresh()
        }
    }

    fun setState(state: Boolean?) {
        if (state == true) {
            background = ThemeCard.accent(context, theme)
            setTextColor(theme.background)
        } else {
            background = ThemeCard.card(context, theme)
            setTextColor(theme.secondaryText)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
}

/** Кнопка шага: синий треугольник вниз (меньше) или красный вверх (больше). */
private class ArrowButton(
    context: Context,
    theme: DashTheme,
    pointingDown: Boolean,
    onClick: () -> Unit
) : View(context) {

    init {
        background = ThemeCard.card(context, theme)
        setOnClickListener { onClick() }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (pointingDown) COLOR_BLUE else COLOR_RED
    }
    private val down = pointingDown

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val halfW = dp(12f)
        val halfH = dp(8f)
        val base = cy + if (down) -halfH else halfH
        val tip = cy + if (down) halfH else -halfH
        canvas.drawPath(
            Path().apply {
                moveTo(cx - halfW, base)
                lineTo(cx + halfW, base)
                lineTo(cx, tip)
                close()
            },
            paint
        )
    }

    override fun performClick(): Boolean = super.performClick()

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
        )

    companion object {
        private val COLOR_BLUE = TRIANGLE_BLUE
        private val COLOR_RED = TRIANGLE_RED
    }
}

/** Кнопка шага обдува: вентилятор (маленький/большой) с треугольником-маркером. */
private class FanStepButton(
    context: Context,
    theme: DashTheme,
    pointingDown: Boolean,
    onClick: () -> Unit
) : View(context) {

    private val down = pointingDown

    private val fanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = theme.secondaryText
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.secondaryText }
    private val triPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (down) TRIANGLE_BLUE else TRIANGLE_RED
    }

    init {
        background = ThemeCard.card(context, theme)
        setOnClickListener { onClick() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val cyIcon = cy - dp(5f)
        val bladeLen = if (down) dp(9f) else dp(13f)
        drawFan(canvas, cx, cyIcon, bladeLen)

        val triBase = cyIcon + bladeLen + dp(4f)
        drawTriangle(canvas, cx, triBase, dp(5f), dp(4f), pointingDown = down)
    }

    private fun drawFan(canvas: Canvas, cx: Float, cy: Float, bladeLen: Float) {
        for (i in 0 until 3) {
            val a = Math.toRadians((i * 120).toDouble())
            val dx = Math.sin(a).toFloat()
            val dy = -Math.cos(a).toFloat()
            val px = -dy
            val py = dx
            val w = bladeLen * 0.5f
            val mid = bladeLen * 0.55f
            val tipX = cx + dx * bladeLen
            val tipY = cy + dy * bladeLen
            canvas.drawPath(
                Path().apply {
                    moveTo(cx, cy)
                    quadTo(cx + dx * mid + px * w, cy + dy * mid + py * w, tipX, tipY)
                    quadTo(cx + dx * mid - px * w, cy + dy * mid - py * w, cx, cy)
                    close()
                },
                fanPaint
            )
        }
        canvas.drawCircle(cx, cy, dp(2f), hubPaint)
    }

    private fun drawTriangle(
        canvas: Canvas,
        cx: Float,
        baseY: Float,
        halfW: Float,
        halfH: Float,
        pointingDown: Boolean
    ) {
        val base = if (pointingDown) baseY - halfH else baseY + halfH
        val tip = if (pointingDown) baseY + halfH else baseY - halfH
        canvas.drawPath(
            Path().apply {
                moveTo(cx - halfW, base)
                lineTo(cx + halfW, base)
                lineTo(cx, tip)
                close()
            },
            triPaint
        )
    }

    override fun performClick(): Boolean = super.performClick()

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
        )
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
