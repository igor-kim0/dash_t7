package com.dash.headunit.navi

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.dash.headunit.navi.cluster.NaviAccessibilityService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Проба «какие кнопки руля и где видны» (диалог «Алиса по кнопке руля» →
 * «Проверить все кнопки»). Назначение кнопки Алисы не срабатывало на ГУ —
 * нужно понять, приходит ли кнопка голоса в Android клавишей вообще и на
 * каком уровне её видно. Пока идёт проба ([DURATION_MS]), нажатия пишутся
 * сразу из нескольких мест:
 *
 *  1. «a11y» — фильтр клавиш [NaviAccessibilityService] (как у назначения),
 *     но только запись: клавиши не забираются, кнопки работают как обычно.
 *     На этой прошивке KeyboardInterceptor отдаёт службам все клавиши,
 *     кроме громкости, ДО PhoneWindowManager / Desay key_policy.
 *  2. «окно» — клавиши, дошедшие до окна диалога (фокус у нас).
 *  3. «key_policy» — свой колбэк в службе Desay key_policy (IKeyPolicy,
 *     registerKeyCallBack с уникальным именем): ей рассылаются «клавиши SV»
 *     (isDSVKey: 82, 85–88, 90, 126/127, 129, 286–301, 304, 314, 320–323),
 *     если их раньше не забрал штатный обработчик (pre_handle и т.п.).
 *  4. adb: `getevent -lt` (сырые события ядра со всех /dev/input — в т.ч.
 *     «SVP UINPUT KEYBOARD», откуда приходят кнопки руля) и системный
 *     logcat за время пробы: KeyPolicyImpl пишет каждую клавишу и кто её
 *     забрал («handled by APP/default…»), WindowManager — interceptKeyT*.
 *
 * Если кнопки голоса нет ни в одном источнике — она идёт мимо ввода
 * Android (MCU → платформа Desay → голосовой помощник напрямую).
 * Отчёт — files/wheel_keys/keys_<время>.txt.
 */
object WheelKeyProbe {

    @Volatile var running = false
        private set

    /** Состояние для диалога. */
    @Volatile var status: String = ""
        private set

    /** Изменения для открытого диалога. */
    var onChanged: (() -> Unit)? = null

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val live = ArrayList<String>()
    private val hits = ArrayList<Hit>()
    private var startedAt = 0L

    private data class Hit(val source: String, val keyCode: Int, val scanCode: Int, val action: Int)

