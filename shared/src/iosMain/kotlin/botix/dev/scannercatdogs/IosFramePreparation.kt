@file:OptIn(ExperimentalForeignApi::class)

package botix.dev.scannercatdogs

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get

enum class ChannelOrder(val red: Int, val green: Int, val blue: Int) {
    RGBA(0, 1, 2),
    BGRA(2, 1, 0),
}

class PixelSource(
    val data: CPointer<UByteVar>,
    val width: Int,
    val height: Int,
    val bytesPerRow: Int,
    val order: ChannelOrder,
)

data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int)

data class NormalizedRect(val x: Double, val y: Double, val width: Double, val height: Double)

object IosFramePreparation {

    fun viewfinderSquare(width: Int, height: Int, region: NormalizedRect?): PixelRect {
        var left = 0.0
        var top = 0.0
        var right = width.toDouble()
        var bottom = height.toDouble()
        if (region != null) {
            val l = (region.x * width).coerceIn(0.0, width.toDouble())
            val t = (region.y * height).coerceIn(0.0, height.toDouble())
            val r = ((region.x + region.width) * width).coerceIn(0.0, width.toDouble())
            val b = ((region.y + region.height) * height).coerceIn(0.0, height.toDouble())
            if (r - l >= 1.0 && b - t >= 1.0) {
                left = l
                top = t
                right = r
                bottom = b
            }
        }
        val side = floor(min(right - left, bottom - top)).toInt()
        return PixelRect(
            left = floor(left + (right - left - side) / 2).toInt(),
            top = floor(top + (bottom - top - side) / 2).toInt(),
            width = side,
            height = side,
        )
    }

    fun writeUprightRgb(
        source: PixelSource,
        crop: PixelRect,
        rotationDegrees: Int,
        size: Int,
        out: FloatArray,
    ) {
        val quarterTurns = ((rotationDegrees / 90.0).roundToInt() % 4 + 4) % 4
        val cropWidth = crop.width
        val cropHeight = crop.height
        val uprightWidth = if (quarterTurns % 2 == 0) cropWidth else cropHeight
        val uprightHeight = if (quarterTurns % 2 == 0) cropHeight else cropWidth
        val scaleX = uprightWidth.toDouble() / size
        val scaleY = uprightHeight.toDouble() / size
        val data = source.data
        val row = source.bytesPerRow
        val redOffset = source.order.red
        val greenOffset = source.order.green
        val blueOffset = source.order.blue
        var i = 0
        for (oy in 0 until size) {
            val v = (oy + 0.5) * scaleY - 0.5
            for (ox in 0 until size) {
                val u = (ox + 0.5) * scaleX - 0.5
                val cx: Double
                val cy: Double
                when (quarterTurns) {
                    1 -> {
                        cx = v
                        cy = cropHeight - 1 - u
                    }
                    2 -> {
                        cx = cropWidth - 1 - u
                        cy = cropHeight - 1 - v
                    }
                    3 -> {
                        cx = cropWidth - 1 - v
                        cy = u
                    }
                    else -> {
                        cx = u
                        cy = v
                    }
                }
                val x = cx.coerceIn(0.0, (cropWidth - 1).toDouble())
                val y = cy.coerceIn(0.0, (cropHeight - 1).toDouble())
                val x0 = x.toInt()
                val y0 = y.toInt()
                val x1 = min(x0 + 1, cropWidth - 1)
                val y1 = min(y0 + 1, cropHeight - 1)
                val fx = x - x0
                val fy = y - y0
                val p00 = ((crop.top + y0) * row) + (crop.left + x0) * 4
                val p10 = ((crop.top + y0) * row) + (crop.left + x1) * 4
                val p01 = ((crop.top + y1) * row) + (crop.left + x0) * 4
                val p11 = ((crop.top + y1) * row) + (crop.left + x1) * 4
                val w00 = (1 - fx) * (1 - fy)
                val w10 = fx * (1 - fy)
                val w01 = (1 - fx) * fy
                val w11 = fx * fy
                out[i++] = (data[p00 + redOffset].toDouble() * w00 + data[p10 + redOffset].toDouble() * w10 +
                    data[p01 + redOffset].toDouble() * w01 + data[p11 + redOffset].toDouble() * w11).toFloat()
                out[i++] = (data[p00 + greenOffset].toDouble() * w00 + data[p10 + greenOffset].toDouble() * w10 +
                    data[p01 + greenOffset].toDouble() * w01 + data[p11 + greenOffset].toDouble() * w11).toFloat()
                out[i++] = (data[p00 + blueOffset].toDouble() * w00 + data[p10 + blueOffset].toDouble() * w10 +
                    data[p01 + blueOffset].toDouble() * w01 + data[p11 + blueOffset].toDouble() * w11).toFloat()
            }
        }
    }
}
