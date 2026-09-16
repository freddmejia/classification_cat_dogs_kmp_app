@file:OptIn(ExperimentalForeignApi::class)

package botix.dev.scannercatdogs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

class IosFramePreparationTest {

    private fun upright(
        width: Int,
        height: Int,
        order: ChannelOrder,
        crop: PixelRect,
        rotationDegrees: Int,
        size: Int,
        colorAt: (x: Int, y: Int) -> Int,
    ): List<Int> {
        val bytes = UByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rgb = colorAt(x, y)
                val i = (y * width + x) * 4
                bytes[i + order.red] = (rgb shr 16).toUByte()
                bytes[i + order.green] = (rgb shr 8).toUByte()
                bytes[i + order.blue] = rgb.toUByte()
                bytes[i + 3] = 0xFFu
            }
        }
        val out = FloatArray(size * size * 3)
        bytes.usePinned { pinned ->
            val source = PixelSource(pinned.addressOf(0), width, height, width * 4, order)
            IosFramePreparation.writeUprightRgb(source, crop, rotationDegrees, size, out)
        }
        return (0 until size * size).map { index ->
            val i = index * 3
            (out[i].toInt() shl 16) or (out[i + 1].toInt() shl 8) or out[i + 2].toInt()
        }
    }

    private fun halves(x: Int) = if (x == 0) RED else BLUE

    @Test
    fun ninetyDegreesRotatesClockwise() {
        val colors = upright(2, 2, ChannelOrder.RGBA, PixelRect(0, 0, 2, 2), 90, 2) { x, _ -> halves(x) }
        assertEquals(listOf(RED, RED, BLUE, BLUE), colors)
    }

    @Test
    fun oneEightyDegreesFlipsBothAxes() {
        val colors = upright(2, 2, ChannelOrder.RGBA, PixelRect(0, 0, 2, 2), 180, 2) { x, y ->
            if (x == 0 && y == 0) RED else BLUE
        }
        assertEquals(listOf(BLUE, BLUE, BLUE, RED), colors)
    }

    @Test
    fun twoSeventyDegreesRotatesCounterClockwise() {
        val colors = upright(2, 2, ChannelOrder.RGBA, PixelRect(0, 0, 2, 2), 270, 2) { x, _ -> halves(x) }
        assertEquals(listOf(BLUE, BLUE, RED, RED), colors)
    }

    @Test
    fun bgraIsSwappedToRgb() {
        val colors = upright(1, 1, ChannelOrder.BGRA, PixelRect(0, 0, 1, 1), 0, 1) { _, _ -> 0x102030 }
        assertEquals(listOf(0x102030), colors)
    }

    @Test
    fun cropReadsOnlyTheRequestedPixels() {
        val colors = upright(4, 4, ChannelOrder.RGBA, PixelRect(2, 2, 2, 2), 0, 2) { x, y ->
            if (x >= 2 && y >= 2) RED else BLUE
        }
        assertEquals(List(4) { RED }, colors)
    }

    @Test
    fun viewfinderSquareCentersInWideFrames() {
        assertEquals(PixelRect(280, 0, 720, 720), IosFramePreparation.viewfinderSquare(1280, 720, null))
    }

    @Test
    fun viewfinderSquareCentersInsideTheVisibleRegion() {
        val region = NormalizedRect(0.25, 0.0, 0.5, 1.0)
        assertEquals(PixelRect(320, 40, 640, 640), IosFramePreparation.viewfinderSquare(1280, 720, region))
    }

    @Test
    fun degenerateRegionFallsBackToTheFullFrame() {
        val region = NormalizedRect(0.5, 0.5, 0.0, 0.0)
        assertEquals(PixelRect(280, 0, 720, 720), IosFramePreparation.viewfinderSquare(1280, 720, region))
    }

    private companion object {
        const val RED = 0xFF0000
        const val BLUE = 0x0000FF
    }
}
