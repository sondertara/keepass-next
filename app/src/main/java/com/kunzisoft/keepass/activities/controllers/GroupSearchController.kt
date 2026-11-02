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
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.ChipGroup
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.fragments.SearchFragment
import com.kunzisoft.keepass.credentialprovider.EntrySelectionHelper.retrieveAutoSearch
import com.kunzisoft.keepass.credentialprovider.EntrySelectionHelper.retrieveSearchInfo
import com.kunzisoft.keepass.model.VaultCategory
import com.kunzisoft.keepass.viewmodels.GroupViewModel
import com.kunzisoft.keepass.viewmodels.SearchViewModel

/**
 * Controller for group search functionality: search fragment transactions,
 * search intent processing, tag/category filter chips, and debouncing.
 */
class GroupSearchController(
    private val activity: AppCompatActivity,
    private val searchViewModel: SearchViewModel,
    private val groupViewModel: GroupViewModel
) {

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

    fun setupFilterChips(chipGroup: ChipGroup?) {
        chipGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            when (checkedIds.first()) {
                R.id.chip_filter_all -> {
                    groupViewModel.setFlatView(false)
                    groupViewModel.setVaultCategory(VaultCategory.ALL)
                }
                R.id.chip_flat_view -> {
                    groupViewModel.setFlatView(true)
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

    fun manageIntent(intent: Intent?) {
        intent?.let {
            val searchInfo = if (it.retrieveAutoSearch()) it.retrieveSearchInfo() else null
            val searchQuery = if (Intent.ACTION_SEARCH == it.action)
                it.getStringExtra(SearchManager.QUERY) else null
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
        const val SEARCH_FRAGMENT_TAG = "SEARCH_FRAGMENT_TAGE"
    }
}
