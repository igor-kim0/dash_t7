package com.dash.headunit.ui

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
import android.util.Log
import android.widget.LinearLayout
import android.widget.Toast
import com.dash.headunit.R
import com.dash.headunit.bt.PhoneBattery
import com.dash.headunit.elm.ElmHub
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeStore
import com.dash.headunit.ui.WifiConnectionDialog.note
import com.dash.headunit.ui.WifiConnectionDialog.row
import com.dash.headunit.ui.WifiConnectionDialog.section

/**
 * Диалог Bluetooth (кнопка Bluetooth в колонке дашборда): вкл/выкл,
 * сопряжённые устройства с состоянием подключения и зарядом телефона.
 *
 * ГУ — приёмник: телефон подключён профилями HFP-клиент (звонки) и
 * A2DP-приёмник (музыка). Подключить/отключить — скрытые
 * `connect`/`disconnect` этих профилей рефлексией; на Android 9 их может
 * не пустить (hidden API / системное право) — тогда тост «не удалось».
 */
object BluetoothDialog {
    private const val TAG = "BluetoothDialog"

    /** BluetoothProfile.HEADSET_CLIENT / A2DP_SINK (@hide). */
    private const val HEADSET_CLIENT = 16
    private const val A2DP_SINK = 11

    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun show(context: Context) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val theme = ThemeStore(ThemeStore.PREFS, context).current()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(p, 0, p, p)
        }
        val proxies = HashMap<Int, BluetoothProfile>()
        val render = { renderRows(context, theme, list, adapter, proxies) }

        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                proxies[profile] = proxy
                render()
            }

            override fun onServiceDisconnected(profile: Int) {
                proxies.remove(profile)
                render()
            }
        }
        if (adapter != null) {
            runCatching { adapter.getProfileProxy(context, listener, HEADSET_CLIENT) }
            runCatching { adapter.getProfileProxy(context, listener, A2DP_SINK) }
        }

        // Состояние профилей меняется асинхронно — перерисовка по событиям стека.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                // Стеку нужно мгновение, чтобы состояние стало видно через прокси.
                main.postDelayed({ render() }, 300)
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED")
        }
        context.registerReceiver(receiver, filter)
        val batteryListener: () -> Unit = { render() }
        PhoneBattery.addListener(batteryListener)
        PhoneBattery.refresh()
        render()

        val dialog = DashDialog.Builder(context)
            .setTitle(R.string.bt_title)
            .setView(list)
            .setPositiveButton(R.string.dialog_ok, null)
            .showFullScreen()
        dialog.setOnDismissListener {
            PhoneBattery.removeListener(batteryListener)
            runCatching { context.unregisterReceiver(receiver) }
            proxies.forEach { (profile, proxy) ->
                runCatching { adapter?.closeProfileProxy(profile, proxy) }
            }
            proxies.clear()
        }
    }

    @SuppressLint("MissingPermission")
    private fun renderRows(
        context: Context,
        theme: DashTheme,
        host: LinearLayout,
        adapter: BluetoothAdapter?,
        proxies: Map<Int, BluetoothProfile>
    ) {
        host.removeAllViews()
        if (adapter == null) {
            host.addView(note(context, theme, context.getString(R.string.bt_note_none)))
            return
        }
        val state = adapter.state
        val enabled = state == BluetoothAdapter.STATE_ON
        val turning = state == BluetoothAdapter.STATE_TURNING_ON ||
            state == BluetoothAdapter.STATE_TURNING_OFF
        host.addView(
            row(
                context, theme,
                title = context.getString(R.string.bt_toggle_row),
                note = context.getString(
                    when {
                        turning -> R.string.bt_turning
                        enabled -> R.string.wifi_on
                        else -> R.string.wifi_off
                    }
                ),
                noteAccent = enabled,
                onClick = {
                    if (!turning) runCatching { if (enabled) adapter.disable() else adapter.enable() }
                }
            )
        )
        if (enabled) {
            runCatching { adapter.name }.getOrNull()?.let {
                host.addView(note(context, theme, context.getString(R.string.bt_name_note, it)))
            }
        } else {
            host.addView(note(context, theme, context.getString(R.string.bt_note_off)))
            return
        }

        val bonded = runCatching { adapter.bondedDevices.toList() }.getOrDefault(emptyList())
        host.addView(section(context, theme, context.getString(R.string.bt_paired_title, bonded.size)))
        if (bonded.isEmpty()) {
            host.addView(note(context, theme, context.getString(R.string.bt_note_no_paired)))
            return
        }
        val elm = ElmHub.address()
        // Подключённые — первыми.
        bonded.sortedWith(
            compareByDescending<BluetoothDevice> { connectedProfiles(proxies, it).isNotEmpty() }
                .thenBy { it.name ?: it.address }
        ).forEach { device ->
            val connected = connectedProfiles(proxies, device)
            val phone = HEADSET_CLIENT in connected
            val name = device.name ?: device.address
            val noteText = when {
                connected.isNotEmpty() -> buildString {
                    append(context.getString(R.string.wifi_connected_note))
                    if (phone && PhoneBattery.percent >= 0) {
                        append(" · ")
                        append(context.getString(R.string.phone_battery_percent, PhoneBattery.percent))
                    }
                }
                device.address == elm -> context.getString(R.string.bt_elm_note)
                else -> context.getString(R.string.bt_paired_note)
            }
            host.addView(
                row(
                    context, theme,
                    title = name,
                    note = noteText,
                    noteAccent = connected.isNotEmpty(),
                    onClick = {
                        if (connected.isEmpty() && device.address != elm) {
                            connect(context, proxies, device, name)
                        }
                    },
                    onLongClick = if (connected.isNotEmpty()) {
                        { confirmDisconnect(context, proxies, device, name) }
                    } else null
                )
            )
        }
        host.addView(note(context, theme, context.getString(R.string.bt_note_hint)))
    }

    private fun connectedProfiles(proxies: Map<Int, BluetoothProfile>, device: BluetoothDevice): Set<Int> =
        proxies.filter { (_, proxy) ->
            runCatching { proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED }
                .getOrDefault(false)
        }.keys

    /** Скрытый connect/disconnect профиля; true — стек принял команду. */
    private fun call(proxy: BluetoothProfile, method: String, device: BluetoothDevice): Boolean =
        runCatching {
            proxy.javaClass.getMethod(method, BluetoothDevice::class.java).invoke(proxy, device) as Boolean
        }.onFailure { Log.w(TAG, "$method: $it") }.getOrDefault(false)

    private fun connect(
        context: Context,
        proxies: Map<Int, BluetoothProfile>,
        device: BluetoothDevice,
        name: String
    ) {
        // Сначала звонки (HFP), затем музыка (A2DP) — как штатное подключение.
        val ok = listOf(HEADSET_CLIENT, A2DP_SINK)
            .mapNotNull { proxies[it] }
            .map { call(it, "connect", device) }
            .any { it }
        Toast.makeText(
            context,
            if (ok) context.getString(R.string.bt_connecting, name)
            else context.getString(R.string.bt_connect_fail),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun confirmDisconnect(
        context: Context,
        proxies: Map<Int, BluetoothProfile>,
        device: BluetoothDevice,
        name: String
    ) {
        DashDialog.Builder(context)
            .setTitle(R.string.bt_disconnect_title)
            .setMessage(context.getString(R.string.bt_disconnect_text, name))
            .setPositiveButton(R.string.bt_disconnect) { _, _ ->
                val ok = proxies.values.map { call(it, "disconnect", device) }.any { it }
                if (!ok) {
                    Toast.makeText(context, R.string.bt_disconnect_fail, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }
}
