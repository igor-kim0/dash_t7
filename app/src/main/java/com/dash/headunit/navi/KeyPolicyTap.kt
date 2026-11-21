package com.dash.headunit.navi

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.view.KeyEvent

/**
 * Свой колбэк в Desay key_policy (android.view.IKeyPolicy, системная служба
 * в system_server). Протокол — из boot-framework прошивки:
 * registerKeyCallBack = txn 1 (callback, name, int, String),
 * unRegisterKeyCallBack = txn 2 (name, String);
 * IKeyPolicyCallBack.callbackEvent = txn 1 (KeyEvent, String) → boolean.
 *
 * Служба рассылает «клавиши SV» (isDSVKey: 82, 85–88, 90, 126/127, 129,
 * 286–301, 304, 314, 320–323 — в т.ч. кнопки руля, голос = 292 VR) всем
 * колбэкам с нештатными именами. Только слушаем: ответ false, клавиша идёт
 * дальше как обычно (забрать её так нельзя). Проба HU 2026-11-21: фильтр
 * клавиш службы спец. возможностей кнопки руля не получает вовсе, а этот
 * колбэк — получает все (с повторами удержания).
 *
 * [listener] зовётся в binder-потоке.
 */
class KeyPolicyTap(private val name: String, private val listener: (KeyEvent) -> Unit) {

    @Volatile var service: IBinder? = null
        private set

    val registered: Boolean get() = service != null

    private val callback = object : Binder() {
        init {
            attachInterface(null, CB_DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != 1) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CB_DESCRIPTOR)
            val e = if (data.readInt() != 0) KeyEvent.CREATOR.createFromParcel(data) else null
            try {
                if (e != null && service != null) listener(e)
            } catch (t: Throwable) {
                Log.w(TAG, "callbackEvent $name", t)
            }
            reply?.writeNoException()
            reply?.writeInt(0)
            return true
        }
    }

    /**
     * Зарегистрироваться. Сначала снимаем своё имя: после перезапуска
     * процесса в службе может остаться старая запись с мёртвым колбэком,
     * а занятое имя служба не перезаписывает. Возвращает текст для журнала.
     */
    @Synchronized
    fun register(context: Context): String {
        if (service != null) return "колбэк «$name» уже зарегистрирован"
        val b = try {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "key_policy") as IBinder?
        } catch (e: Throwable) {
            return "нет доступа к ServiceManager: ${e.javaClass.simpleName}"
        } ?: return "службы key_policy нет"
        val pkg = context.packageName
        runCatching { unregisterOn(b, pkg) }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeStrongBinder(callback)
            data.writeString(name)
            data.writeInt(0)
            data.writeString(pkg)
            b.transact(1, data, reply, 0)
            reply.readException()
            service = b
            "колбэк «$name» зарегистрирован"
        } catch (e: Throwable) {
            "registerKeyCallBack: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Synchronized
    fun unregister(context: Context) {
        val b = service ?: return
        service = null
        try {
            unregisterOn(b, context.packageName)
        } catch (e: Throwable) {
            Log.w(TAG, "unRegisterKeyCallBack $name", e)
        }
    }

    private fun unregisterOn(b: IBinder, pkg: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(name)
            data.writeString(pkg)
            b.transact(2, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private companion object {
        const val DESCRIPTOR = "android.view.IKeyPolicy"
        const val CB_DESCRIPTOR = "android.view.IKeyPolicyCallBack"
        const val TAG = "KeyPolicyTap"
    }
}
