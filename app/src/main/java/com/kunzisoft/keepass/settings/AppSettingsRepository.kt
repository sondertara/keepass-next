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
package com.kunzisoft.keepass.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.credentialprovider.autofill.StructureParser
import com.kunzisoft.keepass.timeout.TimeoutHelper
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Clean domain repository abstracting user and application configuration settings.
 * Encapsulates Android SharedPreferences and provides type-safe, reactive access to settings.
 */
interface AppSettingsRepository {

    // Autofill & Credential Provider Settings
    var isAutofillSuggestionsEnabled: Boolean
    val autofillSuggestionsEnabledFlow: StateFlow<Boolean>

    var isAutofillInlineSuggestionsEnabled: Boolean
    val autofillInlineSuggestionsEnabledFlow: StateFlow<Boolean>

    var isAskToSaveData: Boolean
    val askToSaveDataFlow: StateFlow<Boolean>

    var isAutofillSharedToMagikeyboard: Boolean
    val autofillSharedToMagikeyboardFlow: StateFlow<Boolean>

    var isAutoSwitchToMagikeyboard: Boolean
    val autoSwitchToMagikeyboardFlow: StateFlow<Boolean>

    var isSearchSubDomainsEnabled: Boolean
    val searchSubDomainsFlow: StateFlow<Boolean>

    var applicationIdBlocklist: Set<String>
    val applicationIdBlocklistFlow: StateFlow<Set<String>>

    var webDomainBlocklist: Set<String>
    val webDomainBlocklistFlow: StateFlow<Set<String>>

    /**
     * Determines whether autofill operations are allowed for the given target application or web domain.
     */
    fun isAutofillAllowed(applicationId: String?, webDomain: String?): Boolean

    // Database & Session Settings
    var defaultDatabasePath: String?

    var rememberDatabaseLocations: Boolean
    val rememberDatabaseLocationsFlow: StateFlow<Boolean>

    var showRecentFiles: Boolean
    val showRecentFilesFlow: StateFlow<Boolean>

    var appTimeoutMillis: Long
    val appTimeoutMillisFlow: StateFlow<Long>

    var appTimeoutSeconds: Int

    /**
     * Reactive stream emitting the key names of settings whenever they change.
     */
    val settingsChangedFlow: SharedFlow<String>
}

/**
 * Standard implementation of [AppSettingsRepository].
 */
