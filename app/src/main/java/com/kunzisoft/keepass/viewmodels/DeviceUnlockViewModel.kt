package com.kunzisoft.keepass.viewmodels

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunzisoft.keepass.app.AppLifecycleObserver
import com.kunzisoft.keepass.app.database.CipherDatabaseAction
import com.kunzisoft.keepass.biometric.DeviceUnlockCryptoPrompt
import com.kunzisoft.keepass.biometric.DeviceUnlockCryptoPromptType
import com.kunzisoft.keepass.biometric.DeviceUnlockManager
import com.kunzisoft.keepass.biometric.DeviceUnlockMode
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.DatabaseJobManager
import com.kunzisoft.keepass.database.exception.UnknownDatabaseLocationException
import com.kunzisoft.keepass.model.CipherDecryptDatabase
import com.kunzisoft.keepass.model.CipherEncryptDatabase
import com.kunzisoft.keepass.model.CredentialStorage
import com.kunzisoft.keepass.settings.PreferencesUtil
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.crypto.Cipher


class DeviceUnlockViewModel(application: Application): AndroidViewModel(application) {
    private var cipherDatabase: CipherEncryptDatabase? = null

    private var isConditionToStoreCredentialVerified: Boolean = false

    private var deviceUnlockManager: DeviceUnlockManager? = null
    private var databaseUri: Uri? = null

    private var mCipherJob: Job? = null
    private var mCryptoExecutionJob: Job? = null

    private var deviceUnlockMode = DeviceUnlockMode.BIOMETRIC_UNAVAILABLE
    var cryptoPrompt: DeviceUnlockCryptoPrompt? = null
        private set
    private var isAutoOpenBiometricPromptAllowed = true
    private var cryptoPromptShowPending: Boolean = false
    val isAutoPromptAllowedOrPending: Boolean
        get() = isAutoOpenBiometricPromptAllowed || cryptoPromptShowPending
    private var isUnlockProcessingOrSucceeded: Boolean = false
    private var isAuthenticatingOrDecrypting: Boolean = false

    val isUnlockInProgress: Boolean
        get() = isUnlockProcessingOrSucceeded || isAuthenticatingOrDecrypting

    private fun isDeviceInteractiveAndUnlocked(): Boolean {
        return try {
            val app = getApplication<Application>()
            val powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val keyguardManager = app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isInteractive = powerManager?.isInteractive ?: true
            val isKeyguardLocked = keyguardManager?.isKeyguardLocked ?: false
            isInteractive && !isKeyguardLocked
        } catch (_: Throwable) {
            true
        }
    }

    // TODO Retrieve credential storage from app database
    var credentialDatabaseStorage: CredentialStorage = CredentialStorage.DEFAULT

    val cipherDatabaseAction = CipherDatabaseAction.getInstance(getApplication())
    private var cipherDatabaseListener = object: CipherDatabaseAction.CipherDatabaseListener {
        override fun onCipherDatabaseRetrieved(
            databaseUri: Uri,
            cipherDatabase: CipherEncryptDatabase?
        ) {
            if (databaseUri == this@DeviceUnlockViewModel.databaseUri) {
                cipherDatabase?.let {
                    this@DeviceUnlockViewModel.cipherDatabase = it
                    if (!isUnlockProcessingOrSucceeded && !isAuthenticatingOrDecrypting) {
                        checkUnlockAvailability()
                    }
                } ?: deleteEncryptedDatabaseKey()
            }
        }
        override fun onCipherDatabaseAddedOrUpdated(cipherDatabase: CipherEncryptDatabase) {
            if (cipherDatabase.databaseUri == this@DeviceUnlockViewModel.databaseUri) {
                this@DeviceUnlockViewModel.cipherDatabase = cipherDatabase
                if (!isUnlockProcessingOrSucceeded && !isAuthenticatingOrDecrypting) {
                    checkUnlockAvailability()
                }
            }
        }
        override fun onCipherDatabaseDeleted(databaseUri: Uri) {
            if (databaseUri == this@DeviceUnlockViewModel.databaseUri) {
                this@DeviceUnlockViewModel.cipherDatabase = null
                checkUnlockAvailability()
            }
        }
        override fun onAllCipherDatabasesDeleted() {
            this@DeviceUnlockViewModel.cipherDatabase = null
            checkUnlockAvailability()
        }
        override fun onCipherDatabaseCleared() {
            this@DeviceUnlockViewModel.cipherDatabase = null
            closeBiometricPrompt()
            checkUnlockAvailability()
        }
    }

