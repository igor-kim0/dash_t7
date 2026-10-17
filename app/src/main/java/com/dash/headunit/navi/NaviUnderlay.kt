package com.dash.headunit.navi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Region
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

/**
 * Навигатор «в блоке» дашборда — схема Jarvis без встраивания:
 *
 * 1. Навигатор запущен обычным полноэкранным приложением ПОД дашбордом
 *    (дашборд — полупрозрачная activity, см. тему Theme.Dash.Dark).
 * 2. `am stack resize` (через [AdbShell]) даёт стеку навигатора границы
 *    блока — у навигатора настоящее окно размером с блок, карта и кнопки
 *    раскладываются в нём. Системные панели ГУ (NavigationBar 288 px слева,
 *    StatusBar 96 px справа) пересекаются с блоком и съели бы окно отступами —
 *    поэтому навигатор на это время получает `policy_control immersive.full`.
 * 3. Дашборд в этом прямоугольнике прозрачен и не принимает касания
 *    ([installTouchHole]) — карта видна и управляется пальцем. В PAUSED под
 *    полупрозрачным дашбордом Yandex Navi продолжает рисовать.
 *
 * Дашборд всегда сверху и в фокусе, поэтому окна не дерутся за z-order.
 *
 * Раньше блок задавался `wm overscan` — Yandex Navi применяет его отступы к
 * своим view по нескольку раз (ширина карты 0, чёрный блок на ГУ). Overscan
 * от старых версий сбрасывается в [resetNow]. Границы стека живут до
 * перезагрузки, policy_control — и после неё; оба возвращаются, когда
 * дашборд уходит с экрана ([setHole] с null).
 */
object NaviUnderlay {

    @Volatile
    var lastStatus: String = "не запускалось"
        private set(value) {
            field = value
            synchronized(history) {
                history.add("${System.currentTimeMillis()} $value")
                while (history.size > HISTORY_MAX) history.removeAt(0)
            }
        }

    private val history = mutableListOf<String>()

    /** Последние статусы (unix-время мс + текст) — для «Логи для Claude». */
    fun statusHistory(): List<String> = synchronized(history) { history.toList() }

    /** Shell-команды строго по очереди и не в UI-потоке. */
    private val worker = Executors.newSingleThreadExecutor()

    // Навигатор сейчас в блоке (для сброса при падении).
    @Volatile
    private var holeActive = false

    /** Был ли открыт порт adbd при последней проверке. */
    @Volatile
    var portOpen: Boolean = true
        private set

    /** Канал хоть раз разрешали — можно подключаться без касания пользователя. */
    fun isAuthorized(context: Context): Boolean =
        prefs(context.applicationContext).getBoolean(PREF_AUTHORIZED, false)

    /** Последний результат проверки канала: null — ещё не проверяли. */
    @Volatile
    var shellReady: Boolean? = null
        private set

    /**
     * Проверить/установить adb-канал в фоне. При первом подключении на
     * экране ГУ появится запрос «Разрешить отладку». [done] — в UI-потоке.
     */
    fun connect(context: Context, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        worker.execute {
            portOpen = AdbShell.isPortOpen()
            val ok = if (!portOpen) {
                lastStatus = "порт 5555 закрыт (сетевой adb выключен)"
                false
            } else {
                val id = AdbShell.exec(app, "id")
                lastStatus = "id: ${id?.trim() ?: AdbShell.lastStatus}"
                id != null
            }
            shellReady = ok
            if (ok) prefs(app).edit().putBoolean(PREF_AUTHORIZED, true).apply()
            main.post { done(ok) }
        }
    }

