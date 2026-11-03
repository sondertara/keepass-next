/*
 * Copyright 2024 Jeremy Jamet / Kunzisoft.
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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.password.PasswordEntropy
import com.kunzisoft.keepass.password.PasswordGenerator
import com.kunzisoft.keepass.settings.PreferencesUtil


class PasswordTextEditFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextEditFieldView(context, attrs, defStyle) {

    private var mPasswordEntropyCalculator: PasswordEntropy = PasswordEntropy {
        valueView.text?.toString()?.let { firstPassword ->
            getEntropyStrength(firstPassword)
        }
    }
    private var isColorizedPasswordActivated = PreferencesUtil.colorizePassword(context)

    private var passwordAssistRowId = ViewCompat.generateViewId()

    private var mPasswordProgress = LinearProgressIndicator(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        trackThickness = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            6f,
            context.resources.displayMetrics
        ).toInt()
        trackCornerRadius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            3f,
            context.resources.displayMetrics
        ).toInt()
        setIndicatorColor(PasswordEntropy.Strength.RISKY.color)
        progress = 0
        max = 100
    }

    private val mPasswordEntropyView = TextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                2f,
                context.resources.displayMetrics
            ).toInt()
        }
        TextViewCompat.setTextAppearance(this, R.style.KeepassDXStyle_Text_Indicator)
    }

    private val mGeneratePasswordButton = MaterialButton(
        context,
        null,
        com.google.android.material.R.attr.materialButtonTonalStyle
    ).apply {
        val heightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            32f,
            context.resources.displayMetrics
        ).toInt()
        val padH = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            10f,
            context.resources.displayMetrics
        ).toInt()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            heightPx
        )
        insetTop = 0
        insetBottom = 0
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(padH, 0, padH, 0)
        cornerRadius = heightPx / 2
        icon = ContextCompat.getDrawable(context, R.drawable.ic_random_white_24dp)
        iconSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            15f,
            context.resources.displayMetrics
        ).toInt()
        iconPadding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            4f,
            context.resources.displayMetrics
        ).toInt()
        text = context.getString(R.string.generate_password)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        visibility = GONE
    }

    private val mPasswordAssistRow = LinearLayout(context).apply {
        id = passwordAssistRowId
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                6f,
                context.resources.displayMetrics
            ).toInt()
            addRule(BELOW, labelViewId)
        }
        val leftColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    12f,
                    context.resources.displayMetrics
                ).toInt()
            }
            addView(mPasswordProgress)
            addView(mPasswordEntropyView)
        }
        addView(leftColumn)
        addView(mGeneratePasswordButton)
    }

    init {
        valueView.doAfterTextChanged { editable ->
            getEntropyStrength(editable.toString())
            PasswordGenerator.colorizedPassword(editable)
        }

        addView(mPasswordAssistRow)
    }

    override fun getActionImageView(): View {
        return mGeneratePasswordButton
    }

    override fun setOnActionClickListener(
        onActionClickListener: OnClickListener?,
        @DrawableRes actionImageId: Int?
    ) {
        actionImageId?.let {
            mGeneratePasswordButton.icon = ContextCompat.getDrawable(context, it)
        }
        mGeneratePasswordButton.setOnClickListener(onActionClickListener)
        mGeneratePasswordButton.visibility = if (onActionClickListener == null) GONE else VISIBLE
    }

    private fun getEntropyStrength(passwordText: String) {
        mPasswordEntropyCalculator.getEntropyStrength(passwordText) { entropyStrength ->
            mPasswordProgress.apply {
                post {
                    setIndicatorColor(entropyStrength.strength.color)
                    setProgressCompat(entropyStrength.estimationPercent, true)
                }
            }
            mPasswordEntropyView.apply {
                post {
                    text = PasswordEntropy.getStringEntropy(resources, entropyStrength.entropy)
                }
            }
        }
    }

    override var value: CharArray
        get() = super.value
        set(value) {
            if (isColorizedPasswordActivated) {
                valueView.setText(PasswordGenerator.getColorizedPassword(value))
            } else {
                valueView.setCharArray(value)
            }
        }

    override var label: String
        get() {
            return super.label
        }
        set(value) {
            super.label = value
        }
}