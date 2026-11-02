/*
 * Copyright 2026 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.database

import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.tasks.ActionRunnable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DatabaseJobManagerTest {

    private lateinit var testScope: CoroutineScope
    private lateinit var jobManager: DatabaseJobManager

    @Before
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        jobManager = DatabaseJobManager(testScope)
        DatabaseJobManager.setInstanceForTest(jobManager)
    }

    @After
    fun tearDown() {
        DatabaseJobManager.setInstanceForTest(null)
        testScope.cancel()
    }

    @Test
    fun initialStateIsIdle() {
        assertEquals(DatabaseJobManager.DatabaseJobState.Idle, jobManager.jobState.value)
        assertEquals(0, jobManager.activeJobsCount.value)
        assertEquals(0, jobManager.savingJobsCount.value)
        assertFalse(jobManager.isSavingState.value)
    }

    @Test
    fun jobExecutionStateTransitionsAndCounting() = runBlocking {
        val collectedEvents = mutableListOf<DatabaseJobManager.DatabaseJobEvent>()
        val collectJob = launch {
            jobManager.jobEvents.collect { collectedEvents.add(it) }
        }
        yield()

        var observedRunningState = false
        var observedActiveCount = 0

        val result = jobManager.executeJob(
            database = null,
            actionTask = "TEST_LOAD_TASK",
            isSavingAction = false,
            isMainAction = true
        ) {
            observedActiveCount = jobManager.activeJobsCount.value
            observedRunningState = jobManager.jobState.value is DatabaseJobManager.DatabaseJobState.Running
            ActionRunnable.Result(isSuccess = true)
        }
        yield()

        assertTrue(result.isSuccess)
        assertTrue(observedRunningState)
        assertEquals(1, observedActiveCount)

        // On completion, state should be Idle and count 0
        assertEquals(DatabaseJobManager.DatabaseJobState.Idle, jobManager.jobState.value)
        assertEquals(0, jobManager.activeJobsCount.value)

        // Verify events sequence: Started -> Finished -> Stopped
        assertTrue(collectedEvents.any { it is DatabaseJobManager.DatabaseJobEvent.Started && it.actionTask == "TEST_LOAD_TASK" })
        assertTrue(collectedEvents.any { it is DatabaseJobManager.DatabaseJobEvent.Finished && it.actionTask == "TEST_LOAD_TASK" && it.result.isSuccess })
        assertTrue(collectedEvents.any { it is DatabaseJobManager.DatabaseJobEvent.Stopped })

        collectJob.cancel()
    }

    @Test
    fun savingActionStateAndCounting() = runBlocking {
        var observedSavingCount = 0
        var observedIsSaving = false

        val result = jobManager.executeJob(
            database = null,
            actionTask = "TEST_SAVE_TASK",
            isSavingAction = true,
            isMainAction = true
        ) {
            observedSavingCount = jobManager.savingJobsCount.value
            observedIsSaving = jobManager.isSavingState.value
            ActionRunnable.Result(isSuccess = true)
        }

        assertTrue(result.isSuccess)
        assertEquals(1, observedSavingCount)
        assertTrue(observedIsSaving)

        // After job completes, saving count & flag must reset
        assertEquals(0, jobManager.savingJobsCount.value)
        assertFalse(jobManager.isSavingState.value)
        assertEquals(0, jobManager.activeJobsCount.value)
    }

    @Test
    fun errorHandlingAndPropagation() = runBlocking {
        val collectedEvents = mutableListOf<DatabaseJobManager.DatabaseJobEvent>()
        val collectJob = launch {
            jobManager.jobEvents.collect { collectedEvents.add(it) }
        }
        yield()

        val result = jobManager.executeJob(
            database = null,
            actionTask = "TEST_FAIL_TASK",
            isSavingAction = false,
            isMainAction = true
        ) {
            throw IllegalStateException("Database corrupted or inaccessible")
        }
        yield()

        assertFalse(result.isSuccess)
        assertEquals("Database corrupted or inaccessible", result.message)

        // Ensure state recovers to Idle despite exception
        assertEquals(DatabaseJobManager.DatabaseJobState.Idle, jobManager.jobState.value)
        assertEquals(0, jobManager.activeJobsCount.value)

        val finishEvent = collectedEvents.filterIsInstance<DatabaseJobManager.DatabaseJobEvent.Finished>().firstOrNull()
        assertNotNull(finishEvent)
        assertFalse(finishEvent!!.result.isSuccess)
        assertEquals("Database corrupted or inaccessible", finishEvent.result.message)

        collectJob.cancel()
    }

    @Test
    fun progressUpdaterFlowEmission() = runBlocking {
        val collectedProgress = mutableListOf<DatabaseJobManager.DatabaseJobEvent>()
        val collectJob = launch {
            jobManager.jobEvents.collect { collectedProgress.add(it) }
        }

        jobManager.retrievingDatabaseKey()
        assertEquals(R.string.retrieving_db_key, jobManager.progressMessageFlow.value.messageId)

        jobManager.decryptingDatabase()
        assertEquals(R.string.decrypting_db, jobManager.progressMessageFlow.value.messageId)

        jobManager.benchmarking()
        assertEquals(R.string.benchmarking, jobManager.progressMessageFlow.value.messageId)

        collectJob.cancel()
    }

    @Test
    fun snapshotGraceWindowSuppressesRecentInAppSave() {
        val now = 1_000_000L
        val recentInAppSave = now - 5_000L // 5 seconds ago (< 15s)

        // 1. File modified 5s ago during recent in-app save -> must be suppressed
        val suppressedByRecentSave = jobManager.evaluateExternalModification(
            isSaving = false,
            lastInAppSaveTimeMillis = recentInAppSave,
            currentTimeMillis = now,
            oldModification = 900_000L,
            newModification = 995_000L,
            oldSize = 1024L,
            newSize = 1040L,
            existsChanged = false
        )
        assertFalse(suppressedByRecentSave)

        // 2. While actively saving -> must be suppressed
        val suppressedWhileSaving = jobManager.evaluateExternalModification(
            isSaving = true,
            lastInAppSaveTimeMillis = 0L,
            currentTimeMillis = now,
            oldModification = 900_000L,
            newModification = 1_100_000L,
            oldSize = 1024L,
            newSize = 2048L,
            existsChanged = false
        )
        assertFalse(suppressedWhileSaving)
    }

    @Test
    fun snapshotDetectsLegitimateExternalModification() {
        val now = 1_000_000L
        val oldSave = now - 60_000L // 60 seconds ago (> 15s grace window)

        // 1. Legitimate file modification beyond debounce threshold (+15s delta)
        val detected = jobManager.evaluateExternalModification(
            isSaving = false,
            lastInAppSaveTimeMillis = oldSave,
            currentTimeMillis = now,
            oldModification = oldSave,
            newModification = oldSave + 25_000L, // +25s > 10s debounce threshold
            oldSize = 1024L,
            newSize = 1050L,
            existsChanged = false
        )
        assertTrue(detected)

        // 2. Modification below 10s threshold -> debounced
        val debounced = jobManager.evaluateExternalModification(
            isSaving = false,
            lastInAppSaveTimeMillis = oldSave,
            currentTimeMillis = now,
            oldModification = oldSave,
            newModification = oldSave + 5_000L, // only +5s <= 10s threshold
            oldSize = 1024L,
            newSize = 1050L,
            existsChanged = false
        )
        assertFalse(debounced)

        // 3. File existence changed -> must be detected
        val fileStatusChanged = jobManager.evaluateExternalModification(
            isSaving = false,
            lastInAppSaveTimeMillis = oldSave,
            currentTimeMillis = now,
            oldModification = oldSave,
            newModification = oldSave,
            oldSize = 1024L,
            newSize = 1024L,
            existsChanged = true
        )
        assertTrue(fileStatusChanged)
    }

    @Test
    fun challengeResponseChannelCancellation() = runBlocking {
        jobManager.initializeChallengeResponse()
        jobManager.cancelChallengeResponse("Test cancel")
        // Verify subsequent cancel doesn't crash
        jobManager.closeChallengeResponse()
    }

    @Test
    fun multipleConcurrentJobsActiveCount() = runBlocking {
        val job1Started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job1Proceed = kotlinx.coroutines.CompletableDeferred<Unit>()

        val job2Started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job2Proceed = kotlinx.coroutines.CompletableDeferred<Unit>()

        var peakActiveCount = 0

        val c1 = launch {
            jobManager.executeJob(null, "JOB_1", isSavingAction = false) {
                job1Started.complete(Unit)
                job1Proceed.await()
                ActionRunnable.Result(isSuccess = true)
            }
        }

        val c2 = launch {
            jobManager.executeJob(null, "JOB_2", isSavingAction = false) {
                job2Started.complete(Unit)
                job2Proceed.await()
                ActionRunnable.Result(isSuccess = true)
            }
        }

        job1Started.await()
        job2Started.await()

        peakActiveCount = jobManager.activeJobsCount.value
        assertEquals(2, peakActiveCount)
        assertTrue(jobManager.jobState.value is DatabaseJobManager.DatabaseJobState.Running)

        // Release job1
        job1Proceed.complete(Unit)
        c1.join()

        // After job1 finishes, active count is 1, still Running
        assertEquals(1, jobManager.activeJobsCount.value)
        assertTrue(jobManager.jobState.value is DatabaseJobManager.DatabaseJobState.Running)

        // Release job2
        job2Proceed.complete(Unit)
        c2.join()

        // After both finish, active count is 0, state is Idle
        assertEquals(0, jobManager.activeJobsCount.value)
        assertEquals(DatabaseJobManager.DatabaseJobState.Idle, jobManager.jobState.value)
    }

    @Test
    fun sequentialJobsExecution() = runBlocking {
        for (i in 1..5) {
            val res = jobManager.executeJob(null, "SEQ_$i", isSavingAction = (i % 2 == 0)) {
                ActionRunnable.Result(isSuccess = true)
            }
            assertTrue(res.isSuccess)
            assertEquals(0, jobManager.activeJobsCount.value)
            assertEquals(0, jobManager.savingJobsCount.value)
            assertFalse(jobManager.isSavingState.value)
            assertEquals(DatabaseJobManager.DatabaseJobState.Idle, jobManager.jobState.value)
        }
    }
}
