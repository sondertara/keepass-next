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
package com.kunzisoft.keepass.model

import com.kunzisoft.keepass.database.element.Group
import com.kunzisoft.keepass.database.element.node.EmptyNodeFilter
import com.kunzisoft.keepass.database.element.node.NodeFilter
import com.kunzisoft.keepass.database.element.template.TemplateBuilder
import com.kunzisoft.keepass.database.element.template.TemplateField
import com.kunzisoft.keepass.otp.OtpEntryFields


/**
 * Filter and flattener for vault entries aligned with the 10 official templates.
 */
object VaultNodeFilter {

    /**
     * Filter a list of [SortedNodeInfo] according to the selected [VaultCategory].
     */
    fun filter(
        nodes: List<SortedNodeInfo>,
        category: VaultCategory
    ): List<SortedNodeInfo> {
        if (category == VaultCategory.ALL) return nodes
        return nodes.filter { node ->
            node is EntryInfo && matchesCategory(node, category)
        }
    }

    fun matchesCategory(entry: EntryInfo, category: VaultCategory): Boolean {
        return when (category) {
            VaultCategory.ALL -> true
            VaultCategory.PASSWORDS -> isPasswordEntry(entry)
            VaultCategory.TOTP -> isTotpEntry(entry)
            VaultCategory.PASSKEYS -> isPasskeyEntry(entry)
            VaultCategory.CREDIT_CARD -> isCreditCardEntry(entry)
            VaultCategory.SECURE_NOTE -> isSecureNoteEntry(entry)
            VaultCategory.EMAIL -> isEmailEntry(entry)
            VaultCategory.WIFI -> isWifiEntry(entry)
            VaultCategory.ID_CARD -> isIdCardEntry(entry)
            VaultCategory.BANK -> isBankEntry(entry)
            VaultCategory.CRYPTOCURRENCY -> isCryptoEntry(entry)
        }
    }

    fun isPasswordEntry(entry: EntryInfo): Boolean {
        if (isEmailEntry(entry) || isWifiEntry(entry) || isIdCardEntry(entry) ||
            isBankEntry(entry) || isCryptoEntry(entry) || isCreditCardEntry(entry)
        ) {
            return false
        }
        val templateTitle = entry.template.title
        if (templateTitle.equals(TemplateField.LABEL_TOTP, ignoreCase = true) ||
            templateTitle.equals(PasskeyEntryFields.PASSKEY_FIELD, ignoreCase = true) ||
            templateTitle.equals(TemplateField.LABEL_SECURE_NOTE, ignoreCase = true)
        ) {
            return false
        }
        return entry.password.isNotEmpty()
    }

    fun isPasskeyEntry(entry: EntryInfo): Boolean {
        val tpl = entry.template
        return entry.passkey != null
                || tpl.uuid == TemplateBuilder.UUID_PASSKEY
                || tpl.title.equals(PasskeyEntryFields.PASSKEY_FIELD, ignoreCase = true)
                || entry.containsCustomField(PasskeyEntryFields.FIELD_USERNAME)
                || entry.tags.contains(PasskeyEntryFields.PASSKEY_TAG)
                || entry.tags.toStringList().any { it.equals("passkey", ignoreCase = true) }
    }

    fun isTotpEntry(entry: EntryInfo): Boolean {
        val tpl = entry.template
        return entry.otpModel != null
                || tpl.uuid == TemplateBuilder.UUID_TOTP
                || tpl.title.equals(TemplateField.LABEL_TOTP, ignoreCase = true)
                || entry.containsCustomField(OtpEntryFields.OTP_FIELD)
                || entry.tags.contains(OtpEntryFields.OTP_TAG)
                || entry.tags.toStringList().any {
                    it.equals("otp", ignoreCase = true)
                            || it.equals("2fa", ignoreCase = true)
                            || it.equals("totp", ignoreCase = true)
                }
    }

    fun isCreditCardEntry(entry: EntryInfo): Boolean {
        val tpl = entry.template
        return entry.creditCard?.number?.isNotEmpty() == true
                || tpl.uuid == TemplateBuilder.UUID_CREDIT_CARD
                || tpl.title.equals(TemplateField.LABEL_DEBIT_CREDIT_CARD, ignoreCase = true)
    }