    private val _uiState = MutableStateFlow(DeviceUnlockState())
    val uiState: StateFlow<DeviceUnlockState> = _uiState.asStateFlow()

    init {
        AppLifecycleObserver.appJustLaunched
            .onEach {
                if (!isUnlockProcessingOrSucceeded
                    && !isAuthenticatingOrDecrypting
                    && !ContextualDatabase.getInstance().loaded
                ) {
                    isAutoOpenBiometricPromptAllowed = true
                    if (isDeviceInteractiveAndUnlocked()) {
                        checkUnlockAvailability()
                    } else {
                        cryptoPromptShowPending = true
                    }
                }
            }
            .launchIn(viewModelScope)
        DatabaseJobManager.getInstance().sessionState
            .onEach { sessionState ->
                when (sessionState) {
                    is DatabaseJobManager.VaultSessionState.Locked -> {
                        if (!isAuthenticatingOrDecrypting
                            && DatabaseJobManager.getInstance().activeJobsCount.value == 0
                        ) {
                            isUnlockProcessingOrSucceeded = false
                            isAutoOpenBiometricPromptAllowed = true
                        }
                    }
                    is DatabaseJobManager.VaultSessionState.Unlocked -> {
                        if (!isConditionToStoreCredentialVerified) {
                            onUnlockCompletedAndNavigating()
                        }
                    }
                }
            }
            .launchIn(viewModelScope)
        cipherDatabaseAction.registerDatabaseListener(cipherDatabaseListener)
    }

