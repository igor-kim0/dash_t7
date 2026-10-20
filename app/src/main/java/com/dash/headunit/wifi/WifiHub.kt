package com.dash.headunit.wifi

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.annotation.SuppressLint
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Источник состояния Wi-Fi и управление подключением к роутеру.
 *
 * Приложение с targetSdk 28 на Android 9 (ГУ) имеет право менять
 * Wi-Fi из обычного APK: isWifiEnabled/enableNetwork/addNetwork
 * работают с CHANGE_WIFI_STATE (normal-право). Состояние не
 * запоминает — всегда перечитывает из системы ([refresh]).
 * Все системные вызовы — вне главного потока, через runCatching.
 */
// Права CHANGE_WIFI_STATE/ACCESS_WIFI_STATE объявлены в манифесте
// (normal, выдаются при установке): lint это не видит, вызовы
// дополнительно обёрнуты в runCatching.
@SuppressLint("MissingPermission")
object WifiHub {

    private const val TAG = "WifiHub"

    /** Сохранённая на ГУ сеть. */
    data class SavedNet(val netId: Int, val ssid: String, val secure: Boolean)

    /** Сеть из эфира (getScanResults). */
    data class VisibleNet(val ssid: String, val rssi: Int, val secure: Boolean)

    /** Срез состояния: вкл/выкл, текущая сеть, сохранённые и видимые сети. */
    data class State(
        val enabled: Boolean,
        val connectedSsid: String?,
        val ip: String?,
        val rssi: Int,
        val saved: List<SavedNet>,
        val visible: List<VisibleNet>,
        /** false — нет геоправа: список эфира не читается вообще. */
        val canSeeVisible: Boolean,
        /** true — процесс подключения/добавления сети идёт. */
        val busy: Boolean
    )

    var state: State? = null
        private set

    private var appContext: Context? = null
    private val listeners = mutableSetOf<() -> Unit>()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    private fun wifiOrNull(ctx: Context): WifiManager? =
        ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
        listener()
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    fun ensurePolling() {
        if (pollJob == null) {
            pollJob = scope.launch {
                while (true) {
                    refresh()
                    delay(POLL_MS)
                }
            }
        }
    }

    /** Перечитать состояние из системы; вызвать с главного потока. */
    fun refresh() {
        val ctx = appContext ?: return
        scope.launch(Dispatchers.IO) {
            val fresh = readState(ctx)
            withContext(Dispatchers.Main) {
                if (fresh != null) {
                    state = fresh
                    notifyListeners()
                }
            }
        }
    }

