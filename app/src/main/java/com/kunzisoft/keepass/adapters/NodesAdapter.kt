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
package com.kunzisoft.keepass.adapters

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SortedList
import androidx.recyclerview.widget.SortedListAdapterCallback
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.database.element.SortNodeEnum
import com.kunzisoft.keepass.database.element.Tag
import com.kunzisoft.keepass.database.element.node.NodeId
import com.kunzisoft.keepass.database.element.node.NodeType
import com.kunzisoft.keepass.database.element.template.TemplateField
import com.kunzisoft.keepass.database.helper.getLocalizedName
import com.kunzisoft.keepass.icons.IconDrawableFactory
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.GroupInfo
import com.kunzisoft.keepass.model.Passkey
import com.kunzisoft.keepass.model.PasskeyEntryFields
import com.kunzisoft.keepass.model.SortedEntryInfo
import com.kunzisoft.keepass.model.SortedGroupInfo
import com.kunzisoft.keepass.model.SortedNodeInfo
import com.kunzisoft.keepass.model.VaultNodeFilter
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.view.AvatarHelper
import com.kunzisoft.keepass.view.OtpDisplayView
import com.kunzisoft.keepass.view.setTextSize
import com.kunzisoft.keepass.view.strikeOut
import com.kunzisoft.keepass.view.underline
import androidx.core.widget.ImageViewCompat

/**
 * Create node list adapter.
 * @param context Context to use
 */
