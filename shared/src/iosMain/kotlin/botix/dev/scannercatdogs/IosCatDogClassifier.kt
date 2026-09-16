@file:OptIn(ExperimentalForeignApi::class)

package botix.dev.scannercatdogs

import cnames.structs.TfLiteInterpreter
import cnames.structs.TfLiteInterpreterOptions
import cnames.structs.TfLiteModel
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.Foundation.NSBundle
import tensorflow.lite.c.TfLiteInterpreterAllocateTensors
import tensorflow.lite.c.TfLiteInterpreterCreate
import tensorflow.lite.c.TfLiteInterpreterDelete
import tensorflow.lite.c.TfLiteInterpreterGetInputTensor
import tensorflow.lite.c.TfLiteInterpreterGetOutputTensor
import tensorflow.lite.c.TfLiteInterpreterInvoke
import tensorflow.lite.c.TfLiteInterpreterOptionsCreate
import tensorflow.lite.c.TfLiteInterpreterOptionsDelete
import tensorflow.lite.c.TfLiteInterpreterOptionsSetNumThreads
import tensorflow.lite.c.TfLiteModelCreateFromFile
import tensorflow.lite.c.TfLiteModelDelete
import tensorflow.lite.c.TfLiteTensorCopyFromBuffer
import tensorflow.lite.c.TfLiteTensorCopyToBuffer
import tensorflow.lite.c.TfLiteTensorDim
import tensorflow.lite.c.kTfLiteOk

class IosCatDogClassifier(
    modelPath: String,
    threads: Int = 2,
) : AutoCloseable {

    private val model: CPointer<TfLiteModel>
    private val options: CPointer<TfLiteInterpreterOptions>
    private val interpreter: CPointer<TfLiteInterpreter>

    val inputSize: Int
    private val input: FloatArray
    private val output = FloatArray(1)

    init {
        model = TfLiteModelCreateFromFile(modelPath) ?: error("Could not load the model at $modelPath.")
        options = TfLiteInterpreterOptionsCreate() ?: run {
            TfLiteModelDelete(model)
            error("Could not create interpreter options.")
        }
        TfLiteInterpreterOptionsSetNumThreads(options, threads)
        interpreter = TfLiteInterpreterCreate(model, options) ?: run {
            TfLiteInterpreterOptionsDelete(options)
            TfLiteModelDelete(model)
            error("Could not create the interpreter.")
        }
        if (TfLiteInterpreterAllocateTensors(interpreter) != kTfLiteOk) {
            close()
            error("Could not allocate tensors.")
        }
        inputSize = TfLiteTensorDim(TfLiteInterpreterGetInputTensor(interpreter, 0), 1)
        input = FloatArray(inputSize * inputSize * ModelContract.CHANNELS)
    }

    fun classify(image: CGImageRef): Classification {
        val width = CGImageGetWidth(image).toInt()
        val height = CGImageGetHeight(image).toInt()
        val bytes = UByteArray(width * height * 4)
        return bytes.usePinned { pinned ->
            val colorSpace = CGColorSpaceCreateDeviceRGB()
            val context = CGBitmapContextCreate(
                pinned.addressOf(0),
                width.toULong(),
                height.toULong(),
                8u,
                (width * 4).toULong(),
                colorSpace,
                CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value or kCGBitmapByteOrder32Big,
            )
            CGColorSpaceRelease(colorSpace)
            checkNotNull(context) { "Could not create the decode bitmap." }
            CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
            CGContextRelease(context)
            val source = PixelSource(pinned.addressOf(0), width, height, width * 4, ChannelOrder.RGBA)
            classify(source, PixelRect(0, 0, width, height), 0)
        }
    }

    fun classify(source: PixelSource, crop: PixelRect, rotationDegrees: Int): Classification {
        IosFramePreparation.writeUprightRgb(source, crop, rotationDegrees, inputSize, input)
        val inputTensor = TfLiteInterpreterGetInputTensor(interpreter, 0)
        val copiedIn = input.usePinned {
            TfLiteTensorCopyFromBuffer(inputTensor, it.addressOf(0), (input.size * Float.SIZE_BYTES).toULong())
        }
        check(copiedIn == kTfLiteOk) { "Could not write the input tensor." }
        check(TfLiteInterpreterInvoke(interpreter) == kTfLiteOk) { "Inference failed." }
        val outputTensor = TfLiteInterpreterGetOutputTensor(interpreter, 0)
        val copiedOut = output.usePinned {
            TfLiteTensorCopyToBuffer(outputTensor, it.addressOf(0), Float.SIZE_BYTES.toULong())
        }
        check(copiedOut == kTfLiteOk) { "Could not read the output tensor." }
        return Classification(output[0])
    }

    override fun close() {
        TfLiteInterpreterDelete(interpreter)
        TfLiteInterpreterOptionsDelete(options)
        TfLiteModelDelete(model)
    }

    companion object {
        fun bundledModelPath(asset: String): String? {
            val name = asset.substringBeforeLast('.')
            val extension = asset.substringAfterLast('.', "")
            return NSBundle.mainBundle.pathForResource(name, extension)
        }
    }
}
