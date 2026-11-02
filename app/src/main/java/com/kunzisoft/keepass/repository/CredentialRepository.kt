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

import android.content.Context
import com.kunzisoft.keepass.credentialprovider.autofill.AutofillMatchingEngine
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.search.SearchParameters
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.Passkey
import com.kunzisoft.keepass.model.PasskeyEntryFields
import com.kunzisoft.keepass.model.SearchInfo
import com.kunzisoft.keepass.otp.OtpElement
import com.kunzisoft.keepass.otp.OtpEntryFields
import com.kunzisoft.keepass.settings.AppSettingsRepository
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.timeout.TimeoutHelper

/**
 * Clean domain repository for credential resolution, matching, Passkey extraction, and OTP token generation.
 */
interface CredentialRepository {

    /**
     * Discriminated union outcome of a credential query against the vault.
     */
    sealed class CredentialResult {
        data class Success(val entries: List<EntryInfo>) : CredentialResult()
        data object Empty : CredentialResult()
        data object Locked : CredentialResult()
    }

    /**
     * Executes intelligent credential lookup based on [SearchInfo] with ranking and heuristic fallback.
     */
    fun queryCredentials(searchInfo: SearchInfo?, context: Context? = null): CredentialResult

    /**
     * Executes Tier-1 exact search and Tier-2 heuristic fallback matching on [database]
     * using pre-built [searchParameters] and [searchInfo].
     */
    fun matchEntries(
        database: ContextualDatabase,
        searchInfo: SearchInfo,
        searchParameters: SearchParameters,
        max: Int = 1000
    ): List<EntryInfo>

    /**
     * Extracts Passkey credential info from the entry if configured.
     */
    fun getPasskey(entryInfo: EntryInfo): Passkey?

    /**
     * Extracts the OTP configuration model from the entry if present.
     */
    fun getOtpElement(entryInfo: EntryInfo): OtpElement?

    /**
     * Generates the current live OTP token string (TOTP / HOTP) for the entry, or null if unconfigured.
     */
    fun getOtpToken(entryInfo: EntryInfo): String?

    /**
     * Generates the current live OTP token string formatted with spaces.
     */
    fun getOtpTokenFormatted(entryInfo: EntryInfo): String?

    /**
     * Convenience accessor for entry username.
     */
    fun getUsername(entryInfo: EntryInfo): String?

    /**
     * Convenience accessor for entry password characters.
     */
    fun getPassword(entryInfo: EntryInfo): CharArray?

    /**
     * Convenience accessor for entry password as string.
     */
    fun getPasswordString(entryInfo: EntryInfo): String?
}

/**
 * Standard implementation of [CredentialRepository].
 */
