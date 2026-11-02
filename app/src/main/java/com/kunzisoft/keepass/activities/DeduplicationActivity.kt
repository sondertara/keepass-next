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
package com.kunzisoft.keepass.activities

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.session.DatabaseSessionActivity
import com.kunzisoft.keepass.activities.session.DatabaseSessionOptions
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.importer.DeduplicationEngine
import com.kunzisoft.keepass.view.AvatarHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeduplicationActivity : DatabaseSessionActivity() {

    override fun createSessionOptions(): DatabaseSessionOptions =
        DatabaseSessionOptions(manageDatabaseInfo = false, finishActivityIfReloadRequested = false)

    override fun viewToInvalidateTimeout(): View? = findViewById(android.R.id.content)
    override fun finishActivityIfReloadRequested(): Boolean = false
    override fun manageDatabaseInfo(): Boolean = false

    private lateinit var toolbar: MaterialToolbar
    private lateinit var chipGroupMode: ChipGroup
    private lateinit var progressDedup: LinearProgressIndicator
    private lateinit var txtSummaryTitle: TextView
    private lateinit var txtSummaryDesc: TextView
    private lateinit var recyclerGroups: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var layoutBottomBar: View
    private lateinit var btnCleanAll: MaterialButton

    private var currentMode = DeduplicationEngine.MatchMode.TITLE_AND_USERNAME
    private val duplicateGroups = mutableListOf<DeduplicationEngine.DuplicateGroup>()
    private lateinit var adapter: DuplicateGroupAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_deduplication)

        toolbar = findViewById(R.id.toolbar_dedup)
        chipGroupMode = findViewById(R.id.chip_group_dedup_mode)
        progressDedup = findViewById(R.id.progress_dedup)
        txtSummaryTitle = findViewById(R.id.txt_dedup_summary_title)
        txtSummaryDesc = findViewById(R.id.txt_dedup_summary_desc)
        recyclerGroups = findViewById(R.id.recycler_duplicate_groups)
        layoutEmpty = findViewById(R.id.layout_dedup_empty)
        layoutBottomBar = findViewById(R.id.layout_dedup_bottom_bar)
        btnCleanAll = findViewById(R.id.btn_clean_all_duplicates)

        toolbar.setNavigationOnClickListener {
            finish()
        }

        adapter = DuplicateGroupAdapter(
            groups = duplicateGroups,
            onMergeSingleGroup = { group, position ->
                mergeSingleGroup(group, position)
            }
        )
        recyclerGroups.layoutManager = LinearLayoutManager(this)
        recyclerGroups.adapter = adapter

        chipGroupMode.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chip_dedup_account
            val newMode = when (checkedId) {
                R.id.chip_dedup_exact -> DeduplicationEngine.MatchMode.EXACT_CONTENT
                R.id.chip_dedup_password -> DeduplicationEngine.MatchMode.SAME_PASSWORD
                else -> DeduplicationEngine.MatchMode.TITLE_AND_USERNAME
            }
            if (currentMode != newMode) {
                currentMode = newMode
                scanDuplicates()
            }
        }

        btnCleanAll.setOnClickListener {
            confirmAndCleanAll()
        }
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        super.onDatabaseRetrieved(database)
        scanDuplicates()
    }

    private fun scanDuplicates() {
        val database = mDatabase ?: return
        progressDedup.isVisible = true

        lifecycleScope.launch(Dispatchers.IO) {
            val groups = DeduplicationEngine.scanDatabaseDuplicates(database, currentMode)
            withContext(Dispatchers.Main) {
                progressDedup.isVisible = false
                duplicateGroups.clear()
                duplicateGroups.addAll(groups)
                adapter.notifyDataSetChanged()
                updateSummaryUI()
            }
        }
    }

    private fun updateSummaryUI() {
        val groupCount = duplicateGroups.size
        val redundantCount = duplicateGroups.sumOf { (it.items.size - 1).coerceAtLeast(0) }

        if (groupCount == 0) {
            txtSummaryTitle.text = getString(R.string.dedup_summary_clean)
            recyclerGroups.isVisible = false
            layoutEmpty.isVisible = true
            layoutBottomBar.isVisible = false
        } else {
            txtSummaryTitle.text = getString(R.string.dedup_summary_found, groupCount, redundantCount)
            recyclerGroups.isVisible = true
            layoutEmpty.isVisible = false
            layoutBottomBar.isVisible = true
            btnCleanAll.text = getString(R.string.dedup_btn_clean_all_count, redundantCount)
        }
    }

    private fun mergeSingleGroup(group: DeduplicationEngine.DuplicateGroup, position: Int) {
        val database = mDatabase ?: return
        val recycleBinTitle = getString(R.string.recycle_bin)
        val mergeFields = currentMode != DeduplicationEngine.MatchMode.SAME_PASSWORD

        lifecycleScope.launch(Dispatchers.IO) {
            val removed = DeduplicationEngine.resolveDuplicateGroup(
                database = database,
                group = group,
                recycleBinTitle = recycleBinTitle,
                mergeMissingFields = mergeFields
            )
            if (removed > 0) {
                saveDatabase()
            }
            withContext(Dispatchers.Main) {
                if (position in duplicateGroups.indices) {
                    duplicateGroups.removeAt(position)
                    adapter.notifyItemRemoved(position)
                    adapter.notifyItemRangeChanged(position, duplicateGroups.size - position)
                } else {
                    scanDuplicates()
                }
                updateSummaryUI()
                Toast.makeText(
                    this@DeduplicationActivity,
                    getString(R.string.dedup_toast_cleaned_count, removed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun confirmAndCleanAll() {
        val redundantCount = duplicateGroups.sumOf { (it.items.size - 1).coerceAtLeast(0) }
        if (redundantCount <= 0) return

        AlertDialog.Builder(this)
            .setTitle(R.string.dedup_confirm_title)
            .setMessage(getString(R.string.dedup_confirm_message, duplicateGroups.size, redundantCount))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                executeCleanAll()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun executeCleanAll() {
        val database = mDatabase ?: return
        val recycleBinTitle = getString(R.string.recycle_bin)
        val mergeFields = currentMode != DeduplicationEngine.MatchMode.SAME_PASSWORD
        val groupsSnapshot = duplicateGroups.toList()

        progressDedup.isVisible = true
        btnCleanAll.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val totalRemoved = DeduplicationEngine.resolveAllDuplicateGroups(
                database = database,
                groups = groupsSnapshot,
                recycleBinTitle = recycleBinTitle,
                mergeMissingFields = mergeFields
            )
            if (totalRemoved > 0) {
                saveDatabase()
            }
            withContext(Dispatchers.Main) {
                progressDedup.isVisible = false
                btnCleanAll.isEnabled = true
                duplicateGroups.clear()
                adapter.notifyDataSetChanged()
                updateSummaryUI()
                Toast.makeText(
                    this@DeduplicationActivity,
                    getString(R.string.dedup_toast_cleaned_count, totalRemoved),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private inner class DuplicateGroupAdapter(
        private val groups: List<DeduplicationEngine.DuplicateGroup>,
        private val onMergeSingleGroup: (DeduplicationEngine.DuplicateGroup, Int) -> Unit
    ) : RecyclerView.Adapter<DuplicateGroupAdapter.ViewHolder>() {

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imgAvatar: ImageView = itemView.findViewById(R.id.img_dup_avatar)
            val txtTitle: TextView = itemView.findViewById(R.id.txt_dup_title)
            val txtSubtitle: TextView = itemView.findViewById(R.id.txt_dup_subtitle)
            val btnMerge: MaterialButton = itemView.findViewById(R.id.btn_merge_single_group)
            val containerEntries: LinearLayout = itemView.findViewById(R.id.container_dup_entries)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_duplicate_group, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = groups.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = groups[position]
            val ctx = holder.itemView.context

            holder.imgAvatar.setImageDrawable(AvatarHelper.getAvatarDrawable(ctx, group.displayTitle))
            holder.txtTitle.text = group.displayTitle
            holder.txtSubtitle.text = group.displaySubtitle

            holder.btnMerge.setOnClickListener {
                val adapterPos = duplicateGroups.indexOf(group)
                if (adapterPos != -1) {
                    onMergeSingleGroup(group, adapterPos)
                }
            }

            renderEntryRows(holder.containerEntries, group, holder.txtTitle.currentTextColor)
        }

        private fun renderEntryRows(
            container: LinearLayout,
            group: DeduplicationEngine.DuplicateGroup,
            defaultTextColor: Int
        ) {
            container.removeAllViews()
            val ctx = container.context
            val density = ctx.resources.displayMetrics.density

            group.items.forEachIndexed { index, item ->
                val isSelected = item.entryId == group.selectedKeepEntryId
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        (4 * density).toInt(),
                        (6 * density).toInt(),
                        (4 * density).toInt(),
                        (6 * density).toInt()
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (group.selectedKeepEntryId != item.entryId) {
                            group.selectedKeepEntryId = item.entryId
                            renderEntryRows(container, group, defaultTextColor)
                        }
                    }
                }

                val radioButton = RadioButton(ctx).apply {
                    isChecked = isSelected
                    isClickable = false
                    isFocusable = false
                }
                row.addView(radioButton)

                val infoCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (6 * density).toInt()
                    }
                }

                val dateStr = dateFormat.format(Date(item.lastModifiedTimeMillis))
                val userLabel = if (item.username.isNotBlank()) "${item.username} · " else ""
                val line1 = TextView(ctx).apply {
                    text = "${item.title} (${userLabel}📁 ${item.groupPath})"
                    textSize = 13f
                    setTextColor(if (isSelected) Color.parseColor("#4D6BFE") else defaultTextColor)
                    maxLines = 1
                }

                val tagsList = mutableListOf<String>()
                if (index == 0) tagsList.add(ctx.getString(R.string.dedup_tag_recommended))
                if (item.hasOtp) tagsList.add("2FA")
                if (item.hasNotes) tagsList.add(ctx.getString(R.string.entry_badge_note))
                val tagsSuffix = if (tagsList.isNotEmpty()) "  ·  " + tagsList.joinToString(" / ") else ""

                val line2 = TextView(ctx).apply {
                    text = "${ctx.getString(R.string.dedup_modified_prefix)}: $dateStr$tagsSuffix"
                    textSize = 11f
                    setTextColor(Color.parseColor("#888888"))
                    maxLines = 1
                }

                infoCol.addView(line1)
                infoCol.addView(line2)
                row.addView(infoCol)

                val statusBadge = TextView(ctx).apply {
                    textSize = 11f
                    setPadding(
                        (8 * density).toInt(),
                        (3 * density).toInt(),
                        (8 * density).toInt(),
                        (3 * density).toInt()
                    )
                    if (isSelected) {
                        AvatarHelper.bindCategoryBadge(
                            this,
                            ctx.getString(R.string.dedup_badge_keep),
                            Color.parseColor("#10B981"),
                            Color.parseColor("#1F10B981")
                        )
                    } else {
                        AvatarHelper.bindCategoryBadge(
                            this,
                            ctx.getString(R.string.dedup_badge_recycle),
                            Color.parseColor("#EF4444"),
                            Color.parseColor("#1FEF4444")
                        )
                    }
                }
                row.addView(statusBadge)

                container.addView(row)
            }
        }
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, DeduplicationActivity::class.java))
        }
    }
}