    /** Вкл/выкл Wi-Fi. */
    fun setEnabled(on: Boolean) {
        val ctx = appContext ?: return
        scope.launch(Dispatchers.IO) {
            wifiOrNull(ctx)?.runCatching {
                isWifiEnabled = on
            }?.onFailure { Log.w(TAG, "setEnabled($on)", it) }
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    /** Подключиться к сохранённой сети по netId (отключив прочие). */
    fun connect(netId: Int) {
        val ctx = appContext ?: return
        scope.launch(Dispatchers.IO) {
            wifiOrNull(ctx)?.runCatching {
                // Запрашиваемая сеть — в приоритет: прочие сохранённые выключаем.
                configuredNetworks
                    ?.filter { it.networkId != netId }
                    ?.forEach { enableNetwork(it.networkId, false) }
                enableNetwork(netId, true) && saveConfiguration()
            }?.onFailure { Log.w(TAG, "connect($netId)", it) }
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    /**
     * Добавить сеть и сразу подключиться. Пустой пароль — открытая сеть.
     * [onDone] вызывается на главном потоке (true — добавлена).
     */
    fun addAndConnect(ssid: String, password: String, onDone: (Boolean) -> Unit = {}) {
        val ctx = appContext ?: return
        if (ssid.isBlank()) {
            onDone(false)
            return
        }
        scope.launch(Dispatchers.IO) {
            val ok = wifiOrNull(ctx)?.runCatching {
                val config = WifiConfiguration().apply {
                    val s = ssid.trim()
                    SSID = "\"$s\""
                    if (password.isNotBlank()) {
                        preSharedKey = "\"" + password + "\""
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    } else {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    }
                }
                val netId = addNetwork(config)
                if (netId >= 0) {
                    enableNetwork(netId, true) && saveConfiguration()
                } else {
                    false
                }
            }?.getOrDefault(false) ?: false
            withContext(Dispatchers.Main) {
                onDone(ok)
                refresh()
            }
        }
    }

    /** Удалить сохранённую сеть («Забыть»). */
    fun forget(netId: Int) {
        val ctx = appContext ?: return
        scope.launch(Dispatchers.IO) {
            wifiOrNull(ctx)?.runCatching {
                removeNetwork(netId) && saveConfiguration()
            }?.onFailure { Log.w(TAG, "forget($netId)", it) }
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    // ---------- чтение состояния ----------

    private fun readState(ctx: Context): State? {
        val wifi = wifiOrNull(ctx)
        if (wifi == null) {
            Log.w(TAG, "WifiManager недоступен")
            return null
        }
        val enabled = try {
            wifi.isWifiEnabled
        } catch (e: Exception) {
            Log.w(TAG, "isWifiEnabled", e)
            false
        }
        var ssid: String? = null
        var ip: String? = null
        var rssi = Int.MIN_VALUE
        if (enabled) {
            try {
                val info = wifi.connectionInfo
                if (info != null) {
                    val raw = info.ssid?.removeSurrounding("\"")
                    if (!raw.isNullOrBlank() && raw != UNKNOWN_SSID) ssid = raw
                    val ipInt = info.ipAddress
                    if (ipInt != 0) ip = formatIp(ipInt)
                    rssi = info.rssi
                }
            } catch (e: Exception) {
                Log.w(TAG, "connectionInfo", e)
            }
        }
        val saved = kotlin.runCatching {
            wifi.configuredNetworks
                ?.sortedByDescending { it.status == WifiConfiguration.Status.CURRENT }
                ?.map { cfg ->
                    SavedNet(
                        netId = cfg.networkId,
                        ssid = cfg.SSID?.removeSurrounding("\"") ?: UNKNOWN_SSID,
                        secure = cfg.preSharedKey != null
                    )
                } ?: emptyList()
        }.getOrDefault(emptyList())
        val hasLoc = hasLocation(ctx)
        val visible = if (enabled && hasLoc) {
            // Ручной запуск скана — API 28 ограничивает частоту сам.
            kotlin.runCatching { wifi.startScan() }
            kotlin.runCatching {
                wifi.scanResults?.asSequence()
                    ?.filter { !it.SSID.isNullOrBlank() }
                    ?.sortedByDescending { it.level }
                    ?.distinctBy { it.SSID }
                    ?.map { VisibleNet(it.SSID!!, it.level, isSecure(it.capabilities)) }
                    ?.toList().orEmpty()
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return State(
            enabled = enabled,
            connectedSsid = ssid,
            ip = ip,
            rssi = rssi,
            saved = saved,
            visible = visible,
            canSeeVisible = hasLoc,
            busy = false
        )
    }

    private fun hasLocation(ctx: Context): Boolean {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun isSecure(capabilities: String?): Boolean {
        val caps = capabilities ?: return false
        return listOf("WEP", "PSK", "EAP", "WPA", "RSN").any { caps.contains(it) }
    }

    private fun formatIp(ipInt: Int): String = (
        (ipInt and 0xff).toString() + "." +
            (ipInt shr 8 and 0xff) + "." +
            (ipInt shr 16 and 0xff) + "." +
            (ipInt shr 24 and 0xff)
        )

    private const val POLL_MS = 3000L
    private const val UNKNOWN_SSID = "<unknown ssid>"
}
