package com.android.systemui.bluetooth

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import javax.inject.Inject

/**
 * Reads and applies the A2DP codec preference of the active audio device. The privileged codec
 * APIs are called directly because SystemUI holds BLUETOOTH_PRIVILEGED.
 */
@SysUISingleton
class BluetoothCodecController
@Inject
constructor(
    @Application private val context: Context,
    private val localBluetoothManager: LocalBluetoothManager?,
) {

    @Volatile private var proxy: BluetoothA2dp? = null
    @Volatile private var bindRequested = false

    private val serviceListener =
        object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) proxy = service as BluetoothA2dp
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile != BluetoothProfile.A2DP) return
                proxy = null
                bindRequested = false
            }
        }

    /** Null until the proxy binds; the tile re-reads it on the next Bluetooth state change. */
    private fun a2dp(): BluetoothA2dp? {
        proxy?.let {
            return it
        }
        if (!bindRequested) {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            bindRequested =
                adapter?.getProfileProxy(context, serviceListener, BluetoothProfile.A2DP) == true
        }
        return null
    }

    val activeDevice: BluetoothDevice?
        get() {
            val a2dp = a2dp() ?: return null
            return runCatching {
                    // BluetoothAdapter.getActiveDevices is @hide, so SettingsLib resolves it.
                    localBluetoothManager?.profileManager?.a2dpProfile?.activeDevice
                        ?: a2dp.connectedDevices.firstOrNull()
                }
                .onFailure { Log.w(TAG, "Cannot read the active A2DP device", it) }
                .getOrNull()
        }

    fun getCodecStatus(device: BluetoothDevice): BluetoothCodecStatus? =
        runCatching { a2dp()?.getCodecStatus(device) }
            .onFailure { Log.w(TAG, "getCodecStatus failed", it) }
            .getOrNull()

    /** @return false when the preference could not be handed to the stack at all. */
    fun setCodecPreference(device: BluetoothDevice, config: BluetoothCodecConfig): Boolean {
        val a2dp = a2dp() ?: return false
        return runCatching { a2dp.setCodecConfigPreference(device, config) }
            .onFailure { Log.e(TAG, "setCodecConfigPreference failed", it) }
            .isSuccess
    }

    fun codecName(codecType: Int): String =
        when (codecType) {
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC -> "SBC"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC -> "AAC"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX -> "aptX"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD -> "aptX HD"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC -> "LDAC"
            CODEC_TYPE_APTX_ADAPTIVE -> "aptX Adaptive"
            CODEC_TYPE_APTX_TWS -> "aptX TWS"
            CODEC_TYPE_LC3 -> "LC3"
            CODEC_TYPE_OPUS -> "Opus"
            else -> context.getString(R.string.blutilities_unknown, codecType)
        }

    /** The tile subtitle: the running codec, with the LDAC bit rate when one applies. */
    fun activeCodecLabel(device: BluetoothDevice): String? {
        val config = getCodecStatus(device)?.codecConfig ?: return null
        val name = codecName(config.codecType)
        if (config.codecType != BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC) return name

        val quality =
            when (config.codecSpecific1) {
                LDAC_QUALITY_BEST -> "990 kbps"
                LDAC_QUALITY_BALANCED -> "660 kbps"
                LDAC_QUALITY_CONNECTION -> "330 kbps"
                LDAC_QUALITY_ADAPTIVE ->
                    context.getString(R.string.blutilities_ldac_adaptive_summary)
                else -> return name
            }
        return context.getString(R.string.blutilities_subtitle_format, name, quality)
    }

    companion object {
        private const val TAG = "BluetoothCodecController"

        const val CODEC_TYPE_APTX_ADAPTIVE = 5
        const val CODEC_TYPE_APTX_TWS = 6
        const val CODEC_TYPE_LC3 = 7
        const val CODEC_TYPE_OPUS = 8

        const val LDAC_QUALITY_BEST = 1000L
        const val LDAC_QUALITY_BALANCED = 1001L
        const val LDAC_QUALITY_CONNECTION = 1002L
        const val LDAC_QUALITY_ADAPTIVE = 1003L
    }
}