    fun isSecureNoteEntry(entry: EntryInfo): Boolean {
        val tpl = entry.template
        if (tpl.uuid == TemplateBuilder.UUID_NOTES ||
            tpl.title.equals(TemplateField.LABEL_SECURE_NOTE, ignoreCase = true)
        ) {
            return true
        }
        return !isTotpEntry(entry) && !isPasskeyEntry(entry) && !isCreditCardEntry(entry) &&
                !isEmailEntry(entry) && !isWifiEntry(entry) && !isIdCardEntry(entry) &&
                !isBankEntry(entry) && !isCryptoEntry(entry) &&
                entry.username.isEmpty() && entry.password.isEmpty() && entry.notes.isNotEmpty()
    }

    fun isEmailEntry(entry: EntryInfo): Boolean {
        val tpl = entry.template
        return tpl.uuid == TemplateBuilder.UUID_EMAIL ||
                tpl.title.equals(TemplateField.LABEL_EMAIL, ignoreCase = true)
    }

    fun isWifiEntry(entry: EntryInfo): Boolean {
        val tpl = entry.template
        return tpl.uuid == TemplateBuilder.UUID_WIFI ||
                tpl.title.equals(TemplateField.LABEL_WIRELESS, ignoreCase = true)
    }

    fun isIdCardEntry(entry: EntryInfo): Boolean {
        val tpl = entry.template
        return tpl.uuid == TemplateBuilder.UUID_ID_CARD ||
                tpl.title.equals(TemplateField.LABEL_ID_CARD, ignoreCase = true)
    }

    fun isBankEntry(entry: EntryInfo): Boolean {
        val tpl = entry.template
        return tpl.uuid == TemplateBuilder.UUID_BANK ||
                tpl.title.equals(TemplateField.LABEL_BANK, ignoreCase = true)
    }

    fun isCryptoEntry(entry: EntryInfo): Boolean {
        val tpl = entry.template
        return tpl.uuid == TemplateBuilder.UUID_CRYPTO ||
                tpl.title.equals(TemplateField.LABEL_CRYPTOCURRENCY, ignoreCase = true)
    }

    /**
     * Recursively collect all entries from [group] into a flattened list of [SortedEntryInfo].
     * Uses O(1) sequential indexing and cached group paths instead of O(N^2) indexInParent() lookups.
     */
    fun getFlattenedEntries(
        database: DatabaseInfo,
        group: Group,
        nodeFilter: NodeFilter = EmptyNodeFilter(),
        category: VaultCategory = VaultCategory.ALL
    ): List<SortedEntryInfo> {
        val result = ArrayList<SortedEntryInfo>(128)
        val recycleBinId = if (database.isRecycleBinEnabled) database.recycleBin?.nodeId else null
        val isTargetRecycleBin = recycleBinId != null && group.nodeId == recycleBinId
        var flatIndex = 0

        fun collect(currentGroup: Group, currentPath: String) {
            val childEntries = currentGroup.getChildEntries()
            for (i in childEntries.indices) {
                val entry = childEntries[i]
                if (!nodeFilter.filter(entry)) continue

                val entryInfo = database.getEntryInfoFrom(entry)
                if (matchesCategory(entryInfo, category)) {
                    result.add(
                        SortedEntryInfo(
                            entryToCopy = entryInfo,
                            path = currentPath,
                            indexInParent = flatIndex++,
                            isReferenced = false
                        )
                    )
                }
            }

            val childGroups = currentGroup.getChildGroups()
            for (i in childGroups.indices) {
                val subGroup = childGroups[i]
                if (!isTargetRecycleBin && recycleBinId != null && subGroup.nodeId == recycleBinId) {
                    continue
                }
                if (!nodeFilter.filter(subGroup)) continue
                val subPath = if (currentPath.isEmpty() || currentPath == "/") {
                    "/${subGroup.title}"
                } else {
                    "$currentPath/${subGroup.title}"
                }
                collect(subGroup, subPath)
            }
        }

        val startPath = if (group.nodeId == database.rootGroup?.nodeId) "/" else group.getPathString()
        collect(group, startPath)
        return result
    }
}
