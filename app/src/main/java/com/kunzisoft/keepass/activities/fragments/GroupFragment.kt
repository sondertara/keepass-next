/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
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
package com.kunzisoft.keepass.activities.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.dialogs.PasskeyDetailsDialogFragment
import com.kunzisoft.keepass.adapters.NodesAdapter
import com.kunzisoft.keepass.credentialprovider.EntrySelectionHelper.retrieveSpecialMode
import com.kunzisoft.keepass.credentialprovider.SpecialMode
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.element.SortNodeEnum
import com.kunzisoft.keepass.model.Passkey
import com.kunzisoft.keepass.model.SearchGroupInfo
import com.kunzisoft.keepass.model.SortedEntryInfo
import com.kunzisoft.keepass.model.SortedNodeInfo
import com.kunzisoft.keepass.model.VaultCategory
import com.kunzisoft.keepass.timeout.timeoutCopyToClipboard
import com.kunzisoft.keepass.viewmodels.GroupViewModel
import kotlinx.coroutines.launch

class GroupFragment : DatabaseFragment() {

    private var mNodesRecyclerView: RecyclerView? = null
    private var mEmptyVaultContainer: View? = null
    private var mLayoutManager: LinearLayoutManager? = null

    private var mAdapter: NodesAdapter? = null

    private val mGroupViewModel: GroupViewModel by activityViewModels()

    private var specialMode: SpecialMode = SpecialMode.DEFAULT

    private var mRecycleViewScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            super.onScrollStateChanged(recyclerView, newState)
            if (newState == SCROLL_STATE_IDLE) {
                val firstPosition = mLayoutManager?.findFirstVisibleItemPosition() ?: 0
                val firstView = mLayoutManager?.findViewByPosition(firstPosition)
                val offset = firstView?.top ?: 0
                mGroupViewModel.assignPosition(firstPosition, offset)
            }
        }
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)
            mGroupViewModel.scrollTo(dy)
        }
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        context?.let { context ->
            mAdapter = NodesAdapter(
                context,
                database.iconDrawableFactory,
                SortNodeEnum.SortDatabaseParameters(
                    recycleBinEnabled = database.isRecycleBinEnabled,
                    recycleBinId = database.recycleBin?.nodeId
                )
            ).apply {
                setOnNodeClickListener(object : NodesAdapter.NodeClickCallback {
                    override fun onNodeClick(node: SortedNodeInfo) {
                        mGroupViewModel.performNodeClick(node)
                    }
                    override fun onNodeLongClick(node: SortedNodeInfo): Boolean {
                        mGroupViewModel.performLongNodeClick(node)
                        return true
                    }
                    override fun onPasskeyClick(node: SortedNodeInfo, passkey: Passkey) {
                        PasskeyDetailsDialogFragment.newInstance(passkey)
                            .show(parentFragmentManager, PasskeyDetailsDialogFragment.TAG)
                    }
                    override fun onNodeMenuClick(node: SortedNodeInfo, anchorView: View) {
                        showNodePopupMenu(node, anchorView)
                    }
                })
                setActionNodes(mGroupViewModel.actionsNodes.value)
            }
            mNodesRecyclerView?.adapter = mAdapter
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        // To apply theme
        return inflater.inflate(R.layout.fragment_nodes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mNodesRecyclerView = view.findViewById(R.id.nodes_list)
        mEmptyVaultContainer = view.findViewById(R.id.empty_vault_container)

        mLayoutManager = LinearLayoutManager(context)
        mNodesRecyclerView?.apply {
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            layoutManager = mLayoutManager
            adapter = mAdapter
        }
        resetAppTimeoutWhenViewFocusedOrChanged(view)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mGroupViewModel.groupUIState.collect { groupUIState ->
                        if (!groupUIState.loaded) return@collect
                        try {
                            groupUIState.group?.let { currentGroup ->
                                val isSearchInfo = currentGroup is SearchGroupInfo
                                        || mGroupViewModel.isFlatView.value
                                        || mGroupViewModel.vaultCategory.value != VaultCategory.ALL
                                val children = groupUIState.children
                                if (children != null) {
                                    mAdapter?.rebuildList(
                                        nodes = children,
                                        isSearch = isSearchInfo
                                    )
                                }
                                mEmptyVaultContainer?.visibility = if (children.isNullOrEmpty()) View.VISIBLE else View.GONE
                                // Direct action node selection after rebuild
                                mAdapter?.setActionNodes(mGroupViewModel.actionsNodes.value)
                            }
                        } catch (e:Exception) {
                            Log.e(TAG, "Unable to rebuild the list", e)
                        }
                    }
                }
                launch {
                    mGroupViewModel.viewEvent.collect { event ->
                        when (event) {
                            is GroupViewModel.GroupEvent.ShowPosition -> {
                                mNodesRecyclerView?.post {
                                    mLayoutManager?.scrollToPositionWithOffset(event.position, event.offset)
                                }
                            }
                            is GroupViewModel.GroupEvent.RemoveNodeAction -> {
                                mAdapter?.unselectActionNodes()
                            }
                            is GroupViewModel.GroupEvent.SortSelected -> {
                                // Tell the main group adapter to refresh its list
                                try {
                                    mAdapter?.notifyChangeSort(
                                        sortNodeEnum = event.sortNode.sortNodeEnum,
                                        sortNodeParameters = event.sortNode.sortNodeParameters,
                                        sortDatabaseParameters = event.sortNode.sortDatabaseParameters
                                    )
                                    mGroupViewModel.loadGroup()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Unable to sort the list", e)
                                }
                            }
                            else -> {}
                        }
                    }
                }
                launch {
                    mGroupViewModel.actionsNodes.collect { actionsNodes ->
                        mAdapter?.setActionNodes(actionsNodes)
                    }
                }
            }
        }
    }

    private fun showNodePopupMenu(node: SortedNodeInfo, anchorView: View) {
        val context = anchorView.context
        val popup = androidx.appcompat.widget.PopupMenu(context, anchorView)
        var order = 0
        if (node is SortedEntryInfo) {
            if (node.username.isNotBlank()) {
                popup.menu.add(0, 10, order++, R.string.copy_username)
            }
            if (node.password.isNotEmpty()) {
                popup.menu.add(0, 11, order++, R.string.copy_password)
            }
        }
        popup.menu.add(0, 1, order++, R.string.menu_edit)
        popup.menu.add(0, 2, order++, R.string.menu_delete)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                10 -> {
                    if (node is SortedEntryInfo) {
                        context.timeoutCopyToClipboard(
                            context.getString(R.string.username),
                            node.username,
                            sensitive = false
                        )
                    }
                    true
                }
                11 -> {
                    if (node is SortedEntryInfo) {
                        context.timeoutCopyToClipboard(
                            context.getString(R.string.password),
                            node.password,
                            sensitive = true
                        )
                    }
                    true
                }
                1 -> {
                    mGroupViewModel.editSingleNode(node)
                    true
                }
                2 -> {
                    mGroupViewModel.deleteSingleNode(node)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onResume() {
        super.onResume()

        mNodesRecyclerView?.addOnScrollListener(mRecycleViewScrollListener)
        activity?.intent?.let {
            specialMode = it.retrieveSpecialMode()
        }
    }

    override fun onPause() {

        mNodesRecyclerView?.removeOnScrollListener(mRecycleViewScrollListener)
        super.onPause()
    }

    companion object {
        private val TAG = GroupFragment::class.java.name
    }
}
