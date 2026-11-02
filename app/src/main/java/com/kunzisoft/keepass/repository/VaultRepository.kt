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
import com.kunzisoft.keepass.database.element.node.EmptyNodeFilter
import com.kunzisoft.keepass.database.element.node.NodeFilter
import com.kunzisoft.keepass.database.search.SearchParameters
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.GroupInfo
import com.kunzisoft.keepass.model.SortedNodeInfo
import com.kunzisoft.keepass.tasks.ActionRunnable
import kotlinx.coroutines.flow.StateFlow

/**
 * Clean domain repository providing structured, isolated access to the password vault.
 * Shields callers from direct singleton interaction with [ContextualDatabase] and serializes
 * modifications through [DatabaseJobManager].
 */
interface VaultRepository {

    /**
     * Whether the vault is currently loaded and unlocked in memory.
     */
    val isUnlocked: Boolean

    /**
     * The underlying contextual database instance, or null if locked.
     */
    val database: ContextualDatabase?

    /**
     * Reactive flow representing the current vault session state (Locked vs Unlocked).
     */
    val sessionState: StateFlow<DatabaseJobManager.VaultSessionState>
        get() = DatabaseJobManager.getInstance().sessionState

    /**
     * Reactive flow indicating whether the database has unsaved modifications.
     */
    val dataModifiedFlow: StateFlow<Boolean>?

    /**
     * Retrieves the root group of the unlocked vault.
     */
    fun getRootGroup(): GroupInfo?

    /**
     * Retrieves group information by its identifier.
     */
    fun getGroup(groupId: GroupId): GroupInfo?

    /**
     * Retrieves entry information by its identifier.
     */
    fun getEntry(entryId: EntryId, raw: Boolean = false): EntryInfo?

    /**
     * Retrieves all child nodes (groups and entries) belonging to the specified parent group.
     */
    fun getChildren(
        groupId: GroupId,
        recursiveNumberOfEntries: Boolean = false,
        nodeFilter: NodeFilter = EmptyNodeFilter()
    ): List<SortedNodeInfo>

    /**
     * Performs a domain search across the vault using given parameters.
     */
    fun search(parameters: SearchParameters, max: Int = 1000): List<EntryInfo>

    /**
     * Adds an entry to the vault, serialized through the job manager.
     */
    suspend fun addEntry(entryInfo: EntryInfo, parentGroupId: GroupId): Result<EntryId>

    /**
     * Updates an existing entry in the vault, serialized through the job manager.
     */
    suspend fun updateEntry(entryInfo: EntryInfo): Result<Unit>

    /**
     * Deletes an entry from the vault, serialized through the job manager.
     */
    suspend fun deleteEntry(entryId: EntryId): Result<Unit>

    /**
     * Adds a group to the vault.
     */
    suspend fun addGroup(groupInfo: GroupInfo, parentGroupId: GroupId): Result<GroupId>

    /**
     * Updates a group in the vault.
     */
    suspend fun updateGroup(groupInfo: GroupInfo): Result<Unit>

    /**
     * Deletes a group from the vault.
     */
    suspend fun deleteGroup(groupId: GroupId): Result<Unit>
}

/**
 * Standard implementation of [VaultRepository].
 */