    /**
     * Прямоугольник навигатора [pkg] в координатах экрана или null — вернуть
     * навигатору весь экран и policy_control (снова с панелями). Стек
     * навигатора ищется каждый раз: задача могла пересоздаться. [then] —
     * в потоке команд, после применения; аргумент — навигатор в границах блока.
     */
    fun setHole(context: Context, hole: Rect?, pkg: String = "", then: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext
        val size = realSize(app)
        val bounds = hole ?: Rect(0, 0, size.x, size.y)
        worker.execute {
            var inBlock = false
            try {
                if (hole != null) inBlock = applyHole(app, bounds, pkg) else releaseHole(app, bounds)
            } finally {
                then?.invoke(inBlock)
            }
        }
    }

    private fun applyHole(app: Context, bounds: Rect, pkg: String): Boolean {
        // Без канала стек всё равно не изменить.
        if (shellReady != true) return false
        // Политику — до resize: навигатор сразу раскладывается без панелей.
        if (pkg != appliedPkg) {
            if (!run(app, immersiveCmd(app, pkg))) return false
            appliedPkg = pkg
        }
        // Только что запущенная задача появляется в списке не сразу.
        repeat(STACK_ATTEMPTS) { attempt ->
            val stack = findStack(app, pkg)
            if (stack != null) {
                if (!holeActive) {
                    prefs(app).edit().putString(PREF_HOLE_PKG, pkg).commit()
                    holeActive = true
                }
                if (stack.second != bounds) resizeStack(app, stack.first, bounds)
                return true
            }
            if (attempt < STACK_ATTEMPTS - 1) Thread.sleep(STACK_RETRY_MS)
        }
        lastStatus = "стек $pkg не найден"
        return false
    }

    private fun releaseHole(app: Context, full: Rect) {
        if (!holeActive && appliedPkg.isEmpty()) return
        val pkg = appliedPkg
        if (pkg.isNotEmpty()) {
            findStack(app, pkg)?.let { if (it.second != full) resizeStack(app, it.first, full) }
        }
        if (run(app, restorePolicyCmd(app))) {
            prefs(app).edit().remove(PREF_POLICY_ORIG).remove(PREF_HOLE_PKG).apply()
            appliedPkg = ""
            holeActive = false
        }
    }

    private fun resizeStack(app: Context, id: Int, b: Rect) {
        run(app, "am stack resize $id ${b.left} ${b.top} ${b.right} ${b.bottom}")
    }

    /** Выполнить [cmd]; успех — команда ничего не напечатала. */
    private fun run(app: Context, cmd: String): Boolean {
        val out = AdbShell.exec(app, "$cmd 2>&1; echo __ok__")
        val ok = out != null && out.contains("__ok__") && out.substringBefore("__ok__").isBlank()
        lastStatus = if (ok) cmd else "$cmd → ${out?.trim() ?: AdbShell.lastStatus}"
        return ok
    }

    /** Id и границы стека основного экрана с задачей пакета [pkg]. */
    private fun findStack(app: Context, pkg: String): Pair<Int, Rect>? {
        val out = AdbShell.exec(app, "am stack list") ?: return null
        return parseStack(out, pkg)
    }

    /**
     * Разбор `am stack list` (Android 9):
     * `Stack id=31 bounds=[19,86][652,701] displayId=0 ...`, ниже строки задач
     * `  taskId=135: ru.yandex.yandexnavi/... bounds=...`.
     */
    internal fun parseStack(list: String, pkg: String): Pair<Int, Rect>? {
        var current: Pair<Int, Rect>? = null
        for (line in list.lineSequence()) {
            val m = STACK_RE.find(line)
            if (m != null) {
                val (id, l, t, r, b, display) = m.destructured
                current = if (display == "0") id.toInt() to Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
                else null
            } else if (current != null && line.trimStart().startsWith("taskId=") && line.contains(": $pkg/")) {
                return current
            }
        }
        return null
    }

    /**
     * Вернуть экран на старте процесса: если прошлый раз приложение упало
     * или было убито в режиме навигатора, навигатор остался бы в границах
     * блока, а policy_control — изменённым.
     */
    fun resetOnStart(context: Context) {
        val app = context.applicationContext
        worker.execute { resetNow(app) }
    }

