/*
 * Copyright 2026 KeePassDX Contributors.
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
import com.kunzisoft.keepass.R

class OtpTextEditFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextEditFieldView(context, attrs, defStyle) {

    var onOtpSetupClickListener: (() -> Unit)? = null

    init {
        valueView.isFocusable = false
        valueView.isFocusableInTouchMode = false
        valueView.isClickable = true
        valueView.isLongClickable = false
        valueView.setOnClickListener {
            onOtpSetupClickListener?.invoke()
        }
        setOnClickListener {
            onOtpSetupClickListener?.invoke()
        }
    }

    fun setOtpState(hasOtp: Boolean, otpType: String?) {
        if (hasOtp) {
            val typeStr = otpType ?: "TOTP"
            value = context.getString(R.string.totp_field_configured, typeStr).toCharArray()
            valueView.hint = null
            setOnActionClickListener({
                onOtpSetupClickListener?.invoke()
            }, R.drawable.ic_mode_edit_white_24dp)
        } else {
            value = context.getString(R.string.totp_field_unconfigured).toCharArray()
            valueView.hint = null
            setOnActionClickListener({
                onOtpSetupClickListener?.invoke()
            }, R.drawable.ic_otp_white_24dp)
        }
    }
}