class VaultRepositoryImpl(
    private val databaseProvider: () -> ContextualDatabase? = { ContextualDatabase.getInstance() },
    private val jobManagerProvider: () -> DatabaseJobManager = { DatabaseJobManager.getInstance() }
) : VaultRepository {

    override val database: ContextualDatabase?
        get() = databaseProvider()?.takeIf { it.loaded }

    override val isUnlocked: Boolean
        get() = database?.loaded == true

    override val sessionState: StateFlow<DatabaseJobManager.VaultSessionState>
        get() = jobManagerProvider().sessionState

    override val dataModifiedFlow: StateFlow<Boolean>?
        get() = database?.dataModifiedSinceLastLoadingFlow

    override fun getRootGroup(): GroupInfo? {
        val db = database ?: return null
        if (!db.loaded) return null
        return db.getRootGroupInfo()
    }

    override fun getGroup(groupId: GroupId): GroupInfo? {
        val db = database ?: return null
        if (!db.loaded) return null
        return db.getGroupInfoById(groupId)
    }

    override fun getEntry(entryId: EntryId, raw: Boolean): EntryInfo? {
        val db = database ?: return null
        if (!db.loaded) return null
        return db.getEntryInfoById(entryId, raw)
    }

    override fun getChildren(
        groupId: GroupId,
        recursiveNumberOfEntries: Boolean,
        nodeFilter: NodeFilter
    ): List<SortedNodeInfo> {
        val db = database ?: return emptyList()
        if (!db.loaded) return emptyList()
        return db.getSortedChildrenOf(groupId, recursiveNumberOfEntries, nodeFilter)
    }

    override fun search(parameters: SearchParameters, max: Int): List<EntryInfo> {
        val db = database ?: return emptyList()
        if (!db.loaded) return emptyList()
        return try {
            val searchGroup = db.createSearchGroupInfo(searchParameters = parameters, max = max)
            searchGroup.getSearchResults()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun addEntry(entryInfo: EntryInfo, parentGroupId: GroupId): Result<EntryId> {
        val db = database ?: return Result.failure(IllegalStateException("Vault is locked"))
        val actionResult = jobManagerProvider().executeJob(
            database = db,
            actionTask = "ADD_ENTRY",
            isSavingAction = true
        ) { _ ->
            val parent = db.getGroupById(parentGroupId) ?: db.rootGroup
            val newEntry = db.createEntry(entryInfo)
            if (newEntry != null && parent != null) {
                newEntry.touch(true, true)
                parent.touch(true, true)
                db.addEntryTo(newEntry, parent)
                ActionRunnable.Result(isSuccess = true)
            } else {
                ActionRunnable.Result(isSuccess = false, message = "Could not create entry")
            }
        }
        return if (actionResult.isSuccess) {
            Result.success(entryInfo.nodeId)
        } else {
            Result.failure(actionResult.exception ?: RuntimeException(actionResult.message ?: "Failed to add entry"))
        }
    }

    override suspend fun updateEntry(entryInfo: EntryInfo): Result<Unit> {
        val db = database ?: return Result.failure(IllegalStateException("Vault is locked"))
        val actionResult = jobManagerProvider().executeJob(
            database = db,
            actionTask = "UPDATE_ENTRY",
            isSavingAction = true
        ) { _ ->
            val oldEntry = db.getEntryById(entryInfo.nodeId)
            if (oldEntry != null) {
                db.updateEntry(oldEntry, entryInfo)
                oldEntry.touch(true, true)
                ActionRunnable.Result(isSuccess = true)
            } else {
                ActionRunnable.Result(isSuccess = false, message = "Entry not found")
            }
        }
        return if (actionResult.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(actionResult.exception ?: RuntimeException(actionResult.message ?: "Failed to update entry"))
        }
    }

    override suspend fun deleteEntry(entryId: EntryId): Result<Unit> {
        val db = database ?: return Result.failure(IllegalStateException("Vault is locked"))
        val actionResult = jobManagerProvider().executeJob(
            database = db,
            actionTask = "DELETE_ENTRY",
            isSavingAction = true
        ) { _ ->
            val entry = db.getEntryById(entryId)
            if (entry != null) {
                db.deleteEntry(entry)
                ActionRunnable.Result(isSuccess = true)
            } else {
                ActionRunnable.Result(isSuccess = false, message = "Entry not found")
            }
        }
        return if (actionResult.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(actionResult.exception ?: RuntimeException(actionResult.message ?: "Failed to delete entry"))
        }
    }

    override suspend fun addGroup(groupInfo: GroupInfo, parentGroupId: GroupId): Result<GroupId> {
        val db = database ?: return Result.failure(IllegalStateException("Vault is locked"))
        val actionResult = jobManagerProvider().executeJob(
            database = db,
            actionTask = "ADD_GROUP",
            isSavingAction = true
        ) { _ ->
            val parent = db.getGroupById(parentGroupId) ?: db.rootGroup
            val newGroup = db.createGroup(groupInfo)
            if (newGroup != null && parent != null) {
                newGroup.touch(true, true)
                parent.touch(true, true)
                db.addGroupTo(newGroup, parent)
                ActionRunnable.Result(isSuccess = true)
            } else {
                ActionRunnable.Result(isSuccess = false, message = "Could not create group")
            }
        }
        return if (actionResult.isSuccess) {
            Result.success(groupInfo.nodeId)
        } else {
            Result.failure(actionResult.exception ?: RuntimeException(actionResult.message ?: "Failed to add group"))
        }
    }

    override suspend fun updateGroup(groupInfo: GroupInfo): Result<Unit> {
        val db = database ?: return Result.failure(IllegalStateException("Vault is locked"))
        val actionResult = jobManagerProvider().executeJob(
            database = db,
            actionTask = "UPDATE_GROUP",
            isSavingAction = true
        ) { _ ->
            val oldGroup = db.getGroupById(groupInfo.nodeId)
            if (oldGroup != null) {
                db.updateGroup(oldGroup, groupInfo)
                oldGroup.touch(true, true)
                ActionRunnable.Result(isSuccess = true)
            } else {
                ActionRunnable.Result(isSuccess = false, message = "Group not found")
            }
        }
        return if (actionResult.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(actionResult.exception ?: RuntimeException(actionResult.message ?: "Failed to update group"))
        }
    }

    override suspend fun deleteGroup(groupId: GroupId): Result<Unit> {
        val db = database ?: return Result.failure(IllegalStateException("Vault is locked"))
        val actionResult = jobManagerProvider().executeJob(
            database = db,
            actionTask = "DELETE_GROUP",
            isSavingAction = true
        ) { _ ->
            val group = db.getGroupById(groupId)
            if (group != null) {
                db.deleteGroup(group)
                ActionRunnable.Result(isSuccess = true)
            } else {
                ActionRunnable.Result(isSuccess = false, message = "Group not found")
            }
        }
        return if (actionResult.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(actionResult.exception ?: RuntimeException(actionResult.message ?: "Failed to delete group"))
        }
    }

    companion object {
        @Volatile
        private var instance: VaultRepository? = null

        fun getInstance(): VaultRepository {
            return instance ?: synchronized(this) {
                instance ?: VaultRepositoryImpl().also { instance = it }
            }
        }

        fun setInstanceForTest(repository: VaultRepository?) {
            instance = repository
        }
    }
}
