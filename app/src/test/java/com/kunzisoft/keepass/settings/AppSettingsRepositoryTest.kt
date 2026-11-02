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
import android.content.res.Resources
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.credentialprovider.autofill.StructureParser
import com.kunzisoft.keepass.timeout.TimeoutHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppSettingsRepositoryTest {

    private class FakeEditor(
        private val data: MutableMap<String, Any?>,
        private val listeners: List<SharedPreferences.OnSharedPreferenceChangeListener>,
        private val prefs: SharedPreferences
    ) : SharedPreferences.Editor {
        private val staging = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            staging[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            staging[key] = value
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            staging[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            staging[key] = value
            return this
        }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            staging[key] = values?.toSet()
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removed.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            staging.clear()
            data.clear()
            return this
        }

        override fun apply() {
            commit()
        }

        override fun commit(): Boolean {
            removed.forEach { data.remove(it) }
            val modifiedKeys = HashSet<String>()
            modifiedKeys.addAll(staging.keys)
            modifiedKeys.addAll(removed)
            data.putAll(staging)
            modifiedKeys.forEach { key ->
                listeners.forEach { it.onSharedPreferenceChanged(prefs, key) }
            }
            return true
        }
    }

    private class FakeSharedPreferences : SharedPreferences {
        val data = mutableMapOf<String, Any?>()
        val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getString(key: String, defValue: String?): String? {
            return (data[key] as? String) ?: defValue
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            return (data[key] as? Boolean) ?: defValue
        }

        override fun getInt(key: String, defValue: Int): Int {
            return (data[key] as? Int) ?: defValue
        }

        override fun getLong(key: String, defValue: Long): Long {
            return (data[key] as? Long) ?: defValue
        }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
            return (data[key] as? Set<String>) ?: defValues
        }

        override fun getAll(): Map<String, *> = data

        override fun edit(): SharedPreferences.Editor = FakeEditor(data, listeners, this)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.remove(listener)
        }
    }

    private class FakeContext(private val prefs: SharedPreferences) : Context {
        override fun getApplicationContext(): Context = this
        override fun getString(resId: Int): String = "key_$resId"
        override fun getResources(): Resources = Resources()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var fakeContext: FakeContext
    private lateinit var repository: AppSettingsRepository

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        fakeContext = FakeContext(fakePrefs)
        repository = AppSettingsRepositoryImpl(fakeContext, fakePrefs)
        AppSettingsRepositoryImpl.setInstanceForTest(repository)
    }

    @After
    fun tearDown() {
        AppSettingsRepositoryImpl.setInstanceForTest(null)
    }

    @Test
    fun singletonInstance_canBeOverriddenAndReset() {
        assertSame(repository, AppSettingsRepositoryImpl.getInstance(fakeContext))
        AppSettingsRepositoryImpl.setInstanceForTest(null)
        val newInstance = AppSettingsRepositoryImpl.getInstance(fakeContext)
        assertNotNull(newInstance)
    }

    @Test
    fun isAutofillAllowed_popupWindow_alwaysDisallowed() {
        assertFalse(repository.isAutofillAllowed("${StructureParser.APPLICATION_ID_POPUP_WINDOW}android", "example.com"))
        assertFalse(repository.isAutofillAllowed(StructureParser.APPLICATION_ID_POPUP_WINDOW, null))
    }

    @Test
    fun isAutofillAllowed_blocklistedAppId_isDisallowed() {
        repository.applicationIdBlocklist = setOf("com.blocked.banking", "org.secret.app")

        assertFalse(repository.isAutofillAllowed("com.blocked.banking", "example.com"))
        assertFalse(repository.isAutofillAllowed("org.secret.app.submodule", null))
        assertTrue(repository.isAutofillAllowed("com.allowed.music", "example.com"))
    }

    @Test
    fun isAutofillAllowed_blocklistedWebDomain_isDisallowed() {
        repository.webDomainBlocklist = setOf("phishing.site", "malware.example")

        assertFalse(repository.isAutofillAllowed("com.browser", "https://phishing.site/login"))
        assertFalse(repository.isAutofillAllowed(null, "malware.example"))
        assertTrue(repository.isAutofillAllowed("com.browser", "https://safe.example.com"))
    }

    @Test
    fun isAutofillAllowed_legitimateAppAndDomain_isAllowed() {
        repository.applicationIdBlocklist = setOf("com.blocked")
        repository.webDomainBlocklist = setOf("blocked.com")

        assertTrue(repository.isAutofillAllowed("com.good.app", "good.com"))
        assertTrue(repository.isAutofillAllowed("com.good.app", null))
        assertTrue(repository.isAutofillAllowed(null, "good.com"))
    }

    @Test
    fun preferencesGettersAndSetters_updateAndRetrieveValues() {
        repository.isAutofillSuggestionsEnabled = false
        assertFalse(repository.isAutofillSuggestionsEnabled)

        repository.isAutofillInlineSuggestionsEnabled = false
        assertFalse(repository.isAutofillInlineSuggestionsEnabled)

        repository.isAskToSaveData = true
        assertTrue(repository.isAskToSaveData)

        repository.isAutofillSharedToMagikeyboard = true
        assertTrue(repository.isAutofillSharedToMagikeyboard)

        repository.isAutoSwitchToMagikeyboard = true
        assertTrue(repository.isAutoSwitchToMagikeyboard)

        repository.defaultDatabasePath = "/storage/emulated/0/passwords.kdbx"
        assertEquals("/storage/emulated/0/passwords.kdbx", repository.defaultDatabasePath)

        repository.defaultDatabasePath = null
        assertEquals("", repository.defaultDatabasePath)

        repository.rememberDatabaseLocations = false
        assertFalse(repository.rememberDatabaseLocations)

        repository.showRecentFiles = false
        assertFalse(repository.showRecentFiles)

        repository.appTimeoutSeconds = 900
        assertEquals(900, repository.appTimeoutSeconds)
    }

    @Test
    fun settingsChangedFlow_emitsKeyOnChange() = runBlocking {
        val emittedKey = withTimeout(2000) {
            val job = launch {
                repository.isAskToSaveData = true
            }
            val key = repository.settingsChangedFlow.first()
            job.join()
            key
        }

        assertTrue(emittedKey.startsWith("key_"))
    }

    @Test
    fun appTimeoutMillis_handlesStringSerializationAndFallback() {
        // Default when not set
        assertEquals(TimeoutHelper.DEFAULT_TIMEOUT, repository.appTimeoutMillis)
        assertEquals(TimeoutHelper.DEFAULT_TIMEOUT, repository.appTimeoutMillisFlow.value)

        // Set valid millisecond value
        repository.appTimeoutMillis = 60000L
        assertEquals(60000L, repository.appTimeoutMillis)
        assertEquals(60000L, repository.appTimeoutMillisFlow.value)
        assertEquals(60, repository.appTimeoutSeconds)
        // Verify underlying storage is string format for Android ListPreference compatibility
        assertEquals("60000", fakePrefs.getString("key_${R.string.app_timeout_key}", null))

        // Set via appTimeoutSeconds
        repository.appTimeoutSeconds = 120
        assertEquals(120, repository.appTimeoutSeconds)
        assertEquals(120000L, repository.appTimeoutMillis)
        assertEquals(120000L, repository.appTimeoutMillisFlow.value)

        // Corrupt or non-numeric string stored in prefs falls back gracefully
        fakePrefs.edit().putString("key_${R.string.app_timeout_key}", "not_a_valid_number").commit()
        assertEquals(TimeoutHelper.DEFAULT_TIMEOUT, repository.appTimeoutMillis)
    }

    @Test
    fun searchSubDomains_updatesPropertyAndFlow() {
        assertEquals(false, repository.isSearchSubDomainsEnabled)
        assertEquals(false, repository.searchSubDomainsFlow.value)

        repository.isSearchSubDomainsEnabled = true
        assertEquals(true, repository.isSearchSubDomainsEnabled)
        assertEquals(true, repository.searchSubDomainsFlow.value)
    }

    @Test
    fun reactiveStateFlows_updateWhenPropertiesOrPreferencesChange() {
        repository.isAutofillSuggestionsEnabled = false
        assertEquals(false, repository.autofillSuggestionsEnabledFlow.value)

        repository.isAutofillInlineSuggestionsEnabled = false
        assertEquals(false, repository.autofillInlineSuggestionsEnabledFlow.value)

        repository.isAskToSaveData = true
        assertEquals(true, repository.askToSaveDataFlow.value)

        repository.isAutofillSharedToMagikeyboard = true
        assertEquals(true, repository.autofillSharedToMagikeyboardFlow.value)

        repository.isAutoSwitchToMagikeyboard = true
        assertEquals(true, repository.autoSwitchToMagikeyboardFlow.value)

        repository.rememberDatabaseLocations = false
        assertEquals(false, repository.rememberDatabaseLocationsFlow.value)

        repository.showRecentFiles = false
        assertEquals(false, repository.showRecentFilesFlow.value)

        val appBlocklist = setOf("com.blocked.test")
        repository.applicationIdBlocklist = appBlocklist
        assertEquals(appBlocklist, repository.applicationIdBlocklistFlow.value)

        val domainBlocklist = setOf("blocked.test.org")
        repository.webDomainBlocklist = domainBlocklist
        assertEquals(domainBlocklist, repository.webDomainBlocklistFlow.value)
    }
}
