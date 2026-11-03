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
package com.kunzisoft.keepass.activities.session

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.dialogs.DatabaseChangedDialogFragment
import com.kunzisoft.keepass.activities.dialogs.DatabaseChangedDialogFragment.Companion.DATABASE_CHANGED_DIALOG_TAG
import com.kunzisoft.keepass.activities.dialogs.DeleteNodesDialogFragment
import com.kunzisoft.keepass.activities.dialogs.PasswordEncodingDialogFragment
import com.kunzisoft.keepass.credentialprovider.EntrySelectionHelper.isIntentSenderMode
import com.kunzisoft.keepass.credentialprovider.EntrySelectionHelper.removeInfo
import com.kunzisoft.keepass.credentialprovider.EntrySelectionHelper.removeModes
import com.kunzisoft.keepass.credentialprovider.EntrySelectionHelper.retrieveRegisterInfo
import com.kunzisoft.keepass.credentialprovider.EntrySelectionHelper.retrieveSearchInfo
import com.kunzisoft.keepass.credentialprovider.EntrySelectionHelper.retrieveSpecialMode
import com.kunzisoft.keepass.credentialprovider.EntrySelectionHelper.retrieveTypeMode
import com.kunzisoft.keepass.credentialprovider.EntrySelectionHelper.setActivityResult
import com.kunzisoft.keepass.credentialprovider.SpecialMode
import com.kunzisoft.keepass.credentialprovider.TypeMode
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.DatabaseTaskProvider.Companion.startDatabaseService
import com.kunzisoft.keepass.database.MainCredential
import com.kunzisoft.keepass.database.element.EntryId
import com.kunzisoft.keepass.database.element.GroupId
import com.kunzisoft.keepass.database.element.node.Nodes
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.GroupInfo
import com.kunzisoft.keepass.model.RegisterInfo
import com.kunzisoft.keepass.model.SearchInfo
import com.kunzisoft.keepass.model.SnapFileDatabaseInfo
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.tasks.ActionRunnable
import com.kunzisoft.keepass.tasks.ProgressTaskDialogFragment
import com.kunzisoft.keepass.tasks.ProgressTaskDialogFragment.Companion.PROGRESS_TASK_DIALOG_TAG
import com.kunzisoft.keepass.tasks.ProgressTaskViewModel
import com.kunzisoft.keepass.timeout.TimeoutHelper
import com.kunzisoft.keepass.utils.LOCK_ACTION
import com.kunzisoft.keepass.utils.LockReceiver
import com.kunzisoft.keepass.utils.closeDatabase
import com.kunzisoft.keepass.utils.registerLockReceiver
import com.kunzisoft.keepass.utils.unregisterLockReceiver
import com.kunzisoft.keepass.view.ToolbarSpecial
import com.kunzisoft.keepass.view.showActionErrorIfNeeded
import com.kunzisoft.keepass.viewmodels.DatabaseViewModel
import com.kunzisoft.keepass.viewmodels.NodesViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Modular controller managing database session lifecycle, timeout, lock state,
 * special mode toolbar, and CRUD action delegation.
 *
 * Implements DefaultLifecycleObserver to attach directly to AppCompatActivity.
 */
