package com.dash.headunit.car

import android.content.Context
import android.os.SystemClock
import com.dash.headunit.settings.AppSettings

/**
 * Вход в инженерное меню (ETM) приборки ради температуры ОЖ.
 *
 * Из реверса sva.cluster (QNX-хост 192.168.2.1, образ qnx_system из OTA
 * Cherry Inside, см. «tenet t7/cherry inside info/07_qnx/QNX_SYSTEM.md»):
 * MCU шлёт настоящие °C в /var/pps/spi_service/a0ff (ETM MCU data 2:
 * b14|b15 × 0.75 − 48) только после «входа в ETM», то есть после
 * RcDataSendETMEntryState(1) — в PPS-объект /var/pps/spi_service/tx при
 * этом пишется атрибут xA101 = [0, 0xA1, 0x01, 52, 0] + rc_msg_send_t
 * {тип 0x37, …, state, 0…}. Без входа на T18FL3 a0ff не появляется —
 * у ОЖ только «глупая» стрелка (см. [ClusterParser]).
 *
 * [ClusterPps.session] зовёт [ensureEnter] при каждом подключении к
 * приборке — с троттлингом [RESEND_MS]; если MCU сбросил состояние после
 * сна/цикла зажигания, вход повторяется, и a0ff снова появляется в
 * потоке, который читает [ClusterPps]. Это запись в MCU — осознанная:
 * включается настройкой (по умолчанию вкл), выключение настройки шлёт
 * выход (state=0). Все вызовы блокирующие — только из фонового потока.
 */
object CoolantEtm {

    /** Где команда (PPS tx) и данные MCU (a0fe/a0ff). */
    internal const val DIR = "/var/pps/spi_service"

    /** Атрибут xA101 = команда 0xA101 (RcDataSendThread, тип сообщения < 0x200). */
    private const val CMD = 0xA101
    /** sizeof(rc_msg_send_t). */
    private const val MSG_LEN = 52
    /** rc_msg_send_type для RcDataSendETMEntryState. */
    private const val MSG_ETM_ENTRY = 0x37

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Последний результат записи в tx — для диагностики. */
    @Volatile
    var lastStatus: String = "не посылался"
        private set

    /**
     * Вход в ETM, если разрешено настройкой и троттлинг вышел. Возвращает
     * сообщение для истории статусов ([ClusterPps.logEvent]); пустое —
     * тихо пропущено (выключено настройкой или внутри троттлинга).
     */
    fun ensureEnter(): String {
        val ctx = appContext ?: return ""
        val on = runCatching { AppSettings.etmCoolant(ctx) }.getOrDefault(true)
        if (!on) return ""
        synchronized(this) {
            val now = SystemClock.uptimeMillis()
            if (now - lastSentAt < RESEND_MS) return ""
            lastSentAt = now
            return if (send(1)) "ETM: вход послан ради a0ff" else lastStatus
        }
    }

    /**
     * Записать вход (state=1) или выход (0) из ETM в tx и сверить эхо.
     * Блокирующий; true — эхо подтверждено.
     */
    fun send(state: Int): Boolean {
        if (!QnxLauncher.reachable()) {
            lastStatus = "ETM: qconn не отвечает"
            return false
        }
        return try {
            val word = if (state == 1) "вход" else "выход"
            val out = QnxLauncher.sh("echo '${entry(state)}' >> $DIR/tx\ncat $DIR/tx\n", 8000)
            when {
                out == null -> {
                    lastStatus = "ETM: на приборке нет sh"
                    false
                }
                "xA101" in out -> {
                    lastStatus = "ETM: $word записан в tx, эхо видно"
                    true
                }
                else -> {
                    lastStatus = "ETM: $word не подтвердился (в tx нет xA101)"
                    false
                }
            }
        } catch (e: Exception) {
            lastStatus = "ETM: ${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    /** Строка PPS для tx: вход (state=1) / выход (0) из ETM. */
    internal fun entry(state: Int): String {
        val msg = IntArray(MSG_LEN)
        msg[0] = MSG_ETM_ENTRY
        msg[4] = 1
        msg[5] = state
        val data = intArrayOf(0, CMD shr 8, CMD and 0xFF, MSG_LEN, 0) + msg
        return "xA101:json:{\"data\":[${data.joinToString(",")}]}"
    }

    private var lastSentAt = 0L

    /** Минимальный интервал между записями в tx (см. [ensureEnter]). */
    internal const val RESEND_MS = 5 * 60_000L
}
