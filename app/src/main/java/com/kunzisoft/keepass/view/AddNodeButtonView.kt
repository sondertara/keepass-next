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
package com.kunzisoft.keepass.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.RelativeLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.kunzisoft.keepass.R

class AddNodeButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : RelativeLayout(context, attrs, defStyle) {

    var addButtonView: FloatingActionButton? = null

    private var onFabClickListener: OnClickListener? = null
    private var onAddGroupClickListener: OnClickListener? = null
    private var onAddEntryClickListener: OnClickListener? = null
    private var onScanOtpClickListener: OnClickListener? = null
    private var onPickImageOtpClickListener: OnClickListener? = null

    private var onAddButtonVisibilityChangedListener: FloatingActionButton.OnVisibilityChangedListener? = null

    val isEnable: Boolean
        get() = visibility == View.VISIBLE

    init {
        inflate(context)
        hideButton()
    }

    private fun inflate(context: Context) {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.view_button_add_node, this, true)

        addButtonView = findViewById(R.id.add_button)

        addButtonView?.setOnClickListener { v ->
            onFabClickListener?.onClick(v)
                ?: onAddEntryClickListener?.onClick(v)
        }

        onAddButtonVisibilityChangedListener = object : FloatingActionButton.OnVisibilityChangedListener() {
            override fun onHidden(fab: FloatingActionButton?) {
                super.onHidden(fab)
                addButtonView?.isClickable = false
            }

            override fun onShown(fab: FloatingActionButton?) {
                super.onShown(fab)
                addButtonView?.isClickable = true
            }
        }
    }

    fun hideOrShowButtonOnScrollListener(dy: Int) {
        if (dy > 0 && addButtonView?.visibility == View.VISIBLE) {
            hideButton()
        } else if (dy < 0 && addButtonView?.visibility != View.VISIBLE) {
            showButton()
        }
    }

    fun showButton() {
        if (isEnable && addButtonView?.visibility != VISIBLE)
            addButtonView?.show(onAddButtonVisibilityChangedListener)
    }

    fun hideButton() {
        if (addButtonView?.visibility == VISIBLE)
            addButtonView?.hide(onAddButtonVisibilityChangedListener)
    }

    fun setMainFabClickListener(listener: OnClickListener?) {
        this.onFabClickListener = listener
    }

    fun setMainFabLongClickListener(listener: OnLongClickListener?) {
        addButtonView?.setOnLongClickListener(listener)
    }

    fun setAddGroupClickListener(listener: OnClickListener?) {
        this.onAddGroupClickListener = listener
    }

    fun setAddEntryClickListener(listener: OnClickListener?) {
        this.onAddEntryClickListener = listener
    }

    fun setScanOtpClickListener(listener: OnClickListener?) {
        this.onScanOtpClickListener = listener
    }

    fun setPickImageOtpClickListener(listener: OnClickListener?) {
        this.onPickImageOtpClickListener = listener
    }

    fun enableAddGroup(enable: Boolean) {}

    fun enableAddEntry(enable: Boolean) {}

    fun enableScanOtp(enable: Boolean) {}

    fun enablePickImageOtp(enable: Boolean) {}

    fun closeButtonIfOpen() {}

    fun openButtonIfClose() {}
}
