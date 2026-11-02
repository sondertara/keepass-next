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
package com.kunzisoft.keepass.viewmodels

import com.kunzisoft.keepass.model.GroupInfo
import com.kunzisoft.keepass.model.SortedGroupInfo
import com.kunzisoft.keepass.model.SortedNodeInfo
import com.kunzisoft.keepass.model.VaultCategory

/**
 * Node action mode states for contextual action operations.
 */
enum class NodeActionMode {
    SELECTION,
    PASTE_FROM_COPY,
    PASTE_FROM_MOVE
}

/**
 * State representing active node selection or paste mode.
 */
data class NodeActionState(
    val isActive: Boolean = false,
    val nodeActionMode: NodeActionMode? = null,
    val selectedNodes: List<SortedNodeInfo> = emptyList()
)

/**
 * Immutable UI state model for GroupActivity and GroupViewModel following Unidirectional Data Flow (UDF).
 * Standalone pure Kotlin data class to enable fast JVM unit testing.
 */
data class GroupUiState(
    val loaded: Boolean = false,
    val breadcrumbs: List<SortedGroupInfo>? = null,
    val group: GroupInfo? = null,
    val children: List<SortedNodeInfo>? = null,
    val showAddNodeButton: Boolean = false,
    val showAddGroupButton: Boolean = false,
    val showAddEntryButton: Boolean = false,
    val vaultCategory: VaultCategory = VaultCategory.ALL,
    val isFlatView: Boolean = false,
    val nodeActionState: NodeActionState = NodeActionState(),
    val isRecycleBin: Boolean = false
)
