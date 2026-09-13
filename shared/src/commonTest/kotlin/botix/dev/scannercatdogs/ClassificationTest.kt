package botix.dev.scannercatdogs

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassificationTest {

    private fun assertClose(expected: Float, actual: Float) =
        assertTrue(abs(expected - actual) < 1e-4f, "expected $expected, got $actual")

    @Test
    fun catReferenceScoreIsAConfidentCat() {
        val result = Classification(0.00225f)
        assertEquals(Label.CAT, result.label)
        assertClose(0.99775f, result.confidence)
    }

    @Test
    fun dogReferenceScoreIsAConfidentDog() {
        val result = Classification(0.99254f)
        assertEquals(Label.DOG, result.label)
        assertClose(0.99254f, result.confidence)
    }

    @Test
    fun thresholdIsExclusiveSoExactlyHalfIsCat() {
        assertEquals(Label.CAT, Classification(0.5f).label)
        assertEquals(Label.DOG, Classification(0.5001f).label)
    }

    @Test
    fun scoresNearHalfAreGenuinelyUncertain() {
        val result = Classification(0.498f)
        assertEquals(Label.CAT, result.label)
        assertClose(0.502f, result.confidence)
    }

    @Test
    fun confidenceIsNeverBelowHalfForAnyScore() {
        var score = 0f
        while (score <= 1f) {
            val confidence = Classification(score).confidence
            assertTrue(confidence >= 0.5f, "score $score gave confidence $confidence")
            assertTrue(confidence <= 1f, "score $score gave confidence $confidence")
            score += 0.01f
        }
    }
}
