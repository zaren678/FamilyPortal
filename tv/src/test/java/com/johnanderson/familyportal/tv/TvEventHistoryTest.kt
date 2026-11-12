package com.johnanderson.familyportal.tv

import com.johnanderson.familyportal.ha.FrigateCamera
import com.johnanderson.familyportal.ha.FrigateEvent
import com.johnanderson.familyportal.tv.ui.clipTime
import com.johnanderson.familyportal.tv.ui.historyGridIndex
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TvEventHistoryTest {
    @Test
    fun gridIndexCountsEachDaysHeading() {
        val days = listOf(
            LocalDate.of(2026, 11, 11) to listOf(event("a"), event("b")),
            LocalDate.of(2026, 11, 10) to listOf(event("c")),
        )
        // [Today] a b [Yesterday] c
        assertEquals(1, historyGridIndex(days, "a"))
        assertEquals(2, historyGridIndex(days, "b"))
        assertEquals(4, historyGridIndex(days, "c"))
        assertEquals("gone since", 0, historyGridIndex(days, "z"))
    }

    @Test
    fun clipTimesAreMinutesAndSeconds() {
        assertEquals("0:00", clipTime(0))
        assertEquals("0:07", clipTime(7_900))
        assertEquals("1:20", clipTime(80_000))
    }

    private fun event(id: String) = FrigateEvent(
        id = id,
        camera = FrigateCamera("frigate", "doorbell"),
        label = "person",
        subLabel = null,
        startTime = Instant.EPOCH,
        endTime = Instant.EPOCH,
        hasClip = true,
        hasSnapshot = true,
    )
}
