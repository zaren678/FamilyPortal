package com.johnanderson.familyportal

import com.johnanderson.familyportal.ha.HomeAssistantStateChange
import com.johnanderson.familyportal.ha.DoorbellTransition
import com.johnanderson.familyportal.ha.doorbellTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoorbellTransitionTest {
    @Test
    fun tracksConfiguredSensorStartAndStopTransitions() {
        assertEquals(
            DoorbellTransition.START,
            HomeAssistantStateChange("binary_sensor.front", "off", "on")
                .doorbellTransition("binary_sensor.front"),
        )
        assertNull(
            HomeAssistantStateChange("binary_sensor.back", "off", "on")
                .doorbellTransition("binary_sensor.front"),
        )
        assertNull(
            HomeAssistantStateChange("binary_sensor.front", "on", "on")
                .doorbellTransition("binary_sensor.front"),
        )
        assertEquals(
            DoorbellTransition.STOP,
            HomeAssistantStateChange("binary_sensor.front", "on", "off")
                .doorbellTransition("binary_sensor.front"),
        )
    }
}