class DatabaseSessionController(
    val activity: AppCompatActivity,
    val callback: DatabaseSessionCallback,
    val options: DatabaseSessionOptions = DatabaseSessionOptions()
) : DefaultLifecycleObserver {

    val databaseViewModel: DatabaseViewModel by lazy {
        ViewModelProvider(activity)[DatabaseViewModel::class.java]
    }
    val database: ContextualDatabase?
        get() = databaseViewModel.database

    private val progressTaskViewModel: ProgressTaskViewModel by lazy {
        ViewModelProvider(activity)[ProgressTaskViewModel::class.java]
    }
    private val nodesViewModel: NodesViewModel by lazy {
        ViewModelProvider(activity)[NodesViewModel::class.java]
    }

    private var progressTaskDialogFragment: ProgressTaskDialogFragment? = null
    private var databaseChangedDialogFragment: DatabaseChangedDialogFragment? = null

    private val tempServiceParameters = mutableListOf<Pair<Bundle?, String>>()
    private val requestPermissionLauncher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            tempServiceParameters.removeFirstOrNull()?.let {
                activity.startDatabaseService(it.first, it.second)
            }
        }

    var credentialResultLaunched: Boolean = false

    val credentialActivityResultLauncher: CredentialActivityResultLauncher =
        CredentialActivityResultLauncher(
            activity.registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) {
                activity.setActivityResult(
                    lockDatabase = false,
                    resultCode = it.resultCode,
                    data = it.data
                )
            }
        )

    // Timeout & lock management
    val isVaultProtected: Boolean
        get() = options.role == SessionRole.VAULT_PROTECTED && options.enableTimeout

    var timeoutEnable: Boolean = isVaultProtected
    private var lockReceiver: LockReceiver? = null
    private var exitLock: Boolean = false
    var databaseAllowUserVerification: Boolean = true
    private var autoSaveEnable: Boolean = true

    // Special mode management
    var specialMode: SpecialMode = SpecialMode.DEFAULT
        private set
    var typeMode: TypeMode = TypeMode.DEFAULT
        private set
    private var toolbarSpecial: ToolbarSpecial? = null

    init {
        activity.lifecycle.addObserver(this)
    }

    inner class CredentialActivityResultLauncher(
        val builder: ActivityResultLauncher<Intent>
    ) : ActivityResultLauncher<Intent>() {
        override fun launch(input: Intent?, options: ActivityOptionsCompat?) {
            credentialResultLaunched = true
            builder.launch(input, options)
        }

        override fun unregister() {
            builder.unregister()
        }

        override fun getContract(): ActivityResultContract<Intent?, *> {
            return builder.contract
        }
    }

    fun onRestoreInstanceState(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            if (savedInstanceState.containsKey(CREDENTIAL_RESULT_LAUNCHER_KEY)) {
                credentialResultLaunched = savedInstanceState.getBoolean(CREDENTIAL_RESULT_LAUNCHER_KEY)
            }
            if (savedInstanceState.containsKey(TIMEOUT_ENABLE_KEY)) {
                timeoutEnable = savedInstanceState.getBoolean(TIMEOUT_ENABLE_KEY) && isVaultProtected
            }
        } else {
            activity.intent?.let {
                timeoutEnable = it.getBooleanExtra(TIMEOUT_ENABLE_KEY, isVaultProtected) && isVaultProtected
            }
        }
    }

    fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(CREDENTIAL_RESULT_LAUNCHER_KEY, credentialResultLaunched)
        outState.putBoolean(TIMEOUT_ENABLE_KEY, timeoutEnable)
    }

    override fun onCreate(owner: LifecycleOwner) {
        exitLock = false

        if (options.enableSpecialMode || options.enableTimeout) {
            activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleDatabaseBackPressed()
                }
            })
        }
        if (options.enableSpecialMode) {
            activity.intent?.let {
                specialMode = it.retrieveSpecialMode()
                typeMode = it.retrieveTypeMode()
            }
        }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    databaseViewModel.databaseState.collect { db ->
                        if (!credentialResultLaunched) {
                            callback.onUnknownDatabaseRetrieved(db)
                            db?.let {
                                onDatabaseRetrievedInternal(it)
                            }
                        }
                    }
                }
                launch {
                    databaseViewModel.actionState.collect { uiState ->
                        if (!credentialResultLaunched) {
                            when (uiState) {
                                is DatabaseViewModel.ActionState.Wait -> {}
                                is DatabaseViewModel.ActionState.OnDatabaseReloaded -> {
                                    if (options.finishActivityIfReloadRequested || callback.finishActivityIfReloadRequested()) {
                                        activity.finish()
                                    }
                                }
                                is DatabaseViewModel.ActionState.OnDatabaseInfoChanged -> {
                                    if (callback.manageDatabaseInfo() && options.manageDatabaseInfo) {
                                        showDatabaseChangedDialog(
                                            uiState.previousDatabaseInfo,
                                            uiState.newDatabaseInfo,
                                            uiState.readOnlyDatabase
                                        )
                                    }
                                    databaseViewModel.cancelAction()
                                }
                                is DatabaseViewModel.ActionState.ShowDatabaseInfoReloadedDialog -> {
                                    if (callback.manageDatabaseInfo() && options.manageDatabaseInfo) {
                                        showDatabaseInfoReloadedDialog(uiState.fixDuplicateUuid)
                                    }
                                    databaseViewModel.cancelAction()
                                }
                                is DatabaseViewModel.ActionState.OnDatabaseActionRequested -> {
                                    databaseViewModel.cancelAction()
                                    startDatabasePermissionService(uiState.bundle, uiState.actionTask)
                                }
                                is DatabaseViewModel.ActionState.OnDatabaseActionStarted -> {
                                    progressTaskViewModel.show(uiState.progressMessage)
                                }
                                is DatabaseViewModel.ActionState.OnDatabaseActionUpdated -> {
                                    progressTaskViewModel.show(uiState.progressMessage)
                                }
                                is DatabaseViewModel.ActionState.OnDatabaseActionStopped -> {
                                    progressTaskViewModel.hide()
                                }
                                is DatabaseViewModel.ActionState.OnDatabaseActionFinished -> {
                                    callback.errorCoordinatorView()?.showActionErrorIfNeeded(uiState.result)
                                        ?: activity.showActionErrorIfNeeded(uiState.result)
                                    onDatabaseActionFinishedInternal(
                                        uiState.database,
                                        uiState.actionTask,
                                        uiState.result
                                    )
                                }
                                is DatabaseViewModel.ActionState.ShowPasswordEncodingDialog -> {
                                    showPasswordEncodingDialog(
                                        uiState.databaseUri,
                                        uiState.mainCredential
                                    )
                                    databaseViewModel.cancelAction()
                                }
                            }
                        }
                    }
                }
                launch {
                    progressTaskViewModel.progressTaskState.collect { state ->
                        when (state) {
                            is ProgressTaskViewModel.ProgressTaskState.Show -> startProgressDialog()
                            is ProgressTaskViewModel.ProgressTaskState.Hide -> stopProgressDialog()
                        }
                    }
                }
                if (isVaultProtected) {
                    launch {
                        nodesViewModel.nodesToPermanentlyDelete.collect { nodes ->
                            deleteDatabaseNodes(nodes)
                        }
                    }
                    launch {
                        nodesViewModel.shouldShowDeleteDialog.collect { recycleBin ->
                            DeleteNodesDialogFragment.getInstance(recycleBin)
                                .show(activity.supportFragmentManager, "deleteNodesDialogFragment")
                        }
                    }
                }
            }
        }

        // Database retrieval timeout guard
        if (isVaultProtected && options.timeoutRetrievalGuard) {
            activity.lifecycleScope.launch {
                if (options.finishActivityIfReloadRequested || callback.finishActivityIfReloadRequested()) {
                    delay(3000)
                    if (database?.loaded != true) {
                        Log.w(TAG, "Database not loaded after 3 seconds, finishing activity")
                        activity.finish()
                    }
                }
            }
        }
    }

    private fun onDatabaseRetrievedInternal(db: ContextualDatabase) {
        if (isVaultProtected) {
            if (!db.loaded) {
                if (timeoutEnable) {
                    activity.finish()
                    return
                }
            }

            callback.viewToInvalidateTimeout()
                ?.resetAppTimeoutWhenViewTouchedOrFocused(activity, db.loaded)

            if (timeoutEnable) {
                if (lockReceiver == null) {
                    lockReceiver = LockReceiver {
                        activity.closeDatabase(db)
                        exitLock = true
                        activity.closeOptionsMenu()
                        activity.finish()
                    }
                    activity.registerLockReceiver(lockReceiver)
                }
                TimeoutHelper.checkTimeAndLockIfTimeout(activity)
                if (!exitLock) {
                    TimeoutHelper.recordTime(activity, db.loaded)
                }
            }

            databaseAllowUserVerification = db.allowUserVerification
            checkRegister()
        }
        callback.onDatabaseRetrieved(db)
    }

    private fun onDatabaseActionFinishedInternal(
        db: ContextualDatabase,
        actionTask: String,
        result: ActionRunnable.Result
    ) {
        if (isVaultProtected) {
            when (actionTask) {
                DatabaseTaskNotificationService.ACTION_DATABASE_MERGE_TASK,
                DatabaseTaskNotificationService.ACTION_DATABASE_RELOAD_TASK -> {
                    if (result.isSuccess) {
                        activity.recreate()
                        showDatabaseActionToast(
                            when (actionTask) {
                                DatabaseTaskNotificationService.ACTION_DATABASE_MERGE_TASK ->
                                    if (db.isReadOnly || !PreferencesUtil.isAutoSaveDatabaseEnabled(activity))
                                        R.string.temporary_merge_success
                                    else
                                        R.string.merge_success
                                else ->
                                    R.string.reload_success
                            }
                        )
                    } else {
                        lockAndExit()
                    }
                }
                else -> {
                    if (result.isSuccess && result.data?.getBoolean(
                            DatabaseTaskNotificationService.SAVE_DATABASE_KEY,
                            false
                        ) == true
                    ) {
                        showDatabaseActionToast(R.string.save_success)
                    }
                }
            }
        }
        callback.onDatabaseActionFinished(db, actionTask, result)
    }

    override fun onResume(owner: LifecycleOwner) {
        databaseViewModel.checkChanges()

        if (isVaultProtected) {
            autoSaveEnable = PreferencesUtil.isAutoSaveDatabaseEnabled(activity)
            database?.let { db ->
                callback.viewToInvalidateTimeout()
                    ?.resetAppTimeoutWhenViewTouchedOrFocused(activity, db.loaded)
            }
            activity.invalidateOptionsMenu()
        }

        if (options.enableSpecialMode) {
            activity.intent?.let {
                specialMode = it.retrieveSpecialMode()
                typeMode = it.retrieveTypeMode()
                val registerInfo: RegisterInfo? = it.retrieveRegisterInfo()
                val searchInfo: SearchInfo? = registerInfo?.searchInfo ?: it.retrieveSearchInfo()
                setupSpecialModeToolbar(registerInfo, searchInfo)
            }
            if (specialMode != SpecialMode.DEFAULT && callback.hideHomeButtonIfModeIsNotDefault()) {
                activity.supportActionBar?.setDisplayHomeAsUpEnabled(false)
                activity.supportActionBar?.setDisplayShowHomeEnabled(false)
            }
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        if (timeoutEnable) {
            TimeoutHelper.checkTimeAndLockIfTimeout(activity)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        activity.unregisterLockReceiver(lockReceiver)
        lockReceiver = null
        toolbarSpecial = null
        progressTaskDialogFragment?.dismissAllowingStateLoss()
        progressTaskDialogFragment = null
        databaseChangedDialogFragment?.dismissAllowingStateLoss()
        databaseChangedDialogFragment = null
    }

    private fun setupSpecialModeToolbar(registerInfo: RegisterInfo?, searchInfo: SearchInfo?) {
        toolbarSpecial = activity.findViewById(R.id.special_mode_view)
        toolbarSpecial?.apply {
            val selectionModeStringId = when (specialMode) {
                SpecialMode.DEFAULT,
                SpecialMode.SEARCH -> R.string.search_mode
                SpecialMode.SELECTION -> R.string.selection_mode
                SpecialMode.REGISTRATION -> R.string.save_mode
            }
            val typeModeStringId = when (typeMode) {
                TypeMode.DEFAULT -> 0
                TypeMode.MAGIKEYBOARD -> R.string.magic_keyboard_title
                TypeMode.AUTOFILL -> R.string.autofill
                TypeMode.PASSWORD -> R.string.password
                TypeMode.PASSKEY -> R.string.passkey
            }
            title = activity.getString(selectionModeStringId)
            if (typeMode != TypeMode.DEFAULT && typeModeStringId != 0) {
                title = "$title (${activity.getString(typeModeStringId)})"
            }
            subtitle = registerInfo?.getName(activity.resources) ?: searchInfo?.getName(activity.resources)
            visible = when (specialMode) {
                SpecialMode.DEFAULT -> false
                SpecialMode.SEARCH,
                SpecialMode.SELECTION,
                SpecialMode.REGISTRATION -> true
            }
            onCancelButtonClickListener = View.OnClickListener {
                onCancelSpecialMode()
            }
            menu.clear()
            if (typeMode == TypeMode.AUTOFILL) {
                activity.menuInflater.inflate(R.menu.autofill, menu)
                setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.menu_block_autofill -> {
                            blockAutofill(searchInfo)
                        }
                    }
                    true
                }
            }
        }
    }

    private fun blockAutofill(searchInfo: SearchInfo?) {
        val webDomain = searchInfo?.webDomain
        val applicationId = searchInfo?.applicationId
        if (webDomain != null) {
            PreferencesUtil.addWebDomainToBlocklist(activity, webDomain)
        } else if (applicationId != null) {
            PreferencesUtil.addApplicationIdToBlocklist(activity, applicationId)
        }
        onCancelSpecialMode()
        Toast.makeText(
            activity.applicationContext,
            R.string.autofill_block_restart,
            Toast.LENGTH_LONG
        ).show()
    }

    fun isIntentSender(): Boolean = isIntentSenderMode(specialMode, typeMode)

    fun onLaunchActivitySpecialMode() {
        if (!isIntentSender()) {
            activity.intent?.removeModes()
            activity.intent?.removeInfo()
            activity.finish()
        }
    }

    fun onValidateSpecialMode() {
        if (isIntentSender()) {
            activity.finish()
        } else {
            activity.intent?.removeModes()
            activity.intent?.removeInfo()
            if (specialMode != SpecialMode.DEFAULT) {
                backToTheMainAppAndFinish()
            }
        }
    }

    fun onCancelSpecialMode() {
        if (isIntentSender()) {
            activity.finish()
        } else {
            activity.intent?.removeModes()
            activity.intent?.removeInfo()
            if (specialMode != SpecialMode.DEFAULT) {
                backToTheMainAppAndFinish()
            }
        }
    }

    fun backToTheAppCaller() {
        if (isIntentSender()) {
            activity.finish()
        } else {
            backToTheMainAppAndFinish()
        }
    }

    private fun backToTheMainAppAndFinish() {
        activity.moveTaskToBack(true)
    }

    fun handleDatabaseBackPressed() {
        if (timeoutEnable) {
            TimeoutHelper.checkTimeAndLockIfTimeoutOrResetTimeout(
                activity,
                database?.loaded == true
            ) {
                executeBackPressedInternal()
            }
        } else {
            executeBackPressedInternal()
        }
    }

    private fun executeBackPressedInternal() {
        callback.onDatabaseBackPressed()
    }

    private fun checkRegister() {
        if (specialMode == SpecialMode.REGISTRATION && database?.isReadOnly != false) {
            Toast.makeText(activity, R.string.error_registration_read_only, Toast.LENGTH_LONG).show()
            activity.intent?.removeModes()
            activity.finish()
        }
    }

    fun checkTimeAndLockIfTimeoutOrResetTimeout(action: (() -> Unit)? = null) {
        TimeoutHelper.checkTimeAndLockIfTimeoutOrResetTimeout(
            activity,
            database?.loaded == true,
            action
        )
    }

    fun resetAppTimeout() {
        TimeoutHelper.checkTimeAndLockIfTimeoutOrResetTimeout(
            activity,
            database?.loaded ?: false
        )
    }

    fun lockAndExit() {
        if (database?.dataModifiedSinceLastLoading == true) {
            AlertDialog.Builder(activity)
                .setMessage(R.string.discard_changes)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.lock) { _, _ ->
                    performLockAndExit()
                }.create().show()
        } else {
            performLockAndExit()
        }
    }

    private fun performLockAndExit() {
        exitLock = true
        activity.closeDatabase(database ?: ContextualDatabase.getInstance())
        activity.sendBroadcast(Intent(LOCK_ACTION).apply {
            setPackage(activity.packageName)
        })
        activity.finish()
    }

    private fun showDatabaseActionToast(@StringRes stringId: Int) {
        Toast.makeText(activity, stringId, Toast.LENGTH_SHORT).show()
    }

    private fun startDatabasePermissionService(bundle: Bundle?, actionTask: String) {
        activity.startDatabaseService(bundle, actionTask)
    }

    private fun showPasswordEncodingDialog(databaseUri: Uri, mainCredential: MainCredential) {
        PasswordEncodingDialogFragment.getInstance(databaseUri, mainCredential)
            .show(activity.supportFragmentManager, "passwordEncodingTag")
    }

    private fun showDatabaseChangedDialog(
        previousDatabaseInfo: SnapFileDatabaseInfo,
        newDatabaseInfo: SnapFileDatabaseInfo,
        readOnlyDatabase: Boolean
    ) {
        activity.lifecycleScope.launch {
            if (databaseChangedDialogFragment == null) {
                databaseChangedDialogFragment = activity.supportFragmentManager
                    .findFragmentByTag(DATABASE_CHANGED_DIALOG_TAG) as DatabaseChangedDialogFragment?
            }
            if (databaseChangedDialogFragment == null) {
                databaseChangedDialogFragment = DatabaseChangedDialogFragment.getInstance(
                    previousDatabaseInfo,
                    newDatabaseInfo,
                    readOnlyDatabase
                )
                databaseChangedDialogFragment?.show(
                    activity.supportFragmentManager,
                    DATABASE_CHANGED_DIALOG_TAG
                )
            }
        }
    }

    private fun showDatabaseInfoReloadedDialog(fixDuplicateUuid: Boolean) {
        activity.lifecycleScope.launch {
            AlertDialog.Builder(activity)
                .setMessage(R.string.warning_database_info_reloaded)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    databaseViewModel.cancelAction()
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    databaseViewModel.reloadDatabase(fixDuplicateUuid, forceReload = true)
                }.create().show()
        }
    }

    private fun startProgressDialog() {
        activity.lifecycleScope.launch {
            if (callback.showDatabaseDialog()) {
                if (progressTaskDialogFragment == null) {
                    progressTaskDialogFragment = activity.supportFragmentManager
                        .findFragmentByTag(PROGRESS_TASK_DIALOG_TAG) as ProgressTaskDialogFragment?
                }
                if (progressTaskDialogFragment == null) {
                    progressTaskDialogFragment = ProgressTaskDialogFragment()
                    progressTaskDialogFragment?.show(
                        activity.supportFragmentManager,
                        PROGRESS_TASK_DIALOG_TAG
                    )
                }
            }
        }
    }

    private fun stopProgressDialog() {
        progressTaskDialogFragment?.dismissAllowingStateLoss()
        progressTaskDialogFragment = null
    }

    // CRUD operations facade
    fun assignMainCredential(mainCredential: MainCredential) {
        databaseViewModel.assignMainCredential(mainCredential)
    }

    fun saveDatabase() {
        databaseViewModel.saveDatabase(save = true)
    }

    fun saveDatabaseTo(uri: Uri) {
        databaseViewModel.saveDatabase(save = true, saveToUri = uri)
    }

    fun mergeDatabase() {
        databaseViewModel.mergeDatabase(save = autoSaveEnable)
    }

    fun mergeDatabaseFrom(uri: Uri, mainCredential: MainCredential) {
        databaseViewModel.mergeDatabase(autoSaveEnable, uri, mainCredential)
    }

    fun reloadDatabase() {
        databaseViewModel.reloadDatabase(fixDuplicateUuid = false)
    }

    fun createGroup(parentId: GroupId, groupInfo: GroupInfo) {
        databaseViewModel.createGroup(parentId, groupInfo, autoSaveEnable)
    }

    fun updateGroup(groupInfo: GroupInfo) {
        databaseViewModel.updateGroup(groupInfo, autoSaveEnable)
    }

    fun touchGroup(groupInfo: GroupInfo) {
        databaseViewModel.touchGroup(groupInfo)
    }

    fun createEntry(parentId: GroupId, entryInfo: EntryInfo) {
        databaseViewModel.createEntry(parentId, entryInfo, autoSaveEnable)
    }

    fun updateEntry(entryInfo: EntryInfo) {
        databaseViewModel.updateEntry(entryInfo, autoSaveEnable)
    }

    fun touchEntry(entryInfo: EntryInfo) {
        databaseViewModel.touchEntry(entryInfo)
    }

    fun restoreEntryHistory(mainEntryId: EntryId, entryHistoryPosition: Int) {
        databaseViewModel.restoreEntryHistory(mainEntryId, entryHistoryPosition, autoSaveEnable)
    }

    fun deleteEntryHistory(mainEntryId: EntryId, entryHistoryPosition: Int) {
        databaseViewModel.deleteEntryHistory(mainEntryId, entryHistoryPosition, autoSaveEnable)
    }

    fun copyNodes(newParentId: GroupId, nodesToCopy: Nodes) {
        databaseViewModel.copyNodes(newParentId, nodesToCopy, autoSaveEnable)
    }

    fun moveNodes(newParentId: GroupId, nodesToMove: Nodes) {
        databaseViewModel.moveNodes(newParentId, nodesToMove, autoSaveEnable)
    }

    fun deleteNodes(nodes: Nodes, recycleBin: Boolean = false) {
        database?.let { db ->
            nodesViewModel.requestNodesDeletion(
                database = db,
                nodes = nodes,
                recycleBinName = activity.resources.getString(R.string.recycle_bin),
                recycleBin = recycleBin
            )
        }
    }

    private fun deleteDatabaseNodes(nodes: Nodes) {
        databaseViewModel.deleteNodes(nodes, autoSaveEnable)
    }

    companion object {
        const val TAG = "DatabaseSessionCtrl"
        const val CREDENTIAL_RESULT_LAUNCHER_KEY = "com.kunzisoft.keepass.CREDENTIAL_RESULT_LAUNCHER_KEY"
        const val TIMEOUT_ENABLE_KEY = "TIMEOUT_ENABLE_KEY"
    }
}

/**
 * Extension to reset the app timeout when a view is focused or touched.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.resetAppTimeoutWhenViewTouchedOrFocused(context: Context, databaseLoaded: Boolean?) {
    try {
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    TimeoutHelper.checkTimeAndLockIfTimeoutOrResetTimeout(
                        context,
                        databaseLoaded ?: false
                    )
                }
            }
            false
        }
        setOnFocusChangeListener { _, _ ->
            TimeoutHelper.checkTimeAndLockIfTimeoutOrResetTimeout(
                context,
                databaseLoaded ?: false
            )
        }
        if (this is ViewGroup) {
            for (i in 0 until childCount) {
                getChildAt(i)?.resetAppTimeoutWhenViewTouchedOrFocused(context, databaseLoaded)
            }
        }
    } catch (e: Exception) {
        Log.e("AppTimeout", "Unable to reset app timeout", e)
    }
}
