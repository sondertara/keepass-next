/*
 * Copyright 2026 KeePassDX Contributors.
 *
 * This file is part of KeePassDX.
 *
 * KeePassDX is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * KeePassDX is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with KeePassDX. If not, see <http://www.gnu.org/licenses/>.
 */
package com.kunzisoft.keepass.activities.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.model.EntryType
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.utils.AppUtil

class NewEntryTypeBottomSheetDialogFragment : BottomSheetDialogFragment() {

    interface Listener {
        fun onTypeSelected(type: EntryType)
        fun onTotpScanQr()
        fun onTotpPickImage()
        fun onTotpImportLink()
        fun onGroupSelected()
    }

    var listener: Listener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_new_entry_type, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        context?.let {
            AppUtil.setScreenshotMode(dialog?.window, PreferencesUtil.isScreenshotModeEnabled(it))
        }

        // 1. Password
        view.findViewById<View>(R.id.item_type_password)?.setOnClickListener {
            dismiss()
            listener?.onTypeSelected(EntryType.PASSWORD)
        }

        // 2. TOTP (Expandable actions)
        val totpSubActions = view.findViewById<View>(R.id.layout_totp_actions)
        val totpArrow = view.findViewById<ImageView>(R.id.icon_totp_arrow)
        view.findViewById<View>(R.id.item_type_totp)?.setOnClickListener {
            if (totpSubActions != null) {
                if (totpSubActions.visibility == View.VISIBLE) {
                    totpSubActions.visibility = View.GONE
                    totpArrow?.animate()?.rotation(0f)?.setDuration(200)?.start()
                } else {
                    totpSubActions.visibility = View.VISIBLE
                    totpArrow?.animate()?.rotation(180f)?.setDuration(200)?.start()
                }
            } else {
                dismiss()
                listener?.onTypeSelected(EntryType.TOTP)
            }
        }

        view.findViewById<View>(R.id.btn_totp_scan)?.setOnClickListener {
            dismiss()
            listener?.onTotpScanQr()
        }

        view.findViewById<View>(R.id.btn_totp_gallery)?.setOnClickListener {
            dismiss()
            listener?.onTotpPickImage()
        }

        view.findViewById<View>(R.id.btn_totp_link)?.setOnClickListener {
            dismiss()
            listener?.onTotpImportLink()
        }

        view.findViewById<View>(R.id.btn_totp_manual)?.setOnClickListener {
            dismiss()
            listener?.onTypeSelected(EntryType.TOTP)
        }

        // 3. Passkey (Directly opens unified QR scanner for cross-device registration/login)
        view.findViewById<View>(R.id.item_type_passkey)?.setOnClickListener {
            dismiss()
            listener?.onTotpScanQr()
        }

        // 4. Credit Card
        view.findViewById<View>(R.id.item_type_credit_card)?.setOnClickListener {
            dismiss()
            listener?.onTypeSelected(EntryType.CREDIT_CARD)
        }

        // 5. Secure Note
        view.findViewById<View>(R.id.item_type_secure_note)?.setOnClickListener {
            dismiss()
            listener?.onTypeSelected(EntryType.SECURE_NOTE)
        }

        // 6. Email
        view.findViewById<View>(R.id.item_type_email)?.setOnClickListener {
            dismiss()
            listener?.onTypeSelected(EntryType.EMAIL)
        }

        // 7. Wi-Fi (Wireless)
        view.findViewById<View>(R.id.item_type_wifi)?.setOnClickListener {
            dismiss()
            listener?.onTypeSelected(EntryType.WIFI)
        }

        // 8. ID Card
        view.findViewById<View>(R.id.item_type_id_card)?.setOnClickListener {
            dismiss()
            listener?.onTypeSelected(EntryType.ID_CARD)
        }

        // 9. Bank
        view.findViewById<View>(R.id.item_type_bank)?.setOnClickListener {
            dismiss()
            listener?.onTypeSelected(EntryType.BANK)
        }

        // 10. Cryptocurrency
        view.findViewById<View>(R.id.item_type_crypto)?.setOnClickListener {
            dismiss()
            listener?.onTypeSelected(EntryType.CRYPTOCURRENCY)
        }

        // 11. Group
        view.findViewById<View>(R.id.item_type_group)?.setOnClickListener {
            dismiss()
            listener?.onGroupSelected()
        }
    }

    companion object {
        const val TAG = "NewEntryTypeBottomSheetDialogFragment"

        fun newInstance(): NewEntryTypeBottomSheetDialogFragment {
            return NewEntryTypeBottomSheetDialogFragment()
        }
    }
}
