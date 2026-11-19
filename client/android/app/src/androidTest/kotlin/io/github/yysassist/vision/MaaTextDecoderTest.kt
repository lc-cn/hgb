package io.github.yysassist.vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MaaTextDecoderTest {
    private val valid = """{"taskStatus":3000,"controllerBound":false,"hit":true,"detail":{"all":[{"text":"领取","score":0.9,"box":[1,2,10,20]}]}}"""
    @Test fun preservesTextScoresAndScreenPixelBoundsWithoutAHiddenConfidenceThreshold() {
        val result = MaaTextDecoder.decode(valid, 100, 100) as BackendTextResult.Observed
        assertEquals(listOf(RecognizedText("领取", 0.9, TextBounds(1, 2, 10, 20))), result.candidates)
        assertEquals(0.01, (MaaTextDecoder.decode(valid.replace("0.9", "0.01"), 100, 100) as BackendTextResult.Observed).candidates.single().confidence, 0.0)
    }
    @Test fun successfulEmptyResponseStaysDistinctFromMalformedOrFailedTransport() {
        assertEquals(BackendTextResult.Observed(emptyList<RecognizedText>()), MaaTextDecoder.decode("""{"taskStatus":3000,"controllerBound":false,"hit":false,"detail":{"all":[]}}""", 100, 100))
        for (bad in listOf("{}", valid.replace("3000", "4000"), valid.replace("\"hit\":true", "\"hit\":\"true\""), valid.dropLast(1)))
            assertEquals(BackendTextResult.Unavailable(TextReadProblem.INVALID_OUTPUT), MaaTextDecoder.decode(bad, 100, 100))
    }
    @Test fun invalidCoordinatesTypesOrScoresCannotBecomeClickableTargets() {
        for (bad in listOf(valid.replace("[1,2,10,20]", "[-1,2,10,20]"), valid.replace("[1,2,10,20]", "[99,2,10,20]"),
            valid.replace("[1,2,10,20]", "[1,2,2147483647,20]"), valid.replace("[1,2,10,20]", "[\"1\",2,10,20]"), valid.replace("0.9", "1.1")))
            assertEquals(BackendTextResult.Unavailable(TextReadProblem.INVALID_OUTPUT), MaaTextDecoder.decode(bad, 100, 100))
    }
}
