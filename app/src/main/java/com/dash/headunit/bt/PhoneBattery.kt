package com.dash.headunit.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Заряд подключённого телефона.
 *
 * ГУ — HFP-клиент (штатный com.android.bluetooth/.hfpclient): телефон шлёт
 * индикатор батареи «battchg» 0..5, стек рассылает его в
 * `headsetclient…AG_EVENT` (нужно только обычное право BLUETOOTH — его же
 * слушает SystemUI ГУ). Событие приходит лишь при смене уровня, поэтому
 * текущее значение при старте берём у профиля (скрытый
 * `BluetoothHeadsetClient.getCurrentAgEvents`, рефлексией). Если стек даёт
 * точный процент (`BluetoothDevice.getBatteryLevel` / BATTERY_LEVEL_CHANGED),
 * предпочитаем его. Шкала HFP грубая: 0, 20, … 100 %.
 */
object PhoneBattery {
    private const val TAG = "PhoneBattery"

    /** BluetoothProfile.HEADSET_CLIENT (@hide). */
    private const val HEADSET_CLIENT = 16
    private const val ACTION_AG_EVENT =
        "android.bluetooth.headsetclient.profile.action.AG_EVENT"
    private const val ACTION_HFC_STATE =
        "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"
    private const val EXTRA_AG_BATTERY = "android.bluetooth.headsetclient.extra.BATTERY_LEVEL"
    private const val ACTION_DEV_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private const val EXTRA_DEV_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"

    /** Процент 0..100 или -1 — неизвестно / телефон не подключён. */
    @Volatile
    var percent: Int = -1
        private set

    // Вызываются на главном потоке при смене [percent].
    private val listeners = LinkedHashSet<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var proxy: BluetoothProfile? = null

    // Точный процент от стека имеет приоритет над шкалой HFP.
    private var exact = -1
    private var hfp = -1

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val filter = IntentFilter().apply {
            addAction(ACTION_AG_EVENT)
            addAction(ACTION_HFC_STATE)
            addAction(ACTION_DEV_BATTERY)
        }
        runCatching { app.registerReceiver(receiver, filter) }
            .onFailure { Log.w(TAG, "receiver", it) }
        runCatching {
            BluetoothAdapter.getDefaultAdapter()
                ?.getProfileProxy(app, proxyListener, HEADSET_CLIENT)
        }.onFailure { Log.w(TAG, "proxy", it) }
    }

    /** Перечитать текущее значение у профиля (onResume дашборда). */
    fun refresh() {
        query()
    }

    private val proxyListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
            proxy = p
            query()
        }

        override fun onServiceDisconnected(profile: Int) {
            proxy = null
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_AG_EVENT -> if (intent.hasExtra(EXTRA_AG_BATTERY)) {
                    hfp = hfpToPercent(intent.getIntExtra(EXTRA_AG_BATTERY, -1))
                    publish()
                }
                ACTION_DEV_BATTERY -> {
                    exact = intent.getIntExtra(EXTRA_DEV_BATTERY, -1).takeIf { it in 0..100 } ?: -1
                    publish()
                }
                ACTION_HFC_STATE -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        exact = -1
                        hfp = -1
                        publish()
                        // Мог остаться подключённым другой телефон.
                        query()
                    } else if (state == BluetoothProfile.STATE_CONNECTED) {
                        // Индикаторы телефона приходят чуть позже соединения.
                        main.postDelayed({ query() }, 3000)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun query() {
        val p = proxy ?: return
        val device = runCatching { p.connectedDevices.firstOrNull() }.getOrNull()
        if (device == null) {
            exact = -1
            hfp = -1
            publish()
            return
        }
        exact = deviceBattery(device)
        agBattery(p, device)?.let { hfp = it }
        publish()
    }

    private fun deviceBattery(device: BluetoothDevice): Int = runCatching {
        val m = BluetoothDevice::class.java.getMethod("getBatteryLevel")
        (m.invoke(device) as Int).takeIf { it in 0..100 } ?: -1
    }.getOrDefault(-1)

    private fun agBattery(p: BluetoothProfile, device: BluetoothDevice): Int? = runCatching {
        val m = p.javaClass.getMethod("getCurrentAgEvents", BluetoothDevice::class.java)
        val events = m.invoke(p, device) as? Bundle ?: return@runCatching null
        if (!events.containsKey(EXTRA_AG_BATTERY)) null
        else hfpToPercent(events.getInt(EXTRA_AG_BATTERY, -1))
    }.onFailure { Log.w(TAG, "getCurrentAgEvents: $it") }.getOrNull()

    private fun hfpToPercent(level: Int): Int = if (level in 0..5) level * 20 else -1

    private fun publish() {
        val value = if (exact >= 0) exact else hfp
        if (value == percent) return
        percent = value
        Log.i(TAG, "phone battery $value% (exact=$exact hfp=$hfp)")
        if (Looper.myLooper() == Looper.getMainLooper()) notifyListeners()
        else main.post { notifyListeners() }
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }
}
