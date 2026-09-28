package kz.p2pmessenger.internet

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCode {
    fun matrix(content: String, size: Int = 768): BitMatrix {
        require(content.length <= QrSignal.SINGLE_LIMIT)
        return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
    }
}
