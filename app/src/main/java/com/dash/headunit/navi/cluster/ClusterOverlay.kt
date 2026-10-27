package com.dash.headunit.navi.cluster

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.dash.headunit.settings.AppSettings

/**
 * Маршрут навигатора на приборке. Два режима ([AppSettings.clusterMode]):
 *
 * Родной интерфейс ([AppSettings.CLUSTER_NATIVE], по умолчанию) — приборка
 * рисует сама в верхней строке поверх любого вида: манёвр, расстояние,
 * улица ([ClusterTbt], 202/201/203 через mapservice) и знак ограничения
 * скорости ([ClusterWarnings], CarLan CameraInfo 4100/4). Окна на дисплее 1
 * нет, вид приборки не переключаем. Больше родной интерфейс не умеет:
 * полосы, остаток пути, расстояние до камеры в модели данных QNX есть, но
 * cluster.kzb их не рисует (разбор 2026-10-26).
 *
 * Своё окно ([AppSettings.CLUSTER_OVERLAY]) — описано ниже.
 *
 * Приборка T18FL3 получает видео со второго дисплея Android (id 1,
 * «Экран HDMI» 1920×720, FLAG_PRESENTATION) и показывает его в режиме
 * «навигация». Как у Jarvis:
 *  - родные подсказки приборки (TBT) — [ClusterTbt], по умолчанию
 *    выключены (adb NAVI_CLUSTER cmd tbt);
 *  - картинку кладём на этот дисплей — у нас это окно-оверлей
 *    (TYPE_APPLICATION_OVERLAY, без фокуса и касаний), а не Activity:
 *    на Android 9 activity на втором дисплее забрала бы фокус и паузу
 *    у главного экрана;
 *  - приборку переключаем в навигацию широковещанием для
 *    com.desaysv.mapservice: turbodog.navigation.system.message CODE
 *    207; плюс, как Jarvis,
 *    AUTONAVI_STANDARD_BROADCAST_SEND KEY_TYPE=10019 (на этой ГУ не слушается);
 *  - видео дисплея 1 приборка показывает только после DisplayCluster:true —
 *    шлём сами через CarLan ([CarLanClient], 4100 подтипы 2+5), там же
 *    лимит/камеры ([ClusterWarnings], 4100/4); на ГУ подтверждено 2026-10-26;
 *  - приборка сама просит навигацию (водитель листает виды):
 *    desay.broadcast.map.meter.interaction с RequestNaviAreaDisplay=1/0.
 *    targetSdk 28 — неявные broadcast в манифесте не доходят, поэтому
 *    приёмник регистрируем динамически из живущих сервисов
 *    (MediaListenerService, NaviAccessibilityService).
 *
 * Поведение своего окна при включённой настройке [AppSettings.clusterNavi]:
 * начался маршрут — показать и переключить приборку; закончился
 * (через [END_DELAY_MS]) — вернуть приборку; запросы приборки
 * выполняются всегда.
 *
 * Окно, раз появившись, с дисплея 1 не убираем (пока настройка включена
 * или после демо): без своего содержимого HDMI-дисплей зеркалит главный
 * экран, и в разделе «Карты» приборки оказывается дашборд. Без маршрута
 * окно просто чёрное. Убираем только при выключении настройки ([remove]).
 */
@SuppressLint("StaticFieldLeak") // только applicationContext
object ClusterOverlay {

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var view: ClusterGuidanceView? = null
    private var viewWm: WindowManager? = null
    private var routeActive = false
    private var grantTried = false

    private val history = mutableListOf<String>()

    /** Последний статус (для диагностики). */
    @Volatile
    var status: String = "не запускали"
        private set(value) {
            field = value
            Log.i(TAG, value)
            synchronized(history) {
                history.add("${System.currentTimeMillis()} $value")
                while (history.size > HISTORY_MAX) history.removeAt(0)
            }
        }

    fun statusHistory(): List<String> = synchronized(history) { history.toList() }

