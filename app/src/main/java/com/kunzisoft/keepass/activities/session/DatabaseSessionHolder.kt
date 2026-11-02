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

import android.net.Uri
import com.kunzisoft.keepass.credentialprovider.SpecialMode
import com.kunzisoft.keepass.credentialprovider.TypeMode
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.MainCredential
import com.kunzisoft.keepass.database.element.EntryId
import com.kunzisoft.keepass.database.element.GroupId
import com.kunzisoft.keepass.database.element.node.Nodes
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.GroupInfo
import com.kunzisoft.keepass.viewmodels.DatabaseViewModel

/**
 * Interface providing convenience accessors and CRUD shortcuts that delegate
 * to the composed DatabaseSessionController.
 */
interface DatabaseSessionHolder : DatabaseSessionCallback {

    val sessionController: DatabaseSessionController

    val mDatabase: ContextualDatabase?
        get() = sessionController.database

    val mDatabaseViewModel: DatabaseViewModel
        get() = sessionController.databaseViewModel

    val mCredentialActivityResultLauncher: DatabaseSessionController.CredentialActivityResultLauncher
        get() = sessionController.credentialActivityResultLauncher

    val mSpecialMode: SpecialMode
        get() = sessionController.specialMode

    val mTypeMode: TypeMode
        get() = sessionController.typeMode

    var mTimeoutEnable: Boolean
        get() = sessionController.timeoutEnable
        set(value) {
            sessionController.timeoutEnable = value
        }

    val mDatabaseAllowUserVerification: Boolean
        get() = sessionController.databaseAllowUserVerification

    fun assignMainCredential(mainCredential: MainCredential) =
        sessionController.assignMainCredential(mainCredential)

    fun saveDatabase() = sessionController.saveDatabase()

    fun saveDatabaseTo(uri: Uri) = sessionController.saveDatabaseTo(uri)

    fun mergeDatabase() = sessionController.mergeDatabase()

    fun mergeDatabaseFrom(uri: Uri, mainCredential: MainCredential) =
        sessionController.mergeDatabaseFrom(uri, mainCredential)

    fun reloadDatabase() = sessionController.reloadDatabase()

    fun createGroup(parentId: GroupId, groupInfo: GroupInfo) =
        sessionController.createGroup(parentId, groupInfo)

    fun updateGroup(groupInfo: GroupInfo) =
        sessionController.updateGroup(groupInfo)

    fun touchGroup(groupInfo: GroupInfo) =
        sessionController.touchGroup(groupInfo)

    fun createEntry(parentId: GroupId, entryInfo: EntryInfo) =
        sessionController.createEntry(parentId, entryInfo)

    fun updateEntry(entryInfo: EntryInfo) =
        sessionController.updateEntry(entryInfo)

    fun touchEntry(entryInfo: EntryInfo) =
        sessionController.touchEntry(entryInfo)

    fun restoreEntryHistory(mainEntryId: EntryId, entryHistoryPosition: Int) =
        sessionController.restoreEntryHistory(mainEntryId, entryHistoryPosition)

    fun deleteEntryHistory(mainEntryId: EntryId, entryHistoryPosition: Int) =
        sessionController.deleteEntryHistory(mainEntryId, entryHistoryPosition)

    fun copyNodes(newParentId: GroupId, nodesToCopy: Nodes) =
        sessionController.copyNodes(newParentId, nodesToCopy)

    fun moveNodes(newParentId: GroupId, nodesToMove: Nodes) =
        sessionController.moveNodes(newParentId, nodesToMove)

    fun deleteNodes(nodes: Nodes, recycleBin: Boolean = false) =
        sessionController.deleteNodes(nodes, recycleBin)

    fun lockAndExit() = sessionController.lockAndExit()

    fun resetAppTimeout() = sessionController.resetAppTimeout()

    fun checkTimeAndLockIfTimeoutOrResetTimeout(action: (() -> Unit)? = null) =
        sessionController.checkTimeAndLockIfTimeoutOrResetTimeout(action)

    fun onCancelSpecialMode() = sessionController.onCancelSpecialMode()

    fun onValidateSpecialMode() = sessionController.onValidateSpecialMode()

    fun onLaunchActivitySpecialMode() = sessionController.onLaunchActivitySpecialMode()

    fun isIntentSender(): Boolean = sessionController.isIntentSender()

    fun backToTheAppCaller() = sessionController.backToTheAppCaller()
}
