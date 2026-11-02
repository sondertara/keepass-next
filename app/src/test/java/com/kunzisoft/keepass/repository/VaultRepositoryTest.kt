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
package com.kunzisoft.keepass.repository

import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.DatabaseJobManager
import com.kunzisoft.keepass.database.element.EntryId
import com.kunzisoft.keepass.database.element.GroupId
import com.kunzisoft.keepass.database.element.node.NodeIdUUID
import com.kunzisoft.keepass.database.search.SearchParameters
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.GroupInfo
import com.kunzisoft.keepass.model.SortedNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VaultRepositoryTest {

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
        testScope.cancel()
        DatabaseJobManager.setInstanceForTest(null)
        VaultRepositoryImpl.setInstanceForTest(null)
    }

    @Test
    fun singletonInstance_canBeOverriddenAndReset() {
        VaultRepositoryImpl.setInstanceForTest(null)
        val defaultRepo = VaultRepositoryImpl.getInstance()
        assertNotNull(defaultRepo)

        val customRepo = VaultRepositoryImpl(
            databaseProvider = { null },
            jobManagerProvider = { jobManager }
        )
        VaultRepositoryImpl.setInstanceForTest(customRepo)
        assertSame(customRepo, VaultRepositoryImpl.getInstance())
    }

    @Test
    fun lockedVault_returnsNullAndEmptyForQueries() {
        val dummyGroupId = GroupId()
        val dummyEntryId = EntryId()

        // Vault with null database
        val nullDbRepo = VaultRepositoryImpl(
            databaseProvider = { null },
            jobManagerProvider = { jobManager }
        )
        assertFalse(nullDbRepo.isUnlocked)
        assertNull(nullDbRepo.database)
        assertNull(nullDbRepo.getRootGroup())
        assertNull(nullDbRepo.getGroup(dummyGroupId))
        assertNull(nullDbRepo.getEntry(dummyEntryId))
        assertTrue(nullDbRepo.getChildren(dummyGroupId).isEmpty())
        assertTrue(nullDbRepo.search(SearchParameters()).isEmpty())

        // Vault with database where loaded = false
        val lockedDb = ContextualDatabase.getInstance().apply {
            loaded = false
        }
        val lockedRepo = VaultRepositoryImpl(
            databaseProvider = { lockedDb },
            jobManagerProvider = { jobManager }
        )
        assertFalse(lockedRepo.isUnlocked)
        assertNull(lockedRepo.getRootGroup())
        assertNull(lockedRepo.getGroup(dummyGroupId))
        assertNull(lockedRepo.getEntry(dummyEntryId))
        assertTrue(lockedRepo.getChildren(dummyGroupId).isEmpty())
        assertTrue(lockedRepo.search(SearchParameters()).isEmpty())
    }

    @Test
    fun lockedVault_mutationsFailSafely() = runBlocking {
        val dummyGroupId = GroupId()
        val dummyEntryId = EntryId()

        val nullDbRepo = VaultRepositoryImpl(
            databaseProvider = { null },
            jobManagerProvider = { jobManager }
        )

        val entryResult = nullDbRepo.addEntry(EntryInfo(), dummyGroupId)
        assertTrue(entryResult.isFailure)
        assertTrue(entryResult.exceptionOrNull() is IllegalStateException)

        val updateEntryResult = nullDbRepo.updateEntry(EntryInfo())
        assertTrue(updateEntryResult.isFailure)

        val deleteEntryResult = nullDbRepo.deleteEntry(dummyEntryId)
        assertTrue(deleteEntryResult.isFailure)

        val groupResult = nullDbRepo.addGroup(GroupInfo(), dummyGroupId)
        assertTrue(groupResult.isFailure)

        val updateGroupResult = nullDbRepo.updateGroup(GroupInfo())
        assertTrue(updateGroupResult.isFailure)

        val deleteGroupResult = nullDbRepo.deleteGroup(dummyGroupId)
        assertTrue(deleteGroupResult.isFailure)
    }

    @Test
    fun unlockedVault_queriesDelegateToDatabase() {
        val testDb = ContextualDatabase.getInstance().apply {
            loaded = true
        }
        val repo = VaultRepositoryImpl(
            databaseProvider = { testDb },
            jobManagerProvider = { jobManager }
        )

        assertTrue(repo.isUnlocked)
        assertSame(testDb, repo.database)
    }

    @Test
    fun unlockedVault_mutationsSerializeViaJobManager() = runBlocking {
        val dummyGroupId = GroupId()
        val dummyEntryId = EntryId()

        val testDb = ContextualDatabase.getInstance().apply {
            loaded = true
        }
        val repo = VaultRepositoryImpl(
            databaseProvider = { testDb },
            jobManagerProvider = { jobManager }
        )

        // When entry is not found in database, updateEntry fails gracefully
        val updateResult = repo.updateEntry(EntryInfo().apply { nodeId = dummyEntryId })
        assertTrue(updateResult.isFailure)

        val deleteResult = repo.deleteEntry(dummyEntryId)
        assertTrue(deleteResult.isFailure)

        val updateGroupResult = repo.updateGroup(GroupInfo().apply { nodeId = dummyGroupId })
        assertTrue(updateGroupResult.isFailure)

        val deleteGroupResult = repo.deleteGroup(dummyGroupId)
        assertTrue(deleteGroupResult.isFailure)
    }
}
