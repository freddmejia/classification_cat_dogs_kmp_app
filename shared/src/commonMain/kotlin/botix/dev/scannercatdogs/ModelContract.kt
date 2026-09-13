package botix.dev.scannercatdogs

enum class Label { CAT, DOG }

object ModelContract {
    const val INPUT_SIZE = 128
    const val CHANNELS = 3
    const val INPUT_FLOAT_COUNT = INPUT_SIZE * INPUT_SIZE * CHANNELS
    const val DOG_THRESHOLD = 0.5f
    const val DEFAULT_MODEL_ASSET = "cat_dog_mobilenetv3.tflite"
    const val OPTIMIZED_MODEL_ASSET = "cat_dog_mobilenetv3_optimized.tflite"
}

data class Classification(val dogProbability: Float) {
    val label: Label = if (dogProbability > ModelContract.DOG_THRESHOLD) Label.DOG else Label.CAT
    val confidence: Float = if (label == Label.DOG) dogProbability else 1f - dogProbability
}
