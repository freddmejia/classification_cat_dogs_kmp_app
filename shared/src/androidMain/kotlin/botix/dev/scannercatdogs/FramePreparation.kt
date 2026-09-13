package botix.dev.scannercatdogs

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect

object FramePreparation {

    fun uprightSquare(source: Bitmap, rotationDegrees: Int): Bitmap {
        val size = minOf(source.width, source.height)
        val left = (source.width - size) / 2
        val top = (source.height - size) / 2
        val matrix = Matrix().apply {
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(source, left, top, size, size, matrix, true)
    }

    fun viewfinderSquare(source: Bitmap, cropRect: Rect, rotationDegrees: Int): Bitmap {
        val safe = Rect(
            cropRect.left.coerceIn(0, source.width),
            cropRect.top.coerceIn(0, source.height),
            cropRect.right.coerceIn(0, source.width),
            cropRect.bottom.coerceIn(0, source.height),
        )
        val visible = if (safe.width() <= 0 || safe.height() <= 0) {
            source
        } else {
            Bitmap.createBitmap(source, safe.left, safe.top, safe.width(), safe.height())
        }
        val matrix = Matrix().apply {
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        val size = minOf(visible.width, visible.height)
        val left = (visible.width - size) / 2
        val top = (visible.height - size) / 2
        return Bitmap.createBitmap(visible, left, top, size, size, matrix, true)
    }

    fun upright(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return source
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
