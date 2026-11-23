package com.dash.headunit.navi.cluster

import android.os.SystemClock
import android.util.Log
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.ClusterPps
import com.dash.headunit.car.QnxLauncher
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Скрытие штатных скорости, оборотов и угловых дуг приборки для темы
 * «Современная» (ГУ 2026-11-21, проба diag/ClusterGaugesTest: работает,
 * верхняя и нижняя строки остаются).
 *
 * Ключ модели данных Kanzi `System.InitialStartUpInt`: 0 → приборы уходят
 * (ThemeInOutStateInt = 0), 1 → возвращаются. Пишет штатная утилита Desay
 * uit.test.data-controller в sh приборки через qconn — без записи в MCU.
 *
 * Безопасность — водитель не должен остаться без спидометра:
 *  - одна постоянная sh-сессия; её stdin — наш сокет. Приложение упало или
 *    сокет оборвался → `read` получает конец потока (или sh — HUP/TERM от
 *    qconn) → сама sh пишет 1, шкалы возвращаются;
 *  - нет скорости ни от приборки, ни от CarHub дольше [NO_SPEED_MS] —
 *    возвращаем штатные, пока данные не появятся;
 *  - убрали своё окно / сменили тему / калибровка / Яндекс на приборке —
 *    [setWanted] false → 1;
 *  - в худшем случае (SIGKILL sh) приборы вернёт штатно следующее
 *    включение зажигания.
 *
 * Включение зажигания само возвращает приборы (EnteringIGNONTrigerAni,
 * а наш ключ остаётся 0 — запись 0 перехода не даст), после перезагрузки
 * приборки sva.cluster пишет 1 после самотеста стрелок. Поэтому прячем
 * всегда парой 1 → 0 — при подключении и через [REHIDE_DELAY_MS] после
 * пуска двигателя (обороты появились).
 *
 * Режим «всё» ([setWanted] all — любая своя тема): ключ `System.IGNState`
 * (Global.MarkEnum Default/OFF/ON) = 0 прячет весь штатный слой Root/IGNON —
 * ещё и виньетку по краям, верхнюю и нижнюю строки и ТЕКСТОВЫЕ
 * ПРЕДУПРЕЖДЕНИЯ; лампы остаются (docs/research/cluster_theme.md, разбор
 * 2026-11-22, на ГУ не проверено). Вернуть — ON. sva.cluster пишет ключ
 * только при смене питания, поэтому после пуска двигателя прячем снова.
 * Приборы в этом режиме прячем ещё и InitialStartUpInt 1 → 0 (проверено на
 * ГУ): до 2026-11-23 «всё» писало только IGNState — на ГУ не спряталось
 * ничего. Enum утилита принимает только номером (`--setenum=…#0`, stoi).
 */
object ClusterGauges {

    @Volatile
    private var wanted = false

    @Volatile
    private var worker: Thread? = null

    /** Состояние для калибровки/диагностики. */
    @Volatile
    var state: String = "выкл"
        private set

    private val commands = LinkedBlockingQueue<String>()

    /** Прятать всё штатное (IGNState), а не только приборы. */
    @Volatile
    private var full = false

    /**
     * Нужно ли прятать штатные приборы (своя тема и наше окно видно);
     * [all] — прятать весь штатный слой.
     */
    fun setWanted(on: Boolean, all: Boolean = false) {
        if (on && wanted && full != all) {
            // Сменился режим: текущая сессия вернёт штатное и выйдет, цикл поднимет новую.
            Log.i(TAG, "режим: ${if (all) "всё" else "приборы"}")
            full = all
            commands.offer(CMD_RESTART)
            return
        }
        if (on) full = all
        if (wanted == on) return
        wanted = on
        Log.i(TAG, "нужно прятать: $on${if (all) " (всё)" else ""}")
        if (on) {
            if (worker == null) {
                worker = Thread(::loop, "ClusterGauges").apply {
                    isDaemon = true
                    start()
                }
            }
        } else {
            commands.offer(CMD_STOP)
        }
    }

