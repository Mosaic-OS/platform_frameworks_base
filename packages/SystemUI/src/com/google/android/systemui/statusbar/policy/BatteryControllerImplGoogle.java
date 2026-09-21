package com.google.android.systemui.statusbar.policy;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.ext.power.BatteryBypassCharging;
import android.ext.power.BatteryChargeLimit;
import android.provider.Settings;
import android.os.Handler;
import android.os.PowerManager;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.power.EnhancedEstimates;
import com.android.systemui.statusbar.policy.BatteryControllerImpl;
import com.android.systemui.statusbar.policy.BatteryControllerLogger;

import static android.os.BatteryManager.CHARGING_POLICY_ADAPTIVE_LONGLIFE;

public class BatteryControllerImplGoogle extends BatteryControllerImpl {
    private final Handler mBypassHandler;

    public BatteryControllerImplGoogle(Context context,
                                       EnhancedEstimates enhancedEstimates,
                                       PowerManager powerManager,
                                       BroadcastDispatcher broadcastDispatcher,
                                       DemoModeController demoModeController,
                                       DumpManager dumpManager,
                                       BatteryControllerLogger logger,
                                       Handler mainHandler,
                                       Handler bgHandler) {
        super(context, enhancedEstimates, powerManager, broadcastDispatcher, demoModeController,
                dumpManager, logger, mainHandler, bgHandler);
        mBypassHandler = mainHandler;
    }

    @Override
    public void init() {
        super.init();
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.BATTERY_BYPASS_STATE), false,
                new ContentObserver(mBypassHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        Intent battery = mContext.registerReceiver(null,
                                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                        if (battery != null) {
                            onReceive(mContext, battery);
                        }
                    }
                });
    }

    @Override
    protected boolean isBatteryDefenderMode(int chargingStatus) {
        if (mPluggedIn && BatteryBypassCharging.getHeldLevel(mContext) > 0) {
            return true;
        }
        if (chargingStatus != CHARGING_POLICY_ADAPTIVE_LONGLIFE) {
            return false;
        }

        boolean isChargeLimitEnabled = BatteryChargeLimit.isChargeLimitEnabled(mContext);
        if (isChargeLimitEnabled) {
            return mLevel >= BatteryChargeLimit.CHARGE_LEVEL;
        }
        return false;
    }
}
