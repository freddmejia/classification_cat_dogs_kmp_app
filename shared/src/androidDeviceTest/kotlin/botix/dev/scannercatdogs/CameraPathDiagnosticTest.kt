package botix.dev.scannercatdogs

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Base64
import java.io.ByteArrayOutputStream
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraPathDiagnosticTest {

    private val assets: AssetManager
        get() = InstrumentationRegistry.getInstrumentation().context.assets

    private fun loadAsset(name: String): Bitmap =
        assets.open(name).use { BitmapFactory.decodeStream(it) }

    private fun dump(bitmap: Bitmap, name: String) {
        val bytes = ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        Log.i("CameraPath", "BEGIN " + name + " " + encoded.length)
        encoded.chunked(2000).forEachIndexed { index, chunk ->
            Log.i("CameraPath", "B64 " + name + " " + index + " " + chunk)
        }
        Log.i("CameraPath", "END " + name)
    }

    @Test
    fun cropAndRotationVariantsOfReferenceImages() {
        AndroidCatDogClassifier(assets, ModelContract.DEFAULT_MODEL_ASSET).use { classifier ->
            for (asset in listOf("dog.png", "cat.jpg")) {
                val source = loadAsset(asset)
                for (rotation in listOf(0, 90, 180, 270)) {
                    for (crop in listOf(false, true)) {
                        val prepared = if (crop) {
                            FramePreparation.uprightSquare(source, rotation)
                        } else {
                            FramePreparation.upright(source, rotation)
                        }
                        val result = classifier.classify(prepared)
                        val tag = asset.substringBefore('.') +
                            "_rot" + rotation + (if (crop) "_crop" else "_full")
                        dump(classifier.lastInputAsBitmap(), tag)
                        Log.i(
                            "CameraPath",
                            tag + " -> " + result.label + " dogProbability=" + result.dogProbability,
                        )
                    }
                }
            }
        }
    }
}