class AppSettingsRepositoryImpl(
    private val context: Context,
    customPreferences: SharedPreferences? = null
) : AppSettingsRepository {

    private val sharedPreferences: SharedPreferences by lazy {
        customPreferences ?: PreferenceManager.getDefaultSharedPreferences(context)
    }

    private val _settingsChangedFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val settingsChangedFlow: SharedFlow<String> = _settingsChangedFlow.asSharedFlow()

    private fun getKey(resId: Int): String {
        return try {
            context.getString(resId)
        } catch (_: Exception) {
            resId.toString()
        }
    }

    private fun getDefaultBool(resId: Int, fallback: Boolean = false): Boolean {
        return try {
            context.resources.getBoolean(resId)
        } catch (_: Exception) {
            fallback
        }
    }

    // Reactive StateFlow backing properties
    private val _autofillSuggestionsEnabledFlow by lazy {
        MutableStateFlow(isAutofillSuggestionsEnabled)
    }
    override val autofillSuggestionsEnabledFlow: StateFlow<Boolean> by lazy {
        _autofillSuggestionsEnabledFlow.asStateFlow()
    }

    private val _autofillInlineSuggestionsEnabledFlow by lazy {
        MutableStateFlow(isAutofillInlineSuggestionsEnabled)
    }
    override val autofillInlineSuggestionsEnabledFlow: StateFlow<Boolean> by lazy {
        _autofillInlineSuggestionsEnabledFlow.asStateFlow()
    }

    private val _askToSaveDataFlow by lazy {
        MutableStateFlow(isAskToSaveData)
    }
    override val askToSaveDataFlow: StateFlow<Boolean> by lazy {
        _askToSaveDataFlow.asStateFlow()
    }

    private val _autofillSharedToMagikeyboardFlow by lazy {
        MutableStateFlow(isAutofillSharedToMagikeyboard)
    }
    override val autofillSharedToMagikeyboardFlow: StateFlow<Boolean> by lazy {
        _autofillSharedToMagikeyboardFlow.asStateFlow()
    }

    private val _autoSwitchToMagikeyboardFlow by lazy {
        MutableStateFlow(isAutoSwitchToMagikeyboard)
    }
    override val autoSwitchToMagikeyboardFlow: StateFlow<Boolean> by lazy {
        _autoSwitchToMagikeyboardFlow.asStateFlow()
    }

    private val _searchSubDomainsFlow by lazy {
        MutableStateFlow(isSearchSubDomainsEnabled)
    }
    override val searchSubDomainsFlow: StateFlow<Boolean> by lazy {
        _searchSubDomainsFlow.asStateFlow()
    }

    private val _rememberDatabaseLocationsFlow by lazy {
        MutableStateFlow(rememberDatabaseLocations)
    }
    override val rememberDatabaseLocationsFlow: StateFlow<Boolean> by lazy {
        _rememberDatabaseLocationsFlow.asStateFlow()
    }

    private val _showRecentFilesFlow by lazy {
        MutableStateFlow(showRecentFiles)
    }
    override val showRecentFilesFlow: StateFlow<Boolean> by lazy {
        _showRecentFilesFlow.asStateFlow()
    }

    private val _appTimeoutMillisFlow by lazy {
        MutableStateFlow(appTimeoutMillis)
    }
    override val appTimeoutMillisFlow: StateFlow<Long> by lazy {
        _appTimeoutMillisFlow.asStateFlow()
    }

    private val _applicationIdBlocklistFlow by lazy {
        MutableStateFlow(applicationIdBlocklist)
    }
    override val applicationIdBlocklistFlow: StateFlow<Set<String>> by lazy {
        _applicationIdBlocklistFlow.asStateFlow()
    }

    private val _webDomainBlocklistFlow by lazy {
        MutableStateFlow(webDomainBlocklist)
    }
    override val webDomainBlocklistFlow: StateFlow<Set<String>> by lazy {
        _webDomainBlocklistFlow.asStateFlow()
    }

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            getKey(R.string.autofill_suggestions_key) -> _autofillSuggestionsEnabledFlow.value = isAutofillSuggestionsEnabled
            getKey(R.string.autofill_inline_suggestions_key) -> _autofillInlineSuggestionsEnabledFlow.value = isAutofillInlineSuggestionsEnabled
            getKey(R.string.autofill_ask_to_save_data_key) -> _askToSaveDataFlow.value = isAskToSaveData
            getKey(R.string.autofill_share_magikeyboard_key) -> _autofillSharedToMagikeyboardFlow.value = isAutofillSharedToMagikeyboard
            getKey(R.string.keyboard_auto_switch_key) -> _autoSwitchToMagikeyboardFlow.value = isAutoSwitchToMagikeyboard
            getKey(R.string.subdomain_search_key) -> _searchSubDomainsFlow.value = isSearchSubDomainsEnabled
            getKey(R.string.remember_database_locations_key) -> _rememberDatabaseLocationsFlow.value = rememberDatabaseLocations
            getKey(R.string.show_recent_files_key) -> _showRecentFilesFlow.value = showRecentFiles
            getKey(R.string.app_timeout_key) -> _appTimeoutMillisFlow.value = appTimeoutMillis
            getKey(R.string.autofill_application_id_blocklist_key) -> _applicationIdBlocklistFlow.value = applicationIdBlocklist
            getKey(R.string.autofill_web_domain_blocklist_key) -> _webDomainBlocklistFlow.value = webDomainBlocklist
        }
        key?.let { _settingsChangedFlow.tryEmit(it) }
    }

    init {
        try {
            sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        } catch (_: Exception) {
            // Ignored in minimal unit test harnesses
        }
    }

    override var isAutofillSuggestionsEnabled: Boolean
        get() = sharedPreferences.getBoolean(
            getKey(R.string.autofill_suggestions_key),
            getDefaultBool(R.bool.autofill_suggestions_default, true)
        )
        set(value) {
            sharedPreferences.edit()?.putBoolean(getKey(R.string.autofill_suggestions_key), value)?.apply()
            _autofillSuggestionsEnabledFlow.value = value
        }

    override var isAutofillInlineSuggestionsEnabled: Boolean
        get() = sharedPreferences.getBoolean(
            getKey(R.string.autofill_inline_suggestions_key),
            getDefaultBool(R.bool.autofill_inline_suggestions_default, true)
        )
        set(value) {
            sharedPreferences.edit()?.putBoolean(getKey(R.string.autofill_inline_suggestions_key), value)?.apply()
            _autofillInlineSuggestionsEnabledFlow.value = value
        }

    override var isAskToSaveData: Boolean
        get() = sharedPreferences.getBoolean(
            getKey(R.string.autofill_ask_to_save_data_key),
            getDefaultBool(R.bool.autofill_ask_to_save_data_default, false)
        )
        set(value) {
            sharedPreferences.edit()?.putBoolean(getKey(R.string.autofill_ask_to_save_data_key), value)?.apply()
            _askToSaveDataFlow.value = value
        }

    override var isAutofillSharedToMagikeyboard: Boolean
        get() = sharedPreferences.getBoolean(
            getKey(R.string.autofill_share_magikeyboard_key),
            getDefaultBool(R.bool.autofill_share_magikeyboard_default, false)
        )
        set(value) {
            sharedPreferences.edit()?.putBoolean(getKey(R.string.autofill_share_magikeyboard_key), value)?.apply()
            _autofillSharedToMagikeyboardFlow.value = value
        }

    override var isAutoSwitchToMagikeyboard: Boolean
        get() = sharedPreferences.getBoolean(
            getKey(R.string.keyboard_auto_switch_key),
            getDefaultBool(R.bool.keyboard_auto_switch_default, false)
        )
        set(value) {
            sharedPreferences.edit()?.putBoolean(getKey(R.string.keyboard_auto_switch_key), value)?.apply()
            _autoSwitchToMagikeyboardFlow.value = value
        }

    override var isSearchSubDomainsEnabled: Boolean
        get() = sharedPreferences.getBoolean(
            getKey(R.string.subdomain_search_key),
            getDefaultBool(R.bool.subdomain_search_default, false)
        )
        set(value) {
            sharedPreferences.edit()?.putBoolean(getKey(R.string.subdomain_search_key), value)?.apply()
            _searchSubDomainsFlow.value = value
        }

    override var applicationIdBlocklist: Set<String>
        get() {
            val key = getKey(R.string.autofill_application_id_blocklist_key)
            val stored = sharedPreferences.getStringSet(key, null)
            return stored ?: PreferencesUtil.getDefaultApplicationIdBlocklist(context.resources)
        }
        set(value) {
            sharedPreferences.edit()?.putStringSet(getKey(R.string.autofill_application_id_blocklist_key), value)?.apply()
            _applicationIdBlocklistFlow.value = value
        }

    override var webDomainBlocklist: Set<String>
        get() {
            val key = getKey(R.string.autofill_web_domain_blocklist_key)
            val stored = sharedPreferences.getStringSet(key, null)
            return stored ?: PreferencesUtil.getDefaultWebDomainBlocklist(context.resources)
        }
        set(value) {
            sharedPreferences.edit()?.putStringSet(getKey(R.string.autofill_web_domain_blocklist_key), value)?.apply()
            _webDomainBlocklistFlow.value = value
        }

    override fun isAutofillAllowed(applicationId: String?, webDomain: String?): Boolean {
        if (applicationId?.contains(StructureParser.APPLICATION_ID_POPUP_WINDOW) == true) {
            return false
        }
        val appBlocklist = applicationIdBlocklist
        if (!applicationId.isNullOrEmpty() && appBlocklist.any { applicationId.contains(it) }) {
            return false
        }
        val domainBlocklist = webDomainBlocklist
        if (!webDomain.isNullOrEmpty() && domainBlocklist.any { webDomain.contains(it) }) {
            return false
        }
        return true
    }

    override var defaultDatabasePath: String?
        get() = sharedPreferences.getString(getKey(R.string.default_database_path_key), "")
        set(value) {
            sharedPreferences.edit()?.apply {
                if (value != null) {
                    putString(getKey(R.string.default_database_path_key), value)
                } else {
                    remove(getKey(R.string.default_database_path_key))
                }
                apply()
            }
        }

    override var rememberDatabaseLocations: Boolean
        get() = sharedPreferences.getBoolean(
            getKey(R.string.remember_database_locations_key),
            getDefaultBool(R.bool.remember_database_locations_default, true)
        )
        set(value) {
            sharedPreferences.edit()?.putBoolean(getKey(R.string.remember_database_locations_key), value)?.apply()
            _rememberDatabaseLocationsFlow.value = value
        }

    override var showRecentFiles: Boolean
        get() = sharedPreferences.getBoolean(
            getKey(R.string.show_recent_files_key),
            getDefaultBool(R.bool.show_recent_files_default, true)
        )
        set(value) {
            sharedPreferences.edit()?.putBoolean(getKey(R.string.show_recent_files_key), value)?.apply()
            _showRecentFilesFlow.value = value
        }

    override var appTimeoutMillis: Long
        get() {
            val key = getKey(R.string.app_timeout_key)
            return try {
                val str = sharedPreferences.getString(key, null)
                if (str != null) {
                    str.toLongOrNull() ?: TimeoutHelper.DEFAULT_TIMEOUT
                } else {
                    try {
                        sharedPreferences.getLong(key, TimeoutHelper.DEFAULT_TIMEOUT)
                    } catch (_: Exception) {
                        try {
                            sharedPreferences.getInt(key, TimeoutHelper.DEFAULT_TIMEOUT.toInt()).toLong()
                        } catch (_: Exception) {
                            TimeoutHelper.DEFAULT_TIMEOUT
                        }
                    }
                }
            } catch (_: Exception) {
                TimeoutHelper.DEFAULT_TIMEOUT
            }
        }
        set(value) {
            sharedPreferences.edit()?.putString(getKey(R.string.app_timeout_key), value.toString())?.apply()
            _appTimeoutMillisFlow.value = value
        }

    override var appTimeoutSeconds: Int
        get() = (appTimeoutMillis / 1000).toInt()
        set(value) {
            appTimeoutMillis = value.toLong() * 1000
        }

    companion object {
        @Volatile
        private var instance: AppSettingsRepository? = null

        fun getInstance(context: Context): AppSettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: AppSettingsRepositoryImpl(context.applicationContext ?: context).also { instance = it }
            }
        }

        fun setInstanceForTest(repository: AppSettingsRepository?) {
            instance = repository
        }
    }
}
