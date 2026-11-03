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

import com.kunzisoft.keepass.database.element.Field
import com.kunzisoft.keepass.database.element.security.ProtectedString
import com.kunzisoft.keepass.otp.OtpEntryFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultNodeFilterTest {

    private fun createSortedEntry(
        title: String,
        password: String = "",
        tags: List<String> = emptyList(),
        isPasskey: Boolean = false,
        isTotp: Boolean = false
    ): SortedEntryInfo {
        val entry = EntryInfo().apply {
            this.title = title
            this.password = password.toCharArray()
            tags.forEach { this.tags.put(it) }

            if (isPasskey) {
                this.addOrReplaceField(Field(PasskeyEntryFields.FIELD_USERNAME, ProtectedString(false, "user1")))
            }

            if (isTotp) {
                this.tags.put(OtpEntryFields.OTP_TAG)
            }
        }
        return SortedEntryInfo(entry)
    }

    private fun createSortedGroup(name: String): SortedGroupInfo {
        val group = GroupInfo().apply {
            this.title = name
        }
        return SortedGroupInfo(group)
    }

    @Test
    fun testFilterAll() {
        val nodes = listOf(
            createSortedGroup("Work"),
            createSortedEntry("Regular Login", "pass123"),
            createSortedEntry("Passkey Account", isPasskey = true),
            createSortedEntry("TOTP Token", isTotp = true)
        )

        val result = VaultNodeFilter.filter(nodes, VaultCategory.ALL)
        assertEquals(4, result.size)
    }

    @Test
    fun testFilterPasswords() {
        val nodes = listOf(
            createSortedGroup("Work"),
            createSortedEntry("Has Password", "secret!"),
            createSortedEntry("No Password", ""),
            createSortedEntry("Passkey Account", "", isPasskey = true)
        )

        val result = VaultNodeFilter.filter(nodes, VaultCategory.PASSWORDS)
        assertEquals(1, result.size)
        assertEquals("Has Password", (result[0] as EntryInfo).title)
    }

    @Test
    fun testFilterPasskeys() {
        val nodes = listOf(
            createSortedGroup("Personal"),
            createSortedEntry("Regular Login", "pass"),
            createSortedEntry("Google Passkey", isPasskey = true),
            createSortedEntry("GitHub Passkey Tag", tags = listOf(PasskeyEntryFields.PASSKEY_TAG))
        )

        val result = VaultNodeFilter.filter(nodes, VaultCategory.PASSKEYS)
        assertEquals(2, result.size)
        assertTrue(result.all { VaultNodeFilter.isPasskeyEntry(it as EntryInfo) })
    }

    @Test
    fun testFilterTotp() {
        val nodes = listOf(
            createSortedGroup("2FA Group"),
            createSortedEntry("AWS MFA", isTotp = true),
            createSortedEntry("Cloudflare Token", tags = listOf("2FA")),
            createSortedEntry("Just Password", "p@ss")
        )

        val result = VaultNodeFilter.filter(nodes, VaultCategory.TOTP)
        assertEquals(2, result.size)
        assertTrue(result.all { VaultNodeFilter.isTotpEntry(it as EntryInfo) })
    }

    @Test
    fun testFilterOfficialTemplates() {
        val cardEntry = SortedEntryInfo(EntryInfo().apply {
            title = "Visa Card"
            template = com.kunzisoft.keepass.database.element.template.TemplateBuilder().creditCard
        })
        val noteEntry = SortedEntryInfo(EntryInfo().apply {
            title = "Recovery Phrase"
            template = com.kunzisoft.keepass.database.element.template.TemplateBuilder().notes
        })
        val bankEntry = SortedEntryInfo(EntryInfo().apply {
            title = "Bank Account"
            template = com.kunzisoft.keepass.database.element.template.TemplateBuilder().bank
        })
        val nodes = listOf(cardEntry, noteEntry, bankEntry)

        assertEquals(1, VaultNodeFilter.filter(nodes, VaultCategory.CREDIT_CARD).size)
        assertEquals(1, VaultNodeFilter.filter(nodes, VaultCategory.SECURE_NOTE).size)
        assertEquals(1, VaultNodeFilter.filter(nodes, VaultCategory.BANK).size)
    }

    @Test
    fun testResolveTemplateFromFields() {
        val wifiEntry = EntryInfo().apply {
            title = "Home Wi-Fi"
            addOrReplaceField(Field(com.kunzisoft.keepass.database.element.template.TemplateField.LABEL_SSID, ProtectedString(false, "MyNet")))
        }
        assertEquals(
            com.kunzisoft.keepass.database.element.template.TemplateBuilder.UUID_WIFI,
            VaultNodeFilter.resolveTemplate(wifiEntry).uuid
        )

        val cryptoEntry = EntryInfo().apply {
            title = "BTC Wallet"
            addOrReplaceField(Field(com.kunzisoft.keepass.database.element.template.TemplateField.LABEL_SEED, ProtectedString(true, "twelve words")))
        }
        assertEquals(
            com.kunzisoft.keepass.database.element.template.TemplateBuilder.UUID_CRYPTO,
            VaultNodeFilter.resolveTemplate(cryptoEntry).uuid
        )
    }
}
