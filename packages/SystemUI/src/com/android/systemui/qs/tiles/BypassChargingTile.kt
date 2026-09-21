/*
 * Copyright (C) 2026 The MosaicOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles

import android.content.Intent
import android.database.ContentObserver
import android.ext.power.BatteryBypassCharging
import android.ext.power.BatteryChargeLimit
import android.icu.text.NumberFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.widget.Switch
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
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import javax.inject.Inject

class BypassChargingTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val userTracker: UserTracker,
) : QSTileImpl<QSTile.BooleanState>(
    host, uiEventLogger, backgroundLooper, mainHandler,
    falsingManager, metricsLogger, statusBarStateController, activityStarter, qsLogger
) {
    companion object {
        const val TILE_SPEC = "bypass_charging"
    }

    private val observer = object : ContentObserver(mHandler) {
        override fun onChange(selfChange: Boolean) = refreshState()
    }

    override fun newTileState() = QSTile.BooleanState()

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        if (listening) {
            mContext.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.BATTERY_BYPASS_STATE), false, observer
            )
            refreshState()
        } else {
            mContext.contentResolver.unregisterContentObserver(observer)
        }
    }

    override fun handleDestroy() {
        mContext.contentResolver.unregisterContentObserver(observer)
        super.handleDestroy()
    }

    override fun handleClick(expandable: Expandable?) {
        if (userTracker.userId != 0 || !BatteryBypassCharging.isAvailable(mContext) ||
            !BatteryBypassCharging.isPluggedIn(mContext)) return
        val mode = if (BatteryBypassCharging.isEnabled(mContext)) {
            if (BatteryChargeLimit.isChargeLimitEnabled(mContext)) {
                BatteryBypassCharging.MODE_LIMIT
            } else {
                BatteryBypassCharging.MODE_OFF
            }
        } else {
            BatteryBypassCharging.MODE_BYPASS
        }
        BatteryBypassCharging.requestMode(mContext, mode)
    }

    override fun getLongClickIntent() = Intent(Intent.ACTION_POWER_USAGE_SUMMARY)

    override fun handleUpdateState(state: QSTile.BooleanState, arg: Any?) {
        val available = userTracker.userId == 0 && BatteryBypassCharging.isAvailable(mContext)
        val plugged = BatteryBypassCharging.isPluggedIn(mContext)
        val held = BatteryBypassCharging.getHeldLevel(mContext)
        state.label = tileLabel
        state.icon = ResourceIcon.get(R.drawable.ic_bypass_charging)
        state.value = BatteryBypassCharging.isEnabled(mContext)
        state.state = when {
            !available || !plugged -> Tile.STATE_UNAVAILABLE
            held > 0 -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        state.secondaryLabel = when {
            !available -> mContext.getString(when (BatteryBypassCharging.getAvailability(mContext)) {
                BatteryBypassCharging.SERVICE_MISSING -> R.string.bypass_charging_unavailable_service
                BatteryBypassCharging.HAL_ERROR -> R.string.bypass_charging_unavailable_hal
                BatteryBypassCharging.READBACK_ERROR -> R.string.bypass_charging_unavailable_readback
                BatteryBypassCharging.LEVEL_UNKNOWN -> R.string.bypass_charging_unavailable_level
                BatteryBypassCharging.RECOVERY_ERROR -> R.string.bypass_charging_unavailable_recovery
                BatteryBypassCharging.APPLYING -> R.string.bypass_charging_applying
                else -> R.string.bypass_charging_unavailable
            })
            !plugged -> mContext.getString(R.string.bypass_charging_connect_power)
            held > 0 -> mContext.getString(R.string.bypass_charging_held_level,
                NumberFormat.getPercentInstance().format(held / 100f))
            else -> mContext.getString(R.string.bypass_charging_summary)
        }
        state.contentDescription = "${state.label}, ${state.secondaryLabel}"
        state.expandedAccessibilityClassName = Switch::class.java.name
    }

    override fun isAvailable() = BatteryChargeLimit.isGoogleDevice()

    override fun getTileLabel(): CharSequence = mContext.getString(R.string.bypass_charging_title)
}
