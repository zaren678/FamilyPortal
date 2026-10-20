package com.johnanderson.familyportal

import com.johnanderson.familyportal.ui.cameraGridColumns
import org.junit.Assert.assertEquals
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
}
