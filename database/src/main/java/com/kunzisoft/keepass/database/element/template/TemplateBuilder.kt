/*
 * Copyright 2021 Jeremy Jamet / Kunzisoft.
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
 */
package com.kunzisoft.keepass.database.element.template

import com.kunzisoft.keepass.database.element.icon.IconImage
import com.kunzisoft.keepass.database.element.icon.IconImageStandard
import com.kunzisoft.keepass.model.PasskeyEntryFields
import com.kunzisoft.keepass.otp.OtpEntryFields.OTP_TOKEN_FIELD
import java.util.UUID

class TemplateBuilder {

    private val urlAttribute = TemplateAttribute(
        label = TemplateField.LABEL_URL,
        type = TemplateAttributeType.TEXT,
        protected = false,
        options = TemplateAttributeOption().apply {
            setLink(true)
        }
    )
    private val usernameAttribute = TemplateAttribute(
        label = TemplateField.LABEL_USERNAME,
        type = TemplateAttributeType.TEXT
    )
    private val notesAttribute = TemplateAttribute(
        label = TemplateField.LABEL_NOTES,
        type = TemplateAttributeType.TEXT,
        protected = false,
        options = TemplateAttributeOption().apply {
            setNumberLinesToMany()
        }
    )
    private val holderAttribute = TemplateAttribute(
        label = TemplateField.LABEL_HOLDER,
        type = TemplateAttributeType.TEXT
    )
    private val numberAttribute = TemplateAttribute(
        label = TemplateField.LABEL_NUMBER,
        type = TemplateAttributeType.TEXT
    )
    private val cvvAttribute = TemplateAttribute(
        label = TemplateField.LABEL_CVV,
        type = TemplateAttributeType.TEXT,
        protected = true
    )
    private val pinAttribute = TemplateAttribute(
        label = TemplateField.LABEL_PIN,
        type = TemplateAttributeType.TEXT,
        protected = true
    )
    private val nameAttribute = TemplateAttribute(
        label = TemplateField.LABEL_NAME,
        type = TemplateAttributeType.TEXT
    )
    private val placeOfIssueAttribute = TemplateAttribute(
        label = TemplateField.LABEL_PLACE_OF_ISSUE,
        type = TemplateAttributeType.TEXT
    )
    private val dateOfIssueAttribute = TemplateAttribute(
        label = TemplateField.LABEL_DATE_OF_ISSUE,
        type = TemplateAttributeType.DATETIME,
        protected = false,
        options = TemplateAttributeOption().apply {
            setDateFormatToDate()
        }
    )
    private val expirationDateAttribute = TemplateAttribute(
        label = TemplateField.LABEL_EXPIRATION,
        type = TemplateAttributeType.DATETIME,
        protected = false,
        options = TemplateAttributeOption().apply {
            setDateFormatToDate()
        }
    )
    private val emailAddressAttribute = TemplateAttribute(
        label = TemplateField.LABEL_USERNAME,
        type = TemplateAttributeType.TEXT,
        options = TemplateAttributeOption().apply {
            alias = TemplateField.LABEL_EMAIL_ADDRESS
        }
    )
    private val passwordAttribute = TemplateAttribute(
        label = TemplateField.LABEL_PASSWORD,
        type = TemplateAttributeType.TEXT,
        protected = true,
        options = TemplateAttributeOption().apply {
            setNumberLines(3)
            associatePasswordGenerator()
        }
    )
    private val ssidAttribute = TemplateAttribute(
        label = TemplateField.LABEL_SSID,
        type = TemplateAttributeType.TEXT
    )
    private val wirelessTypeAttribute = TemplateAttribute(
        label = TemplateField.LABEL_TYPE,
        type = TemplateAttributeType.LIST,
        protected = false,
        options = TemplateAttributeOption().apply{
            setListItems("WPA3", "WPA2", "WPA", "WEP")
            default = "WPA2"
        }
    )
    private val tokenAttribute = TemplateAttribute(
        label = TemplateField.LABEL_TOKEN,
        type = TemplateAttributeType.TEXT
    )
    private val otpTokenAttribute = TemplateAttribute(
        label = OTP_TOKEN_FIELD,
        type = TemplateAttributeType.TEXT
    )
    private val publicKeyAttribute = TemplateAttribute(
        label = TemplateField.LABEL_PUBLIC_KEY,
        type = TemplateAttributeType.TEXT
    )
    private val privateKeyAttribute = TemplateAttribute(
        label = TemplateField.LABEL_PRIVATE_KEY,
        type = TemplateAttributeType.TEXT,
        protected = true
    )
    private val seedAttribute = TemplateAttribute(
        label = TemplateField.LABEL_SEED,
        type = TemplateAttributeType.TEXT,
        protected = true
    )
    private val bicAttribute = TemplateAttribute(
        label = TemplateField.LABEL_BIC,
        type = TemplateAttributeType.TEXT
    )
    private val ibanAttribute = TemplateAttribute(
        label = TemplateField.LABEL_IBAN,
        type = TemplateAttributeType.TEXT
    )

