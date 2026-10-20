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
import com.dash.headunit.car.ActionLearner
import com.dash.headunit.car.ActionLearner.Phase
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard
import com.dash.headunit.ui.DashDialog

/**
 * Виджет «Обучение кнопкам» ([ActionLearner]): говорит, какую кнопку
 * машины нажать, ловит изменившиеся ячейки шины Desay, показывает
 * кандидатов и пробует выполнить действие записью в шину (каждая запись —
 * по подтверждению). Выученное выполняется кнопкой «▶».
 */
class LearnWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private val actionBtn = button("")
    private val instruction = TextView(context)
    /** Итог прошлого запуска (ошибка, отмена) — когда снова ждём «Старт». */
    private val lastStatus = TextView(context)
    private val startBtn = button("● Старт")
    private val doneBtn = button("✓ Готово")
    private val cancelBtn = button("Отмена")
    /** Пропустить кнопку (или «Дальше» после разбора) — следующее действие по списку. */
    private val skipBtn = button("Пропустить ▸")
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val fileView = TextView(context)
    private var selected = ActionLearner.ACTIONS.first()

    /** Что уже нарисовано в списке — перестраиваем только при изменениях. */
    private var shownKey = ""

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
        ActionLearner.action?.let { selected = it }

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = context.getString(R.string.widget_learn)
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        actionBtn.setOnClickListener { if (!ActionLearner.busy) pickAction() }
        header.addView(actionBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        instruction.setTextColor(theme.primaryText)
        instruction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        instruction.setPadding(0, dp(8), 0, dp(8))
        addView(instruction, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        lastStatus.setTextColor(RECORD)
        lastStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        addView(lastStatus, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val buttons = LinearLayout(context).apply { orientation = HORIZONTAL }
        startBtn.setOnClickListener { ActionLearner.start(context, selected); render() }
        doneBtn.setOnClickListener { ActionLearner.finish(); render() }
        cancelBtn.setOnClickListener { ActionLearner.cancel(); render() }
        skipBtn.setOnClickListener {
            // Идущее обучение этой кнопки бросаем; попытку записи — нет.
            if (ActionLearner.phase != Phase.TRYING) {
                if (ActionLearner.busy) ActionLearner.cancel()
                selected = ActionLearner.next(selected)
                render()
            }
        }
        for (b in listOf(startBtn, doneBtn, cancelBtn, skipBtn)) {
            buttons.addView(b, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        fileView.setTextColor(theme.secondaryText)
        fileView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        val scroll = ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(list)
                addView(fileView)
            })
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        render()
    }

    private fun pickAction() {
        val learned = ActionLearner.learned(context)
        val names = ActionLearner.ACTIONS.map { if (it.key in learned) "✓ ${it.title}" else it.title }
        DashDialog.Builder(context)
            .setTitle(R.string.learn_pick)
            .setItems(names.toTypedArray()) { _, which ->
                selected = ActionLearner.ACTIONS[which]
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    private fun render() {
        val phase = ActionLearner.phase
        val busy = ActionLearner.busy
        actionBtn.text = "${selected.title}  ▾"
        actionBtn.alpha = if (busy) 0.5f else 1f
        instruction.text = when {
            busy || phase == Phase.ANALYZED -> ActionLearner.status
            else -> (if (selected.canWrite) "Машина стоит. " else "") +
                "Нажмите «Старт», $FON_HINT, затем: " +
                selected.press.replaceFirstChar { it.lowercase() } + "."
        }
        instruction.setTextColor(if (phase == Phase.RECORDING) RECORD else theme.primaryText)
        if (!selected.canWrite && !busy && phase != Phase.ANALYZED) {
            instruction.append("\nТолько наблюдение: в шину для этой кнопки не пишем.")
        }
        val idleAfterRun = !busy && phase == Phase.IDLE && ActionLearner.action == selected
        lastStatus.text = if (idleAfterRun) "Прошлый запуск: ${ActionLearner.status}" else ""
        lastStatus.visibility = if (idleAfterRun) VISIBLE else GONE
        startBtn.visibility = if (busy) GONE else VISIBLE
        doneBtn.visibility = if (phase == Phase.RECORDING) VISIBLE else GONE
        cancelBtn.visibility = if (busy && phase != Phase.TRYING) VISIBLE else GONE
        skipBtn.visibility = if (phase == Phase.TRYING) GONE else VISIBLE
        skipBtn.text = if (phase == Phase.ANALYZED && ActionLearner.action == selected) "Дальше ▸" else "Пропустить ▸"
        fileView.text = ActionLearner.file?.absolutePath.orEmpty()
        renderList(busy)
    }

    private fun renderList(busy: Boolean) {
        val learned = ActionLearner.learned(context)
        val forAction = ActionLearner.action?.takeIf { it == selected }
        val cands = if (forAction != null) ActionLearner.candidates else emptyList()
        val key = buildString {
            append(selected.key).append(busy).append(learned[selected.key])
            cands.forEach { append(it.cell).append(ActionLearner.tryResults[it.cell]) }
            append(ActionLearner.repeated)
            append(ActionLearner.knownResults[selected.key])
        }
        if (key == shownKey) return
        shownKey = key
        list.removeAllViews()

        selected.known?.let { k ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(6))
            }
            val col = LinearLayout(context).apply { orientation = VERTICAL }
            col.addView(text("Команда из прошивки ГУ: (${k.cell}) = ${k.value}" +
                if (k.sure) "" else "  (гипотеза)"))
            ActionLearner.knownResults[selected.key]?.let { r ->
                col.addView(text(r).apply {
                    setTextColor(if (r.startsWith("СРАБОТАЛО")) theme.success else RECORD)
                })
            }
            row.addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("Попробовать").apply {
                isEnabled = !busy
                alpha = if (busy) 0.5f else 1f
                setOnClickListener { confirmKnown(k) }
            })
            list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        learned[selected.key]?.let { l ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(text("Выучено: (${l.group},${l.id}) = ${l.value}" +
                (l.release?.let { ", отпустить ${it}" } ?: "")), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("▶ Выполнить").apply {
                isEnabled = !busy
                setOnClickListener { confirmExecute() }
            })
            row.addView(button("Забыть").apply {
                setOnClickListener {
                    ActionLearner.forget(context, selected.key)
                    shownKey = ""
                    render()
                }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
            list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            })
        }

        for (c in cands.take(MAX_ROWS)) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            val col = LinearLayout(context).apply { orientation = VERTICAL }
            val mark = if (c.cell in ActionLearner.repeated) "  ×2" else ""
            col.addView(text(ActionLearner.describe(c) + mark).apply {
                setTextColor(if (c.pulse) theme.secondaryText else theme.primaryText)
            })
            ActionLearner.tryResults[c.cell]?.let { r ->
                col.addView(text(r).apply {
                    setTextColor(if (r.startsWith("СРАБОТАЛО")) theme.success else RECORD)
                })
            }
            row.addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            if (selected.canWrite) {
                row.addView(button("Попробовать").apply {
                    isEnabled = !busy
                    alpha = if (busy) 0.5f else 1f
                    setOnClickListener { confirmTry(c) }
                })
            }
            list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        if (cands.size > MAX_ROWS) list.addView(text("…ещё ${cands.size - MAX_ROWS} — в файле"))
    }

    private fun confirmTry(c: ActionLearner.Change) {
        val value = ActionLearner.toInt(if (c.pulse) c.pulseValue ?: c.after else c.after)
        DashDialog.Builder(context)
            .setTitle(R.string.learn_try_title)
            .setMessage(
                "Запишу в шину машины (${c.cell}) = $value" +
                    (if (c.pulse) " и через 0,6 с верну ${ActionLearner.toInt(c.before)}" else "") +
                    ".\n\nСначала верните как было (обратным действием), иначе не понять, сработало ли. " +
                    "Машина стоит, руки и предметы — подальше от стёкол и люка."
            )
            .setPositiveButton(R.string.learn_try_go) { _, _ ->
                ActionLearner.tryCandidate(context, c)
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    private fun confirmKnown(k: ActionLearner.Known) {
        DashDialog.Builder(context)
            .setTitle(selected.title)
            .setMessage(
                "Запишу в шину машины (${k.cell}) = ${k.value} — так это делает штатный исполнитель " +
                    "голосовых команд ГУ" + (if (k.sure) "" else " (значение угадано)") +
                    ".\n\nМашина стоит, руки и предметы — подальше от стёкол и люка."
            )
            .setPositiveButton(R.string.learn_try_go) { _, _ ->
                ActionLearner.tryKnown(context, selected)
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    private fun confirmExecute() {
        DashDialog.Builder(context)
            .setTitle(selected.title)
            .setMessage("Выполнить через шину машины? Руки и предметы — подальше от стёкол и люка.")
            .setPositiveButton(R.string.learn_try_go) { _, _ ->
                ActionLearner.execute(context, selected.key) { ok ->
                    handler.post {
                        Toast.makeText(context, if (ok) "Команда ушла" else "Не выполнено", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    private fun text(s: String) = TextView(context).apply {
        text = s
        setTextColor(theme.primaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    private fun button(text: String) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(theme.primaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = ThemeCard.card(context, theme)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        // Обучение живёт в ActionLearner — дашборд может пересоздаваться.
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val TICK_MS = 300L
        const val MAX_ROWS = 12
        const val FON_HINT = "6 с ничего не трогайте"
        val RECORD = 0xFFFFB300.toInt()
    }
}
