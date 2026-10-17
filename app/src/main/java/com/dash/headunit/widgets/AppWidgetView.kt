package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.dashboard.WidgetFrame
import com.dash.headunit.dashboard.WidgetSettingsStore
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard

/**
 * Виджет «Навигатор»: в его прямоугольнике виден настоящий навигатор
 * (по умолчанию Яндекс Навигатор), запущенный под дашбордом, — см.
 * [com.dash.headunit.navi.NaviUnderlay]. Сам виджет в рабочем режиме
 * прозрачен, в углу — кнопка «на весь экран».
 *
 * Пока режим недоступен (нет adb-канала, не выбрано приложение,
 * редактирование), виджет — карточка с подсказкой; касание по ней
 * вызывает [onTapIdle] (например, подключение adb-канала).
 *
 * Состоянием управляет MainActivity: [showMessage] / [showActive].
 * Пакет выбирается в настройках виджета (шестерёнка).
 */
class AppWidgetView(
    context: Context,
    private val widgetId: String,
    private val theme: DashTheme = DashTheme.DARK,
    private val settings: WidgetSettingsStore = WidgetSettingsStore(context)
) : FrameLayout(context) {

    var onTapIdle: (() -> Unit)? = null
    var onExpand: (() -> Unit)? = null

    private val message = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(theme.secondaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setOnClickListener { onTapIdle?.invoke() }
    }

    /** «На весь экран» — единственное, что дашборд рисует поверх навигатора. */
    val expandButton: ImageButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_fullscreen)
        scaleType = ImageView.ScaleType.CENTER
        // Над картой — всегда непрозрачная, даже если карточки темы полупрозрачны.
        background = ThemeCard.card(context, theme).apply {
            setColor(theme.cardBackground or 0xFF000000.toInt())
        }
        contentDescription = context.getString(R.string.cd_navi_full)
        setOnClickListener { onExpand?.invoke() }
        visibility = View.GONE
    }

    /** true — виджет прозрачен, под ним навигатор. */
    var active = false
        private set

    init {
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val size = dp(56f)
        addView(
            expandButton,
            LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(8f), dp(8f), 0)
            }
        )
        showMessage(context.getString(R.string.app_none))
    }

    /**
     * Пакет навигатора: выбранный в настройках, а если виджет ещё не
     * настраивали — первый установленный из [NavApps.NAV_APPS]
     * (Яндекс Навигатор в приоритете). "" — явно «не выбрано».
     */
    fun pkg(): String {
        val saved = settings.getOption(widgetId, OPT_PKG, AUTO)
        if (saved != AUTO) return saved
        return NavApps.NAV_APPS.map { it.second }.firstOrNull { installed(it) } ?: ""
    }

    fun appLabel(pkg: String): String {
        return try {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info)?.toString() ?: pkg
        } catch (e: Exception) {
            pkg
        }
    }

    fun installed(pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Карточка с текстом (навигатора в блоке нет). */
    fun showMessage(text: String) {
        active = false
        message.text = text
        message.visibility = View.VISIBLE
        expandButton.visibility = View.GONE
        (parent as? WidgetFrame)?.restoreBackground()
        setBackgroundColor(Color.TRANSPARENT)
    }

    /** Прозрачный блок: виден навигатор под дашбордом. */
    fun showActive() {
        active = true
        message.visibility = View.GONE
        expandButton.visibility = View.VISIBLE
        // Карточку рамки тоже убираем — иначе она закроет карту.
        (parent as? View)?.background = null
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    companion object {
        /** Имя опции пакета в [WidgetSettingsStore]. */
        const val OPT_PKG = "pkg"

        /** Опция ещё не задана — выбрать навигатор автоматически. */
        private const val AUTO = "?auto"
    }
}
