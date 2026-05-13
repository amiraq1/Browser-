package com.agentic.browser

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `isNewer returns true when remote is higher major`() {
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.0.0"))
    }

    @Test
    fun `isNewer returns true when remote is higher minor`() {
        assertTrue(UpdateChecker.isNewer("1.1.0", "1.0.0"))
    }

    @Test
    fun `isNewer returns true when remote is higher patch`() {
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0.0"))
    }

    @Test
    fun `isNewer returns false when versions are equal`() {
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.0"))
    }

    @Test
    fun `isNewer returns false when local is higher`() {
        assertFalse(UpdateChecker.isNewer("1.0.0", "2.0.0"))
    }

    @Test
    fun `isNewer handles different segment lengths`() {
        assertTrue(UpdateChecker.isNewer("1.0.0.1", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.1"))
    }
}
