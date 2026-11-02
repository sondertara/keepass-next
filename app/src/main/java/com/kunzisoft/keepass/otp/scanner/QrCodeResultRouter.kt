/*
 * Copyright 2026 KeePassDX Contributors.
 *
 * This file is part of KeePassDX.
 *
 * KeePassDX is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * KeePassDX is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with KeePassDX. If not, see <http://www.gnu.org/licenses/>.
 */
package com.kunzisoft.keepass.otp.scanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.credentialprovider.activity.EntrySelectionLauncherActivity

/**
 * Unified router for scanned QR payloads:
 * - FIDO2 / Passkey hybrid transport URIs (`fido:/...`) -> System Credential Manager / GMS FIDO handler
 * - TOTP / HOTP URIs (`otpauth://...`) -> Direct vault creation or EntrySelectionLauncherActivity
 */
object QrCodeResultRouter {

    private const val TAG = "QrCodeResultRouter"

    fun isFidoQr(qrText: String): Boolean {
        return qrText.trim().startsWith("fido:/", ignoreCase = true)
    }

    /**
     * Launches the Android system FIDO / Passkey hybrid transport handler for a `fido:/...` URI.
     */
    fun launchFidoQr(context: Context, qrText: String): Boolean {
        val trimmed = qrText.trim()
        if (!isFidoQr(trimmed)) return false

        val uri = Uri.parse(trimmed)
        return try {
            val browsableIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browsableIntent)
            true
        } catch (firstError: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch FIDO intent for $trimmed", e)
                Toast.makeText(
                    context,
                    R.string.qr_fido_not_supported,
                    Toast.LENGTH_LONG
                ).show()
                false
            }
        }
    }

    /**
     * Dispatches a scanned QR result when launched in standalone mode (e.g., from Unlock screen,
     * Home Screen shortcut, or Quick Settings tile where no caller Activity awaits a result).
     */
    fun dispatchStandaloneResult(context: Context, qrText: String): Boolean {
        val trimmed = qrText.trim()
        if (isFidoQr(trimmed)) {
            return launchFidoQr(context, trimmed)
        }

        if (trimmed.startsWith("otpauth://", ignoreCase = true)) {
            return try {
                val otpIntent = Intent(context, EntrySelectionLauncherActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(trimmed)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(otpIntent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to route otpauth URI in standalone mode", e)
                Toast.makeText(context, R.string.qr_invalid_otp, Toast.LENGTH_SHORT).show()
                false
            }
        }

        val parsedOtp = OtpEntryQuickCreator.parse(trimmed)
        if (parsedOtp != null) {
            return try {
                val syntheticUri = Uri.parse(parsedOtp.rawUri)
                val otpIntent = Intent(context, EntrySelectionLauncherActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = syntheticUri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(otpIntent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to route raw OTP secret in standalone mode", e)
                false
            }
        }

        Toast.makeText(context, R.string.qr_invalid_otp, Toast.LENGTH_LONG).show()
        return false
    }
}
