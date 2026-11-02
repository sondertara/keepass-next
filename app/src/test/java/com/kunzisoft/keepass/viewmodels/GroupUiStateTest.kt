package com.kunzisoft.keepass.viewmodels

import com.kunzisoft.keepass.model.VaultCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupUiStateTest {

    @Test
    fun defaultGroupUiStateProperties() {
        val state = GroupViewModel.GroupUiState()
        assertFalse(state.loaded)
        assertNull(state.breadcrumbs)
        assertNull(state.group)
        assertNull(state.children)
        assertFalse(state.showAddNodeButton)
        assertFalse(state.showAddGroupButton)
        assertFalse(state.showAddEntryButton)
        assertEquals(VaultCategory.ALL, state.vaultCategory)
        assertFalse(state.isFlatView)
        assertNotNull(state.nodeActionState)
        assertFalse(state.nodeActionState.isActive)
        assertFalse(state.isRecycleBin)
    }

    @Test
    fun copyWithUpdatedCategoryAndFlatView() {
        val initial = GroupViewModel.GroupUiState()
        val updated = initial.copy(
            loaded = true,
            vaultCategory = VaultCategory.PASSKEYS,
            isFlatView = true
        )
        assertTrue(updated.loaded)
        assertEquals(VaultCategory.PASSKEYS, updated.vaultCategory)
        assertTrue(updated.isFlatView)
    }

    @Test
    fun nodeActionStateTransitions() {
        val actionState = GroupViewModel.NodeActionState(
            isActive = true,
            nodeActionMode = GroupViewModel.NodeActionMode.SELECTION,
            selectedNodes = emptyList()
        )
        val uiState = GroupViewModel.GroupUiState(nodeActionState = actionState)
        assertTrue(uiState.nodeActionState.isActive)
        assertEquals(GroupViewModel.NodeActionMode.SELECTION, uiState.nodeActionState.nodeActionMode)
    }

    @Test
    fun backwardCompatibilityTypealias() {
        val aliasState: GroupViewModel.GroupUISTate = GroupViewModel.GroupUiState(loaded = true)
        assertTrue(aliasState.loaded)
    }
}