    /** Идемпотентно: можно звать из любого живущего компонента. */
    fun init(context: Context) {
        main.post {
            if (app != null) return@post
            val ctx = context.applicationContext
            app = ctx
            ctx.registerReceiver(meterReceiver, IntentFilter().apply {
                addAction(ACTION_METER)
                addAction(ACTION_CARLAN)
            })
            (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .registerDisplayListener(displayListener, main)
            GuidanceHub.addListener(guidanceListener)
            status = "готов"
            // Сразу занять дисплей 1 чёрным окном — чтобы в «Картах»
            // приборки не было зеркала главного экрана.
            if (overlayOn(ctx)) show(announce = false)
        }
    }

    /** Режим родного интерфейса приборки. */
    fun isNative(ctx: Context): Boolean = AppSettings.clusterMode(ctx) == AppSettings.CLUSTER_NATIVE

    /**
     * Слать ли знак лимита (CarLan CameraInfo): в родном режиме — по
     * варианту ([ClusterTbt.variant]), в своём окне — всегда.
     */
    private fun warningsOn(ctx: Context): Boolean =
        CarLanClient.enabled && (!isNative(ctx) || ClusterTbt.variant(ctx).limit)

    /** Включён режим своего окна. */
    private fun overlayOn(ctx: Context): Boolean = AppSettings.clusterNavi(ctx) && !isNative(ctx)

    /**
     * Настройку (вкл/выкл или режим) поменяли — привести приборку в
     * соответствие: убрать чужой режим, текущий маршрут показать новым.
     */
    fun applySettings() {
        main.post {
            val ctx = app ?: return@post
            val g = GuidanceHub.current
            if (overlayOn(ctx)) {
                if (!ClusterTbt.enabled) ClusterTbt.stop(ctx)
                if (view == null && !addView(ctx)) return@post
                if (g != null) announce(ctx, true)
                status = "режим: своё окно"
                return@post
            }
            if (view != null) {
                removeView()
                announce(ctx, false)
            }
            // Вариант без знака лимита: убрать уже показанный.
            if (CarLanClient.enabled && !warningsOn(ctx)) ClusterWarnings.update(ctx, null)
            if (AppSettings.clusterNavi(ctx) && g != null) {
                nativeUpdate(ctx, g)
            } else {
                ClusterTbt.stop(ctx)
                if (warningsOn(ctx)) ClusterWarnings.update(ctx, null)
            }
            status = if (AppSettings.clusterNavi(ctx)) "режим: родной интерфейс приборки" else "маршрут на приборке выключен"
        }
    }

    /** Родной интерфейс: подсказка манёвра и знак лимита. */
    private fun nativeUpdate(ctx: Context, g: Guidance) {
        val wasActive = ClusterTbt.isActive
        ClusterTbt.update(ctx, g)
        val started = !wasActive && ClusterTbt.isActive
        if (!warningsOn(ctx)) return
        ClusterWarnings.update(ctx, g)
        // На 202 mapservice сам шлёт CameraInfo (NaviStatus=3) и может
        // затереть наш лимит — повторяем свой следом.
        if (started) {
            main.removeCallbacks(warningsRepeat)
            main.postDelayed(warningsRepeat, WARNINGS_REPEAT_MS)
        }
    }

    private val guidanceListener = GuidanceHub.Listener { g ->
        view?.guidance = g
        val ctx = app ?: return@Listener
        if (g != null) {
            main.removeCallbacks(routeEnded)
            val toCluster = AppSettings.clusterNavi(ctx) || GuidanceHub.isDemo
            if (toCluster && isNative(ctx)) {
                nativeUpdate(ctx, g)
            } else if (toCluster) {
                if (ClusterTbt.enabled) ClusterTbt.update(ctx, g)
                if (CarLanClient.enabled) ClusterWarnings.update(ctx, g)
            }
            if (!routeActive) {
                routeActive = true
                if (overlayOn(ctx)) {
                    status = "маршрут начался — показываю на приборке"
                    show(announce = true)
                } else if (toCluster) {
                    status = "маршрут начался — родной интерфейс приборки"
                }
            }
        } else if (routeActive) {
            main.removeCallbacks(routeEnded)
            main.postDelayed(routeEnded, END_DELAY_MS)
        }
    }

    private val routeEnded = Runnable {
        routeActive = false
        ManeuverGrab.reset()
        val ctx = app ?: return@Runnable
        ClusterTbt.stop(ctx)
        if (warningsOn(ctx)) ClusterWarnings.update(ctx, null)
        if (view != null && overlayOn(ctx)) {
            status = "маршрут закончился — возвращаю приборку"
            hide(announce = true)
        }
    }

    private val meterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ex = intent.extras
            val map = ex?.keySet()?.associateWith { k -> try { ex.get(k) } catch (e: Exception) { null } }
                ?: emptyMap()
            val req = GuidanceText.naviAreaRequest(map)
            if (req == null) {
                Log.d(TAG, "от приборки ${intent.action}: $map — не про навигацию")
                return
            }
            status = "от приборки ${intent.action}: $map -> " +
                if (req == 1) "показать навигацию" else "убрать навигацию"
            if (!overlayOn(context)) return
            when (req) {
                1 -> show(announce = true)
                0 -> {} // окно остаётся (чёрное без маршрута) — см. описание

            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            val ctx = app ?: return
            if (view == null && overlayOn(ctx)) show(announce = routeActive)
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (view?.display?.displayId == displayId) {
                status = "дисплей $displayId пропал"
                removeView()
            }
        }

        override fun onDisplayChanged(displayId: Int) {}
    }

