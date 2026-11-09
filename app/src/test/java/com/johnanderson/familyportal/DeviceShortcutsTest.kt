package com.johnanderson.familyportal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceShortcutsTest {
    private val familyPortal = HomeCandidate("com.johnanderson.familyportal", ".MainActivity", 0)
    private val fallbackHome = HomeCandidate("com.android.settings", "com.android.settings.FallbackHome", -1000)
    private val metaHome = HomeCandidate("com.facebook.aloha.app.home", "com.facebook.aloha.app.home.HomeActivity", 0)

    @Test
    fun opensTheOtherRealHomeScreen() {
        assertEquals(
            metaHome,
            otherHomeScreen(listOf(familyPortal, fallbackHome, metaHome), "com.johnanderson.familyportal"),
        )
    }

    @Test
    fun noOtherHomeScreenHidesTheButton() {
        // FallbackHome only shows "Android is starting" and would strand the user.
        assertNull(otherHomeScreen(listOf(familyPortal, fallbackHome), "com.johnanderson.familyportal"))
    }
}
