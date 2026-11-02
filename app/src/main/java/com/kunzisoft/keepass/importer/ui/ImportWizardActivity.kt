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
package com.kunzisoft.keepass.importer.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.session.DatabaseSessionActivity
import com.kunzisoft.keepass.activities.session.DatabaseSessionOptions
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.DatabaseTaskProvider.Companion.startDatabaseService
import com.kunzisoft.keepass.database.element.GroupId
import com.kunzisoft.keepass.importer.DatabaseBatchImporter
import com.kunzisoft.keepass.importer.DeduplicationEngine
import com.kunzisoft.keepass.importer.ImporterFactory
import com.kunzisoft.keepass.importer.model.ImportBatchResult
import com.kunzisoft.keepass.importer.parsers.VaultImporter
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService
import com.kunzisoft.keepass.utils.getParcelableExtraCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream

class ImportWizardActivity : DatabaseSessionActivity() {

    override fun createSessionOptions(): DatabaseSessionOptions =
        DatabaseSessionOptions(manageDatabaseInfo = false, finishActivityIfReloadRequested = false)

    override fun viewToInvalidateTimeout(): View? = findViewById(android.R.id.content)
    override fun finishActivityIfReloadRequested(): Boolean = false
    override fun manageDatabaseInfo(): Boolean = false

    private var currentParentGroupId: GroupId? = null
    private var selectedFileUri: Uri? = null
    private var selectedFileName: String? = null
    private var parsedBatchResult: ImportBatchResult? = null

    // Views
    private lateinit var toolbar: MaterialToolbar
    private lateinit var spinnerFormat: Spinner
    private lateinit var btnSelectFile: MaterialButton
    private lateinit var txtSelectedFileName: TextView
    private lateinit var cardPreview: MaterialCardView
    private lateinit var txtPreviewFormatName: TextView
    private lateinit var txtPreviewTotalEntries: TextView
    private lateinit var txtPreviewTotpEntries: TextView
    private lateinit var txtPreviewGroupsCount: TextView
    private lateinit var txtPreviewDuplicates: TextView
    private lateinit var txtPreviewWarning: TextView
    private lateinit var radioGroupDestination: RadioGroup
    private lateinit var radioDestNewGroup: RadioButton
    private lateinit var radioGroupDedup: RadioGroup
    private lateinit var progressImport: LinearProgressIndicator
    private lateinit var txtImportStatus: TextView
    private lateinit var btnStartImport: MaterialButton

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleSelectedFile(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_wizard)

        currentParentGroupId = intent.getParcelableExtraCompat(EXTRA_PARENT_GROUP_ID)

