package com.dash.headunit.elm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Диагностика Bluetooth головного устройства для виджета «OBD (ELM)».
 *
 * Задача: машина видит сканер ELM327, но не спаривает его. Зонд показывает
 * состояние адаптера, ведёт скан ([BluetoothAdapter.startDiscovery]),
 * перечисляет спаренные и найденные устройства, пытается спарить
 * ([BluetoothDevice.createBond]) и подробно логирует все переходы
 * состояния пары и ошибки подключения (SPP/RFCOMM).
 *
 * Ничего не пишет в шину и не меняет системные настройки BT: только
 * стандартные BT-API. Всё, что не вышло, попадает в журнал в виде текста
 * исключения — по нему видно, на каком шаге рвётся.
 */
object ElmBtProbe {

    /** Устройство BT: спаренное или найденное сканом. */
    data class Device(
        val address: String,
        val name: String?,
        val bondState: Int,
        val rssi: Int?,
        val at: Long
    ) {
        val bonded: Boolean get() = bondState == BluetoothDevice.BOND_BONDED
    }

    data class Line(val at: Long, val text: String)

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val devices = LinkedHashMap<String, Device>()
    private val lines = ArrayDeque<Line>()

    @Volatile
    var status: String = "не запускался"
        private set

    @Volatile
    var scanning: Boolean = false
        private set

    /** PIN, которым зонд отвечает на запрос спаривания (ELM327 обычно 1234/0000). */
    @Volatile
    var pin: String = "1234"
        private set

    /** Отвечать ли на запросы пары сами (когда у машины нет окна ввода PIN). */
    @Volatile
    var autoAnswerPairing: Boolean = true
        private set

    /** Адрес, для которого мы сами запустили [pair]; после пары/отказа сбрасывается. */
    @Volatile
    private var awaitingPair: String? = null

