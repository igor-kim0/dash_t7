package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.elm.ElmHub
import com.dash.headunit.elm.IgnitionMonitor
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.ui.DashDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Виджет «Зажигание»: пропуски по цилиндрам (прирост на 1000 км за 7 дней,
 * мало пробега — на час работы, мало журнала — за поездку), перекос между
 * цилиндрами, ошибки P030x, откат по детонации, зона пропусков, Mode 06.
 * Данные — [IgnitionMonitor] (опрашивает [ElmHub]); тап — история по дням.
 */
class IgnitionWidgetView(
    context: Context,
    private val theme: DashTheme
) : LinearLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val statusView = TextView(context)
    private val bars = BarsView(context)
    private val captionView = TextView(context)
    private val dtcView = TextView(context)
    private val retardView = TextView(context)
    private val areaView = TextView(context)
    private val mode06View = TextView(context)

    /** Журнал с диска — перечитывается в фоне раз в [JOURNAL_MS]. */
    @Volatile
    private var rows: List<IgnitionMonitor.Row> = emptyList()
    private var journalAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(8))

        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_ignition)
            scaleType = ImageView.ScaleType.CENTER
            setColorFilter(theme.accent)
        }, LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) })
        head.addView(TextView(context).apply {
            text = context.getString(R.string.widget_ignition)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            letterSpacing = 0.08f
            setTextColor(theme.secondaryText)
        })
        statusView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(theme.secondaryText)
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        head.addView(statusView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(bars, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        captionView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(theme.secondaryText)
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
        }
        addView(captionView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        for (v in listOf(dtcView, retardView, areaView, mode06View)) {
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            v.setTextColor(theme.primaryText)
            v.maxLines = 1
            v.ellipsize = TextUtils.TruncateAt.END
            addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }
        setOnClickListener { showHistory() }
        ElmHub.start(context)
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        IgnitionMonitor.liveWanted = true
        journalAt = 0L
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        IgnitionMonitor.liveWanted = false
        super.onDetachedFromWindow()
    }

    private fun reloadJournal() {
        val now = System.currentTimeMillis()
        if (now - journalAt < JOURNAL_MS) return
        journalAt = now
        val app = context.applicationContext
        io.execute {
            val r = IgnitionMonitor.journal(app)
            handler.post {
                rows = r
                if (isAttachedToWindow) render()
            }
        }
    }

    /** Что рисуют столбики: значения по цилиндрам, подпись, перекос. */
    private class View4(val values: DoubleArray, val caption: String, val skew: Int?, val decimals: Int)

    private fun chooseView(s: IgnitionMonitor.Snapshot, now: Long): View4? {
        val r = rows
        for ((days, label) in listOf(7 to R.string.ign_window_7d, 30 to R.string.ign_window_30d)) {
            val g = IgnitionMonitor.growth(r, now - days * DAY_MS) ?: continue
            val skew = IgnitionMonitor.skewedCylinder(g.perCyl)
            g.per1000km()?.let {
                return View4(it, context.getString(R.string.ign_caption_km, context.getString(label)), skew, 0)
            }
            g.perHour()?.let {
                return View4(it, context.getString(R.string.ign_caption_hour, context.getString(label)), skew, 1)
            }
        }
        val c = s.counters ?: return null
        val base = s.tripBase
        if (base != null) {
            val d = IntArray(IgnitionMonitor.CYL) { (c[it] - base[it]).coerceAtLeast(0) }
            if (d.sum() > 0) {
                return View4(DoubleArray(d.size) { d[it].toDouble() }, context.getString(R.string.ign_caption_trip),
                    IgnitionMonitor.skewedCylinder(d), 0)
            }
        }
        return View4(DoubleArray(c.size) { c[it].toDouble() }, context.getString(R.string.ign_caption_total), null, 0)
    }

    private fun render() {
        reloadJournal()
        val now = System.currentTimeMillis()
        val s = IgnitionMonitor.snapshot
        val fresh = s.readAt > 0 && now - s.readAt < STALE_MS
        statusView.text = when {
            ElmHub.address() == null -> context.getString(R.string.ign_no_elm)
            fresh -> context.getString(R.string.ign_updated, TIME.format(Date(s.readAt)))
            s.readAt > 0 -> context.getString(R.string.ign_old, TIME.format(Date(s.readAt)))
            else -> context.getString(R.string.ign_waiting)
        }
        val view = chooseView(s, now)
        if (view == null) {
            bars.set(null, null, 0)
            captionView.text = context.getString(R.string.ign_no_counters)
        } else {
            bars.set(view.values, view.skew, view.decimals)
            captionView.text = view.caption + (view.skew?.let { "  ⚠ " + context.getString(R.string.ign_skew, it + 1) } ?: "")
            captionView.setTextColor(if (view.skew != null) FUEL_RED else theme.secondaryText)
        }

        // Ошибки
        val pending = s.pending
        val stored = s.stored
        if (pending == null && stored == null) {
            dtcView.text = context.getString(R.string.ign_dtc_label) + " —"
            dtcView.setTextColor(theme.primaryText)
        } else {
            val all = (stored.orEmpty() + pending.orEmpty()).distinct()
            val mis = all.filter { it.startsWith("P030") }
            val other = all.size - mis.size
            val parts = ArrayList<String>()
            for (code in mis) {
                val p = pending.orEmpty().contains(code) && !stored.orEmpty().contains(code)
                parts += code + if (p) " (" + context.getString(R.string.ign_dtc_pending) + ")" else ""
            }
            if (other > 0) parts += context.getString(R.string.ign_dtc_other, other)
            dtcView.text = context.getString(R.string.ign_dtc_label) + " " +
                parts.ifEmpty { listOf(context.getString(R.string.ign_none)) }.joinToString(", ")
            dtcView.setTextColor(if (mis.isNotEmpty()) FUEL_RED else if (other > 0) theme.accent else theme.success)
        }

        // Откат
        retardView.text = context.getString(R.string.ign_retard_label) + " " +
            (s.retardDeg?.joinToString(" / ") { String.format(Locale.US, "%.1f", it) }?.plus("°") ?: "—")
        retardView.setTextColor(
            if ((s.retardDeg?.maxOrNull() ?: 0.0) >= RETARD_WARN_DEG) theme.accent else theme.primaryText
        )

        // Зона пропусков
        val a = s.area
        areaView.text = context.getString(R.string.ign_area_label) + " " + when {
            a == null -> "—"
            a.empty -> context.getString(R.string.ign_area_none)
            else -> context.getString(
                R.string.ign_area_value, a.rpmMin, a.rpmMax, a.loadMin.toInt(), a.loadMax.toInt()
            )
        }

        // Mode 06
        val m = s.mode06
        mode06View.text = context.getString(R.string.ign_mode06_label) + " " + when {
            m == null -> "—"
            m.isEmpty() -> context.getString(R.string.ign_mode06_none)
            else -> mode06Text(m)
        }
    }

    /** «цикл 0/0/3/0 · ср. 0/0/1/0» по MID A2..A5 (TID 0C / 0B). */
    private fun mode06Text(m: Map<Int, List<IgnitionMonitor.Mode06Test>>): String {
        fun col(tid: Int) = (0xA2..0xA5).joinToString("/") { mid ->
            m[mid]?.firstOrNull { it.tid == tid }?.value?.toString() ?: "?"
        }
        return context.getString(R.string.ign_mode06_value, col(0x0C), col(0x0B))
    }

    /** История: прирост по дням, свежие сверху. */
    private fun showHistory() {
        val r = IgnitionMonitor.journal(context)
        val pad = dp(16)
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val cal = Calendar.getInstance()
        val days = IgnitionMonitor.daily(r) { ts ->
            cal.timeInMillis = ts
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
        fun line(text: String, color: Int, size: Float, bold: Boolean = false) {
            list.addView(TextView(context).apply {
                this.text = text
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                if (bold) typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(4), 0, 0)
            })
        }
        line(context.getString(R.string.ign_history_hint), theme.secondaryText, 13f)
        r.lastOrNull()?.let { last ->
            line(
                context.getString(R.string.ign_history_total, last.counters.joinToString(" / ")),
                theme.primaryText, 15f
            )
        }
        if (days.isEmpty()) line(context.getString(R.string.ign_history_empty), theme.secondaryText, 15f)
        val dateFmt = SimpleDateFormat("d MMMM, EEE", Locale("ru"))
        for ((day, g) in days) {
            val skew = IgnitionMonitor.skewedCylinder(g.perCyl)
            line(dateFmt.format(Date(day)), theme.accent, 15f, bold = true)
            line(
                context.getString(R.string.ign_history_day, g.perCyl.joinToString(" / "), g.km?.toString() ?: "?") +
                    (skew?.let { "  ⚠ " + context.getString(R.string.ign_skew, it + 1) } ?: ""),
                if (skew != null) FUEL_RED else theme.primaryText, 15f
            )
        }
        val scroll = android.widget.ScrollView(context).apply { addView(list) }
        DashDialog.Builder(context)
            .setTitle(R.string.ign_history_title)
            .setView(scroll)
            .setPositiveButton(R.string.dialog_ok, null)
            .showFullScreen()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** Четыре столбика «Ц1..Ц4» с числом над каждым; виновник перекоса — красный. */
    private inner class BarsView(context: Context) : View(context) {
        private var values: DoubleArray? = null
        private var skew: Int? = null
        private var decimals = 0
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.secondaryText
            alpha = 40
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = theme.secondaryText
        }
        private val rect = RectF()

        fun set(v: DoubleArray?, skew: Int?, decimals: Int) {
            values = v
            this.skew = skew
            this.decimals = decimals
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val n = IgnitionMonitor.CYL
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            val label = (h * 0.16f).coerceIn(dp(10).toFloat(), dp(16).toFloat())
            labelPaint.textSize = label
            textPaint.textSize = label * 1.2f
            val top = textPaint.textSize + dp(4)
            val bottom = h - label - dp(4)
            val slot = w / n
            val barW = (slot * 0.45f).coerceAtMost(dp(48).toFloat())
            val v = values
            val max = v?.maxOrNull()?.takeIf { it > 0 } ?: 1.0
            val r = dp(4).toFloat()
            for (i in 0 until n) {
                val cx = slot * i + slot / 2
                rect.set(cx - barW / 2, top, cx + barW / 2, bottom)
                canvas.drawRoundRect(rect, r, r, trackPaint)
                if (v != null) {
                    val frac = (v[i] / max).toFloat().coerceIn(0f, 1f)
                    val y = bottom - (bottom - top) * frac
                    barPaint.color = if (skew == i) FUEL_RED else theme.accent
                    if (bottom - y >= 1f) {
                        rect.set(cx - barW / 2, y, cx + barW / 2, bottom)
                        canvas.drawRoundRect(rect, r, r, barPaint)
                    }
                    textPaint.color = if (skew == i) FUEL_RED else theme.primaryText
                    canvas.drawText(String.format(Locale.US, "%.${decimals}f", v[i]), cx, y - dp(3), textPaint)
                }
                canvas.drawText(context.getString(R.string.ign_cyl, i + 1), cx, h - dp(2), labelPaint)
            }
        }
    }

    private companion object {
        const val TICK_MS = 2_000L
        const val JOURNAL_MS = 60_000L
        const val STALE_MS = 5 * 60_000L
        const val DAY_MS = 24 * 3600_000L
        /** Откат от этого значения (°) подсвечиваем. */
        const val RETARD_WARN_DEG = 3.0
        val TIME = SimpleDateFormat("HH:mm", Locale.US)
        val io = Executors.newSingleThreadExecutor { Thread(it, "dash-ignition-io").apply { isDaemon = true } }
    }
}
