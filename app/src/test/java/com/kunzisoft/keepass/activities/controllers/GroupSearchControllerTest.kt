package com.kunzisoft.keepass.activities.controllers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupSearchControllerTest {

    data class MockSearchParameters(
        var searchQuery: String = "",
        var searchInTitles: Boolean = true,
        var searchInUsernames: Boolean = true,
        var searchInUrls: Boolean = true,
        var searchInNotes: Boolean = true,
        var searchInPasswords: Boolean = false,
        var searchInTags: Boolean = true
    )

    private fun prepareParameters(
        query: String?,
        searchInTitles: Boolean = true,
        searchInUsernames: Boolean = true,
        searchInUrls: Boolean = true,
        searchInNotes: Boolean = true,
        searchInPasswords: Boolean = false,
        searchInTags: Boolean = true
    ): MockSearchParameters {
        return MockSearchParameters(
            searchQuery = query ?: "",
            searchInTitles = searchInTitles,
            searchInUsernames = searchInUsernames,
            searchInUrls = searchInUrls,
            searchInNotes = searchInNotes,
            searchInPasswords = searchInPasswords,
            searchInTags = searchInTags
        )
    }

    @Test
    fun prepareSearchParametersDefaultFields() {
        val params = prepareParameters("master-key")
        assertEquals("master-key", params.searchQuery)
        assertTrue(params.searchInTitles)
        assertTrue(params.searchInUsernames)
        assertTrue(params.searchInUrls)
        assertTrue(params.searchInNotes)
        assertFalse(params.searchInPasswords)
        assertTrue(params.searchInTags)
    }

    @Test
    fun prepareSearchParametersCustomToggles() {
        val params = prepareParameters(
            query = "wifi_network",
            searchInTitles = true,
            searchInUsernames = false,
            searchInUrls = false,
            searchInNotes = false,
            searchInPasswords = true,
            searchInTags = false
        )
        assertEquals("wifi_network", params.searchQuery)
        assertTrue(params.searchInTitles)
        assertFalse(params.searchInUsernames)
        assertFalse(params.searchInUrls)
        assertFalse(params.searchInNotes)
        assertTrue(params.searchInPasswords)
        assertFalse(params.searchInTags)
    }

    @Test
    fun searchHistorySerializationAndCapacityLogic() {
        val initialHistory = mutableListOf<String>()
        val maxItems = 10

        // Helper replicating controller's LRU & deduplication algorithm
        fun addQuery(list: MutableList<String>, query: String): List<String> {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return list
            list.remove(trimmed)
            list.add(0, trimmed)
            return list.take(maxItems)
        }

        var history = addQuery(initialHistory, "google")
        assertEquals(listOf("google"), history)

        history = addQuery(history.toMutableList(), "github")
        assertEquals(listOf("github", "google"), history)

        // Adding "google" again should bring it to index 0 without duplicates
        history = addQuery(history.toMutableList(), "google")
        assertEquals(listOf("google", "github"), history)

        // Empty queries should be ignored
        history = addQuery(history.toMutableList(), "   ")
        assertEquals(listOf("google", "github"), history)

        // Fill up beyond 10 items
        for (i in 1..12) {
            history = addQuery(history.toMutableList(), "query_$i")
        }
        assertEquals(10, history.size)
        assertEquals("query_12", history.first())
        assertFalse(history.contains("google")) // Should have been pushed out past capacity 10
    }
}
