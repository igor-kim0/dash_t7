package com.dash.headunit.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.dash.headunit.theme.DashTheme

/**
 * Содержимое «современного» диалога настроек: секции-карточки, строки
 * с иконкой в цветной плашке, заголовком и подписью, справа — переключатель,
 * степпер или сегментированный выбор. Собирается из простых View (не
 * CompoundButton/Button/EditText), поэтому [DashDialog] его не перекрашивает.
 *
 * Значения не применяются сами: обработчики получают новое значение,
 * сохранение — забота вызывающего (по «Готово»).
 */
class SettingsList(private val context: Context, private val theme: DashTheme) {

    /** Колонки секций рядом — экран ГУ широкий и низкий. */
    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(12), 0, dp(12), dp(4))
    }

    private var col: LinearLayout = newColumn()
    private var group: LinearLayout? = null

    /** Следующие секции — в новой колонке справа. */
    fun column() = apply { col = newColumn() }

    private fun newColumn(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
    }.also {
        view.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** Новая секция: подпись капсом над карточкой. */
    fun section(title: CharSequence) = apply {
        col.addView(TextView(context).apply {
            text = title.toString().uppercase()
            textSize = 13f
            letterSpacing = 0.08f
            setTextColor(theme.accent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(if (col.childCount == 0) 4 else 18), dp(8), dp(8))
        })
        group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(groupColor())
            }
            clipToOutline = true
        }.also {
            col.addView(it, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    /** Строка-переключатель: тап по всей строке меняет значение. */
    fun switch(
        icon: Int,
        title: CharSequence,
        subtitle: CharSequence?,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) = apply {
        val sw = DashSwitch(context, theme).apply { setChecked(checked, animate = false) }
        val row = row(icon, title, subtitle, sw)
        row.setOnClickListener {
            sw.setChecked(!sw.isOn, animate = true)
            onChange(sw.isOn)
        }
    }

    /** Строка со степпером «− значение +» (долгое нажатие — повтор). */
    fun stepper(
        icon: Int,
        title: CharSequence,
        subtitle: CharSequence?,
        value: Int,
        range: IntRange,
        format: (Int) -> String,
        onChange: (Int) -> Unit
    ) = apply {
        var current = value.coerceIn(range)
        val label = TextView(context).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            minWidth = dp(72)
            setTextColor(theme.primaryText)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        lateinit var minus: TextView
        lateinit var plus: TextView
        fun refresh() {
            label.text = format(current)
            minus.isEnabled = current > range.first
            plus.isEnabled = current < range.last
            minus.alpha = if (minus.isEnabled) 1f else 0.35f
            plus.alpha = if (plus.isEnabled) 1f else 0.35f
        }
        fun step(delta: Int): Boolean {
            val next = (current + delta).coerceIn(range)
            if (next == current) return false
            current = next
            refresh()
            onChange(current)
            return true
        }
        minus = roundButton("−") { step(-1) }
        plus = roundButton("+") { step(1) }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(minus, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(label)
            addView(plus, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        refresh()
        row(icon, title, subtitle, box)
    }

    /** Строка с сегментированным выбором под заголовком. */
    fun segmented(
        icon: Int,
        title: CharSequence,
        subtitle: CharSequence?,
        options: List<CharSequence>,
        selected: Int,
        onChange: (Int) -> Unit
    ) = apply {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(ColorUtils.compositeColors(
                    ColorUtils.setAlphaComponent(theme.background, 0xB0), groupColor()
                ))
            }
        }
        val cells = options.mapIndexed { i, text ->
            TextView(context).apply {
                this.text = text
                textSize = 16f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { select(bar, i); onChange(i) }
            }.also {
                bar.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f))
            }
        }
        bar.tag = cells
        select(bar, selected)
        val r = row(icon, title, subtitle, null)
        compact(r)
        group!!.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(16), 0, dp(16), dp(14)) })
    }

    /**
     * Строка с ползунком под заголовком; справа — текущее значение.
     * [step] — шаг значения (ползунок прилипает к нему).
     */
    fun slider(
        icon: Int,
        title: CharSequence,
        subtitle: CharSequence?,
        value: Int,
        range: IntRange,
        step: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit
    ) = apply {
        val label = TextView(context).apply {
            textSize = 18f
            gravity = Gravity.END
            minWidth = dp(64)
            setTextColor(theme.accent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = format(value)
        }
        val r = row(icon, title, subtitle, label)
        compact(r)
        val bar = DashSlider(context, theme, range, step, value) {
            label.text = format(it)
            onChange(it)
        }
        group!!.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
        ).apply { setMargins(dp(16), 0, dp(16), dp(12)) })
    }

    /**
     * Строка-переход: справа текущее значение и шеврон, тап — [onClick]
     * (открыть вложенный выбор).
     */
    fun nav(
        icon: Int,
        title: CharSequence,
        subtitle: CharSequence?,
        value: CharSequence?,
        onClick: () -> Unit
    ) = apply {
        val trailing = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            value?.let {
                addView(TextView(context).apply {
                    text = it
                    textSize = 16f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = dp(260)
                    setTextColor(theme.accent)
                })
            }
            addView(ImageView(context).apply {
                setImageResource(com.dash.headunit.R.drawable.ic_chevron)
                imageTintList = ColorStateList.valueOf(theme.secondaryText)
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(6) })
        }
        row(icon, title, subtitle, trailing).setOnClickListener { onClick() }
    }

    /**
     * Список выбора одного из вариантов (длинный: приложения и т.п.):
     * отмеченный — акцентом и галочкой. [icon] = 0 — строки без иконок.
     */
    fun choices(
        options: List<CharSequence>,
        selected: Int,
        icon: Int = 0,
        onChange: (Int) -> Unit
    ) = apply {
        val checks = ArrayList<ImageView>()
        val titles = ArrayList<TextView>()
        fun mark(sel: Int) {
            checks.forEachIndexed { i, c -> c.visibility = if (i == sel) View.VISIBLE else View.INVISIBLE }
            titles.forEachIndexed { i, t -> t.setTextColor(if (i == sel) theme.accent else theme.primaryText) }
        }
        options.forEachIndexed { i, text ->
            val check = ImageView(context).apply {
                setImageResource(com.dash.headunit.R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(theme.accent)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            }
            checks += check
            val r = row(icon, text, null, check, minHeight = 56)
            titles += r.titleView()
            r.setOnClickListener { mark(i); onChange(i) }
        }
        mark(selected)
    }

    /**
     * Состав и порядок: у каждой строки переключатель «показывать» и
     * кнопки ↑/↓. [order] и [checked] меняются на месте.
     */
    fun <K> reorder(
        order: MutableList<K>,
        checked: MutableSet<K>,
        label: (K) -> CharSequence,
        icon: (K) -> Int = { 0 }
    ) = apply {
        val g = group ?: error("section() before rows")
        val start = g.childCount
        fun rebuild() {
            while (g.childCount > start) g.removeViewAt(start)
            order.forEachIndexed { index, key ->
                val sw = DashSwitch(context, theme).apply { setChecked(key in checked, animate = false) }
                val up = arrowButton(com.dash.headunit.R.drawable.ic_arrow_up, index > 0) {
                    order[index] = order[index - 1].also { order[index - 1] = order[index] }
                    rebuild()
                }
                val down = arrowButton(com.dash.headunit.R.drawable.ic_arrow_down, index < order.size - 1) {
                    order[index] = order[index + 1].also { order[index + 1] = order[index] }
                    rebuild()
                }
                val trailing = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(up, LinearLayout.LayoutParams(dp(44), dp(44)))
                    addView(down, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
                    addView(sw, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(14) })
                }
                val r = row(icon(key), label(key), null, trailing, minHeight = 60, target = g)
                r.setOnClickListener {
                    sw.setChecked(!sw.isOn, animate = true)
                    if (sw.isOn) checked.add(key) else checked.remove(key)
                }
            }
        }
        rebuild()
    }

    /** Пояснение мелким текстом под секцией. */
    fun hint(text: CharSequence) = apply {
        col.addView(TextView(context).apply {
            this.text = text
            textSize = 13f
            setLineSpacing(0f, 1.1f)
            setTextColor(theme.secondaryText)
            setPadding(dp(8), dp(8), dp(8), 0)
        })
    }

    private fun arrowButton(icon: Int, enabled: Boolean, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(theme.primaryText)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(theme.accent, 0x55)),
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ColorUtils.setAlphaComponent(theme.primaryText, 0x1A))
            },
            null
        )
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.25f
        if (enabled) setOnClickListener { onClick() }
    }

    private fun LinearLayout.titleView(): TextView =
        ((getChildAt(if (getChildAt(0) is ImageView) 1 else 0) as LinearLayout).getChildAt(0)) as TextView

    // ---------- строка ----------

    private fun row(
        icon: Int,
        title: CharSequence,
        subtitle: CharSequence?,
        trailing: View?,
        minHeight: Int = 72,
        target: LinearLayout? = null
    ): LinearLayout {
        // [target] — своя секция при перестройке ([reorder]): к этому
        // моменту [group] может указывать уже на следующую секцию.
        val g = target ?: group ?: error("section() before rows")
        if (g.childCount > 0) {
            g.addView(View(context).apply { setBackgroundColor(dividerColor()) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    marginStart = if (icon != 0) dp(72) else dp(16)
                })
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(minHeight)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(theme.accent, 0x33)),
                null, ColorDrawable(android.graphics.Color.WHITE)
            )
            isClickable = true
        }
        if (icon != 0) row.addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(theme.accent)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(ColorUtils.setAlphaComponent(theme.accent, 0x2E))
            }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(if (icon != 0) dp(14) else dp(4), 0, dp(12), 0)
        }
        texts.addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTextColor(theme.primaryText)
        })
        subtitle?.let {
            texts.addView(TextView(context).apply {
                text = it
                textSize = 14f
                setLineSpacing(0f, 1.1f)
                setTextColor(theme.secondaryText)
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let { row.addView(it) }
        g.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return row
    }

    /** Строка-заголовок для контрола под ней: не нажимается, ниже обычной. */
    private fun compact(r: LinearLayout) {
        r.isClickable = false
        r.background = null
        r.minimumHeight = dp(60)
        r.setPadding(dp(16), dp(8), dp(16), dp(6))
    }

    private fun select(bar: LinearLayout, index: Int) {
        @Suppress("UNCHECKED_CAST")
        val cells = bar.tag as List<TextView>
        cells.forEachIndexed { i, c ->
            val on = i == index
            c.setTextColor(if (on) theme.background else theme.secondaryText)
            c.typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            c.background = if (on) GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(theme.accent)
            } else null
        }
    }

    /** Круглая кнопка степпера; удержание повторяет шаг. */
    private fun roundButton(text: String, step: () -> Boolean): TextView = TextView(context).apply {
        this.text = text
        textSize = 24f
        gravity = Gravity.CENTER
        setTextColor(theme.primaryText)
        background = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(theme.accent, 0x55)),
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ColorUtils.setAlphaComponent(theme.primaryText, 0x1A))
            },
            null
        )
        val handler = Handler(Looper.getMainLooper())
        var repeat: Runnable? = null
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!v.isEnabled) return@setOnTouchListener false
                    v.isPressed = true
                    step()
                    repeat = object : Runnable {
                        override fun run() {
                            if (step()) handler.postDelayed(this, REPEAT_MS)
                        }
                    }.also { handler.postDelayed(it, REPEAT_DELAY_MS) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    repeat?.let { handler.removeCallbacks(it) }
                    repeat = null
                    true
                }
                else -> true
            }
        }
    }

    /** Карточка секции чуть светлее фона диалога. */
    private fun groupColor(): Int = ColorUtils.compositeColors(
        ColorUtils.setAlphaComponent(theme.primaryText, 0x0D),
        ColorUtils.compositeColors(theme.cardBackground, theme.background)
    )

    private fun dividerColor(): Int = ColorUtils.setAlphaComponent(theme.primaryText, 0x14)

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
    ).toInt()

    /** Переключатель в духе Material 3, нарисованный вручную. */
    private class DashSwitch(context: Context, private val theme: DashTheme) : View(context) {
        var isOn = false
            private set
        private var pos = 0f
        private var anim: ValueAnimator? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val w = dp(56f)
        private val h = dp(32f)

        init {
            isClickable = false
            isFocusable = false
        }

        fun setChecked(on: Boolean, animate: Boolean) {
            isOn = on
            anim?.cancel()
            val target = if (on) 1f else 0f
            if (!animate) {
                pos = target
                invalidate()
                return
            }
            anim = ValueAnimator.ofFloat(pos, target).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(w.toInt(), h.toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val offTrack = ColorUtils.setAlphaComponent(theme.primaryText, 0x26)
            rect.set(0f, 0f, w, h)
            paint.style = Paint.Style.FILL
            paint.color = ColorUtils.blendARGB(offTrack, theme.accent, pos)
            canvas.drawRoundRect(rect, h / 2, h / 2, paint)
            if (pos < 1f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2f)
                paint.color = ColorUtils.setAlphaComponent(theme.secondaryText, ((1f - pos) * 0x99).toInt())
                rect.inset(dp(1f), dp(1f))
                canvas.drawRoundRect(rect, h / 2, h / 2, paint)
            }
            // Ползунок растёт при включении (16 → 24dp), как в M3.
            val r = dp(8f) + dp(4f) * pos
            val cx = h / 2 + (w - h) * pos
            paint.style = Paint.Style.FILL
            paint.color = ColorUtils.blendARGB(theme.secondaryText, theme.background, pos)
            canvas.drawCircle(cx, h / 2, r, paint)
        }

        private fun dp(v: Float): Float = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
        )
    }

    /** Ползунок: толстая дорожка, заполненная акцентом, круглый бегунок. */
    private class DashSlider(
        context: Context,
        private val theme: DashTheme,
        private val range: IntRange,
        private val step: Int,
        start: Int,
        private val onChange: (Int) -> Unit
    ) : View(context) {
        private var value = start.coerceIn(range)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val trackH = dp(10f)
        private val thumbR = dp(12f)
        private var dragging = false

        private fun fraction(): Float =
            if (range.last == range.first) 0f
            else (value - range.first).toFloat() / (range.last - range.first)

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    update(e.x)
                }
                MotionEvent.ACTION_MOVE -> update(e.x)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                }
            }
            return true
        }

        private fun update(x: Float) {
            val left = thumbR
            val right = width - thumbR
            val f = ((x - left) / (right - left)).coerceIn(0f, 1f)
            val raw = range.first + f * (range.last - range.first)
            val snapped = (Math.round(raw / step) * step).coerceIn(range)
            if (snapped != value) {
                value = snapped
                onChange(value)
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cy = height / 2f
            val left = thumbR
            val right = width - thumbR
            val x = left + (right - left) * fraction()
            paint.style = Paint.Style.FILL
            paint.color = ColorUtils.setAlphaComponent(theme.primaryText, 0x26)
            rect.set(left - trackH / 2, cy - trackH / 2, right + trackH / 2, cy + trackH / 2)
            canvas.drawRoundRect(rect, trackH / 2, trackH / 2, paint)
            paint.color = theme.accent
            rect.set(left - trackH / 2, cy - trackH / 2, x, cy + trackH / 2)
            canvas.drawRoundRect(rect, trackH / 2, trackH / 2, paint)
            if (dragging) {
                paint.color = ColorUtils.setAlphaComponent(theme.accent, 0x40)
                canvas.drawCircle(x, cy, thumbR * 1.7f, paint)
            }
            paint.color = theme.accent
            canvas.drawCircle(x, cy, thumbR, paint)
            paint.color = theme.background
            canvas.drawCircle(x, cy, thumbR * 0.4f, paint)
        }

        private fun dp(v: Float): Float = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
        )
    }

    private companion object {
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_MS = 70L
    }
}
