package botix.dev.scannercatdogs

import android.content.res.AssetManager
import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel

class AndroidCatDogClassifier(
    assets: AssetManager,
    modelAsset: String = ModelContract.DEFAULT_MODEL_ASSET,
    accelerator: Accelerator = Accelerator.CPU,
) : AutoCloseable {

    private val model = CompiledModel.create(assets, modelAsset, CompiledModel.Options(accelerator))
    private val inputBuffers = model.createInputBuffers()
    private val outputBuffers = model.createOutputBuffers()
    private val input = FloatArray(ModelContract.INPUT_FLOAT_COUNT)
    private val pixels = IntArray(ModelContract.INPUT_SIZE * ModelContract.INPUT_SIZE)

    fun classify(bitmap: Bitmap): Classification {
        writeInput(bitmap)
        inputBuffers[0].writeFloat(input)
        model.run(inputBuffers, outputBuffers)
        return Classification(outputBuffers[0].readFloat()[0])
    }

    private fun writeInput(bitmap: Bitmap) {
        val size = ModelContract.INPUT_SIZE
        val scaled = if (bitmap.width == size && bitmap.height == size) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, size, size, true)
        }
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== bitmap) {
            scaled.recycle()
        }
        var i = 0
        for (pixel in pixels) {
            input[i++] = ((pixel shr 16) and 0xFF).toFloat()
            input[i++] = ((pixel shr 8) and 0xFF).toFloat()
            input[i++] = (pixel and 0xFF).toFloat()
        }
    }

    fun lastInputAsBitmap(): Bitmap {
        val size = ModelContract.INPUT_SIZE
        val argb = IntArray(size * size)
        var i = 0
        for (index in argb.indices) {
            val r = input[i++].toInt().coerceIn(0, 255)
            val g = input[i++].toInt().coerceIn(0, 255)
            val b = input[i++].toInt().coerceIn(0, 255)
            argb[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(argb, size, size, Bitmap.Config.ARGB_8888)
    }

    override fun close() {
        model.close()
    }
}
