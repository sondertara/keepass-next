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
package com.kunzisoft.keepass.activities.controllers

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.ChipGroup
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.GroupActivity
import com.kunzisoft.keepass.activities.fragments.SearchFragment
import com.kunzisoft.keepass.credentialprovider.EntrySelectionHelper.retrieveSearchInfo
import com.kunzisoft.keepass.database.search.SearchParameters
import com.kunzisoft.keepass.model.VaultCategory
import com.kunzisoft.keepass.viewmodels.GroupViewModel
import com.kunzisoft.keepass.viewmodels.SearchViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Controller for group search functionality: search fragment transactions,
 * search intent processing, tag/category filter chips, debouncing,
 * search history, and query parameter preparation.
 */
class GroupSearchController(
    private val activity: AppCompatActivity,
    private val searchViewModel: SearchViewModel,
    private val groupViewModel: GroupViewModel
) {

    private var debounceJob: Job? = null

    val isSearchActivated: Boolean
        get() = searchViewModel.isSearchActivated

    fun showSearch(show: Boolean = true) {
        val searchFragment =
            activity.supportFragmentManager.findFragmentByTag(SEARCH_FRAGMENT_TAG) as? SearchFragment
                ?: SearchFragment()
        activity.supportFragmentManager.beginTransaction().apply {
            setCustomAnimations(
                R.anim.slide_in_bottom,
                R.anim.slide_out_bottom,
                R.anim.slide_in_bottom,
                R.anim.slide_out_bottom
            )
            if (!searchFragment.isAdded && show) {
                add(
                    R.id.search_content_view,
                    searchFragment,
                    SEARCH_FRAGMENT_TAG
                )
            } else if (searchFragment.isAdded && !show) {
                remove(searchFragment)
            }
        }.commit()
    }

    /**
     * Debounces search bar input queries before triggering database search operations.
     */
    fun debounceQuery(
        scope: CoroutineScope,
        query: String?,
        delayMs: Long = 300L,
        onDebounced: (String?) -> Unit
    ) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(delayMs)
            onDebounced(query)
        }
    }

    fun cancelDebounce() {
        debounceJob?.cancel()
        debounceJob = null
    }

    fun setupFilterChips(chipGroup: ChipGroup?) {
        chipGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            when (checkedIds.first()) {
                R.id.chip_filter_all -> {
                    groupViewModel.setVaultCategory(VaultCategory.ALL)
                }
                R.id.chip_filter_passwords -> {
                    groupViewModel.setVaultCategory(VaultCategory.PASSWORDS)
                }
                R.id.chip_filter_totp -> {
                    groupViewModel.setVaultCategory(VaultCategory.TOTP)
                }
                R.id.chip_filter_passkeys -> {
                    groupViewModel.setVaultCategory(VaultCategory.PASSKEYS)
                }
                R.id.chip_filter_cards -> {
                    groupViewModel.setVaultCategory(VaultCategory.CREDIT_CARD)
                }
                R.id.chip_filter_notes -> {
                    groupViewModel.setVaultCategory(VaultCategory.SECURE_NOTE)
                }
                R.id.chip_filter_email -> {
                    groupViewModel.setVaultCategory(VaultCategory.EMAIL)
                }
                R.id.chip_filter_wifi -> {
                    groupViewModel.setVaultCategory(VaultCategory.WIFI)
                }
                R.id.chip_filter_id_card -> {
                    groupViewModel.setVaultCategory(VaultCategory.ID_CARD)
                }
                R.id.chip_filter_bank -> {
                    groupViewModel.setVaultCategory(VaultCategory.BANK)
                }
                R.id.chip_filter_crypto -> {
                    groupViewModel.setVaultCategory(VaultCategory.CRYPTOCURRENCY)
                }
            }
        }
    }

    /**
     * Programmatically synchronizes the chip group with a category.
     */
    fun selectCategoryChip(chipGroup: ChipGroup?, category: VaultCategory, isFlatView: Boolean = true) {
        val targetId = when (category) {
            VaultCategory.ALL -> R.id.chip_filter_all
            VaultCategory.PASSWORDS -> R.id.chip_filter_passwords
            VaultCategory.TOTP -> R.id.chip_filter_totp
            VaultCategory.PASSKEYS -> R.id.chip_filter_passkeys
            VaultCategory.CREDIT_CARD -> R.id.chip_filter_cards
            VaultCategory.SECURE_NOTE -> R.id.chip_filter_notes
            VaultCategory.EMAIL -> R.id.chip_filter_email
            VaultCategory.WIFI -> R.id.chip_filter_wifi
            VaultCategory.ID_CARD -> R.id.chip_filter_id_card
            VaultCategory.BANK -> R.id.chip_filter_bank
            VaultCategory.CRYPTOCURRENCY -> R.id.chip_filter_crypto
        }
        chipGroup?.check(targetId)
    }

    /**
     * Dynamically updates category chip visibility based on which categories actually have entries.
     */
    fun updateCategoryChipsVisibility(chipGroup: ChipGroup?, availableCategories: Set<VaultCategory>) {
        if (chipGroup == null) return
        val categoryChipMap = mapOf(
            VaultCategory.PASSWORDS to R.id.chip_filter_passwords,
            VaultCategory.TOTP to R.id.chip_filter_totp,
            VaultCategory.PASSKEYS to R.id.chip_filter_passkeys,
            VaultCategory.CREDIT_CARD to R.id.chip_filter_cards,
            VaultCategory.SECURE_NOTE to R.id.chip_filter_notes,
            VaultCategory.EMAIL to R.id.chip_filter_email,
            VaultCategory.WIFI to R.id.chip_filter_wifi,
            VaultCategory.ID_CARD to R.id.chip_filter_id_card,
            VaultCategory.BANK to R.id.chip_filter_bank,
            VaultCategory.CRYPTOCURRENCY to R.id.chip_filter_crypto
        )
        categoryChipMap.forEach { (category, chipId) ->
            chipGroup.findViewById<android.view.View>(chipId)?.visibility =
                if (availableCategories.contains(category)) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    fun manageIntent(intent: Intent?) {
        intent?.let {
            val autoSearch = it.getBooleanExtra(AUTO_SEARCH_KEY, false)
            val searchInfo = if (autoSearch) it.retrieveSearchInfo() else it.retrieveSearchInfo()
            val searchQuery = if (Intent.ACTION_SEARCH == it.action)
                it.getStringExtra(SearchManager.QUERY) else null
            if (!searchQuery.isNullOrBlank()) {
                addSearchHistory(activity, searchQuery)
            }
            searchViewModel.processSearchData(searchInfo, searchQuery)
            it.action = Intent.ACTION_DEFAULT
            it.removeExtra(SearchManager.QUERY)
        }
    }

    fun activateSearch() {
        searchViewModel.activateSearch()
    }

    fun deactivateSearch() {
        searchViewModel.deactivateSearch()
    }

    fun clearSearch() {
        searchViewModel.clearSearch()
    }

    companion object {
        const val SEARCH_FRAGMENT_TAG = "SEARCH_FRAGMENT_TAG"
        private const val AUTO_SEARCH_KEY = "AUTO_SEARCH"
        private const val PREFS_NAME = "keepassdx_search_history"
        private const val KEY_SEARCH_HISTORY = "recent_queries"
        const val MAX_HISTORY_ITEMS = 10

        /**
         * Adds a query string to persistent search history (LRU, deduplicated, capped).
         */
        fun addSearchHistory(context: Context, query: String) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentList = getSearchHistory(context).toMutableList()
            currentList.remove(trimmed)
            currentList.add(0, trimmed)
            val capped = currentList.take(MAX_HISTORY_ITEMS)
            prefs.edit().putString(KEY_SEARCH_HISTORY, capped.joinToString("\u0000")).apply()
        }

        /**
         * Retrieves the list of recent search queries.
         */
        fun getSearchHistory(context: Context): List<String> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_SEARCH_HISTORY, null) ?: return emptyList()
            return raw.split("\u0000").filter { it.isNotBlank() }
        }

        /**
         * Clears all saved search history.
         */
        fun clearSearchHistory(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_SEARCH_HISTORY).apply()
        }

        /**
         * Prepares search parameters with target query and optional scope toggles.
         */
        fun prepareSearchParameters(
            query: String?,
            baseParameters: SearchParameters = SearchParameters(),
            searchInTitles: Boolean = true,
            searchInUsernames: Boolean = true,
            searchInUrls: Boolean = true,
            searchInNotes: Boolean = true,
            searchInPasswords: Boolean = false,
            searchInTags: Boolean = true
        ): SearchParameters {
            return baseParameters.copy().apply {
                searchQuery = query ?: ""
                this.searchInTitles = searchInTitles
                this.searchInUsernames = searchInUsernames
                this.searchInUrls = searchInUrls
                this.searchInNotes = searchInNotes
                this.searchInPasswords = searchInPasswords
                this.searchInTags = searchInTags
            }
        }

        /**
         * Builds an intent configured for triggering search in GroupActivity.
         */
        fun prepareSearchIntent(
            context: Context,
            query: String?,
            autoSearch: Boolean = false
        ): Intent {
            return Intent(context, GroupActivity::class.java).apply {
                action = Intent.ACTION_SEARCH
                putExtra(SearchManager.QUERY, query)
                putExtra(AUTO_SEARCH_KEY, autoSearch)
            }
        }
    }
}