        initViews()
        setupFormatSpinner()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val incomingUri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                    ?: intent.data
            }
            Intent.ACTION_VIEW -> {
                intent.data
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            else -> {
                intent.data ?: intent.getParcelableExtraCompat<Uri>(EXTRA_FILE_URI)
            }
        }

        if (incomingUri != null) {
            handleSelectedFile(incomingUri)
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        spinnerFormat = findViewById(R.id.spinner_import_format)
        btnSelectFile = findViewById(R.id.btn_select_file)
        txtSelectedFileName = findViewById(R.id.txt_selected_file_name)
        cardPreview = findViewById(R.id.card_import_preview)
        txtPreviewFormatName = findViewById(R.id.txt_preview_format_name)
        txtPreviewTotalEntries = findViewById(R.id.txt_preview_total_entries)
        txtPreviewTotpEntries = findViewById(R.id.txt_preview_totp_entries)
        txtPreviewGroupsCount = findViewById(R.id.txt_preview_groups_count)
        txtPreviewDuplicates = findViewById(R.id.txt_preview_duplicates)
        txtPreviewWarning = findViewById(R.id.txt_preview_warning)
        radioGroupDestination = findViewById(R.id.radio_group_destination)
        radioDestNewGroup = findViewById(R.id.radio_dest_new_group)
        radioGroupDedup = findViewById(R.id.radio_group_dedup)
        progressImport = findViewById(R.id.progress_import)
        txtImportStatus = findViewById(R.id.txt_import_status)
        btnStartImport = findViewById(R.id.btn_start_import)

        btnSelectFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        btnStartImport.setOnClickListener {
            executeImport()
        }
    }

    private fun setupFormatSpinner() {
        val formatList = mutableListOf(getString(R.string.import_source_auto))
        formatList.addAll(ImporterFactory.allImporters.map { it.displayName })

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, formatList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerFormat.adapter = adapter

        spinnerFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // If a file is already picked, re-parse with the newly selected parser
                selectedFileUri?.let { handleSelectedFile(it) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun handleSelectedFile(uri: Uri) {
        selectedFileUri = uri
        selectedFileName = queryFileName(uri) ?: uri.lastPathSegment ?: "unknown"
        txtSelectedFileName.text = selectedFileName

        parseFileInBackground(uri, selectedFileName ?: "")
    }

    private fun parseFileInBackground(uri: Uri, fileName: String) {
        showLoading(true, getString(R.string.import_parsing_in_progress))
        btnStartImport.isEnabled = false
        cardPreview.isVisible = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Cannot open file stream.")

                // Determine importer
                val selectedPos = withContext(Dispatchers.Main) { spinnerFormat.selectedItemPosition }
                val importer: VaultImporter = if (selectedPos == 0) {
                    // Auto-detect
                    val previewLength = minOf(fileBytes.size, 2048)
                    val preview = String(fileBytes, 0, previewLength)
                    ImporterFactory.detectImporter(fileName, preview)
                } else {
                    ImporterFactory.allImporters[selectedPos - 1]
                }

                val parseResult = importer.parse(ByteArrayInputStream(fileBytes))
                val precheck = DeduplicationEngine.precheckImportBatch(mDatabase, parseResult)

                withContext(Dispatchers.Main) {
                    showLoading(false)
                    displayPreviewResult(parseResult, precheck)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing file for import", e)
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    Toast.makeText(
                        this@ImportWizardActivity,
                        getString(R.string.import_error, e.localizedMessage ?: "Unknown error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun displayPreviewResult(
        result: ImportBatchResult,
        precheck: DeduplicationEngine.PrecheckResult
    ) {
        parsedBatchResult = result

        txtPreviewFormatName.text = getString(R.string.import_detected_format, result.sourceName)
        txtPreviewTotalEntries.text = getString(R.string.import_total_entries, result.totalEntriesCount)
        txtPreviewTotpEntries.text = getString(R.string.import_totp_entries, result.totpEntriesCount)
        txtPreviewGroupsCount.text = getString(R.string.import_folders_count, result.groups.size)

        if (precheck.duplicateCount > 0) {
            txtPreviewDuplicates.text = getString(R.string.import_duplicates_detected, precheck.duplicateCount)
            txtPreviewDuplicates.isVisible = true
        } else {
            txtPreviewDuplicates.isVisible = false
        }

        if (result.warnings.isNotEmpty()) {
            txtPreviewWarning.text = result.warnings.joinToString("\n")
            txtPreviewWarning.isVisible = true
        } else {
            txtPreviewWarning.isVisible = false
        }

        cardPreview.isVisible = true
        btnStartImport.isEnabled = result.totalEntriesCount > 0
    }

    private fun executeImport() {
        val database = mDatabase ?: run {
            Toast.makeText(this, getString(R.string.import_error_database_locked), Toast.LENGTH_LONG).show()
            startActivity(Intent(this, com.kunzisoft.keepass.activities.FileDatabaseSelectActivity::class.java))
            return
        }

        val result = parsedBatchResult ?: return

        showLoading(true, getString(R.string.importing_in_progress))
        btnStartImport.isEnabled = false
        btnSelectFile.isEnabled = false

        val createDedicated = radioDestNewGroup.isChecked
        val conflictStrategy = when (radioGroupDedup.checkedRadioButtonId) {
            R.id.radio_dedup_update -> DeduplicationEngine.ImportConflictStrategy.UPDATE_EXISTING
            R.id.radio_dedup_keep_all -> DeduplicationEngine.ImportConflictStrategy.KEEP_ALL
            else -> DeduplicationEngine.ImportConflictStrategy.SKIP_DUPLICATES
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val summary = DatabaseBatchImporter.importBatch(
                    database = database,
                    targetParentGroupId = currentParentGroupId,
                    batchResult = result,
                    createDedicatedGroup = createDedicated,
                    conflictStrategy = conflictStrategy
                )

                withContext(Dispatchers.Main) {
                    // Save changes to database reliably before finishing
                    if (summary.totalEntries > 0 || summary.updatedEntries > 0 || summary.totalGroups > 0) {
                        startDatabaseService(
                            Bundle().apply {
                                putBoolean(DatabaseTaskNotificationService.SAVE_DATABASE_KEY, true)
                            },
                            DatabaseTaskNotificationService.ACTION_DATABASE_SAVE
                        )
                    }
                    showLoading(false)
                    val msg = if (summary.skippedDuplicates > 0 || summary.updatedEntries > 0) {
                        getString(
                            R.string.import_success_with_dedup,
                            summary.totalEntries,
                            summary.skippedDuplicates,
                            summary.updatedEntries
                        )
                    } else {
                        getString(R.string.import_success, summary.totalEntries, summary.totalGroups)
                    }
                    Toast.makeText(this@ImportWizardActivity, msg, Toast.LENGTH_LONG).show()

                    setResult(Activity.RESULT_OK)
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Batch import execution failed", e)
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    btnStartImport.isEnabled = true
                    btnSelectFile.isEnabled = true
                    Toast.makeText(
                        this@ImportWizardActivity,
                        getString(R.string.import_error, e.localizedMessage ?: "Error during batch import"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showLoading(loading: Boolean, statusText: String? = null) {
        progressImport.isVisible = loading
        txtImportStatus.isVisible = loading
        txtImportStatus.text = statusText ?: ""
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        super.onDatabaseRetrieved(database)
        parsedBatchResult?.let { result ->
            lifecycleScope.launch(Dispatchers.IO) {
                val precheck = DeduplicationEngine.precheckImportBatch(database, result)
                withContext(Dispatchers.Main) {
                    displayPreviewResult(result, precheck)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ImportWizardActivity"
        const val EXTRA_PARENT_GROUP_ID = "EXTRA_PARENT_GROUP_ID"
        const val EXTRA_FILE_URI = "EXTRA_FILE_URI"

        fun launch(context: Context, parentGroupId: GroupId? = null, fileUri: Uri? = null) {
            val intent = Intent(context, ImportWizardActivity::class.java).apply {
                if (parentGroupId != null) {
                    putExtra(EXTRA_PARENT_GROUP_ID, parentGroupId)
                }
                if (fileUri != null) {
                    putExtra(EXTRA_FILE_URI, fileUri)
                }
            }
            context.startActivity(intent)
        }
    }
}
