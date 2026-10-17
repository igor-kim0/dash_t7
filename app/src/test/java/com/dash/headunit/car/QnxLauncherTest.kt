package com.dash.headunit.car

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Обмен с qconn на фейковом сервере: launcher получает 4 строки команд,
 * stdin процесса, отвечает выводом. Отсутствующая программа — «No such file».
 */
class QnxLauncherTest {

    private lateinit var server: ServerSocket
    private val commands = mutableListOf<String>()

    @Before
    fun setUp() {
        server = ServerSocket(0)
        QnxLauncher.HOST = "127.0.0.1"
        QnxLauncher.PORT = server.localPort
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    s.use {
                        val r = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val lines = (1..4).map { r.readLine() }
                        // Проверка reachable(): соединение без команд.
                        if (lines[0] == null) return@thread
                        synchronized(commands) { commands.addAll(lines) }
                        val start = lines[2]
                        val out = it.getOutputStream()
                        when {
                            // Утилита для проверки кеша find(): есть только в /bin.
                            start.startsWith("start/flags 8000 /bin/flaky ") -> Unit
                            !start.startsWith("start/flags 8000 /bin/sh ") ->
                                out.write("Error No such file or directory\n".toByteArray())
                            start.contains(" sh -c exit") -> Unit
                            start.endsWith(" sh") -> {
                                val script = r.readLine()
                                // IAC-последовательность в выводе должна вырезаться.
                                out.write(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x01))
                                out.write("ran:$script\n".toByteArray())
                            }
                        }
                        out.flush()
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        server.close()
        QnxLauncher.HOST = "192.168.2.1"
        QnxLauncher.PORT = 8000
    }

    @Test
    fun launcherProtocolAndFind() {
        assertEquals(true, QnxLauncher.reachable())
        assertEquals("/bin/sh", QnxLauncher.find("sh"))
        val out = QnxLauncher.sh("echo hi")
        assertEquals("ran:echo hi\n", out)
        synchronized(commands) {
            assertEquals("service launcher", commands[0])
            assertEquals("chdir /", commands[1])
            assertEquals("continue", commands[3])
        }
    }

    @Test
    fun findReturnsNullWhenMissingEverywhere() {
        assertNull(QnxLauncher.find("definitely_missing_tool"))
    }

    @Test
    fun readLineSkipsTelnetAndCr() {
        val input = ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 1, 2) + "ab\r\ncd".toByteArray())
        assertEquals("ab", QnxLauncher.readLine(input))
        assertEquals("cd", QnxLauncher.readLine(input))
        assertNull(QnxLauncher.readLine(input))
    }

    @Test
    fun findDoesNotCacheMissingOnConnectionError() {
        val port = QnxLauncher.PORT
        // Порт, на котором никто не слушает: connect refused.
        QnxLauncher.PORT = ServerSocket(0).use { it.localPort }
        assertNull(QnxLauncher.find("flaky"))
        QnxLauncher.PORT = port
        assertEquals("/bin/flaky", QnxLauncher.find("flaky"))
    }

    @Test
    fun readLineKeepsPartialLineAcrossTimeout() {
        // «ab», таймаут сокета, затем «cd\n».
        val input = object : InputStream() {
            val bytes = "ab|cd\n".toByteArray()
            var i = 0
            override fun read(): Int {
                if (i >= bytes.size) return -1
                val b = bytes[i++].toInt()
                if (b == '|'.code) throw SocketTimeoutException()
                return b
            }
        }
        val partial = StringBuilder()
        try {
            QnxLauncher.readLine(input, partial)
            fail("ожидался таймаут")
        } catch (e: SocketTimeoutException) {
            // Как в потоке записи: читаем дальше с тем же буфером.
        }
        assertEquals("abcd", QnxLauncher.readLine(input, partial))
        assertNull(QnxLauncher.readLine(input, partial))
    }
}
