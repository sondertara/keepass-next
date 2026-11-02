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
package com.kunzisoft.keepass.services

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.app.database.CipherDatabaseAction
import com.kunzisoft.keepass.app.database.FileDatabaseHistoryAction
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.DatabaseJobManager
import com.kunzisoft.keepass.database.MainCredential
import com.kunzisoft.keepass.database.action.BenchmarkKdfRunnable
import com.kunzisoft.keepass.database.action.CreateDatabaseRunnable
import com.kunzisoft.keepass.database.action.LoadDatabaseRunnable
import com.kunzisoft.keepass.database.action.MergeDatabaseRunnable
import com.kunzisoft.keepass.database.action.ReloadDatabaseRunnable
import com.kunzisoft.keepass.database.action.RemoveUnlinkedDataDatabaseRunnable
import com.kunzisoft.keepass.database.action.SaveDatabaseRunnable
import com.kunzisoft.keepass.database.action.UpdateCompressionBinariesDatabaseRunnable
import com.kunzisoft.keepass.database.action.UpdateKeyDerivationDatabaseRunnable
import com.kunzisoft.keepass.database.action.history.DeleteEntryHistoryDatabaseRunnable
import com.kunzisoft.keepass.database.action.history.RestoreEntryHistoryDatabaseRunnable
import com.kunzisoft.keepass.database.action.node.ActionNodesValues
import com.kunzisoft.keepass.database.action.node.AddEntryRunnable
import com.kunzisoft.keepass.database.action.node.AddGroupRunnable
import com.kunzisoft.keepass.database.action.node.AfterActionNodesFinish
import com.kunzisoft.keepass.database.action.node.CopyNodesRunnable
import com.kunzisoft.keepass.database.action.node.DeleteNodesRunnable
import com.kunzisoft.keepass.database.action.node.MoveNodesRunnable
import com.kunzisoft.keepass.database.action.node.TouchEntryRunnable
import com.kunzisoft.keepass.database.action.node.TouchGroupRunnable
import com.kunzisoft.keepass.database.action.node.UpdateEntryRunnable
import com.kunzisoft.keepass.database.action.node.UpdateGroupRunnable
import com.kunzisoft.keepass.database.crypto.kdf.KdfEngine
import com.kunzisoft.keepass.database.crypto.kdf.KdfEngine.Companion.DEFAULT_BENCHMARK_TIME
import com.kunzisoft.keepass.database.element.EntryId
import com.kunzisoft.keepass.database.element.GroupId
import com.kunzisoft.keepass.database.element.database.CompressionAlgorithm
import com.kunzisoft.keepass.hardware.HardwareKey
import com.kunzisoft.keepass.model.CipherEncryptDatabase
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.GroupInfo
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_CHALLENGE_RESPONDED
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_ASSIGN_CREDENTIAL_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_BENCHMARK_KDF
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_COPY_NODES_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_CREATE_ENTRY_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_CREATE_GROUP_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_CREATE_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_DELETE_ENTRY_HISTORY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_DELETE_NODES_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_LOAD_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_MERGE_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_MOVE_NODES_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_RELOAD_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_REMOVE_UNLINKED_DATA_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_RESTORE_ENTRY_HISTORY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_SAVE
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_TOUCH_ENTRY_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_TOUCH_GROUP_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_COLOR_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_COMPRESSION_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_DEFAULT_USERNAME_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_DESCRIPTION_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_ENCRYPTION_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_ENTRY_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_GROUP_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_ITERATIONS_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_KEY_DERIVATION_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_MAX_HISTORY_ITEMS_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_MAX_HISTORY_SIZE_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_MEMORY_USAGE_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_NAME_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_PARALLELISM_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_RECYCLE_BIN_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_TEMPLATES_GROUP_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.BENCHMARK_TIME_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.CIPHER_DATABASE_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.DATABASE_URI_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.DATA_BYTES_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ENTRIES_ID_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ENTRY_HISTORY_POSITION_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ENTRY_ID_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ENTRY_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.FIX_DUPLICATE_UUID_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.GROUPS_ID_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.GROUP_ID_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.GROUP_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.MAIN_CREDENTIAL_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.NEW_ELEMENT_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.NEW_NODES_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.OLD_ELEMENT_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.OLD_NODES_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.PARENT_ID_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.READ_ONLY_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.SAVE_DATABASE_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.USER_VERIFICATION_KEY
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.tasks.ActionRunnable
import com.kunzisoft.keepass.tasks.ProgressTaskUpdater
import com.kunzisoft.keepass.utils.getParcelableExtraCompat
import com.kunzisoft.keepass.utils.getParcelableList
import com.kunzisoft.keepass.utils.getSerializableExtraCompat
import com.kunzisoft.keepass.utils.putParcelableList

