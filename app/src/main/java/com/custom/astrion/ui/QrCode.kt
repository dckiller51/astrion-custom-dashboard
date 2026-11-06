package com.custom.astrion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code of [text] (1.4.3), black on white so every phone camera reads it, e.g. the remote's
 * web page address in Settings › Web configuration. Encoded with ZXing (Apache-2.0), drawn here
 * module by module, so it stays sharp at any size.
 */
@Composable
internal fun QrCode(text: String, modifier: Modifier = Modifier, size: Dp = 168.dp) {
    val matrix = remember(text) { runCatching { qrMatrix(text) }.getOrNull() } ?: return
    Canvas(modifier.size(size).background(Color.White)) {
        val cell = this.size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    // A hair wider than the cell, so no seam shows between modules.
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

/** The smallest matrix for [text], with the 2-module quiet zone phone cameras need. */
internal fun qrMatrix(text: String): BitMatrix = QRCodeWriter().encode(
    text,
    BarcodeFormat.QR_CODE,
    0,
    0,
    mapOf(EncodeHintType.MARGIN to QR_MARGIN, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
)

private const val QR_MARGIN = 2
