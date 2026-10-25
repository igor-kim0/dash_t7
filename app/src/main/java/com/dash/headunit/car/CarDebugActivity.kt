package com.dash.headunit.car

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dash.headunit.elm.ElmHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Скрытый debug-экран поиска температуры двигателя/коробки (открывается
 * пятью тапами по заголовку на главном экране).
 *
 * Сканирует шину Desay SV (transact-read безопасен) и перечисляет читаемые
 * свойства VHAL. Замерять на ПРОГРЕТОМ двигателе — искомые значения ~80–95.
 * Результат сохраняется в /sdcard/dash_car_dump.txt.
 */
class CarDebugActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var btnScan: Button
    private lateinit var btnSave: Button
    private lateinit var recStatus: TextView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val lines = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        btnScan = Button(this).apply { text = "Сканировать CAN/VHAL" }
        btnSave = Button(this).apply { text = "Сохранить в файл" }
        val btnEcho = Button(this).apply { text = "Климат: состояние (группа 16)" }
        val btnProbe = Button(this).apply { text = "Климат-проба записи (write-back)" }
        val btnCluster = Button(this).apply { text = "Слушать приборку 60 с" }
        recStatus = TextView(this)
        val btnRec = Button(this).apply { text = "Запись приборки (QNX PPS) старт/стоп" }
        val btnTripSim = Button(this).apply { text = "Журнал: записать тест-поездку" }
        val btnNaviPeek = Button(this).apply {
            text = "Навигатор в блоке: проверка окна для приборки (~25 с на дашборде)"
        }
        val btnSla = Button(this).apply {
            text = "Знак «60» на приборку штатным путём (~25 с, смотреть на приборку)"
        }
        val btnOverspeed = Button(this).apply {
            text = "Знак через порог превышения 30 км/ч (~20 с, на ходу > 40)"
        }
        val btnEtm = Button(this).apply { text = "ОЖ °C через инженерное меню приборки (~20 с)" }
        val markText = EditText(this).apply {
            hint = "что на приборке: «ОЖ 65», «расход 8.4», «передача 3»"
            isSingleLine = true
        }
        val btnMark = Button(this).apply { text = "Метка" }
        val recRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnRec)
            addView(markText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnMark)
        }
        output = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(output) }
        root.addView(btnScan)
        root.addView(btnSave)
        root.addView(btnEcho)
        root.addView(btnProbe)
        root.addView(btnCluster)
        root.addView(recRow)
        root.addView(btnTripSim)
        root.addView(btnNaviPeek)
        root.addView(btnSla)
        root.addView(btnOverspeed)
        root.addView(btnEtm)
        root.addView(recStatus)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        setContentView(root)

        btnScan.setOnClickListener { startScan() }
        btnSave.setOnClickListener { saveToFile() }
        btnEcho.setOnClickListener { readEcho() }
        btnProbe.setOnClickListener { writeProbe() }
        btnCluster.setOnClickListener { listenCluster() }
        btnTripSim.setOnClickListener { simulateTrip() }
        // Проверка идёт на дашборде (навигатор в блоке под ним) — экран закрываем.
        btnNaviPeek.setOnClickListener {
            com.dash.headunit.diag.NaviPeekTest.start(this)
            finish()
        }
        btnSla.setOnClickListener { com.dash.headunit.diag.ClusterSpeedLimitTest.start(this) }
        btnOverspeed.setOnClickListener { com.dash.headunit.diag.OverspeedSignTest.start(this) }
        btnEtm.setOnClickListener { com.dash.headunit.diag.CoolantEtmTest.start(this) }
        btnRec.setOnClickListener {
            if (ClusterRecorder.running) ClusterRecorder.stop() else ClusterRecorder.start(this)
        }
        btnMark.setOnClickListener {
            val t = markText.text.toString().trim()
            ClusterRecorder.mark(t.ifEmpty { "метка" })
            markText.setText("")
        }
        scope.launch {
            while (true) {
                recStatus.text = "Запись приборки: ${ClusterRecorder.status}" +
                    (ClusterRecorder.file?.let { "\n${it.absolutePath}" } ?: "")
                delay(1000)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()

    private fun log(s: String) {
        lines.add(s)
        output.append(s + "\n")
    }

    private fun startScan() {        if (job?.isActive == true) return
        lines.clear()
        output.text = ""
        btnScan.isEnabled = false
        job = scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val pi = applicationContext.packageManager.getPackageInfo(
                        applicationContext.packageName, 0
                    )
                    emit("app: version=${pi.versionName} code=${pi.versionCode}")
                } catch (e: Exception) {
                    emit("app: version ?")
                }
                try {
                    emit("=== Бортовой компьютер ===")
                    for (line in TripTracker.debugLines(applicationContext)) {
                        emit(line)
                    }
                    emit("trip: приборка a001 ${ClusterPps.trip()?.describe() ?: "нет"}")
                    emit("ОЖ: стрелка ${ClusterPps.needle()?.let { "%.0f‰".format(it * 1000) } ?: "нет"}, " +
                        "сканер ${ElmHub.coolantC() ?: "нет"}")
                    emit("ОЖ калибровка стрелки: ${CoolantCalibration.describe()}")
                } catch (e: Exception) {
                    emit("Trip: ОШИБКА ${e.javaClass.simpleName}: ${e.message}")
                }
                try {
                    scanDesay()
                } catch (e: Exception) {
                    emit("Desay: ОШИБКА ${e.javaClass.simpleName}: ${e.message}")
                }
                try {
                    scanDesayReadonly()
                } catch (e: Exception) {
                    emit("Desay READONLY: ОШИБКА ${e.javaClass.simpleName}: ${e.message}")
                }
                try {
                    dumpVhal()
                } catch (e: Exception) {
                    emit("VHAL: ОШИБКА ${e.javaClass.simpleName}: ${e.message}")
                }
                try {
                    dumpCarHub()
                } catch (e: Exception) {
                    emit("carhub: ОШИБКА ${e.javaClass.simpleName}: ${e.message}")
                }
                try {
                    emit("=== Media-сессии (музыка/BT) ===")
                    com.dash.headunit.music.MusicHub.init(applicationContext)
                    for (line in com.dash.headunit.music.MusicHub.debugLines()) {
                        emit(line)
                    }
                } catch (e: Exception) {
                    emit("Media: ОШИБКА ${e.javaClass.simpleName}: ${e.message}")
                }
                try {
                    emit("=== Погода/сеть ===")
                    for (line in probeWeather()) {
                        emit(line)
                    }
                } catch (e: Exception) {
                    emit("Net: ОШИБКА ${e.javaClass.simpleName}: ${e.message}")
                }
                try {
                    emit("=== Встраивание приложений ===")
                    emit("embed: ActivityView доступен=${com.dash.headunit.embed.AppEmbed.isSupported()}")
                    for (sig in com.dash.headunit.embed.AppEmbed.methodSignatures()) {
                        emit("embed: метод $sig")
                    }
                    try {
                        val probe = com.dash.headunit.embed.AppEmbed.createView(this@CarDebugActivity)
                        emit(
                            if (probe != null) {
                                "embed: createView → ${com.dash.headunit.embed.AppEmbed.lastStatus}"
                            } else {
                                "embed: createView → НЕ СОЗДАН: ${com.dash.headunit.embed.AppEmbed.lastStatus}"
                            }
                        )
                        com.dash.headunit.embed.AppEmbed.release(probe)
                    } catch (e: Exception) {
                        emit("embed: createView ОШИБКА ${e.javaClass.simpleName}: ${e.message}")
                    }
                    emit("embed: последний статус: ${com.dash.headunit.embed.AppEmbed.lastStatus}")
                } catch (e: Exception) {
                    emit("Embed: ОШИБКА ${e.javaClass.simpleName}: ${e.message}")
                }
                emit("=== конец ===")
            }
            btnScan.isEnabled = true
        }
    }

    private suspend fun emit(s: String) {
        withContext(Dispatchers.Main) { log(s) }
    }

    // ---------- Desay SV ----------

    private suspend fun emitDesay(group: Int, id: Int, raw: IntArray?) {
        if (raw == null || raw.isEmpty()) return
        val assembled = CarScaling.bytesToInt(raw)
        val bytes = raw.joinToString(",") { (it and 0xFF).toString() }
        val asText = try {
            val chars = raw.map { (it and 0xFF).toChar() }
            if (chars.isNotEmpty() && chars.all { c -> c.code in 32..126 || c == '.' }) {
                " text=\"" + chars.joinToString("") + "\""
            } else ""
        } catch (e: Exception) {
            ""
        }
        emit("desay($group,$id) = [$bytes] int=$assembled$asText")
    }

    private suspend fun scanDesay() {
        emit("=== Desay SV: шины 0..8, только ненулевые ===")
        val reader = DesayCarReader(applicationContext)
        if (!reader.isAvailable()) {
            emit("Desay: сервис com.desaysv.svcarinfo НЕ НАЙДЕН")
            return
        }
        emit("Desay: сервис найден, читаю…")
        var hits = 0
        for (group in 0..8) {
            for (id in 0..255) {
                val raw = try {
                    reader.readRaw(group, id)
                } catch (e: Exception) {
                    emit("Desay: исключение на ($group,$id): ${e.message}")
                    return
                }
                if (raw != null && raw.isNotEmpty() && raw.any { it != 0 }) {
                    hits++
                    emitDesay(group, id, raw)
                    if (hits > 300) {
                        emit("Desay: лимит 300 строк, стоп")
                        return
                    }
                }
            }
        }
        emit("Desay: готово, ненулевых: $hits")
    }

    /**
     * Desay READONLY-группа 327684 — по TiggoBridge (Chery Pro, та же
     * Desay-поставка, binder тот же CarInfoService): id 100 = температура
     * ОЖ, id 62 = обороты. Обычный скан ходит по группам 0..8 и эту
     * группу не трогает. Формат TiggoBridge.tempName: в диапазоне
     * [-80..80] — °C как есть, иначе десятые °C.
     */
    private suspend fun scanDesayReadonly() {
        emit("=== Desay SV: READONLY-группа 327684 (100=ОЖ, 62=об/мин) ===")
        val reader = DesayCarReader(applicationContext)
        if (!reader.isAvailable()) {
            emit("Desay: сервис com.desaysv.svcarinfo НЕ НАЙДЕН")
            return
        }
        val group = 327684
        var hits = 0
        for (id in 0..255) {
            val raw = try {
                reader.readRaw(group, id, logErrors = false)
            } catch (e: Exception) {
                emit("Desay READONLY: исключение на ($group,$id): ${e.message}")
                return
            }
            if (raw == null || raw.isEmpty() || raw.all { it == 0 }) continue
            hits++
            val assembled = CarScaling.bytesToInt(raw)
            val bytes = raw.joinToString(",") { (it and 0xFF).toString() }
            val hint = when (id) {
                100 -> if (assembled > 80 || assembled < -80) {
                    " °C=%.1f".format(java.util.Locale.US, assembled / 10.0)
                } else " °C=$assembled"
                62 -> " rpm=$assembled"
                else -> ""
            }
            emit("desay($group,$id) = [$bytes] int=$assembled$hint")
            if (hits > 200) {
                emit("Desay READONLY: лимит 200 строк, стоп")
                return
            }
        }
        emit("Desay READONLY: готово, ненулевых: $hits")
    }

    // ---------- VHAL ----------

    private suspend fun dumpVhal() {
        emit("=== VHAL: диагностика ===")
        val reader = VhalReader(applicationContext)
        for (line in reader.debugProbe()) {
            emit(line)
        }
        emit("=== VHAL: список свойств ===")
        val mgr = try {
            reader.javaClass.getDeclaredMethod("ensureManager").apply {
                isAccessible = true
            }.invoke(reader)
        } catch (e: Exception) {
            emit("VHAL: нет android.car (${e.javaClass.simpleName})")
            return
        }
        if (mgr == null) {
            emit("VHAL: manager null (нет сервиса/прав)")
            return
        }
        val configs: List<*> = try {
            @Suppress("UNCHECKED_CAST")
            (mgr.javaClass.getMethod("getPropertyList").invoke(mgr) as? List<*>) ?: emptyList<Any>()
        } catch (e: Exception) {
            emit("VHAL: getPropertyList недоступен: ${e.message}")
            return
        }
        emit("VHAL: свойств в списке: ${configs.size}")
        var read = 0
        for (cfg in configs) {
            if (cfg == null) continue
            try {
                val propId = cfg.javaClass.getMethod("getPropertyId").invoke(cfg) as? Int
                    ?: continue
                val areas = try {
                    cfg.javaClass.getMethod("getAreaIds").invoke(cfg) as? IntArray
                } catch (e: Exception) {
                    null
                }
                val areaList = if (areas == null || areas.isEmpty()) intArrayOf(0) else areas
                for (area in areaList.take(2)) {
                    val value = try {
                        val m = mgr.javaClass.getMethod(
                            "getProperty", Class::class.java,
                            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                        )
                        val prop = m.invoke(mgr, Object::class.java, propId, area) ?: continue
                        val v = prop.javaClass.getMethod("getValue").invoke(prop)
                        val st = try {
                            prop.javaClass.getMethod("getStatus").invoke(prop)
                        } catch (e: Exception) {
                            0
                        }
                        "value=$v status=$st"
                    } catch (e: Exception) {
                        "ERR ${e.javaClass.simpleName}"
                    }
                    val hex = "0x" + propId.toUInt().toString(16).uppercase(Locale.US)
                    emit("vhal($hex,$area) $value")
                    read++
                    if (read > 400) {
                        emit("VHAL: лимит 400 чтений, стоп")
                        return
                    }
                }
            } catch (e: Exception) {
                emit("VHAL: пропуск свойства: ${e.message}")
            }
        }
        emit("VHAL: готово")
    }

    // ---------- CarHub: итоговый снимок ----------

    /**
     * Что реально дошло до виджетов после слияния Desay+VHAL.
     * Desay температуру двигателя не отдаёт (engineTempC всегда null
     * с его стороны) — единственный шанс это VHAL-цепочка
     * COOLANT → OIL_TEMP → TEMP_INFO (см. строки vhal выше).
     */
    private suspend fun dumpCarHub() {
        emit("=== CarHub: итоговый снимок для виджетов ===")
        CarHub.init(applicationContext)
        CarHub.refresh()
        // Даём опросу дойти до провайдеров (Desay bind до 4 с, VHAL connect до 3 с).
        delay(8000)
        val d = CarHub.data
        if (d == null) {
            emit("carhub: данных нет — виджеты показывают демо-снимок")
            return
        }
        emit("carhub: source=${d.source}")
        emit("carhub: fuelPct=${d.fuelPct} fuelLiters=${d.fuelLiters} rangeKm=${d.rangeKm}")
        emit("carhub: voltageV=${d.voltageV} charging=${d.charging}")
        emit("carhub: engineTempC=${d.engineTempC} gearboxTempC=${d.gearboxTempC}")
        emit("carhub: outsideTempC=${d.outsideTempC} odoKm=${d.odoKm}")
        if (d.engineTempC == null) {
            emit("carhub: engineTempC НЕТ — Desay его не отдаёт, VHAL-цепочка пуста (см. vhal выше)")
        }
    }

    // ---------- Приборка ( broadcasts ) ----------

    private var clusterReceiver: android.content.BroadcastReceiver? = null

    /**
     * Слушает широковещательные сообщения приборки 60 секунд и пишет
     * всё в лог: действие, время, все extras. Именно отсюда Jarvis
     * узнаёт о навигации на кластере — возможно, там же есть и
     * температура ОЖ. Во время записи музыка/навигация могут мигнуть
     * на приборке — это нормально.
     */
    private fun listenCluster() {
        if (job?.isActive == true) return
        if (clusterReceiver != null) {
            emitSync("cluster: уже слушаю")
            return
        }
        emitSync("cluster: слушаю 60 с: desay.broadcast.map.meter.interaction, SVCarLanManager…")
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                if (intent == null) return
                val sb = StringBuilder()
                sb.append("cluster: [")
                sb.append(
                    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                )
                sb.append("] ").append(intent.action)
                try {
                    val extras = intent.extras
                    if (extras != null) {
                        for (key in extras.keySet()) {
                            val v = extras.get(key)
                            val s = v?.toString() ?: "null"
                            sb.append(" | ").append(key).append("=")
                                .append(if (s.length > 200) s.take(200) + "…" else s)
                        }
                    } else {
                        sb.append(" (без extras)")
                    }
                } catch (e: Exception) {
                    sb.append(" err extras: ${e.message}")
                }
                emitSync(sb.toString())
            }
        }
        clusterReceiver = receiver
        val filter = android.content.IntentFilter().apply {
            addAction("desay.broadcast.map.meter.interaction")
            addAction("SVCarLanManager")
        }
        try {
            registerReceiver(receiver, filter)
        } catch (e: Exception) {
            emitSync("cluster: register FAILED: ${e.message}")
            clusterReceiver = null
            return
        }
        job = scope.launch {
            delay(60_000)
            try {
                unregisterReceiver(receiver)
            } catch (e: Exception) {
            }
            clusterReceiver = null
            withContext(Dispatchers.Main) {
                log("cluster: стоп, 60 с прошло")
            }
        }
    }

    private fun emitSync(s: String) {
        lines.add(s)
        runOnUiThread { output.append(s + "\n") }
    }

    // ---------- Симуляция поездки (для проверки журнала на эмуляторе) ----------

    /**
     * Синтетическая поездка через тот же публичный [TripTracker.update] —
     * чтобы проверить запись/отрисовку журнала там, где машину не подключить
     * (эмулятор). Запись разрешена только пока у CarHub НЕТ реальных данных:
     * на боевой ГУ демо-цифры в журнал не попадут никак.
     */
    private fun simulateTrip() {
        if (job?.isActive == true) return
        job = scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // Пролистываем опрос: вдруг данные как раз пришли.
                    CarHub.init(applicationContext)
                    CarHub.refresh()
                    delay(3000)
                    if (CarHub.freshData() != null || ClusterPps.trip() != null) {
                        emit("trip-sim: ТАМ ЕСТЬ РЕАЛЬНЫЕ ДАННЫЕ — не пишем синтетику в журнал")
                        return@withContext
                    }
                    val ctx = applicationContext
                    val now = System.currentTimeMillis()
                    val t0 = now - 330_000L // поездка «началась» 5.5 минут назад
                    fun car(odo: Int, fuel: Float, rpm: Int?, volt: Float = 12.5f): CarData =
                        CarData(
                            fuelPct = fuel, fuelLiters = null, rangeKm = null,
                            voltageV = volt, charging = rpm != null,
                            engineTempC = null, gearboxTempC = null,
                            outsideTempC = null, odoKm = odo, rpm = rpm
                        )
                    TripTracker.update(ctx, car(19_500, 50f, 900, 14.2f), 51, null, t0)
                    for (i in 1..5) {
                        // ~1 км/мин (60 км/ч), ~7.5 л/100 км из 51 л бака.
                        TripTracker.update(
                            ctx,
                            car(19_500 + i, 50f - i * 0.15f, 2_100, 14.2f),
                            51, null,
                            t0 + i * 60_000L
                        )
                    }
                    TripTracker.update(
                        ctx,
                        car(19_505, 49.25f, null, 12.4f),
                        51, null,
                        t0 + 305_000L
                    )
                    val j = TripTracker.journal(ctx)
                    emit("trip-sim: в журнале записей = ${j.size}")
                    for (e in j.take(3)) {
                        emit(
                            "trip-sim: ${java.text.SimpleDateFormat("dd.MM HH:mm", Locale.US).format(java.util.Date(e.startTs))}" +
                                " → ${java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(e.endTs))}" +
                                ", ${e.distKm} км, ${e.driveMillis / 60_000} мин" +
                                (e.avgCons?.let { ", $it л/100" } ?: "")
                        )
                    }
                    emit("trip-sim: готово — проверьте «Журнал поездок» в виджете «Бортовой компьютер»")
                } catch (e: Exception) {
                    emit("trip-sim: ОШИБКА ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    // ---------- Погода/сеть ----------

    /**
     * Диагностика цепочки погоды: разрешения, геолокация, DNS/сеть,
     * ответы OWM и Open-Meteo, состояние хаба и кэша. Ключ не печатаем.
     */
    private fun probeWeather(): List<String> {
        val out = mutableListOf<String>()
        val ctx = applicationContext
        // Разрешения.
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        out.add("net: location perm fine=$fine coarse=$coarse")
        // Провайдеры и lastKnown.
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val providers = try {
                lm.getProviders(true)
            } catch (e: Exception) {
                emptyList<String>()
            }
            out.add("net: providers=$providers")
            for (p in listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER
            )) {
                try {
                    val loc = lm.getLastKnownLocation(p)
                    if (loc != null) {
                        val ageMin = (System.currentTimeMillis() - loc.time) / 60000
                        out.add("net: last[$p] lat=${loc.latitude} lon=${loc.longitude} ageMin=$ageMin")
                    } else {
                        out.add("net: last[$p] null")
                    }
                } catch (e: Exception) {
                    out.add("net: last[$p]: ${e.javaClass.simpleName}")
                }
            }
        } catch (e: Exception) {
            out.add("net: location err: ${e.message}")
        }
        // Сеть: активное подключение + DNS.
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val info = cm.activeNetworkInfo
            out.add("net: active=${info?.typeName} connected=${info?.isConnected}")
        } catch (e: Exception) {
            out.add("net: connectivity err: ${e.message}")
        }
        for (host in listOf("api.openweathermap.org", "api.open-meteo.com")) {
            try {
                val addr = java.net.InetAddress.getByName(host).hostAddress
                out.add("net: dns $host -> $addr")
            } catch (e: Exception) {
                out.add("net: dns $host FAIL: ${e.javaClass.simpleName}")
            }
        }
        // Прямые запросы (короткий таймаут).
        val key = try {
            com.dash.headunit.BuildConfig.OPENWEATHER_KEY
        } catch (e: Exception) {
            ""
        }
        out.add("net: owm key present=${key.isNotBlank()} len=${key.length}")
        if (key.isNotBlank()) {
            out.add("net: owm current -> " + httpProbe(
                "https://api.openweathermap.org/data/2.5/weather?lat=55.75&lon=37.61&appid=$key&units=metric&lang=ru"
            ))
        }
        out.add("net: open-meteo -> " + httpProbe(
            "https://api.open-meteo.com/v1/forecast?latitude=55.75&longitude=37.61&current=temperature_2m&forecast_days=1"
        ))
        // Состояние хаба и кэша.
        try {
            val hub = com.dash.headunit.weather.WeatherHub
            val d = hub.data
            out.add("net: hub data=${d != null} source=${d?.source} err=${hub.error} loading=${hub.loading}")
        } catch (e: Exception) {
            out.add("net: hub err: ${e.message}")
        }
        return out
    }

    private fun httpProbe(url: String): String {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) {
                "HTTP $code"
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                "HTTP 200 len=${body.length} :: ${body.take(160)}"
            }
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    // ---------- Климат-проба ----------

    /**
     * Состояние HVAC: все ключевые свойства группы 16 по карте из дампа
     * (1 A/C, 3 off, 4 auto, 6 mode, 8 recirc, 10/13 уставка, 12 fan,
     * 18 power, 24 defrost, 19 outside) + разобранный [ClimateHub.ClimateState].
     */
    private fun readEcho() {
        if (job?.isActive == true) return
        job = scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val reader = DesayCarReader(applicationContext)
                    if (!reader.isAvailable()) {
                        emit("hvac: сервис НЕ НАЙДЕН")
                        return@withContext
                    }
                    val keys = listOf(
                        1 to "A/C",
                        3 to "climate_off(инв)",
                        4 to "AUTO",
                        6 to "mode(1 лицо/2 ноги/5 лоб)",
                        8 to "recirc(инв:0 вкл)",
                        10 to "t°вод",
                        12 to "fan 0..7",
                        13 to "t°пас",
                        18 to "power",
                        19 to "t°заборт",
                        24 to "defrost_front"
                    )
                    emit("=== Климат (Desay group 16) ===")
                    for ((id, label) in keys) {
                        val raw = reader.readRaw(16, id)
                        val bytes = raw?.joinToString(",") { (it and 0xFF).toString() } ?: "null"
                        val int = if (raw == null) "null" else CarScaling.bytesToInt(raw).toString()
                        emit("hvac(16,$id) $label = [$bytes] int=$int")
                    }
                    emit("=== Климат (ClimateHub разбор) ===")
                    ClimateHub.init(applicationContext)
                    withContext(Dispatchers.Main) { ClimateHub.refresh() }
                    var parsed: ClimateHub.ClimateState? = null
                    var waited = 0
                    while (parsed == null && waited < 6000) {
                        Thread.sleep(250)
                        waited += 250
                        parsed = ClimateHub.data
                    }
                    emit("hvac: parsed=${parsed ?: "null (опрос не вернулся за 6 с)"}")
                } catch (e: Exception) {
                    emit("hvac: ОШИБКА ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /**
     * Проба записи БЕЗ изменения состояния: читаем (16,10) и пишем то же
     * значение обратно в (16,10). Если transact принят и эхо не изменилось —
     * писать в уставку ClimateHub можно. Для проверки РЕАЛЬНОЙ записи можно
     * один раз нажать при изменённой вручную уставке (см. readEcho до/после).
     * Машину ставить на паркинг.
     */
    private fun writeProbe() {
        if (job?.isActive == true) return
        job = scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val reader = DesayCarReader(applicationContext)
                    if (!reader.isAvailable()) {
                        emit("hvac-проба: сервис НЕ НАЙДЕН")
                        return@withContext
                    }
                    val before = reader.readRaw(16, 10)
                    if (before == null || before.isEmpty()) {
                        emit("hvac-проба: эхо не читается, писать нечего — стоп")
                        return@withContext
                    }
                    val value = CarScaling.bytesToInt(before)
                    emit("hvac-проба: (16,10) до = $value, пишу то же значение в (16,10)…")
                    val ok = reader.write(16, 10, value)
                    Thread.sleep(1500)
                    val after = reader.readRaw(16, 10)
                    val afterInt = if (after == null) "null" else CarScaling.bytesToInt(after).toString()
                    emit("hvac-проба: transact принят=$ok, (16,10) после = $afterInt")
                    emit(
                        if (ok && afterInt == value.toString())
                            "hvac-проба: ЗАПИСЬ ПРИНИМАЕТСЯ (значение стабильно)"
                        else if (ok)
                            "hvac-проба: transact принят, но эхо изменилось ($value → $afterInt) — проверить"
                        else
                            "hvac-проба: запись отклонена (нужен root/системное приложение)"
                    )
                    emit("hvac-проба: для проверки реальной записи: сдвиньте уставку на штатном климате на +1° и нажмите «Климат: состояние» — значение 16,10 должно измениться; затем нажмите эту пробу ещё раз.")
                } catch (e: Exception) {
                    emit("hvac-проба: ОШИБКА ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    private fun saveToFile() {
        try {
            val name = SimpleDateFormat("'dash_car_dump_'yyyyMMdd_HHmmss'.txt'", Locale.US)
                .format(Date())
            val file = File(getExternalFilesDir(null), name)
            file.writeText(lines.joinToString("\n"))
            // Дублируем в корень sdcard для лёгкого доступа.
            try {
                File("/sdcard/$name").writeText(lines.joinToString("\n"))
            } catch (e: Exception) {
                // Не критично.
            }
            Toast.makeText(this, "Сохранено: $name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