/**
 * Factory class responsible for parsing Intents and constructing the appropriate
 * [ActionRunnable] instances.
 */
object DatabaseActionBuilder {

    private const val TAG = "DatabaseActionBuilder"

    fun buildAction(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        progressTaskUpdater: ProgressTaskUpdater,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        val intentAction = intent.action ?: return null

        return when (intentAction) {
            ACTION_DATABASE_CREATE_TASK -> buildDatabaseCreateActionTask(context, intent, database, progressTaskUpdater, challengeResponseRetriever)
            ACTION_DATABASE_LOAD_TASK -> buildDatabaseLoadActionTask(context, intent, database, progressTaskUpdater, challengeResponseRetriever)
            ACTION_DATABASE_MERGE_TASK -> buildDatabaseMergeActionTask(context, intent, database, progressTaskUpdater, challengeResponseRetriever)
            ACTION_DATABASE_RELOAD_TASK -> buildDatabaseReloadActionTask(context, database, progressTaskUpdater)
            ACTION_DATABASE_ASSIGN_CREDENTIAL_TASK -> buildDatabaseAssignCredentialActionTask(context, intent, database, challengeResponseRetriever)
            ACTION_DATABASE_CREATE_GROUP_TASK -> buildDatabaseCreateGroupActionTask(context, intent, database, challengeResponseRetriever)
            ACTION_DATABASE_UPDATE_GROUP_TASK -> buildDatabaseUpdateGroupActionTask(context, intent, database, challengeResponseRetriever)
            ACTION_DATABASE_TOUCH_GROUP_TASK -> buildDatabaseTouchGroupActionTask(context, intent, database, challengeResponseRetriever)
            ACTION_DATABASE_CREATE_ENTRY_TASK -> buildDatabaseCreateEntryActionTask(context, intent, database, challengeResponseRetriever)
            ACTION_DATABASE_UPDATE_ENTRY_TASK -> buildDatabaseUpdateEntryActionTask(context, intent, database, challengeResponseRetriever)
            ACTION_DATABASE_TOUCH_ENTRY_TASK -> buildDatabaseTouchEntryActionTask(context, intent, database, challengeResponseRetriever)
            ACTION_DATABASE_COPY_NODES_TASK -> buildDatabaseCopyNodesActionTask(context, intent, database, challengeResponseRetriever)
            ACTION_DATABASE_MOVE_NODES_TASK -> buildDatabaseMoveNodesActionTask(context, intent, database, challengeResponseRetriever)
            ACTION_DATABASE_DELETE_NODES_TASK -> buildDatabaseDeleteNodesActionTask(context, intent, database, challengeResponseRetriever)
            ACTION_DATABASE_RESTORE_ENTRY_HISTORY -> buildDatabaseRestoreEntryHistoryActionTask(context, intent, database, challengeResponseRetriever)
            ACTION_DATABASE_DELETE_ENTRY_HISTORY -> buildDatabaseDeleteEntryHistoryActionTask(context, intent, database, challengeResponseRetriever)
            ACTION_DATABASE_UPDATE_COMPRESSION_TASK -> buildDatabaseUpdateCompressionActionTask(context, intent, database, challengeResponseRetriever)
            ACTION_DATABASE_REMOVE_UNLINKED_DATA_TASK -> buildDatabaseRemoveUnlinkedDataActionTask(context, intent, database, challengeResponseRetriever)
            ACTION_DATABASE_UPDATE_NAME_TASK,
            ACTION_DATABASE_UPDATE_DESCRIPTION_TASK,
            ACTION_DATABASE_UPDATE_DEFAULT_USERNAME_TASK,
            ACTION_DATABASE_UPDATE_COLOR_TASK,
            ACTION_DATABASE_UPDATE_RECYCLE_BIN_TASK,
            ACTION_DATABASE_UPDATE_TEMPLATES_GROUP_TASK,
            ACTION_DATABASE_UPDATE_MAX_HISTORY_ITEMS_TASK,
            ACTION_DATABASE_UPDATE_MAX_HISTORY_SIZE_TASK,
            ACTION_DATABASE_UPDATE_ENCRYPTION_TASK,
            ACTION_DATABASE_UPDATE_MEMORY_USAGE_TASK,
            ACTION_DATABASE_UPDATE_PARALLELISM_TASK,
            ACTION_DATABASE_UPDATE_ITERATIONS_TASK -> buildDatabaseUpdateElementActionTask(context, intent, database, challengeResponseRetriever)
            ACTION_DATABASE_UPDATE_KEY_DERIVATION_TASK -> buildDatabaseUpdateKeyDerivationActionTask(context, intent, database, progressTaskUpdater, challengeResponseRetriever)
            ACTION_DATABASE_BENCHMARK_KDF -> buildDatabaseBenchmarkKdfActionTask(context, intent, database, progressTaskUpdater)
            ACTION_DATABASE_SAVE -> buildDatabaseSaveActionTask(context, intent, database, challengeResponseRetriever)
            ACTION_CHALLENGE_RESPONDED -> buildChallengeRespondedActionTask(intent)
            else -> null
        }
    }

