package com.dash.headunit.car

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.dash.headunit.navi.AdbShell

/**
 * Штатная шторка климата (`com.desaysv.svhvac`) разворачивается на любую
 * запись в HVAC. Как Jarvis (d6 / e cases 25–27 / x5.o):
 *
 * - **hold** — до записи по локальному adb `am force-stop` svhvac
 *   ([prepare] прямо из тапа, [beforeWrite] ждёт его ≤ [HOLD_WAIT_MS]);
 * - каждая запись двигает метку [lastCmd];
 * - **wake** — сторож ждёт [QUIET_MS] тишины (и пока 16,9 ≠ 0 — до
 *   [FLAG_MAX_MS]), затем будит svhvac broadcast'ом на его
 *   BootCompleteReceiver;
 * - adb нет — после записи шлём `CLOSE_PANEL` серией с задержками [CLOSE_DELAYS].
 *
 * Флаг «придержана» живёт в prefs: процесс убили — [restoreOnStart] будит svhvac.
 */
object HvacShade {

    private const val TAG = "HvacShade"
    private const val PKG = "com.desaysv.svhvac"
    private const val RECEIVER = "com.desaysv.svhvac.receiver.BootCompleteReceiver"
    private const val ACTION_WAKE = "com.dash.headunit.WAKE_HVAC"
    private const val ACTION_CLOSE = "com.desaysv.svhvac.CLOSE_PANEL"
    private const val PREFS = "hvac_shade"
    private const val PREF_HELD = "held"

    private const val HOLD_WAIT_MS = 2500L
    private const val QUIET_MS = 9000L
    private const val FLAG_MAX_MS = 45000L
    private const val FLAG_UNKNOWN_MAX_MS = 12000L
    private const val ADB_AUTH_MS = 3000
    private val CLOSE_DELAYS = longArrayOf(120, 300, 600, 1000, 1600, 2500)

    private val lock = Object()
    private val main = Handler(Looper.getMainLooper())

    /** null — ещё не проверяли; false — svhvac нет или adb не помог. */
    @Volatile
    private var usable: Boolean? = null
    private var held = false
    private var holding = false
    @Volatile
    private var lastCmd = 0L
    private var watcher: Thread? = null

    /** Тап по кнопке климата: начать hold заранее, не дожидаясь очереди записи. */
    fun prepare(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            lastCmd = SystemClock.uptimeMillis()
            if (held || holding || usable == false) return
            holding = true
        }
        Thread({ hold(app) }, "dash-hvac-hold").start()
    }

    /** Перед записью (поток записи): дождаться hold, если он идёт. */
    fun beforeWrite(context: Context) {
        prepare(context)
        val deadline = SystemClock.uptimeMillis() + HOLD_WAIT_MS
        synchronized(lock) {
            lastCmd = SystemClock.uptimeMillis()
            while (holding && !held) {
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0) break
                lock.wait(left)
            }
        }
    }

    /** После записи: метка тишины; без hold — свернуть шторку вдогонку. */
    fun afterWrite(context: Context) {
        val app = context.applicationContext
        val isHeld = synchronized(lock) {
            lastCmd = SystemClock.uptimeMillis()
            held
        }
        if (!isHeld) {
            for (d in CLOSE_DELAYS) main.postDelayed({ closePanel(app) }, d)
        }
    }

    /** Старт приложения: svhvac остался остановленным с прошлого раза — поднять. */
    fun restoreOnStart(context: Context) {
        val app = context.applicationContext
        if (prefs(app).getBoolean(PREF_HELD, false)) {
            Thread({ wake(app, "остался с прошлого запуска") }, "dash-hvac-wake").start()
        }
    }

    private fun hold(app: Context) {
        var ok = false
        try {
            if (usable == null) usable = installed(app) && AdbShell.isPortOpen()
            if (usable == true) {
                val out = AdbShell.exec(app, "am force-stop $PKG 2>&1; echo __done__", ADB_AUTH_MS)
                ok = out?.contains("__done__") == true
                if (!ok) usable = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "hold", e)
        }
        synchronized(lock) {
            holding = false
            if (ok) {
                held = true
                prefs(app).edit().putBoolean(PREF_HELD, true).apply()
                startWatcher(app)
            }
            lock.notifyAll()
        }
        Log.i(TAG, if (ok) "штатная шторка климата придержана" else "придержать не вышло — CLOSE_PANEL вдогонку")
    }

    private fun startWatcher(app: Context) {
        if (watcher?.isAlive == true) return
        watcher = Thread({ watch(app) }, "dash-hvac-wake").also { it.start() }
    }

    /** e.e() Jarvis: тишина [QUIET_MS]; пока 16,9 ≠ 0 — ждём, но не дольше лимита. */
    private fun watch(app: Context) {
        val reader = DesayCarReader(app)
        while (true) {
            val quiet = SystemClock.uptimeMillis() - lastCmd
            if (quiet < QUIET_MS) {
                SystemClock.sleep(QUIET_MS - quiet + 100)
                continue
            }
            val flag = runCatching { if (reader.isAvailable()) reader.readInt(16, 9) else -1 }
                .getOrDefault(-1)
            val done = flag == 0 ||
                (flag < 0 && quiet >= FLAG_UNKNOWN_MAX_MS) ||
                quiet >= FLAG_MAX_MS
            if (!done) {
                SystemClock.sleep(800)
                continue
            }
            synchronized(lock) {
                if (SystemClock.uptimeMillis() - lastCmd < QUIET_MS || holding) return@synchronized
                wake(app, if (quiet >= FLAG_MAX_MS) "флаг 16,9 не снялся за ${quiet / 1000} с" else "тишина ${quiet / 1000} с")
                watcher = null
                return
            }
        }
    }

    private fun wake(app: Context, reason: String) {
        try {
            app.sendBroadcast(
                Intent(ACTION_WAKE).setClassName(PKG, RECEIVER)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            )
        } catch (e: Exception) {
            Log.w(TAG, "wake", e)
        }
        synchronized(lock) { held = false }
        prefs(app).edit().putBoolean(PREF_HELD, false).apply()
        Log.i(TAG, "штатная служба климата поднята обратно ($reason)")
    }

    private fun closePanel(app: Context) {
        try {
            app.sendBroadcast(Intent(ACTION_CLOSE).setPackage(PKG))
        } catch (e: Exception) {
            Log.w(TAG, "close", e)
        }
    }

    private fun installed(app: Context): Boolean = try {
        app.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun prefs(app: Context) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
