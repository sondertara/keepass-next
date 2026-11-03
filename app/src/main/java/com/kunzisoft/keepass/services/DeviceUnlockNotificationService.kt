package com.kunzisoft.keepass.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.app.database.CipherDatabaseEntity
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.timeout.TimeoutHelper.NEVER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DeviceUnlockNotificationService : NotificationService() {

    private lateinit var mTempCipherDao: MutableList<CipherDatabaseEntity>

    private var mActionTaskBinder = DeviceUnlockBinder()

    inner class DeviceUnlockBinder: Binder() {
        fun getCipherDatabase(databaseUri: Uri): CipherDatabaseEntity? {
            return mTempCipherDao.firstOrNull { it.databaseUri == databaseUri.toString()}
        }
        fun addOrUpdateCipherDatabase(cipherDatabaseEntity: CipherDatabaseEntity) {
            val cipherDatabaseRetrieve = mTempCipherDao.firstOrNull {
                it.databaseUri == cipherDatabaseEntity.databaseUri
            }
            cipherDatabaseRetrieve?.replaceContent(cipherDatabaseEntity)
                    ?: mTempCipherDao.add(cipherDatabaseEntity)
        }
        fun deleteByDatabaseUri(databaseUri: Uri) {
            mTempCipherDao.firstOrNull { it.databaseUri == databaseUri.toString() }?.let {
                mTempCipherDao.remove(it)
            }
        }
        fun resetTimer() {
            startSilentExpiryTimer()
        }
        fun deleteAll() {
            mTempCipherDao.clear()
        }
    }

    override val notificationId: Int = 593

    override fun retrieveChannelId(): String {
        return CHANNEL_DEVICE_UNLOCK_ID
    }

    override fun retrieveChannelName(): String {
        return getString(R.string.device_unlock)
    }

    override fun onCreate() {
        super.onCreate()
        mTempCipherDao = mutableListOf()
    }

    private fun startSilentExpiryTimer() {
        val notificationTimeoutMilliSecs = PreferencesUtil.getDeviceUnlockTimeout(this)
        mTimerJob?.cancel()
        if (notificationTimeoutMilliSecs > NEVER) {
            mTimerJob = CoroutineScope(Dispatchers.Main).launch {
                delay(notificationTimeoutMilliSecs)
                sendBroadcast(Intent(REMOVE_DEVICE_UNLOCK_KEY_ACTION))
                mTimerJob = null
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        // Silent in-memory session storage without persistent foreground notification
        startSilentExpiryTimer()
        return mActionTaskBinder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopSelf()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        mTempCipherDao.clear()
        super.onDestroy()
    }

    class DeviceUnlockReceiver(var removeKeyAction: () -> Unit): BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.action?.let {
                when (it) {
                    REMOVE_DEVICE_UNLOCK_KEY_ACTION -> {
                        removeKeyAction.invoke()
                    }
                }
            }
        }
    }

    companion object {
        private const val CHANNEL_DEVICE_UNLOCK_ID = "com.kunzisoft.keepass.notification.channel.unlock"
        const val REMOVE_DEVICE_UNLOCK_KEY_ACTION = "com.kunzisoft.keepass.REMOVE_DEVICE_UNLOCK_KEY"

        // Only one service connection
        fun bindService(context: Context, serviceConnection: ServiceConnection, flags: Int) {
            context.bindService(Intent(context,
                DeviceUnlockNotificationService::class.java),
                    serviceConnection,
                    flags)
        }

        fun unbindService(context: Context, serviceConnection: ServiceConnection) {
            context.unbindService(serviceConnection)
        }
    }
}
