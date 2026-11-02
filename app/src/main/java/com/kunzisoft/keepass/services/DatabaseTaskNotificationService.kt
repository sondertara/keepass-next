/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
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
package com.kunzisoft.keepass.services

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import androidx.media.app.NotificationCompat
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.FileDatabaseSelectActivity
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.DatabaseJobManager
import com.kunzisoft.keepass.database.DatabaseJobManager.DatabaseJobEvent
import com.kunzisoft.keepass.database.ProgressMessage
import com.kunzisoft.keepass.database.element.EntryId
import com.kunzisoft.keepass.database.element.GroupId
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.GroupInfo
import com.kunzisoft.keepass.model.SnapFileDatabaseInfo
import com.kunzisoft.keepass.tasks.ActionRunnable
import com.kunzisoft.keepass.tasks.ProgressTaskUpdater
import com.kunzisoft.keepass.timeout.TimeoutHelper
import com.kunzisoft.keepass.utils.DATABASE_START_TASK_ACTION
import com.kunzisoft.keepass.utils.DATABASE_STOP_TASK_ACTION
import com.kunzisoft.keepass.utils.LOCK_ACTION
import com.kunzisoft.keepass.utils.closeDatabase
import com.kunzisoft.keepass.utils.getParcelableCompat
import com.kunzisoft.keepass.utils.getParcelableList
import com.kunzisoft.keepass.utils.getSerializableCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Serializable

/**
 * Focused foreground notification service for database tasks.
 * Strictly observes [DatabaseJobManager] events and manages foreground notifications
 * and lifecycle, rather than executing raw business IO logic.
 */
open class DatabaseTaskNotificationService : LockNotificationService(), ProgressTaskUpdater {

    override val notificationId: Int = 575

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val actionTaskBinder = ActionTaskBinder()

    private var taskRemovedRequested = false

    override fun retrieveChannelId(): String = CHANNEL_DATABASE_ID
    override fun retrieveChannelName(): String = getString(R.string.database)

    inner class ActionTaskBinder : Binder() {
        fun getService(): DatabaseTaskNotificationService = this@DatabaseTaskNotificationService

        fun addDatabaseListener(databaseListener: DatabaseJobManager.DatabaseListener) {
            DatabaseJobManager.getInstance().addDatabaseListener(databaseListener)
        }

        fun removeDatabaseListener(databaseListener: DatabaseJobManager.DatabaseListener) {
            DatabaseJobManager.getInstance().removeDatabaseListener(databaseListener)
        }

        fun addDatabaseFileInfoListener(databaseInfoListener: DatabaseJobManager.DatabaseInfoListener) {
            DatabaseJobManager.getInstance().addDatabaseInfoListener(databaseInfoListener)
        }

        fun removeDatabaseFileInfoListener(databaseInfoListener: DatabaseJobManager.DatabaseInfoListener) {
            DatabaseJobManager.getInstance().removeDatabaseInfoListener(databaseInfoListener)
        }

        fun addActionTaskListener(actionTaskListener: DatabaseJobManager.ActionTaskListener) {
            DatabaseJobManager.getInstance().addActionTaskListener(actionTaskListener)
        }

        fun removeActionTaskListener(actionTaskListener: DatabaseJobManager.ActionTaskListener) {
            DatabaseJobManager.getInstance().removeActionTaskListener(actionTaskListener)
        }
    }

    override fun onCreate() {
        super.onCreate()
        DatabaseJobManager.getInstance().challengeResponseRequester = { context, key, seed ->
            com.kunzisoft.keepass.credentialprovider.activity.HardwareKeyActivity.launchHardwareKeyActivity(context, key, seed)
        }
        observeJobEvents()
    }

    override fun onDestroy() {
        mainScope.cancel()
        DatabaseJobManager.getInstance().challengeResponseRequester = null
        super.onDestroy()
    }

