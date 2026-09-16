@file:OptIn(ExperimentalForeignApi::class)

package botix.dev.scannercatdogs

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIImage

class ReferenceImageIosTest {

    private val assetsDir: String
        get() = assertNotNull(
            NSProcessInfo.processInfo.environment["SCANNER_ASSETS_DIR"] as? String,
            "SCANNER_ASSETS_DIR is not set",
        )

    private fun assertReference(
        modelAsset: String,
        imageAsset: String,
        expected: Float,
        expectedLabel: Label,
    ) {
        val image = assertNotNull(UIImage.imageWithContentsOfFile("$assetsDir/$imageAsset")?.CGImage, imageAsset)
        IosCatDogClassifier("$assetsDir/$modelAsset").use { classifier ->
            val result = classifier.classify(image)
            val delta = abs(result.dogProbability - expected)
            println(
                "ReferenceImageIosTest: $imageAsset via $modelAsset: ${result.dogProbability} " +
                    "(${result.label}), expected $expected, delta $delta"
            )
            assertEquals(expectedLabel, result.label, "$imageAsset via $modelAsset")
            assertTrue(
                delta <= TOLERANCE,
                "$imageAsset via $modelAsset: got ${result.dogProbability}, expected $expected, delta $delta",
            )
        }
    }

    @Test
    fun defaultModelClassifiesDog() =
        assertReference(CONTRACT_MODEL, "dog.png", 0.99254f, Label.DOG)

    @Test
    fun defaultModelClassifiesCat() =
        assertReference(CONTRACT_MODEL, "cat.jpg", 0.00225f, Label.CAT)

    @Test
    fun optimizedModelClassifiesDog() =
        assertReference(CONTRACT_OPTIMIZED_MODEL, "dog.png", 0.99545f, Label.DOG)

    @Test
    fun optimizedModelClassifiesCat() =
        assertReference(CONTRACT_OPTIMIZED_MODEL, "cat.jpg", 0.00266f, Label.CAT)

    private companion object {
        const val TOLERANCE = 0.02f
        const val CONTRACT_MODEL = "cat_dog_mobilenetv3.tflite"
        const val CONTRACT_OPTIMIZED_MODEL = "cat_dog_mobilenetv3_optimized.tflite"
    }
}