class CredentialRepositoryImpl(
    private val vaultRepository: VaultRepository = VaultRepositoryImpl.getInstance(),
    private val appSettingsRepositoryProvider: () -> AppSettingsRepository? = { null }
) : CredentialRepository {

    override fun queryCredentials(searchInfo: SearchInfo?, context: Context?): CredentialRepository.CredentialResult {
        if (!vaultRepository.isUnlocked) {
            return CredentialRepository.CredentialResult.Locked
        }
        val db = vaultRepository.database ?: return CredentialRepository.CredentialResult.Locked

        // Check timeout if context provided
        if (context != null && !TimeoutHelper.checkTime(context)) {
            return CredentialRepository.CredentialResult.Locked
        }

        if (searchInfo == null || searchInfo.manualSelection || searchInfo.containsOnlyNullValues()) {
            return CredentialRepository.CredentialResult.Empty
        }

        val searchSubDomains = appSettingsRepositoryProvider()?.isSearchSubDomainsEnabled
            ?: (context?.let { PreferencesUtil.searchSubDomains(it) } ?: false)

        // Build SearchParameters for direct matching
        val searchParams = SearchParameters().apply {
            searchQuery = if (searchInfo.isDomainSearch && !searchInfo.webDomain.isNullOrEmpty()) {
                searchInfo.webDomain ?: ""
            } else {
                searchInfo.toString()
            }
            allowEmptyQuery = false
            searchInTags = searchInfo.isTagSearch
            tagsToSearch = searchInfo.tags
            searchInTitles = false
            searchInUsernames = false
            searchInPasswords = false
            searchInAppIds = searchInfo.isAppIdSearch
            searchInUrls = searchInfo.isDomainSearch
            searchByDomain = true
            searchBySubDomain = searchSubDomains
            searchInRelyingParty = searchInfo.isPasskeySearch
            credentialIds = searchInfo.optionsString()
            searchInNotes = false
            searchInOTP = searchInfo.isOTPSearch
            searchInOther = false
            searchInUUIDs = false
            searchInCurrentGroup = false
            searchInSearchableGroup = true
            searchInRecycleBin = false
            searchInTemplates = false
        }

        // Tier 1: Search via VaultRepository
        val rawResults = vaultRepository.search(searchParams)
        if (rawResults.isNotEmpty()) {
            val ranked = AutofillMatchingEngine.rankEntries(rawResults, searchInfo)
            return CredentialRepository.CredentialResult.Success(ranked)
        }

        // Tier 2: Heuristic alias and fuzzy matching
        val fallbackMatches = AutofillMatchingEngine.findFallbackMatches(db, searchInfo)
        if (fallbackMatches.isNotEmpty()) {
            return CredentialRepository.CredentialResult.Success(fallbackMatches)
        }

        return CredentialRepository.CredentialResult.Empty
    }

    override fun matchEntries(
        database: ContextualDatabase,
        searchInfo: SearchInfo,
        searchParameters: SearchParameters,
        max: Int
    ): List<EntryInfo> {
        val searchGroup = database.createSearchGroupInfo(
            searchParameters = searchParameters,
            max = max
        )
        if (searchGroup.numberOfSearchResults() > 0) {
            return AutofillMatchingEngine.rankEntries(
                entries = searchGroup.getSearchResults(),
                searchInfo = searchInfo
            )
        }
        return AutofillMatchingEngine.findFallbackMatches(
            database = database,
            searchInfo = searchInfo
        )
    }

    override fun getPasskey(entryInfo: EntryInfo): Passkey? {
        return entryInfo.passkey ?: PasskeyEntryFields.parseFields { id ->
            entryInfo.customFields.firstOrNull { it.name == id }?.protectedValue?.charArrayValue
        }
    }

    override fun getOtpElement(entryInfo: EntryInfo): OtpElement? {
        entryInfo.otpModel?.let { return OtpElement(it) }
        return OtpEntryFields.parseFields { id ->
            entryInfo.customFields.firstOrNull { it.name == id }?.protectedValue?.charArrayValue
        }
    }

    override fun getOtpToken(entryInfo: EntryInfo): String? {
        return getOtpElement(entryInfo)?.token?.let { String(it) }
    }

    override fun getOtpTokenFormatted(entryInfo: EntryInfo): String? {
        return getOtpElement(entryInfo)?.tokenFormatted?.let { String(it) }
    }

    override fun getUsername(entryInfo: EntryInfo): String? {
        return entryInfo.username
    }

    override fun getPassword(entryInfo: EntryInfo): CharArray? {
        return entryInfo.password
    }

    override fun getPasswordString(entryInfo: EntryInfo): String? {
        return entryInfo.password?.let { String(it) }
    }

    companion object {
        @Volatile
        private var instance: CredentialRepository? = null

        fun getInstance(
            vaultRepository: VaultRepository = VaultRepositoryImpl.getInstance(),
            appSettingsRepositoryProvider: () -> AppSettingsRepository? = { null }
        ): CredentialRepository {
            return instance ?: synchronized(this) {
                instance ?: CredentialRepositoryImpl(vaultRepository, appSettingsRepositoryProvider).also { instance = it }
            }
        }

        fun setInstanceForTest(repository: CredentialRepository?) {
            instance = repository
        }
    }
}
