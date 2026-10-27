package com.dash.headunit

import android.Manifest
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Region
import android.net.wifi.WifiManager
import android.widget.ScrollView
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dash.headunit.dashboard.DashboardGridLayout
import com.dash.headunit.dashboard.DashboardStore
import com.dash.headunit.dashboard.WidgetFrame
import com.dash.headunit.dashboard.WidgetSettingsStore
import com.dash.headunit.dashboard.WidgetAvailability
import com.dash.headunit.dashboard.WidgetSpec
import com.dash.headunit.dashboard.WidgetType
import com.dash.headunit.elm.ElmHub
import com.dash.headunit.car.CarDebugActivity
import com.dash.headunit.car.DesayPushProbe
import com.dash.headunit.car.TripTracker
import com.dash.headunit.navi.AdbShell
import com.dash.headunit.navi.HoleBackground
import com.dash.headunit.navi.NaviUnderlay
import com.dash.headunit.ui.DashDialog
import com.dash.headunit.navi.cluster.ClusterOverlay
import com.dash.headunit.navi.cluster.ClusterSetup
import com.dash.headunit.settings.AppSettings
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard
import com.dash.headunit.theme.ThemeStore
import com.dash.headunit.widgets.CarWidgetView
import com.dash.headunit.widgets.ClockWidgetView
import com.dash.headunit.widgets.ComboWidgetView
import com.dash.headunit.widgets.ForecastStripView
import com.dash.headunit.widgets.MusicWidgetView
import com.dash.headunit.widgets.WifiWidgetView
import com.dash.headunit.widgets.ReferenceWidgetView
import com.dash.headunit.widgets.AppWidgetView
import com.dash.headunit.widgets.ButtonsWidgetView
import com.dash.headunit.widgets.NavApps
import com.dash.headunit.widgets.TripWidgetView
import com.dash.headunit.widgets.ClimateWidgetView
import com.dash.headunit.widgets.HeatWidgetView
import com.dash.headunit.widgets.OutsideWidgetView
import com.dash.headunit.widgets.ElmWidgetView
import com.dash.headunit.widgets.LearnWidgetView
import com.dash.headunit.widgets.RetroSpeedometerView
import com.dash.headunit.widgets.SpeedWidgetView
import com.dash.headunit.widgets.VazClusterView
import com.dash.headunit.widgets.DesayPushTestWidgetView

