package com.johnanderson.familyportal

import com.johnanderson.familyportal.ui.cameraGridColumns
import com.johnanderson.familyportal.ui.retryDelayMillis
import com.johnanderson.familyportal.ui.shouldRetryMainStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraGridTest {

    @Test
    fun gridColumnsGrowWithCameraCount() {
        assertEquals(1, cameraGridColumns(0))
        assertEquals(1, cameraGridColumns(1))
        assertEquals(2, cameraGridColumns(2))
        assertEquals(2, cameraGridColumns(4))
        assertEquals(3, cameraGridColumns(5))
    }

    @Test
    fun mainStreamThatHasPlayedIsAlwaysRetried() {
        // Used to give up after a few failures across the whole viewing, leaving the
        // preview up for good.
        assertTrue(shouldRetryMainStream(failuresSinceLastPlayed = 10, hasPlayed = true))
        assertTrue(shouldRetryMainStream(failuresSinceLastPlayed = 2, hasPlayed = false))
        assertFalse(shouldRetryMainStream(failuresSinceLastPlayed = 3, hasPlayed = false))
    }

    @Test
    fun retriesBackOffToFifteenSeconds() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L), (1..6).map(::retryDelayMillis))
    }
}
