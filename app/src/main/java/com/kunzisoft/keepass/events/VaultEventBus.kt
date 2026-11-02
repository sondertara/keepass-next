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

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event bus contract for dispatching and observing vault domain events.
 * Decouples core services (such as Autofill) from side-effect plugins (such as MagicKeyboard or Clipboard OTP).
 */
interface VaultEventBus {
    /**
     * Shared stream of vault events.
     */
    val events: SharedFlow<VaultEvent>

    /**
     * Suspend and emit an event to the bus.
     */
    suspend fun emit(event: VaultEvent)

    /**
     * Non-blocking attempt to emit an event into the bus buffer.
     */
    fun tryEmit(event: VaultEvent): Boolean
}

/**
 * Standard implementation of [VaultEventBus] backed by Kotlin Coroutines [MutableSharedFlow].
 */
class VaultEventBusImpl(
    replay: Int = 0,
    extraBufferCapacity: Int = 64,
    onBufferOverflow: BufferOverflow = BufferOverflow.DROP_OLDEST
) : VaultEventBus {

    private val _events = MutableSharedFlow<VaultEvent>(
        replay = replay,
        extraBufferCapacity = extraBufferCapacity,
        onBufferOverflow = onBufferOverflow
    )

    override val events: SharedFlow<VaultEvent> = _events.asSharedFlow()

    override suspend fun emit(event: VaultEvent) {
        _events.emit(event)
    }

    override fun tryEmit(event: VaultEvent): Boolean {
        return _events.tryEmit(event)
    }

    companion object {
        @Volatile
        private var defaultInstance: VaultEventBus? = null

        val instance: VaultEventBus
            get() = defaultInstance ?: synchronized(this) {
                defaultInstance ?: VaultEventBusImpl().also { defaultInstance = it }
            }

        /**
         * For testing: replaces the singleton instance with a test mock or custom configuration.
         */
        fun setInstanceForTest(eventBus: VaultEventBus?) {
            defaultInstance = eventBus
        }
    }
}
