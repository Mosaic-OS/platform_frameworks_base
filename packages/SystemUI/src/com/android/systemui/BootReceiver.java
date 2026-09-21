package com.android.systemui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.android.systemui.power.BatteryBypassChargingController;

import javax.inject.Inject;

public class BootReceiver extends BroadcastReceiver {
    private final BatteryBypassChargingController mChargingController;

    @Inject
    public BootReceiver(BatteryBypassChargingController chargingController) {
        mChargingController = chargingController;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!context.getUser().isSystem()) {
            return;
        }
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
            mChargingController.handleLockedBootCompleted();
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            mChargingController.handleBootCompleted();
        }
    }
}
