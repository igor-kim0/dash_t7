package com.dash.headunit.screensaver

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.dash.headunit.dashboard.WidgetSettingsStore

/**
 * Заставка дашборда (виджет «Заставка» — только настройки).
 *
 * Нет касаний экрана [Config.timeoutSec] — поверх всего окна кладётся фон
 * заставки ([BACKGROUNDS]: тёмный с часами, камин, северное сияние), яркость окна снижается до [Config.brightness] %. Любое касание
 * убирает заставку (само касание до виджетов не доходит), возвращает
 * яркость, и таймер начинается заново.
 *
 * Яркость — атрибут окна (screenBrightness): без разрешений, действует, пока
 * окно дашборда на экране, и снимается системой, если приложение упадёт.
 * Таймер идёт только между [resume] и [pause].
 */
class Screensaver(
    private val activity: Activity,
    /** Заставка показана/убрана — напр. снять вырез касаний под навигатор. */
    private val onShowingChanged: (Boolean) -> Unit
) {

    data class Config(val timeoutSec: Int, val brightness: Int, val background: String)

    /** null — виджета на дашборде нет, заставка выключена. */
    var config: Config? = null
        set(value) {
            field = value
            if (value == null) hide() else schedule()
        }

    /** Можно ли сейчас показать (не редактирование, нет открытого диалога). */
    var canShow: () -> Boolean = { true }

    var isShowing = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val fire = Runnable { onTimer() }
    private var running = false
    private var lastTouch = SystemClock.uptimeMillis()
    // Касание, разбудившее заставку, — съедаем до отпускания пальца.
    private var swallowing = false
    private var overlay: View? = null
    private var savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    fun resume() {
        running = true
        lastTouch = SystemClock.uptimeMillis()
        schedule()
    }

    fun pause() {
        running = false
        handler.removeCallbacks(fire)
        hide()
    }

    /**
     * Каждое касание окна (из Activity.dispatchTouchEvent). true — касание
     * съедено заставкой, дальше его не передавать.
     */
    fun onTouch(ev: MotionEvent): Boolean {
        lastTouch = SystemClock.uptimeMillis()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swallowing = isShowing
                if (isShowing) hide()
            }
            // Касание мимо окна (навигатор в блоке, FLAG_WATCH_OUTSIDE_TOUCH).
            MotionEvent.ACTION_OUTSIDE -> {
                if (isShowing) hide()
                return false
            }
        }
        val swallow = swallowing
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            swallowing = false
        }
        return swallow
    }

    /** Показать сразу (тап по плитке виджета — посмотреть, как выглядит). */
    fun showNow() {
        val c = config ?: return
        show(c)
    }

    private fun schedule() {
        handler.removeCallbacks(fire)
        val c = config ?: return
        if (!running || isShowing) return
        val left = lastTouch + c.timeoutSec * 1000L - SystemClock.uptimeMillis()
        handler.postDelayed(fire, left.coerceAtLeast(MIN_DELAY_MS))
    }

    private fun onTimer() {
        val c = config ?: return
        if (!running || isShowing) return
        val idle = SystemClock.uptimeMillis() - lastTouch
        if (idle < c.timeoutSec * 1000L) {
            schedule()
            return
        }
        if (!canShow()) {
            // Диалог/редактирование — ждём ещё один полный интервал.
            lastTouch = SystemClock.uptimeMillis()
            schedule()
            return
        }
        show(c)
    }

    private fun show(c: Config) {
        if (isShowing) return
        isShowing = true
        handler.removeCallbacks(fire)
        val decor = activity.window.decorView as ViewGroup
        val view = createView(activity, c.background).apply {
            setBackgroundColor(0xFF000000.toInt())
            alpha = 0f
            // Касания не должны провалиться к виджетам под заставкой.
            isClickable = true
        }
        decor.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        view.animate().alpha(1f).setDuration(FADE_MS).start()
        overlay = view
        val lp = activity.window.attributes
        savedBrightness = lp.screenBrightness
        lp.screenBrightness = (c.brightness / 100f).coerceIn(MIN_BRIGHTNESS, 1f)
        activity.window.attributes = lp
        onShowingChanged(true)
    }

    private fun hide() {
        if (!isShowing) return
        isShowing = false
        overlay?.let {
            it.animate().cancel()
            (it.parent as? ViewGroup)?.removeView(it)
        }
        overlay = null
        val lp = activity.window.attributes
        lp.screenBrightness = savedBrightness
        activity.window.attributes = lp
        onShowingChanged(false)
        lastTouch = SystemClock.uptimeMillis()
        schedule()
    }

    companion object {
        const val OPT_TIMEOUT = "ss_timeout_s"
        const val OPT_BRIGHTNESS = "ss_brightness"
        const val OPT_BACKGROUND = "ss_background"

        /** Варианты задержки, с. */
        val TIMEOUTS = listOf(30, 60, 120, 180, 300, 600, 900, 1800)
        const val DEFAULT_TIMEOUT = 60
        const val DEFAULT_BRIGHTNESS = 20

        /** Фоны заставки: тёмный с часами, камин, северное сияние. */
        const val BG_DARK = "dark"
        const val BG_FIREPLACE = "fireplace"
        const val BG_AURORA = "aurora"
        val BACKGROUNDS = listOf(BG_DARK, BG_FIREPLACE, BG_AURORA)

        private const val FADE_MS = 800L
        private const val MIN_DELAY_MS = 200L
        // 0 у части прошивок гасит подсветку совсем — оставляем минимум.
        private const val MIN_BRIGHTNESS = 0.01f

        fun createView(context: android.content.Context, bg: String): View = when (bg) {
            BG_FIREPLACE -> FireplaceView(context)
            BG_AURORA -> AuroraView(context)
            else -> DarkClockView(context)
        }

        fun config(store: WidgetSettingsStore, widgetId: String): Config = Config(
            timeoutSec = store.getOption(widgetId, OPT_TIMEOUT, DEFAULT_TIMEOUT.toString())
                .toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT,
            brightness = (store.getOption(widgetId, OPT_BRIGHTNESS, DEFAULT_BRIGHTNESS.toString())
                .toIntOrNull() ?: DEFAULT_BRIGHTNESS).coerceIn(0, 100),
            background = store.getOption(widgetId, OPT_BACKGROUND, BG_DARK)
                .takeIf { it in BACKGROUNDS } ?: BG_DARK
        )

        /** «30 с», «1 мин», «15 мин». */
        fun timeoutLabel(sec: Int): String =
            if (sec < 60) "$sec с" else "${sec / 60} мин"
    }
}
