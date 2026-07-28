package com.johnanderson.familyportal

import com.johnanderson.familyportal.core.AppCoordinator
import com.johnanderson.familyportal.core.PortalOverlay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppCoordinatorTest {
    @Test
    fun missingStopExpiresAfterFourMinutes() = runTest {
        val coordinator = AppCoordinator(this)

        coordinator.startDoorbell("front", playChime = true, maxDurationSeconds = 240)
        assertTrue(coordinator.state.value.overlay is PortalOverlay.Doorbell)

        advanceTimeBy(240_000)
        runCurrent()
        assertNull(coordinator.state.value.overlay)
    }

    @Test
    fun manualDismissCancelsAlert() = runTest {
        val coordinator = AppCoordinator(this)
        coordinator.startDoorbell("front", playChime = false, maxDurationSeconds = 240)

        coordinator.dismissOverlay()

        assertNull(coordinator.state.value.overlay)
    }

    @Test
    fun stopKeepsAlertVisibleForPostRoll() = runTest {
        val coordinator = AppCoordinator(this)
        coordinator.startDoorbell("front", playChime = false, maxDurationSeconds = 240)

        advanceTimeBy(60_000)
        coordinator.finishDoorbell(postRollSeconds = 30)
        advanceTimeBy(29_999)
        runCurrent()
        assertTrue(coordinator.state.value.overlay is PortalOverlay.Doorbell)

        advanceTimeBy(1)
        runCurrent()
        assertNull(coordinator.state.value.overlay)
    }

    @Test
    fun newStartCancelsPostRollCountdown() = runTest {
        val coordinator = AppCoordinator(this)
        coordinator.startDoorbell("front", playChime = false, maxDurationSeconds = 240)
        coordinator.finishDoorbell(postRollSeconds = 30)

        advanceTimeBy(20_000)
        coordinator.startDoorbell("front", playChime = false, maxDurationSeconds = 240)
        advanceTimeBy(30_000)
        runCurrent()

        assertTrue(coordinator.state.value.overlay is PortalOverlay.Doorbell)
    }
}
