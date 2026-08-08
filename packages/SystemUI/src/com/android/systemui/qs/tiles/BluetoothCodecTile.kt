package com.android.systemui.qs.tiles

import android.bluetooth.BluetoothA2dp
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import com.android.internal.logging.MetricsLogger
import com.android.settingslib.development.DevelopmentSettingsEnabler
import com.android.systemui.animation.Expandable
import com.android.systemui.bluetooth.BluetoothCodecController
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
import com.android.systemui.qs.tiles.dialog.BluetoothCodecDialogManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.BluetoothController
import javax.inject.Inject

class BluetoothCodecTile
@Inject
constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main private val mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val bluetoothController: BluetoothController,
    private val codecController: BluetoothCodecController,
    private val dialogManager: BluetoothCodecDialogManager,
) :
    QSTileImpl<QSTile.BooleanState>(
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

    companion object {
        const val TILE_SPEC = "blutilities"

        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val BLUETOOTH_DEVELOPMENT_ACTIVITY =
            "com.android.settings.Settings\$BluetoothDevelopmentSettingsActivity"
        private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
        private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        private const val CODEC_PREFERENCE_KEY = "bluetooth_audio_codec_settings_list"
    }

    private val bluetoothCallback =
        object : BluetoothController.Callback {
            override fun onBluetoothStateChange(enabled: Boolean) = refreshState()

            override fun onBluetoothDevicesChanged() = refreshState()
        }

    /** The stack renegotiates asynchronously, so the subtitle waits for the codec broadcast. */
    private val codecReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refreshState()
        }

    private var receiverRegistered = false

    override fun newTileState() = QSTile.BooleanState()

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        if (listening) {
            bluetoothController.addCallback(bluetoothCallback)
            registerCodecReceiver()
        } else {
            bluetoothController.removeCallback(bluetoothCallback)
            unregisterCodecReceiver()
        }
    }

    override fun handleDestroy() {
        unregisterCodecReceiver()
        super.handleDestroy()
    }

    private fun registerCodecReceiver() {
        if (receiverRegistered) return
        val filter =
            IntentFilter().apply {
                addAction(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED)
                addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED)
            }
        mContext.registerReceiver(codecReceiver, filter)
        receiverRegistered = true
    }

    private fun unregisterCodecReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        mContext.unregisterReceiver(codecReceiver)
    }

    override fun handleClick(expandable: Expandable?) {
        mainHandler.post { dialogManager.create(mContext, expandable) }
    }

    /** The key has to travel in the fragment arguments; the plain extra is flag-gated in Settings. */
    override fun getLongClickIntent(): Intent =
        Intent()
            .setComponent(ComponentName(SETTINGS_PACKAGE, BLUETOOTH_DEVELOPMENT_ACTIVITY))
            .putExtra(
                EXTRA_SHOW_FRAGMENT_ARGUMENTS,
                Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, CODEC_PREFERENCE_KEY) },
            )

    /** The target screen only exists while Developer options are on, so long press is inert. */
    override fun handleLongClick(expandable: Expandable?) {
        if (!DevelopmentSettingsEnabler.isDevelopmentSettingsEnabled(mContext)) return
        // Never hand an unresolvable component to the activity starter.
        if (mContext.packageManager.resolveActivity(getLongClickIntent(), 0) == null) return
        super.handleLongClick(expandable)
    }

    override fun handleUpdateState(state: QSTile.BooleanState, arg: Any?) {
        state.label = tileLabel
        state.icon = ResourceIcon.get(R.drawable.ic_blutilities)

        val enabled = bluetoothController.isBluetoothEnabled
        val device = if (enabled) codecController.activeDevice else null
        when {
            !enabled -> {
                state.state = Tile.STATE_UNAVAILABLE
                state.secondaryLabel = mContext.getString(R.string.blutilities_bluetooth_off)
            }
            device == null -> {
                state.state = Tile.STATE_UNAVAILABLE
                state.secondaryLabel = mContext.getString(R.string.blutilities_no_device)
            }
            else -> {
                state.state = Tile.STATE_ACTIVE
                state.secondaryLabel =
                    codecController.activeCodecLabel(device)
                        ?: mContext.getString(R.string.blutilities_connected)
            }
        }
        state.value = state.state == Tile.STATE_ACTIVE
        state.contentDescription = "${state.label}, ${state.secondaryLabel}"
    }

    override fun isAvailable() = true

    override fun getTileLabel(): CharSequence = mContext.getString(R.string.blutilities_title)
}
