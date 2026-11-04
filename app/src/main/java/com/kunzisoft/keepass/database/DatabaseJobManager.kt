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

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.StringRes
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.hardware.HardwareKey
import com.kunzisoft.keepass.model.SnapFileDatabaseInfo
import com.kunzisoft.keepass.tasks.ActionRunnable
import com.kunzisoft.keepass.tasks.ProgressTaskUpdater
import com.kunzisoft.keepass.timeout.TimeoutHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Domain component managing database asynchronous background tasks,
 * task queue tracking, snapshot metadata tracking, task deduplication,
 * and progress dispatching via Kotlin StateFlow and SharedFlow.
 */
class DatabaseJobManager(
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : ProgressTaskUpdater {

    sealed class VaultSessionState {
        data object Locked : VaultSessionState()
        data class Unlocked(
            val database: ContextualDatabase,
            val sessionVersion: Long = System.nanoTime()
        ) : VaultSessionState()
    }

    sealed class DatabaseJobState {
        object Idle : DatabaseJobState()
        data class Running(
            val actionTask: String,
            val isSavingAction: Boolean,
            val progressMessage: ProgressMessage
        ) : DatabaseJobState()
    }

    sealed class DatabaseJobEvent {
        data class Started(
            val database: ContextualDatabase?,
            val actionTask: String,
            val progressMessage: ProgressMessage,
            val isSavingAction: Boolean
        ) : DatabaseJobEvent()

        data class ProgressUpdated(
            val database: ContextualDatabase?,
            val progressMessage: ProgressMessage
        ) : DatabaseJobEvent()

        data class Finished(
            val database: ContextualDatabase?,
            val actionTask: String,
            val result: ActionRunnable.Result,
            val isSavingAction: Boolean
        ) : DatabaseJobEvent()

        data class Stopped(
            val database: ContextualDatabase?
        ) : DatabaseJobEvent()

        data class InfoChanged(
            val previousDatabaseInfo: SnapFileDatabaseInfo?,
            val newDatabaseInfo: SnapFileDatabaseInfo,
            val readOnlyDatabase: Boolean
        ) : DatabaseJobEvent()
    }

    interface DatabaseListener {
        fun onDatabaseRetrieved(database: ContextualDatabase?)
    }

    interface DatabaseInfoListener {
        fun onDatabaseInfoChanged(
            previousDatabaseInfo: SnapFileDatabaseInfo,
            newDatabaseInfo: SnapFileDatabaseInfo,
            readOnlyDatabase: Boolean
        )
    }

    interface ActionTaskListener {
        fun onActionStarted(
            database: ContextualDatabase,
            progressMessage: ProgressMessage
        )
        fun onActionUpdated(
            database: ContextualDatabase,
            progressMessage: ProgressMessage
        )
        fun onActionStopped(
            database: ContextualDatabase? = null
        )
        fun onActionFinished(
            database: ContextualDatabase,
            actionTask: String,
            result: ActionRunnable.Result
        )
    }

    // State & Flow emitters
    private val _jobState = MutableStateFlow<DatabaseJobState>(DatabaseJobState.Idle)
    val jobState: StateFlow<DatabaseJobState> = _jobState.asStateFlow()

    private val _jobEvents = MutableSharedFlow<DatabaseJobEvent>(replay = 0, extraBufferCapacity = 64)
    val jobEvents: SharedFlow<DatabaseJobEvent> = _jobEvents.asSharedFlow()

    fun launchJob(block: suspend CoroutineScope.() -> Unit) {
        coroutineScope.launch(block = block)
    }

    private val _databaseState = MutableStateFlow<ContextualDatabase?>(null)
    val databaseState: StateFlow<ContextualDatabase?> = _databaseState.asStateFlow()

    private val _sessionState = MutableStateFlow<VaultSessionState>(VaultSessionState.Locked)
    val sessionState: StateFlow<VaultSessionState> = _sessionState.asStateFlow()

    private val _activeJobsCount = MutableStateFlow(0)
    val activeJobsCount: StateFlow<Int> = _activeJobsCount.asStateFlow()

    private val _savingJobsCount = MutableStateFlow(0)
    val savingJobsCount: StateFlow<Int> = _savingJobsCount.asStateFlow()

    private val _isSavingState = MutableStateFlow(false)
    val isSavingState: StateFlow<Boolean> = _isSavingState.asStateFlow()

    private var mProgressMessage: ProgressMessage = ProgressMessage(R.string.database_opened)
    private val _progressMessageFlow = MutableStateFlow(mProgressMessage)
    val progressMessageFlow: StateFlow<ProgressMessage> = _progressMessageFlow.asStateFlow()

    var currentDatabase: ContextualDatabase?
        get() = _databaseState.value
        set(value) {
            val loadedDb = value?.takeIf { it.loaded }
            _databaseState.value = loadedDb
            val previousState = _sessionState.value
            _sessionState.value = if (loadedDb != null) {
                if (previousState is VaultSessionState.Unlocked && previousState.database === loadedDb) {
                    previousState
                } else {
                    VaultSessionState.Unlocked(loadedDb)
                }
            } else {
                VaultSessionState.Locked
            }
        }

    fun refreshSessionState(database: ContextualDatabase? = currentDatabase) {
        val loadedDb = database?.takeIf { it.loaded }
        currentDatabase = loadedDb
        databaseListeners.forEach { it.onDatabaseRetrieved(loadedDb) }
    }

    // Challenge-response launcher hook
    var challengeResponseRequester: ((context: Context, hardwareKey: HardwareKey, seed: ByteArray?) -> Unit)? = null

    // Backward-compatible listeners
    private val databaseListeners = CopyOnWriteArrayList<DatabaseListener>()
    private val databaseInfoListeners = CopyOnWriteArrayList<DatabaseInfoListener>()
    private val actionTaskListeners = CopyOnWriteArrayList<ActionTaskListener>()

    // Task deduplication map: deduplicationKey -> Deferred<ActionRunnable.Result>
    private val activeJobDeferreds = ConcurrentHashMap<String, Deferred<ActionRunnable.Result>>()

    // Concurrency control: serialize save/write operations to prevent disk data corruption
    private val saveMutex = Mutex()

    // Concurrency control: track active main jobs to prevent premature timeout re-enabling
    private val activeMainJobsCount = AtomicInteger(0)

    // Task queue / active task identifiers
    private val activeJobKeys = CopyOnWriteArrayList<String>()

    fun getActiveJobKeys(): List<String> = activeJobKeys.toList()

    // Challenge-response channel
    private var responseChallengeChannel: Channel<ByteArray?>? = null

    fun addDatabaseListener(listener: DatabaseListener) {
        if (!databaseListeners.contains(listener)) {
            databaseListeners.add(listener)
        }
    }

    fun removeDatabaseListener(listener: DatabaseListener) {
        databaseListeners.remove(listener)
    }

    fun addDatabaseInfoListener(listener: DatabaseInfoListener) {
        if (!databaseInfoListeners.contains(listener)) {
            databaseInfoListeners.add(listener)
        }
    }

    fun removeDatabaseInfoListener(listener: DatabaseInfoListener) {
        databaseInfoListeners.remove(listener)
    }

    fun addActionTaskListener(listener: ActionTaskListener) {
        if (!actionTaskListeners.contains(listener)) {
            actionTaskListeners.add(listener)
        }
    }

    fun removeActionTaskListener(listener: ActionTaskListener) {
        actionTaskListeners.remove(listener)
    }

    fun checkDatabase(database: ContextualDatabase? = currentDatabase) {
        refreshSessionState(database)
    }

    /**
     * Pure evaluator for external file modification detection with a 15s in-app save grace window
     * and a 10s modification debounce threshold.
     */
    fun evaluateExternalModification(
        isSaving: Boolean,
        lastInAppSaveTimeMillis: Long,
        currentTimeMillis: Long,
        oldModification: Long?,
        newModification: Long?,
        oldSize: Long?,
        newSize: Long?,
        existsChanged: Boolean
    ): Boolean {
        if (isSaving) return false
        if (existsChanged) return true
        if (lastInAppSaveTimeMillis > 0L) {
            val deltaNow = currentTimeMillis - lastInAppSaveTimeMillis
            val deltaMod = if (newModification != null) kotlin.math.abs(newModification - lastInAppSaveTimeMillis) else Long.MAX_VALUE
            if (deltaNow < 15000L || deltaMod < 15000L) {
                return false
            }
        }
        if (oldModification != null && newModification != null && oldSize != null && newSize != null) {
            if (oldModification > 0 && newModification > 0 && oldSize > 0 && oldModification + 10000 < newModification) {
                return true
            }
        }
        return false
    }

    /**
     * Checks whether the underlying database file was modified externally while avoiding
     * false positive triggers during and immediately following in-app saves (15s grace window).
     */
    fun checkDatabaseInfo(context: Context, database: ContextualDatabase? = currentDatabase): Boolean {
        try {
            if (_isSavingState.value || _savingJobsCount.value > 0) {
                return false
            }
            val db = database ?: return false
            val fileUri = db.fileUri ?: return false
            val previousDatabaseInfo = db.snapFileDatabaseInfo
            db.saveDatabaseInfo(context)
            val lastFileDatabaseInfo = db.snapFileDatabaseInfo ?: return false

            val now = System.currentTimeMillis()
            val existsChanged = previousDatabaseInfo != null
                    && previousDatabaseInfo.exists != lastFileDatabaseInfo.exists

            val shouldNotify = evaluateExternalModification(
                isSaving = _isSavingState.value,
                lastInAppSaveTimeMillis = db.lastInAppSaveTimeMillis,
                currentTimeMillis = now,
                oldModification = previousDatabaseInfo?.lastModification,
                newModification = lastFileDatabaseInfo.lastModification,
                oldSize = previousDatabaseInfo?.size,
                newSize = lastFileDatabaseInfo.size,
                existsChanged = existsChanged
            )

            if (shouldNotify) {
                db.indicateNotSavedData()
                try {
                    Log.i(TAG, "Database file modified $previousDatabaseInfo != $lastFileDatabaseInfo")
                } catch (_: Throwable) {}

                if (!_isSavingState.value) {
                    _jobEvents.tryEmit(
                        DatabaseJobEvent.InfoChanged(
                            previousDatabaseInfo,
                            lastFileDatabaseInfo,
                            db.isReadOnly
                        )
                    )
                    databaseInfoListeners.forEach { listener ->
                        listener.onDatabaseInfoChanged(
                            previousDatabaseInfo ?: lastFileDatabaseInfo,
                            lastFileDatabaseInfo,
                            db.isReadOnly
                        )
                    }
                }
                return true
            }
        } catch (e: Exception) {
            try {
                Log.e(TAG, "Unable to check database info", e)
            } catch (_: Throwable) {}
        }
        return false
    }

    fun saveDatabaseInfo(context: Context, database: ContextualDatabase? = currentDatabase) {
        database?.recordInAppSaveCompleted(context)
    }

    fun checkAction() {
        val database = currentDatabase ?: ContextualDatabase.getInstance()
        if (_activeJobsCount.value > 0) {
            actionTaskListeners.forEach { listener ->
                listener.onActionStarted(database, mProgressMessage)
            }
        }
    }

    /**
     * Executes an [ActionRunnable] within coroutine structured concurrency on [Dispatchers.IO],
     * managing job counting, timeout pauses, flow transitions, deduplication, and listener callbacks.
     */
    suspend fun executeAction(
        database: ContextualDatabase?,
        actionTask: String,
        isSavingAction: Boolean,
        isMainAction: Boolean = true,
        deduplicate: Boolean = false,
        deduplicationKey: String = actionTask,
        actionRunnable: ActionRunnable
    ): ActionRunnable.Result {
        return executeJob(
            database = database,
            actionTask = actionTask,
            isSavingAction = isSavingAction,
            isMainAction = isMainAction,
            deduplicate = deduplicate,
            deduplicationKey = deduplicationKey
        ) {
            actionRunnable.execute()
        }
    }

    /**
     * General coroutine-based job execution function with lifecycle state dispatching,
     * save serialization (via Mutex), task deduplication, and atomic timeout handling.
     */
    suspend fun executeJob(
        database: ContextualDatabase?,
        actionTask: String,
        isSavingAction: Boolean,
        isMainAction: Boolean = true,
        deduplicate: Boolean = false,
        deduplicationKey: String = actionTask,
        taskBlock: suspend (ProgressTaskUpdater) -> ActionRunnable.Result
    ): ActionRunnable.Result {
        if (deduplicate) {
            val existing = activeJobDeferreds[deduplicationKey]
            if (existing != null) {
                return existing.await()
            }
        }

        val deferred = CompletableDeferred<ActionRunnable.Result>()
        if (deduplicate) {
            val prev = activeJobDeferreds.putIfAbsent(deduplicationKey, deferred)
            if (prev != null) {
                return prev.await()
            }
        }

        _activeJobsCount.value++
        activeJobKeys.add(actionTask)
        if (isSavingAction) {
            _savingJobsCount.value++
        }
        _isSavingState.value = _savingJobsCount.value > 0

        try {
            if (database != null && database.loaded) {
                currentDatabase = database
            }

            if (isMainAction) {
                if (activeMainJobsCount.getAndIncrement() == 0) {
                    try {
                        TimeoutHelper.temporarilyDisableTimeout()
                    } catch (_: Throwable) {}
                }

                _jobState.value = DatabaseJobState.Running(actionTask, isSavingAction, mProgressMessage)
                val startedEvent = DatabaseJobEvent.Started(database, actionTask, mProgressMessage, isSavingAction)
                _jobEvents.emit(startedEvent)
                if (database != null) {
                    actionTaskListeners.forEach { listener ->
                        listener.onActionStarted(database, mProgressMessage)
                    }
                }
            }

            val result: ActionRunnable.Result = try {
                if (isSavingAction) {
                    saveMutex.withLock {
                        taskBlock(this)
                    }
                } else {
                    taskBlock(this)
                }
            } catch (c: CancellationException) {
                deferred.cancel(c)
                throw c
            } catch (e: Exception) {
                try {
                    Log.e(TAG, "Error executing job for action $actionTask", e)
                } catch (_: Throwable) {}
                ActionRunnable.Result(isSuccess = false, message = e.message)
            }

            if (result.isSuccess && isSavingAction) {
                database?.indicateUpToDateData()
            }

            if (database != null) {
                refreshSessionState(database)
            }

            deferred.complete(result)

            if (isMainAction) {
                try {
                    if (result.data == null) {
                        result.data = Bundle()
                    }
                    result.data?.putBoolean(SAVE_DATABASE_KEY, isSavingAction)
                } catch (_: Throwable) {}

                val finishedEvent = DatabaseJobEvent.Finished(database, actionTask, result, isSavingAction)
                _jobEvents.emit(finishedEvent)
                if (database != null) {
                    actionTaskListeners.forEach { listener ->
                        listener.onActionFinished(database, actionTask, result)
                    }
                }
            }

            return result
        } finally {
            if (deduplicate) {
                activeJobDeferreds.remove(deduplicationKey)
            }
            activeJobKeys.remove(actionTask)
            if (isMainAction) {
                if (isSavingAction) {
                    _savingJobsCount.value = (_savingJobsCount.value - 1).coerceAtLeast(0)
                }
                _isSavingState.value = _savingJobsCount.value > 0
                if (activeMainJobsCount.decrementAndGet() <= 0) {
                    activeMainJobsCount.set(0)
                    try {
                        TimeoutHelper.releaseTemporarilyDisableTimeout()
                    } catch (_: Throwable) {}
                }
            }
            _activeJobsCount.value = (_activeJobsCount.value - 1).coerceAtLeast(0)
            if (_activeJobsCount.value == 0) {
                _jobState.value = DatabaseJobState.Idle
                val stoppedEvent = DatabaseJobEvent.Stopped(database)
                _jobEvents.emit(stoppedEvent)
                actionTaskListeners.forEach { listener ->
                    listener.onActionStopped(database)
                }
            }
        }
    }

    // ProgressTaskUpdater implementation
    override fun retrievingDatabaseKey() {
        updateMessage(R.string.retrieving_db_key)
    }

    override fun decryptingDatabase() {
        updateMessage(R.string.decrypting_db)
    }

    override fun benchmarking() {
        updateMessage(R.string.benchmarking)
    }

    fun updateMessage(@StringRes resId: Int) {
        mProgressMessage = mProgressMessage.copy(messageId = resId)
        _progressMessageFlow.value = mProgressMessage
        val db = currentDatabase
        val event = DatabaseJobEvent.ProgressUpdated(db, mProgressMessage)
        _jobEvents.tryEmit(event)
        if (db != null) {
            actionTaskListeners.forEach { listener ->
                listener.onActionUpdated(db, mProgressMessage)
            }
        }
    }

    // Hardware Challenge-Response Channel Handling
    fun initializeChallengeResponse() {
        if (responseChallengeChannel == null) {
            responseChallengeChannel = Channel(0)
        }
    }

    fun closeChallengeResponse() {
        responseChallengeChannel?.close()
        responseChallengeChannel = null
    }

    fun cancelChallengeResponse(reason: String = "Cancelled by user") {
        responseChallengeChannel?.cancel(CancellationException(reason))
        responseChallengeChannel = null
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun sendResponseToChallenge(response: ByteArray) {
        coroutineScope.launch {
            val channel = responseChallengeChannel
            if (channel == null || channel.isEmpty) {
                if (response.isEmpty()) {
                    cancelChallengeResponse("No response from challenge")
                } else {
                    channel?.send(response)
                }
            } else {
                cancelChallengeResponse("Response already provided")
            }
        }
    }

    fun retrieveResponseFromChallenge(
        context: Context,
        hardwareKey: HardwareKey,
        seed: ByteArray?
    ): ByteArray {
        var response: ByteArray
        runBlocking {
            initializeChallengeResponse()
            val previousMessage = mProgressMessage.copy()
            mProgressMessage = mProgressMessage.copy(
                messageId = R.string.waiting_challenge_request,
                cancelable = {
                    cancelChallengeResponse("Cancelled by user")
                }
            )
            updateMessage(R.string.waiting_challenge_request)

            challengeResponseRequester?.invoke(context, hardwareKey, seed)

            updateMessage(R.string.waiting_challenge_response)
            response = responseChallengeChannel?.receive() ?: byteArrayOf()
            closeChallengeResponse()

            mProgressMessage = previousMessage.copy(cancelable = null)
            _progressMessageFlow.value = mProgressMessage
        }
        return response
    }

    companion object {
        private const val TAG = "DatabaseJobManager"

        const val SAVE_DATABASE_KEY = "SAVE_DATABASE_KEY"

        @Volatile
        private var instance: DatabaseJobManager? = null

        fun getInstance(): DatabaseJobManager {
            return instance ?: synchronized(this) {
                instance ?: DatabaseJobManager().also { instance = it }
            }
        }

        fun setInstanceForTest(testInstance: DatabaseJobManager?) {
            instance = testInstance
        }
    }
}