/**
 * Главный экран дашборда ГУ (альбомная ориентация, иммерсивный режим).
 *
 * - Строит виджеты из [DashboardStore] на сетке 100x70.
 * - Иконка сетки включает режим редактирования: перемещение/ресайз,
 *   удаление (бейдж ✕), добавление блоков, очистка, сохранение.
 * - Геолокация запрашивается для виджета погоды.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var dashboard: DashboardGridLayout
    private lateinit var barNormal: View
    private lateinit var barEdit: View
    private lateinit var btnEdit: ImageButton
    private lateinit var iconWifi: ImageView
    private lateinit var iconBluetooth: ImageView
    private lateinit var store: DashboardStore
    private lateinit var settingsStore: WidgetSettingsStore
    private lateinit var themeStore: ThemeStore

    private var editMode = false
    private lateinit var theme: DashTheme

    override fun onCreate(savedInstanceState: Bundle?) {
        // Пока доступна только тёмная тема — форсируем её.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ClusterOverlay.init(this)
        ClusterSetup.restoreOnStart(this)

        // Не гаснуть на ГУ + во весь экран.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Окно всегда на весь экран: не зависит ни от системных панелей, ни
        // от wm overscan (остаётся от старых версий режима навигатора).
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN or
                // Немодальное окно: иначе WMS считает зоной касаний весь стек и
                // вырез под навигатор (NaviUnderlay.installTouchHole) не работает.
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        hideSystemUi()

        store = DashboardStore(this)
        settingsStore = WidgetSettingsStore(this)
        themeStore = ThemeStore(ThemeStore.PREFS, this)
        theme = themeStore.current()
        applyThemeChrome()
        dashboard = findViewById(R.id.dashboard)
        barNormal = findViewById(R.id.bar_normal)
        barEdit = findViewById(R.id.bar_edit)
        btnEdit = findViewById(R.id.btn_edit)
        iconWifi = findViewById(R.id.icon_wifi)
        iconBluetooth = findViewById(R.id.icon_bluetooth)
        // Диалог управления Wi-Fi: сохранённые/видимые сети, добавление сети.
        findViewById<View>(R.id.icon_wifi).setOnClickListener {
            com.dash.headunit.ui.WifiConnectionDialog.show(this)
        }
        applyBarSide()

        dashboard.onLayoutChanged = { persistLayout() }

        btnEdit.setOnClickListener { setEditMode(!editMode) }
        findViewById<View>(R.id.btn_done).setOnClickListener { setEditMode(false) }
        findViewById<View>(R.id.btn_clear).setOnClickListener { confirmClearDashboard() }
        findViewById<View>(R.id.btn_add).setOnClickListener { showAddDialog() }
        // Диагностика: CAN/VHAL, борткомпьютер, приборка — все проверки на одном экране.
        findViewById<View>(R.id.btn_debug).setOnClickListener {
            startActivity(android.content.Intent(this, CarDebugActivity::class.java))
        }
        // Демо-маршрут на приборке — тоже dev (prod-сборке иконка не нужна).
        val naviDemo = findViewById<View>(R.id.btn_navi_demo)
        naviDemo.visibility = if (BuildConfig.PROD_BUILD) View.GONE else View.VISIBLE
        naviDemo.setOnClickListener { startNaviDemo() }
        findViewById<View>(R.id.btn_night).setOnClickListener {
            showThemeDialog()
        }
        findViewById<View>(R.id.btn_settings).setOnClickListener { showSettingsDialog() }
        // «Домой»: выход в штатную систему авто (домашний лаунчер).
        findViewById<View>(R.id.btn_home).setOnClickListener {
            try {
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_MAIN
                    ).apply {
                        addCategory(android.content.Intent.CATEGORY_HOME)
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(this, e.message ?: "?", Toast.LENGTH_SHORT).show()
            }
        }
        rootBackground = HoleBackground(theme.background, theme.glow).apply {
            // Углы блока навигатора — как у карточек (ThemeCard.card); рамка —
            // только в темах, где у виджетов есть карточка.
            val dp = resources.displayMetrics.density
            holeRadius = 12 * dp
            if (!theme.flatWidgets) {
                holeBorderWidth = 1 * dp
                holeBorderColor = theme.cardBorder
            }
        }
        findViewById<View>(R.id.root).background = rootBackground
        touchHoleInstalled = NaviUnderlay.installTouchHole(window.decorView) { touchHole }
        // Навигатор мог остаться в границах блока после падения.
        NaviUnderlay.resetOnStart(this)
        NaviUnderlay.installCrashReset(this)

        buildWidgets(store.load())
        updateStatusIcons()
        ensureLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        updateStatusIcons()
        refreshNavi()
    }

    override fun onStop() {
        super.onStop()
        // Сами только что подняли навигатор под себя — это не уход с экрана.
        if (System.currentTimeMillis() - underlayLaunchedAt < UNDERLAY_GRACE_MS) return
        // Дашборд закрыт другим приложением (или навигатором на весь экран):
        // вернуть экран целиком, навигатор под нами больше не гарантирован.
        underlayPkg = null
        NaviUnderlay.setHole(this, null)
    }

    /**
     * Хром в цветах текущей темы: фон, фоны кнопок панелей.
     * Виджеты получают тему при построении.
     */
    private fun applyThemeChrome() {
        val card = ThemeCard.card(this, theme)
        findViewById<View>(R.id.icon_wifi).background = card
        findViewById<View>(R.id.icon_bluetooth).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_night).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_home).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_settings).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_edit).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_navi_demo).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_debug).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_clear).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_add).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_done).background = ThemeCard.accent(this, theme)
        findViewById<ImageButton>(R.id.btn_done).setColorFilter(theme.background)
        findViewById<View>(R.id.top_glow_line).apply {
            visibility = if (theme.glow != null) View.VISIBLE else View.GONE
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(android.graphics.Color.TRANSPARENT, theme.accent, android.graphics.Color.TRANSPARENT)
            )
        }
    }

    /** Диалог выбора темы оформления. */
    private fun showThemeDialog() {
        val themes = DashTheme.ALL
        val names = themes.map { it.name }.toTypedArray()
        val checked = themes.indexOfFirst { it.id == theme.id }.takeIf { it >= 0 } ?: 0
        var selected = checked
        DashDialog.Builder(this)
            .setTitle(R.string.set_theme_title)
            .setSingleChoiceItems(names, checked) { _, which ->
                selected = which
            }
            .setPositiveButton(R.string.edit_done) { _, _ ->
                val next = themes[selected]
                if (next.id != theme.id) {
                    themeStore.set(next)
                    recreate()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /** Настройки приложения: автозапуск, сторона колонки, приборка, шторка, журнал поездок. */
    private fun showSettingsDialog() {
        val clusterModeRow = 3
        val clusterVariantRow = 4
        val journalKeepRow = 6
        val items = arrayOf(
            getString(R.string.settings_autostart),
            getString(R.string.settings_bar_left),
            getString(R.string.settings_cluster_navi),
            getString(R.string.settings_cluster_mode, clusterModeLabel(AppSettings.clusterMode(this))),
            getString(R.string.settings_cluster_variant, AppSettings.clusterVariant(this)),
            getString(R.string.settings_shade_resume),
            getString(
                R.string.settings_trip_journal_keep,
                journalKeepLabel(AppSettings.journalKeepDays(this))
            )
        )
        val checked = booleanArrayOf(
            AppSettings.autostart(this),
            AppSettings.barSideLeft(this),
            AppSettings.clusterNavi(this),
            false,
            false,
            AppSettings.shadeResume(this),
            false
        )
        DashDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setMultiChoiceItems(items, checked, routers = setOf(clusterModeRow, clusterVariantRow, journalKeepRow)) { dlg, which, isChecked ->
                when (which) {
                    clusterModeRow -> {
                        dlg.dismiss()
                        showClusterModeDialog()
                    }
                    clusterVariantRow -> {
                        dlg.dismiss()
                        showClusterVariantDialog()
                    }
                    journalKeepRow -> {
                        dlg.dismiss()
                        showJournalKeepDialog()
                    }
                    else -> checked[which] = isChecked
                }
            }
            .setPositiveButton(R.string.edit_done) { _, _ ->
                applyAutostart(checked[0])
                AppSettings.setBarSideLeft(this, checked[1])
                applyBarSide()
                applyClusterNavi(checked[2])
                // Шторка: настройка проверяется при следующем старте.
                AppSettings.setShadeResume(this, checked[5])
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /**
     * Срок хранения журнала поездок ([AppSettings.journalKeepDays]);
     * «всегда» — тоже легитимный выбор. По «Готово» сразу обрезаем журнал
     * под новый срок ([TripTracker.applyJournalKeep]) и возвращаемся
     * в настройки.
     */
    private fun showJournalKeepDialog() {
        val options = intArrayOf(7, 14, 30, 0)
        val labels = arrayOf(
            getString(R.string.journal_keep_week),
            getString(R.string.journal_keep_two_weeks),
            getString(R.string.journal_keep_month),
            getString(R.string.journal_keep_always)
        )
        var selected = options.indexOf(AppSettings.journalKeepDays(this))
            .takeIf { it >= 0 }
            ?: (options.size - 1)
        DashDialog.Builder(this)
            .setTitle(R.string.journal_keep_title)
            .setSingleChoiceItems(labels, selected) { _, which ->
                selected = which
            }
            .setPositiveButton(R.string.edit_done) { _, _ ->
                AppSettings.setJournalKeepDays(this, options[selected])
                if (options[selected] > 0) {
                    TripTracker.applyJournalKeep(applicationContext, options[selected])
                }
                showSettingsDialog()
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> showSettingsDialog() }
            .showFullScreen()
    }

    /**
     * Как показывать маршрут на приборке ([AppSettings.clusterMode]):
     * родной интерфейс или своё окно в «Картах». Если маршрут на приборке
     * включён — сразу переключаем ([ClusterOverlay.applySettings]).
     */
    private fun showClusterModeDialog() {
        val modes = intArrayOf(AppSettings.CLUSTER_NATIVE, AppSettings.CLUSTER_OVERLAY)
        val labels = arrayOf(
            getString(R.string.cluster_mode_native_full),
            getString(R.string.cluster_mode_overlay_full)
        )
        var selected = modes.indexOf(AppSettings.clusterMode(this)).coerceAtLeast(0)
        DashDialog.Builder(this)
            .setTitle(R.string.cluster_mode_title)
            .setSingleChoiceItems(labels, selected) { _, which ->
                selected = which
            }
            .setPositiveButton(R.string.edit_done) { _, _ ->
                if (modes[selected] != AppSettings.clusterMode(this)) {
                    AppSettings.setClusterMode(this, modes[selected])
                    ClusterOverlay.init(applicationContext)
                    ClusterOverlay.applySettings()
                }
                showSettingsDialog()
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> showSettingsDialog() }
            .showFullScreen()
    }

    /**
     * Вариант родного интерфейса приборки ([AppSettings.clusterVariant]) —
     * подбор на ГУ, почему подсказки пропадают с Яндексом на экране.
     * Применяется сразу ([ClusterOverlay.applySettings]).
     */
    private fun showClusterVariantDialog() {
        val labels = resources.getStringArray(R.array.cluster_variants)
        var selected = AppSettings.clusterVariant(this) - 1
        DashDialog.Builder(this)
            .setTitle(R.string.cluster_variant_title)
            .setSingleChoiceItems(labels, selected) { _, which ->
                selected = which
            }
            .setPositiveButton(R.string.edit_done) { _, _ ->
                if (selected + 1 != AppSettings.clusterVariant(this)) {
                    AppSettings.setClusterVariant(this, selected + 1)
                    ClusterOverlay.init(applicationContext)
                    ClusterOverlay.applySettings()
                }
                showSettingsDialog()
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> showSettingsDialog() }
            .showFullScreen()
    }

    /** «родной интерфейс / своё окно» — для строки настроек. */
    private fun clusterModeLabel(mode: Int): String = getString(
        if (mode == AppSettings.CLUSTER_OVERLAY) R.string.cluster_mode_overlay else R.string.cluster_mode_native
    )

    /** «неделю / 2 недели / месяц / всегда» — для строки настроек. */
    private fun journalKeepLabel(days: Int): String = getString(
        when (days) {
            7 -> R.string.journal_keep_week
            14 -> R.string.journal_keep_two_weeks
            30 -> R.string.journal_keep_month
            else -> R.string.journal_keep_always
        }
    )

    /**
     * Автозапуск держится на слушателе уведомлений: система привязывает его
     * при загрузке и поднимает наш процесс ([com.dash.headunit.settings.Autostart]).
     * При включении настройки включаем слушатель через adb-канал, если он выключен.
     */
    private fun applyAutostart(enabled: Boolean) {
        val was = AppSettings.autostart(this)
        AppSettings.setAutostart(this, enabled)
        if (!enabled || was || ClusterSetup.isListenerEnabled(this)) return
        val app = applicationContext
        Thread {
            val report = ClusterSetup.enableListener(app)
            val ok = ClusterSetup.isListenerEnabled(app)
            runOnUiThread {
                Toast.makeText(
                    app,
                    getString(if (ok) R.string.autostart_ready else R.string.autostart_no_listener, report),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    /**
     * Маршрут на приборке: при включении выдаём себе права через adb-канал
     * (оверлей, спец. возможности, уведомления) и, если маршрут уже идёт,
     * сразу показываем; при выключении — убираем с приборки.
     */
    private fun applyClusterNavi(enabled: Boolean) {
        val was = AppSettings.clusterNavi(this)
        AppSettings.setClusterNavi(this, enabled)
        if (enabled == was) return
        if (!enabled) {
            ClusterOverlay.remove()
            return
        }
        val app = applicationContext
        Thread {
            val report = ClusterSetup.ensureAll(app)
            runOnUiThread {
                Toast.makeText(app, report, Toast.LENGTH_LONG).show()
                ClusterOverlay.init(app)
                ClusterOverlay.applySettings()
            }
        }.start()
    }

    /**
     * Демо-маршрут на приборке (dev): та же команда, что и с ПК —
     * `am broadcast -a com.dash.headunit.NAVI_CLUSTER -n
     * com.dash.headunit/.diag.NaviClusterReceiver --es cmd demo`
     * (diag/NaviClusterReceiver демонстрирует маршрут 30 секунд и убирает).
     * Идёт через adb-канал ([AdbShell]), потому что приёмник защищён правом DUMP.
     */
    private fun startNaviDemo() {
        val app = applicationContext
        Thread {
            val out = AdbShell.exec(
                app,
                "am broadcast -a com.dash.headunit.NAVI_CLUSTER " +
                    "-n com.dash.headunit/.diag.NaviClusterReceiver --es cmd demo"
            )?.trim()
            runOnUiThread {
                val failed = out == null ||
                    out.contains("Error", ignoreCase = true) ||
                    out.contains("Exception", ignoreCase = true)
                if (failed) {
                    Toast.makeText(
                        app,
                        getString(R.string.navi_demo_failed, out ?: AdbShell.lastStatus),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(app, R.string.navi_demo_started, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** Перенести вертикальную колонку иконок влево или вправо по настройке. */
    private fun applyBarSide() {
        val row = findViewById<android.widget.LinearLayout>(R.id.main_row) ?: return
        val side = findViewById<View>(R.id.side_bar) ?: return
        row.removeView(side)
        if (AppSettings.barSideLeft(this)) {
            row.addView(side, 0)
        } else {
            row.addView(side)
        }
    }

    private fun updateStatusIcons() {
        // Зелёный = есть подключение, серый = включено, тусклое = выключено.
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val connected = wifi?.isWifiEnabled == true &&
                runCatching { wifi.connectionInfo?.networkId != -1 }.getOrDefault(false)
            iconWifi.setColorFilter(if (connected) theme.success else theme.secondaryText)
            iconWifi.alpha = if (wifi?.isWifiEnabled == true) 1f else 0.35f
        } catch (e: Exception) {
            iconWifi.setColorFilter(theme.secondaryText)
            iconWifi.alpha = 0.35f
        }
        try {
            val bt = (getSystemService(BluetoothManager::class.java))?.adapter
            val connected = runCatching {
                // A2DP — подключён ли аудиопрофиль (нормальное право BLUETOOTH).
                bt?.getProfileConnectionState(BluetoothProfile.A2DP) ==
                    BluetoothProfile.STATE_CONNECTED
            }.getOrDefault(false)
            iconBluetooth.setColorFilter(if (connected) theme.success else theme.secondaryText)
            iconBluetooth.alpha = if (bt?.isEnabled == true) 1f else 0.35f
        } catch (e: Exception) {
            iconBluetooth.setColorFilter(theme.secondaryText)
            iconBluetooth.alpha = 0.35f
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ---------- навигатор под дашбордом ----------

    // Фон корня с вырезом под блок навигатора.
    private lateinit var rootBackground: HoleBackground

    // Касания в этой области (координаты окна) уходят навигатору.
    private var touchHole: Region? = null
    private var touchHoleInstalled = false

    // Пакет, запущенный под дашбордом (null — под нами ничего не гарантировано:
    // ещё не запускали или дашборд с тех пор закрывали).
    private var underlayPkg: String? = null
    private var underlayLaunchedAt = 0L

    // Навигатор после launchUnder ещё ни разу не был RESUMED.
    private var resumeKickPending = false

    // Подключение adb-канала идёт (ждём «Разрешить отладку»).
    private var connecting = false

    /** Первый блок «Навигатор» на дашборде (поддерживается один). */
    private fun naviWidget(): AppWidgetView? {
        for (i in 0 until dashboard.childCount) {
            val frame = dashboard.getChildAt(i) as? WidgetFrame ?: continue
            (frame.contentRoot as? AppWidgetView)?.let { return it }
        }
        return null
    }

    /**
     * Привести режим навигатора в соответствие с состоянием: есть ли блок,
     * выбран ли навигатор, есть ли adb-канал, не идёт ли редактирование.
     * Вызывается на onResume, после раскладки и смены режима.
     */
    private fun refreshNavi() {
        val view = naviWidget()
        // Остальные блоки «Навигатор» (если их несколько) — просто карточки.
        for (i in 0 until dashboard.childCount) {
            val other = (dashboard.getChildAt(i) as? WidgetFrame)?.contentRoot as? AppWidgetView
            if (other != null && other !== view) other.showMessage(getString(R.string.app_none))
        }
        val pkg = view?.pkg().orEmpty()
        val message: String? = when {
            view == null -> ""
            pkg.isEmpty() -> getString(R.string.app_none)
            !view.installed(pkg) -> getString(R.string.navi_not_installed, pkg)
            editMode -> getString(R.string.navi_edit, view.appLabel(pkg))
            connecting -> getString(R.string.navi_connecting)
            NaviUnderlay.shellReady != true -> getString(R.string.navi_need_adb, view.appLabel(pkg))
            else -> null
        }
        if (message != null) {
            view?.showMessage(message)
            setNaviHole(null)
            // Без блока навигатора экран должен быть целым.
            if (!editMode) NaviUnderlay.setHole(this, null)
            // Канал уже разрешали раньше — подключаемся сами, без касания.
            if (view != null && NaviUnderlay.shellReady == null && !connecting &&
                NaviUnderlay.isAuthorized(this)
            ) connectShell()
            return
        }
        view!!
        if (underlayPkg != pkg) {
            if (!NaviUnderlay.launchUnder(this, pkg)) {
                view.showMessage(getString(R.string.navi_failed, NaviUnderlay.lastStatus))
                return
            }
            underlayPkg = pkg
            underlayLaunchedAt = System.currentTimeMillis()
            resumeKickPending = true
        }
        view.showActive()
        // Границы блока известны только после раскладки.
        val frame = view.parent as View
        if (frame.width == 0 || frame.height == 0) {
            frame.post { refreshNavi() }
            return
        }
        val loc = IntArray(2)
        frame.getLocationOnScreen(loc)
        val screen = Rect(loc[0], loc[1], loc[0] + frame.width, loc[1] + frame.height)
        NaviUnderlay.setHole(this, screen, pkg) { inBlock ->
            if (inBlock) runOnUiThread { kickNaviResume(pkg) }
        }
        frame.getLocationInWindow(loc)
        val inWindow = Rect(loc[0], loc[1], loc[0] + frame.width, loc[1] + frame.height)
        setNaviHole(inWindow, view.expandButton)
    }

    /**
     * Один раз после [NaviUnderlay.launchUnder]: навигатор уже в границах
     * блока — дать ему onResume (иначе карта не следит за машиной).
     */
    private fun kickNaviResume(pkg: String) {
        if (!resumeKickPending || underlayPkg != pkg || !isResumedNow()) return
        resumeKickPending = false
        // Пинок до готовности процесса навигатора пропадает впустую.
        NaviUnderlay.whenDrawn(this, pkg) {
            if (underlayPkg != pkg || !isResumedNow()) return@whenDrawn
            // Наш onPause/onStop на это время — не уход с экрана.
            underlayLaunchedAt = System.currentTimeMillis()
            NaviUnderlay.resumeInBlock(this, pkg)
        }
    }

    /** Вырезать [hole] (координаты окна) из фона и из зоны касаний дашборда. */
    private fun setNaviHole(hole: Rect?, keepTouchable: View? = null) {
        // Фон рисует корень — переводим вырез в его координаты.
        val root = findViewById<View>(R.id.root)
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        rootBackground.hole = hole?.let { Rect(it).apply { offset(-rootLoc[0], -rootLoc[1]) } }
        touchHole = hole?.let {
            Region(it).apply {
                // Кнопка «на весь экран» лежит поверх карты — её касания наши.
                if (keepTouchable != null && keepTouchable.width > 0) {
                    val loc = IntArray(2)
                    keepTouchable.getLocationInWindow(loc)
                    op(
                        Rect(loc[0], loc[1], loc[0] + keepTouchable.width, loc[1] + keepTouchable.height),
                        Region.Op.DIFFERENCE
                    )
                }
            }
        }
        // Пересчитать зону касаний окна (ViewRootImpl спрашивает её на обходе).
        window.decorView.requestLayout()
    }

    /** Подключить adb-канал; при первом разе ГУ спросит «Разрешить отладку». */
    private fun connectShell() {
        if (connecting) return
        connecting = true
        refreshNavi()
        NaviUnderlay.connect(this) { ok ->
            connecting = false
            if (isDestroyed) return@connect
            if (!ok && !NaviUnderlay.portOpen) {
                naviWidget()?.showMessage(getString(R.string.navi_port_closed))
                return@connect
            }
            refreshNavi()
        }
    }

    /** Навигатор на весь экран; вернётся в блок, когда снова откроют дашборд. */
    private fun openNaviFull() {
        val pkg = naviWidget()?.pkg().orEmpty()
        if (pkg.isNotEmpty()) NaviUnderlay.openFull(this, pkg)
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    // ---------- виджеты ----------

    private fun buildWidgets(specs: List<WidgetSpec>) {
        dashboard.removeAllViews()
        specs.forEach { spec ->
            // Продовая сборка игнорирует виджеты вне утверждённого набора.
            if (!WidgetAvailability.isEnabled(spec.type)) return@forEach
            val content: View = when (spec.type) {
                WidgetType.CLOCK -> ClockWidgetView(this, theme)
                WidgetType.COMBO -> ComboWidgetView(this, spec.widgetId, theme, settingsStore)
                WidgetType.WEATHER -> ForecastStripView(this, spec.widgetId, theme, settingsStore)
                WidgetType.CAR -> CarWidgetView(this, theme, spec.widgetId, settingsStore)
                WidgetType.MUSIC -> MusicWidgetView(this, theme)
                WidgetType.WIFI -> WifiWidgetView(this, theme)
                WidgetType.APP -> AppWidgetView(this, spec.widgetId, theme, settingsStore).apply {
                    onTapIdle = { if (!editMode) connectShell() }
                    onExpand = { openNaviFull() }
                }
                WidgetType.TRIP -> TripWidgetView(this, theme)
                WidgetType.CLIMATE -> ClimateWidgetView(this, theme)
                WidgetType.REFERENCE -> ReferenceWidgetView(this, theme)
                WidgetType.HEAT -> HeatWidgetView(this, theme)
                WidgetType.OUTSIDE -> OutsideWidgetView(this, theme)
                WidgetType.ELM -> ElmWidgetView(this, theme, spec.widgetId, settingsStore)
                WidgetType.SPEED ->
                    when (settingsStore.getOption(spec.widgetId, OPT_SPEED_STYLE, SPEED_RETRO)) {
                        SPEED_DIGITAL -> SpeedWidgetView(this, theme)
                        SPEED_VAZ -> VazClusterView(this)
                        else -> RetroSpeedometerView(this)
                    }
                WidgetType.PUSH_TEST -> DesayPushTestWidgetView(this, theme)
                WidgetType.LEARN -> LearnWidgetView(this, theme)
                WidgetType.BUTTONS -> ButtonsWidgetView(this, theme)
            }
            val frame = WidgetFrame(
                context = this,
                widgetId = spec.widgetId,
                widgetType = spec.type,
                editing = editMode,
                theme = theme,
                onGeometryChanged = { persistLayout() },
                onDelete = { removeWidget(spec.widgetId) },
                // Настройки: погода, приложение и метрики «Автомобиля».
                onSettings = when (spec.type) {
                    WidgetType.WEATHER -> {
                        { showWeatherSettings(spec.widgetId) }
                    }
                    WidgetType.APP -> {
                        { showAppSettings(spec.widgetId) }
                    }
                    WidgetType.CAR -> {
                        { showCarSettings(spec.widgetId) }
                    }
                    WidgetType.ELM -> {
                        { showElmSettings(spec.widgetId) }
                    }
                    WidgetType.SPEED -> {
                        { showSpeedSettings(spec.widgetId) }
                    }
                    WidgetType.COMBO -> {
                        { showClockSettings(spec.widgetId) }
                    }
                    WidgetType.PUSH_TEST -> {
                        { showPushSettings() }
                    }
                    else -> null
                }
            )
            frame.setContent(content)
            val lp = DashboardGridLayout.LayoutParams(spec.x, spec.y, spec.w, spec.h)
            dashboard.addView(frame, lp)
            if (spec.type == WidgetType.APP) {
                // Блок переехал (раскладка, смена размера окна) — двигаем и навигатор.
                frame.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                    if ((l != ol || t != ot || r != or || b != ob) && isResumedNow()) {
                        frame.post { refreshNavi() }
                    }
                }
            }
        }
        if (isResumedNow()) refreshNavi()
    }

    private fun isResumedNow(): Boolean =
        lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)

    private fun setEditMode(enabled: Boolean) {
        editMode = enabled
        // Активный режим — подсветка иконки акцентом темы.
        if (enabled) {
            btnEdit.setColorFilter(theme.accent)
        } else {
            btnEdit.clearColorFilter()
        }
        // В режиме редактирования иконки заменяются панелью действий.
        barNormal.visibility = if (enabled) View.GONE else View.VISIBLE
        barEdit.visibility = if (enabled) View.VISIBLE else View.GONE
        for (i in 0 until dashboard.childCount) {
            (dashboard.getChildAt(i) as? WidgetFrame)?.setEditMode(enabled)
        }
        if (!enabled) persistLayout()
        // Блок навигатора мог переехать; в редактировании он — карточка.
        refreshNavi()
    }

    /** Кнопка-иконка без подписи — легко задеть, поэтому переспрашиваем. */
    private fun confirmClearDashboard() {
        DashDialog.Builder(this)
            .setTitle(R.string.clear_confirm_title)
            .setMessage(R.string.clear_confirm_text)
            .setPositiveButton(R.string.clear_confirm_ok) { _, _ -> clearDashboard() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /** «Сбросить»: убрать все виджеты, дашборд остаётся пустым. */
    private fun clearDashboard() {
        store.clearAll()
        buildWidgets(emptyList())
        Toast.makeText(this, R.string.msg_cleared, Toast.LENGTH_SHORT).show()
    }

    /** Один пункт выбора виджета: тип, подпись, иконка; [debug] — секция «Отладка». */
    private class PickerItem(
        val type: WidgetType,
        val labelRes: Int,
        val iconRes: Int,
        val debug: Boolean = false
    )

    private fun pickerItems(): List<PickerItem> = listOf(
        PickerItem(WidgetType.COMBO, R.string.widget_clock, R.drawable.ic_clock),
        PickerItem(WidgetType.WEATHER, R.string.widget_weather, R.drawable.ic_cloud),
        PickerItem(WidgetType.CAR, R.string.widget_car, R.drawable.ic_fuel),
        PickerItem(WidgetType.MUSIC, R.string.widget_music, R.drawable.ic_play),
        PickerItem(WidgetType.WIFI, R.string.widget_wifi, R.drawable.ic_wifi),
        PickerItem(WidgetType.APP, R.string.widget_app, R.drawable.ic_nav),
        PickerItem(WidgetType.TRIP, R.string.widget_trip, R.drawable.ic_journal),
        PickerItem(WidgetType.SPEED, R.string.widget_speed, R.drawable.ic_gauge),
        PickerItem(WidgetType.OUTSIDE, R.string.widget_outside, R.drawable.ic_thermo),
        // Отладочная секция: в prod не попадают (WidgetAvailability).
        PickerItem(WidgetType.CLIMATE, R.string.widget_climate, R.drawable.ic_fan, debug = true),
        PickerItem(WidgetType.REFERENCE, R.string.widget_reference, R.drawable.ic_book, debug = true),
        PickerItem(WidgetType.HEAT, R.string.widget_heat, R.drawable.ic_seat_heat, debug = true),
        PickerItem(WidgetType.ELM, R.string.widget_elm, R.drawable.ic_obd, debug = true),
        PickerItem(WidgetType.PUSH_TEST, R.string.widget_push_test, R.drawable.ic_broadcast, debug = true),
        PickerItem(WidgetType.LEARN, R.string.widget_learn, R.drawable.ic_sync, debug = true),
        PickerItem(WidgetType.BUTTONS, R.string.widget_buttons, R.drawable.ic_grid, debug = true),
    ).filter { WidgetAvailability.isEnabled(it.type) }

    /**
     * Диалог «+ Добавить блок»: сетка карточек с иконкой и подписью.
     * Виджеты, которые уже стоят на главной, отмечены.
     */
    private fun showAddDialog() {
        val added = readSpecs().map { it.type }.toSet()
        lateinit var dialog: Dialog
        val grid = buildPickerGrid(pickerItems(), added) { type ->
            dialog.dismiss()
            addWidget(type)
        }
        dialog = DashDialog.Builder(this)
            .setTitle(R.string.add_title)
            .setView(grid)
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /**
     * Сетка карточек; [onPick] вызывается по тапу. Уже добавленные — отмечены.
     * Отладочные виджеты идут отдельной секцией «Отладка» после «Основных»
     * (в prod их нет — тогда и заголовки секций не рисуем).
     */
    private fun buildPickerGrid(
        items: List<PickerItem>,
        added: Set<WidgetType>,
        onPick: (WidgetType) -> Unit
    ): View {
        val columns = 5
        val gap = dp(10)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), dp(4))
        }
        val rows: (List<PickerItem>) -> Unit = { list ->
            list.chunked(columns).forEach { rowItems ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                rowItems.forEach { item ->
                    row.addView(
                        pickerCard(item, item.type in added, onPick),
                        LinearLayout.LayoutParams(0, dp(130), 1f).apply {
                            marginStart = if (row.childCount > 0) gap else 0
                        }
                    )
                }
                // Пустышки, чтобы карточки не растягивались на всю строку.
                for (i in rowItems.size until columns) {
                    row.addView(
                        Space(this),
                        LinearLayout.LayoutParams(0, 1, 1f).apply {
                            marginStart = if (row.childCount > 0) gap else 0
                        }
                    )
                }
                container.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = gap }
                )
            }
        }
        val main = items.filter { !it.debug }
        val debug = items.filter { it.debug }
        if (debug.isEmpty()) {
            // Prod-набор: секций нет — плоская сетка как раньше.
            rows(items)
        } else {
            container.addView(sectionTitle(R.string.picker_main))
            rows(main)
            container.addView(sectionTitle(R.string.picker_debug), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
            rows(debug)
        }
        // Вертикальная прокрутка: пунктов больше, чем влезает на экран.
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            addView(container)
        }
    }

    /** Небольшой заголовок секции пикера («Основные» / «Отладка»). */
    private fun sectionTitle(res: Int) = TextView(this).apply {
        text = getString(res)
        setTextColor(theme.secondaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    /** Карточка виджета: иконка сверху, подпись, отметка «Уже на главной». */
    private fun pickerCard(
        item: PickerItem,
        added: Boolean,
        onPick: (WidgetType) -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = ThemeCard.card(this@MainActivity, theme)
            isClickable = true
            setOnClickListener { onPick(item.type) }
        }
        card.addView(
            ImageView(this).apply {
                setImageResource(item.iconRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setColorFilter(if (added) theme.accent else theme.primaryText)
            },
            LinearLayout.LayoutParams(dp(30), dp(30))
        )
        card.addView(
            TextView(this).apply {
                text = getString(item.labelRes)
                textSize = 14f
                gravity = Gravity.CENTER
                maxLines = 2
                setTextColor(theme.primaryText)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
        card.addView(
            TextView(this).apply {
                text = if (added) getString(R.string.add_on_dashboard) else ""
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(theme.accent)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )
        return card
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun addWidget(type: WidgetType) {
        val specs = readSpecs().toMutableList()
        val sameCount = specs.count { it.type == type }
        val (w, h) = when (type) {
            WidgetType.COMBO -> 60 to 20
            WidgetType.WEATHER -> 92 to 30
            WidgetType.CAR -> 92 to 24
            WidgetType.MUSIC -> 92 to 24
            WidgetType.APP -> 92 to 40
            WidgetType.TRIP -> 92 to 24
            WidgetType.CLIMATE -> 92 to 40
            WidgetType.REFERENCE -> 56 to 40
            WidgetType.LEARN -> 56 to 48
            WidgetType.HEAT -> 60 to 32
            WidgetType.OUTSIDE -> 40 to 20
            WidgetType.WIFI -> 40 to 18
            WidgetType.ELM -> 56 to 44
            WidgetType.SPEED -> 48 to 26
            WidgetType.PUSH_TEST -> 56 to 40
            WidgetType.BUTTONS -> 56 to 40
            WidgetType.CLOCK -> 40 to 24
        }
        // Каскадом от левого верхнего угла, кламп — в границы сетки.
        val spec = WidgetSpec(
            widgetId = "${type.id}_${sameCount + 1}",
            type = type,
            x = (4 + specs.size * 6) % (DashboardGridLayout.COLS - w),
            y = (6 + specs.size * 4) % (DashboardGridLayout.ROWS - h),
            w = w,
            h = h
        ).clamped()
        specs.add(spec)
        store.save(specs)
        buildWidgets(specs)
        Toast.makeText(this, R.string.msg_added, Toast.LENGTH_SHORT).show()
    }

    private fun removeWidget(widgetId: String) {
        val specs = readSpecs().filterNot { it.widgetId == widgetId }
        store.save(specs)
        buildWidgets(specs)
        Toast.makeText(this, R.string.msg_removed, Toast.LENGTH_SHORT).show()
    }

    /**
     * Настройки виджета погоды: число дней/часов (1–5) и единицы (°C/°F).
     * После сохранения виджеты перестраиваются.
     */
    private fun showWeatherSettings(widgetId: String) {
        var days = settingsStore.getDays(widgetId)
        var hours = settingsStore.getHours(widgetId)
        var unit = settingsStore.getUnit(widgetId)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        fun stepperRow(
            label: String,
            get: () -> Int,
            set: (Int) -> Unit
        ) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val labelView = android.widget.TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(theme.primaryText)
            }
            row.addView(
                labelView,
                android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            val valueView = android.widget.TextView(this).apply {
                text = get().toString()
                textSize = 18f
                setTextColor(theme.accent)
                gravity = android.view.Gravity.CENTER
                minWidth = (48 * resources.displayMetrics.density).toInt()
            }
            val minus = android.widget.Button(this).apply { text = "−" }
            val plus = android.widget.Button(this).apply { text = "+" }
            // Локальная копия с клампом стора.
            var current = get()
            val wrappedSet: (Int) -> Unit = { v ->
                current = v.coerceIn(
                    WidgetSettingsStore.MIN_COUNT,
                    WidgetSettingsStore.MAX_COUNT
                )
                set(current)
                valueView.text = current.toString()
            }
            minus.setOnClickListener { wrappedSet(current - 1) }
            plus.setOnClickListener { wrappedSet(current + 1) }
            row.addView(minus)
            row.addView(valueView)
            row.addView(plus)
            root.addView(row)
        }

        stepperRow(getString(R.string.set_days), { days }, { days = it })
        stepperRow(getString(R.string.set_hours), { hours }, { hours = it })

        val units = android.widget.RadioGroup(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val radioC = android.widget.RadioButton(this).apply {
            text = "°C"
            isChecked = unit != "F"
            setOnCheckedChangeListener { _, checked ->
                if (checked) unit = "C"
            }
        }
        val radioF = android.widget.RadioButton(this).apply {
            text = "°F"
            isChecked = unit == "F"
            setOnCheckedChangeListener { _, checked ->
                if (checked) unit = "F"
            }
        }
        units.addView(radioC)
        units.addView(radioF)
        root.addView(units)

        DashDialog.Builder(this)
            .setTitle(R.string.set_title)
            .setView(root)
            .setPositiveButton(R.string.edit_done) { _, _ ->
                settingsStore.setDays(widgetId, days)
                settingsStore.setHours(widgetId, hours)
                settingsStore.setUnit(widgetId, unit)
                buildWidgets(readSpecs())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /**
     * Настройки виджета «Автомобиль»: какие метрики показывать и в каком
     * порядке (топливо / аккумулятор / двигатель / запас хода).
     * Видимость — [CarWidgetView.OPT_ITEMS] (пусто = все),
     * порядок всех метрик — [CarWidgetView.OPT_ORDER].
     */
    /** Вид «Спидометра»: ретро-стрелочный, классика ВАЗ или цифровой. */
    private fun showSpeedSettings(widgetId: String) {
        val styles = listOf(SPEED_RETRO, SPEED_VAZ, SPEED_DIGITAL)
        var selected = styles.indexOf(settingsStore.getOption(widgetId, OPT_SPEED_STYLE, SPEED_RETRO))
            .coerceAtLeast(0)
        DashDialog.Builder(this)
            .setTitle(R.string.speed_style_title)
            .setSingleChoiceItems(
                arrayOf(
                    getString(R.string.speed_style_retro),
                    getString(R.string.speed_style_vaz),
                    getString(R.string.speed_style_digital)
                ),
                selected
            ) { _, which ->
                selected = which
            }
            .setPositiveButton(R.string.edit_done) { _, _ ->
                settingsStore.setOption(widgetId, OPT_SPEED_STYLE, styles[selected])
                buildWidgets(readSpecs())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /**
     * Настройки «Часов»: масштаб текста (×0.5 … ×2.0), жирный шрифт времени
     * и перекидной вид как у HTC (см. [ComboWidgetView.OPT_SCALE]).
     */
    private fun showClockSettings(widgetId: String) {
        val scales = ComboWidgetView.SCALES
        var selected = scales.indexOf(
            settingsStore.getOption(widgetId, ComboWidgetView.OPT_SCALE, ComboWidgetView.DEFAULT_SCALE)
        ).coerceAtLeast(0)
        var bold = ComboWidgetView.boldValue(settingsStore, widgetId)
        var flip = ComboWidgetView.styleValue(settingsStore, widgetId) == ComboWidgetView.STYLE_FLIP
        var invert = ComboWidgetView.invertValue(settingsStore, widgetId)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val radios = scales.mapIndexed { i, value ->
            android.widget.RadioButton(this).apply {
                text = "×$value"
                textSize = 18f
                setTextColor(theme.primaryText)
                isChecked = i == selected
                setOnCheckedChangeListener { _, isCheckedNow ->
                    if (isCheckedNow) selected = i
                }
            }
        }
        root.addView(
            android.widget.RadioGroup(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                radios.forEach { addView(it) }
            }
        )
        root.addView(
            android.widget.CheckBox(this).apply {
                text = getString(R.string.clock_bold)
                textSize = 18f
                setTextColor(theme.primaryText)
                isChecked = bold
                setOnCheckedChangeListener { _, isCheckedNow -> bold = isCheckedNow }
            }
        )
        root.addView(
            android.widget.CheckBox(this).apply {
                text = getString(R.string.clock_flip)
                textSize = 18f
                setTextColor(theme.primaryText)
                isChecked = flip
                setOnCheckedChangeListener { _, isCheckedNow -> flip = isCheckedNow }
            }
        )
        root.addView(
            android.widget.CheckBox(this).apply {
                text = getString(R.string.clock_flip_invert)
                textSize = 18f
                setTextColor(theme.primaryText)
                isChecked = invert
                setOnCheckedChangeListener { _, isCheckedNow -> invert = isCheckedNow }
            }
        )

        DashDialog.Builder(this)
            .setTitle(R.string.set_clock_title)
            .setView(root)
            .setPositiveButton(R.string.edit_done) { _, _ ->
                settingsStore.setOption(widgetId, ComboWidgetView.OPT_SCALE, scales[selected])
                settingsStore.setOption(
                    widgetId, ComboWidgetView.OPT_BOLD, if (bold) "1" else "0"
                )
                settingsStore.setOption(
                    widgetId, ComboWidgetView.OPT_STYLE,
                    if (flip) ComboWidgetView.STYLE_FLIP else ComboWidgetView.STYLE_DIGITAL
                )
                settingsStore.setOption(
                    widgetId, ComboWidgetView.OPT_INVERT, if (invert) "1" else "0"
                )
                buildWidgets(readSpecs())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    private fun showCarSettings(widgetId: String) {
        val labelIds = mapOf(
            "fuel" to R.string.car_item_fuel,
            "batt" to R.string.car_item_batt,
            "eng" to R.string.car_item_eng,
            "range" to R.string.car_item_range
        )
        val orderRaw = settingsStore.getOption(widgetId, CarWidgetView.OPT_ORDER, "")
        val savedOrder = orderRaw.split(',')
            .mapNotNull { it.trim().takeIf { s -> s in CarWidgetView.ITEM_KEYS } }
            .distinct()
        val order = (if (savedOrder.isEmpty()) CarWidgetView.ITEM_KEYS
        else savedOrder + CarWidgetView.ITEM_KEYS.filter { it !in savedOrder })
            .toMutableList()
        val itemsRaw = settingsStore.getOption(widgetId, CarWidgetView.OPT_ITEMS, "")
        // Пусто = по умолчанию показываем всё — в диалоге все отмечены.
        val checked = (if (itemsRaw.isEmpty()) order
        else order.filter { key ->
            itemsRaw.split(',').any { it.trim() == key }
        }).toMutableSet()

        val pad = (16 * resources.displayMetrics.density).toInt()
        val list = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        fun rebuild() {
            list.removeAllViews()
            order.forEachIndexed { index, key ->
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val box = android.widget.CheckBox(this).apply {
                    isChecked = key in checked
                    setOnCheckedChangeListener { _, on ->
                        if (on) checked.add(key) else checked.remove(key)
                    }
                }
                val label = android.widget.TextView(this).apply {
                    text = getString(labelIds.getValue(key))
                    textSize = 16f
                    setTextColor(theme.primaryText)
                }
                val up = android.widget.Button(this).apply {
                    text = "↑"
                    isEnabled = index > 0
                    setOnClickListener {
                        order[index] = order[index - 1].also { order[index - 1] = order[index] }
                        rebuild()
                    }
                }
                val down = android.widget.Button(this).apply {
                    text = "↓"
                    isEnabled = index < order.size - 1
                    setOnClickListener {
                        order[index] = order[index + 1].also { order[index + 1] = order[index] }
                        rebuild()
                    }
                }
                row.addView(box)
                row.addView(
                    label,
                    android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                row.addView(up)
                row.addView(down)
                list.addView(row)
            }
        }
        rebuild()

        DashDialog.Builder(this)
            .setTitle(R.string.set_car_title)
            .setView(list)
            .setPositiveButton(R.string.edit_done) { _, _ ->
                settingsStore.setOption(
                    widgetId, CarWidgetView.OPT_ORDER, order.joinToString(",")
                )
                settingsStore.setOption(
                    widgetId, CarWidgetView.OPT_ITEMS,
                    order.filter { it in checked }.joinToString(",")
                )
                buildWidgets(readSpecs())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /**
     * Настройка виджета «OBD (ELM)»: состав показателей (мультивыбор)
     * и строка-роутер к выбору адаптера. Состав хранится per-widget
     * ([WidgetSettingsStore]), адаптер — глобально ([ElmHub]): он в
     * машине один, а виджетов OBD может быть несколько.
     */
    private fun showElmSettings(widgetId: String) {
        val items = arrayOf(
            getString(R.string.elm_pick_adapter)
        ) + ElmWidgetView.ITEM_KEYS.map { getString(checkNotNull(ElmWidgetView.labelRes(it))) }
        val selected = ElmWidgetView.selectedIds(settingsStore, widgetId).toMutableSet()
        val checked = BooleanArray(items.size) { i -> i > 0 && ElmWidgetView.ITEM_KEYS[i - 1] in selected }
        DashDialog.Builder(this)
            .setTitle(R.string.set_elm_title)
            .setMultiChoiceItems(items, checked, routerIndex = 0) { dlg, which, on ->
                if (which == 0) {
                    dlg.dismiss()
                    showElmAdapterDialog()
                } else {
                    val key = ElmWidgetView.ITEM_KEYS[which - 1]
                    if (on) selected.add(key) else selected.remove(key)
                }
            }
            .setPositiveButton(R.string.edit_done) { _, _ ->
                // Ничего не отмечено — сохраняем маркер «-»: пустая опция
                // означала бы «ещё не настраивали» (дефолт).
                val items = ElmWidgetView.ITEM_KEYS.filter { it in selected }
                settingsStore.setOption(
                    widgetId,
                    ElmWidgetView.OPT_ITEMS,
                    if (items.isEmpty()) ElmWidgetView.NONE_ITEMS else items.joinToString(",")
                )
                buildWidgets(readSpecs())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /**
     * Выбор спаренного Bluetooth-адаптера ELM327. Выбор сохраняется
     * глобально ([ElmHub]), а не per-widget — адаптер в машине один.
     */
    private fun showElmAdapterDialog() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val paired = manager.adapter?.takeIf { it.isEnabled }
            ?.bondedDevices?.sortedBy { it.name ?: "" }?.toList() ?: emptyList()
        if (paired.isEmpty()) {
            Toast.makeText(this, R.string.elm_paired_none, Toast.LENGTH_SHORT).show()
            return
        }
        val current = ElmHub.address()?.let { addr -> getString(R.string.elm_current, addr, ElmHub.name() ?: "?") } ?: ""
        val items = paired.map { "${it.name ?: getString(R.string.elm_no_name)} — ${it.address}" }
            .toTypedArray()
        DashDialog.Builder(this)
            .setTitle(if (current.isEmpty()) getString(R.string.set_elm_adapter_title) else current)
            .setItems(items) { _, which ->
                val device = paired[which]
                ElmHub.setDevice(this, device.address, device.name)
                Toast.makeText(
                    this,
                    getString(R.string.elm_picked, device.name ?: getString(R.string.elm_no_name)),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /** Настройки теста push: какой набор подписок регистрировать. */
    private fun showPushSettings() {
        val presets = DesayPushProbe.Preset.values()
        DashDialog.Builder(this)
            .setTitle(R.string.set_push_title)
            .setItems(presets.map { it.label }.toTypedArray()) { _, which ->
                DesayPushProbe.preset = presets[which]
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    private fun isAppInstalled(pkg: String): Boolean {
        return try {
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * «На приборку»: открывает навигацию на дисплее приборки.
     * Пакет — первое установленное навигационное приложение.
     */
    private fun openNavOnCluster() {
        val clusterId = NavApps.getClusterDisplayId(this)
        if (clusterId == null) {
            Toast.makeText(this, R.string.msg_no_cluster, Toast.LENGTH_SHORT).show()
            return
        }
        val pkg = firstNavPackage()
        if (pkg == null) {
            Toast.makeText(this, R.string.msg_no_nav, Toast.LENGTH_SHORT).show()
            return
        }
        com.dash.headunit.location.LocationHelper(this).requestFix { fix ->
            val lat = fix?.latitude ?: com.dash.headunit.location.LocationHelper.FALLBACK_LAT
            val lon = fix?.longitude ?: com.dash.headunit.location.LocationHelper.FALLBACK_LON
            try {
                val uri = android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon")
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW, uri
                ).apply {
                    setPackage(pkg)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val options = android.app.ActivityOptions.makeBasic()
                options.launchDisplayId = clusterId
                startActivity(intent, options.toBundle())
            } catch (e: Exception) {
                Toast.makeText(this, R.string.msg_no_nav, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Пакет навигации: первое установленное из известных. */
    private fun firstNavPackage(): String? {
        return NavApps.NAV_APPS.firstOrNull { isAppInstalled(it.second) }?.second
    }

    /**
     * Настройки виджета-приложения: выбор из всех приложений лаунчера
     * (виден только первое после установки — дальше список полный).
     */
    private fun showAppSettings(widgetId: String) {
        val current = settingsStore.getOption(
            widgetId, AppWidgetView.OPT_PKG, ""
        )
        val apps = launchableApps()
        // Пары (label, pkg); себя исключаем — встраивать самих себя нельзя.
        val options = mutableListOf(
            getString(R.string.app_none_selected) to ""
        ) + apps.filter { it.first != packageName }
            .map { it.second to it.first }
        var selected = options.indexOfFirst { it.second == current }.takeIf { it >= 0 } ?: 0
        DashDialog.Builder(this)
            .setTitle(R.string.set_app_title)
            .setSingleChoiceItems(
                options.map { it.first }.toTypedArray(), selected
            ) { _, which ->
                selected = which
            }
            .setPositiveButton(R.string.edit_done) { _, _ ->
                settingsStore.setOption(
                    widgetId, AppWidgetView.OPT_PKG, options[selected].second
                )
                buildWidgets(readSpecs())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /** Все запускаемые приложения (label + package), по алфавиту. */
    private fun launchableApps(): List<Pair<String, String>> {
        return try {
            val main = android.content.Intent(
                android.content.Intent.ACTION_MAIN
            ).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(main, 0)
                .mapNotNull { ri ->
                    val label = ri.loadLabel(packageManager)?.toString()
                    val pkg = ri.activityInfo?.packageName
                    if (label.isNullOrBlank() || pkg.isNullOrBlank()) null
                    else pkg to label
                }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun resetLayout() {
        store.reset()
        buildWidgets(store.defaultLayout())
    }

    private fun persistLayout() {
        store.save(readSpecs())
    }

    private fun readSpecs(): List<WidgetSpec> {
        val specs = mutableListOf<WidgetSpec>()
        for (i in 0 until dashboard.childCount) {
            val frame = dashboard.getChildAt(i) as? WidgetFrame ?: continue
            val lp = frame.layoutParams as? DashboardGridLayout.LayoutParams ?: continue
            specs.add(
                WidgetSpec(
                    widgetId = frame.widgetId,
                    type = frame.widgetType,
                    x = lp.cellX, y = lp.cellY, w = lp.cellW, h = lp.cellH
                )
            )
        }
        return specs
    }

    // ---------- геолокация ----------

    private fun ensureLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED &&
            coarse != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                REQ_LOCATION
            )
        }
    }

    companion object {
        /** onStop в этот срок после запуска навигатора под себя — не уход с экрана. */
        private const val UNDERLAY_GRACE_MS = 5000L

        private const val REQ_LOCATION = 1001

        /** Опция «Спидометра»: вид (см. [showSpeedSettings]). */
        private const val OPT_SPEED_STYLE = "style"
        private const val SPEED_RETRO = "retro"
        private const val SPEED_VAZ = "vaz"
        private const val SPEED_DIGITAL = "digital"
    }
}
