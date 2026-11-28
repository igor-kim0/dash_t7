package com.dash.headunit.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.dash.headunit.elm.ElmHub
import com.dash.headunit.settings.AppSettings

/**
 * Автоподключение сохранённых телефонов.
 *
 * Штатный стек ГУ ищет сопряжённые устройства лишь первые минуты после
 * старта: сел в машину позже (запуск с сигнализации) — телефон не
 * подключается. Здесь, пока ни одно отмеченное устройство
 * ([AppSettings.btAutoConnect]) не подключено по HFP, раз в [INTERVAL_MS]
 * пробуем подключить следующее (скрытые `connect` HFP-клиента и
 * A2DP-приёмника, как ручное подключение в диалоге Bluetooth).
 * Классический BT не видит «появление» телефона пассивно — сама попытка
 * подключения и есть поиск.
 *
 * Устройство, отключённое вручную из диалога ([pauseUntilRestart]),
 * не переподключаем до перезапуска процесса.
 */
object BtAutoConnect {
    /** BluetoothProfile.HEADSET_CLIENT / A2DP_SINK (@hide). */
    const val HEADSET_CLIENT = 16
    const val A2DP_SINK = 11

    private const val INTERVAL_MS = 20_000L
    private const val ACTION_HFC_STATE =
        "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private val proxies = HashMap<Int, BluetoothProfile>()
    private val paused = HashSet<String>()
    private var next = 0

    private val tick = Runnable { attempt() }

    fun start(context: Context) {
        if (app != null) return
        val ctx = context.applicationContext
        app = ctx
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                proxies[profile] = proxy
                kick()
            }

            override fun onServiceDisconnected(profile: Int) {
                proxies.remove(profile)
            }
        }
        runCatching { adapter.getProfileProxy(ctx, listener, HEADSET_CLIENT) }
        runCatching { adapter.getProfileProxy(ctx, listener, A2DP_SINK) }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(ACTION_HFC_STATE)
        }
        runCatching { ctx.registerReceiver(receiver, filter) }
        schedule(INTERVAL_MS)
    }

    /** Попробовать сейчас (сменился список отмеченных, включили BT). */
    fun kick() {
        schedule(1_000)
    }

    /** Отключено вручную — не переподключать до перезапуска приложения. */
    fun pauseUntilRestart(address: String) {
        paused.add(address)
    }

    /** Снять паузу (флажок включён заново / подключено вручную). */
    fun unpause(address: String) {
        paused.remove(address)
    }

    /** Скрытый connect/disconnect профиля; true — стек принял команду. */
    fun call(proxy: BluetoothProfile, method: String, device: BluetoothDevice): Boolean =
        runCatching {
            proxy.javaClass.getMethod(method, BluetoothDevice::class.java).invoke(proxy, device) as Boolean
        }.getOrDefault(false)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) kick()
                // Телефон отвалился — снова пробуем, не дожидаясь полного интервала.
                ACTION_HFC_STATE ->
                    if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) ==
                        BluetoothProfile.STATE_DISCONNECTED
                    ) schedule(5_000)
            }
        }
    }

    private fun schedule(delayMs: Long) {
        main.removeCallbacks(tick)
        main.postDelayed(tick, delayMs)
    }

    @SuppressLint("MissingPermission")
    private fun attempt() {
        schedule(INTERVAL_MS)
        val ctx = app ?: return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (adapter.state != BluetoothAdapter.STATE_ON) return
        val hfp = proxies[HEADSET_CLIENT] ?: return
        val wanted = AppSettings.btAutoConnect(ctx)
        if (wanted.isEmpty()) return
        val elm = ElmHub.address()
        val candidates = runCatching { adapter.bondedDevices.toList() }.getOrDefault(emptyList())
            .filter { it.address in wanted && it.address != elm && it.address !in paused }
            .sortedBy { it.address }
        if (candidates.isEmpty()) return
        // Телефон уже подключён (любой, в т.ч. неотмеченный) — не перебиваем.
        if (runCatching { hfp.connectedDevices.isNotEmpty() }.getOrDefault(false)) return
        val states = candidates.map { state(hfp, it) }
        // Уже подключён (или подключается) один из отмеченных — ждём.
        if (states.any { it == BluetoothProfile.STATE_CONNECTED || it == BluetoothProfile.STATE_CONNECTING }) return
        val device = candidates[next % candidates.size]
        next++
        // Сначала звонки (HFP), затем музыка (A2DP) — как штатное подключение.
        call(hfp, "connect", device)
        proxies[A2DP_SINK]?.let { call(it, "connect", device) }
    }

    private fun state(proxy: BluetoothProfile, device: BluetoothDevice): Int =
        runCatching { proxy.getConnectionState(device) }.getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
}