    /**
     * Блокирующий сброс (для фоновых потоков: загрузка системы, падение
     * приложения): policy_control, overscan старых версий, границы стека
     * навигатора. Без разрешённого канала ничего не делает — не пугаем
     * запросом отладки. Возвращает true, если сброс прошёл.
     */
    fun resetNow(context: Context): Boolean {
        val app = context.applicationContext
        if (!prefs(app).getBoolean(PREF_AUTHORIZED, false)) return false
        if (!AdbShell.isPortOpen()) return false
        val cmd = "${restorePolicyCmd(app)}; wm overscan reset"
        if (AdbShell.exec(app, cmd, authTimeoutMs = 5000) == null) return false
        prefs(app).getString(PREF_HOLE_PKG, null)?.let { pkg ->
            val full = realSize(app).let { Rect(0, 0, it.x, it.y) }
            findStack(app, pkg)?.let { if (it.second != full) resizeStack(app, it.first, full) }
        }
        prefs(app).edit().remove(PREF_HOLE_PKG).remove(PREF_POLICY_ORIG).apply()
        shellReady = true
        appliedPkg = ""
        holeActive = false
        return true
    }

    // Пакет, для которого включён immersive ("" — не включали).
    @Volatile
    private var appliedPkg = ""

    /**
     * Команда: навигатор [pkg] без системных панелей (immersive.full) и без
     * подсказки «Полноэкранный режим» (preconfirms). Исходное значение
     * policy_control запоминается один раз — до возврата в [restorePolicyCmd].
     */
    private fun immersiveCmd(app: Context, pkg: String): String {
        if (pkg.isEmpty()) return "true"
        val prefs = prefs(app)
        if (!prefs.contains(PREF_POLICY_ORIG)) {
            val orig = AdbShell.exec(app, "settings get global policy_control")?.trim()
            prefs.edit().putString(PREF_POLICY_ORIG, orig.takeUnless { it == "null" }.orEmpty()).commit()
        }
        return "settings put global policy_control immersive.full=$pkg:immersive.preconfirms=$pkg"
    }

    /** Команда: вернуть policy_control, каким он был до режима навигатора. */
    private fun restorePolicyCmd(app: Context): String {
        val prefs = prefs(app)
        if (!prefs.contains(PREF_POLICY_ORIG)) return "true"
        val orig = prefs.getString(PREF_POLICY_ORIG, "").orEmpty()
        return if (orig.isEmpty()) "settings delete global policy_control >/dev/null"
        else "settings put global policy_control '$orig'"
    }

