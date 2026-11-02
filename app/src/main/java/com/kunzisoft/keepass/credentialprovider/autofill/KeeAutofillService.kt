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
package com.kunzisoft.keepass.credentialprovider.autofill

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BlendMode
import android.graphics.drawable.Icon
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import android.view.autofill.AutofillManager
import android.widget.RemoteViews
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.credentialprovider.TypeMode
import com.kunzisoft.keepass.credentialprovider.activity.AutofillLauncherActivity
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.DatabaseTaskProvider
import com.kunzisoft.keepass.database.element.DateInstant
import com.kunzisoft.keepass.database.helper.SearchHelper
import com.kunzisoft.keepass.events.VaultEvent
import com.kunzisoft.keepass.events.VaultEventBus
import com.kunzisoft.keepass.events.VaultEventBusImpl
import com.kunzisoft.keepass.model.CreditCard
import com.kunzisoft.keepass.model.RegisterInfo
import com.kunzisoft.keepass.model.SearchInfo
import com.kunzisoft.keepass.plugins.ClipboardOtpPluginAdapter
import com.kunzisoft.keepass.plugins.MagicKeyboardPluginAdapter
import com.kunzisoft.keepass.plugins.VaultPlugin
import com.kunzisoft.keepass.repository.CredentialRepository
import com.kunzisoft.keepass.repository.CredentialRepositoryImpl
import com.kunzisoft.keepass.repository.VaultRepository
import com.kunzisoft.keepass.repository.VaultRepositoryImpl
import com.kunzisoft.keepass.settings.AppSettingsRepository
import com.kunzisoft.keepass.settings.AppSettingsRepositoryImpl
import com.kunzisoft.keepass.settings.AutofillSettingsActivity
import com.kunzisoft.keepass.utils.AppUtil.randomRequestCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.joda.time.DateTime
import org.joda.time.Instant

class KeeAutofillService : AutofillService() {

    private var mDatabaseTaskProvider: DatabaseTaskProvider? = null
    private var mDatabase: ContextualDatabase? = null

    private val eventBus: VaultEventBus = VaultEventBusImpl.instance
    private lateinit var appSettingsRepository: AppSettingsRepository
    private lateinit var vaultRepository: VaultRepository
    private lateinit var credentialRepository: CredentialRepository

    private var magicKeyboardPlugin: VaultPlugin? = null
    private var clipboardOtpPlugin: VaultPlugin? = null
    private var serviceScope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()

        appSettingsRepository = AppSettingsRepositoryImpl.getInstance(this)
        vaultRepository = VaultRepositoryImpl.getInstance()
        credentialRepository = CredentialRepositoryImpl.getInstance(vaultRepository)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        serviceScope = scope

        // Decoupled side-effect plugins triggered via domain events
        magicKeyboardPlugin = MagicKeyboardPluginAdapter(this, eventBus, appSettingsRepository).also {
            it.start(scope)
        }
        clipboardOtpPlugin = ClipboardOtpPluginAdapter(this, eventBus, appSettingsRepository).also {
            it.start(scope)
        }