class NodesAdapter(
    private val context: Context,
    private val iconDrawableFactory: IconDrawableFactory,
    private var sortDatabaseParameters: SortNodeEnum.SortDatabaseParameters
) : RecyclerView.Adapter<NodesAdapter.NodeViewHolder>() {

    private var sortNode: SortNodeEnum = SortNodeEnum.DB
    private var sortNodeParameters: SortNodeEnum.SortNodeParameters = SortNodeEnum.SortNodeParameters()
    private var mNodeComparator: Comparator<SortedNodeInfo> = buildNodeComparator()
    private val mNodeSortedListCallback: NodeSortedListCallback
    private val mNodeSortedList: SortedList<SortedNodeInfo>
    private val mInflater: LayoutInflater = LayoutInflater.from(context)
    private var mCalculateViewTypeTextSize = Array(2) { true } // number of view type
    private var mTextSizeUnit: Int = TypedValue.COMPLEX_UNIT_PX
    private var mPrefSizeMultiplier: Float = 0F
    private var mTextDefaultDimension: Float = 0F
    private var mSubTextDefaultDimension: Float = 0F
    private var mMetaTextDefaultDimension: Float = 0F
    private var mNumberChildrenTextDefaultDimension: Float = 0F
    private var mIconDefaultDimension: Float = 0F

    private var mShowEntryColors: Boolean = true
    private var mShowUserNames: Boolean = true
    private var mShowNumberEntries: Boolean = true
    private var mShowTags: Boolean = false
    private var mShowUUID: Boolean = false
    private var mRecursiveNumberEntries: Boolean = false
    private var mOldVirtualGroup = false
    private var mVirtualGroup = false

    private var mActionNodesList = mutableListOf<SortedNodeInfo>()
    private var mActionNodeIds = mutableSetOf<NodeId<*>>()
    private var mNodeClickCallback: NodeClickCallback? = null

    @ColorInt
    private val mColorSurfaceContainer: Int
    @ColorInt
    private val mTextColorPrimary: Int
    @ColorInt
    private val mTextColor: Int
    @ColorInt
    private val mTextColorSecondary: Int
    @ColorInt
    private val mColorSecondary: Int
    @ColorInt
    private val mColorOnSecondary: Int

    /**
     * Determine if the adapter contains or not any element
     * @return true if the list is empty
     */
    val isEmpty: Boolean
        get() = mNodeSortedList.size() <= 0

    init {
        this.mIconDefaultDimension = context.resources.getDimension(R.dimen.list_icon_size_default)

        assignPreferences()

        this.mNodeSortedListCallback = NodeSortedListCallback()
        this.mNodeSortedList = SortedList(SortedNodeInfo::class.java, mNodeSortedListCallback)

        context.obtainStyledAttributes(intArrayOf(R.attr.colorSurfaceContainer)).also { taColorSurfaceContainer ->
            this.mColorSurfaceContainer = taColorSurfaceContainer.getColor(0, Color.BLACK)
        }.recycle()
        // Retrieve the color to tint the icon
        context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).also { taTextColorPrimary ->
            this.mTextColorPrimary = taTextColorPrimary.getColor(0, Color.BLACK)
        }.recycle()
        // To get text color
        context.obtainStyledAttributes(intArrayOf(android.R.attr.textColor)).also { taTextColor ->
            this.mTextColor = taTextColor.getColor(0, Color.BLACK)
        }.recycle()
        // To get text color secondary
        context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).also { taTextColorSecondary ->
            this.mTextColorSecondary = taTextColorSecondary.getColor(0, Color.BLACK)
        }.recycle()
        // To get background color for selection
        context.obtainStyledAttributes(intArrayOf(R.attr.colorSecondary)).also { taColorSecondary ->
            this.mColorSecondary = taColorSecondary.getColor(0, Color.GRAY)
        }.recycle()
        // To get text color for selection
        context.obtainStyledAttributes(intArrayOf(R.attr.colorOnSecondary)).also { taColorOnSecondary ->
            this.mColorOnSecondary = taColorOnSecondary.getColor(0, Color.WHITE)
        }.recycle()
    }

    private fun assignPreferences() {
        this.mPrefSizeMultiplier = PreferencesUtil.getListTextSize(context)

        notifyChangeSort(
            sortNodeEnum = PreferencesUtil.getListSort(context),
            sortDatabaseParameters = sortDatabaseParameters,
            sortNodeParameters = SortNodeEnum.SortNodeParameters(
                ascending = PreferencesUtil.getAscendingSort(context),
                groupsBefore = PreferencesUtil.getGroupsBeforeSort(context),
                recycleBinBottom = PreferencesUtil.getRecycleBinBottomSort(context)
            )
        )

        this.mShowEntryColors = PreferencesUtil.showEntryColors(context)
        this.mShowUserNames = PreferencesUtil.showUsernamesListEntries(context)
        this.mShowNumberEntries = PreferencesUtil.showNumberEntries(context)
        this.mShowTags = PreferencesUtil.showTags(context)
        this.mShowUUID = PreferencesUtil.showUUID(context)
        this.mRecursiveNumberEntries = PreferencesUtil.recursiveNumberEntries(context)

        // Reinit textSize for all view type
        mCalculateViewTypeTextSize.forEachIndexed { index, _ -> mCalculateViewTypeTextSize[index] = true }
    }

    /**
     * Rebuild the list by clear and build children from the list of [nodes]
     * @param isSearch If the list is a search list
     */
    fun rebuildList(
        nodes: List<SortedNodeInfo>,
        isSearch: Boolean = false
    ) {
        mOldVirtualGroup = mVirtualGroup
        mVirtualGroup = isSearch
        assignPreferences()
        mNodeSortedList.replaceAll(nodes)
    }

    private inner class NodeSortedListCallback: SortedListAdapterCallback<SortedNodeInfo>(this) {
        override fun compare(item1: SortedNodeInfo, item2: SortedNodeInfo): Int {
            return mNodeComparator.compare(item1, item2)
        }

        override fun areContentsTheSame(oldItem: SortedNodeInfo, newItem: SortedNodeInfo): Boolean {
            if (mOldVirtualGroup != mVirtualGroup)
                return false
            var typeContentTheSame = true
            if (oldItem is SortedEntryInfo && newItem is SortedEntryInfo) {
                typeContentTheSame = oldItem.getVisualTitle() == newItem.getVisualTitle()
                        && oldItem.username == newItem.username
                        && oldItem.backgroundColor == newItem.backgroundColor
                        && oldItem.foregroundColor == newItem.foregroundColor
                        && oldItem.otpModel == newItem.otpModel
                        && oldItem.containsAttachment() == newItem.containsAttachment()
            } else if (oldItem is SortedGroupInfo && newItem is SortedGroupInfo) {
                typeContentTheSame = oldItem.numberOfChildEntries == newItem.numberOfChildEntries
                        && oldItem.notes == newItem.notes
            }
            return typeContentTheSame
                    && oldItem.nodeId == newItem.nodeId
                    && oldItem::class == newItem::class
                    && oldItem.title == newItem.title
                    && oldItem.icon == newItem.icon
                    && oldItem.isCurrentlyExpires == newItem.isCurrentlyExpires
                    && oldItem.tags == newItem.tags
                    && oldItem.path == newItem.path
        }

        override fun areItemsTheSame(item1: SortedNodeInfo, item2: SortedNodeInfo): Boolean {
            return item1.nodeId == item2.nodeId
        }
    }

    fun notifyNodeChanged(node: SortedNodeInfo) {
        for (i in 0 until mNodeSortedList.size()) {
            if (mNodeSortedList.get(i).nodeId == node.nodeId) {
                notifyItemChanged(i)
                break
            }
        }
    }

    fun setActionNodes(actionNodes: List<SortedNodeInfo>) {
        val oldIds = mActionNodeIds.toSet()
        val newIds = actionNodes.map { it.nodeId }.toSet()

        val nodesToUpdate = mutableSetOf<SortedNodeInfo>()
        // Items that were selected and now are not
        mActionNodesList.forEach { if (it.nodeId !in newIds) nodesToUpdate.add(it) }
        // Items that were not selected and now are
        actionNodes.forEach { if (it.nodeId !in oldIds) nodesToUpdate.add(it) }

        this.mActionNodesList.apply {
            clear()
            addAll(actionNodes)
        }
        this.mActionNodeIds.apply {
            clear()
            addAll(newIds)
        }
        nodesToUpdate.forEach {
            notifyNodeChanged(it)
        }
    }

    fun unselectActionNodes() {
        setActionNodes(listOf())
    }

    fun buildNodeComparator(): Comparator<SortedNodeInfo> {
        return this.sortNode.getNodeComparator(
            this.sortDatabaseParameters,
            this.sortNodeParameters
        )
    }

    /**
     * Notify a change sort of the list
     */
    fun notifyChangeSort(
        sortNodeEnum: SortNodeEnum? = null,
        sortDatabaseParameters: SortNodeEnum.SortDatabaseParameters? = null,
        sortNodeParameters: SortNodeEnum.SortNodeParameters? = null
    ) {
        this.sortNode = sortNodeEnum ?: this.sortNode
        this.sortDatabaseParameters = sortDatabaseParameters ?: this.sortDatabaseParameters
        this.sortNodeParameters = sortNodeParameters ?: this.sortNodeParameters
        this.mNodeComparator = buildNodeComparator()
    }

    override fun getItemViewType(position: Int): Int {
        return when (mNodeSortedList.get(position)) {
            is GroupInfo -> NodeType.GROUP.ordinal
            else -> NodeType.ENTRY.ordinal
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NodeViewHolder {
        val view: View = if (viewType == NodeType.GROUP.ordinal) {
            mInflater.inflate(R.layout.item_list_nodes_group, parent, false)
        } else {
            mInflater.inflate(R.layout.item_list_nodes_entry, parent, false)
        }
        val nodeViewHolder = NodeViewHolder(view)
        mTextDefaultDimension = nodeViewHolder.text.textSize
        mSubTextDefaultDimension = nodeViewHolder.subText?.textSize ?: mSubTextDefaultDimension
        mMetaTextDefaultDimension = nodeViewHolder.meta.textSize
        nodeViewHolder.numberChildren?.let {
            mNumberChildrenTextDefaultDimension = it.textSize
        }
        return nodeViewHolder
    }

    override fun onBindViewHolder(holder: NodeViewHolder, position: Int) {
        val node = mNodeSortedList.get(position)

        // Node selection
        val isSelected = mActionNodeIds.contains(node.nodeId)
        holder.container.isSelected = isSelected

        // Assign text
        holder.text.apply {
            text = node.title
            setTextSize(mTextSizeUnit, mTextDefaultDimension, mPrefSizeMultiplier)
            strikeOut(node.isCurrentlyExpires)
            underline(node.isReferenced)
        }
        // Custom tags are no longer displayed on list cards; unified category pill badge is used instead
        holder.tags.visibility = View.GONE
        // Add meta text to show UUID
        holder.meta.apply {
            val nodeId = node.nodeId.toVisualString()
            if (mShowUUID && nodeId != null) {
                text = nodeId
                setTextSize(mTextSizeUnit, mMetaTextDefaultDimension, mPrefSizeMultiplier)
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        // Keep path view hidden so every card maintains a uniform 72dp 2-line height
        holder.path?.visibility = View.GONE

        // Assign icon colors
        var iconColor = if (holder.container.isSelected)
            mColorOnSecondary
        else when (node) {
            is GroupInfo -> Color.parseColor("#4D6BFE")
            is EntryInfo -> mColorSecondary
            else -> mColorSecondary
        }

        // Unified 3-dots Menu Button (⋮) for both entries and groups
        holder.menuBtn?.apply {
            visibility = View.VISIBLE
            setOnClickListener { v ->
                mNodeClickCallback?.onNodeMenuClick(node, v)
            }
        }

        // Specific elements for entry
        if (node is EntryInfo) {
            holder.text.text = node.getVisualTitle()
            // Standardized 2-line subText: always visible with smart fallback
            holder.subText?.apply {
                val subtitle = resolveEntrySubtitle(node)
                visibility = View.VISIBLE
                text = subtitle
                setTextSize(mTextSizeUnit, mSubTextDefaultDimension, mPrefSizeMultiplier)
                strikeOut(node.isCurrentlyExpires)
            }

            // OTP inline display
            holder.otpDisplay?.setOtpModel(node.otpModel)

            // Category Pill Badge on the right of Row 1 (O(1) lookup via resolved canonical template UUID)
            val tplUuid = node.template.uuid
            val templateTitle = node.template.title
            holder.badgeTotp?.let { badgeView ->
                when {
                    tplUuid == com.kunzisoft.keepass.database.element.template.TemplateBuilder.UUID_EMAIL -> AvatarHelper.bindCategoryBadge(
                        badgeView,
                        context.getString(R.string.email),
                        Color.parseColor("#3B82F6"),
                        Color.parseColor("#1F3B82F6")
                    )
                    tplUuid == com.kunzisoft.keepass.database.element.template.TemplateBuilder.UUID_WIFI -> AvatarHelper.bindCategoryBadge(
                        badgeView,
                        context.getString(R.string.wireless),
                        Color.parseColor("#06B6D4"),
                        Color.parseColor("#1F06B6D4")
                    )
                    tplUuid == com.kunzisoft.keepass.database.element.template.TemplateBuilder.UUID_ID_CARD -> AvatarHelper.bindCategoryBadge(
                        badgeView,
                        context.getString(R.string.id_card),
                        Color.parseColor("#8B5CF6"),
                        Color.parseColor("#1F8B5CF6")
                    )
                    tplUuid == com.kunzisoft.keepass.database.element.template.TemplateBuilder.UUID_BANK -> AvatarHelper.bindCategoryBadge(
                        badgeView,
                        context.getString(R.string.bank),
                        Color.parseColor("#0284C7"),
                        Color.parseColor("#1F0284C7")
                    )
                    tplUuid == com.kunzisoft.keepass.database.element.template.TemplateBuilder.UUID_CRYPTO -> AvatarHelper.bindCategoryBadge(
                        badgeView,
                        context.getString(R.string.cryptocurrency),
                        Color.parseColor("#EC4899"),
                        Color.parseColor("#1FEC4899")
                    )
                    tplUuid == com.kunzisoft.keepass.database.element.template.TemplateBuilder.UUID_CREDIT_CARD -> AvatarHelper.bindCategoryBadge(
                        badgeView,
                        context.getString(R.string.debit_credit_card),
                        Color.parseColor("#F59E0B"),
                        Color.parseColor("#1FF59E0B")
                    )
                    tplUuid == com.kunzisoft.keepass.database.element.template.TemplateBuilder.UUID_TOTP || node.otpModel != null -> AvatarHelper.bindCategoryBadge(
                        badgeView,
                        context.getString(R.string.template_totp),
                        Color.parseColor("#10B981"),
                        Color.parseColor("#1F10B981")
                    )
                    tplUuid == com.kunzisoft.keepass.database.element.template.TemplateBuilder.UUID_PASSKEY || node.passkey != null -> AvatarHelper.bindCategoryBadge(
                        badgeView,
                        context.getString(R.string.passkey),
                        Color.parseColor("#6366F1"),
                        Color.parseColor("#1F6366F1")
                    )
                    tplUuid == com.kunzisoft.keepass.database.element.template.TemplateBuilder.UUID_NOTES -> AvatarHelper.bindCategoryBadge(
                        badgeView,
                        context.getString(R.string.secure_note),
                        Color.parseColor("#14B8A6"),
                        Color.parseColor("#1F14B8A6")
                    )
                    templateTitle.isNotBlank() && !templateTitle.equals(TemplateField.LABEL_STANDARD, ignoreCase = true) -> AvatarHelper.bindCategoryBadge(
                        badgeView,
                        TemplateField.getLocalizedName(context, templateTitle),
                        Color.parseColor("#8B5CF6"),
                        Color.parseColor("#1F8B5CF6")
                    )
                    else -> AvatarHelper.bindCategoryBadge(
                        badgeView,
                        context.getString(R.string.standard),
                        Color.parseColor("#4D6BFE"),
                        Color.parseColor("#1F4D6BFE")
                    )
                }
            }

            // Attachment
            holder.attachmentIcon?.visibility =
                if (node.containsAttachment()) View.VISIBLE else View.GONE

            // Passkey action icon
            val passkey = node.passkey
            if (passkey != null) {
                holder.passkeyIcon?.visibility = View.VISIBLE
                holder.passkeyIcon?.setOnClickListener {
                    mNodeClickCallback?.onPasskeyClick(node, passkey)
                }
            } else {
                holder.passkeyIcon?.visibility = View.GONE
                holder.passkeyIcon?.setOnClickListener(null)
            }

            // Assign colors
            assignBackgroundColor(holder.container, node)
            assignBackgroundColor(holder.otpDisplay, node)
            val foregroundColor = if (mShowEntryColors) node.foregroundColor else null
            if (!isSelected) {
                if (foregroundColor != null) {
                    holder.text.setTextColor(foregroundColor)
                    holder.subText?.setTextColor(foregroundColor)
                    holder.otpDisplay?.setTokenTextColor(foregroundColor)
                    holder.otpDisplay?.setProgressIndicatorColor(foregroundColor)
                    holder.attachmentIcon?.setColorFilter(foregroundColor)
                    holder.passkeyIcon?.setColorFilter(foregroundColor)
                    holder.meta.setTextColor(foregroundColor)
                    holder.path?.setTextColor(foregroundColor)
                    iconColor = foregroundColor
                } else {
                    holder.text.setTextColor(mTextColor)
                    holder.subText?.setTextColor(mTextColorSecondary)
                    holder.otpDisplay?.setTokenTextColor(mTextColor)
                    holder.otpDisplay?.setProgressIndicatorColor(mColorSecondary)
                    holder.attachmentIcon?.setColorFilter(mTextColorSecondary)
                    holder.passkeyIcon?.setColorFilter(mTextColorSecondary)
                    holder.meta.setTextColor(mTextColor)
                    holder.path?.setTextColor(mTextColor)
                }
            } else {
                holder.text.setTextColor(mColorOnSecondary)
                holder.subText?.setTextColor(mColorOnSecondary)
                holder.otpDisplay?.setTokenTextColor(mColorOnSecondary)
                holder.otpDisplay?.setProgressIndicatorColor(mColorOnSecondary)
                holder.attachmentIcon?.setColorFilter(mColorOnSecondary)
                holder.passkeyIcon?.setColorFilter(mColorOnSecondary)
                holder.meta.setTextColor(mColorOnSecondary)
                holder.path?.setTextColor(mColorOnSecondary)
            }
        }

        // Specific elements for groups (folders)
        if (node is SortedGroupInfo) {
            holder.numberChildren?.visibility = View.GONE
            holder.subText?.apply {
                visibility = View.VISIBLE
                text = context.getString(R.string.group_entries_count_subtitle, node.numberOfChildEntries)
                setTextSize(mTextSizeUnit, mSubTextDefaultDimension, mPrefSizeMultiplier)
                setTextColor(if (isSelected) mColorOnSecondary else mTextColorSecondary)
            }
            holder.text.setTextColor(if (isSelected) mColorOnSecondary else mTextColor)
            holder.badgeTotp?.let { badgeView ->
                AvatarHelper.bindCategoryBadge(
                    badgeView,
                    context.getString(R.string.entry_badge_group),
                    Color.parseColor("#64748B"),
                    Color.parseColor("#1F64748B")
                )
            }
            ViewCompat.setBackgroundTintList(
                holder.container,
                ColorStateList.valueOf(if (isSelected) mColorSecondary else mColorSurfaceContainer)
            )
            holder.iconFrame?.background = AvatarHelper.getGroupSquircleBackground(context)
        }

        // Assign image
        holder.imageIdentifier?.setColorFilter(iconColor)
        holder.icon.apply {
            if (node is EntryInfo) {
                val iconImage = node.icon.getIconImageToDraw()
                val hasCustomIcon = iconImage.custom.uuid.mostSignificantBits != 0L || iconImage.custom.uuid.leastSignificantBits != 0L
                if (hasCustomIcon) {
                    iconDrawableFactory.assignDatabaseIcon(this, node.icon, iconColor)
                } else {
                    setImageDrawable(AvatarHelper.getAvatarDrawable(context, node.getVisualTitle()))
                    ImageViewCompat.setImageTintList(this, null)
                }
                val entryIconDp = 44f * context.resources.displayMetrics.density
                layoutParams?.apply {
                    height = (entryIconDp * mPrefSizeMultiplier).toInt()
                    width = (entryIconDp * mPrefSizeMultiplier).toInt()
                }
            } else {
                iconDrawableFactory.assignDatabaseIcon(this, node.icon, iconColor)
                val groupIconDp = 24f * context.resources.displayMetrics.density
                layoutParams?.apply {
                    height = (groupIconDp * mPrefSizeMultiplier).toInt()
                    width = (groupIconDp * mPrefSizeMultiplier).toInt()
                }
            }
        }

        // Assign click
        holder.container.setOnClickListener {
            mNodeClickCallback?.onNodeClick(node)
        }
        holder.container.setOnLongClickListener {
            mNodeClickCallback?.onNodeLongClick(node) ?: false
        }
    }

    private fun resolveEntrySubtitle(node: EntryInfo): String {
        if (mShowUserNames && node.username.isNotBlank()) {
            return node.username
        }
        val cardNumChars = node.creditCard?.number
        if (cardNumChars != null && cardNumChars.isNotEmpty()) {
            val cardStr = String(cardNumChars).filter { !it.isWhitespace() }
            if (cardStr.isNotEmpty()) {
                val tail = if (cardStr.length > 4) cardStr.takeLast(4) else cardStr
                return "•••• $tail"
            }
        }
        val rp = node.passkey?.relyingParty
        if (!rp.isNullOrBlank()) {
            return rp
        }
        if (node.url.isNotBlank()) {
            return node.url
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
                .substringBefore("/")
        }
        if (node.notes.isNotBlank()) {
            val firstLine = node.notes.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            if (!firstLine.isNullOrEmpty()) return firstLine
        }
        val path = (node as? SortedEntryInfo)?.path
        if (!path.isNullOrBlank() && path != "/") {
            return path
        }
        return context.getString(R.string.entry_no_username_placeholder)
    }

    private fun assignBackgroundColor(view: View?, entry: EntryInfo) {
        view?.let {
            ViewCompat.setBackgroundTintList(
                view,
                ColorStateList.valueOf(
                    if (!view.isSelected) {
                        (if (mShowEntryColors) entry.backgroundColor else null)
                            ?: mColorSurfaceContainer
                    } else {
                        mColorSecondary
                    }
                )
            )
        }
    }

    override fun getItemCount(): Int {
        return mNodeSortedList.size()
    }

    /**
     * Assign a listener when a node is clicked
     */
    fun setOnNodeClickListener(nodeClickCallback: NodeClickCallback?) {
        this.mNodeClickCallback = nodeClickCallback
    }

    /**
     * Callback listener to redefine to do an action when a node is clicked
     */
    interface NodeClickCallback {
        fun onNodeClick(node: SortedNodeInfo)
        fun onNodeLongClick(node: SortedNodeInfo): Boolean
        fun onPasskeyClick(node: SortedNodeInfo, passkey: Passkey) {}
        fun onNodeMenuClick(node: SortedNodeInfo, anchorView: View) {}
    }

    class NodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var container: View = itemView.findViewById(R.id.node_container)
        var imageIdentifier: ImageView? = itemView.findViewById(R.id.node_image_identifier)
        var iconFrame: View? = itemView.findViewById(R.id.node_icon_frame)
        var icon: ImageView = itemView.findViewById(R.id.node_icon)
        var text: TextView = itemView.findViewById(R.id.node_text)
        var subText: TextView? = itemView.findViewById(R.id.node_subtext)
        var tags: RecyclerView = itemView.findViewById(R.id.node_tags_list_view)
        var meta: TextView = itemView.findViewById(R.id.node_meta)
        var path: TextView? = itemView.findViewById(R.id.node_path)
        var otpDisplay: OtpDisplayView? = itemView.findViewById(R.id.node_otp_display)
        var numberChildren: TextView? = itemView.findViewById(R.id.node_child_numbers)
        var attachmentIcon: ImageView? = itemView.findViewById(R.id.node_attachment_icon)
        var passkeyIcon: ImageView? = itemView.findViewById(R.id.node_passkey_icon)
        var menuBtn: ImageView? = itemView.findViewById(R.id.node_btn_menu)
        var badgeTotp: TextView? = itemView.findViewById(R.id.node_badge_totp)
    }

    companion object {
        private val TAG = NodesAdapter::class.java.name
    }
}
