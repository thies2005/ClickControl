package com.ankiride.bridge

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopInstanceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun secondLaunchRequestsReopenAndClosingReleasesOwnership() = runBlocking {
        val directory = temp.root.toPath()
        checkNotNull(DesktopInstance.open(directory)).use { first ->
            assertNull(DesktopInstance.open(directory))
            assertTrue(withTimeout(3000) { first.awaitReopen() })
        }
        DesktopInstance.open(directory).use { assertNotNull(it) }
    }
}
