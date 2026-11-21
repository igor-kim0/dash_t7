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

    /** Нужно ли прятать штатные приборы (тема «Современная» и наше окно видно). */
    fun setWanted(on: Boolean) {
        if (wanted == on) return
        wanted = on
        Log.i(TAG, "нужно прятать: $on")
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
                try {
                    session()
                } catch (e: Exception) {
                    Log.w(TAG, "сессия", e)
                    state = "обрыв: ${e.javaClass.simpleName}"
                }
                if (wanted) sleepQuietly(RETRY_MS)
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
        setWanted(true)
    }

    /** Одна sh-сессия на приборке: живёт, пока нужно прятать и сокет цел. */
    private fun session() {
        val sh = QnxLauncher.find("sh") ?: run {
            state = "нет sh на приборке"
            return
        }
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
            send(SCRIPT)
            state = "прячу штатные приборы"

            var hidden = true
            var lastPing = SystemClock.uptimeMillis()
            var noSpeedSince = 0L
            var engineOn = engineRunning()
            var rehideAt = 0L
            while (true) {
                val cmd = commands.poll(TICK_MS, TimeUnit.MILLISECONDS)
                drain()
                if (cmd == CMD_STOP || !wanted) {
                    send("s")
                    // Дать sh дописать 1 до закрытия сокета.
                    val until = SystemClock.uptimeMillis() + RUN_S * 1000 + 1500
                    while (SystemClock.uptimeMillis() < until) {
                        try {
                            drain()
                        } catch (e: java.io.EOFException) {
                            break // sh дописала и вышла
                        }
                        if (state.endsWith("возвращены")) break
                    }
                    return
                }
                val now = SystemClock.uptimeMillis()

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
                        send("w 1")
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
        if (cmd != null && cmd != CMD_STOP) commands.offer(cmd)
    }

    private const val UIT = "/emmc/uit"
    private const val KEY = "System.InitialStartUpInt"
    /** Утилита может не завершаться сама — снимаем через RUN_S с. */
    private const val RUN_S = 3
    private const val MARK_HIDDEN = "DASH_HIDDEN"
    private const val MARK_SHOWN = "DASH_SHOWN"

    /**
     * Весь скрипт — одна группа `{ … }`: sh разбирает её целиком до
     * выполнения, и `read` дальше читает из сокета только наши команды:
     * t — спрятать (1 → 0), w 1 — показать, p — пинг, s — показать и выйти.
     * Конец потока (сокет закрыт) — показать и выйти.
     */
    private val SCRIPT = """
        { export LD_LIBRARY_PATH=$UIT/lib:${'$'}LD_LIBRARY_PATH
        cd $UIT/etc
        w() { $UIT/bin/uit.test.data-controller "--setint=$KEY#${'$'}1" >/dev/null 2>&1 & P=${'$'}!; sleep $RUN_S; kill ${'$'}P 2>/dev/null; }
        trap 'w 1; echo $MARK_SHOWN; exit' HUP TERM INT
        w 1; sleep 1; w 0; echo $MARK_HIDDEN
        while read C; do
          case "${'$'}C" in
            t) w 1; sleep 1; w 0; echo $MARK_HIDDEN ;;
            "w 1") w 1 ;;
            s) break ;;
          esac
        done
        w 1; echo $MARK_SHOWN; exit; }
    """.trimIndent()

    private const val CMD_STOP = "stop"
    private const val TICK_MS = 1000L
    private const val PING_MS = 5000L
    private const val RETRY_MS = 10_000L
    private const val NO_SPEED_MS = 5000L
    private const val REHIDE_DELAY_MS = 4000L
    private const val STDIN_DELAY_MS = 1300L
    private const val TAG = "ClusterGauges"
}
