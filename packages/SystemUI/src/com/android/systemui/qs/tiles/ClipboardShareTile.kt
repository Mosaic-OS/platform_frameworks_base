package com.android.systemui.qs.tiles

import android.app.ActivityManager
import android.content.ClipboardManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.service.quicksettings.Tile
import com.android.internal.logging.MetricsLogger
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
import com.android.systemui.qs.tiles.dialog.ClipboardShareDialogManager
import com.android.systemui.res.R
import javax.inject.Inject

class ClipboardShareTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main private val mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val dialogManager: ClipboardShareDialogManager,
) : QSTileImpl<QSTile.BooleanState>(
    host, uiEventLogger, backgroundLooper, mainHandler,
    falsingManager, metricsLogger, statusBarStateController,
    activityStarter, qsLogger
) {
    companion object {
        const val TILE_SPEC = "clipboard_share"
    }

    override fun newTileState() = QSTile.BooleanState()

    override fun handleClick(expandable: Expandable?) {
        mainHandler.post {
            dialogManager.create(mContext, expandable)
        }
    }

    override fun handleLongClick(expandable: Expandable?) {
        // Not needed, but must implement
    }

    override fun getLongClickIntent(): Intent? = null

    override fun handleUpdateState(state: QSTile.BooleanState, arg: Any?) {
        val currentUserId = ActivityManager.getCurrentUser()

        val hasClip = try {
            val userCtx = mContext.createContextAsUser(UserHandle.of(currentUserId), 0)
            userCtx.getSystemService(ClipboardManager::class.java)?.hasPrimaryClip() == true
        } catch (e: Exception) {
            false
        }

        val um = mContext.getSystemService(UserManager::class.java)
        val hasOtherUsers = um?.getAliveUsers()?.any {
            it.id != currentUserId && it.isEnabled && !it.isManagedProfile
        } == true

        state.label = tileLabel
        state.icon = ResourceIcon.get(R.drawable.ic_content_paste)

        when {
            !hasClip -> {
                state.state = Tile.STATE_UNAVAILABLE
                state.secondaryLabel = mContext.getString(R.string.clipboard_share_empty)
            }
            !hasOtherUsers -> {
                state.state = Tile.STATE_UNAVAILABLE
                state.secondaryLabel = mContext.getString(R.string.clipboard_share_no_users)
            }
            else -> {
                state.state = Tile.STATE_ACTIVE
                state.secondaryLabel = ""
            }
        }
        state.value = state.state == Tile.STATE_ACTIVE
    }

    override fun isAvailable() = true

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.clipboard_share_title)
}