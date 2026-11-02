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
package com.kunzisoft.keepass.activities.session

import android.os.Bundle
import android.view.View
import com.kunzisoft.keepass.activities.stylish.StylishActivity
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.tasks.ActionRunnable

/**
 * Modern, lightweight base Activity that composes DatabaseSessionController.
 * Inherits from StylishActivity (which extends standard AppCompatActivity).
 * Replaces the obsolete 4-layer inheritance tree in activities/legacy/.
 */
abstract class DatabaseSessionActivity : StylishActivity(), DatabaseSessionHolder {

    open fun createSessionOptions(): DatabaseSessionOptions = DatabaseSessionOptions()

    override val sessionController: DatabaseSessionController by lazy {
        DatabaseSessionController(
            activity = this,
            callback = this,
            options = createSessionOptions()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionController.onRestoreInstanceState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sessionController.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {}

    override fun onUnknownDatabaseRetrieved(database: ContextualDatabase?) {}

    override fun onDatabaseActionFinished(
        database: ContextualDatabase,
        actionTask: String,
        result: ActionRunnable.Result
    ) {}

    override fun viewToInvalidateTimeout(): View? = null

    override fun errorCoordinatorView(): View? = null

    override fun showDatabaseDialog(): Boolean = true

    override fun finishActivityIfReloadRequested(): Boolean =
        sessionController.options.finishActivityIfReloadRequested

    override fun manageDatabaseInfo(): Boolean =
        sessionController.options.manageDatabaseInfo

    override fun hideHomeButtonIfModeIsNotDefault(): Boolean = true

    override fun onDatabaseBackPressed() {
        if (sessionController.specialMode != com.kunzisoft.keepass.credentialprovider.SpecialMode.DEFAULT) {
            sessionController.onCancelSpecialMode()
        } else {
            finish()
        }
    }
}
