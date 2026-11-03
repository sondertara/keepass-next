package com.kunzisoft.keepass.database.element.template
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
import android.graphics.Color
import android.util.Log
import com.kunzisoft.keepass.database.element.Field
import com.kunzisoft.keepass.database.element.database.DatabaseKDBX
import com.kunzisoft.keepass.database.element.entry.EntryKDBX
import com.kunzisoft.keepass.database.element.group.GroupKDBX
import com.kunzisoft.keepass.database.element.icon.IconImage
import com.kunzisoft.keepass.database.element.icon.IconImageStandard
import com.kunzisoft.keepass.database.element.node.NodeIdUUID
import com.kunzisoft.keepass.database.element.security.ProtectedString
import com.kunzisoft.keepass.utils.clear
import com.kunzisoft.keepass.utils.contains
import java.util.UUID

abstract class TemplateEngine(private val mDatabase: DatabaseKDBX) {

    private val mCacheTemplates = HashMap<UUID, Template>()

    fun getTemplates(): List<Template> {
        val templates = mutableListOf<Template>()
        templates.add(Template.STANDARD)
        val builtInDefaults = getDefaults()
        templates.addAll(builtInDefaults)
        try {
            val templateGroup = mDatabase.getTemplatesGroup()
            mCacheTemplates.clear()
            builtInDefaults.forEach { defaultTpl ->
                mCacheTemplates[defaultTpl.uuid] = defaultTpl
            }
            if (templateGroup != null) {
                templateGroup.getChildEntries().forEach { templateEntry ->
                    val builtIn = findBuiltInTemplate(templateEntry.id, templateEntry.title)
                    if (builtIn != null) {
                        mCacheTemplates[templateEntry.id] = builtIn
                    } else if (!templateEntry.title.equals(TemplateField.LABEL_STANDARD, ignoreCase = true)) {
                        getTemplateFromTemplateEntry(templateEntry).let { customTemplate ->
                            mCacheTemplates[templateEntry.id] = customTemplate
                            if (templates.none { it.uuid == customTemplate.uuid || it.title.equals(customTemplate.title, ignoreCase = true) }) {
                                templates.add(customTemplate)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to get templates from group", e)
        }
        return templates
    }

    fun getTemplateCreation(): Template {
        return Template(CREATION)
    }

    fun createNewTemplatesGroup(templatesGroupName: String): GroupKDBX {
        val newTemplatesGroup = mDatabase.createGroup().apply {
            title = templatesGroupName
            icon.standard = mDatabase.getStandardIcon(IconImageStandard.BUILD_ID)
            enableAutoType = false
            enableSearching = false
            isExpanded = false
        }
        mDatabase.addGroupTo(newTemplatesGroup, mDatabase.rootGroup)
        // Build default templates
        getDefaults().forEach { defaultTemplate ->
            createTemplateEntry(defaultTemplate).also {
                mDatabase.addEntryTo(it, newTemplatesGroup)
            }
        }
        return newTemplatesGroup
    }

    fun clearCache() {
        mCacheTemplates.clear()
    }

    protected fun getTemplateByCache(uuid: UUID): Template? {
        try {
            if (mCacheTemplates.containsKey(uuid))
                return mCacheTemplates[uuid]
            findBuiltInTemplate(uuid, null)?.let { builtIn ->
                mCacheTemplates[uuid] = builtIn
                return builtIn
            }
            mDatabase.getEntryById(uuid)?.let { templateEntry ->
                val resolved = findBuiltInTemplate(templateEntry.id, templateEntry.title)
                    ?: getTemplateFromTemplateEntry(templateEntry)
                mCacheTemplates[uuid] = resolved
                return resolved
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to get Entry by $uuid", e)
        }
        return null
    }

    abstract fun getVersion(): Int

    abstract fun getTemplate(entryKDBX: EntryKDBX): Template?

    abstract fun removeMetaTemplateRecognitionFromEntry(entry: EntryKDBX): EntryKDBX

    abstract fun addMetaTemplateRecognitionToEntry(template: Template, entry: EntryKDBX): EntryKDBX

    abstract fun buildTemplateEntryField(attribute: TemplateAttribute): Field

    abstract fun decodeTemplateEntry(templateEntry: EntryKDBX): EntryKDBX

    abstract fun encodeTemplateEntry(templateEntry: EntryKDBX): EntryKDBX

    fun createTemplateEntry(template: Template): EntryKDBX {
        val newEntry = EntryKDBX().apply {
            nodeId = NodeIdUUID(template.uuid)
            title = template.title
            icon = template.icon
            template.sections.forEachIndexed { index, section ->
                if (index > 0) {
                    // Label is not important with section => [Section_X]: Divider
                    val sectionName = section.name.ifEmpty {
                        "$SECTION_DECODED_TEMPLATE_PREFIX${index-1}"
                    }
                    putField(Field(addTemplateDecorator(sectionName),
                        ProtectedString(false, TemplateAttributeType.DIVIDER.typeString))
                    )
                }
                section.attributes.forEach { attribute ->
                    putField(buildTemplateEntryField(attribute))
                }
            }
        }
        return encodeTemplateEntry(newEntry)
    }

    private fun buildTemplateSectionFromFields(fields: List<Field>): TemplateSection {
        val sectionAttributes = mutableListOf<TemplateAttribute>()
        fields.forEach { field ->
            val protectedValue = field.protectedValue.charArrayValue.copyOf()
            sectionAttributes.add(TemplateAttribute(
                removeTemplateDecorator(field.name),
                TemplateAttributeType.getFrom(protectedValue),
                field.protectedValue.isProtected,
                TemplateAttributeOption.getOptionsFrom(protectedValue))
            )
            protectedValue.clear()
        }
        return TemplateSection(sectionAttributes)
    }

    private fun getTemplateFromTemplateEntry(templateEntry: EntryKDBX): Template {

        val templateEntryDecoded = decodeTemplateEntry(templateEntry)
        val templateSections = mutableListOf<TemplateSection>()
        val sectionFields = mutableListOf<Field>()
        templateEntryDecoded.doForEachDecodedCustomField { field ->
            if (field.protectedValue.charArrayValue.contains(TemplateAttributeType.DIVIDER.typeString)) {
                templateSections.add(buildTemplateSectionFromFields(sectionFields))
                sectionFields.clear()
            } else {
                sectionFields.add(field)
            }
        }
        templateSections.add(buildTemplateSectionFromFields(sectionFields))

        var backgroundColor: Int? = null
        templateEntry.backgroundColor.let {
            try {
                backgroundColor = Color.parseColor(it)
            } catch (_: Exception) {}
        }
        var foregroundColor: Int? = null
        templateEntry.foregroundColor.let {
            try {
                foregroundColor = Color.parseColor(it)
            } catch (_: Exception) {}
        }

        return Template(
            templateEntry.id,
            templateEntry.title,
            templateEntry.icon,
            backgroundColor,
            foregroundColor,
            templateEntry.tags,
            templateSections,
            getVersion()
        )
    }

    companion object {

        private val TAG = TemplateEngine::class.java.name

        private const val PREFIX_DECODED_TEMPLATE = "["
        private const val SUFFIX_DECODED_TEMPLATE = "]"
        private const val SECTION_DECODED_TEMPLATE_PREFIX = "Section "

        val CREATION: Template
            get() {
                val sections = mutableListOf<TemplateSection>()
                val mainSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                    // Dynamic part
                })
                sections.add(mainSection)
                return Template(UUID(0, 1),
                    TemplateField.LABEL_TEMPLATE,
                    IconImage(IconImageStandard(IconImageStandard.BUILD_ID)),
                    sections)
            }
        
        fun getDefaults(): List<Template> {
            val templateBuilder = TemplateBuilder()
            return listOf(
                templateBuilder.email,
                templateBuilder.wifi,
                templateBuilder.notes,
                templateBuilder.idCard,
                templateBuilder.creditCard,
                templateBuilder.bank,
                templateBuilder.cryptocurrency,
                templateBuilder.passkey,
                templateBuilder.totp)
        }

        fun findBuiltInTemplate(uuid: UUID?, title: String?): Template? {
            val builder = TemplateBuilder()
            if (uuid != null) {
                when (uuid) {
                    TemplateBuilder.UUID_EMAIL -> return builder.email
                    TemplateBuilder.UUID_WIFI -> return builder.wifi
                    TemplateBuilder.UUID_NOTES -> return builder.notes
                    TemplateBuilder.UUID_ID_CARD -> return builder.idCard
                    TemplateBuilder.UUID_CREDIT_CARD -> return builder.creditCard
                    TemplateBuilder.UUID_BANK -> return builder.bank
                    TemplateBuilder.UUID_CRYPTO -> return builder.cryptocurrency
                    TemplateBuilder.UUID_PASSKEY -> return builder.passkey
                    TemplateBuilder.UUID_TOTP -> return builder.totp
                }
            }
            val normalized = title?.trim() ?: return null
            return when {
                normalized.equals(TemplateField.LABEL_EMAIL, ignoreCase = true) ||
                        normalized.equals(TemplateField.LABEL_EMAIL_ADDRESS, ignoreCase = true) -> builder.email
                normalized.equals(TemplateField.LABEL_WIRELESS, ignoreCase = true) ||
                        normalized.equals("Wireless", ignoreCase = true) ||
                        normalized.equals("WLAN", ignoreCase = true) -> builder.wifi
                normalized.equals(TemplateField.LABEL_SECURE_NOTE, ignoreCase = true) ||
                        normalized.equals(TemplateField.LABEL_NOTES, ignoreCase = true) -> builder.notes
                normalized.equals(TemplateField.LABEL_ID_CARD, ignoreCase = true) -> builder.idCard
                normalized.equals(TemplateField.LABEL_DEBIT_CREDIT_CARD, ignoreCase = true) ||
                        normalized.equals("Credit Card", ignoreCase = true) -> builder.creditCard
                normalized.equals(TemplateField.LABEL_BANK, ignoreCase = true) -> builder.bank
                normalized.equals(TemplateField.LABEL_CRYPTOCURRENCY, ignoreCase = true) ||
                        normalized.equals("Cryptocurrency", ignoreCase = true) -> builder.cryptocurrency
                normalized.equals("Passkey", ignoreCase = true) -> builder.passkey
                normalized.equals(TemplateField.LABEL_TOTP, ignoreCase = true) ||
                        normalized.equals("TOTP", ignoreCase = true) ||
                        normalized.equals("2FA", ignoreCase = true) -> builder.totp
                else -> null
            }
        }

        fun containsTemplateDecorator(name: String): Boolean {
            return name.startsWith(PREFIX_DECODED_TEMPLATE)
                    && name.endsWith(SUFFIX_DECODED_TEMPLATE)
        }

        fun addTemplateDecorator(name: String): String {
            return "$PREFIX_DECODED_TEMPLATE${name}$SUFFIX_DECODED_TEMPLATE"
        }

        fun removeTemplateDecorator(name: String): String {
            return name
                .removePrefix(PREFIX_DECODED_TEMPLATE)
                .removeSuffix(SUFFIX_DECODED_TEMPLATE)
        }
    }
}