    private fun observeJobEvents() {
        mainScope.launch {
            DatabaseJobManager.getInstance().jobEvents.collect { event ->
                when (event) {
                    is DatabaseJobEvent.Started -> {
                        sendBroadcast(Intent(DATABASE_START_TASK_ACTION).apply {
                            putExtra(DATABASE_TASK_TITLE_KEY, event.progressMessage.titleId)
                            putExtra(DATABASE_TASK_MESSAGE_KEY, event.progressMessage.messageId)
                            putExtra(DATABASE_TASK_WARNING_KEY, event.progressMessage.warningId)
                        })
                        buildNotification(event.actionTask, event.isSavingAction, event.progressMessage)
                    }
                    is DatabaseJobEvent.ProgressUpdated -> {
                        buildNotification(null, DatabaseJobManager.getInstance().isSavingState.value, event.progressMessage)
                    }
                    is DatabaseJobEvent.Finished -> {
                        sendBroadcast(Intent(DATABASE_STOP_TASK_ACTION))
                        if (event.result.isSuccess && (event.isSavingAction || event.actionTask in listOf(
                            ACTION_DATABASE_CREATE_TASK,
                            ACTION_DATABASE_LOAD_TASK,
                            ACTION_DATABASE_MERGE_TASK,
                            ACTION_DATABASE_RELOAD_TASK,
                            ACTION_DATABASE_ASSIGN_CREDENTIAL_TASK
                        ))) {
                            saveDatabaseInfo()
                        }
                        handleJobFinished(event.isSavingAction)
                    }
                    is DatabaseJobEvent.Stopped -> {
                        buildNotification(null, false, DatabaseJobManager.getInstance().progressMessageFlow.value)
                    }
                    is DatabaseJobEvent.InfoChanged -> {
                        // Handled by listeners
                    }
                }
            }
        }
    }

    private fun handleJobFinished(isSavingAction: Boolean) {
        val database = ContextualDatabase.getInstance()
        if (isSavingAction && taskRemovedRequested) {
            stopService()
        } else if (TimeoutHelper.checkTimeAndLockIfTimeout(this@DatabaseTaskNotificationService)) {
            if (!database.loaded) {
                stopService()
            } else {
                try {
                    startService(Intent(applicationContext, DatabaseTaskNotificationService::class.java))
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Cannot restart database task service", e)
                }
            }
        }
        taskRemovedRequested = false
    }

    fun checkDatabase() {
        DatabaseJobManager.getInstance().checkDatabase(DatabaseJobManager.getInstance().currentDatabase)
    }

    fun checkDatabaseInfo() {
        DatabaseJobManager.getInstance().checkDatabaseInfo(applicationContext)
    }

    fun saveDatabaseInfo() {
        DatabaseJobManager.getInstance().saveDatabaseInfo(applicationContext)
    }

    fun checkAction() {
        DatabaseJobManager.getInstance().checkAction()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return actionTaskBinder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val database = ContextualDatabase.getInstance()
        val intentAction = intent?.action
        val isSavingAction = if (intent != null && !database.isReadOnly) {
            if (intent.hasExtra(SAVE_DATABASE_KEY)) {
                intent.getBooleanExtra(SAVE_DATABASE_KEY, false)
            } else (intentAction == ACTION_DATABASE_CREATE_TASK
                    || intentAction == ACTION_DATABASE_ASSIGN_CREDENTIAL_TASK
                    || intentAction == ACTION_DATABASE_SAVE)
        } else false

        buildNotification(intentAction, isSavingAction, DatabaseJobManager.getInstance().progressMessageFlow.value)

        if (intentAction == null && !database.loaded) {
            stopService()
        } else if (database.loaded) {
            DatabaseJobManager.getInstance().currentDatabase = database
        }

        val actionRunnable: ActionRunnable? = if (intent != null) {
            DatabaseActionBuilder.buildAction(
                context = this,
                intent = intent,
                database = database,
                progressTaskUpdater = DatabaseJobManager.getInstance(),
                challengeResponseRetriever = { hardwareKey, seed ->
                    DatabaseJobManager.getInstance().retrieveResponseFromChallenge(this, hardwareKey, seed)
                }
            )
        } else null

        val isMainAction = intentAction != ACTION_CHALLENGE_RESPONDED

        if (actionRunnable != null) {
            if (database.loaded) {
                DatabaseJobManager.getInstance().currentDatabase = database
            }
            mainScope.launch {
                try {
                    DatabaseJobManager.getInstance().executeAction(
                        database = database,
                        actionTask = intentAction ?: "",
                        isSavingAction = isSavingAction,
                        isMainAction = isMainAction,
                        actionRunnable = actionRunnable
                    )
                } finally {
                    removeIntentData(intent)
                }
            }
        }

        return when (intentAction) {
            ACTION_DATABASE_LOAD_TASK,
            ACTION_DATABASE_MERGE_TASK,
            ACTION_DATABASE_RELOAD_TASK,
            null -> START_STICKY
            else -> START_REDELIVER_INTENT
        }
    }

