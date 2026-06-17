/*
 * Copyright (C) 2022 StatiXOS
 * Copyright (C) 2024-2025 The LibreMobileOS Foundation
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

package com.android.systemui.qs.tiles;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCharacteristics.Key;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.FlashlightController;

import javax.inject.Inject;

/**
 * Quick settings tile: Flashlight with adjustable brightness
 * Compose-based UI handles the slider gesture, this class handles the logic
 */
public class FlashlightStrengthTile extends FlashlightTile {

    public static final String TILE_SPEC = "flashlight";

    private static final Key<Integer> FLASHLIGHT_MAX_BRIGHTNESS_CHARACTERISTIC =
            CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL;
    private static final Key<Integer> FLASHLIGHT_DEFAULT_BRIGHTNESS_CHARACTERISTIC =
            CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL;

    private static final String FLASHLIGHT_BRIGHTNESS_SETTING = "flashlight_brightness";

    private final CameraManager mCameraManager;
    private final FlashlightController mFlashlightController;
    private final Looper mBgLooper;
    private final Handler mBgHandler;
    private boolean mSupportsSettingFlashLevel;
    private boolean mRegistered = false;
    private int mDefaultLevel = 0;
    private int mMaxLevel = 1;
    private float mCurrentPercent;
    private int mCurrentLevel;

    @Nullable private String mCameraId;

