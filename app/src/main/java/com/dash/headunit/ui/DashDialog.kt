package com.dash.headunit.ui

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard
import com.dash.headunit.theme.ThemeStore

/**
 * Диалоги в стиле дашборда вместо системного AlertDialog: карточка в цветах
 * текущей темы, крупные пункты и кнопки под палец. API — подмножество
 * AlertDialog.Builder, которым пользуется приложение.
 *
 * Показывается по центру всего экрана, а не рабочей области: при сжатой
 * рабочей области (старый режим навигатора через `wm overscan`) обычный
 * диалог оказался бы внутри блока «Навигатор». FLAG_LAYOUT_IN_OVERSCAN
 * учитывается только вместе с IN_SCREEN | INSET_DECOR; без immersive-флагов
 * WM обрезает поверхность до стабильной области.
 */
class DashDialog private constructor() {

    class Builder(private val context: Context) {

        private val theme: DashTheme = ThemeStore(ThemeStore.PREFS, context).current()

        private var title: CharSequence? = null
        private var message: CharSequence? = null
        private var view: View? = null
        private var items: Array<out CharSequence>? = null
        private var itemsListener: ((DialogInterface, Int) -> Unit)? = null
        private var choiceMode = CHOICE_NONE
        private var singleChecked = -1
        private var multiChecked: BooleanArray? = null
        private var multiListener: ((DialogInterface, Int, Boolean) -> Unit)? = null
        private val buttons = arrayOfNulls<Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>>(3)

        fun setTitle(res: Int) = apply { title = context.getString(res) }
        fun setTitle(text: CharSequence?) = apply { title = text }
        fun setMessage(res: Int) = apply { message = context.getString(res) }
        fun setMessage(text: CharSequence?) = apply { message = text }
        fun setView(v: View) = apply { view = v }

        /** Простой список: выбор пункта сразу закрывает диалог. */
        fun setItems(list: Array<out CharSequence>, listener: ((DialogInterface, Int) -> Unit)?) = apply {
            items = list
            itemsListener = listener
            choiceMode = CHOICE_NONE
        }

        fun setSingleChoiceItems(
            list: Array<out CharSequence>,
            checked: Int,
            listener: ((DialogInterface, Int) -> Unit)?
        ) = apply {
            items = list
            singleChecked = checked
            itemsListener = listener
            choiceMode = CHOICE_SINGLE
        }

        fun setMultiChoiceItems(
            list: Array<out CharSequence>,
            checked: BooleanArray?,
            listener: ((DialogInterface, Int, Boolean) -> Unit)?
        ) = apply {
            items = list
            multiChecked = checked ?: BooleanArray(list.size)
            multiListener = listener
            choiceMode = CHOICE_MULTI
        }

        fun setPositiveButton(res: Int, l: ((DialogInterface, Int) -> Unit)?) = setPositiveButton(context.getString(res), l)
        fun setPositiveButton(text: CharSequence, l: ((DialogInterface, Int) -> Unit)?) =
            apply { buttons[POSITIVE] = text to l }

        fun setNegativeButton(res: Int, l: ((DialogInterface, Int) -> Unit)?) = setNegativeButton(context.getString(res), l)
        fun setNegativeButton(text: CharSequence, l: ((DialogInterface, Int) -> Unit)?) =
            apply { buttons[NEGATIVE] = text to l }

        fun setNeutralButton(res: Int, l: ((DialogInterface, Int) -> Unit)?) = setNeutralButton(context.getString(res), l)
        fun setNeutralButton(text: CharSequence, l: ((DialogInterface, Int) -> Unit)?) =
            apply { buttons[NEUTRAL] = text to l }

        fun showFullScreen(): Dialog {
            val dialog = Dialog(context)
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            dialog.setContentView(buildContent(dialog))
            dialog.window?.run {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setDimAmount(DIM)
                val metrics = context.resources.displayMetrics
                setLayout(
                    minOf(dp(WIDTH_DP), (metrics.widthPixels * 0.9f).toInt()),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setGravity(Gravity.CENTER)
                addFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN
                )
                @Suppress("DEPRECATION")
                decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
            }
            // Окно, получившее фокус, заново показывает системные панели. Без
            // фокуса на время show() оно наследует immersive-режим дашборда,
            // фокус (клавиатура для полей ввода) возвращаем сразу после.
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            dialog.show()
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            return dialog
        }

        // ---------- раскладка ----------

        private fun buildContent(dialog: Dialog): View {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    // Фон карточки бывает полупрозрачным (тёмно-синяя тема) —
                    // диалог должен быть плотным.
                    setColor(ColorUtils.compositeColors(theme.cardBackground, theme.background))
                    setStroke(dp(1), theme.cardBorder)
                }
                setPadding(0, dp(20), 0, dp(20))
            }
            title?.let {
                card.addView(TextView(context).apply {
                    text = it
                    textSize = 22f
                    setTextColor(theme.primaryText)
                    setPadding(dp(28), 0, dp(28), dp(12))
                })
            }

