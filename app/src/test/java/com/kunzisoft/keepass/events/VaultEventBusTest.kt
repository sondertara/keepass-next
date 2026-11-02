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
package com.kunzisoft.keepass.events

import com.kunzisoft.keepass.credentialprovider.TypeMode
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.RegisterInfo
import com.kunzisoft.keepass.model.SearchInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VaultEventBusTest {

    private lateinit var testScope: CoroutineScope
    private lateinit var eventBus: VaultEventBus

    @Before
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        eventBus = VaultEventBusImpl(replay = 1)
        VaultEventBusImpl.setInstanceForTest(eventBus)
    }

    @After
    fun tearDown() {
        testScope.cancel()
        VaultEventBusImpl.setInstanceForTest(null)
    }

    @Test
    fun singletonInstance_returnsSameInstanceAndNotNull() {
        VaultEventBusImpl.setInstanceForTest(null)
        val first = VaultEventBusImpl.instance
        val second = VaultEventBusImpl.instance
        assertNotNull(first)
        assertSame(first, second)
    }

    @Test
    fun setInstanceForTest_overridesSingleton() {
        val customBus = VaultEventBusImpl()
        VaultEventBusImpl.setInstanceForTest(customBus)
        assertSame(customBus, VaultEventBusImpl.instance)
    }

    @Test
    fun emitAndCollect_searchTargetIdentified_dispatchedCorrectly() = runBlocking {
        val searchInfo = SearchInfo().apply {
            applicationId = "com.example.app"
        }
        val event = VaultEvent.SearchTargetIdentified(
            searchInfo = searchInfo,
            typeMode = TypeMode.AUTOFILL
        )

        eventBus.emit(event)
        val collected = withTimeout(2000) {
            eventBus.events.first()
        }

        assertTrue(collected is VaultEvent.SearchTargetIdentified)
        val searchEvent = collected as VaultEvent.SearchTargetIdentified
        assertEquals("com.example.app", searchEvent.searchInfo.applicationId)
        assertEquals(TypeMode.AUTOFILL, searchEvent.typeMode)
    }

    @Test
    fun tryEmit_credentialsRetrieved_buffersAndDispatches() = runBlocking {
        val entry1 = EntryInfo().apply { username = "user1" }
        val entry2 = EntryInfo().apply { username = "user2" }
        val event = VaultEvent.CredentialsRetrieved(
            entries = listOf(entry1, entry2),
            typeMode = TypeMode.MAGIKEYBOARD
        )

        val emitted = eventBus.tryEmit(event)
        assertTrue(emitted)

        val collected = withTimeout(2000) {
            eventBus.events.first()
        }

        assertTrue(collected is VaultEvent.CredentialsRetrieved)
        val credEvent = collected as VaultEvent.CredentialsRetrieved
        assertEquals(2, credEvent.entries.size)
        assertEquals("user1", credEvent.entries[0].username)
        assertEquals("user2", credEvent.entries[1].username)
        assertEquals(TypeMode.MAGIKEYBOARD, credEvent.typeMode)
    }

    @Test
    fun emit_entrySaved_dispatchedToSubscribers() = runBlocking {
        val searchInfo = SearchInfo().apply {
            webDomain = "example.com"
        }
        val registerInfo = RegisterInfo(
            searchInfo,
            "new_user"
        )
        val event = VaultEvent.EntrySaved(registerInfo)

        eventBus.emit(event)
        val collected = withTimeout(2000) {
            eventBus.events.first()
        }

        assertTrue(collected is VaultEvent.EntrySaved)
        val savedEvent = collected as VaultEvent.EntrySaved
        assertEquals("example.com", savedEvent.registerInfo.searchInfo.webDomain)
        assertEquals("new_user", savedEvent.registerInfo.username)
    }

    @Test
    fun multipleSubscribers_allReceiveEvents() = runBlocking {
        val multiBus = VaultEventBusImpl(replay = 2)

        val event1 = VaultEvent.SearchTargetIdentified(SearchInfo(), TypeMode.DEFAULT)
        val event2 = VaultEvent.CredentialsRetrieved(emptyList(), TypeMode.PASSWORD)

        multiBus.emit(event1)
        multiBus.emit(event2)

        val results1 = withTimeout(2000) {
            multiBus.events.take(2).toList()
        }
        val results2 = withTimeout(2000) {
            multiBus.events.take(2).toList()
        }

        assertEquals(2, results1.size)
        assertEquals(2, results2.size)
        assertSame(event1, results1[0])
        assertSame(event2, results1[1])
        assertSame(event1, results2[0])
        assertSame(event2, results2[1])
    }

    @Test
    fun bufferOverflow_dropsOldestWhenConfigured() = runBlocking {
        val overflowingBus = VaultEventBusImpl(
            replay = 2,
            extraBufferCapacity = 0,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        val event1 = VaultEvent.SearchTargetIdentified(SearchInfo().apply { applicationId = "app1" })
        val event2 = VaultEvent.SearchTargetIdentified(SearchInfo().apply { applicationId = "app2" })
        val event3 = VaultEvent.SearchTargetIdentified(SearchInfo().apply { applicationId = "app3" })

        overflowingBus.tryEmit(event1)
        overflowingBus.tryEmit(event2)
        overflowingBus.tryEmit(event3)

        val collected = overflowingBus.events.take(2).toList()
        assertEquals(2, collected.size)
        assertEquals("app2", (collected[0] as VaultEvent.SearchTargetIdentified).searchInfo.applicationId)
        assertEquals("app3", (collected[1] as VaultEvent.SearchTargetIdentified).searchInfo.applicationId)
    }
}
