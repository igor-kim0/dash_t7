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
 * Маршрут навигатора на приборке.
 *
 * Приборка T18FL3 получает видео со второго дисплея Android (id 1,
 * «Экран HDMI» 1920×720, FLAG_PRESENTATION) и показывает его в режиме
 * «навигация». Как у Jarvis:
 *  - картинку кладём на этот дисплей — у нас это окно-оверлей
 *    (TYPE_APPLICATION_OVERLAY, без фокуса и касаний), а не Activity:
 *    на Android 9 activity на втором дисплее забрала бы фокус и паузу
 *    у главного экрана;
 *  - приборку переключаем в навигацию широковещанием для
 *    com.desaysv.mapservice: AUTONAVI_STANDARD_BROADCAST_SEND,
 *    KEY_TYPE=10019, EXTRA_STATE=1 (показать) / 2 (убрать);
 *  - приборка сама просит навигацию (водитель листает виды):
 *    desay.broadcast.map.meter.interaction с RequestNaviAreaDisplay=1/0.
 *    targetSdk 28 — неявные broadcast в манифесте не доходят, поэтому
 *    приёмник регистрируем динамически из живущих сервисов
 *    (MediaListenerService, NaviAccessibilityService).
 *
 * Поведение при включённой настройке [AppSettings.clusterNavi]:
 * начался маршрут — показать и переключить приборку; закончился
 * (через [END_DELAY_MS]) — убрать и вернуть приборку; запросы приборки
 * выполняются всегда.
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
        }
    }

    private val guidanceListener = GuidanceHub.Listener { g ->
        view?.guidance = g
        val ctx = app ?: return@Listener
        if (g != null) {
            main.removeCallbacks(routeEnded)
            if (!routeActive) {
                routeActive = true
                if (AppSettings.clusterNavi(ctx)) {
                    status = "маршрут начался — показываю на приборке"
                    show(announce = true)
                }
            }
        } else if (routeActive) {
            main.removeCallbacks(routeEnded)
            main.postDelayed(routeEnded, END_DELAY_MS)
        }
    }

    private val routeEnded = Runnable {
        routeActive = false
        val ctx = app ?: return@Runnable
        if (view != null && AppSettings.clusterNavi(ctx)) {
            status = "маршрут закончился — убираю с приборки"
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
            if (!AppSettings.clusterNavi(context)) return
            when (req) {
                1 -> show(announce = true)
                0 -> hide(announce = false)
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            val ctx = app ?: return
            if (routeActive && view == null && AppSettings.clusterNavi(ctx)) show(announce = true)
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

    fun hide(announce: Boolean) {
        main.post {
            val ctx = app ?: return@post
            removeView()
            if (announce) announce(ctx, false)
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

    /** Переключение вида приборки через com.desaysv.mapservice (как Jarvis). */
    private fun announce(ctx: Context, on: Boolean) {
        try {
            // Штатному навигатору TurboDog — отпустить приборку.
            ctx.sendBroadcast(Intent("turbodog.navigation.system.message").putExtra("CODE", 207).putExtra("DATA", 0))
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

    private const val ACTION_METER = "desay.broadcast.map.meter.interaction"
    private const val ACTION_CARLAN = "SVCarLanManager"
    private const val ACTION_AUTONAVI = "AUTONAVI_STANDARD_BROADCAST_SEND"
    private const val MAP_SERVICE = "com.desaysv.mapservice"
    private const val END_DELAY_MS = 8_000L
    private const val HISTORY_MAX = 40
    private const val TAG = "ClusterOverlay"
}
