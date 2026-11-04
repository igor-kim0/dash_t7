package com.dash.headunit.ui

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR-код строки (ссылки) — через zxing core. */
object QrCode {

    /** Матрица модулей: поле в 1 модуль, коррекция M. Чистая — тестируется. */
    fun matrix(text: String): BitMatrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
    )

    /**
     * Картинка не больше [maxPx]: каждый модуль — целое число пикселей
     * (без сглаживания, иначе камера читает хуже).
     */
    fun bitmap(text: String, maxPx: Int, dark: Int, light: Int): Bitmap {
        val m = matrix(text)
        val scale = (maxPx / m.width).coerceAtLeast(1)
        val size = m.width * scale
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val my = y / scale
            for (x in 0 until size) {
                pixels[y * size + x] = if (m.get(x / scale, my)) dark else light
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
