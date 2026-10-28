package com.dash.headunit.navi

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * Shell без root и без ПК: свой adb-клиент к сетевому adbd самого ГУ
 * (127.0.0.1:5555, на ГУ включён persist.adb.tcp.port=5555). Так делает Jarvis.
 *
 * Первый вход: adbd не знает наш ключ — на экране ГУ появляется
 * «Разрешить отладку?». Пользователь отмечает «Всегда» и разрешает;
 * дальше подключение молча. Права — как у `adb shell` (uid shell):
 * `wm overscan`, `am`, `pm grant`, `appops`.
 *
 * Все вызовы блокирующие — только из фонового потока.
 */
object AdbShell {

    @Volatile
    var lastStatus: String = "не подключались"
        private set(value) {
            field = value
            Log.i(TAG, value)
            // История статусов — для отчёта «Логи для Claude».
            synchronized(history) {
                history.add("${System.currentTimeMillis()} $value")
                while (history.size > HISTORY_MAX) history.removeAt(0)
            }
        }

    private val history = mutableListOf<String>()

    /** Последние статусы канала (unix-время мс + текст). */
    fun statusHistory(): List<String> = synchronized(history) { history.toList() }

    private var conn: Connection? = null

    /** Открыт ли порт adbd (без авторизации, быстро). */
    fun isPortOpen(): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress(HOST, PORT), 1500) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Выполняет [cmd] в shell и возвращает вывод (stdout+stderr, если
     * команда сама их сливает) или null, если канал недоступен.
     * [authTimeoutMs] — сколько ждать, пока пользователь разрешит отладку.
     */
    @Synchronized
    fun exec(context: Context, cmd: String, authTimeoutMs: Int = AUTH_WAIT_MS): String? {
        for (attempt in 0..1) {
            val c = conn ?: try {
                Connection.open(keys(context), authTimeoutMs).also {
                    conn = it
                    lastStatus = "подключено (${it.banner})"
                }
            } catch (e: Exception) {
                Log.w(TAG, "connect", e)
                lastStatus = "connect: ${e.javaClass.simpleName}: ${e.message}"
                return null
            }
            try {
                return c.shell(cmd)
            } catch (e: Exception) {
                // Соединение могло умереть (рестарт adbd) — одна повторная попытка.
                Log.w(TAG, "shell", e)
                lastStatus = "shell: ${e.javaClass.simpleName}: ${e.message}"
                c.close()
                conn = null
            }
        }
        return null
    }

    /** Ключ RSA 2048 в filesDir: один на установку, его и разрешает пользователь. */
    private fun keys(context: Context): KeyPair {
        val priv = File(context.filesDir, "adb_key.der")
        val pub = File(context.filesDir, "adb_key_pub.der")
        if (priv.exists() && pub.exists()) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                return KeyPair(
                    kf.generatePublic(X509EncodedKeySpec(pub.readBytes())),
                    kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes()))
                )
            } catch (e: Exception) {
                Log.w(TAG, "keys broken, regenerating", e)
            }
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        priv.writeBytes(kp.private.encoded)
        pub.writeBytes(kp.public.encoded)
        return kp
    }

    /** Одно соединение с adbd по протоколу adb (CNXN/AUTH/OPEN/WRTE/OKAY/CLSE). */
    private class Connection(
        private val socket: Socket,
        private val input: DataInputStream,
        private val output: OutputStream,
        val banner: String
    ) {
        private var nextLocalId = 1

        fun shell(cmd: String): String {
            val localId = nextLocalId++
            send(A_OPEN, localId, 0, "shell:$cmd\u0000".toByteArray())
            val out = StringBuilder()
            var remoteId = 0
            while (true) {
                val m = read()
                if (m.arg1 != localId) continue
                when (m.cmd) {
                    A_OKAY -> remoteId = m.arg0
                    A_WRTE -> {
                        out.append(String(m.data))
                        send(A_OKAY, localId, m.arg0, ByteArray(0))
                    }
                    A_CLSE -> {
                        // Без OPEN-ответа (remoteId == 0) это отказ сервиса.
                        if (remoteId != 0) send(A_CLSE, localId, remoteId, ByteArray(0))
                        return out.toString()
                    }
                }
            }
        }

        fun close() {
            try {
                socket.close()
            } catch (e: Exception) {
                // Уже закрыт.
            }
        }

        private fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray) =
            writeMessage(output, cmd, arg0, arg1, data)

        private fun read(): Message = readMessage(input)

        companion object {
            fun open(keys: KeyPair, authTimeoutMs: Int): Connection {
                val socket = Socket()
                socket.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
                socket.soTimeout = IO_TIMEOUT_MS
                val input = DataInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                try {
                    writeMessage(output, A_CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
                    var sentSignature = false
                    while (true) {
                        val m = readMessage(input)
                        when (m.cmd) {
                            A_CNXN -> {
                                socket.soTimeout = IO_TIMEOUT_MS
                                return Connection(socket, input, output, String(m.data).trim('\u0000'))
                            }
                            A_AUTH -> if (m.arg0 != AUTH_TOKEN) {
                                throw IllegalStateException("AUTH type ${m.arg0}")
                            } else if (!sentSignature) {
                                writeMessage(output, A_AUTH, AUTH_SIGNATURE, 0, sign(keys, m.data))
                                sentSignature = true
                            } else {
                                // Ключ неизвестен — отдаём публичный, adbd спросит пользователя.
                                lastStatus = "ждём «Разрешить отладку» на экране ГУ"
                                writeMessage(output, A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob(keys))
                                socket.soTimeout = authTimeoutMs
                            }
                        }
                    }
                } catch (e: Exception) {
                    socket.close()
                    throw e
                }
            }

            /** Подпись токена: RSA PKCS#1 v1.5 над «уже посчитанным» SHA-1 (как adbd). */
            private fun sign(keys: KeyPair, token: ByteArray): ByteArray {
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(Cipher.ENCRYPT_MODE, keys.private)
                return cipher.doFinal(SHA1_DIGEST_INFO + token)
            }

            private fun publicKeyBlob(keys: KeyPair): ByteArray =
                "${adbPublicKey(keys.public as RSAPublicKey)} dash@headunit\u0000".toByteArray()
        }
    }

    /**
     * Публичный ключ в формате adb (struct RSAPublicKey из mincrypt):
     * len, n0inv, n[], rr[], e — little-endian слова, в base64.
     * Тот же формат, что печатает `adb pubkey` и хранит adb_keys.
     */
    internal fun adbPublicKey(key: RSAPublicKey): String {
        val words = KEY_BITS / 32
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n = key.modulus
        val rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
        val buf = ByteBuffer.allocate(4 * (3 + 2 * words)).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv.toInt())
        putWords(buf, n, words)
        putWords(buf, rr, words)
        buf.putInt(key.publicExponent.toInt())
        return Base64.getEncoder().encodeToString(buf.array())
    }

    private fun putWords(buf: ByteBuffer, value: BigInteger, words: Int) {
        var v = value
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        repeat(words) {
            buf.putInt(v.and(mask).toLong().toInt())
            v = v.shiftRight(32)
        }
    }

    private class Message(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun writeMessage(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(cmd)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(data.size)
        header.putInt(data.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) })
        header.putInt(cmd xor -1)
        out.write(header.array())
        out.write(data)
        out.flush()
    }

    private fun readMessage(input: DataInputStream): Message {
        val header = ByteArray(24)
        input.readFully(header)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int
        val arg0 = b.int
        val arg1 = b.int
        val len = b.int
        b.int // checksum: в новых версиях протокола не проверяется
        val magic = b.int
        if (magic != cmd xor -1) throw IllegalStateException("bad adb magic")
        val data = ByteArray(len)
        input.readFully(data)
        return Message(cmd, arg0, arg1, data)
    }

    private const val TAG = "AdbShell"
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555
    private const val HISTORY_MAX = 50
    private const val KEY_BITS = 2048

    private const val CONNECT_TIMEOUT_MS = 1500
    private const val IO_TIMEOUT_MS = 15_000
    private const val AUTH_WAIT_MS = 90_000

    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_CLSE = 0x45534c43
    private const val A_WRTE = 0x45545257
    private const val VERSION = 0x01000000
    private const val MAX_DATA = 256 * 1024

    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSAPUBLICKEY = 3

    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )
}
