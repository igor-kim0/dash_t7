package com.dash.headunit.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

/** QR ссылки на канал читается обратно той же строкой. */
class QrCodeTest {

    @Test
    fun telegramUrl_roundTrip() {
        val url = "https://t.me/tenet_t7_dash"
        val m = QrCode.matrix(url)
        val scale = 8
        val size = m.width * scale
        val px = IntArray(size * size) { i ->
            if (m.get((i % size) / scale, (i / size) / scale)) 0xFF000000.toInt() else -1
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, px)))
        assertEquals(url, QRCodeReader().decode(bitmap).text)
    }
}
