package com.dash.headunit.navi.cluster

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.dash.headunit.settings.AppSettings

/**
 * Маршрут навигатора на приборке — своё окно в виде «Карты».
 *
 * Приборка T18FL3 получает видео со второго дисплея Android (id 1,
 * «Экран HDMI» 1920×720, FLAG_PRESENTATION) и показывает его в виде
 * «Карты», если включить панель навигации через CarLan
 * ([CarLanClient.showNaviArea], 4100/2 DisplayCluster:true + 4100/5) —
 * подтверждено на ГУ 2026-10-26.
 *  - Картинка — окно-оверлей ([ClusterGuidanceView], TYPE_APPLICATION_OVERLAY,
 *    без фокуса и касаний), а не Activity: на Android 9 activity на втором
 *    дисплее забрала бы фокус и паузу у главного экрана.
 *  - Знак лимита рисуем сами; штатный CameraInfo ([ClusterWarnings], 4100/4)
 *    — только «навигация выключена», чтобы приборка не рисовала свою
 *    строку манёвра («0 м Войти»).
 *  - Приборка сама просит навигацию: CarLan 8196/1 {"RequestNaviAreaDisplay":1/0}
 *    ([CarLanClient.subscribe], [meterListener]). SVMapService на 0 всегда
 *    шлёт DisplayCluster:false, а на 1 отвечает только при открытом TurboDog —
 *    за Яндекс отвечаем сами (1 → 2+5, если маршрут/навигатор в блоке/рамка).
 *    Его пересылку desay.broadcast.map.meter.interaction (map_status) тоже
 *    слушаем — только в лог. targetSdk 28 — неявные broadcast в манифесте
 *    не доходят, поэтому всё регистрируем динамически из живущих сервисов
 *    (MediaListenerService, NaviAccessibilityService).
 *
 * При включённой настройке [AppSettings.clusterNavi]: начался маршрут —
 * поднять панель; закончился (через [END_DELAY_MS]) — опустить. Без
 * маршрута панель поднимают и предупреждения навигатора (ограничение,
 * камера ближе 500 м, светофор — [Guidance.freeDrive]).
 *
 * Навигатор виден в блоке дашборда ([naviVisible]) — панель поднята всегда.
 * Яндекс на экране снимает уведомление ведения, а окно навигатора не всегда
 * читается (ГУ 2026-10-28: «Нет информации о карте»), поэтому маршрут тогда
 * не считаем закончившимся, на карточке — последнее известное ведение.
 *
 * Окно, раз появившись, с дисплея 1 не убираем, пока настройка включена:
 * без своего содержимого HDMI-дисплей зеркалит главный экран, и в «Картах»
 * приборки оказывается дашборд. Без маршрута окно просто чёрное.
 *
 * Режим «Яндекс на приборке» ([setYandex], [NaviOnCluster]): на дисплее 1
 * сам навигатор, нашего окна нет (оверлей лёг бы поверх карты), панель
 * «Карты» поднята как обычно.
 *
 * Калибровка ([toggleCalibration], кнопка на экране диагностики) — окно на весь
 * дисплей 1 с сеткой координат и образцом маршрута ([SAMPLE]), панель
 * навигации и знак лимита подняты: видно, какая часть 1920×720 попадает
 * в «Карты» и как смотрится карточка, без поездки и без настройки.
 */
@SuppressLint("StaticFieldLeak") // только applicationContext
object ClusterOverlay {

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var view: ClusterGuidanceView? = null
    private var viewWm: WindowManager? = null
    private var routeActive = false
    private var naviOnScreen = false
    /** Последнее непустое ведение — держим на карточке, пока навигатор на экране. */
    private var lastGuidance: Guidance? = null
    private var grantTried = false

    /** На дисплее приборки сам Яндекс Навигатор — своё окно не ставим. */
    private var yandex = false

    /** Включена калибровочная рамка. */
    var calibration = false
        private set

    private fun status(s: String) = Log.i(TAG, s)