    private fun cancelAndLaunchCipherJob(
        coroutineExceptionHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, e ->
            setException(e)
        },
        block: suspend () -> Unit
    ) {
        mCipherJob?.cancel()
        mCipherJob = viewModelScope.launch(coroutineExceptionHandler) {
            block()
        }
    }

    private fun launchCryptoExecutionJob(
        coroutineExceptionHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, e ->
            isUnlockProcessingOrSucceeded = false
            isAuthenticatingOrDecrypting = false
            setException(e)
        },
        block: suspend () -> Unit
    ) {
        mCipherJob?.cancel()
        mCryptoExecutionJob?.cancel()
        mCryptoExecutionJob = viewModelScope.launch(coroutineExceptionHandler) {
            block()
        }
    }

    fun checkConditionToStoreCredential(condition: Boolean) {
        if (isUnlockProcessingOrSucceeded || isAuthenticatingOrDecrypting) {
            isConditionToStoreCredentialVerified = condition
            return
        }
        if (isConditionToStoreCredentialVerified == condition) {
            return
        }
        isConditionToStoreCredentialVerified = condition
        checkUnlockAvailability()
    }

    /**
     * Check unlock availability and change the current mode depending on device's state
     */
    fun checkUnlockAvailability() {
        if (isUnlockProcessingOrSucceeded || isAuthenticatingOrDecrypting) {
            return
        }
        if (ContextualDatabase.getInstance().loaded && !isConditionToStoreCredentialVerified) {
            return
        }
        if (PreferencesUtil.isBiometricUnlockEnable(getApplication())) {
            // biometric not supported (by API level or hardware) so keep option hidden
            // or manually disable
            val biometricCanAuthenticate = DeviceUnlockManager.canAuthenticate(getApplication())
            if (!PreferencesUtil.isDeviceUnlockEnable(getApplication())
                || biometricCanAuthenticate == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
                || biometricCanAuthenticate == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) {
                changeMode(DeviceUnlockMode.BIOMETRIC_UNAVAILABLE)
            } else if (biometricCanAuthenticate == BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED) {
                changeMode(DeviceUnlockMode.BIOMETRIC_SECURITY_UPDATE_REQUIRED)
            } else {
                // biometric is available but not configured, show icon but in disabled state with some information
                if (biometricCanAuthenticate == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                    changeMode(DeviceUnlockMode.DEVICE_CREDENTIAL_OR_BIOMETRIC_NOT_CONFIGURED)
                } else {
                    changeMode()
                }
            }
        } else if (PreferencesUtil.isDeviceCredentialUnlockEnable(getApplication())) {
            if (DeviceUnlockManager.isDeviceSecure(getApplication())) {
                changeMode()
            } else {
                changeMode(DeviceUnlockMode.DEVICE_CREDENTIAL_OR_BIOMETRIC_NOT_CONFIGURED)
            }
        }
    }

    private fun changeMode() {
        try {
            if (isConditionToStoreCredentialVerified) {
                // listen for encryption
                changeMode(DeviceUnlockMode.STORE_CREDENTIAL)
            } else if (cipherDatabase != null) {
                // biometric available but no stored password found yet for this DB
                // listen for decryption
                changeMode(DeviceUnlockMode.EXTRACT_CREDENTIAL)
            } else {
                // wait for typing
                changeMode(DeviceUnlockMode.WAIT_CREDENTIAL)
            }
        } catch (e: Exception) {
            changeMode(DeviceUnlockMode.KEY_MANAGER_UNAVAILABLE)
            setException(e)
        }
    }

    private fun changeMode(deviceUnlockMode: DeviceUnlockMode) {
        this.deviceUnlockMode = deviceUnlockMode
        when (deviceUnlockMode) {
            DeviceUnlockMode.STORE_CREDENTIAL -> initEncryptData()
            DeviceUnlockMode.EXTRACT_CREDENTIAL -> initDecryptData()
            else -> {}
        }
        _uiState.update { currentState ->
            currentState.copy(
                newDeviceUnlockMode = deviceUnlockMode,
                allowDeviceUnlockMenu = cipherDatabase != null
                        && deviceUnlockMode != DeviceUnlockMode.BIOMETRIC_UNAVAILABLE
                        && deviceUnlockMode != DeviceUnlockMode.KEY_MANAGER_UNAVAILABLE
            )
        }
    }

    private fun connectDatabase(databaseUri: Uri) {
        this.databaseUri = databaseUri
        cipherDatabaseAction.getCipherDatabase(databaseUri)
    }

    private fun showPendingIfNecessary() {
        // Reassign prompt state to open again if necessary (only when not already unlocking or loaded)
        if (!isUnlockProcessingOrSucceeded
            && !isAuthenticatingOrDecrypting
            && !ContextualDatabase.getInstance().loaded
            && uiState.value.cryptoPromptState == DeviceUnlockPromptMode.IDLE_SHOW
        ) {
            cryptoPromptShowPending = true
        } else {
            cryptoPromptShowPending = false
        }
    }

    private fun disconnectDatabase() {
        mCipherJob?.cancel()
        this.databaseUri = null
        this.cipherDatabase = null
        clearPrompt()
        changeMode(DeviceUnlockMode.BIOMETRIC_UNAVAILABLE)
    }

    fun connect(databaseUri: Uri?) {
        Log.d(TAG, "Connect to device unlock")
        if (ContextualDatabase.getInstance().loaded && !isConditionToStoreCredentialVerified) {
            return
        }
        // To get device credential unlock result, only if same database uri
        if (databaseUri != null
            && PreferencesUtil.isDeviceUnlockEnable(getApplication())) {
            if (databaseUri != this.databaseUri || this.cipherDatabase == null) {
                connectDatabase(databaseUri)
            } else if (!isUnlockProcessingOrSucceeded && !isAuthenticatingOrDecrypting) {
                checkUnlockAvailability()
            }
        } else {
            disconnectDatabase()
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnect from device unlock")
        showPendingIfNecessary()
        disconnectDatabase()
    }

    fun onUnlockCompletedAndNavigating() {
        isUnlockProcessingOrSucceeded = true
        isAuthenticatingOrDecrypting = false
        isAutoOpenBiometricPromptAllowed = false
        cryptoPromptShowPending = false
        mCipherJob?.cancel()
        closeBiometricPrompt()
        clearPrompt()
    }

    fun resetUnlockProcessingState(allowAutoOpen: Boolean = false) {
        isUnlockProcessingOrSucceeded = false
        isAuthenticatingOrDecrypting = false
        if (allowAutoOpen) {
            isAutoOpenBiometricPromptAllowed = true
        }
    }

    fun onAuthenticationSucceeded() {
        isAuthenticatingOrDecrypting = true
        cryptoPromptShowPending = false
        isAutoOpenBiometricPromptAllowed = false
        biometricPromptClosed()
        cryptoPrompt?.let { prompt ->
            when (prompt.type) {
                DeviceUnlockCryptoPromptType.CREDENTIAL_ENCRYPTION ->
                    retrieveCredentialForEncryption( prompt.cipher)
                DeviceUnlockCryptoPromptType.CREDENTIAL_DECRYPTION -> {
                    isUnlockProcessingOrSucceeded = true
                    decryptCredential( prompt.cipher)
                }
            }
        }
    }

    fun onAuthenticationSucceeded(
        result: BiometricPrompt.AuthenticationResult
    ) {
        isAuthenticatingOrDecrypting = true
        cryptoPromptShowPending = false
        isAutoOpenBiometricPromptAllowed = false
        biometricPromptClosed()
        val promptType = cryptoPrompt?.type ?: when (deviceUnlockMode) {
            DeviceUnlockMode.STORE_CREDENTIAL -> DeviceUnlockCryptoPromptType.CREDENTIAL_ENCRYPTION
            else -> DeviceUnlockCryptoPromptType.CREDENTIAL_DECRYPTION
        }
        val authenticatedCipher = result.cryptoObject?.cipher ?: cryptoPrompt?.cipher
        when (promptType) {
            DeviceUnlockCryptoPromptType.CREDENTIAL_ENCRYPTION ->
                retrieveCredentialForEncryption(authenticatedCipher)
            DeviceUnlockCryptoPromptType.CREDENTIAL_DECRYPTION -> {
                isUnlockProcessingOrSucceeded = true
                decryptCredential(authenticatedCipher)
            }
        }
    }

    private fun retrieveCredentialForEncryption(cipher: Cipher?) {
        _uiState.update { currentState ->
            currentState.copy(
                credentialRequiredCipher = cipher
            )
        }
    }

    fun encryptCredential(
        credential: ByteArray,
        cipher: Cipher?
    ) {
        launchCryptoExecutionJob {
            val manager = deviceUnlockManager ?: DeviceUnlockManager(getApplication()).also {
                deviceUnlockManager = it
            }
            manager.encryptData(
                value = credential,
                cipher = cipher,
                handleEncryptedResult = { encryptedValue, ivSpec ->
                    isAuthenticatingOrDecrypting = false
                    databaseUri?.let { databaseUri ->
                        onCredentialEncrypted(
                            CipherEncryptDatabase().apply {
                                this.databaseUri = databaseUri
                                this.credentialStorage = credentialDatabaseStorage
                                this.encryptedValue = encryptedValue
                                this.specParameters = ivSpec
                            }
                        )
                    } ?: throw UnknownDatabaseLocationException()
                }
            )
        }
        // Reinit credential storage request
        _uiState.update { currentState ->
            currentState.copy(
                credentialRequiredCipher = null
            )
        }
    }

    fun decryptCredential(cipher: Cipher?) {
        isUnlockProcessingOrSucceeded = true
        isAuthenticatingOrDecrypting = true
        cryptoPromptShowPending = false
        isAutoOpenBiometricPromptAllowed = false
        // retrieve the encrypted value from preferences
        launchCryptoExecutionJob {
            databaseUri?.let { databaseUri ->
                cipherDatabase?.let { cipherDb ->
                    val manager = deviceUnlockManager ?: DeviceUnlockManager(getApplication()).also {
                        deviceUnlockManager = it
                    }
                    manager.decryptData(
                        encryptedValue = cipherDb.encryptedValue,
                        cipher = cipher,
                        ivSpecValue = cipherDb.specParameters,
                        handleDecryptedResult = { decryptedValue ->
                            isAuthenticatingOrDecrypting = false
                            // Load database directly with password retrieve
                            onCredentialDecrypted(
                                CipherDecryptDatabase().apply {
                                    this.databaseUri = databaseUri
                                    this.credentialStorage = credentialDatabaseStorage
                                    this.decryptedValue = decryptedValue
                                }
                            )
                            cipherDatabaseAction.resetCipherParameters(databaseUri)
                        }
                    )
                } ?: run {
                    isUnlockProcessingOrSucceeded = false
                    isAuthenticatingOrDecrypting = false
                    deleteEncryptedDatabaseKey()
                }
            } ?: run {
                isUnlockProcessingOrSucceeded = false
                isAuthenticatingOrDecrypting = false
                throw UnknownDatabaseLocationException()
            }
        }
    }

    fun onCredentialEncrypted(cipherEncryptDatabase: CipherEncryptDatabase) {
        _uiState.update { currentState ->
            currentState.copy(
                cipherEncryptDatabase = cipherEncryptDatabase
            )
        }
    }

    fun consumeCredentialEncrypted() {
        _uiState.update { currentState ->
            currentState.copy(
                cipherEncryptDatabase = null
            )
        }
    }

    fun onCredentialDecrypted(cipherDecryptDatabase: CipherDecryptDatabase) {
        _uiState.update { currentState ->
            currentState.copy(
                cipherDecryptDatabase = cipherDecryptDatabase
            )
        }
    }

    fun consumeCredentialDecrypted() {
        _uiState.update { currentState ->
            currentState.copy(
                cipherDecryptDatabase = null
            )
        }
    }

    fun onPromptRequested(
        cryptoPrompt: DeviceUnlockCryptoPrompt,
        autoOpen: Boolean = false
    ) {
        if (isUnlockProcessingOrSucceeded
            || isAuthenticatingOrDecrypting
            || (ContextualDatabase.getInstance().loaded && !isConditionToStoreCredentialVerified)
        ) {
            return
        }
        this@DeviceUnlockViewModel.cryptoPrompt = cryptoPrompt
        val shouldAutoOpen = cryptoPromptShowPending
                || (autoOpen && PreferencesUtil.isDeviceUnlockPromptAutoOpenEnable(getApplication()))
        if (shouldAutoOpen) {
            if (isDeviceInteractiveAndUnlocked()) {
                showPrompt()
            } else {
                cryptoPromptShowPending = true
                isAutoOpenBiometricPromptAllowed = true
            }
        }
    }

    fun deferPromptUntilInteractive() {
        cryptoPromptShowPending = true
        isAutoOpenBiometricPromptAllowed = true
        _uiState.update { currentState ->
            currentState.copy(
                cryptoPromptState = DeviceUnlockPromptMode.IDLE_CLOSE
            )
        }
    }

    fun onActivityInteractiveFocusGained(databaseUri: Uri?) {
        if (isUnlockProcessingOrSucceeded
            || isAuthenticatingOrDecrypting
            || (ContextualDatabase.getInstance().loaded && !isConditionToStoreCredentialVerified)
            || DatabaseJobManager.getInstance().activeJobsCount.value > 0
            || !isDeviceInteractiveAndUnlocked()
        ) {
            return
        }
        if (cryptoPromptShowPending
            || (isAutoOpenBiometricPromptAllowed && PreferencesUtil.isDeviceUnlockPromptAutoOpenEnable(getApplication()))
        ) {
            if (cryptoPrompt != null) {
                showPrompt()
            } else {
                connect(databaseUri)
            }
        }
    }

    fun showPrompt() {
        if (isUnlockProcessingOrSucceeded
            || isAuthenticatingOrDecrypting
            || (ContextualDatabase.getInstance().loaded && !isConditionToStoreCredentialVerified)
        ) {
            return
        }
        AppLifecycleObserver.lockBackgroundEvent = true
        isAutoOpenBiometricPromptAllowed = false
        cryptoPromptShowPending = false
        if (cryptoPrompt == null) {
            checkUnlockAvailability()
        }
        _uiState.update { currentState ->
            currentState.copy(
                cryptoPromptState = DeviceUnlockPromptMode.SHOW
            )
        }
    }

    fun promptShown() {
        _uiState.update { currentState ->
            currentState.copy(
                cryptoPromptState = DeviceUnlockPromptMode.IDLE_SHOW
            )
        }
    }

    fun setException(value: Throwable?) {
        _uiState.update { currentState ->
            currentState.copy(
                exception = value
            )
        }
    }

    fun exceptionShown() {
        _uiState.update { currentState ->
            currentState.copy(
                exception = null
            )
        }
    }

    private fun initEncryptData() {
        if (isAuthenticatingOrDecrypting) return
        cancelAndLaunchCipherJob {
            deviceUnlockManager = DeviceUnlockManager(getApplication())
            deviceUnlockManager?.initEncryptData { cryptoPrompt ->
                onPromptRequested(cryptoPrompt)
            } ?: throw Exception("Device unlock manager not initialized")
        }
    }

    private fun initDecryptData() {
        if (isUnlockProcessingOrSucceeded
            || isAuthenticatingOrDecrypting
            || ContextualDatabase.getInstance().loaded
        ) {
            return
        }
        cancelAndLaunchCipherJob {
            cipherDatabase?.let { cipherDb ->
                    deviceUnlockManager = DeviceUnlockManager(getApplication())
                    deviceUnlockManager?.initDecryptData(cipherDb.specParameters) { cryptoPrompt ->
                        onPromptRequested(
                            cryptoPrompt,
                            autoOpen = isAutoOpenBiometricPromptAllowed
                        )
                    } ?: throw Exception("Device unlock manager not initialized")
            } ?: throw Exception("Cipher database not initialized")
        }
    }

    fun deleteEncryptedDatabaseKey() {
        closeBiometricPrompt()
        databaseUri?.let { databaseUri ->
            cipherDatabaseAction.deleteByDatabaseUri(databaseUri)
        } ?: run {
            checkUnlockAvailability()
        }
        _uiState.update { currentState ->
            currentState.copy(
                allowDeviceUnlockMenu = false
            )
        }
    }

    fun closeBiometricPrompt() {
        _uiState.update { currentState ->
            currentState.copy(
                cryptoPromptState = DeviceUnlockPromptMode.CLOSE
            )
        }
    }

    fun biometricPromptClosed() {
        _uiState.update { currentState ->
            currentState.copy(
                cryptoPromptState = DeviceUnlockPromptMode.IDLE_CLOSE
            )
        }
    }

    private fun clearPrompt() {
        if (!isAuthenticatingOrDecrypting) {
            cryptoPrompt = null
            deviceUnlockManager = null
        }
    }

    fun clearData() {
        clearPrompt()
    }

    override fun onCleared() {
        super.onCleared()
        cipherDatabaseAction.unregisterDatabaseListener(cipherDatabaseListener)
        clearPrompt()
    }

    companion object {
        private val TAG = DeviceUnlockViewModel::class.java.simpleName
    }
}

enum class DeviceUnlockPromptMode {
    IDLE_CLOSE, IDLE_SHOW, SHOW, CLOSE
}

data class DeviceUnlockState(
    val newDeviceUnlockMode: DeviceUnlockMode = DeviceUnlockMode.BIOMETRIC_UNAVAILABLE,
    val allowDeviceUnlockMenu: Boolean = false,
    val credentialRequiredCipher: Cipher? = null,
    val cipherEncryptDatabase: CipherEncryptDatabase? = null,
    val cipherDecryptDatabase: CipherDecryptDatabase? = null,
    val cryptoPromptState: DeviceUnlockPromptMode = DeviceUnlockPromptMode.IDLE_CLOSE,
    val autoOpenPrompt: Boolean = false,
    val exception: Throwable? = null
)