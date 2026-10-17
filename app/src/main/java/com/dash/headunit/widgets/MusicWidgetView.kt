package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Outline
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dash.headunit.R
import com.dash.headunit.music.MusicHub
import com.dash.headunit.ui.DashDialog
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard

/**
 * Виджет «Музыка»: управление воспроизведением с телефона по Bluetooth
 * (AVRCP) или локального плеера — через активную media-сессию.
 *
 * Слева обложка (только если плеер её прислал — BT AVRCP обычно нет), далее строки источник / трек / исполнитель, тонкий
 * прогресс; справа транспорт: назад, play/pause и акцентная кнопка
 * перехода к следующей композиции.
 *
 * Требуется доступ к уведомлениям ([com.dash.headunit.music.MediaListenerService]).
 */
class MusicWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private val artView = ImageView(context)
    private val sourceView = TextView(context)
    private val titleView = TextView(context)
    private val artistView = TextView(context)
    private val progressFill = View(context)
    private val progressEmpty = View(context)
    private val timeElapsedView = TextView(context)
    private val timeTotalView = TextView(context)
    private val btnPrev = ImageButton(context)
    private val btnPlay = ImageButton(context)
    private val btnNext = ImageButton(context)
    private val accessBtn = Button(context)

    private val hubListener: () -> Unit = { render() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Тап по свободной области: без доступа — открыть настройки
        // уведомлений, иначе — обновить. Доступ проверяем напрямую
        // по Settings.Secure (надёжнее флага опроса сессий).
        // Плюс явная кнопка: по корпусу можно и промахнуться.
        setOnClickListener { onBodyTap() }

        // Обложка со скруглением; без картинки скрыта — заглушка-квадрат не нужна.
        artView.apply {
            visibility = GONE
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                }
            }
        }
        addView(artView, LayoutParams(dp(84), dp(84)).apply { marginEnd = dp(12) })

        // Тексты.
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(12), 0)
        }
        sourceView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            letterSpacing = 0.08f
        }
        titleView.apply {
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            isSingleLine = true
        }
        artistView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            isSingleLine = true
        }
        texts.addView(sourceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        texts.addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        texts.addView(artistView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val progress = LinearLayout(context).apply { orientation = HORIZONTAL }
        progressFill.setBackgroundColor(theme.accent)
        progressEmpty.setBackgroundColor(theme.cardBorder)
        progress.addView(progressFill, LayoutParams(0, dp(3), 0f))
        progress.addView(progressEmpty, LayoutParams(0, dp(3), 1f))
        // Строка времени: текущая позиция трека слева, длительность справа.
        timeElapsedView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        timeTotalView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        texts.addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
        val times = LinearLayout(context).apply { orientation = HORIZONTAL }
        times.addView(timeElapsedView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        times.addView(View(context), LayoutParams(0, 0, 1f))
        times.addView(timeTotalView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        texts.addView(times, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        accessBtn.apply {
            text = context.getString(R.string.music_enable_access)
            textSize = 14f
            setTextColor(theme.background)
            background = ThemeCard.accent(context, theme)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener { openListenerSettings() }
            visibility = GONE
        }
        texts.addView(accessBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        // Транспорт: назад / play-pause / ВПЕРЁД (акцент, крупнее) —
        // квадратные кнопки, выровнены по нижнему краю.
        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        styleTransportButton(btnPrev, R.drawable.ic_prev, ThemeCard.square(theme.cardBorder, 12, context), theme.secondaryText, 52)
        styleTransportButton(btnPlay, R.drawable.ic_play, ThemeCard.square(theme.cardBorder, 12, context), theme.primaryText, 56)
        styleTransportButton(btnNext, R.drawable.ic_next, ThemeCard.square(theme.accent, 12, context), theme.background, 66)
        btnPrev.setOnClickListener { MusicHub.prev() }
        btnPlay.setOnClickListener { MusicHub.toggle() }
        btnNext.setOnClickListener { MusicHub.next() }
        controls.addView(btnPrev)
        controls.addView(btnPlay.apply { setPadding(0, 0, dp(6), 0) })
        controls.addView(btnNext)
        addView(controls, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        render()
    }

    /**
     * Квадратная кнопка транспорта: фон-квадрат с мягким радиусом,
     * иконка по центру.
     */
    private fun styleTransportButton(
        btn: ImageButton,
        iconRes: Int,
        bg: android.graphics.drawable.Drawable,
        tint: Int,
        sizeDp: Int
    ) {
        val s = dp(sizeDp)
        btn.apply {
            setImageResource(iconRes)
            background = bg
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LayoutParams(s, s).apply {
                marginEnd = dp(6)
            }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    private fun onBodyTap() {
        if (MusicHub.data == null && MusicHub.needsAccess(context)) {
            openListenerSettings()
        } else {
            MusicHub.refresh()
        }
    }

    /**
     * Включение доступа: сначала пробуем самовключение (если выдано
     * WRITE_SECURE_SETTINGS); иначе сразу диалог с adb-командами —
     * экран настроек на ГУ обрезан, а NMS принимает разрешение только
     * от system/shell (см. MusicHub.enableSelf).
     */
    private fun openListenerSettings() {
        // 1. Самовключение без экранов, если есть право.
        if (MusicHub.enableSelf(context) == true) {
            Toast.makeText(context, R.string.music_access_self_ok, Toast.LENGTH_SHORT).show()
            MusicHub.refresh()
            render()
            return
        }
        // 2. Диалог: рабочая adb-команда + кнопка открытия экрана, если он есть.
        val settingsIntent = android.content.Intent(
            android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
        )
        val canOpen = try {
            context.packageManager.resolveActivity(
                settingsIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            ) != null
        } catch (e: Exception) {
            false
        }
        val builder = DashDialog.Builder(context)
            .setTitle(R.string.music_access_title)
            .setMessage(R.string.music_access_text)
            .setPositiveButton(R.string.dialog_ok, null)
        if (canOpen) {
            builder.setNeutralButton(R.string.music_access_open_settings) { _, _ ->
                try {
                    context.startActivity(
                        settingsIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    // Экран есть в манифесте, но не открывается — тишина.
                }
            }
        }
        builder.showFullScreen()
    }

    // ---------- отрисовка ----------

    private fun render() {
        val d = MusicHub.data
        if (d == null) {
            artView.setImageDrawable(null)
            artView.visibility = GONE
            sourceView.text = context.getString(R.string.music_bluetooth)
            val needAccess = MusicHub.needsAccess(context)
            titleView.text = if (needAccess) {
                context.getString(R.string.music_no_access)
            } else {
                context.getString(R.string.music_nothing)
            }
            artistView.text = ""
            setProgress(0f)
            timeElapsedView.text = ""
            timeTotalView.text = ""
            btnPlay.setImageResource(R.drawable.ic_play)
            accessBtn.visibility = if (needAccess) VISIBLE else GONE
            alpha = 1f
            return
        }
        accessBtn.visibility = GONE
        if (d.art != null) {
            artView.setImageBitmap(d.art)
            artView.visibility = VISIBLE
        } else {
            artView.setImageDrawable(null)
            artView.visibility = GONE
        }
        sourceView.text = friendlySource(d.sourceLabel)
        titleView.text = d.title
        artistView.text = d.artist ?: d.album
        ?: context.getString(R.string.music_bluetooth_audio)
        btnPlay.setImageResource(
            if (d.playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        if (d.durationMs > 0 && d.positionMs >= 0) {
            setProgress((d.positionMs.toFloat() / d.durationMs).coerceIn(0f, 1f))
            timeElapsedView.text = formatTime(d.positionMs)
            timeTotalView.text = formatTime(d.durationMs)
        } else {
            setProgress(if (d.playing) 1f else 0f)
            timeElapsedView.text = ""
            timeTotalView.text = ""
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms < 0) return context.getString(R.string.music_time_unknown)
        val totalSec = ms / 1000
        return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
    }

    private fun setProgress(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        (progressFill.layoutParams as LayoutParams).weight = f
        (progressEmpty.layoutParams as LayoutParams).weight = 1f - f
        progressFill.requestLayout()
        progressEmpty.requestLayout()
    }

    private fun friendlySource(pkg: String?): String {
        if (pkg == null) return context.getString(R.string.music_bluetooth)
        val lower = pkg.lowercase()
        return when {
            "bluetooth" in lower -> context.getString(R.string.music_bluetooth)
            "yandex" in lower && "music" in lower ->
                context.getString(R.string.music_yandex)
            else -> pkg.uppercase()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MusicHub.init(context)
        MusicHub.addListener(hubListener)
        MusicHub.ensurePolling()
    }

    override fun onDetachedFromWindow() {
        MusicHub.removeListener(hubListener)
        super.onDetachedFromWindow()
    }
}