    /** Дисплей приборки: первый presentation-дисплей, иначе второй по счёту. */
    fun clusterDisplay(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
            ?: dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    val isShown: Boolean get() = view != null

    /** Показать окно на приборке; [announce] — переключить приборку в навигацию. */
    fun show(announce: Boolean) {
        main.post {
            val ctx = app ?: return@post
            if (view == null && !addView(ctx)) return@post
            if (announce) announce(ctx, true)
        }
    }

    /**
     * Маршрута на приборке больше нет: окно остаётся (пустое чёрное —
     * не зеркало главного экрана), [announce] — вернуть вид приборки.
     */
    fun hide(announce: Boolean) {
        main.post {
            val ctx = app ?: return@post
            if (announce) announce(ctx, false)
        }
    }

    /** Совсем убрать маршрут с приборки (настройка выключена): окно и родные подсказки. */
    fun remove() {
        main.post {
            val ctx = app ?: return@post
            if (view != null) {
                removeView()
                announce(ctx, false)
            }
            ClusterTbt.stop(ctx)
            if (warningsOn(ctx)) ClusterWarnings.update(ctx, null)
        }
    }

    /** Ширина центральной полосы картинки, px. */
    fun setContentWidth(context: Context, px: Int) {
        AppSettings.setClusterWidth(context, px)
        main.post { view?.contentWidth = px }
    }

    private fun addView(ctx: Context): Boolean {
        val display = clusterDisplay(ctx)
        if (display == null) {
            status = "второго дисплея (приборки) нет"
            return false
        }
        if (!Settings.canDrawOverlays(ctx)) {
            if (!grantTried) {
                grantTried = true
                status = "нет права оверлея — выдаю через adb"
                Thread {
                    ClusterSetup.grantOverlay(ctx)
                    main.post { if (routeActive || GuidanceHub.current != null) show(announce = true) }
                }.start()
            } else {
                status = "нет права оверлея (appops SYSTEM_ALERT_WINDOW)"
            }
            return false
        }
        return try {
            val dctx = ctx.createDisplayContext(display)
            val wm = dctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = ClusterGuidanceView(dctx).apply {
                contentWidth = AppSettings.clusterWidth(ctx)
                guidance = GuidanceHub.current
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
            status = "окно на дисплее ${display.displayId} (${display.name})"
            true
        } catch (e: Exception) {
            Log.w(TAG, "addView", e)
            status = "окно на приборку не добавилось: ${e.javaClass.simpleName}: ${e.message}"
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
        status = "окно с приборки убрано"
    }

    /** Переключение вида приборки: CarLan (если включён) и com.desaysv.mapservice (как Jarvis). */
    private fun announce(ctx: Context, on: Boolean) {
        if (CarLanClient.enabled) {
            // После 207: на него mapservice сам шлёт DisplayCluster:false.
            main.removeCallbacks(carLanAnnounce)
            carLanOn = on
            main.postDelayed(carLanAnnounce, CARLAN_DELAY_MS)
        }
        try {
            // 207 — как в проверенной на ГУ версии (картинка в «Картах» была).
            // Пара 205→207 (по разбору SVMapService) на ГУ дала пустые «Карты»
            // и неработающее демо (2026-10-22) — не шлём.
            ctx.sendBroadcast(Intent(ACTION_TURBODOG).putExtra("CODE", 207).putExtra("DATA", 0))
        } catch (e: Exception) {
            Log.w(TAG, "turbodog", e)
        }
        val hasMapService = try {
            ctx.packageManager.getPackageInfo(MAP_SERVICE, 0)
            true
        } catch (e: Exception) {
            false
        }
        if (!hasMapService) {
            status = "$MAP_SERVICE нет — вид приборки не переключаю"
            return
        }
        fun send(state: Int) = ctx.sendBroadcast(
            Intent(ACTION_AUTONAVI).putExtra("KEY_TYPE", 10019).putExtra("EXTRA_STATE", state)
        )
        if (on) {
            // Jarvis сначала «сбрасывает» (2), через 400 мс — показать (1).
            send(2)
            main.postDelayed({ send(1) }, 400)
        } else {
            send(2)
        }
        status = "приборка: навигация ${if (on) "вкл" else "выкл"} (10019)"
    }

    private var carLanOn = false

    private val carLanAnnounce = Runnable {
        val ctx = app ?: return@Runnable
        CarLanClient.showNaviArea(ctx, carLanOn)
        // У TiggoBridge панель появлялась только вместе с CameraInfo (2+5+4).
        // Окно убрали ради родного режима — лимит там нужен по-прежнему.
        val keepWarnings = carLanOn || (isNative(ctx) && AppSettings.clusterNavi(ctx) && warningsOn(ctx))
        ClusterWarnings.reset()
        ClusterWarnings.update(ctx, if (keepWarnings) GuidanceHub.current else null)
        // CameraInfo сразу после 2+5 приборка теряет (ГУ 2026-10-26: знак
        // появился только со второго 4100/4) — повторить, когда панель поднимется.
        main.removeCallbacks(warningsRepeat)
        if (keepWarnings) main.postDelayed(warningsRepeat, CARLAN_CAMERA_REPEAT_MS)
        status = "приборка: CarLan DisplayCluster=$carLanOn"
    }

    /** Повторить CameraInfo (лимит) текущего маршрута — его могли потерять или затереть. */
    private val warningsRepeat = Runnable {
        val ctx = app ?: return@Runnable
        val g = GuidanceHub.current ?: return@Runnable
        if (!warningsOn(ctx)) return@Runnable
        ClusterWarnings.reset()
        ClusterWarnings.update(ctx, g)
    }

    private const val CARLAN_DELAY_MS = 700L
    private const val CARLAN_CAMERA_REPEAT_MS = 1500L
    private const val WARNINGS_REPEAT_MS = 700L
    private const val ACTION_METER = "desay.broadcast.map.meter.interaction"
    private const val ACTION_CARLAN = "SVCarLanManager"
    private const val ACTION_TURBODOG = "turbodog.navigation.system.message"
    private const val ACTION_AUTONAVI = "AUTONAVI_STANDARD_BROADCAST_SEND"
    private const val MAP_SERVICE = "com.desaysv.mapservice"
    private const val END_DELAY_MS = 8_000L
    private const val HISTORY_MAX = 40
    private const val TAG = "ClusterOverlay"
}
