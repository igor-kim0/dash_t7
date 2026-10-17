package com.dash.headunit.widgets

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.car.VhalReader
import com.dash.headunit.theme.DashTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Виджет «Справочник»: свойства Vehicle HAL (AOSP + вендорские Chery)
 * с полным ID и живым значением с ГУ.
 *
 * ID — настоящие 32-битные AOSP VehicleProperty (группа | тип | зона | номер),
 * а не обрезанные «0x1160»-формы из сторонних списков. Тип значения берётся
 * из битов ID. Зонные свойства читаются по каждой зоне.
 * Опрос каждые 3 с, тап — сразу. Нет VHAL/свойства — «—».
 */
class ReferenceWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private val values = mutableMapOf<Prop, TextView>()
    private var reader: VhalReader? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL

        addView(TextView(context).apply {
            text = context.getString(R.string.widget_reference)
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val rows = LinearLayout(context).apply { orientation = VERTICAL }
        for (section in SECTIONS) {
            rows.addView(TextView(context).apply {
                text = section.title
                setTextColor(theme.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(8), 0, dp(2))
            })
            for (prop in section.props) rows.addView(row(prop))
        }
        // Строк много — список прокручивается (в режиме редактирования
        // касания перехватывает рамка, скролл не мешает драгу).
        val scroll = android.widget.ScrollView(context).apply { addView(rows) }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        setOnClickListener { refresh() }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    /** Строка: имя + ID, справа значение; ниже описание. */
    private fun row(prop: Prop): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val idText = prop.id?.let { "0x%08X · %d".format(Locale.US, it, it) } ?: "вендорский"
        line.addView(TextView(context).apply {
            text = "${prop.name}  $idText"
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(context).apply {
            text = if (prop.id == null) "ID не известен" else "—"
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), 0, 0, 0)
        }
        line.addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        values[prop] = value
        box.addView(line)
        box.addView(TextView(context).apply {
            text = prop.desc
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        return box
    }

    private fun ensurePolling() {
        if (pollJob == null) {
            pollJob = scope.launch {
                while (true) {
                    refresh()
                    delay(POLL_MS)
                }
            }
        }
    }

    private fun refresh() {
        val r = reader ?: VhalReader(context.applicationContext).also { reader = it }
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                values.keys.filter { it.id != null }.associateWith { read(r, it) }
            }
            if (!isAttachedToWindow) return@launch
            for ((prop, text) in data) values[prop]?.text = text
        }
    }

    /** Значение по всем зонам свойства; null у зоны — «—». */
    private fun read(r: VhalReader, prop: Prop): String {
        val id = prop.id ?: return "—"
        val parts = prop.areas.map { (label, area) ->
            val raw: Any? = when (id and TYPE_MASK) {
                TYPE_BOOLEAN -> r.getBooleanProperty(id, area)
                TYPE_INT32 -> r.getIntProperty(id, area)
                TYPE_FLOAT -> r.getFloatProperty(id, area)
                else -> null
            }
            val v = raw?.let { prop.format(it) } ?: "—"
            if (label.isEmpty()) v else "$label $v"
        }
        return parts.joinToString(" · ")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        pollJob = null
        ensurePolling()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        pollJob = null
        reader = null
        super.onDetachedFromWindow()
    }

    /** Свойство VHAL; [id] null — вендорское без известного ID. */
    class Prop(
        val name: String,
        val id: Int?,
        val desc: String,
        val areas: List<Pair<String, Int>> = GLOBAL,
        val format: (Any) -> String = ::plain
    )

    class Section(val title: String, val props: List<Prop>)

    companion object {
        private const val POLL_MS = 3000L

        // Биты типа значения в ID свойства (VehiclePropertyType).
        private const val TYPE_MASK = 0x00FF0000
        private const val TYPE_BOOLEAN = 0x00200000
        private const val TYPE_INT32 = 0x00400000
        private const val TYPE_FLOAT = 0x00600000

        private val GLOBAL = listOf("" to 0)
        // VehicleAreaSeat / Door / Window / Wheel.
        private val SEATS_FRONT = listOf("вод" to 0x1, "пас" to 0x4)
        private val DOORS = listOf("ПЛ" to 0x1, "ПП" to 0x4, "ЗЛ" to 0x10, "ЗП" to 0x40)
        private val WINDOWS = listOf("ПЛ" to 0x10, "ПП" to 0x40, "ЗЛ" to 0x100, "ЗП" to 0x400)
        private val WHEELS = listOf("ПЛ" to 0x1, "ПП" to 0x2, "ЗЛ" to 0x4, "ЗП" to 0x8)

        private fun plain(v: Any): String = when (v) {
            is Boolean -> if (v) "да" else "нет"
            is Float -> if (v.isFinite()) "%.1f".format(Locale.US, v) else v.toString()
            else -> v.toString()
        }

        private fun speedKmh(v: Any): String =
            (v as? Float)?.let { "%.0f км/ч".format(Locale.US, it * 3.6f) } ?: plain(v)

        /** VehicleGear. */
        private fun gear(v: Any): String = when (v) {
            1 -> "N"
            2 -> "R"
            4 -> "P"
            8 -> "D"
            else -> v.toString()
        }

        /** VehicleIgnitionState. */
        private fun ignition(v: Any): String = when (v) {
            0 -> "?"
            1 -> "LOCK"
            2 -> "OFF"
            3 -> "ACC"
            4 -> "ON"
            5 -> "START"
            else -> v.toString()
        }

        val SECTIONS = listOf(
            Section("Двигатель и динамика", listOf(
                Prop("PERF_VEHICLE_SPEED", 0x11600207, "Скорость (VHAL отдаёт м/с, тут км/ч)", format = ::speedKmh),
                Prop("ENGINE_RPM", 0x11600305, "Обороты двигателя"),
                Prop("ENGINE_COOLANT_TEMP", 0x11600301, "Температура ОЖ, °C"),
                Prop("ENGINE_OIL_TEMP", 0x11600304, "Температура масла, °C"),
                Prop("FUEL_LEVEL", 0x11600307, "Уровень топлива (AOSP — мл, у вендоров бывает %)"),
                Prop("FUEL_LEVEL_LOW", 0x11200405, "Лампа «резерв»"),
                Prop("GEAR_SELECTION", 0x11400400, "Селектор (P/R/N/D)", format = ::gear),
                Prop("IGNITION_STATE", 0x11400409, "Зажигание", format = ::ignition),
                Prop("PERF_ODOMETER", 0x11600204, "Одометр, км"),
                Prop("EV_BATTERY_LEVEL", 0x11600309, "Заряд ВВ-батареи, Вт·ч (PHEV/EV)"),
            )),
            Section("Кузов и комфорт", listOf(
                Prop("DOOR_POS", 0x16400B00, "Двери: 0 закрыта, >0 открыта", DOORS),
                Prop("WINDOW_POS", 0x13400BC0, "Положение стёкол", WINDOWS),
                Prop("HVAC_TEMPERATURE_SET", 0x15600503, "Уставка климата, °C", SEATS_FRONT),
                Prop("HVAC_FAN_SPEED", 0x15400500, "Скорость вентилятора", listOf("" to 0x1)),
                Prop("HVAC_AC_ON", 0x15200505, "Компрессор кондиционера", listOf("" to 0x1)),
                Prop("HVAC_SEAT_TEMPERATURE", 0x1540050B, "Подогрев (+) / вентиляция (−) сидений", SEATS_FRONT),
                Prop("HVAC_STEERING_WHEEL_HEAT", 0x1140050D, "Подогрев руля"),
                Prop("AMBIENT_LIGHT_COLOR", null, "Цвет контурной подсветки"),
                Prop("TRUNK_RELEASE", null, "Открытие багажника (в AOSP такого свойства нет)"),
            )),
            Section("Безопасность и шасси", listOf(
                Prop("TIRE_PRESSURE", 0x17600309, "Давление в шинах, кПа", WHEELS),
                Prop("PERF_STEERING_ANGLE", 0x11600209, "Угол поворота руля, °"),
                Prop("SEAT_BELT_BUCKLED", 0x15200B82, "Ремень пристёгнут", SEATS_FRONT),
                Prop("CRUISE_CONTROL_ENABLED", 0x1120100F, "Круиз-контроль (Android 14+)"),
                Prop("CRUISE_CONTROL_TARGET_SPEED", 0x11601013, "Скорость круиза (Android 14+)", format = ::speedKmh),
                Prop("LANE_KEEP_ASSIST_ENABLED", 0x11201008, "Удержание в полосе (Android 14+)"),
            )),
            Section("Вендорские Chery (0x2xxxxxxx)", listOf(
                Prop("DRIVE_MODE", null, "Eco / Normal / Sport"),
                Prop("AIR_QUALITY", null, "PM2.5 (если есть датчик)"),
                Prop("360_VIEW_MODE", null, "Ракурс камер кругового обзора"),
                Prop("VOICE_ASSISTANT", null, "Статус голосового помощника"),
            )),
        )
    }
}