    private val passkeyUsernameAttribute = TemplateAttribute(
        label = PasskeyEntryFields.FIELD_USERNAME,
        type = TemplateAttributeType.TEXT
    )
    private val passkeyRelyingPartyAttribute = TemplateAttribute(
        label = PasskeyEntryFields.FIELD_RELYING_PARTY,
        type = TemplateAttributeType.TEXT
    )
    private val passkeyCredentialIdAttribute = TemplateAttribute(
        label = PasskeyEntryFields.FIELD_CREDENTIAL_ID,
        type = TemplateAttributeType.TEXT
    )
    private val passkeyPrivateKeyAttribute = TemplateAttribute(
        label = PasskeyEntryFields.FIELD_PRIVATE_KEY,
        type = TemplateAttributeType.TEXT,
        protected = true,
        options = TemplateAttributeOption().apply {
            setNumberLines(6)
        }
    )

    val email: Template
        get() {
            val sections = mutableListOf<TemplateSection>()
            val mainSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                add(emailAddressAttribute)
                add(passwordAttribute)
                add(urlAttribute)
                add(notesAttribute)
            })
            sections.add(mainSection)
            return Template(
                UUID_EMAIL,
                TemplateField.LABEL_EMAIL,
                IconImage(IconImageStandard(IconImageStandard.EMAIL_ID)),
                sections)
        }

    val wifi: Template
        get() {
            val sections = mutableListOf<TemplateSection>()
            val mainSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                add(ssidAttribute)
                add(passwordAttribute)
                add(wirelessTypeAttribute)
                add(notesAttribute)
            })
            sections.add(mainSection)
            return Template(
                UUID_WIFI,
                TemplateField.LABEL_WIRELESS,
                IconImage(IconImageStandard(IconImageStandard.WIRELESS_ID)),
                sections)
        }

    val notes: Template
        get() {
            val sections = mutableListOf<TemplateSection>()
            val mainSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                add(notesAttribute)
            })
            sections.add(mainSection)
            return Template(
                UUID_NOTES,
                TemplateField.LABEL_SECURE_NOTE,
                IconImage(IconImageStandard(IconImageStandard.LIST_ID)),
                sections)
        }

    val idCard: Template
        get() {
            val sections = mutableListOf<TemplateSection>()
            val mainSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                add(numberAttribute)
                add(nameAttribute)
                add(placeOfIssueAttribute)
                add(dateOfIssueAttribute)
                add(expirationDateAttribute)
                add(notesAttribute)
            })
            sections.add(mainSection)
            return Template(
                UUID_ID_CARD,
                TemplateField.LABEL_ID_CARD,
                IconImage(IconImageStandard(IconImageStandard.ID_CARD_ID)),
                sections)
        }

    val creditCard: Template
        get() {
            val sections = mutableListOf<TemplateSection>()
            val mainSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                add(numberAttribute)
                add(holderAttribute)
                add(expirationDateAttribute)
                add(cvvAttribute)
                add(pinAttribute)
                add(notesAttribute)
            })
            sections.add(mainSection)
            return Template(
                UUID_CREDIT_CARD,
                TemplateField.LABEL_DEBIT_CREDIT_CARD,
                IconImage(IconImageStandard(IconImageStandard.CREDIT_CARD_ID)),
                sections)
        }

    val bank: Template
        get() {
            val sections = mutableListOf<TemplateSection>()
            val mainSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                add(nameAttribute)
                add(holderAttribute)
                add(ibanAttribute)
                add(bicAttribute)
                add(usernameAttribute)
                add(passwordAttribute)
                add(urlAttribute)
                add(notesAttribute)
            })
            sections.add(mainSection)
            return Template(
                UUID_BANK,
                TemplateField.LABEL_BANK,
                IconImage(IconImageStandard(IconImageStandard.DOLLAR_ID)),
                sections)
        }

    val cryptocurrency: Template
        get() {
            val sections = mutableListOf<TemplateSection>()
            val mainSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                add(tokenAttribute)
                add(publicKeyAttribute)
                add(privateKeyAttribute)
                add(seedAttribute)
                add(notesAttribute)
            })
            sections.add(mainSection)
            return Template(
                UUID_CRYPTO,
                TemplateField.LABEL_CRYPTOCURRENCY,
                IconImage(IconImageStandard(IconImageStandard.STAR_ID)),
                sections)
        }

    val passkey: Template
        get() {
            val sections = mutableListOf<TemplateSection>()
            val mainSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                add(usernameAttribute)
                add(urlAttribute)
                add(notesAttribute)
            })
            sections.add(mainSection)
            return Template(
                UUID_PASSKEY,
                PasskeyEntryFields.PASSKEY_FIELD,
                IconImage(IconImageStandard(IconImageStandard.KEY_ID)),
                sections)
        }

    val totp: Template
        get() {
            val sections = mutableListOf<TemplateSection>()
            val mainSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                add(usernameAttribute)
                add(otpTokenAttribute)
                add(urlAttribute)
                add(notesAttribute)
            })
            sections.add(mainSection)
            return Template(
                UUID_TOTP,
                TemplateField.LABEL_TOTP,
                IconImage(IconImageStandard(IconImageStandard.KEY_ID)),
                sections)
        }

    companion object {
        val UUID_EMAIL = UUID(0L, 101L)
        val UUID_WIFI = UUID(0L, 102L)
        val UUID_NOTES = UUID(0L, 103L)
        val UUID_ID_CARD = UUID(0L, 104L)
        val UUID_CREDIT_CARD = UUID(0L, 105L)
        val UUID_BANK = UUID(0L, 106L)
        val UUID_CRYPTO = UUID(0L, 107L)
        val UUID_PASSKEY = UUID(0L, 108L)
        val UUID_TOTP = UUID(0L, 109L)
    }
}