            // Середина (сообщение / список / своё содержимое) прокручивается,
            // заголовок и кнопки всегда на месте.
            val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            message?.let {
                body.addView(TextView(context).apply {
                    text = it
                    textSize = 18f
                    setLineSpacing(0f, 1.15f)
                    setTextColor(theme.secondaryText)
                    setTextIsSelectable(false)
                    setPadding(dp(28), dp(4), dp(28), dp(8))
                })
            }
            items?.let { addItems(body, it, dialog) }
            view?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                styleTree(it)
                body.addView(it)
            }
            val scroll = MaxHeightScrollView(context, maxHeight()).apply {
                isVerticalScrollBarEnabled = true
                addView(body)
            }
            // Длинный список выбора — сразу к отмеченному пункту.
            if (choiceMode == CHOICE_SINGLE && singleChecked > 0) {
                scroll.post {
                    body.getChildAt(singleChecked + (if (message != null) 1 else 0))?.let { row ->
                        scroll.scrollTo(0, row.top - (scroll.height - row.height) / 2)
                    }
                }
            }
            card.addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            buttonRow(dialog)?.let { card.addView(it) }
            return card
        }

        private fun addItems(body: LinearLayout, list: Array<out CharSequence>, dialog: Dialog) {
            val radios = mutableListOf<RadioButton>()
            list.forEachIndexed { index, label ->
                val row: TextView = when (choiceMode) {
                    CHOICE_SINGLE -> RadioButton(context).apply {
                        isChecked = index == singleChecked
                        radios.add(this)
                        setOnClickListener {
                            radios.forEachIndexed { i, r -> r.isChecked = i == index }
                            itemsListener?.invoke(dialog, index)
                        }
                    }
                    CHOICE_MULTI -> CheckBox(context).apply {
                        isChecked = multiChecked!![index]
                        setOnCheckedChangeListener { _, on ->
                            multiChecked!![index] = on
                            multiListener?.invoke(dialog, index, on)
                        }
                    }
                    else -> TextView(context).apply {
                        setOnClickListener {
                            itemsListener?.invoke(dialog, index)
                            dialog.dismiss()
                        }
                    }
                }
                row.apply {
                    text = label
                    textSize = 18f
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(56)
                    setTextColor(theme.primaryText)
                    background = pressable()
                    if (this is CompoundButton) {
                        buttonTintList = choiceTint()
                        setPadding(dp(16), 0, dp(28), 0)
                    } else {
                        setPadding(dp(28), 0, dp(28), 0)
                    }
                }
                body.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = if (row is CompoundButton) dp(12) else 0 })
            }
        }

        private fun buttonRow(dialog: Dialog): View? {
            if (buttons.all { it == null }) return null
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(16), dp(24), 0)
            }
            fun add(which: Int, accent: Boolean) {
                val (text, listener) = buttons[which] ?: return
                row.addView(TextView(context).apply {
                    this.text = text
                    textSize = 18f
                    gravity = Gravity.CENTER
                    minWidth = dp(120)
                    setPadding(dp(24), 0, dp(24), 0)
                    if (accent) {
                        setTextColor(theme.background)
                        background = ThemeCard.accent(context, theme)
                    } else {
                        setTextColor(theme.primaryText)
                        background = ThemeCard.card(context, theme)
                    }
                    setOnClickListener {
                        listener?.invoke(dialog, BUTTON_IDS[which])
                        dialog.dismiss()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply {
                    marginStart = if (row.childCount > 0) dp(12) else 0
                })
            }
            add(NEUTRAL, accent = false)
            row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            add(NEGATIVE, accent = false)
            add(POSITIVE, accent = true)
            return row
        }

        /**
         * Своё содержимое диалогов (настройки виджетов) собрано из обычных
         * виджетов — перекрасить их под тему.
         */
        private fun styleTree(v: View) {
            when (v) {
                is CompoundButton -> {
                    v.buttonTintList = choiceTint()
                    v.setTextColor(theme.primaryText)
                }
                is EditText -> {
                    v.backgroundTintList = ColorStateList.valueOf(theme.accent)
                    v.setTextColor(theme.primaryText)
                    v.setHintTextColor(theme.secondaryText)
                    v.textSize = 18f
                }
                is Button -> {
                    v.background = ThemeCard.card(context, theme)
                    v.setTextColor(ColorStateList(
                        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                        intArrayOf(ColorUtils.setAlphaComponent(theme.secondaryText, 0x66), theme.primaryText)
                    ))
                    v.textSize = 20f
                    v.stateListAnimator = null
                    v.minWidth = dp(56)
                    v.minimumWidth = dp(56)
                    (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                    } ?: run {
                        v.layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                            setMargins(dp(4), dp(4), dp(4), dp(4))
                        }
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) styleTree(v.getChildAt(i))
        }

        private fun choiceTint() = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(theme.accent, theme.secondaryText)
        )

        /** Подсветка нажатия пункта списка. */
        private fun pressable() = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(theme.accent, 0x40)),
            null,
            ColorDrawable(Color.WHITE)
        )

        private fun maxHeight(): Int {
            val h = context.resources.displayMetrics.heightPixels
            // Заголовок и кнопки — около 170dp.
            return (h * 0.9f).toInt() - dp(170)
        }

        private fun dp(v: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
        ).toInt()
    }

    /** ScrollView, который растёт по содержимому, но не выше [maxHeight]. */
    private class MaxHeightScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
            )
        }
    }

    private companion object {
        const val CHOICE_NONE = 0
        const val CHOICE_SINGLE = 1
        const val CHOICE_MULTI = 2
        const val POSITIVE = 0
        const val NEGATIVE = 1
        const val NEUTRAL = 2
        val BUTTON_IDS = intArrayOf(
            DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL
        )
        const val WIDTH_DP = 640
        const val DIM = 0.6f
    }
}
