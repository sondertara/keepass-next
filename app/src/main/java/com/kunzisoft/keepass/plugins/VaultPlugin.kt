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
package com.kunzisoft.keepass.plugins

import android.content.Context
import com.kunzisoft.keepass.credentialprovider.magikeyboard.MagikeyboardService
import com.kunzisoft.keepass.events.VaultEvent
import com.kunzisoft.keepass.events.VaultEventBus
import com.kunzisoft.keepass.services.ClipboardEntryNotificationService
import com.kunzisoft.keepass.settings.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Common lifecycle contract for external plugin adapters listening to vault events.
 */
interface VaultPlugin {
    fun start(scope: CoroutineScope)
    fun stop()
}

/**
 * Decoupled adapter that synchronizes search queries and resolved credentials to MagicKeyboard.
 */
class MagicKeyboardPluginAdapter(
    private val context: Context,
    private val eventBus: VaultEventBus,
    private val settingsRepository: AppSettingsRepository
) : VaultPlugin {

    private var job: Job? = null

    override fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is VaultEvent.SearchTargetIdentified -> {
                        try {
                            MagikeyboardService.addSearchInfo(
                                context = context.applicationContext ?: context,
                                value = event.searchInfo,
                                from = event.typeMode
                            )
                        } catch (_: Exception) {
                            // Safe fallback in headless / test environments
                        }
                    }
                    is VaultEvent.CredentialsRetrieved -> {
                        if (settingsRepository.isAutofillSharedToMagikeyboard) {
                            try {
                                MagikeyboardService.addEntries(
                                    context = context.applicationContext ?: context,
                                    entryList = event.entries,
                                    autoSwitchKeyboard = settingsRepository.isAutoSwitchToMagikeyboard,
                                    from = event.typeMode
                                )
                            } catch (_: Exception) {
                                // Safe fallback in headless / test environments
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}

/**
 * Decoupled adapter that displays clipboard OTP notification when credentials are retrieved
 * and not routed to MagicKeyboard.
 */
class ClipboardOtpPluginAdapter(
    private val context: Context,
    private val eventBus: VaultEventBus,
    private val settingsRepository: AppSettingsRepository
) : VaultPlugin {

    private var job: Job? = null

    override fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            eventBus.events.collect { event ->
                if (event is VaultEvent.CredentialsRetrieved) {
                    if (!settingsRepository.isAutofillSharedToMagikeyboard) {
                        try {
                            ClipboardEntryNotificationService.launchOtpNotificationIfAllowed(
                                context = context.applicationContext ?: context,
                                entries = event.entries
                            )
                        } catch (_: Exception) {
                            // Safe fallback in headless / test environments
                        }
                    }
                }
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}
