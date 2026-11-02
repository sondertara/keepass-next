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
package com.kunzisoft.keepass.events

import com.kunzisoft.keepass.credentialprovider.TypeMode
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.RegisterInfo
import com.kunzisoft.keepass.model.SearchInfo

/**
 * Domain events emitted during vault and credential lifecycle interactions.
 */
sealed class VaultEvent {

    /**
     * Emitted when a client (e.g. Autofill, QuickSearch) identifies a target application or domain.
     */
    data class SearchTargetIdentified(
        val searchInfo: SearchInfo,
        val typeMode: TypeMode = TypeMode.AUTOFILL
    ) : VaultEvent()

    /**
     * Emitted when matching credentials have been successfully retrieved from the unlocked vault.
     */
    data class CredentialsRetrieved(
        val entries: List<EntryInfo>,
        val typeMode: TypeMode = TypeMode.AUTOFILL
    ) : VaultEvent()

    /**
     * Emitted when an entry is captured or registered from an external source.
     */
    data class EntrySaved(
        val registerInfo: RegisterInfo
    ) : VaultEvent()
}
