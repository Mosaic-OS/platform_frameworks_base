/*
 * Copyright (C) 2026 The MosaicOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tiles

import android.app.ActivityManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import com.android.internal.logging.MetricsLogger
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.dialog.ProfileInboxDialogManager
import com.android.systemui.res.R
import javax.inject.Inject

class ProfileInboxTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main private val mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val dialogManager: ProfileInboxDialogManager,
) : QSTileImpl<QSTile.BooleanState>(
    host, uiEventLogger, backgroundLooper, mainHandler,
    falsingManager, metricsLogger, statusBarStateController,
    activityStarter, qsLogger
) {
    companion object {
        const val TILE_SPEC = "profile_inbox"
    }

    private val lockPatternUtils by lazy { LockPatternUtils(mContext) }

    override fun newTileState() = QSTile.BooleanState()

    override fun handleClick(expandable: Expandable?) {
        openProfileInbox(expandable, false)
    }

    override fun handleLongClick(expandable: Expandable?) {
        openProfileInbox(expandable, true)
    }

    private fun openProfileInbox(expandable: Expandable?, openInbox: Boolean) {
        if (ActivityManager.getCurrentUser() == lockPatternUtils.getSecretProfileUserId()) return
        mainHandler.post { dialogManager.create(mContext, expandable, openInbox) }
    }

    override fun getLongClickIntent(): Intent? = null

    override fun handleUpdateState(state: QSTile.BooleanState, arg: Any?) {
        val secret = ActivityManager.getCurrentUser() == lockPatternUtils.getSecretProfileUserId()
        state.label = tileLabel
        state.icon = ResourceIcon.get(R.drawable.ic_content_paste)
        state.state = if (secret) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
        state.secondaryLabel = mContext.getString(
            if (secret) R.string.profile_inbox_unavailable else R.string.profile_inbox_tile_hint
        )
        state.value = false
    }

    override fun isAvailable() = true

    override fun getTileLabel(): CharSequence = mContext.getString(R.string.profile_inbox_title)
}
