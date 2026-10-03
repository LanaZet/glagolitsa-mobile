// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.safety

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
actual fun SafetyNumberQrCode(
    payload: ByteArray,
    modifier: Modifier,
    foreground: Color,
    background: Color,
) {
    val matrix = runCatching {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(
            payload.joinToString(separator = "") { byte ->
                ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1)
            },
            BarcodeFormat.QR_CODE,
            256,
            256,
        )
        Array(bitMatrix.height) { y ->
            BooleanArray(bitMatrix.width) { x -> bitMatrix.get(x, y) }
        }
    }.getOrNull() ?: return

    Canvas(modifier = modifier) {
        val cellWidth = size.width / matrix[0].size
        val cellHeight = size.height / matrix.size
        drawRect(background)
        matrix.forEachIndexed { y, row ->
            row.forEachIndexed { x, filled ->
                if (filled) {
                    drawRect(
                        color = foreground,
                        topLeft = Offset(x * cellWidth, y * cellHeight),
                        size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight),
                    )
                }
            }
        }
    }
}