        mDatabaseTaskProvider = DatabaseTaskProvider(this)
        mDatabaseTaskProvider?.registerProgressTask()
        mDatabaseTaskProvider?.onDatabaseRetrieved = { database ->
            this.mDatabase = database
        }
    }

    override fun onDestroy() {
        mDatabaseTaskProvider?.unregisterProgressTask()
        magicKeyboardPlugin?.stop()
        clipboardOtpPlugin?.stop()
        serviceScope?.cancel()
        serviceScope = null

        super.onDestroy()
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        @Volatile var isCancelled = false
        cancellationSignal.setOnCancelListener {
            isCancelled = true
            Log.w(TAG, "Cancel autofill.")
        }

        if (request.flags and FillRequest.FLAG_COMPATIBILITY_MODE_REQUEST != 0) {
            Log.d(TAG, "Autofill requested in compatibility mode")
        } else {
            Log.d(TAG, "Autofill requested in native mode")
        }

        // Check user's settings for authenticating Responses and Datasets.
        val latestStructure = request.fillContexts.last().structure
        StructureParser(latestStructure).parse(saveValue = false)?.let { parseResult ->

            // Build the search info from the parser
            val searchInfo = SearchInfo().apply {
                applicationId = parseResult.applicationId
                webScheme = parseResult.webScheme
                webDomain = parseResult.webDomain
            }

            // Dispatch domain event: plugins asynchronously handle side-effects
            eventBus.tryEmit(VaultEvent.SearchTargetIdentified(searchInfo, TypeMode.AUTOFILL))

            // Build search info only if applicationId or webDomain are not blocked
            if (appSettingsRepository.isAutofillSuggestionsEnabled &&
                appSettingsRepository.isAutofillAllowed(parseResult.applicationId, parseResult.webDomain)
            ) {
                if (parseResult.isValid()) {
                    val inlineSuggestionsRequest =
                        if (appSettingsRepository.isAutofillInlineSuggestionsEnabled) {
                            CompatInlineSuggestionsRequest(request)
                        } else {
                            null
                        }
                    val autofillComponent = AutofillComponent(
                        latestStructure,
                        inlineSuggestionsRequest
                    )
                    SearchHelper.checkAutoSearchInfo(
                        context = this,
                        database = mDatabase,
                        searchInfo = searchInfo,
                        onItemsFound = { openedDatabase, items ->
                            if (isCancelled) return@checkAutoSearchInfo

                            // Dispatch domain event: plugins handle MagicKeyboard / Clipboard OTP notifications
                            eventBus.tryEmit(VaultEvent.CredentialsRetrieved(items, TypeMode.AUTOFILL))

                            callback.onSuccess(
                                AutofillHelper.buildResponse(
                                    context = this,
                                    database = openedDatabase,
                                    entriesInfo = items,
                                    parseResult = parseResult,
                                    autofillComponent = autofillComponent
                                )
                            )
                        },
                        onItemNotFound = { openedDatabase ->
                            if (isCancelled) return@checkAutoSearchInfo
                            // Show UI if no search result
                            showUIForEntrySelection(
                                parseResult, openedDatabase,
                                searchInfo, autofillComponent, callback
                            )
                        },
                        onDatabaseClosed = {
                            if (isCancelled) return@checkAutoSearchInfo
                            // Show UI if database not open
                            showUIForEntrySelection(
                                parseResult, null,
                                searchInfo, autofillComponent, callback
                            )
                        }
                    )
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    private fun showUIForEntrySelection(
        parseResult: StructureParser.Result,
        database: ContextualDatabase?,
        searchInfo: SearchInfo,
        autofillComponent: AutofillComponent,
        callback: FillCallback
    ) {
        var success = false
        parseResult.allAutofillIds().let { autofillIds ->
            if (autofillIds.isNotEmpty()) {
                // If the entire Autofill Response is authenticated, AuthActivity is used to generate Response.
                AutofillLauncherActivity.getPendingIntentForSelection(
                    this,
                    searchInfo,
                    autofillComponent
                )?.intentSender?.let { intentSender ->
                    val responseBuilder = FillResponse.Builder()
                    val remoteViewsUnlock: RemoteViews = if (database == null) {
                        if (!parseResult.webDomain.isNullOrEmpty()) {
                            RemoteViews(
                                packageName,
                                R.layout.item_autofill_unlock_web_domain
                            ).apply {
                                setTextViewText(
                                    R.id.autofill_web_domain_text,
                                    parseResult.webDomain
                                )
                            }
                        } else if (!parseResult.applicationId.isNullOrEmpty()) {
                            RemoteViews(packageName, R.layout.item_autofill_unlock_app_id).apply {
                                setTextViewText(
                                    R.id.autofill_app_id_text,
                                    parseResult.applicationId
                                )
                            }
                        } else {
                            RemoteViews(packageName, R.layout.item_autofill_unlock)
                        }
                    } else {
                        if (!parseResult.webDomain.isNullOrEmpty()) {
                            RemoteViews(
                                packageName,
                                R.layout.item_autofill_select_entry_web_domain
                            ).apply {
                                setTextViewText(
                                    R.id.autofill_web_domain_text,
                                    parseResult.webDomain
                                )
                            }
                        } else if (!parseResult.applicationId.isNullOrEmpty()) {
                            RemoteViews(
                                packageName,
                                R.layout.item_autofill_select_entry_app_id
                            ).apply {
                                setTextViewText(
                                    R.id.autofill_app_id_text,
                                    parseResult.applicationId
                                )
                            }
                        } else {
                            RemoteViews(packageName, R.layout.item_autofill_select_entry)
                        }
                    }

                    // Tell the autofill framework the interest to save credentials
                    if (appSettingsRepository.isAskToSaveData) {
                        AutofillHelper.buildSaveInfo(parseResult)?.let { saveInfo ->
                            responseBuilder.setSaveInfo(saveInfo)
                        }
                    }

                    // Build inline presentation
                    var inlinePresentation: InlinePresentation? = null
                    if (appSettingsRepository.isAutofillInlineSuggestionsEnabled) {
                        autofillComponent.compatInlineSuggestionsRequest
                            ?.inlineSuggestionsRequest?.let { inlineSuggestionsRequest ->
                            val inlinePresentationSpecs =
                                inlineSuggestionsRequest.inlinePresentationSpecs
                            if (inlineSuggestionsRequest.maxSuggestionCount > 0
                                && inlinePresentationSpecs.isNotEmpty()
                            ) {
                                val inlinePresentationSpec = inlinePresentationSpecs[0]

                                // Make sure that the IME spec claims support for v1 UI template.
                                val imeStyle = inlinePresentationSpec.style
                                if (UiVersions.getVersions(imeStyle)
                                        .contains(UiVersions.INLINE_UI_VERSION_1)
                                ) {
                                    // Build the content for IME UI
                                    inlinePresentation = InlinePresentation(
                                        InlineSuggestionUi.newContentBuilder(
                                            PendingIntent.getActivity(
                                                this,
                                                randomRequestCode(),
                                                Intent(this, AutofillSettingsActivity::class.java),
                                                PendingIntent.FLAG_IMMUTABLE
                                            )
                                        ).apply {
                                            setContentDescription(getString(R.string.autofill_sign_in_prompt))
                                            setTitle(getString(R.string.autofill_sign_in_prompt))
                                            setStartIcon(
                                                Icon.createWithResource(
                                                    this@KeeAutofillService,
                                                    R.mipmap.ic_launcher_round
                                                ).apply {
                                                    setTintBlendMode(BlendMode.DST)
                                                })
                                        }.build().slice, inlinePresentationSpec, false
                                    )
                                }
                            }
                        }
                    }

                    val isWebEnvironment = parseResult.isWebView || !parseResult.webDomain.isNullOrBlank()
                    // If database is already unlocked and this is a webpage where no entries matched,
                    // do not show an intrusive dropdown popup that steals focus and dismisses the keyboard!
                    val shouldSuppressIntrusiveDropdown = database != null && isWebEnvironment && inlinePresentation == null
                    if (shouldSuppressIntrusiveDropdown) {
                        success = true
                        callback.onSuccess(responseBuilder.build())
                        return
                    }

                    // Build response
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        try {
                            responseBuilder.setAuthentication(
                                autofillIds,
                                intentSender,
                                Presentations.Builder().apply {
                                    inlinePresentation?.let {
                                        setInlinePresentation(it)
                                    }
                                    setMenuPresentation(remoteViewsUnlock)
                                }.build()
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Unable to use the new setAuthentication method.", e)
                            @Suppress("DEPRECATION")
                            responseBuilder.setAuthentication(
                                autofillIds,
                                intentSender,
                                remoteViewsUnlock,
                                inlinePresentation
                            )
                        }
                    } else if (inlinePresentation != null) {
                        @Suppress("DEPRECATION")
                        responseBuilder.setAuthentication(
                            autofillIds,
                            intentSender,
                            remoteViewsUnlock,
                            inlinePresentation
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        responseBuilder.setAuthentication(
                            autofillIds,
                            intentSender,
                            remoteViewsUnlock
                        )
                    }
                    success = true
                    callback.onSuccess(responseBuilder.build())
                }
            }
        }
        if (!success)
            callback.onFailure("Unable to get Autofill ids for UI selection")
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        var success = false
        if (appSettingsRepository.isAskToSaveData) {
            val latestStructure = request.fillContexts.last().structure
            StructureParser(latestStructure).parse(saveValue = true)?.let { parseResult ->

                if (parseResult.isValid() &&
                    appSettingsRepository.isAutofillAllowed(parseResult.applicationId, parseResult.webDomain)
                ) {
                    Log.d(TAG, "autofill onSaveRequest password")

                    // Build expiration from date or from year and month
                    var expiration: DateTime? = parseResult.creditCardExpirationValue
                    if (parseResult.creditCardExpirationValue == null
                        && parseResult.creditCardExpirationYearValue != 0
                        && parseResult.creditCardExpirationMonthValue != 0) {
                        expiration = DateTime()
                            .withYear(parseResult.creditCardExpirationYearValue)
                            .withMonthOfYear(parseResult.creditCardExpirationMonthValue)
                        if (parseResult.creditCardExpirationDayValue != 0) {
                            expiration = expiration.withDayOfMonth(parseResult.creditCardExpirationDayValue)
                        }
                    }

                    // Show UI to save data
                    val searchInfo = SearchInfo().apply {
                        applicationId = parseResult.applicationId
                        webDomain = parseResult.webDomain
                        webScheme = parseResult.webScheme
                    }
                    val registerInfo = RegisterInfo(
                        searchInfo = searchInfo,
                        username = parseResult.usernameValue?.textValue?.toString(),
                        password = parseResult.passwordValue?.textValue?.toString()?.toCharArray(),
                        expiration = expiration?.let { DateInstant(Instant(it)) },
                        creditCard = parseResult.creditCardNumber?.textValue?.toString()?.let { cardNumber ->
                            CreditCard(
                                cardholder = parseResult.creditCardHolder?.textValue?.toString(),
                                number = cardNumber.toCharArray(),
                                cvv = parseResult.cardVerificationValue?.textValue?.toString()?.toCharArray()
                            )
                        }
                    )

                    // Emit domain event for saved entry
                    eventBus.tryEmit(VaultEvent.EntrySaved(registerInfo))

                    AutofillLauncherActivity.getPendingIntentForRegistration(
                        this,
                        registerInfo
                    )?.intentSender?.let { intentSender ->
                        success = true
                        callback.onSuccess(intentSender)
                    }
                }
            }
        }
        if (!success) {
            callback.onFailure("Saving form values is not allowed")
        }
    }

    override fun onConnected() {
        Log.d(TAG, "onConnected")
    }

    override fun onDisconnected() {
        Log.d(TAG, "onDisconnected")
    }

    companion object {
        private val TAG = KeeAutofillService::class.java.name

        fun autofillAllowedFor(
            applicationId: String?,
            webDomain: String?,
            context: Context
        ): Boolean {
            return AppSettingsRepositoryImpl.getInstance(context).isAutofillAllowed(applicationId, webDomain)
        }

        fun Context.showAutofillDeviceSettings() {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                        data = "package:${KeeAutofillService::class.java.canonicalName}".toUri()
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unable to choose the autofill service", e)
            }
        }
    }
}

fun Context.isCredentialProviderActivated(): Boolean {
    return ContextCompat.getSystemService(
        this,
        AutofillManager::class.java
    )?.hasEnabledAutofillServices() == true
}
