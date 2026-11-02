package com.kunzisoft.keepass.activities.controllers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupSelectionCoordinatorTest {

    data class MockNode(val id: String, var tags: List<String> = emptyList())

    @Test
    fun selectionToggleLogic() {
        val selectedNodes = mutableListOf<MockNode>()

        fun toggle(node: MockNode) {
            val index = selectedNodes.indexOfFirst { it.id == node.id }
            if (index >= 0) {
                selectedNodes.removeAt(index)
            } else {
                selectedNodes.add(node)
            }
        }

        val node1 = MockNode("1")
        val node2 = MockNode("2")

        toggle(node1)
        assertEquals(1, selectedNodes.size)
        assertTrue(selectedNodes.any { it.id == "1" })

        toggle(node2)
        assertEquals(2, selectedNodes.size)

        // Toggle node1 again -> should be removed
        toggle(node1)
        assertEquals(1, selectedNodes.size)
        assertFalse(selectedNodes.any { it.id == "1" })
        assertTrue(selectedNodes.any { it.id == "2" })
    }

    @Test
    fun selectionInvertLogic() {
        val allNodes = listOf(MockNode("1"), MockNode("2"), MockNode("3"), MockNode("4"))
        val currentlySelected = listOf(allNodes[0], allNodes[2]) // IDs 1 and 3

        val selectedIds = currentlySelected.map { it.id }.toSet()
        val inverted = allNodes.filterNot { it.id in selectedIds }

        assertEquals(2, inverted.size)
        assertEquals("2", inverted[0].id)
        assertEquals("4", inverted[1].id)
    }

    @Test
    fun batchTaggingAddAndRemove() {
        val entry1 = MockNode("1", tags = listOf("work"))
        val entry2 = MockNode("2", tags = listOf("personal", "email"))

        fun applyTag(nodes: List<MockNode>, tag: String) {
            if (tag.isBlank()) return
            nodes.forEach { node ->
                val current = node.tags.toMutableList()
                if (!current.contains(tag)) {
                    current.add(tag)
                    node.tags = current
                }
            }
        }

        fun removeTag(nodes: List<MockNode>, tag: String) {
            if (tag.isBlank()) return
            nodes.forEach { node ->
                val current = node.tags.toMutableList()
                if (current.remove(tag)) {
                    node.tags = current
                }
            }
        }

        val list = listOf(entry1, entry2)
        applyTag(list, "important")

        assertEquals(listOf("work", "important"), entry1.tags)
        assertEquals(listOf("personal", "email", "important"), entry2.tags)

        // Applying again shouldn't duplicate
        applyTag(list, "important")
        assertEquals(listOf("work", "important"), entry1.tags)

        // Removing tag
        removeTag(list, "important")
        assertEquals(listOf("work"), entry1.tags)
        assertEquals(listOf("personal", "email"), entry2.tags)
    }
}
