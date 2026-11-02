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

import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.Toolbar
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.GroupInfo
import com.kunzisoft.keepass.model.NodeInfo
import com.kunzisoft.keepass.model.SortedNodeInfo
import com.kunzisoft.keepass.viewmodels.GroupViewModel
import com.kunzisoft.keepass.viewmodels.GroupViewModel.NodeActionMode

/**
 * Coordinator for Contextual Action Mode (CAB), multi-selection state,
 * and batch operations (delete, move, copy, paste, tagging).
 */
class GroupSelectionCoordinator(
    private val activity: AppCompatActivity,
    private val groupViewModel: GroupViewModel,
    private val getDatabase: () -> ContextualDatabase?,
    private val getToolbarAction: () -> Toolbar?,
    private val onUpdateEntry: ((EntryInfo) -> Unit)? = null
) {

    var activeActionMode: ActionMode? = null
        private set

    val isActionModeActive: Boolean
        get() = activeActionMode != null

    val selectedNodes: List<SortedNodeInfo>
        get() = groupViewModel.nodeActionState.value.selectedNodes

    val selectedCount: Int
        get() = selectedNodes.size

    fun isNodeSelected(node: SortedNodeInfo): Boolean =
        selectedNodes.any { it.nodeId == node.nodeId }

    /**
     * Toggles selection state of a specific node.
     */
    fun toggleSelection(node: SortedNodeInfo) {
        val current = selectedNodes.toMutableList()
        val index = current.indexOfFirst { it.nodeId == node.nodeId }
        if (index >= 0) {
            current.removeAt(index)
        } else {
            current.add(node)
        }
        groupViewModel.selectNodes(current)
    }

    /**
     * Selects all nodes in the given collection.
     */
    fun selectAll(allNodes: List<SortedNodeInfo>) {
        if (allNodes.isNotEmpty()) {
            groupViewModel.selectNodes(allNodes)
        }
    }

    /**
     * Inverts the selection within the given collection of nodes.
     */
    fun invertSelection(allNodes: List<SortedNodeInfo>) {
        val selectedIds = selectedNodes.map { it.nodeId }.toSet()
        val inverted = allNodes.filterNot { it.nodeId in selectedIds }
        groupViewModel.selectNodes(inverted)
    }

    /**
     * Clears all selections and terminates the contextual action mode.
     */
    fun clearSelection() {
        groupViewModel.finishNodeAction()
        finishActionMode()
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            groupViewModel.onActionCreated()
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            val database = getDatabase() ?: return false
            val state = groupViewModel.nodeActionState.value
            val nodes = state.selectedNodes

            menu?.clear()
            when (state.nodeActionMode) {
                NodeActionMode.SELECTION -> {
                    mode?.menuInflater?.inflate(R.menu.node_menu, menu)

                    // Open and Edit for a single item
                    if (nodes.size == 1) {
                        if (database.isReadOnly || groupViewModel.containsRecycleBin(
                                database, nodes.filterIsInstance<NodeInfo>()
                            )
                        ) {
                            menu?.removeItem(R.id.menu_edit)
                        }
                    } else {
                        menu?.removeItem(R.id.menu_open)
                        menu?.removeItem(R.id.menu_edit)
                    }

                    // Move
                    if (database.isReadOnly) {
                        menu?.removeItem(R.id.menu_move)
                    }

                    // Copy (not allowed for group)
                    if (database.isReadOnly || nodes.any { it is GroupInfo }) {
                        menu?.removeItem(R.id.menu_copy)
                    }

                    // Deletion
                    if (database.isReadOnly || groupViewModel.containsRecycleBin(
                            database, nodes.filterIsInstance<NodeInfo>()
                        )
                    ) {
                        menu?.removeItem(R.id.menu_delete)
                    }
                }
                NodeActionMode.PASTE_FROM_COPY,
                NodeActionMode.PASTE_FROM_MOVE -> {
                    mode?.menuInflater?.inflate(R.menu.node_paste_menu, menu)
                }
                null -> {}
            }

            mode?.title = nodes.size.toString()
            return true
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            return when (item?.itemId) {
                R.id.menu_open -> {
                    groupViewModel.onOpenNodeClicked()
                    true
                }
                R.id.menu_edit -> {
                    groupViewModel.onEditNodeClicked()
                    true
                }
                R.id.menu_copy -> {
                    executeBatchCopy()
                    true
                }
                R.id.menu_move -> {
                    executeBatchMove()
                    true
                }
                R.id.menu_delete -> {
                    executeBatchDelete()
                    true
                }
                R.id.menu_paste -> {
                    executeBatchPaste()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            groupViewModel.onActionDestroyed()
            activeActionMode = null
        }
    }

    /**
     * Triggers batch deletion of selected nodes.
     */
    fun executeBatchDelete() {
        groupViewModel.onDeleteNodesClicked()
    }

    /**
     * Triggers batch move (transitions to PASTE_FROM_MOVE mode).
     */
    fun executeBatchMove() {
        groupViewModel.onMoveNodesClicked()
    }

    /**
     * Triggers batch copy (transitions to PASTE_FROM_COPY mode).
     */
    fun executeBatchCopy() {
        groupViewModel.onCopyNodesClicked()
    }

    /**
     * Executes paste of previously copied or moved nodes.
     */
    fun executeBatchPaste() {
        groupViewModel.onPasteNodesClicked()
    }

    /**
     * Synchronize action mode with ViewModel node action state.
     */
    fun syncWithState(state: GroupViewModel.NodeActionState) {
        if (state.nodeActionMode != null) {
            if (activeActionMode == null) {
                activeActionMode = getToolbarAction()?.startSupportActionMode(actionModeCallback)
                    ?: activity.startSupportActionMode(actionModeCallback)
            }
            activeActionMode?.invalidate()
        } else {
            activeActionMode?.finish()
            activeActionMode = null
        }
    }

    fun finishActionMode() {
        activeActionMode?.finish()
        activeActionMode = null
    }

    /**
     * Batch tagging: apply or append a tag to all selected entries.
     */
    fun applyBatchTag(tag: String, nodes: List<SortedNodeInfo> = selectedNodes) {
        if (tag.isBlank()) return
        val database = getDatabase() ?: return
        if (database.isReadOnly) return

        nodes.forEach { node ->
            if (node is EntryInfo) {
                val currentTags = node.tags.toMutableList()
                if (!currentTags.contains(tag)) {
                    currentTags.add(tag)
                    node.tags = currentTags
                    onUpdateEntry?.invoke(node)
                }
            }
        }
    }

    /**
     * Batch untagging: removes a tag from all specified entries.
     */
    fun removeBatchTag(tag: String, nodes: List<SortedNodeInfo> = selectedNodes) {
        if (tag.isBlank()) return
        val database = getDatabase() ?: return
        if (database.isReadOnly) return

        nodes.forEach { node ->
            if (node is EntryInfo) {
                val currentTags = node.tags.toMutableList()
                if (currentTags.remove(tag)) {
                    node.tags = currentTags
                    onUpdateEntry?.invoke(node)
                }
            }
        }
    }

    /**
     * Batch tag modifier: adds and/or removes tags across selected entries.
     */
    fun batchTagEntries(
        tagsToAdd: Set<String> = emptySet(),
        tagsToRemove: Set<String> = emptySet(),
        nodes: List<SortedNodeInfo> = selectedNodes
    ) {
        if (tagsToAdd.isEmpty() && tagsToRemove.isEmpty()) return
        val database = getDatabase() ?: return
        if (database.isReadOnly) return

        nodes.forEach { node ->
            if (node is EntryInfo) {
                val currentTags = node.tags.toMutableList()
                var modified = false
                tagsToRemove.forEach { toRemove ->
                    if (currentTags.remove(toRemove)) modified = true
                }
                tagsToAdd.forEach { toAdd ->
                    if (toAdd.isNotBlank() && !currentTags.contains(toAdd)) {
                        currentTags.add(toAdd)
                        modified = true
                    }
                }
                if (modified) {
                    node.tags = currentTags
                    onUpdateEntry?.invoke(node)
                }
            }
        }
    }
}
