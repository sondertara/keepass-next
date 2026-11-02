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
package com.kunzisoft.keepass.otp.scanner

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.kunzisoft.keepass.database.DatabaseTaskProvider.Companion.startDatabaseService
import com.kunzisoft.keepass.database.element.GroupId
import com.kunzisoft.keepass.database.element.icon.IconImage
import com.kunzisoft.keepass.database.element.icon.IconImageStandard
import com.kunzisoft.keepass.database.element.template.TemplateBuilder
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.otp.OtpElement
import com.kunzisoft.keepass.otp.OtpEntryFields
import com.kunzisoft.keepass.otp.OtpEntryFields.setOtp
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService
import java.util.Locale

/**
 * Helper to parse QR text and quickly create a 2FA entry in database.
 */
object OtpEntryQuickCreator {

    data class ParsedOtp(
        val rawUri: String,
        val otpElement: OtpElement,
        val displayTitle: String,
        val displayAccount: String
    )

    /**
     * Parse raw string from camera QR or image.
     * Supports standard otpauth:// and raw Base32 secret fallback.
     */
    fun parse(rawScanText: String): ParsedOtp? {
        val trimmed = rawScanText.trim()
        val normalizedUri = if (trimmed.startsWith("otpauth://", ignoreCase = true)) {
            trimmed
        } else {
            // Check if it's a raw base32 secret (16-128 chars A-Z 2-7)
            val cleaned = trimmed.replace(" ", "").uppercase(Locale.US)
            if (cleaned.matches(Regex("^[A-Z2-7=]{16,128}$"))) {
                "otpauth://totp/Token?secret=$cleaned"
            } else {
                return null
            }
        }

        val otpElement = OtpEntryFields.parseOTPUri(normalizedUri) ?: return null

        val pathPrefix = try {
            val rawPath = Uri.parse(normalizedUri).path?.removePrefix("/")?.let { Uri.decode(it) }.orEmpty()
            if (rawPath.contains(":")) rawPath.substringBefore(":").trim() else ""
        } catch (_: Exception) {
            ""
        }

        val displayTitle = when {
            pathPrefix.isNotEmpty() && (!otpElement.isIssuerPopulated() || pathPrefix.length > otpElement.issuer.length) -> {
                pathPrefix
            }
            otpElement.isIssuerPopulated() -> {
                otpElement.issuer
            }
            otpElement.isNamePopulated() -> {
                otpElement.name
            }
            else -> {
                "2FA Token"
            }
        }

        val displayAccount = if (otpElement.isNamePopulated() && otpElement.name != displayTitle) {
            otpElement.name
        } else {
            ""
        }

        return ParsedOtp(
            rawUri = normalizedUri,
            otpElement = otpElement,
            displayTitle = displayTitle,
            displayAccount = displayAccount
        )
    }

    /**
     * Build an EntryInfo populated with the parsed OTP.
     */
    fun buildEntryInfo(parsedOtp: ParsedOtp): EntryInfo {
        val entry = EntryInfo().apply {
            template = TemplateBuilder().totp
            icon = IconImage(IconImageStandard(IconImageStandard.KEY_ID))
            title = parsedOtp.displayTitle
            if (parsedOtp.displayAccount.isNotEmpty()) {
                username = parsedOtp.displayAccount
            }
            setOtp(parsedOtp.rawUri, addTag = false)
        }
        return entry
    }

    /**
     * Create EntryInfo and dispatch database task to persist it in the current group.
     */
    fun createAndSaveEntry(
        context: Context,
        parentId: GroupId,
        parsedOtp: ParsedOtp
    ): EntryInfo {
        val newEntry = buildEntryInfo(parsedOtp)
        context.startDatabaseService(
            Bundle().apply {
                putParcelable(DatabaseTaskNotificationService.PARENT_ID_KEY, parentId)
                putParcelable(DatabaseTaskNotificationService.ENTRY_KEY, newEntry)
                putBoolean(DatabaseTaskNotificationService.SAVE_DATABASE_KEY, true)
            },
            DatabaseTaskNotificationService.ACTION_DATABASE_CREATE_ENTRY_TASK
        )
        return newEntry
    }
}
