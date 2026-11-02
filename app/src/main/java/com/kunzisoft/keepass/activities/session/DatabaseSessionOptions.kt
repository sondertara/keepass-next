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

/**
 * Defines the explicit architectural role of a database-aware Activity.
 */
enum class SessionRole {
    /**
     * Pre-unlock or credential-provider launcher screens (e.g. FileDatabaseSelectActivity,
     * MainCredentialActivity, AutofillLauncherActivity) that can operate while the vault is locked.
     */
    PRE_UNLOCK,

    /**
     * Post-unlock vault screens (e.g. GroupActivity, EntryActivity, EntryEditActivity)
     * that require an active unlocked vault session and enforce lock/timeout guards.
     */
    VAULT_PROTECTED
}

/**
 * Configuration options for DatabaseSessionController.
 */
data class DatabaseSessionOptions(
    val enableTimeout: Boolean = true,
    val manageDatabaseInfo: Boolean = true,
    val enableSpecialMode: Boolean = true,
    val timeoutRetrievalGuard: Boolean = false,
    val finishActivityIfReloadRequested: Boolean = false,
    val role: SessionRole = if (enableTimeout) SessionRole.VAULT_PROTECTED else SessionRole.PRE_UNLOCK
) {
    companion object {
        fun preUnlock(
            enableSpecialMode: Boolean = true,
            manageDatabaseInfo: Boolean = false
        ): DatabaseSessionOptions = DatabaseSessionOptions(
            enableTimeout = false,
            manageDatabaseInfo = manageDatabaseInfo,
            enableSpecialMode = enableSpecialMode,
            role = SessionRole.PRE_UNLOCK
        )

        fun vaultProtected(
            manageDatabaseInfo: Boolean = true,
            enableSpecialMode: Boolean = true,
            timeoutRetrievalGuard: Boolean = false,
            finishActivityIfReloadRequested: Boolean = false
        ): DatabaseSessionOptions = DatabaseSessionOptions(
            enableTimeout = true,
            manageDatabaseInfo = manageDatabaseInfo,
            enableSpecialMode = enableSpecialMode,
            timeoutRetrievalGuard = timeoutRetrievalGuard,
            finishActivityIfReloadRequested = finishActivityIfReloadRequested,
            role = SessionRole.VAULT_PROTECTED
        )
    }
}