    private fun loop() {
        try {
            while (wanted) {
                if (!QnxLauncher.reachable()) {
                    state = "qconn не отвечает"
                    sleepQuietly(RETRY_MS)
                    continue
                }
                val restart = try {
                    session()
                } catch (e: Exception) {
                    Log.w(TAG, "сессия", e)
                    state = "обрыв: ${e.javaClass.simpleName}"
                    false
                }
                if (wanted && !restart) sleepQuietly(RETRY_MS)
            }
        } finally {
            worker = null
            state = "выкл"
            // Пока поток завершался, могли снова попросить — поднять заново.
            if (wanted) setWantedAgain()
        }
    }

    private fun setWantedAgain() {
        wanted = false
        setWanted(true, full)
    }

    /** Отправить «спрятать» ещё раз (сменили тему) — если сейчас прячем. */
    fun rehide() {
        if (wanted) commands.offer(CMD_HIDE)
    }

    /**
     * Одна sh-сессия на приборке: живёт, пока нужно прятать и сокет цел.
     * true — закрыта для смены режима (новую поднять сразу).
     */
    private fun session(): Boolean {
        val sh = QnxLauncher.find("sh") ?: run {
            state = "нет sh на приборке"
            return false
        }
        val all = full
        commands.clear()
        Socket().use { socket ->
            val out = QnxLauncher.start(socket, sh, listOf("sh"))
            socket.soTimeout = 200
            val input = socket.getInputStream()
            val buf = ByteArray(1024)
            fun drain() {
                // Читаем, пока есть вывод; таймаут сокета — больше нечего.
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) throw java.io.EOFException("sh закрыта")
                        val text = String(buf, 0, n, Charsets.ISO_8859_1)
                        if (MARK_HIDDEN in text) state = "штатные приборы спрятаны"
                        if (MARK_SHOWN in text) state = "штатные приборы возвращены"
                    }
                } catch (e: SocketTimeoutException) {
                    // нечего читать
                }
            }
            fun send(line: String) {
                out.write("$line\n".toByteArray(Charsets.US_ASCII))
                out.flush()
            }
            Thread.sleep(STDIN_DELAY_MS)
            send(script(all))
            state = if (all) "прячу всё штатное" else "прячу штатные приборы"

            var hidden = true
            var lastPing = SystemClock.uptimeMillis()
            var noSpeedSince = 0L
            var engineOn = engineRunning()
            var rehideAt = 0L
            while (true) {
                val cmd = commands.poll(TICK_MS, TimeUnit.MILLISECONDS)
                drain()
                if (cmd == CMD_STOP || cmd == CMD_RESTART || !wanted) {
                    send("s")
                    // Дать sh дописать 1 до закрытия сокета.
                    val until = SystemClock.uptimeMillis() + 2 * RUN_S * 1000 + 1500
                    while (SystemClock.uptimeMillis() < until) {
                        try {
                            drain()
                        } catch (e: java.io.EOFException) {
                            break // sh дописала и вышла
                        }
                        if (state.endsWith("возвращены")) break
                    }
                    return cmd == CMD_RESTART && wanted
                }
                val now = SystemClock.uptimeMillis()

                if (cmd == CMD_HIDE && hidden) {
                    send("t")
                    state = if (all) "прячу всё штатное (смена темы)" else "прячу штатные приборы (смена темы)"
                }

                // Скорости нет — штатный спидометр обратно, пока не появится.
                if (speedKnown()) {
                    noSpeedSince = 0L
                    if (!hidden) {
                        send("t")
                        hidden = true
                        state = "прячу штатные приборы (скорость снова есть)"
                    }
                } else {
                    if (noSpeedSince == 0L) noSpeedSince = now
                    if (hidden && now - noSpeedSince > NO_SPEED_MS) {
                        send("v")
                        hidden = false
                        state = "нет скорости — штатные приборы видны"
                    }
                }

                // Пуск двигателя (после включения зажигания приборы вернулись сами).
                val running = engineRunning()
                if (running && !engineOn) rehideAt = now + REHIDE_DELAY_MS
                engineOn = running
                if (rehideAt != 0L && now >= rehideAt) {
                    rehideAt = 0L
                    if (hidden) {
                        send("t")
                        state = "прячу снова после пуска двигателя"
                    }
                }

                if (now - lastPing >= PING_MS) {
                    send("p")
                    lastPing = now
                }
            }
        }
    }

    private fun speedKnown(): Boolean =
        ClusterPps.speedKmh() != null || CarHub.data?.speedKmh != null

    private fun engineRunning(): Boolean =
        ((ClusterPps.rpm() ?: CarHub.data?.rpm) ?: 0) > 300

    private fun sleepQuietly(ms: Long) {
        val cmd = commands.poll(ms, TimeUnit.MILLISECONDS)
        if (cmd != null && cmd != CMD_STOP && cmd != CMD_RESTART) commands.offer(cmd)
    }

    private const val UIT = "/emmc/uit"
    private const val KEY = "System.InitialStartUpInt"
    private const val IGN = "System.IGNState"
    /** Утилита может не завершаться сама — снимаем через RUN_S с. */
    private const val RUN_S = 3
    private const val MARK_HIDDEN = "DASH_HIDDEN"
    private const val MARK_SHOWN = "DASH_SHOWN"

    /**
     * Весь скрипт — одна группа `{ … }`: sh разбирает её целиком до
     * выполнения, и `read` дальше читает из сокета только наши команды:
     * t — спрятать, v — показать, p — пинг, s — показать и выйти.
     * Конец потока (сокет закрыт) — показать и выйти.
     * r — запустить утилиту с аргументами разом и снять через [RUN_S] с;
     * h — спрятать (приборы: 1 → 0, всё: IGNState 0), v — показать.
     */
    private fun script(all: Boolean): String {
        val d = '$'
        // --setenum утилита разбирает через stoi (дизассемблер main, 2026-11-23):
        // только номер — 0 Default, 2 ON; «#Default» её роняет.
        // Приборы — проверенным InitialStartUpInt 1 → 0, остальное — IGNState.
        val hv = if (all) {
            """
            h() { r "--setint=$KEY#1"; sleep 1; r "--setint=$KEY#0"; r "--setenum=$IGN#0"; }
            v() { r "--setenum=$IGN#2"; r "--setint=$KEY#1"; }
            """
        } else {
            """
            h() { r "--setint=$KEY#1"; sleep 1; r "--setint=$KEY#0"; }
            v() { r "--setint=$KEY#1"; }
            """
        }.trimIndent()
        return """
            { export LD_LIBRARY_PATH=$UIT/lib:${d}LD_LIBRARY_PATH
            cd $UIT/etc
            r() { P=""; for A in "${d}@"; do $UIT/bin/uit.test.data-controller "${d}A" >/dev/null 2>&1 & P="${d}P ${d}!"; done; sleep $RUN_S; kill ${d}P 2>/dev/null; }
            $hv
            trap 'v; echo $MARK_SHOWN; exit' HUP TERM INT
            h; echo $MARK_HIDDEN
            while read C; do
              case "${d}C" in
                t) h; echo $MARK_HIDDEN ;;
                v) v ;;
                s) break ;;
              esac
            done
            v; echo $MARK_SHOWN; exit; }
        """.trimIndent()
    }

    private const val CMD_STOP = "stop"
    private const val CMD_RESTART = "restart"
    private const val CMD_HIDE = "hide"
    private const val TICK_MS = 1000L
    private const val PING_MS = 5000L
    private const val RETRY_MS = 10_000L
    private const val NO_SPEED_MS = 5000L
    private const val REHIDE_DELAY_MS = 4000L
    private const val STDIN_DELAY_MS = 1300L
    private const val TAG = "ClusterGauges"
}
