/*
 * Copyright (C) 2026 The MosaicOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.ext.power;

import android.content.Context;
import android.ext.settings.BoolSetting;
import android.ext.settings.Setting;
import android.provider.Settings;

import java.util.UUID;

/** @hide */
public final class BatteryBypassCharging {
    public static final int MODE_OFF = 0;
    public static final int MODE_LIMIT = 1;
    public static final int MODE_BYPASS = 2;

    public static final int UNKNOWN = 0;
    public static final int AVAILABLE = 1;
    public static final int SERVICE_MISSING = 2;
    public static final int HAL_ERROR = 3;
    public static final int READBACK_ERROR = 4;
    public static final int LEVEL_UNKNOWN = 5;
    public static final int RECOVERY_ERROR = 6;
    public static final int APPLYING = 7;

    private static final BoolSetting SETTING = new BoolSetting(Setting.Scope.GLOBAL,
            Settings.Global.BATTERY_BYPASS_CHARGING, false);

    private BatteryBypassCharging() {}

    public static BoolSetting getSetting() {
        return SETTING;
    }

    public static boolean isEnabled(Context context) {
        return BatteryChargeLimit.isGoogleDevice() && SETTING.get(context);
    }

    public static int getMode(Context context) {
        if (isEnabled(context)) {
            return MODE_BYPASS;
        }
        return BatteryChargeLimit.isChargeLimitEnabled(context) ? MODE_LIMIT : MODE_OFF;
    }

    public static boolean requestMode(Context context, int mode) {
        if (!context.getUser().isSystem() || !BatteryChargeLimit.isGoogleDevice()
                || mode < MODE_OFF || mode > MODE_BYPASS
                || (mode == MODE_BYPASS ? !isAvailable(context) : !isPolicyAvailable(context))) {
            return false;
        }
        return Settings.Global.putString(context.getContentResolver(),
                Settings.Global.BATTERY_CHARGING_MODE_REQUEST, mode + ":" + UUID.randomUUID());
    }

    public static int[] getState(Context context) {
        int[] unknown = {UNKNOWN, 0, 0, 0};
        if (!BatteryChargeLimit.isGoogleDevice()) {
            return unknown;
        }
        String value = Settings.Global.getString(context.getContentResolver(),
                Settings.Global.BATTERY_BYPASS_STATE);
        if (value == null) {
            return unknown;
        }
        String[] fields = value.split(":");
        if (fields.length != 5) {
            return unknown;
        }
        try {
            int boot = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
            if (boot < 0 || Integer.parseInt(fields[0]) != boot) {
                return unknown;
            }
            return new int[] {Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]), Integer.parseInt(fields[4])};
        } catch (NumberFormatException e) {
            return unknown;
        }
    }

    public static int getAvailability(Context context) {
        return getState(context)[0];
    }

    public static boolean isAvailable(Context context) {
        return getAvailability(context) == AVAILABLE;
    }

    public static boolean isPolicyAvailable(Context context) {
        int[] state = getState(context);
        return state[3] != 0 && state[0] != APPLYING;
    }

    public static boolean isPluggedIn(Context context) {
        return getState(context)[2] != 0;
    }

    public static int getHeldLevel(Context context) {
        int[] state = getState(context);
        return isEnabled(context) && state[0] == AVAILABLE && state[2] != 0
                && state[1] >= 1 && state[1] <= 99 ? state[1] : 0;
    }
}
