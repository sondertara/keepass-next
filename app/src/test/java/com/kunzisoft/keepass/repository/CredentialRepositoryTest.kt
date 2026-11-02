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
import com.kunzisoft.keepass.database.element.EntryId
import com.kunzisoft.keepass.database.element.GroupId
import com.kunzisoft.keepass.database.element.node.EmptyNodeFilter
import com.kunzisoft.keepass.database.element.node.NodeFilter
import com.kunzisoft.keepass.database.element.node.NodeIdUUID
import com.kunzisoft.keepass.database.search.SearchParameters
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.GroupInfo
import com.kunzisoft.keepass.model.Passkey
import com.kunzisoft.keepass.model.SearchInfo
import com.kunzisoft.keepass.model.SortedNodeInfo
import com.kunzisoft.keepass.settings.AppSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRepositoryTest {

    private class FakeVaultRepository(
        override var isUnlocked: Boolean = false,
        override var database: ContextualDatabase? = null,
        var searchResults: List<EntryInfo> = emptyList()
    ) : VaultRepository {
        override val dataModifiedFlow: StateFlow<Boolean>? = null
        override fun getRootGroup(): GroupInfo? = null
        override fun getGroup(groupId: GroupId): GroupInfo? = null
        override fun getEntry(entryId: EntryId, raw: Boolean): EntryInfo? = null
        override fun getChildren(
            groupId: GroupId,
            recursiveNumberOfEntries: Boolean,
            nodeFilter: NodeFilter
        ): List<SortedNodeInfo> = emptyList()
        override fun search(parameters: SearchParameters, max: Int): List<EntryInfo> = searchResults
        override suspend fun addEntry(entryInfo: EntryInfo, parentGroupId: GroupId): Result<EntryId> = Result.success(entryInfo.nodeId)
        override suspend fun updateEntry(entryInfo: EntryInfo): Result<Unit> = Result.success(Unit)
        override suspend fun deleteEntry(entryId: EntryId): Result<Unit> = Result.success(Unit)
        override suspend fun addGroup(groupInfo: GroupInfo, parentGroupId: GroupId): Result<GroupId> = Result.success(groupInfo.nodeId)
        override suspend fun updateGroup(groupInfo: GroupInfo): Result<Unit> = Result.success(Unit)
        override suspend fun deleteGroup(groupId: GroupId): Result<Unit> = Result.success(Unit)
    }

    private class FakeAppSettingsRepository(
        override var isSearchSubDomainsEnabled: Boolean = false
    ) : AppSettingsRepository {
        override var isAutofillSuggestionsEnabled: Boolean = true
        override val autofillSuggestionsEnabledFlow: StateFlow<Boolean> = MutableStateFlow(true)
        override var isAutofillInlineSuggestionsEnabled: Boolean = true
        override val autofillInlineSuggestionsEnabledFlow: StateFlow<Boolean> = MutableStateFlow(true)
        override var isAskToSaveData: Boolean = false
        override val askToSaveDataFlow: StateFlow<Boolean> = MutableStateFlow(false)
        override var isAutofillSharedToMagikeyboard: Boolean = false
        override val autofillSharedToMagikeyboardFlow: StateFlow<Boolean> = MutableStateFlow(false)
        override var isAutoSwitchToMagikeyboard: Boolean = false
        override val autoSwitchToMagikeyboardFlow: StateFlow<Boolean> = MutableStateFlow(false)
        override val searchSubDomainsFlow: StateFlow<Boolean> = MutableStateFlow(isSearchSubDomainsEnabled)
        override var applicationIdBlocklist: Set<String> = emptySet()
        override val applicationIdBlocklistFlow: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override var webDomainBlocklist: Set<String> = emptySet()
        override val webDomainBlocklistFlow: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override fun isAutofillAllowed(applicationId: String?, webDomain: String?): Boolean = true
        override var defaultDatabasePath: String? = null
        override var rememberDatabaseLocations: Boolean = true
        override val rememberDatabaseLocationsFlow: StateFlow<Boolean> = MutableStateFlow(true)
        override var showRecentFiles: Boolean = true
        override val showRecentFilesFlow: StateFlow<Boolean> = MutableStateFlow(true)
        override var appTimeoutMillis: Long = 300000L
        override val appTimeoutMillisFlow: StateFlow<Long> = MutableStateFlow(300000L)
        override var appTimeoutSeconds: Int = 300
        override val settingsChangedFlow: SharedFlow<String> get() = throw UnsupportedOperationException()
    }

    @After
    fun tearDown() {
        CredentialRepositoryImpl.setInstanceForTest(null)
    }

    @Test
    fun singletonInstance_canBeOverriddenAndReset() {
        CredentialRepositoryImpl.setInstanceForTest(null)
        val defaultRepo = CredentialRepositoryImpl.getInstance(FakeVaultRepository())
        assertNotNull(defaultRepo)

        val customRepo = CredentialRepositoryImpl(FakeVaultRepository())
        CredentialRepositoryImpl.setInstanceForTest(customRepo)
        assertSame(customRepo, CredentialRepositoryImpl.getInstance())
    }

    @Test
    fun queryCredentials_vaultLocked_returnsLocked() {
        val fakeVault = FakeVaultRepository(isUnlocked = false)
        val repo = CredentialRepositoryImpl(fakeVault)

        val searchInfo = SearchInfo().apply {
            applicationId = "com.example.app"
        }
        val result = repo.queryCredentials(searchInfo)
        assertTrue(result is CredentialRepository.CredentialResult.Locked)
    }

    @Test
    fun queryCredentials_nullOrEmptySearch_returnsEmpty() {
        val fakeDb = ContextualDatabase.getInstance().apply { loaded = true }
        val fakeVault = FakeVaultRepository(isUnlocked = true, database = fakeDb)
        val repo = CredentialRepositoryImpl(fakeVault)

        // null SearchInfo
        assertTrue(repo.queryCredentials(null) is CredentialRepository.CredentialResult.Empty)

        // manual selection enabled
        val manualSearch = SearchInfo().apply {
            applicationId = "com.example.app"
            manualSelection = true
        }
        assertTrue(repo.queryCredentials(manualSearch) is CredentialRepository.CredentialResult.Empty)

        // only null values
        val emptySearch = SearchInfo()
        assertTrue(repo.queryCredentials(emptySearch) is CredentialRepository.CredentialResult.Empty)
    }

    @Test
    fun queryCredentials_vaultUnlocked_noResults_returnsEmpty() {
        val fakeDb = ContextualDatabase.getInstance().apply { loaded = true }
        val fakeVault = FakeVaultRepository(
            isUnlocked = true,
            database = fakeDb,
            searchResults = emptyList()
        )
        val repo = CredentialRepositoryImpl(fakeVault)

        val searchInfo = SearchInfo().apply {
            applicationId = "com.unmatched.unknown"
        }
        val result = repo.queryCredentials(searchInfo)
        assertTrue(result is CredentialRepository.CredentialResult.Empty)
    }

    @Test
    fun queryCredentials_vaultUnlocked_hasResults_returnsSuccessRanked() {
        val fakeDb = ContextualDatabase.getInstance().apply { loaded = true }
        val entry1 = EntryInfo().apply {
            nodeId = NodeIdUUID()
            title = "My App"
            username = "alice"
            url = "https://example.com"
        }
        val entry2 = EntryInfo().apply {
            nodeId = NodeIdUUID()
            title = "Another App"
            username = "bob"
        }

        val fakeVault = FakeVaultRepository(
            isUnlocked = true,
            database = fakeDb,
            searchResults = listOf(entry1, entry2)
        )
        val repo = CredentialRepositoryImpl(fakeVault)

        val searchInfo = SearchInfo().apply {
            webDomain = "example.com"
        }
        val result = repo.queryCredentials(searchInfo)
        assertTrue(result is CredentialRepository.CredentialResult.Success)
        val success = result as CredentialRepository.CredentialResult.Success
        assertEquals(2, success.entries.size)
    }

    @Test
    fun queryCredentials_respectsSearchSubDomainsSetting() {
        var observedSearchSubDomain = false
        val fakeDb = ContextualDatabase.getInstance().apply { loaded = true }
        val fakeVault = object : VaultRepository {
            override val isUnlocked: Boolean = true
            override val database: ContextualDatabase = fakeDb
            override val dataModifiedFlow: StateFlow<Boolean>? = null
            override fun getRootGroup(): GroupInfo? = null
            override fun getGroup(groupId: GroupId): GroupInfo? = null
            override fun getEntry(entryId: EntryId, raw: Boolean): EntryInfo? = null
            override fun getChildren(groupId: GroupId, recursiveNumberOfEntries: Boolean, nodeFilter: NodeFilter): List<SortedNodeInfo> = emptyList()
            override fun search(parameters: SearchParameters, max: Int): List<EntryInfo> {
                observedSearchSubDomain = parameters.searchBySubDomain
                return listOf(EntryInfo())
            }
            override suspend fun addEntry(entryInfo: EntryInfo, parentGroupId: GroupId): Result<EntryId> = Result.success(entryInfo.nodeId)
            override suspend fun updateEntry(entryInfo: EntryInfo): Result<Unit> = Result.success(Unit)
            override suspend fun deleteEntry(entryId: EntryId): Result<Unit> = Result.success(Unit)
            override suspend fun addGroup(groupInfo: GroupInfo, parentGroupId: GroupId): Result<GroupId> = Result.success(groupInfo.nodeId)
            override suspend fun updateGroup(groupInfo: GroupInfo): Result<Unit> = Result.success(Unit)
            override suspend fun deleteGroup(groupId: GroupId): Result<Unit> = Result.success(Unit)
        }

        val fakeSettings = FakeAppSettingsRepository(isSearchSubDomainsEnabled = true)
        val repo = CredentialRepositoryImpl(fakeVault) { fakeSettings }

        val searchInfo = SearchInfo().apply { webDomain = "login.example.com" }
        repo.queryCredentials(searchInfo)

        assertTrue(observedSearchSubDomain)
    }

    @Test
    fun fieldAccessors_usernamePassword_extractsCorrectly() {
        val repo = CredentialRepositoryImpl(FakeVaultRepository())
        val entry = EntryInfo().apply {
            username = "charlie"
            password = "SecretPassword123".toCharArray()
        }

        assertEquals("charlie", repo.getUsername(entry))
        assertEquals("SecretPassword123", repo.getPasswordString(entry))
        assertTrue("SecretPassword123".toCharArray().contentEquals(repo.getPassword(entry)))
    }

    @Test
    fun passkeyExtraction_fromEntry_returnsExpectedPasskey() {
        val repo = CredentialRepositoryImpl(FakeVaultRepository())
        val entryWithoutPasskey = EntryInfo()
        assertNull(repo.getPasskey(entryWithoutPasskey))

        val passkey = Passkey(
            "dave",
            "dummy-pem".toCharArray(),
            "cred-123",
            "handle-123",
            "example.com",
            true,
            true
        )
        val entryWithPasskey = EntryInfo().apply {
            this.passkey = passkey
        }

        val extracted = repo.getPasskey(entryWithPasskey)
        assertNotNull(extracted)
        assertEquals("dave", extracted?.username)
        assertEquals("cred-123", extracted?.credentialId)
        assertEquals("example.com", extracted?.relyingParty)
    }

    @Test
    fun otpExtraction_entryWithoutOtp_returnsNull() {
        val repo = CredentialRepositoryImpl(FakeVaultRepository())
        val plainEntry = EntryInfo()

        assertNull(repo.getOtpElement(plainEntry))
        assertNull(repo.getOtpToken(plainEntry))
        assertNull(repo.getOtpTokenFormatted(plainEntry))
    }
}
