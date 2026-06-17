package com.android.systemui.qs.tiles
 
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.text.TextUtils
import android.util.Log
import android.widget.Switch
import androidx.annotation.Nullable
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSIconViewImpl
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import com.android.systemui.statusbar.connectivity.AccessPointController
import com.android.systemui.statusbar.connectivity.NetworkController
import com.android.systemui.statusbar.connectivity.SignalCallback
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiTileIconModel
import com.android.systemui.statusbar.connectivity.WifiIndicators
import com.android.systemui.statusbar.connectivity.WifiIcons
import javax.inject.Inject
 
/** Quick settings tile: Wifi */
class WifiTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    networkController: NetworkController,
    private val mWifiController: AccessPointController,
) : QSTileImpl<BooleanState>(
    host,
    uiEventLogger,
    backgroundLooper,
    mainHandler,
    falsingManager,
    metricsLogger,
    statusBarStateController,
    activityStarter,
    qsLogger,
) {
 
    protected val mController: NetworkController = networkController
    private val mStateBeforeClick: BooleanState = newTileState().also { it.spec = "wifi" }
    protected val mSignalCallback = WifiSignalCallback()
    private var mExpectDisabled = false
 
    init {
        mController.observe(lifecycle, mSignalCallback)
    }
 
    override fun newTileState(): BooleanState = BooleanState()
 
    override fun getLongClickIntent(): Intent = WIFI_SETTINGS
 
    override fun handleClick(expandable: Expandable?) {
        mState.copyTo(mStateBeforeClick)
        val wifiEnabled = mState.value
        // Immediately enter transient state when turning on wifi.
        refreshState(if (wifiEnabled) null else ARG_SHOW_TRANSIENT_ENABLING)
        mController.setWifiEnabled(!wifiEnabled)
        mExpectDisabled = wifiEnabled
        if (mExpectDisabled) {
            mHandler.postDelayed({
                if (mExpectDisabled) {
                    mExpectDisabled = false
                    refreshState()
                }
            }, QSIconViewImpl.QS_ANIM_LENGTH)
        }
    }
 
    override fun handleSecondaryClick(expandable: Expandable?) {
        if (!mWifiController.canConfigWifi()) {
            mActivityStarter.postStartActivityDismissingKeyguard(
                Intent(Settings.ACTION_WIFI_SETTINGS), 0
            )
            return
        }
        if (!mState.value) {
            mController.setWifiEnabled(true)
        }
    }
 
    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_wifi_label)
 
    override fun handleUpdateState(state: BooleanState?, arg: Any?) {
        if (DEBUG) Log.d(TAG, "handleUpdateState arg=$arg")
        val cb = mSignalCallback.mInfo
        if (mExpectDisabled) {
            if (cb.enabled) {
                return // Ignore updates until disabled event occurs.
            } else {
                mExpectDisabled = false
            }
        }
        val transientEnabling = arg == ARG_SHOW_TRANSIENT_ENABLING
        val wifiConnected = cb.enabled
                && (cb.wifiSignalIconId > 0)
                && (cb.ssid != null || cb.wifiSignalIconId != WifiIcons.QS_WIFI_NO_NETWORK)
        val wifiNotConnected = (cb.ssid == null)
                && (cb.wifiSignalIconId == WifiIcons.QS_WIFI_NO_NETWORK)
        val isTransient = transientEnabling || cb.isTransient
 
        state?.apply {
            secondaryLabel = getSecondaryLabel(isTransient, cb.statusLabel)
            this.state = Tile.STATE_ACTIVE
            dualTarget = true
            value = transientEnabling || cb.enabled
 
            val minimalContentDescription = StringBuilder()
            val minimalStateDescription = StringBuilder()
            val r = mContext.resources
 
            when {
				isTransient -> {
					icon = maybeLoadResourceIcon(
						com.android.settingslib.R.drawable.ic_wifi_3
					)
					label = r.getString(R.string.quick_settings_wifi_label)
				}
				!value -> {
					this.state = Tile.STATE_INACTIVE
					icon = maybeLoadResourceIcon(R.drawable.ic_signal_wifi_off)
					label = r.getString(R.string.quick_settings_wifi_label)
				}
				wifiConnected -> {
					icon = maybeLoadResourceIcon(cb.wifiSignalIconId)
					label = if (cb.ssid != null) removeDoubleQuotes(cb.ssid) else getTileLabel()
				}
				wifiNotConnected -> {
					icon = maybeLoadResourceIcon(
						com.android.settingslib.R.drawable.ic_wifi_0
					)
					label = r.getString(R.string.quick_settings_wifi_label)
				}
				else -> {
					icon = maybeLoadResourceIcon(
						com.android.settingslib.R.drawable.ic_wifi_0
					)
					label = r.getString(R.string.quick_settings_wifi_label)
				}
			}
 
            minimalContentDescription
                .append(mContext.getString(R.string.quick_settings_wifi_label))
                .append(",")
 
            if (value && wifiConnected) {
                minimalStateDescription.append(cb.wifiSignalContentDescription)
                minimalContentDescription.append(removeDoubleQuotes(cb.ssid))
                if (!TextUtils.isEmpty(secondaryLabel)) {
                    minimalContentDescription.append(",").append(secondaryLabel)
                }
            }
 
            stateDescription = minimalStateDescription.toString()
            contentDescription = minimalContentDescription.toString()
            dualLabelContentDescription = r.getString(
                R.string.accessibility_quick_settings_open_settings, getTileLabel()
            )
            expandedAccessibilityClassName = Switch::class.java.name
        }
    }
 
    private fun getSecondaryLabel(isTransient: Boolean, statusLabel: String?): CharSequence? =
        if (isTransient) {
            mContext.getString(R.string.quick_settings_wifi_secondary_label_transient)
        } else {
            statusLabel
        }
 
    override fun getMetricsCategory(): Int = MetricsEvent.QS_WIFI
 
    override fun isAvailable(): Boolean =
        mContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
 
    data class CallbackInfo(
        var enabled: Boolean = false,
        var connected: Boolean = false,
        var wifiSignalIconId: Int = 0,
        var ssid: String? = null,
        var wifiSignalContentDescription: String? = null,
        var isTransient: Boolean = false,
        var statusLabel: String? = null,
    ) {
        override fun toString(): String =
            "CallbackInfo[" +
                "enabled=$enabled" +
                ",connected=$connected" +
                ",wifiSignalIconId=$wifiSignalIconId" +
                ",ssid=$ssid" +
                ",wifiSignalContentDescription=$wifiSignalContentDescription" +
                ",isTransient=$isTransient" +
                "]"
    }
 
    inner class WifiSignalCallback : SignalCallback {
        val mInfo = CallbackInfo()
 
        override fun setWifiIndicators(indicators: WifiIndicators) {
			if (DEBUG) Log.d(TAG, "onWifiSignalChanged enabled=${indicators.enabled}")
			if (indicators.qsIcon == null) {
				mInfo.enabled = indicators.enabled
				mInfo.connected = false
				mInfo.wifiSignalIconId = 0
				mInfo.ssid = null
				mInfo.wifiSignalContentDescription = null
				mInfo.isTransient = indicators.isTransient
				mInfo.statusLabel = indicators.statusLabel
				refreshState()
				return
			}
			mInfo.enabled = indicators.enabled
			mInfo.connected = indicators.qsIcon.visible
			mInfo.wifiSignalIconId = indicators.qsIcon.icon
			mInfo.ssid = indicators.description
			mInfo.wifiSignalContentDescription = indicators.qsIcon.contentDescription
			mInfo.isTransient = indicators.isTransient
			mInfo.statusLabel = indicators.statusLabel
			refreshState()
		}
    }
 
    companion object {
        const val TILE_SPEC = "wifi"
        private val WIFI_SETTINGS = Intent(Settings.ACTION_WIFI_SETTINGS)
 
        @Nullable
        private fun removeDoubleQuotes(string: String?): String? {
            if (string == null) return null
            val length = string.length
            if (length > 1 && string[0] == '"' && string[length - 1] == '"') {
                return string.substring(1, length - 1)
            }
            return string
        }
    }
}