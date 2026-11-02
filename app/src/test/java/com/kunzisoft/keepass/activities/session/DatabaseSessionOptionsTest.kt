package com.kunzisoft.keepass.activities.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseSessionOptionsTest {

    @Test
    fun defaultOptionsValues() {
        val options = DatabaseSessionOptions()
        assertTrue(options.enableTimeout)
        assertTrue(options.manageDatabaseInfo)
        assertTrue(options.enableSpecialMode)
        assertFalse(options.timeoutRetrievalGuard)
        assertFalse(options.finishActivityIfReloadRequested)
    }

    @Test
    fun customOptionsConfiguration() {
        val options = DatabaseSessionOptions(
            enableTimeout = false,
            manageDatabaseInfo = false,
            enableSpecialMode = false,
            timeoutRetrievalGuard = true,
            finishActivityIfReloadRequested = true
        )
        assertFalse(options.enableTimeout)
        assertFalse(options.manageDatabaseInfo)
        assertFalse(options.enableSpecialMode)
        assertTrue(options.timeoutRetrievalGuard)
        assertTrue(options.finishActivityIfReloadRequested)
    }

    @Test
    fun copyOptionsModification() {
        val base = DatabaseSessionOptions()
        val modified = base.copy(
            finishActivityIfReloadRequested = true,
            enableSpecialMode = false
        )
        assertTrue(modified.enableTimeout)
        assertTrue(modified.manageDatabaseInfo)
        assertFalse(modified.enableSpecialMode)
        assertFalse(modified.timeoutRetrievalGuard)
        assertTrue(modified.finishActivityIfReloadRequested)
    }
}
