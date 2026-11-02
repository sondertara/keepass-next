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
package com.kunzisoft.keepass.app

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.stylish.Stylish
import com.kunzisoft.keepass.otp.scanner.QrScanActivity
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver)

        Stylish.load(this)
        registerQuickScanShortcut()
    }

    private fun registerQuickScanShortcut() {
        try {
            val shortcutManager = getSystemService(ShortcutManager::class.java) ?: return
            val scanIntent = Intent(Intent.ACTION_VIEW, null, this, QrScanActivity::class.java).apply {
                putExtra(QrScanActivity.EXTRA_STANDALONE_MODE, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val scanShortcut = ShortcutInfo.Builder(this, "shortcut_qr_scan")
                .setShortLabel(getString(R.string.shortcut_qr_scan_short))
                .setLongLabel(getString(R.string.shortcut_qr_scan_long))
                .setIcon(Icon.createWithResource(this, R.drawable.ic_qr_code_scanner_24dp))
                .setIntent(scanIntent)
                .build()
            shortcutManager.dynamicShortcuts = listOf(scanShortcut)
        } catch (_: Exception) {
        }
    }
}

object AppLifecycleObserver : DefaultLifecycleObserver {

    var isAppInForeground: Boolean = false
        private set

    var lockBackgroundEvent = false

    private val _appJustLaunched = MutableSharedFlow<Unit>(replay = 0)
    val appJustLaunched = _appJustLaunched.asSharedFlow()


    @OptIn(DelicateCoroutinesApi::class)
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        val wasPreviouslyInBackground = !isAppInForeground
        isAppInForeground = true
        if (!lockBackgroundEvent && wasPreviouslyInBackground) {
            GlobalScope.launch {
                _appJustLaunched.emit(Unit)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        isAppInForeground = false
    }
}
