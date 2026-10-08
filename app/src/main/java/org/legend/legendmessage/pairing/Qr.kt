package org.legend.legendmessage.pairing

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Renders text or raw bytes into a QR [Bitmap]. */
object Qr {
    fun encode(text: String, sizePx: Int): Bitmap =
        render(text, "UTF-8", sizePx)

    /**
     * Encode raw bytes in QR byte mode (via a Latin-1 1:1 mapping), which is
     * denser-efficient than base64 text for the large contact card.
     */
    fun encodeBytes(data: ByteArray, sizePx: Int): Bitmap =
        render(String(data, Charsets.ISO_8859_1), "ISO-8859-1", sizePx)

    private fun render(content: String, charset: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            // Low ECC: the card (esp. the Kyber key) is large and the QR is shown
            // on-screen for a direct scan, so fitting the payload matters more
            // than print robustness.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to charset,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
