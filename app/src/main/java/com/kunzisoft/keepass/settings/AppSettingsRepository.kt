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
import com.kunzisoft.keepass.credentialprovider.autofill.StructureParser.Companion.APPLICATION_ID_POPUP_WINDOW
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Clean domain repository abstracting user and application configuration settings.
 * Encapsulates Android SharedPreferences and provides type-safe, reactive access to settings.
 */
interface AppSettingsRepository {

    // Autofill & Credential Provider Settings
    var isAutofillSuggestionsEnabled: Boolean
    var isAutofillInlineSuggestionsEnabled: Boolean
    var isAskToSaveData: Boolean
    var isAutofillSharedToMagikeyboard: Boolean
    var isAutoSwitchToMagikeyboard: Boolean
    var applicationIdBlocklist: Set<String>
    var webDomainBlocklist: Set<String>

    /**
     * Determines whether autofill operations are allowed for the given target application or web domain.
     */
    fun isAutofillAllowed(applicationId: String?, webDomain: String?): Boolean

    // Database & Session Settings
    var defaultDatabasePath: String?
    var rememberDatabaseLocations: Boolean
    var showRecentFiles: Boolean
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

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        key?.let { _settingsChangedFlow.tryEmit(it) }
    }

    init {
        try {
            sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        } catch (_: Exception) {
            // Ignored in minimal unit test harnesses
        }
    }

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

    override var isAutofillSuggestionsEnabled: Boolean
        get() = sharedPreferences.getBoolean(
            getKey(R.string.autofill_suggestions_key),
            getDefaultBool(R.bool.autofill_suggestions_default, true)
        )
        set(value) {
            sharedPreferences.edit()?.putBoolean(getKey(R.string.autofill_suggestions_key), value)?.apply()
        }

    override var isAutofillInlineSuggestionsEnabled: Boolean
        get() = sharedPreferences.getBoolean(
            getKey(R.string.autofill_inline_suggestions_key),
            getDefaultBool(R.bool.autofill_inline_suggestions_default, true)
        )
        set(value) {
            sharedPreferences.edit()?.putBoolean(getKey(R.string.autofill_inline_suggestions_key), value)?.apply()
        }

    override var isAskToSaveData: Boolean
        get() = sharedPreferences.getBoolean(
            getKey(R.string.autofill_ask_to_save_data_key),
            getDefaultBool(R.bool.autofill_ask_to_save_data_default, false)
        )
        set(value) {
            sharedPreferences.edit()?.putBoolean(getKey(R.string.autofill_ask_to_save_data_key), value)?.apply()
        }

    override var isAutofillSharedToMagikeyboard: Boolean
        get() = sharedPreferences.getBoolean(
            getKey(R.string.autofill_share_magikeyboard_key),
            getDefaultBool(R.bool.autofill_share_magikeyboard_default, false)
        )
        set(value) {
            sharedPreferences.edit()?.putBoolean(getKey(R.string.autofill_share_magikeyboard_key), value)?.apply()
        }

    override var isAutoSwitchToMagikeyboard: Boolean
        get() = sharedPreferences.getBoolean(
            getKey(R.string.keyboard_auto_switch_key),
            getDefaultBool(R.bool.keyboard_auto_switch_default, false)
        )
        set(value) {
            sharedPreferences.edit()?.putBoolean(getKey(R.string.keyboard_auto_switch_key), value)?.apply()
        }

    override var applicationIdBlocklist: Set<String>
        get() {
            val key = getKey(R.string.autofill_application_id_blocklist_key)
            val stored = sharedPreferences.getStringSet(key, null)
            return stored ?: PreferencesUtil.getDefaultApplicationIdBlocklist(context.resources)
        }
        set(value) {
            sharedPreferences.edit()?.putStringSet(getKey(R.string.autofill_application_id_blocklist_key), value)?.apply()
        }

    override var webDomainBlocklist: Set<String>
        get() {
            val key = getKey(R.string.autofill_web_domain_blocklist_key)
            val stored = sharedPreferences.getStringSet(key, null)
            return stored ?: PreferencesUtil.getDefaultWebDomainBlocklist(context.resources)
        }
        set(value) {
            sharedPreferences.edit()?.putStringSet(getKey(R.string.autofill_web_domain_blocklist_key), value)?.apply()
        }

    override fun isAutofillAllowed(applicationId: String?, webDomain: String?): Boolean {
        if (applicationId?.contains(APPLICATION_ID_POPUP_WINDOW) == true) {
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
        }

    override var showRecentFiles: Boolean
        get() = sharedPreferences.getBoolean(
            getKey(R.string.show_recent_files_key),
            getDefaultBool(R.bool.show_recent_files_default, true)
        )
        set(value) {
            sharedPreferences.edit()?.putBoolean(getKey(R.string.show_recent_files_key), value)?.apply()
        }

    override var appTimeoutSeconds: Int
        get() {
            val key = getKey(R.string.app_timeout_key)
            return try {
                sharedPreferences.getInt(key, 300)
            } catch (_: Exception) {
                // In case it was stored as string preference
                try {
                    sharedPreferences.getString(key, "300")?.toIntOrNull() ?: 300
                } catch (_: Exception) {
                    300
                }
            }
        }
        set(value) {
            sharedPreferences.edit()?.putInt(getKey(R.string.app_timeout_key), value)?.apply()
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