    /** Последние строки живого журнала пробы. */
    fun liveLines(max: Int): List<String> = synchronized(live) { live.takeLast(max) }

    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        running = true
        startedAt = SystemClock.uptimeMillis()
        synchronized(live) { live.clear() }
        synchronized(hits) { hits.clear() }
        status = "идёт проверка: нажмите по очереди все кнопки на руле (каждую коротко, кнопку голоса — ещё и долго)"
        WheelKeys.applyFilter()
        Thread({ run(app) }, "WheelKeyProbe").start()
        changed()
    }

    /** Клавиша от службы спец. возможностей (запись, не забираем). */
    fun onA11yKey(e: KeyEvent) = record("a11y", e)

    /** Клавиша, дошедшая до окна диалога. */
    fun onWindowKey(e: KeyEvent) {
        if (running) record("окно", e)
    }

    private fun record(source: String, e: KeyEvent, extra: String = "") {
        if (!running || e.repeatCount > 0) return
        synchronized(hits) { hits.add(Hit(source, e.keyCode, e.scanCode, e.action)) }
        line(
            "$source ${if (e.action == KeyEvent.ACTION_DOWN) "↓" else "↑"} ${e.keyCode} " +
                "${KeyEvent.keyCodeToString(e.keyCode)} scan=${e.scanCode} dev=${e.deviceId} " +
                "src=0x${Integer.toHexString(e.source)}$extra"
        )
    }

    private fun line(text: String) {
        val sec = (SystemClock.uptimeMillis() - startedAt) / 1000.0
        val s = String.format(Locale.US, "%5.1f с  %s", sec, text)
        synchronized(live) {
            live.add(s)
            while (live.size > 400) live.removeAt(0)
        }
        Log.i(TAG, s)
        changed()
    }

    private fun changed() {
        onChanged?.let { main.post(it) }
    }

    private fun run(app: Context) {
        val sb = StringBuilder()
        val since = LOGCAT_STAMP.format(Date())
        sb.append("# wheel keys probe ").append(STAMP.format(Date())).append('\n')
        try {
            // Фильтр ставится на главном потоке — дать ему примениться.
            SystemClock.sleep(500)
            val a11y = NaviAccessibilityService.instance
            val filter = a11y?.keyFilterState() ?: "служба спец. возможностей не привязана"
            sb.append("a11y: ").append(filter).append('\n')
            line("a11y: $filter")

            val kp = KeyPolicyTap.register(app) { e, name -> record("key_policy", e, " cb=$name") }
            sb.append("key_policy: ").append(kp).append('\n')
            line("key_policy: $kp")

            val adb = AdbShell.exec(app, "echo ok")?.trim()
            sb.append("adb: ").append(adb ?: "нет (${AdbShell.lastStatus})").append('\n')
            if (adb == "ok") {
                // Сырые события — в фоне до конца пробы (сессия adb столько не живёт).
                AdbShell.exec(
                    app,
                    "rm -f $GETEVENT_FILE; setsid sh -c 'timeout ${DURATION_MS / 1000 + 2} getevent -lt > $GETEVENT_FILE 2>&1' " +
                        "< /dev/null > /dev/null 2>&1 &"
                )
                line("adb: getevent запущен")
            } else {
                line("adb недоступен — будут только a11y / окно / key_policy")
            }

            val end = startedAt + DURATION_MS
            while (running && SystemClock.uptimeMillis() < end) {
                val left = (end - SystemClock.uptimeMillis()) / 1000
                status = "идёт проверка (ещё $left с): нажмите по очереди все кнопки на руле — каждую коротко, кнопку голоса ещё и долго"
                changed()
                SystemClock.sleep(1000)
            }
            running = false
            WheelKeys.applyFilterAsync()
            status = "собираю отчёт…"
            changed()
            KeyPolicyTap.unregister(app)

            sb.append("\n== живой журнал (a11y / окно / key_policy)\n")
            synchronized(live) { live.forEach { sb.append(it).append('\n') } }

            sb.append("\n== сводка по источникам (keyCode: имя)\n")
            val bySource = synchronized(hits) { hits.toList() }.groupBy { it.source }
            for (src in listOf("a11y", "окно", "key_policy")) {
                val codes = bySource[src].orEmpty().map { it.keyCode to it.scanCode }.distinct()
                sb.append(src).append(": ")
                    .append(codes.joinToString(", ") { "${it.first} ${KeyEvent.keyCodeToString(it.first)} (scan ${it.second})" }.ifEmpty { "ничего" })
                    .append('\n')
            }

            if (adb == "ok") {
                sb.append("\n== getevent -lt (сырые события ядра)\n")
                sb.append(shell(app, "sleep 2; cat $GETEVENT_FILE | grep -v -E 'EV_SYN|EV_MSC|ABS_|BTN_TOUCH' | tail -n 400"))
                sb.append("\n== getevent -pl (устройства ввода и их кнопки)\n")
                sb.append(shell(app, "getevent -pl 2>&1 | grep -v -E '^ +(ABS|0035|0036|0039|003a)' | head -n 150"))
                sb.append("\n== logcat системы за время пробы (клавиши / key_policy / голос)\n")
                sb.append(
                    shell(
                        app,
                        "logcat -d -v time -T '$since' | grep -i -E '$LOGCAT_FILTER' | grep -v -E '$LOGCAT_SKIP' | tail -n 600"
                    )
                )
            }
            val file = save(app, sb)
            val seen = bySource.keys.joinToString(", ").ifEmpty { "нигде" }
            status = "проверка закончена (нажатия видны: $seen). Отчёт: ${file.absolutePath}"
            line("отчёт: ${file.absolutePath}")
        } catch (e: Throwable) {
            Log.w(TAG, "probe", e)
            status = "ошибка пробы: ${e.javaClass.simpleName}: ${e.message}"
            runCatching { save(app, sb.append("\nОШИБКА: ").append(Log.getStackTraceString(e))) }
        } finally {
            running = false
            runCatching { KeyPolicyTap.unregister(app) }
            WheelKeys.applyFilterAsync()
            changed()
        }
    }

    fun stop() {
        running = false
    }

    private fun shell(app: Context, cmd: String): String {
        val out = AdbShell.exec(app, cmd) ?: return "(нет adb: ${AdbShell.lastStatus})\n"
        val text = out.ifBlank { "(пусто)" }
        return if (text.endsWith("\n")) text else "$text\n"
    }

    private fun save(app: Context, sb: StringBuilder): File {
        val dir = File(app.getExternalFilesDir(null), "wheel_keys").apply { mkdirs() }
        val file = File(dir, "keys_${FILE_STAMP.format(Date())}.txt")
        file.writeText(sb.toString())
        return file
    }

    /**
     * Свой колбэк в Desay key_policy (android.view.IKeyPolicy, системная
     * служба в system_server). Протокол — из boot-framework прошивки:
     * registerKeyCallBack = txn 1 (callback, name, int, String),
     * unRegisterKeyCallBack = txn 2 (name, String);
     * IKeyPolicyCallBack.callbackEvent = txn 1 (KeyEvent, String) → boolean.
     * Имя уникальное — штатные слоты (pre_handle*, power_key…) не трогаем;
     * отвечаем false, ничего не забираем.
     */
    private object KeyPolicyTap {
        private var service: IBinder? = null
        @Volatile private var listener: ((KeyEvent, String) -> Unit)? = null

        private val callback = object : Binder() {
            init {
                attachInterface(null, CB_DESCRIPTOR)
            }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(CB_DESCRIPTOR)
                val e = if (data.readInt() != 0) KeyEvent.CREATOR.createFromParcel(data) else null
                val name = data.readString().orEmpty()
                try {
                    if (e != null) listener?.invoke(e, name)
                } catch (t: Throwable) {
                    Log.w(TAG, "callbackEvent", t)
                }
                reply?.writeNoException()
                reply?.writeInt(0)
                return true
            }
        }

        fun register(app: Context, l: (KeyEvent, String) -> Unit): String {
            val b = try {
                Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "key_policy") as IBinder?
            } catch (e: Throwable) {
                return "нет доступа к ServiceManager: ${e.javaClass.simpleName}"
            } ?: return "службы key_policy нет"
            listener = l
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeStrongBinder(callback)
                data.writeString(NAME)
                data.writeInt(0)
                data.writeString(app.packageName)
                b.transact(1, data, reply, 0)
                reply.readException()
                service = b
                "колбэк «$NAME» зарегистрирован"
            } catch (e: Throwable) {
                listener = null
                "registerKeyCallBack: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun unregister(app: Context) {
            val b = service ?: return
            service = null
            listener = null
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(NAME)
                data.writeString(app.packageName)
                b.transact(2, data, reply, 0)
                reply.readException()
            } catch (e: Throwable) {
                Log.w(TAG, "unRegisterKeyCallBack", e)
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        private const val DESCRIPTOR = "android.view.IKeyPolicy"
        private const val CB_DESCRIPTOR = "android.view.IKeyPolicyCallBack"
        private const val NAME = "dash_wheel_probe"
    }

    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val LOGCAT_STAMP = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private const val TAG = "WheelKeyProbe"
    const val DURATION_MS = 60_000L
    private const val GETEVENT_FILE = "/data/local/tmp/dash_getevent.txt"
    private const val LOGCAT_FILTER =
        "keypolicy|keydelaytest|interceptkey|keycode|keyevent|svplatform|onkeycode|carstatemanager|" +
            "iflytek|autofly|wakeup|voice|speech|vrservice|vr_|\\bvr\\b|steer|swc"
    private const val LOGCAT_SKIP = "WheelKeyProbe| W Binder  :|^\\s+at "
}