    private fun buildNotification(
        intentAction: String?,
        isSavingAction: Boolean,
        progressMessage: ProgressMessage
    ) {
        val database = ContextualDatabase.getInstance()
        val iconId = if (intentAction == null && DatabaseJobManager.getInstance().activeJobsCount.value == 0)
            R.drawable.notification_ic_database_open
        else
            R.drawable.notification_ic_database_action

        val titleId = if (intentAction == null) {
            if (DatabaseJobManager.getInstance().activeJobsCount.value > 0) {
                if (isSavingAction) R.string.saving_database else R.string.command_execution
            } else {
                R.string.database_opened
            }
        } else when (intentAction) {
            ACTION_DATABASE_CREATE_TASK -> R.string.creating_database
            ACTION_DATABASE_LOAD_TASK,
            ACTION_DATABASE_MERGE_TASK,
            ACTION_DATABASE_RELOAD_TASK -> R.string.loading_database
            ACTION_DATABASE_ASSIGN_CREDENTIAL_TASK,
            ACTION_DATABASE_SAVE -> R.string.saving_database
            else -> if (isSavingAction) R.string.saving_database else R.string.command_execution
        }

        val notificationBuilder = buildNewNotification().apply {
            setSmallIcon(iconId)
            setContentTitle(getString(titleId))
            setAutoCancel(false)
            setContentIntent(null)
        }

        if (intentAction == null && DatabaseJobManager.getInstance().activeJobsCount.value == 0) {
            if (database.loaded) {
                val pendingDatabaseIntent = buildActivityPendingIntent(
                    Intent(this, FileDatabaseSelectActivity::class.java)
                )
                val pendingDeleteIntent = PendingIntent.getBroadcast(
                    this,
                    4576,
                    Intent(LOCK_ACTION),
                    PendingIntent.FLAG_IMMUTABLE
                )
                notificationBuilder.apply {
                    setContentText("${database.name} (${database.version})")
                    setContentIntent(pendingDatabaseIntent)
                    setDeleteIntent(pendingDeleteIntent)
                    addAction(
                        R.drawable.ic_lock_database_white_32dp,
                        getString(R.string.lock),
                        pendingDeleteIntent
                    )
                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
                        setStyle(
                            NotificationCompat.MediaStyle()
                                .setShowActionsInCompactView(0)
                        )
                    }
                }
            }
        } else {
            progressMessage.messageId?.let { msgId ->
                notificationBuilder.setContentText(getString(msgId))
            }
        }

