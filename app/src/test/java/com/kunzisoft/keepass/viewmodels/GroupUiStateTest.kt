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
        val state = GroupUiState()
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
        val initial = GroupUiState()
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
        val actionState = NodeActionState(
            isActive = true,
            nodeActionMode = NodeActionMode.SELECTION,
            selectedNodes = emptyList()
        )
        val uiState = GroupUiState(nodeActionState = actionState)
        assertTrue(uiState.nodeActionState.isActive)
        assertEquals(NodeActionMode.SELECTION, uiState.nodeActionState.nodeActionMode)
    }

    @Test
    fun copyAndImmutabilityPreservation() {
        val original = GroupUiState(loaded = false, vaultCategory = VaultCategory.ALL)
        val modified = original.copy(loaded = true, vaultCategory = VaultCategory.BANK)

        // Original remains unchanged
        assertFalse(original.loaded)
        assertEquals(VaultCategory.ALL, original.vaultCategory)

        // Modified has updated values
        assertTrue(modified.loaded)
        assertEquals(VaultCategory.BANK, modified.vaultCategory)
    }
}
