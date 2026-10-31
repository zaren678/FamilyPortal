package com.johnanderson.familyportal

import com.johnanderson.familyportal.ha.DoorbellTransition.START
import com.johnanderson.familyportal.ha.DoorbellTransition.STOP
import com.johnanderson.familyportal.ha.HomeAssistantAuthenticationException
import com.johnanderson.familyportal.ha.HomeAssistantEntityState
import com.johnanderson.familyportal.ha.catchUpTransition
import com.johnanderson.familyportal.ha.doorbellTransition
import com.johnanderson.familyportal.ha.watchDoorbellSensor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class DoorbellMonitorTest {
    private val now = Instant.parse("2026-10-30T20:00:00Z")

    @Test
    fun sensorTurningOnStartsAndTurningOffStops() {
        assertEquals(START, doorbellTransition("off", "on"))
        assertEquals(START, doorbellTransition("unavailable", "on"))
        assertNull(doorbellTransition("on", "on"))
        assertEquals(STOP, doorbellTransition("on", "off"))
    }

    @Test
    fun catchesUpOnlyOnRecentArrivals() {
        val off = HomeAssistantEntityState("off")
        val on = HomeAssistantEntityState("on")
        assertEquals(START, catchUpTransition(off, HomeAssistantEntityState("on", now.minusSeconds(30)), now))
        assertEquals(START, catchUpTransition(null, HomeAssistantEntityState("on", now.minusSeconds(30)), now))
        // A sensor that's been on for an hour isn't a new visit.
        assertNull(catchUpTransition(null, HomeAssistantEntityState("on", now.minusSeconds(3_600)), now))
        // Already alerting for this visit.
        assertNull(catchUpTransition(on, HomeAssistantEntityState("on", now.minusSeconds(30)), now))
        // The visit ended while disconnected.
        assertEquals(STOP, catchUpTransition(on, off, now))
        assertNull(catchUpTransition(off, off, now))
    }

    @Test
    fun reconnectingCatchesUpOnVisitsMissedWhileDisconnected() = runTest {
        val transitions = watchDoorbellSensor(
            sensorEntityId = SENSOR,
            accessToken = { "token" },
            entityStates = connections(
                flow {
                    emit(HomeAssistantEntityState("off"))
                    emit(HomeAssistantEntityState("on"))
                    throw IOException("dropped")
                },
                // The visit ended while disconnected.
                flow {
                    emit(HomeAssistantEntityState("off"))
                    throw IOException("dropped")
                },
                // Someone arrived while disconnected.
                flow {
                    emit(HomeAssistantEntityState("on", now.minusSeconds(30)))
                    awaitCancellation()
                },
            ),
            now = { now },
        ).take(3).toList()

        assertEquals(listOf(START, STOP, START), transitions)
    }

    @Test
    fun reconnectsQuicklyOnceAConnectionHasWorked() = runTest {
        val attempts = mutableListOf<Long>()
        var connections = 0
        val transition = watchDoorbellSensor(
            sensorEntityId = SENSOR,
            // No token for the first six attempts, which keeps retrying rather than giving up.
            accessToken = {
                attempts += testScheduler.currentTime
                if (attempts.size <= 6) null else "token"
            },
            entityStates = {
                flow {
                    emit(HomeAssistantEntityState("off"))
                    if (++connections == 1) throw IOException("dropped")
                    emit(HomeAssistantEntityState("on"))
                    awaitCancellation()
                }
            },
        ).first()

        assertEquals(START, transition)
        val gaps = attempts.zipWithNext { previous, next -> next - previous }
        assertEquals(7, gaps.size)
        assertTrue("backs off to 15s, was ${gaps[4]}", gaps[4] in 15_000..15_250)
        assertTrue("retries ~1s after a working connection drops, was ${gaps.last()}", gaps.last() < 1_250)
    }

    @Test
    fun rejectedTokenIsRefreshedOnTheNextAttempt() = runTest {
        val forcedRefreshes = mutableListOf<Boolean>()
        watchDoorbellSensor(
            sensorEntityId = SENSOR,
            accessToken = { forceRefresh ->
                forcedRefreshes += forceRefresh
                "token"
            },
            entityStates = connections(
                flow { throw HomeAssistantAuthenticationException("rejected") },
                flow {
                    emit(HomeAssistantEntityState("off"))
                    emit(HomeAssistantEntityState("on"))
                    awaitCancellation()
                },
            ),
        ).first()

        assertEquals(listOf(false, true), forcedRefreshes)
    }

    private fun connections(vararg flows: Flow<HomeAssistantEntityState>): (String) -> Flow<HomeAssistantEntityState> {
        val remaining = ArrayDeque(flows.toList())
        return { remaining.removeFirst() }
    }

    private companion object {
        const val SENSOR = "binary_sensor.front_person"
    }
}
