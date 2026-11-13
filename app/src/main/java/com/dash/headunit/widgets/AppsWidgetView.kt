package com.dash.headunit.widgets

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dash.headunit.R
import com.dash.headunit.dashboard.WidgetSettingsStore
import com.dash.headunit.theme.DashTheme
import kotlin.math.min

/**
 * Виджет «Программы»: сетка иконок часто используемых приложений, тап по
 * иконке — запуск программы. Состав и порядок — в настройках виджета
 * ([OPT_APPS]: пакеты через запятую, в порядке показа).
 *
 * Иконки тянутся из установленных приложений: распаковка APK тяжёлая, поэтому
 * читаем их в фоне и подставляем, когда готовы; подписи берём из менеджера
 * пакетов (они готовы сразу).
 */
class AppsWidgetView(
    context: Context,
    private val widgetId: String,
    private val theme: DashTheme = DashTheme.DARK,
    private val settings: WidgetSettingsStore = WidgetSettingsStore(context),
    /** Открыть настройки виджета (пустое состояние). */
    private val onOpenSettings: () -> Unit = {}
) : LinearLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val grid = AppGrid(context)
    private val hint = TextView(context)
    /** Сетка или подсказка — то, что сейчас в плитке. */
    private val body = FrameLayout(context)
    /** Иконки уже прочитанных пакетов — APK второй раз не распаковываем. */
    private val icons = HashMap<String, Drawable>()
    /** Иконки в ячейках: подставить, когда фоновая загрузка кончится. */
    private val iconViews = HashMap<String, ImageView>()

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))

        hint.apply {
            gravity = Gravity.CENTER
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            visibility = View.GONE
            setOnClickListener { onOpenSettings() }
        }
        body.addView(grid, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        body.addView(hint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        render()
    }

    /** Пересобрать содержимое: список программ из настроек виджета. */
    private fun render() {
        // Удалённую программу показывать нечем — пропускаем.
        val pkgs = selected(settings, widgetId).filter { installed(it) }
        grid.removeAllViews()
        iconViews.clear()
        if (pkgs.isEmpty()) {
            grid.visibility = View.GONE
            hint.visibility = View.VISIBLE
            hint.text = context.getString(R.string.apps_empty)
            // Подсказка — по центру плитки, сетка — от верхнего края.
            body.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            return
        }
        grid.visibility = View.VISIBLE
        hint.visibility = View.GONE
        body.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        pkgs.forEach { pkg ->
            val icon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = appLabel(pkg)
            }
            iconViews[pkg] = icon
            grid.addView(cell(pkg, appLabel(pkg), icon))
        }
        loadIcons(pkgs)
    }

    /** Ячейка: иконка программы с подписью. */
    private fun cell(pkg: String, label: String, icon: ImageView): View {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(4), dp(2), dp(2))
            isClickable = true
            setOnClickListener { launch(pkg) }
        }
        box.addView(icon, LayoutParams(dp(ICON_DP), dp(ICON_DP)))
        box.addView(TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        return box
    }

    /** Иконки читаем в фоне: распаковка APK тормозит. */
    private fun loadIcons(pkgs: List<String>) {
        Thread {
            val pm = context.packageManager
            pkgs.forEach { pkg ->
                val drawable = icons[pkg] ?: try {
                    pm.getApplicationIcon(pkg)
                } catch (e: Exception) {
                    null
                }?.also { icons[pkg] = it }
                handler.post {
                    val view = iconViews[pkg] ?: return@post
                    if (drawable != null) view.setImageDrawable(drawable)
                }
            }
        }.start()
    }

    /** Подпись программы по пакету. */
    private fun appLabel(pkg: String): String = try {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(info)?.toString() ?: pkg
    } catch (e: Exception) {
        pkg
    }

    /** Установлена ли программа. */
    private fun installed(pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** Запуск программы. */
    private fun launch(pkg: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Toast.makeText(
                context,
                context.getString(R.string.apps_no_launch, appLabel(pkg)),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, e.message ?: "?", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    /**
     * Сетка ячеек: колонки считаются по ширине, в ячейке — иконка с подписью.
     * Рядов может быть больше, чем влезает в виджет, — лишние уходят за
     * нижний край и обрезаются рамкой.
     */
    private inner class AppGrid(context: Context) : ViewGroup(context) {

        private var columns = 1
        private var cellW = 0
        private var cellH = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val availH = when (MeasureSpec.getMode(heightMeasureSpec)) {
                MeasureSpec.UNSPECIFIED -> Int.MAX_VALUE
                else -> MeasureSpec.getSize(heightMeasureSpec)
            }
            val avail = (width - paddingLeft - paddingRight).coerceAtLeast(1)
            columns = (avail / dp(ITEM_W_DP)).coerceAtLeast(1)
            cellW = avail / columns
            cellH = dp(ICON_DP) + dp(LABEL_H_DP) + dp(4)
            val rows = (childCount + columns - 1) / columns
            // Измеряем всех, даже не влезающих: у неразмеченного ребёнка
            // остаются старые границы, и он рисуется поверх остальных.
            for (i in 0 until childCount) {
                getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY)
                )
            }
            val needed = rows * cellH
            setMeasuredDimension(
                width,
                if (availH == Int.MAX_VALUE) needed else min(needed, availH)
            )
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            // Раскладываем всех: те, кто не влез по высоте, уезжают за нижний
            // край сетки и обрезаются ею же.
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val x = paddingLeft + (i % columns) * cellW
                val y = paddingTop + (i / columns) * cellH
                child.layout(x, y, x + cellW, y + cellH)
            }
        }
    }

    companion object {
        /** Опция виджета: пакеты программ через запятую, в порядке показа. */
        const val OPT_APPS = "apps"

        /** Выбранные программы виджета (пакеты, в порядке показа). */
        fun selected(settings: WidgetSettingsStore, widgetId: String): List<String> =
            settings.getOption(widgetId, OPT_APPS, "")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

        /** Сторона иконки программы, dp. */
        private const val ICON_DP = 66

        /** Кегль подписи под иконкой, sp. */
        private const val LABEL_SP = 14f

        /** Высота блока подписи (две строки), dp. */
        private const val LABEL_H_DP = 40

        /** Ширина ячейки сетки (иконка + подпись), dp. */
        private const val ITEM_W_DP = 96
    }
}