        startForegroundCompat(
            notificationId,
            notificationBuilder,
            NotificationServiceType.DATABASE_TASK
        )
    }

    override fun stopService() {
        if (!TimeoutHelper.temporarilyDisableLock) {
            val database = ContextualDatabase.getInstance()
            closeDatabase(database)
            DatabaseJobManager.getInstance().checkDatabase(null)
            TimeoutHelper.cancelLockTimer(this)
            super.stopService()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (TimeoutHelper.temporarilyDisableLock) {
            taskRemovedRequested = true
        }
        super.onTaskRemoved(rootIntent)
    }

    // Forward ProgressTaskUpdater to DatabaseJobManager
    override fun retrievingDatabaseKey() {
        DatabaseJobManager.getInstance().retrievingDatabaseKey()
    }

    override fun decryptingDatabase() {
        DatabaseJobManager.getInstance().decryptingDatabase()
    }

    override fun benchmarking() {
        DatabaseJobManager.getInstance().benchmarking()
    }

    private fun removeIntentData(intent: Intent?) {
        intent?.action = null

        intent?.removeExtra(DATABASE_TASK_TITLE_KEY)
        intent?.removeExtra(DATABASE_TASK_MESSAGE_KEY)
        intent?.removeExtra(DATABASE_TASK_WARNING_KEY)

        intent?.removeExtra(DATABASE_URI_KEY)
        intent?.removeExtra(MAIN_CREDENTIAL_KEY)
        intent?.removeExtra(READ_ONLY_KEY)
        intent?.removeExtra(USER_VERIFICATION_KEY)
        intent?.removeExtra(CIPHER_DATABASE_KEY)
        intent?.removeExtra(FIX_DUPLICATE_UUID_KEY)
        intent?.removeExtra(GROUP_KEY)
        intent?.removeExtra(ENTRY_KEY)
        intent?.removeExtra(GROUP_ID_KEY)
        intent?.removeExtra(ENTRY_ID_KEY)
        intent?.removeExtra(GROUPS_ID_KEY)
        intent?.removeExtra(ENTRIES_ID_KEY)
        intent?.removeExtra(PARENT_ID_KEY)
        intent?.removeExtra(ENTRY_HISTORY_POSITION_KEY)
        intent?.removeExtra(SAVE_DATABASE_KEY)
        intent?.removeExtra(OLD_NODES_KEY)
        intent?.removeExtra(NEW_NODES_KEY)
        intent?.removeExtra(OLD_ELEMENT_KEY)
        intent?.removeExtra(NEW_ELEMENT_KEY)
    }

    companion object {
        private val TAG = DatabaseTaskNotificationService::class.java.name

        private const val CHANNEL_DATABASE_ID = "com.kunzisoft.keepass.notification.channel.database"

        const val ACTION_DATABASE_CREATE_TASK = "ACTION_DATABASE_CREATE_TASK"
        const val ACTION_DATABASE_LOAD_TASK = "ACTION_DATABASE_LOAD_TASK"
        const val ACTION_DATABASE_MERGE_TASK = "ACTION_DATABASE_MERGE_TASK"
        const val ACTION_DATABASE_RELOAD_TASK = "ACTION_DATABASE_RELOAD_TASK"
        const val ACTION_DATABASE_ASSIGN_CREDENTIAL_TASK = "ACTION_DATABASE_ASSIGN_CREDENTIAL_TASK"
        const val ACTION_DATABASE_CREATE_GROUP_TASK = "ACTION_DATABASE_CREATE_GROUP_TASK"
        const val ACTION_DATABASE_UPDATE_GROUP_TASK = "ACTION_DATABASE_UPDATE_GROUP_TASK"
        const val ACTION_DATABASE_TOUCH_GROUP_TASK = "ACTION_DATABASE_TOUCH_GROUP_TASK"
        const val ACTION_DATABASE_CREATE_ENTRY_TASK = "ACTION_DATABASE_CREATE_ENTRY_TASK"
        const val ACTION_DATABASE_UPDATE_ENTRY_TASK = "ACTION_DATABASE_UPDATE_ENTRY_TASK"
        const val ACTION_DATABASE_TOUCH_ENTRY_TASK = "ACTION_DATABASE_TOUCH_ENTRY_TASK"
        const val ACTION_DATABASE_COPY_NODES_TASK = "ACTION_DATABASE_COPY_NODES_TASK"
        const val ACTION_DATABASE_MOVE_NODES_TASK = "ACTION_DATABASE_MOVE_NODES_TASK"
        const val ACTION_DATABASE_DELETE_NODES_TASK = "ACTION_DATABASE_DELETE_NODES_TASK"
        const val ACTION_DATABASE_RESTORE_ENTRY_HISTORY = "ACTION_DATABASE_RESTORE_ENTRY_HISTORY"
        const val ACTION_DATABASE_DELETE_ENTRY_HISTORY = "ACTION_DATABASE_DELETE_ENTRY_HISTORY"
        const val ACTION_DATABASE_UPDATE_NAME_TASK = "ACTION_DATABASE_UPDATE_NAME_TASK"
        const val ACTION_DATABASE_UPDATE_DESCRIPTION_TASK = "ACTION_DATABASE_UPDATE_DESCRIPTION_TASK"
        const val ACTION_DATABASE_UPDATE_DEFAULT_USERNAME_TASK = "ACTION_DATABASE_UPDATE_DEFAULT_USERNAME_TASK"
        const val ACTION_DATABASE_UPDATE_COLOR_TASK = "ACTION_DATABASE_UPDATE_COLOR_TASK"
        const val ACTION_DATABASE_UPDATE_COMPRESSION_TASK = "ACTION_DATABASE_UPDATE_COMPRESSION_TASK"
        const val ACTION_DATABASE_REMOVE_UNLINKED_DATA_TASK = "ACTION_DATABASE_REMOVE_UNLINKED_DATA_TASK"
        const val ACTION_DATABASE_UPDATE_RECYCLE_BIN_TASK = "ACTION_DATABASE_UPDATE_RECYCLE_BIN_TASK"
        const val ACTION_DATABASE_UPDATE_TEMPLATES_GROUP_TASK = "ACTION_DATABASE_UPDATE_TEMPLATES_GROUP_TASK"
        const val ACTION_DATABASE_UPDATE_MAX_HISTORY_ITEMS_TASK = "ACTION_DATABASE_UPDATE_MAX_HISTORY_ITEMS_TASK"
        const val ACTION_DATABASE_UPDATE_MAX_HISTORY_SIZE_TASK = "ACTION_DATABASE_UPDATE_MAX_HISTORY_SIZE_TASK"
        const val ACTION_DATABASE_UPDATE_ENCRYPTION_TASK = "ACTION_DATABASE_UPDATE_ENCRYPTION_TASK"
        const val ACTION_DATABASE_UPDATE_MEMORY_USAGE_TASK = "ACTION_DATABASE_UPDATE_MEMORY_USAGE_TASK"
        const val ACTION_DATABASE_UPDATE_PARALLELISM_TASK = "ACTION_DATABASE_UPDATE_PARALLELISM_TASK"
        const val ACTION_DATABASE_UPDATE_ITERATIONS_TASK = "ACTION_DATABASE_UPDATE_ITERATIONS_TASK"
        const val ACTION_DATABASE_UPDATE_KEY_DERIVATION_TASK = "ACTION_DATABASE_UPDATE_KEY_DERIVATION_TASK"
        const val ACTION_DATABASE_BENCHMARK_KDF = "ACTION_DATABASE_BENCHMARK_KDF"
        const val ACTION_DATABASE_SAVE = "ACTION_DATABASE_SAVE"
        const val ACTION_CHALLENGE_RESPONDED = "ACTION_CHALLENGE_RESPONDED"

        const val DATABASE_TASK_TITLE_KEY = "DATABASE_TASK_TITLE_KEY"
        const val DATABASE_TASK_MESSAGE_KEY = "DATABASE_TASK_MESSAGE_KEY"
        const val DATABASE_TASK_WARNING_KEY = "DATABASE_TASK_WARNING_KEY"

        const val DATABASE_URI_KEY = "DATABASE_URI_KEY"
        const val MAIN_CREDENTIAL_KEY = "MAIN_CREDENTIAL_KEY"
        const val READ_ONLY_KEY = "READ_ONLY_KEY"
        const val USER_VERIFICATION_KEY = "USER_VERIFICATION_KEY"
        const val CIPHER_DATABASE_KEY = "CIPHER_DATABASE_KEY"
        const val FIX_DUPLICATE_UUID_KEY = "FIX_DUPLICATE_UUID_KEY"
        const val GROUP_KEY = "GROUP_KEY"
        const val ENTRY_KEY = "ENTRY_KEY"
        const val GROUP_ID_KEY = "GROUP_ID_KEY"
        const val ENTRY_ID_KEY = "ENTRY_ID_KEY"
        const val GROUPS_ID_KEY = "GROUPS_ID_KEY"
        const val ENTRIES_ID_KEY = "ENTRIES_ID_KEY"
        const val PARENT_ID_KEY = "PARENT_ID_KEY"
        const val ENTRY_HISTORY_POSITION_KEY = "ENTRY_HISTORY_POSITION_KEY"
        const val SAVE_DATABASE_KEY = "SAVE_DATABASE_KEY"
        const val OLD_NODES_KEY = "OLD_NODES_KEY"
        const val NEW_NODES_KEY = "NEW_NODES_KEY"
        const val OLD_ELEMENT_KEY = "OLD_ELEMENT_KEY"
        const val NEW_ELEMENT_KEY = "NEW_ELEMENT_KEY"
        const val DATA_BYTES_KEY = "DATA_BYTES_KEY"
        const val BENCHMARK_TIME_KEY = "BENCHMARK_TIME_KEY"

        fun Bundle.getNewGroups(database: ContextualDatabase): List<GroupInfo>? {
            return getBundle(NEW_NODES_KEY)
                ?.getParcelableList<GroupId>(GROUPS_ID_KEY)
                ?.mapNotNull { database.getGroupInfoById(it) }
        }

        fun Bundle.getNewGroup(database: ContextualDatabase): GroupInfo? {
            return getNewGroups(database)?.get(0)
        }

        fun Bundle.getNewEntries(database: ContextualDatabase): List<EntryInfo>? {
            return getBundle(NEW_NODES_KEY)
                ?.getParcelableList<EntryId>(ENTRIES_ID_KEY)
                ?.mapNotNull { database.getEntryInfoById(it) }
        }

        fun Bundle.getNewEntry(database: ContextualDatabase): EntryInfo? {
            return getNewEntries(database)?.get(0)
        }

        fun Bundle.getStringElements(
            onElementsRetrieve: (oldElement: String, newElement: String) -> Unit
        ) {
            if (containsKey(OLD_ELEMENT_KEY) && containsKey(NEW_ELEMENT_KEY)) {
                val oldElement = getString(OLD_ELEMENT_KEY)!!
                val newElement = getString(NEW_ELEMENT_KEY)!!
                onElementsRetrieve.invoke(oldElement, newElement)
            }
        }

        fun Bundle.getIntElements(
            onElementsRetrieve: (oldElement: Int, newElement: Int) -> Unit
        ) {
            if (containsKey(OLD_ELEMENT_KEY) && containsKey(NEW_ELEMENT_KEY)) {
                val oldElement = getInt(OLD_ELEMENT_KEY)
                val newElement = getInt(NEW_ELEMENT_KEY)
                onElementsRetrieve.invoke(oldElement, newElement)
            }
        }

        fun Bundle.getLongElements(
            onElementsRetrieve: (oldElement: Long, newElement: Long) -> Unit
        ) {
            if (containsKey(OLD_ELEMENT_KEY) && containsKey(NEW_ELEMENT_KEY)) {
                val oldElement = getLong(OLD_ELEMENT_KEY)
                val newElement = getLong(NEW_ELEMENT_KEY)
                onElementsRetrieve.invoke(oldElement, newElement)
            }
        }

        inline fun <reified T: Serializable> Bundle.getSerializableElements(
            onElementsRetrieve: (oldElement: T?, newElement: T?) -> Unit
        ) {
            if (containsKey(OLD_ELEMENT_KEY) && containsKey(NEW_ELEMENT_KEY)) {
                val oldElement = getSerializableCompat<T>(OLD_ELEMENT_KEY)
                val newElement = getSerializableCompat<T>(NEW_ELEMENT_KEY)
                onElementsRetrieve.invoke(oldElement, newElement)
            }
        }

        inline fun <reified T: Parcelable> Bundle.getParcelableElements(
            onElementsRetrieve: (oldElement: T?, newElement: T?) -> Unit
        ) {
            if (containsKey(OLD_ELEMENT_KEY) && containsKey(NEW_ELEMENT_KEY)) {
                val oldElement = getParcelableCompat<T>(OLD_ELEMENT_KEY)
                val newElement = getParcelableCompat<T>(NEW_ELEMENT_KEY)
                onElementsRetrieve.invoke(oldElement, newElement)
            }
        }
    }
}