    private fun eraseCredentials(context: Context, databaseUri: Uri) {
        CipherDatabaseAction.getInstance(context).deleteByDatabaseUri(databaseUri)
        FileDatabaseHistoryAction.getInstance(context).deleteKeyFileByDatabaseUri(databaseUri)
    }

    private class AfterActionNodesRunnable : AfterActionNodesFinish() {
        override fun onActionNodesFinish(
            result: ActionRunnable.Result,
            actionNodesValues: ActionNodesValues,
        ) {
            val bundle = result.data ?: Bundle()
            bundle.putBundle(OLD_NODES_KEY, Bundle().apply {
                putParcelableList(GROUPS_ID_KEY, actionNodesValues.oldGroupsIds ?: listOf())
                putParcelableList(ENTRIES_ID_KEY, actionNodesValues.oldEntriesIds ?: listOf())
            })
            bundle.putBundle(NEW_NODES_KEY, Bundle().apply {
                putParcelableList(GROUPS_ID_KEY, actionNodesValues.newGroupsIds ?: listOf())
                putParcelableList(ENTRIES_ID_KEY, actionNodesValues.newEntriesIds ?: listOf())
            })
            result.data = bundle
        }
    }

    private fun buildDatabaseCreateActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        progressTaskUpdater: ProgressTaskUpdater,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(DATABASE_URI_KEY) && intent.hasExtra(MAIN_CREDENTIAL_KEY)) {
            val databaseUri: Uri = intent.getParcelableExtraCompat(DATABASE_URI_KEY) ?: return null
            val mainCredential: MainCredential =
                intent.getParcelableExtraCompat(MAIN_CREDENTIAL_KEY) ?: MainCredential()
            return CreateDatabaseRunnable(
                context = context,
                mDatabase = database,
                databaseUri = databaseUri,
                databaseName = context.getString(R.string.database_default_name),
                rootName = context.getString(R.string.database),
                templateGroupName = context.getString(R.string.template_group_name),
                mainCredential = mainCredential,
                challengeResponseRetriever = challengeResponseRetriever,
                progressTaskUpdater = progressTaskUpdater
            ).apply {
                afterSaveDatabase = { result ->
                    eraseCredentials(context, databaseUri)
                    if (result.isSuccess) {
                        if (PreferencesUtil.rememberDatabaseLocations(context.applicationContext)) {
                            FileDatabaseHistoryAction.getInstance(context.applicationContext)
                                .addOrUpdateDatabaseUri(
                                    databaseUri,
                                    if (PreferencesUtil.rememberKeyFileLocations(context.applicationContext))
                                        mainCredential.keyFileUri else null,
                                    if (PreferencesUtil.rememberHardwareKey(context.applicationContext))
                                        mainCredential.hardwareKey else null,
                                )
                        }
                        PreferencesUtil.saveCurrentTime(context.applicationContext)
                    } else {
                        Log.e(TAG, "Unable to create the database")
                    }
                    result.data = Bundle().apply {
                        putParcelable(DATABASE_URI_KEY, databaseUri)
                        putParcelable(MAIN_CREDENTIAL_KEY, mainCredential)
                    }
                }
            }
        }
        return null
    }

    private fun buildDatabaseLoadActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        progressTaskUpdater: ProgressTaskUpdater,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(DATABASE_URI_KEY)
            && intent.hasExtra(MAIN_CREDENTIAL_KEY)
            && intent.hasExtra(READ_ONLY_KEY)
            && intent.hasExtra(USER_VERIFICATION_KEY)
            && intent.hasExtra(CIPHER_DATABASE_KEY)
            && intent.hasExtra(FIX_DUPLICATE_UUID_KEY)
        ) {
            val databaseUri: Uri = intent.getParcelableExtraCompat(DATABASE_URI_KEY) ?: return null
            val mainCredential: MainCredential =
                intent.getParcelableExtraCompat(MAIN_CREDENTIAL_KEY) ?: MainCredential()
            val readOnly: Boolean = intent.getBooleanExtra(READ_ONLY_KEY, true)
            val allowUserVerification: Boolean = intent.getBooleanExtra(USER_VERIFICATION_KEY, true)
            val cipherEncryptDatabase: CipherEncryptDatabase? =
                intent.getParcelableExtraCompat(CIPHER_DATABASE_KEY)
            return LoadDatabaseRunnable(
                context = context,
                mDatabase = database,
                mDatabaseUri = databaseUri,
                mMainCredential = mainCredential,
                mChallengeResponseRetriever = challengeResponseRetriever,
                mReadonly = readOnly,
                mAllowUserVerification = allowUserVerification,
                mFixDuplicateUUID = intent.getBooleanExtra(FIX_DUPLICATE_UUID_KEY, false),
                progressTaskUpdater = progressTaskUpdater
            ).apply {
                afterLoadDatabase = { result ->
                    if (result.isSuccess) {
                        if (PreferencesUtil.rememberDatabaseLocations(context.applicationContext)) {
                            FileDatabaseHistoryAction.getInstance(context.applicationContext)
                                .addOrUpdateDatabaseUri(
                                    databaseUri,
                                    if (PreferencesUtil.rememberKeyFileLocations(context.applicationContext))
                                        mainCredential.keyFileUri else null,
                                    if (PreferencesUtil.rememberHardwareKey(context.applicationContext))
                                        mainCredential.hardwareKey else null,
                                )
                        }
                        cipherEncryptDatabase?.let { cipherDatabase ->
                            CipherDatabaseAction.getInstance(context.applicationContext)
                                .addOrUpdateCipherDatabase(cipherDatabase)
                        }
                        PreferencesUtil.saveCurrentTime(context.applicationContext)
                    }
                    result.data = Bundle().apply {
                        putParcelable(DATABASE_URI_KEY, databaseUri)
                        putParcelable(MAIN_CREDENTIAL_KEY, mainCredential)
                        putBoolean(READ_ONLY_KEY, readOnly)
                        putBoolean(USER_VERIFICATION_KEY, allowUserVerification)
                        putParcelable(CIPHER_DATABASE_KEY, cipherEncryptDatabase)
                    }
                }
            }
        }
        return null
    }

    private fun buildDatabaseMergeActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        progressTaskUpdater: ProgressTaskUpdater,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable {
        var databaseToMergeUri: Uri? = null
        var databaseToMergeMainCredential: MainCredential? = null
        if (intent.hasExtra(DATABASE_URI_KEY)) {
            databaseToMergeUri = intent.getParcelableExtraCompat(DATABASE_URI_KEY)
        }
        if (intent.hasExtra(MAIN_CREDENTIAL_KEY)) {
            databaseToMergeMainCredential = intent.getParcelableExtraCompat(MAIN_CREDENTIAL_KEY)
        }
        val saveDatabase = intent.getBooleanExtra(SAVE_DATABASE_KEY, false)
        return MergeDatabaseRunnable(
            context = context,
            mDatabaseToMergeUri = databaseToMergeUri,
            mDatabaseToMergeMainCredential = databaseToMergeMainCredential,
            mDatabaseToMergeChallengeResponseRetriever = challengeResponseRetriever,
            database = database,
            save = !database.isReadOnly && saveDatabase,
            challengeResponseRetriever = challengeResponseRetriever,
            progressTaskUpdater = progressTaskUpdater
        ).apply {
            afterSaveDatabase = { result ->
                if (result.isSuccess) {
                    PreferencesUtil.saveCurrentTime(context.applicationContext)
                }
                result.data = Bundle()
            }
        }
    }

    private fun buildDatabaseReloadActionTask(
        context: Context,
        database: ContextualDatabase,
        progressTaskUpdater: ProgressTaskUpdater
    ): ActionRunnable {
        return ReloadDatabaseRunnable(
            context = context,
            mDatabase = database,
            progressTaskUpdater = progressTaskUpdater
        ).apply {
            afterReloadDatabase = { result ->
                if (result.isSuccess) {
                    PreferencesUtil.saveCurrentTime(context.applicationContext)
                }
                result.data = Bundle()
            }
        }
    }

    private fun buildDatabaseAssignCredentialActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(DATABASE_URI_KEY) && intent.hasExtra(MAIN_CREDENTIAL_KEY)) {
            val databaseUri: Uri = intent.getParcelableExtraCompat(DATABASE_URI_KEY) ?: return null
            return SaveDatabaseRunnable(
                context = context,
                database = database,
                save = true,
                mainCredential = intent.getParcelableExtraCompat(MAIN_CREDENTIAL_KEY) ?: MainCredential(),
                challengeResponseRetriever = challengeResponseRetriever,
                databaseCopyUri = null
            ).apply {
                afterSaveDatabase = {
                    eraseCredentials(context, databaseUri)
                }
            }
        }
        return null
    }

    private fun buildDatabaseCreateGroupActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(GROUP_KEY) && intent.hasExtra(PARENT_ID_KEY) && intent.hasExtra(SAVE_DATABASE_KEY)) {
            val parentId: GroupId = intent.getParcelableExtraCompat(PARENT_ID_KEY) ?: return null
            val newGroup: GroupInfo = intent.getParcelableExtraCompat(GROUP_KEY) ?: return null
            val saveDatabase = intent.getBooleanExtra(SAVE_DATABASE_KEY, false)
            return AddGroupRunnable(
                context = context,
                database = database,
                parentId = parentId,
                newGroup = newGroup,
                save = !database.isReadOnly && saveDatabase,
                afterActionNodesFinish = AfterActionNodesRunnable(),
                challengeResponseRetriever = challengeResponseRetriever
            )
        }
        return null
    }

    private fun buildDatabaseUpdateGroupActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(GROUP_KEY) && intent.hasExtra(SAVE_DATABASE_KEY)) {
            val newGroup: GroupInfo = intent.getParcelableExtraCompat(GROUP_KEY) ?: return null
            val saveDatabase = intent.getBooleanExtra(SAVE_DATABASE_KEY, false)
            return UpdateGroupRunnable(
                context = context,
                database = database,
                newGroup = newGroup,
                save = !database.isReadOnly && saveDatabase,
                afterActionNodesFinish = AfterActionNodesRunnable(),
                challengeResponseRetriever = challengeResponseRetriever
            )
        }
        return null
    }

    private fun buildDatabaseTouchGroupActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(GROUP_ID_KEY)) {
            val groupId: GroupId = intent.getParcelableExtraCompat(GROUP_ID_KEY) ?: return null
            return TouchGroupRunnable(
                context = context,
                database = database,
                groupId = groupId,
                afterActionNodesFinish = AfterActionNodesRunnable(),
                challengeResponseRetriever = challengeResponseRetriever
            )
        }
        return null
    }

    private fun buildDatabaseCreateEntryActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(ENTRY_KEY) && intent.hasExtra(PARENT_ID_KEY) && intent.hasExtra(SAVE_DATABASE_KEY)) {
            val parentId: GroupId = intent.getParcelableExtraCompat(PARENT_ID_KEY) ?: return null
            val newEntry: EntryInfo = intent.getParcelableExtraCompat(ENTRY_KEY) ?: return null
            val saveDatabase = intent.getBooleanExtra(SAVE_DATABASE_KEY, false)
            return AddEntryRunnable(
                context = context,
                database = database,
                parentId = parentId,
                newEntry = newEntry,
                save = !database.isReadOnly && saveDatabase,
                afterActionNodesFinish = AfterActionNodesRunnable(),
                challengeResponseRetriever = challengeResponseRetriever
            )
        }
        return null
    }

    private fun buildDatabaseUpdateEntryActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(ENTRY_KEY) && intent.hasExtra(SAVE_DATABASE_KEY)) {
            val newEntry: EntryInfo = intent.getParcelableExtraCompat(ENTRY_KEY) ?: return null
            val saveDatabase = intent.getBooleanExtra(SAVE_DATABASE_KEY, false)
            return UpdateEntryRunnable(
                context = context,
                database = database,
                newEntry = newEntry,
                save = !database.isReadOnly && saveDatabase,
                afterActionNodesFinish = AfterActionNodesRunnable(),
                challengeResponseRetriever = challengeResponseRetriever
            )
        }
        return null
    }

    private fun buildDatabaseTouchEntryActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(ENTRY_ID_KEY)) {
            val entryId: EntryId = intent.getParcelableExtraCompat(ENTRY_ID_KEY) ?: return null
            return TouchEntryRunnable(
                context = context,
                database = database,
                oldEntryId = entryId,
                afterActionNodesFinish = AfterActionNodesRunnable(),
                challengeResponseRetriever = challengeResponseRetriever
            )
        }
        return null
    }

    private fun buildDatabaseCopyNodesActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(PARENT_ID_KEY)
            && intent.hasExtra(GROUPS_ID_KEY)
            && intent.hasExtra(ENTRIES_ID_KEY)
            && intent.hasExtra(SAVE_DATABASE_KEY)
        ) {
            val parentId: GroupId = intent.getParcelableExtraCompat(PARENT_ID_KEY) ?: return null
            val groupsIds = intent.getParcelableList<GroupId>(GROUPS_ID_KEY) ?: listOf()
            val entriesIds = intent.getParcelableList<EntryId>(ENTRIES_ID_KEY) ?: listOf()
            val saveDatabase = intent.getBooleanExtra(SAVE_DATABASE_KEY, false)
            return CopyNodesRunnable(
                context = context,
                database = database,
                newParentId = parentId,
                groupsIdsToCopy = groupsIds,
                entriesIdsToCopy = entriesIds,
                save = !database.isReadOnly && saveDatabase,
                afterActionNodesFinish = AfterActionNodesRunnable(),
                challengeResponseRetriever = challengeResponseRetriever
            )
        }
        return null
    }

    private fun buildDatabaseMoveNodesActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(PARENT_ID_KEY)
            && intent.hasExtra(GROUPS_ID_KEY)
            && intent.hasExtra(ENTRIES_ID_KEY)
            && intent.hasExtra(SAVE_DATABASE_KEY)
        ) {
            val parentId: GroupId = intent.getParcelableExtraCompat(PARENT_ID_KEY) ?: return null
            val groupsIds = intent.getParcelableList<GroupId>(GROUPS_ID_KEY) ?: listOf()
            val entriesIds = intent.getParcelableList<EntryId>(ENTRIES_ID_KEY) ?: listOf()
            val saveDatabase = intent.getBooleanExtra(SAVE_DATABASE_KEY, false)
            return MoveNodesRunnable(
                context = context,
                database = database,
                parentId = parentId,
                groupsIdsToMove = groupsIds,
                entriesIdsToMove = entriesIds,
                save = !database.isReadOnly && saveDatabase,
                afterActionNodesFinish = AfterActionNodesRunnable(),
                challengeResponseRetriever = challengeResponseRetriever
            )
        }
        return null
    }

    private fun buildDatabaseDeleteNodesActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(GROUPS_ID_KEY)
            && intent.hasExtra(ENTRIES_ID_KEY)
            && intent.hasExtra(SAVE_DATABASE_KEY)
        ) {
            val groupsIds = intent.getParcelableList<GroupId>(GROUPS_ID_KEY) ?: listOf()
            val entriesIds = intent.getParcelableList<EntryId>(ENTRIES_ID_KEY) ?: listOf()
            val saveDatabase = intent.getBooleanExtra(SAVE_DATABASE_KEY, false)
            return DeleteNodesRunnable(
                context = context,
                database = database,
                groupsIdsToDelete = groupsIds,
                entriesIdsToDelete = entriesIds,
                recyclerBinTitle = context.resources.getString(R.string.recycle_bin),
                save = !database.isReadOnly && saveDatabase,
                afterActionNodesFinish = AfterActionNodesRunnable(),
                challengeResponseRetriever = challengeResponseRetriever
            )
        }
        return null
    }

    private fun buildDatabaseRestoreEntryHistoryActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(ENTRY_ID_KEY)
            && intent.hasExtra(ENTRY_HISTORY_POSITION_KEY)
            && intent.hasExtra(SAVE_DATABASE_KEY)
        ) {
            val entryId: EntryId = intent.getParcelableExtraCompat(ENTRY_ID_KEY) ?: return null
            val saveDatabase = intent.getBooleanExtra(SAVE_DATABASE_KEY, false)
            return RestoreEntryHistoryDatabaseRunnable(
                context = context,
                database = database,
                entryId = entryId,
                entryHistoryPosition = intent.getIntExtra(ENTRY_HISTORY_POSITION_KEY, -1),
                save = !database.isReadOnly && saveDatabase,
                challengeResponseRetriever = challengeResponseRetriever
            )
        }
        return null
    }

    private fun buildDatabaseDeleteEntryHistoryActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(ENTRY_ID_KEY)
            && intent.hasExtra(ENTRY_HISTORY_POSITION_KEY)
            && intent.hasExtra(SAVE_DATABASE_KEY)
        ) {
            val entryId: EntryId = intent.getParcelableExtraCompat(ENTRY_ID_KEY) ?: return null
            val saveDatabase = intent.getBooleanExtra(SAVE_DATABASE_KEY, false)
            return DeleteEntryHistoryDatabaseRunnable(
                context = context,
                database = database,
                mainEntryId = entryId,
                entryHistoryPosition = intent.getIntExtra(ENTRY_HISTORY_POSITION_KEY, -1),
                save = !database.isReadOnly && saveDatabase,
                challengeResponseRetriever = challengeResponseRetriever
            )
        }
        return null
    }

    private fun buildDatabaseUpdateCompressionActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(OLD_ELEMENT_KEY)
            && intent.hasExtra(NEW_ELEMENT_KEY)
            && intent.hasExtra(SAVE_DATABASE_KEY)
        ) {
            val oldElement: CompressionAlgorithm? = intent.getParcelableExtraCompat(OLD_ELEMENT_KEY)
            val newElement: CompressionAlgorithm? = intent.getParcelableExtraCompat(NEW_ELEMENT_KEY)
            if (oldElement == null || newElement == null) return null
            val saveDatabase = intent.getBooleanExtra(SAVE_DATABASE_KEY, false)
            return UpdateCompressionBinariesDatabaseRunnable(
                context = context,
                database = database,
                oldCompressionAlgorithm = oldElement,
                newCompressionAlgorithm = newElement,
                save = !database.isReadOnly && saveDatabase,
                challengeResponseRetriever = challengeResponseRetriever
            ).apply {
                afterSaveDatabase = { result ->
                    result.data = intent.extras
                }
            }
        }
        return null
    }

    private fun buildDatabaseRemoveUnlinkedDataActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(SAVE_DATABASE_KEY)) {
            val saveDatabase = intent.getBooleanExtra(SAVE_DATABASE_KEY, false)
            return RemoveUnlinkedDataDatabaseRunnable(
                context = context,
                database = database,
                save = !database.isReadOnly && saveDatabase,
                challengeResponseRetriever = challengeResponseRetriever
            ).apply {
                afterSaveDatabase = { result ->
                    result.data = intent.extras
                }
            }
        }
        return null
    }

    private fun buildDatabaseUpdateElementActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(SAVE_DATABASE_KEY)) {
            val saveDatabase = intent.getBooleanExtra(SAVE_DATABASE_KEY, false)
            return SaveDatabaseRunnable(
                context = context,
                database = database,
                save = !database.isReadOnly && saveDatabase,
                mainCredential = null,
                challengeResponseRetriever = challengeResponseRetriever
            ).apply {
                afterSaveDatabase = { result ->
                    result.data = intent.extras
                }
            }
        }
        return null
    }

    private fun buildDatabaseUpdateKeyDerivationActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        progressTaskUpdater: ProgressTaskUpdater,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        val oldKeyDerivation = intent.getSerializableExtraCompat<KdfEngine>(OLD_ELEMENT_KEY)
        val newKeyDerivation = intent.getSerializableExtraCompat<KdfEngine>(NEW_ELEMENT_KEY)
        val saveDatabase = intent.getBooleanExtra(SAVE_DATABASE_KEY, false)
        if (oldKeyDerivation != null && newKeyDerivation != null) {
            return UpdateKeyDerivationDatabaseRunnable(
                context = context,
                database = database,
                oldKeyDerivation = oldKeyDerivation,
                newKeyDerivation = newKeyDerivation,
                save = !database.isReadOnly && saveDatabase,
                challengeResponseRetriever = challengeResponseRetriever,
                progressTaskUpdater = progressTaskUpdater
            ).apply {
                afterSaveDatabase = { result ->
                    result.data = intent.extras
                }
            }
        }
        return null
    }

    private fun buildDatabaseSaveActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
    ): ActionRunnable? {
        if (intent.hasExtra(SAVE_DATABASE_KEY)) {
            val saveDatabase = intent.getBooleanExtra(SAVE_DATABASE_KEY, false)
            var databaseCopyUri: Uri? = null
            if (intent.hasExtra(DATABASE_URI_KEY)) {
                databaseCopyUri = intent.getParcelableExtraCompat(DATABASE_URI_KEY)
            }
            return SaveDatabaseRunnable(
                context = context,
                database = database,
                save = !database.isReadOnly && saveDatabase,
                mainCredential = null,
                challengeResponseRetriever = challengeResponseRetriever,
                databaseCopyUri = databaseCopyUri
            )
        }
        return null
    }

    private fun buildDatabaseBenchmarkKdfActionTask(
        context: Context,
        intent: Intent,
        database: ContextualDatabase,
        progressTaskUpdater: ProgressTaskUpdater
    ): ActionRunnable {
        return BenchmarkKdfRunnable(
            context = context,
            database = database,
            targetTime = intent.getLongExtra(BENCHMARK_TIME_KEY, DEFAULT_BENCHMARK_TIME),
            progressTaskUpdater = progressTaskUpdater
        )
    }

    private fun buildChallengeRespondedActionTask(intent: Intent): ActionRunnable? {
        if (intent.hasExtra(DATA_BYTES_KEY)) {
            return object : ActionRunnable() {
                override fun onStartRun() {}
                override fun onActionRun() {
                    intent.getByteArrayExtra(DATA_BYTES_KEY)?.let { response ->
                        DatabaseJobManager.getInstance().sendResponseToChallenge(response)
                    }
                }
                override fun onFinishRun() {}
            }
        }
        return null
    }
}