    private var appCtx: Context? = null
    private var receiver: BroadcastReceiver? = null

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dash-bt-probe").apply { isDaemon = true }
    }

    /** Подключиться к системному BT: загрузить спаренные, слушать события. */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        appCtx = app
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    // Глушим рассылку запроса пары ТОЛЬКО если сами ответили:
                    // иначе системное окно PIN/подтверждения не появится,
                    // и пара повиснет до таймаута.
                    if (onBroadcast(intent) && isOrderedBroadcast) {
                        runCatching { abortBroadcast() }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(ACTION_PAIRING_REQUEST)
                addAction(ACTION_PAIRING_CANCEL)
                // Высокий приоритет, чтобы успеть ответить на запрос пары (abortBroadcast).
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            }
            app.registerReceiver(r, filter)
            receiver = r
        }
        loadBonded()
        status = adapterInfo()
        log("старт: $status")
    }

    /** Снять приёмник событий (для тестов; обычно живёт вместе с приложением). */
    @Synchronized
    fun stop() {
        receiver?.let { runCatching { appCtx?.unregisterReceiver(it) } }
        receiver = null
    }

    fun devices(): List<Device> = synchronized(devices) {
        devices.values.sortedWith(compareByDescending<Device> { it.bonded }.thenBy { it.name ?: it.address })
    }

    fun log(): List<Line> = synchronized(lines) { lines.toList() }

    fun clearLog() = synchronized(lines) { lines.clear() }

    /** Задать PIN для автоответа на запрос пары. */
    fun setPin(value: String) {
        pin = value.trim()
        log("PIN для пары: $pin")
    }

    /** Включить/выключить автоответ на запрос пары. */
    fun setAutoAnswerPairing(enabled: Boolean) {
        autoAnswerPairing = enabled
        log(if (enabled) "автоответ на пару: вкл (PIN $pin)" else "автоответ на пару: выкл")
    }

    /** Строка состояния адаптера (или почему BT недоступен). */
    fun adapterInfo(): String {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return "нет BT-адаптера в системе"
        val state = when (adapter.state) {
            BluetoothAdapter.STATE_ON -> "включён"
            BluetoothAdapter.STATE_OFF -> "выключен"
            BluetoothAdapter.STATE_TURNING_ON -> "включается"
            BluetoothAdapter.STATE_TURNING_OFF -> "выключается"
            else -> "состояние ${adapter.state}"
        }
        val scanMode = when (adapter.scanMode) {
            BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "видим всем"
            BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "подключаем"
            else -> "невидим"
        }
        return "${adapter.name ?: "?"} [${adapter.address ?: "?"}] $state · $scanMode · спарено ${devices().count { it.bonded }}"
    }

    /** Запустить скан (или перезапустить, если уже идёт). */
    fun scan() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            status = "нет BT-адаптера"
            log(status)
            return
        }
        if (!adapter.isEnabled) {
            log("BT выключен — скан невозможен")
            return
        }
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        loadBonded()
        val ok = adapter.startDiscovery()
        log("startDiscovery -> $ok")
    }

    fun stopScan() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
            log("cancelDiscovery")
        }
    }

    /** Попытаться спарить устройство. Возвращает текст результата. */
    fun pair(address: String): String {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "нет BT-адаптера"
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return "неверный адрес $address"
        }
        val result = try {
            awaitingPair = address
            val ok = device.createBond()
            "createBond -> $ok"
        } catch (e: Exception) {
            "createBond: ${e.javaClass.simpleName}: ${e.message}"
        }
        log("спаривание $address (${nameOf(device)}): $result, состояние ${bondName(device.bondState)}" +
            if (autoAnswerPairing) ", автоответ PIN $pin" else "")
        return result
    }

    /** Отменить пару (на случай «залипшего» неверного спаривания). */
    fun unpair(address: String): String {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "нет BT-адаптера"
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return "неверный адрес $address"
        }
        return try {
            // removeBond — скрытый метод, в публичном SDK его нет.
            val m = device.javaClass.getMethod("removeBond")
            val ok = m.invoke(device) as? Boolean ?: false
            log("removeBond($address) -> $ok")
            "removeBond -> $ok"
        } catch (e: Exception) {
            log("removeBond: ${e.javaClass.simpleName}: ${e.message}")
            "removeBond: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * Проверка подключения по SPP: перебирает штатный secure SPP, небезопасный
     * и скрытый RFCOMM-канал 1 — классические обходы, когда машина не даёт
     * SPP-сокет. Всё в фоне, результат — в журнале.
     */
    fun testConnect(address: String) {
        if (address.isBlank()) {
            log("адрес ELM не выбран — сначала выберите устройство")
            return
        }
        log("тест подключения к $address…")
        worker.execute {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
                log("нет BT-адаптера")
                return@execute
            }
            val device = try {
                adapter.getRemoteDevice(address)
            } catch (e: IllegalArgumentException) {
                log("неверный адрес $address")
                return@execute
            }
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            log("состояние пары: ${bondName(device.bondState)}")
            attempt("SPP secure") { device.createRfcommSocketToServiceRecord(SPP_UUID) }
            attempt("SPP insecure") { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
            attempt("RFCOMM ch1") {
                val m = device.javaClass.getMethod(
                    "createRfcommSocket", Int::class.javaPrimitiveType
                )
                @Suppress("UNCHECKED_CAST")
                m.invoke(device, 1) as BluetoothSocket
            }
            log("тест подключения завершён")
        }
    }

    private inline fun attempt(name: String, factory: () -> BluetoothSocket) {
        val socket = try {
            factory()
        } catch (e: Exception) {
            log("$name: сокет не создан: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        try {
            socket.connect()
            log("$name: OK — сокет открыт")
        } catch (e: Exception) {
            log("$name: connect FAIL: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    // ---------- события ----------

    /** Обработать событие; true — запрос пары отвечен нами (системному окну не отдаём). */
    @SuppressLint("MissingPermission")
    private fun onBroadcast(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        when (action) {
            BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                scanning = true
                log("скан начат")
            }
            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                scanning = false
                log("скан завершён (всего ${devices().size})")
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                log("BT состояние: $state")
                status = adapterInfo()
            }
            BluetoothDevice.ACTION_FOUND -> {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    .takeIf { it != Short.MIN_VALUE.toInt() }
                device?.let {
                    add(it, rssi)
                    log("найден ${nameOf(it)} ${it.address}${rssi?.let { r -> " rssi $r" } ?: ""} (${bondName(it.bondState)})")
                }
            }
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return false
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                add(device, null)
                log("пара ${nameOf(device)} ${device.address}: ${bondName(prev)} → ${bondName(state)}")
                status = adapterInfo()
                if (state != BluetoothDevice.BOND_BONDING) awaitingPair = null
            }
            ACTION_PAIRING_REQUEST -> {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return false
                val variant = intent.getIntExtra(EXTRA_PAIRING_VARIANT, -1)
                val key = intent.getIntExtra(EXTRA_PAIRING_KEY, -1)
                log("запрос пары ${nameOf(device)} ${device.address}: ${variantName(variant)}, key $key")
                if (!autoAnswerPairing) {
                    log("автоответ выключен — пару нужно подтвердить на экране ГУ")
                    return false
                }
                val answered = answerPairing(device, variant, key)
                if (!answered) log("автоответ не прошёл — отдаём запрос системному окну ГУ")
                return answered
            }
            ACTION_PAIRING_CANCEL -> {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                log("запрос пары отменён${device?.let { " (${it.address})" } ?: ""} — вероятно, таймаут/ошибка")
            }
        }
        return false
    }

    private fun variantName(variant: Int): String = when (variant) {
        PAIRING_VARIANT_PIN -> "нужен PIN"
        PAIRING_VARIANT_PASSKEY -> "нужен 6-значный код"
        PAIRING_VARIANT_PASSKEY_CONFIRMATION -> "подтвердить код"
        PAIRING_VARIANT_CONSENT -> "подтвердить пару"
        PAIRING_VARIANT_DISPLAY_PASSKEY -> "код на экране"
        PAIRING_VARIANT_DISPLAY_PIN -> "PIN на экране"
        else -> "вариант $variant"
    } + " ($variant)"


    @SuppressLint("MissingPermission")
    private fun loadBonded() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) return
        try {
            adapter.bondedDevices?.forEach { add(it, null) }
        } catch (e: Exception) {
            log("bondedDevices: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun add(device: BluetoothDevice, rssi: Int?) {
        synchronized(devices) {
            val prev = devices[device.address]
            devices[device.address] = Device(
                address = device.address,
                name = nameOf(device),
                bondState = device.bondState,
                rssi = rssi ?: prev?.rssi,
                at = System.currentTimeMillis()
            )
            while (devices.size > DEVICE_MAX) {
                // Выкидываем самое старое НЕспаренное, чтобы список не рос.
                val victim = devices.values.firstOrNull { !it.bonded } ?: break
                devices.remove(victim.address)
            }
        }
    }

    private fun nameOf(device: BluetoothDevice): String? =
        try {
            device.name
        } catch (e: SecurityException) {
            null
        }

    private fun bondName(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "не спарен"
        BluetoothDevice.BOND_BONDING -> "спаривание…"
        BluetoothDevice.BOND_BONDED -> "спарен"
        else -> "?($state)"
    }

    fun bondName(device: Device): String = bondName(device.bondState)

    // ---------- автоответ на запрос пары ----------

    /**
     * Отвечает на запрос спаривания так, как это сделало бы системное окно PIN.
     * Это чинит случай «машина видит сканер, но спаривать не хочет»: когда
     * окна ввода нет, стек ждёт ввода, и пара отваливается по таймауту.
     */
    /**
     * Возвращает true, только если ответ принят стеком. setPin требует лишь
     * BLUETOOTH_ADMIN; setPasskey/setPairingConfirmation на Android 9 —
     * BLUETOOTH_PRIVILEGED (у нас его нет), там почти наверняка false.
     */
    @SuppressLint("MissingPermission")
    private fun answerPairing(device: BluetoothDevice, variant: Int, key: Int): Boolean =
        when (variant) {
            PAIRING_VARIANT_PIN -> {
                val ok = callBoolean(device, "setPin", ByteArray::class.java, pin.toByteArray(Charsets.UTF_8))
                log("setPin(\"$pin\") -> $ok")
                ok
            }
            PAIRING_VARIANT_PASSKEY -> {
                // setPasskey — скрытый метод; в публичном SDK его нет.
                val ok = callBooleanHidden(device, "setPasskey", Int::class.javaPrimitiveType!!, key)
                log("setPasskey($key) -> $ok")
                ok
            }
            PAIRING_VARIANT_PASSKEY_CONFIRMATION, PAIRING_VARIANT_CONSENT -> confirmPairing(device)
            PAIRING_VARIANT_DISPLAY_PASSKEY -> {
                log("на ГУ показывается код $key — подтвердите на экране")
                false
            }
            PAIRING_VARIANT_DISPLAY_PIN -> {
                log("на ГУ показывается PIN $key — подтвердите на экране")
                false
            }
            else -> {
                log("вариант $variant требует ввода вручную — автоответ невозможен")
                false
            }
        }

    @SuppressLint("MissingPermission")
    private fun confirmPairing(device: BluetoothDevice): Boolean {
        val ok = callBoolean(device, "setPairingConfirmation", Boolean::class.javaPrimitiveType!!, true)
        log("setPairingConfirmation(true) -> $ok")
        return ok
    }

    /** Публичный (в новых SDK) метод BluetoothDevice; вызов через рефлексию — безопаснее. */
    private fun callBoolean(device: BluetoothDevice, name: String, argType: Class<*>, arg: Any): Boolean =
        try {
            val m = BluetoothDevice::class.java.getMethod(name, argType)
            (m.invoke(device, arg) as? Boolean) ?: false
        } catch (e: Exception) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            log("$name: ${cause.javaClass.simpleName}: ${cause.message}")
            false
        }

    private fun callBooleanHidden(device: BluetoothDevice, name: String, argType: Class<*>, arg: Any): Boolean =
        try {
            val m = BluetoothDevice::class.java.getDeclaredMethod(name, argType)
            m.isAccessible = true
            (m.invoke(device, arg) as? Boolean) ?: false
        } catch (e: Exception) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            log("$name: ${cause.javaClass.simpleName}: ${cause.message}")
            false
        }

    private fun log(text: String) {
        Log.i(TAG, text)
        synchronized(lines) {
            lines.addLast(Line(System.currentTimeMillis(), text))
            while (lines.size > LOG_MAX) lines.removeFirst()
        }
    }

    private const val TAG = "ElmBtProbe"
    private const val DEVICE_MAX = 60
    private const val LOG_MAX = 200

    // Запрос пары и его extras (строки — на случай, если константы скрыты в SDK).
    private const val ACTION_PAIRING_REQUEST = "android.bluetooth.device.action.PAIRING_REQUEST"
    private const val ACTION_PAIRING_CANCEL = "android.bluetooth.device.action.PAIRING_CANCEL"
    private const val EXTRA_PAIRING_VARIANT = "android.bluetooth.device.extra.PAIRING_VARIANT"
    private const val EXTRA_PAIRING_KEY = "android.bluetooth.device.extra.PAIRING_KEY"

    private const val PAIRING_VARIANT_PIN = 0
    private const val PAIRING_VARIANT_PASSKEY = 1
    private const val PAIRING_VARIANT_PASSKEY_CONFIRMATION = 2
    private const val PAIRING_VARIANT_CONSENT = 3
    private const val PAIRING_VARIANT_DISPLAY_PASSKEY = 4
    private const val PAIRING_VARIANT_DISPLAY_PIN = 5
}
