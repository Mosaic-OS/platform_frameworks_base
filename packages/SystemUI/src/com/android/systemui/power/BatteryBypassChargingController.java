/*
 * Copyright (C) 2026 The MosaicOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.power;

import static android.ext.power.BatteryBypassCharging.*;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.ext.power.BatteryBypassCharging;
import android.ext.power.BatteryChargeLimit;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;

import java.util.Objects;

import javax.inject.Inject;

import vendor.google.google_battery.IGoogleBattery;

@SysUISingleton
public final class BatteryBypassChargingController implements CoreStartable {
    private static final String TAG = "BatteryBypassCharging";
    // Local selectors 12 and 5 select /sys/class/power_supply/battery/charge_to_limit.
    private static final int LOCAL_FEATURE = 12;
    private static final int LOCAL_PROPERTY = 5;

    private final Context mContext;
    private Handler mHandler;
    private IGoogleBattery mService;
    private int mBoot;
    private int mAvailability = UNKNOWN;
    private int mHeldLevel;
    private boolean mPlugged;
    private boolean mBootCompleted;
    private boolean mEarlyLimitApplied;
    private boolean mLoggedFailure;
    private boolean mBlocked;
    private boolean mMayHaveTarget;
    private boolean mPolicyAvailable;
    private String mLastRequest;
    private int[] mPreviousState;

    @Inject
    public BatteryBypassChargingController(Context context) {
        mContext = context;
    }

    @Override
    public void start() {
        if (!mContext.getUser().isSystem() || !BatteryChargeLimit.isGoogleDevice()
                || mHandler != null) {
            return;
        }
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        mHandler.post(() -> runSafely(this::initialize));
    }

    public void handleLockedBootCompleted() {
        start();
        if (mHandler != null) {
            mHandler.post(() -> runSafely(() -> {
                if (mBootCompleted || mEarlyLimitApplied || mBlocked
                        || mPreviousState[0] != UNKNOWN
                        || !BatteryChargeLimit.isChargeLimitEnabled(mContext)) {
                    return;
                }
                try {
                    IBinder binder = ServiceManager.checkService(
                            IGoogleBattery.DESCRIPTOR + "/default");
                    if (binder == null) {
                        fail(SERVICE_MISSING,
                                new IllegalStateException("Battery HAL is missing"), false);
                        return;
                    }
                    mService = IGoogleBattery.Stub.asInterface(binder);
                    mAvailability = APPLYING;
                    publish();
                    mService.setChargingPolicy(IGoogleBattery.BatteryChargingPolicy.LONGLIFE);
                    mEarlyLimitApplied = true;
                    mAvailability = UNKNOWN;
                    publish();
                } catch (RemoteException | RuntimeException e) {
                    fail(HAL_ERROR, e, false);
                }
            }));
        }
    }

    public void handleBootCompleted() {
        start();
        if (mHandler != null) {
            mHandler.post(() -> runSafely(this::completeBoot));
        }
    }

    private void initialize() {
        mBoot = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.BOOT_COUNT, -1);
        mPreviousState = getState(mContext);
        Intent battery = batteryIntent();
        mPlugged = battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        mLastRequest = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.BATTERY_CHARGING_MODE_REQUEST);
        mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.BATTERY_CHARGING_MODE_REQUEST), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        runSafely(() -> handleRequest());
                    }
                });
        IntentFilter filter = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                runSafely(() -> {
                    if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {
                        mPlugged = false;
                        mHeldLevel = 0;
                        publish();
                    } else if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())
                            && !mPlugged) {
                        mPlugged = true;
                        if (mBootCompleted && isEnabled(mContext)
                                && mAvailability == AVAILABLE && !mBlocked) {
                            applyMode(MODE_BYPASS);
                        } else {
                            publish();
                        }
                    }
                }, !Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction()));
            }
        }, filter, null, mHandler, Context.RECEIVER_NOT_EXPORTED);

        if (SystemProperties.getBoolean("sys.boot_completed", false)) {
            completeBoot();
        }
    }

    private void completeBoot() {
        if (mBootCompleted || mBlocked) {
            return;
        }
        mBootCompleted = true;
        int previousStatus = mPreviousState[0];
        boolean interrupted = previousStatus == APPLYING
                || (previousStatus == AVAILABLE && isEnabled(mContext));
        if (previousStatus != UNKNOWN && previousStatus != AVAILABLE && !interrupted) {
            mAvailability = previousStatus;
            mBlocked = true;
            BatteryBypassCharging.getSetting().put(mContext, false);
            publish();
            return;
        }
        // Persist interruption before recovery so another process restart cannot repeat HAL writes
        if (interrupted) {
            mAvailability = RECOVERY_ERROR;
            publish();
        }
        try {
            IBinder binder = ServiceManager.checkService(IGoogleBattery.DESCRIPTOR + "/default");
            if (binder == null) {
                fail(SERVICE_MISSING, new IllegalStateException("Battery HAL is missing"), false);
                return;
            }
            mService = IGoogleBattery.Stub.asInterface(binder);
            mPolicyAvailable = true;
            binder.linkToDeath(() -> mHandler.post(() -> runSafely(() -> {
                mPolicyAvailable = false;
                if (mBlocked) {
                    publish();
                } else {
                    fail(mMayHaveTarget ? RECOVERY_ERROR : HAL_ERROR,
                            new IllegalStateException("Battery HAL died"), false);
                }
            })), 0);
            if (interrupted) {
                fail(RECOVERY_ERROR, new IllegalStateException("Charging owner was restarted"), true);
                return;
            }
            int target = mService.getProperty(LOCAL_FEATURE, LOCAL_PROPERTY);
            if (target < 0 || target > 99) {
                fail(READBACK_ERROR, new IllegalStateException("Invalid bypass target"), false);
                applySavedMode();
                return;
            }
            mAvailability = AVAILABLE;
            mMayHaveTarget = target != 0;
            publish();
            applySavedMode();
        } catch (RemoteException | RuntimeException e) {
            fail(HAL_ERROR, e, mMayHaveTarget);
            applySavedMode();
        }
    }

    private void handleRequest() {
        String request = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.BATTERY_CHARGING_MODE_REQUEST);
        if (Objects.equals(request, mLastRequest)) {
            return;
        }
        mLastRequest = request;
        if (!mBootCompleted || request == null) {
            return;
        }
        String[] fields = request.split(":");
        if (fields.length != 2) {
            return;
        }
        int mode;
        try {
            mode = Integer.parseInt(fields[0]);
        } catch (NumberFormatException e) {
            return;
        }
        if (mode < MODE_OFF || mode > MODE_BYPASS || mode == getMode(mContext)) {
            return;
        }
        if (mBlocked) {
            if (mode != MODE_BYPASS && mPolicyAvailable) {
                applyPolicyOnly(mode);
            }
        } else if (mode != MODE_BYPASS || mAvailability == AVAILABLE) {
            applyMode(mode);
        }
    }

    private void applySavedMode() {
        if (mService == null) {
            return;
        }
        if (mBlocked) {
            if (mPolicyAvailable && !mEarlyLimitApplied
                    && BatteryChargeLimit.isChargeLimitEnabled(mContext)) {
                applyPolicyOnly(MODE_LIMIT);
            }
            return;
        }
        if (isEnabled(mContext)) {
            if (mPlugged && mHeldLevel == 0 && mAvailability == AVAILABLE) {
                applyMode(MODE_BYPASS);
            }
        } else if (BatteryChargeLimit.isChargeLimitEnabled(mContext)) {
            if (!mEarlyLimitApplied || mMayHaveTarget) {
                applyMode(MODE_LIMIT);
            }
        } else if (mMayHaveTarget) {
            applyMode(MODE_OFF);
        }
    }

    private void applyPolicyOnly(int mode) {
        int previousAvailability = mAvailability;
        mAvailability = APPLYING;
        publish();
        try {
            mService.setChargingPolicy(mode == MODE_LIMIT
                    ? IGoogleBattery.BatteryChargingPolicy.LONGLIFE
                    : IGoogleBattery.BatteryChargingPolicy.DEFAULT);
            if (!BatteryChargeLimit.getSetting().put(mContext, mode == MODE_LIMIT)) {
                throw new IllegalStateException("Charging preference could not be saved");
            }
            mAvailability = previousAvailability;
        } catch (RemoteException | RuntimeException e) {
            mPolicyAvailable = false;
            mAvailability = HAL_ERROR;
            if (!mLoggedFailure) {
                mLoggedFailure = true;
                Log.e(TAG, "Charging policy unavailable until reboot", e);
            }
        }
        publish();
    }

    private void applyMode(int mode) {
        if (mService == null || mBlocked) {
            return;
        }
        boolean previousLimit = BatteryChargeLimit.isChargeLimitEnabled(mContext);
        boolean needsClear = mMayHaveTarget || isEnabled(mContext);
        int previousAvailability = mAvailability;
        int target = 0;
        if (mode == MODE_BYPASS) {
            Intent battery = batteryIntent();
            int level = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
            if (level < 0 || scale <= 0 || level > scale) {
                fail(LEVEL_UNKNOWN, new IllegalStateException("Battery level is unavailable"),
                        needsClear);
                return;
            }
            mPlugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            target = Math.max(1, Math.min(99, (int) (100L * level / scale)));
        }
        mAvailability = APPLYING;
        mHeldLevel = 0;
        publish();
        try {
            if (mode == MODE_BYPASS) {
                mService.setChargingPolicy(IGoogleBattery.BatteryChargingPolicy.DEFAULT);
                mMayHaveTarget = true;
                mService.setProperty(LOCAL_FEATURE, LOCAL_PROPERTY, target);
                if (mService.getProperty(LOCAL_FEATURE, LOCAL_PROPERTY) != target) {
                    fail(READBACK_ERROR, new IllegalStateException("Bypass readback mismatch"),
                            true);
                    return;
                }
            } else {
                if (needsClear) {
                    mService.setProperty(LOCAL_FEATURE, LOCAL_PROPERTY, 0);
                    if (mService.getProperty(LOCAL_FEATURE, LOCAL_PROPERTY) != 0) {
                        fail(READBACK_ERROR, new IllegalStateException("Bypass disable mismatch"),
                                true);
                        return;
                    }
                    mMayHaveTarget = false;
                }
                mService.setChargingPolicy(mode == MODE_LIMIT
                        ? IGoogleBattery.BatteryChargingPolicy.LONGLIFE
                        : IGoogleBattery.BatteryChargingPolicy.DEFAULT);
            }
            if ((mode != MODE_BYPASS
                    && !BatteryChargeLimit.getSetting().put(mContext, mode == MODE_LIMIT))
                    || !BatteryBypassCharging.getSetting().put(mContext, mode == MODE_BYPASS)) {
                throw new IllegalStateException("Charging preference could not be saved");
            }
            mHeldLevel = mode == MODE_BYPASS && mPlugged ? target : 0;
            mAvailability = previousAvailability;
            publish();
        } catch (RemoteException | RuntimeException e) {
            if (mode != MODE_BYPASS) {
                BatteryChargeLimit.getSetting().put(mContext, previousLimit);
            }
            fail(HAL_ERROR, e, true);
        }
    }

    private void fail(int reason, Exception error, boolean rollback) {
        if (mBlocked) {
            return;
        }
        mBlocked = true;
        mPolicyAvailable = mPolicyAvailable && !mMayHaveTarget && !isEnabled(mContext)
                && mService != null && mService.asBinder().isBinderAlive();
        mAvailability = reason;
        mHeldLevel = 0;
        try {
            if (!BatteryBypassCharging.getSetting().put(mContext, false)) {
                throw new IllegalStateException("Bypass preference could not be cleared");
            }
            publish();
        } catch (RuntimeException stateError) {
            error.addSuppressed(stateError);
        }
        if (rollback && mService != null) {
            try {
                mService.setProperty(LOCAL_FEATURE, LOCAL_PROPERTY, 0);
                if (mService.getProperty(LOCAL_FEATURE, LOCAL_PROPERTY) != 0) {
                    throw new IllegalStateException("Bypass recovery readback mismatch");
                }
                mService.setChargingPolicy(BatteryChargeLimit.isChargeLimitEnabled(mContext)
                        ? IGoogleBattery.BatteryChargingPolicy.LONGLIFE
                        : IGoogleBattery.BatteryChargingPolicy.DEFAULT);
                mMayHaveTarget = false;
                mPolicyAvailable = true;
            } catch (RemoteException | RuntimeException recoveryError) {
                error.addSuppressed(recoveryError);
                mPolicyAvailable = false;
                mAvailability = RECOVERY_ERROR;
            }
        }
        try {
            publish();
        } catch (RuntimeException stateError) {
            error.addSuppressed(stateError);
        }
        if (!mLoggedFailure) {
            mLoggedFailure = true;
            Log.e(TAG, "Charging control unavailable until reboot (reason "
                    + mAvailability + ")", error);
        }
    }

    private Intent batteryIntent() {
        return mContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private void publish() {
        if (!Settings.Global.putString(mContext.getContentResolver(),
                Settings.Global.BATTERY_BYPASS_STATE,
                mBoot + ":" + mAvailability + ":" + mHeldLevel + ":" + (mPlugged ? 1 : 0)
                        + ":" + (mPolicyAvailable ? 1 : 0))) {
            throw new IllegalStateException("Charging state could not be saved");
        }
    }

    private void runSafely(Runnable action) {
        runSafely(action, true);
    }

    private void runSafely(Runnable action, boolean recoverTarget) {
        try {
            action.run();
        } catch (RuntimeException e) {
            if (!mBlocked) {
                fail(RECOVERY_ERROR, e, recoverTarget && mMayHaveTarget);
            } else if (!mLoggedFailure) {
                mLoggedFailure = true;
                Log.e(TAG, "Charging control stopped", e);
            }
        }
    }
}