    /**
     * При падении приложения в режиме навигатора вернуть экран целиком,
     * иначе он останется сжатым до следующего запуска (и после перезагрузки).
     */
    fun installCrashReset(context: Context) {
        if (crashResetInstalled) return
        crashResetInstalled = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (holeActive) {
                // Отдельный поток с таймаутом: канал может быть занят worker'ом.
                val reset = Thread { resetNow(app) }
                reset.start()
                reset.join(CRASH_RESET_WAIT_MS)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    @Volatile
    private var crashResetInstalled = false

    /**
     * Запустить навигатор [pkg] и сразу вернуть дашборд наверх — одним
     * переходом (startActivities), без мелькания полного навигатора.
     */
    fun launchUnder(activity: Activity, pkg: String): Boolean {
        return try {
            val navi = activity.packageManager.getLaunchIntentForPackage(pkg)
            if (navi == null) {
                lastStatus = "нет запускаемой активности: $pkg"
                return false
            }
            navi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val self = Intent(activity, activity.javaClass)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            activity.startActivities(arrayOf(navi, self))
            lastStatus = "навигатор $pkg запущен под дашбордом"
            true
        } catch (e: Exception) {
            Log.w(TAG, "launchUnder", e)
            lastStatus = "launchUnder: ${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    /**
     * Дать навигатору [pkg], уже ужатому до блока, один раз стать RESUMED и
     * вернуть дашборд наверх. После [launchUnder] навигатор только создан и
     * сразу PAUSED: Yandex Navi включает GPS/слежение в onResume, без этого
     * карта нарисована, но стоит. Окно навигатора уже размером с блок, так
     * что дашборд вокруг остаётся на экране. Дальше в PAUSED слежение живёт.
     */
    fun resumeInBlock(activity: Activity, pkg: String) {
        val navi = activity.packageManager.getLaunchIntentForPackage(pkg) ?: return
        val app = activity.applicationContext
        val self = Intent(app, activity.javaClass)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            app.startActivity(
                navi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            lastStatus = "навигатор $pkg поднят в блоке для onResume"
        } catch (e: Exception) {
            lastStatus = "resumeInBlock: ${e.javaClass.simpleName}: ${e.message}"
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                app.startActivity(self)
            } catch (e: Exception) {
                lastStatus = "resumeInBlock back: ${e.javaClass.simpleName}: ${e.message}"
            }
        }, RESUME_KICK_MS)
    }

    /**
     * Открыть навигатор на весь экран: сначала вернуть стеку полный размер,
     * потом поднять — без кадра с маленьким навигатором на чёрном фоне.
     */
    fun openFull(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        val app = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        setHole(app, null) {
            main.post {
                try {
                    app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    lastStatus = "openFull: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
        return true
    }

    /**
     * Касания в [hole] (координаты окна) проходят сквозь окно [view] к
     * навигатору под ним. Скрытый API ViewTreeObserver
     * (OnComputeInternalInsetsListener, light greylist на Android 9) —
     * так делают SystemUI и плавающие клавиатуры. Возвращает false, если
     * API недоступен: тогда навигатор виден, но касания до него не дойдут.
     */
    fun installTouchHole(view: View, hole: () -> Region?): Boolean {
        return try {
            val listenerCls = Class.forName(
                "android.view.ViewTreeObserver\$OnComputeInternalInsetsListener"
            )
            val proxy = Proxy.newProxyInstance(
                listenerCls.classLoader, arrayOf(listenerCls)
            ) { self, method, args ->
                when (method.name) {
                    "onComputeInternalInsets" -> {
                        fillInsets(args!![0], view, hole())
                        null
                    }
                    "equals" -> self === args?.get(0)
                    "hashCode" -> System.identityHashCode(self)
                    "toString" -> "NaviTouchHole"
                    else -> null
                }
            }
            ViewTreeObserver::class.java
                .getMethod("addOnComputeInternalInsetsListener", listenerCls)
                .invoke(view.viewTreeObserver, proxy)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "touch hole", e)
            lastStatus = "touch hole: ${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    private fun fillInsets(info: Any, view: View, hole: Region?) {
        val cls = info.javaClass
        val region = cls.getField("touchableRegion").get(info) as Region
        region.set(0, 0, view.width, view.height)
        if (hole != null) region.op(hole, Region.Op.DIFFERENCE)
        // InternalInsetsInfo.TOUCHABLE_INSETS_REGION
        cls.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
            .invoke(info, TOUCHABLE_INSETS_REGION)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("navi_underlay", Context.MODE_PRIVATE)

    private fun realSize(context: Context): Point {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return Point().also {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(it)
        }
    }

    private const val TAG = "NaviUnderlay"
    private const val HISTORY_MAX = 50
    private const val TOUCHABLE_INSETS_REGION = 3
    private const val CRASH_RESET_WAIT_MS = 3000L
    private const val STACK_ATTEMPTS = 5
    private const val STACK_RETRY_MS = 300L
    private const val RESUME_KICK_MS = 1000L
    private val STACK_RE =
        Regex("""^Stack id=(\d+) bounds=\[(\d+),(\d+)]\[(\d+),(\d+)] displayId=(\d+)""")
    private const val PREF_AUTHORIZED = "adb_authorized"
    private const val PREF_POLICY_ORIG = "policy_control_orig"
    private const val PREF_HOLE_PKG = "hole_pkg"
}
