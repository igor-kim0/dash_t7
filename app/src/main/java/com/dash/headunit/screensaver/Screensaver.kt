package com.dash.headunit.screensaver

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.dash.headunit.car.HuBrightness
import com.dash.headunit.dashboard.WidgetSettingsStore

/**
 * Заставка дашборда (виджет «Заставка» — только настройки).
 *
 * Нет касаний экрана [Config.timeoutSec] — поверх всего окна кладётся фон
 * заставки ([BACKGROUNDS]: тёмный с часами, камин, северное сияние), подсветка ГУ снижается до уровня [Config.level] (1..10). Любое касание
 * убирает заставку (само касание до виджетов не доходит), возвращает
 * яркость, и таймер начинается заново. С [Config.rotate] фоны сменяют друг
 * друга по кругу каждые [Config.rotateSec] секунд.
 *
 * Яркость — подсветка ГУ через Desay ([HuBrightness.dim]/[HuBrightness.undim],
 * как штатный ползунок; исходные значения — в prefs до возврата, MainActivity
 * возвращает их на onResume, если процесс падал). Нет Desay (эмулятор) —
 * атрибут окна screenBrightness.
 * Таймер идёт только между [resume] и [pause].
 */
class Screensaver(
    private val activity: Activity,
    /** Заставка показана/убрана — напр. снять вырез касаний под навигатор. */
    private val onShowingChanged: (Boolean) -> Unit
) {

    data class Config(
        val timeoutSec: Int,
        /** Подсветка под заставкой, уровень 1..10 (как штатный ползунок ГУ). */
        val level: Int,
        val background: String,
        /** Менять фоны по кругу, пока заставка на экране. */
        val rotate: Boolean = false,
        /** Сколько секунд показывать каждый фон при смене по кругу. */
        val rotateSec: Int = DEFAULT_ROTATE_SEC,
        /** Кнопка на плитке виджета: false — заставка не включается. */
        val enabled: Boolean = true
    )

    /** null — виджета на дашборде нет или заставка выключена кнопкой. */
    var config: Config? = null
        set(value) {
            field = value
            if (value == null) {
                hide()
            } else {
                schedule()
                // Настройки могли поменять интервал/выключить смену на ходу.
                if (isShowing) scheduleRotate(value)
            }
        }

    /** Можно ли сейчас показать (не редактирование, нет открытого диалога). */
    var canShow: () -> Boolean = { true }

    var isShowing = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val fire = Runnable { onTimer() }
    private val rotate = Runnable { onRotate() }
    private var running = false
    private var lastTouch = SystemClock.uptimeMillis()
    // Касание, разбудившее заставку, — съедаем до отпускания пальца.
    private var swallowing = false
    private var overlay: FrameLayout? = null
    // Индекс текущего фона в [BACKGROUNDS] — растёт при смене по кругу.
    private var bgIndex = 0
    private var savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var windowDimmed = false

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
        val container = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            // Касания не должны провалиться к виджетам под заставкой.
            isClickable = true
        }
        bgIndex = BACKGROUNDS.indexOf(c.background).coerceAtLeast(0)
        val view = createView(activity, BACKGROUNDS[bgIndex]).apply {
            alpha = 0f
            isClickable = true
        }
        container.addView(
            view,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        decor.addView(
            container,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        view.animate().alpha(1f).setDuration(FADE_MS).start()
        overlay = container
        scheduleRotate(c)
        savedBrightness = activity.window.attributes.screenBrightness
        windowDimmed = false
        HuBrightness.dim(activity, c.level) { ok ->
            // Нет пути через Desay — гасим хотя бы окно (эмулятор, другие ГУ).
            if (!ok && isShowing) {
                val lp = activity.window.attributes
                lp.screenBrightness = (c.level.toFloat() / HuBrightness.MAX_LEVEL).coerceIn(MIN_BRIGHTNESS, 1f)
                activity.window.attributes = lp
                windowDimmed = true
            }
        }
        onShowingChanged(true)
    }

    private fun hide() {
        if (!isShowing) return
        isShowing = false
        handler.removeCallbacks(rotate)
        overlay?.let {
            for (i in 0 until it.childCount) it.getChildAt(i).animate().cancel()
            it.animate().cancel()
            (it.parent as? ViewGroup)?.removeView(it)
        }
        overlay = null
        HuBrightness.undim(activity)
        if (windowDimmed) {
            windowDimmed = false
            val lp = activity.window.attributes
            lp.screenBrightness = savedBrightness
            activity.window.attributes = lp
        }
        onShowingChanged(false)
        lastTouch = SystemClock.uptimeMillis()
        schedule()
    }

    /** Запланировать смену фона через [Config.rotateSec] секунд. */
    private fun scheduleRotate(c: Config) {
        handler.removeCallbacks(rotate)
        if (!isShowing || !c.rotate) return
        handler.postDelayed(rotate, c.rotateSec * 1000L)
    }

    private fun onRotate() {
        val c = config ?: return
        if (!running || !isShowing) return
        val container = overlay ?: return
        bgIndex = (bgIndex + 1) % BACKGROUNDS.size
        val old: View? = if (container.childCount > 0) {
            container.getChildAt(container.childCount - 1)
        } else null
        val next = createView(activity, BACKGROUNDS[bgIndex]).apply { alpha = 0f }
        container.addView(
            next,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        next.animate().alpha(1f).setDuration(FADE_MS).withEndAction {
            old?.let { container.removeView(it) }
        }.start()
        scheduleRotate(c)
    }

    companion object {
        const val OPT_TIMEOUT = "ss_timeout_s"
        /** Старая настройка (яркость окна, %) — только для переноса в [OPT_LEVEL]. */
        const val OPT_BRIGHTNESS = "ss_brightness"
        /** Уровень подсветки ГУ под заставкой, 1..10. */
        const val OPT_LEVEL = "ss_level"
        const val OPT_BACKGROUND = "ss_background"
        const val OPT_ROTATE = "ss_rotate"
        const val OPT_ROTATE_SEC = "ss_rotate_s"
        const val OPT_ENABLED = "ss_enabled"

        /** Варианты задержки, с. */
        val TIMEOUTS = listOf(30, 60, 120, 180, 300, 600, 900, 1800)
        const val DEFAULT_TIMEOUT = 60
        const val DEFAULT_LEVEL = 2

        /** Через сколько секунд менять фон при смене по кругу. */
        val ROTATE_INTERVALS = listOf(30, 60, 180, 300, 600)
        const val DEFAULT_ROTATE_SEC = 60

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
            level = levelOf(
                store.getOption(widgetId, OPT_LEVEL, ""),
                store.getOption(widgetId, OPT_BRIGHTNESS, "")
            ),
            background = store.getOption(widgetId, OPT_BACKGROUND, BG_DARK)
                .takeIf { it in BACKGROUNDS } ?: BG_DARK,
            rotate = store.getOption(widgetId, OPT_ROTATE, "0") != "0",
            rotateSec = store.getOption(widgetId, OPT_ROTATE_SEC, DEFAULT_ROTATE_SEC.toString())
                .toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_ROTATE_SEC,
            enabled = store.getOption(widgetId, OPT_ENABLED, "1") != "0"
        )

        /**
         * Уровень 1..10 из [OPT_LEVEL]; нет его — перенос старых процентов
         * яркости окна ([OPT_BRIGHTNESS]): 20 % → 2.
         */
        fun levelOf(level: String, oldPercent: String): Int {
            level.toIntOrNull()?.let { return HuBrightness.clamp(it) }
            val pct = oldPercent.toIntOrNull() ?: return DEFAULT_LEVEL
            return HuBrightness.clamp(Math.round(pct / 10f))
        }

        /** «30 с», «1 мин», «15 мин». */
        fun timeoutLabel(sec: Int): String =
            if (sec < 60) "$sec с" else "${sec / 60} мин"
    }
}
