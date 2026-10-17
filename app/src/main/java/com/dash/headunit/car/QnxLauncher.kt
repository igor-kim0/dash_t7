package com.dash.headunit.car

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Запуск программ на приборке (QNX) через qconn — служба удалённой отладки
 * QNX на 192.168.2.1:8000, доступная с ГУ по внутреннему Ethernet (eth0
 * ГУ = 192.168.2.2). Так приборку читает Jarvis: «service launcher» →
 * «start/flags 8000 <путь> <argv…>» → stdin/stdout процесса идут по сокету.
 *
 * Права root на ГУ не нужны — только сеть (INTERNET). Все вызовы
 * блокирующие, только из фонового потока.
 */
object QnxLauncher {

    /** Адрес qconn приборки; var — только чтобы тесты подставили фейковый сервер. */
    @Volatile
    var HOST = "192.168.2.1"
        internal set
    @Volatile
    var PORT = 8000
        internal set

    /** Где искать утилиты на QNX (порядок как у Jarvis). */
    private val DIRS = listOf(
        "/bin", "/usr/bin", "/proc/boot", "/sbin", "/usr/sbin",
        "/system/bin", "/xbin", "/usr/local/bin", "/mnt/bin", "/np/bin"
    )

    private val found = mutableMapOf<String, String>()
    private val missing = mutableSetOf<String>()

    /** Отвечает ли qconn (быстро, без запуска процессов). */
    fun reachable(): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Найденные пути утилит — для диагностики. */
    fun foundTools(): Map<String, String> = synchronized(found) { found.toMap() }

    /**
     * Полный путь утилиты [name] на приборке или null. Пробуем запустить
     * её из каждого каталога с безобидными аргументами; ответ «no such
     * file» значит «не здесь». Результат кешируется.
     */
    fun find(name: String): String? {
        synchronized(found) {
            found[name]?.let { return it }
            if (name in missing) return null
        }
        var connectionError = false
        for (dir in DIRS) {
            val path = "$dir/$name"
            try {
                run(path, probeArgs(name), null, PROBE_TIMEOUT_MS)
                synchronized(found) { found[name] = path }
                return path
            } catch (e: IllegalStateException) {
                // «No such file» — не здесь, следующий каталог.
            } catch (e: Exception) {
                // Сбой связи, а не отсутствие утилиты.
                connectionError = true
            }
        }
        // «Нет» запоминаем, только если ответили все каталоги: иначе один
        // обрыв qconn навсегда «терял» бы sh до перезапуска приложения.
        if (!connectionError) synchronized(found) { missing.add(name) }
        return null
    }

    /** Выполнить [script] в sh приборки и вернуть вывод. */
    fun sh(script: String, timeoutMs: Int = RUN_TIMEOUT_MS): String? {
        val sh = find("sh") ?: return null
        val out = run(sh, listOf("sh"), "$script\nexit\n".toByteArray(Charsets.US_ASCII), timeoutMs)
        return String(out, Charsets.ISO_8859_1)
    }

    /**
     * Запустить [path] с argv [args]; [stdin] пишется через 1.3 с после
     * старта (раньше процесс его теряет — так у Jarvis). Возвращает весь
     * вывод до завершения процесса или [timeoutMs].
     */
    fun run(path: String, args: List<String>, stdin: ByteArray?, timeoutMs: Int): ByteArray {
        Socket().use { socket ->
            val output = start(socket, path, args)
            if (stdin != null) {
                Thread.sleep(STDIN_DELAY_MS)
                output.write(stdin)
                output.flush()
            }
            socket.soTimeout = READ_SLICE_MS
            val input = socket.getInputStream()
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val n = try {
                    input.read(chunk)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                if (n < 0) break
                buf.write(chunk, 0, n)
            }
            val bytes = stripTelnet(buf.toByteArray())
            if (bytes.size < 4096) {
                val text = String(bytes, Charsets.US_ASCII).lowercase()
                if ("no such file" in text || "error no such" in text || "command not found" in text) {
                    throw IllegalStateException("launcher: ${text.replace('\n', ' ').trim()}")
                }
            }
            return bytes
        }
    }

    /**
     * Открыть процесс [path] на долгое чтение (поток данных приборки).
     * Вызывающий сам закрывает [socket].
     */
    fun start(socket: Socket, path: String, args: List<String>): OutputStream {
        socket.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
        val out = socket.getOutputStream()
        send(out, "service launcher\n")
        send(out, "chdir /\n")
        send(out, "start/flags 8000 $path ${args.joinToString(" ")}\n")
        send(out, "continue\n")
        return out
    }

    /**
     * Строка вывода без CR/LF; telnet-последовательности (IAC, 255 + 2
     * байта) пропускаются. null — конец потока.
     *
     * Если чтение прервано таймаутом сокета, уже прочитанное начало строки
     * остаётся в [sb] — передайте тот же буфер в следующий вызов, и строка
     * не порвётся.
     */
    fun readLine(input: InputStream, sb: StringBuilder = StringBuilder()): String? {
        var b: Int
        while (true) {
            b = input.read()
            when {
                b < 0 -> break
                b == 255 -> {
                    input.read()
                    input.read()
                }
                b == '\r'.code -> Unit
                b == '\n'.code -> break
                else -> sb.append(b.toChar())
            }
        }
        if (sb.isEmpty() && b < 0) return null
        return sb.toString().also { sb.setLength(0) }
    }

    private fun send(out: OutputStream, s: String) {
        out.write(s.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun stripTelnet(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == 0xFF.toByte()) {
                i += 3
                continue
            }
            out.write(bytes[i].toInt())
            i++
        }
        return out.toByteArray()
    }

    /** Аргументы, с которыми утилита сразу завершается (как у Jarvis). */
    private fun probeArgs(name: String): List<String> = when (name) {
        "sh" -> listOf("sh", "-c", "exit")
        "cat" -> listOf("cat", "/dev/null")
        "ls" -> listOf("ls", "/")
        "sleep" -> listOf("sleep", "0")
        else -> listOf(name)
    }

    private const val CONNECT_TIMEOUT_MS = 3000
    private const val PROBE_TIMEOUT_MS = 6000
    private const val RUN_TIMEOUT_MS = 15_000
    private const val READ_SLICE_MS = 1000
    private const val STDIN_DELAY_MS = 1300L
}