    /** Идемпотентно: можно звать из любого живущего компонента. */
    fun init(context: Context) {
        main.post {
            if (app != null) return@post
            val ctx = context.applicationContext
            app = ctx
            ctx.registerReceiver(meterReceiver, IntentFilter(ACTION_METER))
            CarLanClient.listener = meterListener
            CarLanClient.subscribe(ctx, CarLanClient.TYPE_METER)
            (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .registerDisplayListener(displayListener, main)
            GuidanceHub.addListener(guidanceListener)
            // Сразу занять дисплей 1 чёрным окном — чтобы в «Картах»
            // приборки не было зеркала главного экрана. Панель «Карты»
            // держим поднятой и без маршрута: блок остаётся пустым.
            if (AppSettings.clusterNavi(ctx) && addView(ctx)) naviArea(ctx, true)
        }
    }

    /**
     * Режим «Яндекс на приборке» вкл/выкл (навигатор уже перенесён /
     * возвращён [NaviOnCluster]). Выкл при включённой настройке — снова
     * наше окно, иначе дисплей 1 зеркалил бы дашборд.
     */
    fun setYandex(on: Boolean) {
        main.post {
            yandex = on
            val ctx = app ?: return@post
            if (on) {
                if (!calibration) removeView()
                status("на приборке Яндекс Навигатор")
                naviArea(ctx, true)
            } else {
                status("на приборке снова наше окно")
                if (AppSettings.clusterNavi(ctx) && view == null && addView(ctx)) naviArea(ctx, true)
            }
        }
    }

    /** Дисплей приборки и видимая в «Картах» полоса на нём (для [NaviOnCluster]); null — дисплея нет. */
    fun clusterTarget(context: Context): Pair<Int, Rect>? {
        val ctx = context.applicationContext
        val display = clusterDisplay(ctx) ?: return null
        val size = android.graphics.Point().also {
            @Suppress("DEPRECATION")
            display.getRealSize(it)
        }
        val cw = AppSettings.clusterWidth(ctx).coerceAtMost(size.x)
        val left = (size.x - cw) / 2
        return display.displayId to Rect(left, 0, left + cw, size.y)
    }

    /** Настройку включили: окно на приборку и панель «Карты» — даже без маршрута. */
    fun show() {
        main.post {
            val ctx = app ?: return@post
            if (view == null && !addView(ctx)) return@post
            naviArea(ctx, true)
        }
    }

    /**
     * Вкл/выкл калибровочную рамку (главный поток). Выключили — окно и панель
     * как требуют настройка и маршрут. Возвращает новое состояние.
     */
    fun toggleCalibration(): Boolean {
        val ctx = app ?: return false
        calibration = !calibration
        view?.calibration = calibration
        main.removeCallbacks(calibrationInfo)
        if (calibration) {
            main.post(calibrationInfo)
            status("калибровка: рамка на приборке")
            if (view == null && !addView(ctx)) return true
            naviArea(ctx, true)
        } else {
            status("калибровка выключена")
            if (yandex) removeView()
            if (!AppSettings.clusterNavi(ctx)) {
                removeView()
                naviArea(ctx, false)
            } else {
                // Настройка включена — панель и пустой блок остаются.
                naviArea(ctx, true)
                ClusterWarnings.update(ctx)
            }
        }
        return calibration
    }

    /** Строка состояния в рамке: источники данных, службы, настройка — раз в секунду. */
    private val calibrationInfo = object : Runnable {
        override fun run() {
            val ctx = app ?: return
            if (!calibration) return
            view?.info = listOf(
                GuidanceHub.sourcesInfo(),
                "спец. возможности: ${onOff(ClusterSetup.isA11yEnabled(ctx))}" +
                    " · слушатель уведомлений: ${onOff(ClusterSetup.isListenerEnabled(ctx))}",
                "настройка «маршрут на приборке»: ${onOff(AppSettings.clusterNavi(ctx))}" +
                    " · ${if (GuidanceHub.current != null) "на карточке маршрут" else "на карточке образец"}",
            )
            main.postDelayed(this, 1000)
        }
    }

    private fun onOff(b: Boolean) = if (b) "вкл" else "выкл"

    /** Состояние одной строкой — для диагностики ([com.dash.headunit.diag.ClusterNaviProbe]). */
    fun debugState(): String =
        "яндекс на приборке=$yandex навигатор в блоке=$naviOnScreen маршрут=$routeActive калибровка=$calibration " +
            "окно=${view != null} последнее ведение=${lastGuidance != null} на карточке=${shown() != null}"

    /**
     * Навигатор виден / не виден в блоке дашборда (из MainActivity, можно
     * звать повторно). Виден — панель навигации на приборке поднята.
     */
    fun naviVisible(visible: Boolean) {
        main.post {
            if (naviOnScreen == visible) return@post
            naviOnScreen = visible
            val ctx = app ?: return@post
            if (!AppSettings.clusterNavi(ctx)) return@post
            if (visible) {
                status("навигатор в блоке — держу панель на приборке")
                main.removeCallbacks(routeEnded)
                if (view == null && !addView(ctx)) return@post
                view?.guidance = shown()
                naviArea(ctx, true)
            } else {
                status("навигатор ушёл из блока")
                view?.guidance = shown()
                if (GuidanceHub.current == null) {
                    main.removeCallbacks(routeEnded)
                    main.postDelayed(routeEnded, END_DELAY_MS)
                }
            }
        }
    }

    /**
     * Ведение для приборки: навигатор на экране — последнее известное,
     * в калибровке без маршрута — образец.
     */
    private fun shown(): Guidance? = GuidanceHub.current
        ?: lastGuidance?.takeIf { naviOnScreen }?.frozen()
        ?: SAMPLE.takeIf { calibration }

    /**
     * Замороженное ведение: манёвр и остаток пути держим, а то, что быстро
     * устаревает (камера), — нет. Без маршрута держать нечего.
     */
    private fun Guidance.frozen(): Guidance? =
        takeIf { it.onRoute }?.copy(cameraDistance = null, cameraLimit = null)

    /** Настройку выключили: убрать окно и панель навигации. */
    fun remove() {
        main.post {
            val ctx = app ?: return@post
            removeView()
            naviArea(ctx, false)
        }
    }

    private val guidanceListener = GuidanceHub.Listener { g ->
        if (g != null) lastGuidance = g
        view?.guidance = shown()
        val ctx = app ?: return@Listener
        if (!AppSettings.clusterNavi(ctx)) return@Listener
        if (g != null) {
            main.removeCallbacks(routeEnded)
            ClusterWarnings.update(ctx)
            if (!routeActive) {
                routeActive = true
                status("${if (g.onRoute) "маршрут начался" else "предупреждения без маршрута"} — показываю на приборке")
                if (view == null) addView(ctx)
                naviArea(ctx, true)
            }
        } else if (routeActive && !naviOnScreen) {
            main.removeCallbacks(routeEnded)
            main.postDelayed(routeEnded, END_DELAY_MS)
        }
    }

    private val routeEnded = Runnable {
        if (naviOnScreen) return@Runnable // навигатор в блоке держит панель
        routeActive = false
        lastGuidance = null
        ManeuverGrab.reset()
        view?.guidance = shown()
        val ctx = app ?: return@Runnable
        if (calibration) return@Runnable // рамка держит панель сама
        if (AppSettings.clusterNavi(ctx)) {
            // Блок маршрута остаётся на приборке пустым, панель не опускаем.
            status("маршрут/предупреждения закончились — блок остаётся пустым")
            naviArea(ctx, true)
        } else {
            status("маршрут/предупреждения закончились — опускаю панель")
            naviArea(ctx, false)
        }
    }

    /** Пересылка запроса приборки от SVMapService — только в лог (запрос ловит [meterListener]). */
    private val meterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ex = intent.extras
            val map = ex?.keySet()?.associateWith { k -> try { ex.get(k) } catch (e: Exception) { null } }
                ?: emptyMap()
            status("SVMapService переслал запрос приборки: $map")
        }
    }

    /** Входящие CarLan от приборки (поток CarLan → главный). */
    private val meterListener = CarLanClient.Listener { type, subtype, json ->
        if (type != CarLanClient.TYPE_METER || subtype != CarLanClient.SUB_AREA_REQUEST) return@Listener
        val req = CarLanClient.Json.naviAreaRequest(json) ?: return@Listener
        main.post { onAreaRequest(req) }
    }

    private fun onAreaRequest(req: Int) {
        val ctx = app ?: return
        val state = debugState()
        if (req != 1) {
            // SVMapService сам ответит DisplayCluster:false — пока только смотрим, когда это бывает.
            status("приборка: убрать навигацию ($state)")
            return
        }
        if (!calibration && !AppSettings.clusterNavi(ctx)) {
            status("приборка: показать навигацию — не отвечаю, настройка выключена ($state)")
            return
        }
        status("приборка: показать навигацию — поднимаю панель ($state)")
        if (view == null && !addView(ctx)) return
        naviArea(ctx, true)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            val ctx = app ?: return
            if (view == null && AppSettings.clusterNavi(ctx) && addView(ctx)) naviArea(ctx, true)
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (view?.display?.displayId == displayId) {
                status("дисплей $displayId пропал")
                removeView()
            }
        }

        override fun onDisplayChanged(displayId: Int) {}
    }

    /** Дисплей приборки: первый presentation-дисплей, иначе второй по счёту. */
    private fun clusterDisplay(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
            ?: dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    private fun addView(ctx: Context): Boolean {
        // Яндекс на дисплее 1 сам — окно не нужно, панель поднимать можно.
        if (yandex && !calibration) return true
        val display = clusterDisplay(ctx)
        if (display == null) {
            status("второго дисплея (приборки) нет")
            return false
        }
        if (!Settings.canDrawOverlays(ctx)) {
            if (!grantTried) {
                grantTried = true
                status("нет права оверлея — выдаю через adb")
                Thread {
                    ClusterSetup.grantOverlay(ctx)
                    main.post { if (AppSettings.clusterNavi(ctx) || calibration) show() }
                }.start()
            } else {
                status("нет права оверлея (appops SYSTEM_ALERT_WINDOW)")
            }
            return false
        }
        return try {
            val dctx = ctx.createDisplayContext(display)
            val wm = dctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = ClusterGuidanceView(dctx).apply {
                contentWidth = AppSettings.clusterWidth(ctx)
                guidance = GuidanceHub.current
                calibration = this@ClusterOverlay.calibration
            }
            @Suppress("DEPRECATION")
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE
            ).apply { title = "DashCluster" }
            wm.addView(v, lp)
            view = v
            viewWm = wm
            status("окно на дисплее ${display.displayId} (${display.name})")
            true
        } catch (e: Exception) {
            Log.w(TAG, "addView", e)
            status("окно на приборку не добавилось: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun removeView() {
        val v = view ?: return
        try {
            viewWm?.removeView(v)
        } catch (e: Exception) {
            Log.w(TAG, "removeView", e)
        }
        view = null
        viewWm = null
        status("окно с приборки убрано")
    }

    /** Панель навигации приборки (видео дисплея 1 в «Картах») и сброс её штатной навигации. */
    private fun naviArea(ctx: Context, on: Boolean) {
        CarLanClient.showNaviArea(ctx, on)
        ClusterWarnings.reset()
        ClusterWarnings.update(ctx)
        // CameraInfo сразу после 2+5 приборка теряет (ГУ 2026-10-26: знак
        // появился только со второго 4100/4) — повторить, когда панель поднимется.
        // Шлём «навигация выключена»: иначе висит штатная строка «0 м Войти».
        main.removeCallbacks(warningsRepeat)
        if (on) main.postDelayed(warningsRepeat, CAMERA_REPEAT_MS)
        status("приборка: панель навигации ${if (on) "вкл" else "выкл"}")
    }

    private val warningsRepeat = Runnable {
        val ctx = app ?: return@Runnable
        ClusterWarnings.reset()
        ClusterWarnings.update(ctx)
    }

    /** Образец маршрута для калибровки. */
    val SAMPLE = Guidance(
        maneuver = Maneuver.LEFT,
        distance = "300 м",
        street = "улица Ленина",
        remainingDistance = "12 км",
        remainingTime = "18 мин",
        arrival = "14:35",
        speedLimit = 60,
        cameraDistance = "350 м",
        cameraLimit = 60,
    )

    private const val CAMERA_REPEAT_MS = 1500L
    private const val ACTION_METER = "desay.broadcast.map.meter.interaction"
    private const val END_DELAY_MS = 8_000L
    private const val TAG = "ClusterOverlay"
}