    private final CameraManager.TorchCallback mTorchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchStrengthLevelChanged(@NonNull String cameraId, int newStrengthLevel) {
            if (!cameraId.equals(mCameraId)) {
                return;
            }
            // We don't wanna refresh state for same values as this callback
            // will be invoked from this tile as well.
            if (mCurrentLevel == newStrengthLevel) {
                return;
            }
            // Update current percent/level and refresh the tile.
            mCurrentLevel = newStrengthLevel;
            mCurrentPercent = ((float) mCurrentLevel) / ((float) mMaxLevel);
            writeCurrentSetting();
            refreshState(true);
        }
    };

    @Inject
    public FlashlightStrengthTile(
            QSHost host,
            QsEventLogger qsEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            FlashlightController flashlightController) {
        super(
                host,
                qsEventLogger,
                backgroundLooper,
                mainHandler,
                falsingManager,
                metricsLogger,
                statusBarStateController,
                activityStarter,
                qsLogger,
                flashlightController);
        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        mFlashlightController = flashlightController;
        mBgLooper = backgroundLooper;
        mBgHandler = new Handler(backgroundLooper);
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (!listening) {
            if (mRegistered) {
                mCameraManager.unregisterTorchCallback(mTorchCallback);
                mRegistered = false;
            }
            return;
        }

        try {
            mCameraId = getCameraId();
            CameraCharacteristics characteristics =
                    mCameraManager.getCameraCharacteristics(mCameraId);
            mSupportsSettingFlashLevel =
                    mFlashlightController.isAvailable()
                            && mCameraId != null
                            && characteristics.get(FLASHLIGHT_MAX_BRIGHTNESS_CHARACTERISTIC) > 1;
            mMaxLevel = (int) characteristics.get(FLASHLIGHT_MAX_BRIGHTNESS_CHARACTERISTIC);
            mDefaultLevel = (int) characteristics.get(FLASHLIGHT_DEFAULT_BRIGHTNESS_CHARACTERISTIC);
        } catch (CameraAccessException | NullPointerException e) {
            Log.d("FlashlightStrengthTile", "Setting to non-controllable defaults");
            mCameraId = null;
            mSupportsSettingFlashLevel = false;
            mMaxLevel = 1;
            mDefaultLevel = 0;
        }
        float defaultPercent = ((float) mDefaultLevel) / ((float) mMaxLevel);
        
        // Read current brightness from Settings (set by Compose UI)
        mCurrentPercent =
                Settings.System.getFloatForUser(
                        mContext.getContentResolver(),
                        FLASHLIGHT_BRIGHTNESS_SETTING,
                        defaultPercent,
                        UserHandle.USER_CURRENT);
        
        // Register torch callback on torch strength level supported devices.
        if (mSupportsSettingFlashLevel && !mRegistered) {
            mCameraManager.registerTorchCallback(mTorchCallback, mBgHandler);
            mRegistered = true;
        }
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        // Read the latest brightness value from Settings (updated by Compose slider)
        float defaultPercent = ((float) mDefaultLevel) / ((float) mMaxLevel);
        mCurrentPercent = Settings.System.getFloatForUser(
                mContext.getContentResolver(),
                FLASHLIGHT_BRIGHTNESS_SETTING,
                defaultPercent,
                UserHandle.USER_CURRENT);
        
        boolean newState = !mState.value;
        
        if (mSupportsSettingFlashLevel && newState) {
            try {
                int level = (int) (mCurrentPercent * ((float) mMaxLevel));
                // Not all devices has 100 light level so in that case, it will attain level 0
                // before 0%. We don't want flashlight is getting off other than 0%.
                // Make sure level won't go below 1.
                mCurrentLevel = Math.max(level, 1);
                
                float percent = mCurrentPercent * 100f;
                if (percent == 0f) {
                    mFlashlightController.setFlashlight(false);
                    newState = false;
                } else {
                    mCameraManager.turnOnTorchWithStrengthLevel(mCameraId, mCurrentLevel);
                }
            } catch (CameraAccessException e) {
                Log.e("FlashlightStrengthTile", "Failed to set torch strength level", e);
            }
        } else {
            mFlashlightController.setFlashlight(newState);
        }
        refreshState(newState);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        super.handleUpdateState(state, arg);
        
        // Always read the latest value from Settings for display
        if (mSupportsSettingFlashLevel) {
            float defaultPercent = ((float) mDefaultLevel) / ((float) mMaxLevel);
            mCurrentPercent = Settings.System.getFloatForUser(
                    mContext.getContentResolver(),
                    FLASHLIGHT_BRIGHTNESS_SETTING,
                    defaultPercent,
                    UserHandle.USER_CURRENT);
            
            String label = mHost.getContext().getString(R.string.quick_settings_flashlight_label);
            if (state.value) {
                label = String.format(
                        "%s - %s%%",
                        mHost.getContext().getString(R.string.quick_settings_flashlight_label),
                        Math.round(mCurrentPercent * 100f));
            }
            state.label = label;
        }
    }

    private String getCameraId() throws CameraAccessException {
        String[] ids = mCameraManager.getCameraIdList();
        for (String id : ids) {
            CameraCharacteristics c = mCameraManager.getCameraCharacteristics(id);
            Boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer lensFacing = c.get(CameraCharacteristics.LENS_FACING);
            if (flashAvailable != null
                    && flashAvailable
                    && lensFacing != null
                    && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return null;
    }

    /**
     * Sets the flashlight brightness from the Compose slider. Clamps, persists, and (if the torch
     * is on) updates the torch strength - all on the tile's background thread, so the UI thread
     * never performs Settings/camera IPC and all level state stays confined to one thread.
     */
    public void setBrightnessPercent(float percent) {
        final float p = Math.max(0f, Math.min(1f, percent));
        mBgHandler.post(() -> {
            if (!mSupportsSettingFlashLevel || mCameraId == null) {
                return;
            }
            int level = Math.max((int) (p * mMaxLevel), 1);
            if (p == mCurrentPercent && level == mCurrentLevel) {
                // No effective change; avoid redundant Settings/camera writes per frame.
                return;
            }
            mCurrentPercent = p;
            mCurrentLevel = level;
            writeCurrentSetting();
            if (mState.value) {
                try {
                    mCameraManager.turnOnTorchWithStrengthLevel(mCameraId, level);
                } catch (CameraAccessException e) {
                    Log.e("FlashlightStrengthTile", "Failed to set torch strength level", e);
                }
            }
            refreshState();
        });
    }

    private void writeCurrentSetting() {
        Settings.System.putFloatForUser(
                mContext.getContentResolver(),
                FLASHLIGHT_BRIGHTNESS_SETTING,
                mCurrentPercent,
                UserHandle.USER_CURRENT);
    }
    
    public boolean isSlideable() {
        return mSupportsSettingFlashLevel;
    }
}
