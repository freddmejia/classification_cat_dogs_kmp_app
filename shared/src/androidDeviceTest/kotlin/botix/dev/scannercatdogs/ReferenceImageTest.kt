package botix.dev.scannercatdogs

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceImageTest {

    private val assets: AssetManager
        get() = InstrumentationRegistry.getInstrumentation().context.assets

    private fun loadAsset(name: String): Bitmap =
        assets.open(name).use { BitmapFactory.decodeStream(it) }

    private fun assertReference(
        modelAsset: String,
        imageAsset: String,
        expected: Float,
        expectedLabel: Label,
    ) {
        AndroidCatDogClassifier(assets, modelAsset).use { classifier ->
            val result = classifier.classify(loadAsset(imageAsset))
            val delta = abs(result.dogProbability - expected)
            Log.i(TAG, "$imageAsset via $modelAsset: ${result.dogProbability} (${result.label}), "
                + "expected $expected, delta $delta")
            assertEquals(expectedLabel, result.label, "$imageAsset via $modelAsset")
            assertTrue(
                delta <= TOLERANCE,
                "$imageAsset via $modelAsset: got ${result.dogProbability}, expected $expected, delta $delta",
            )
        }
    }

    @Test
    fun defaultModelClassifiesDog() =
        assertReference(ModelContract.DEFAULT_MODEL_ASSET, "dog.png", 0.99254f, Label.DOG)

    @Test
    fun defaultModelClassifiesCat() =
        assertReference(ModelContract.DEFAULT_MODEL_ASSET, "cat.jpg", 0.00225f, Label.CAT)

    @Test
    fun optimizedModelClassifiesDog() =
        assertReference(ModelContract.OPTIMIZED_MODEL_ASSET, "dog.png", 0.99545f, Label.DOG)

    @Test
    fun optimizedModelClassifiesCat() =
        assertReference(ModelContract.OPTIMIZED_MODEL_ASSET, "cat.jpg", 0.00266f, Label.CAT)

    private companion object {
        const val TOLERANCE = 0.02f
        const val TAG = "ReferenceImageTest"
    }
}
