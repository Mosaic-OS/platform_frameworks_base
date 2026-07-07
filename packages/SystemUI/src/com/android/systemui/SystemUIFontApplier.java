package com.android.systemui;

import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Slog;

import org.json.JSONObject;

import java.util.List;

public class SystemUIFontApplier extends ContentObserver {
    private static final String TAG = "SystemUIFontApplier";
    public static final String KEY_FONT_PACKAGE = "systemui_font_package_request";
    public static final String KEY_FONT_RESTART  = "systemui_font_restart_request";
    public static final String VALUE_NONE = "none";
    private static final String OVERLAY_CATEGORY_FONT = "android.theme.customization.font";

    private final Context mContext;

    public SystemUIFontApplier(Context context, Handler handler) {
        super(handler);
        mContext = context;
    }

    private static boolean sRegistered;

    public static void register(Context context, Handler handler) {
        if (sRegistered) {
            return;
        }
        sRegistered = true;
        SystemUIFontApplier observer = new SystemUIFontApplier(context, handler);
        // Observe the font *package* key (the value that actually matters) so a change applies
        // directly, plus the restart key so an explicit re-apply request still triggers.
        context.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(KEY_FONT_PACKAGE), false, observer, -1);
        context.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(KEY_FONT_RESTART), false, observer, -1);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
    
        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
    
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userId == -1 || userId == UserHandle.USER_SYSTEM) return;
    
                String ownerFont = Settings.Global.getString(
                        ctx.getContentResolver(), KEY_FONT_PACKAGE);
                if (ownerFont == null || VALUE_NONE.equals(ownerFont)) return;
    
                String userCurrentFont = getCurrentFontOverlay(ctx, null, userId);
                if (ownerFont.equals(userCurrentFont)) {
                    Slog.d(TAG, "User " + userId + " already has correct font, skipping");
                    return;
                }
    
                Slog.d(TAG, "Action=" + action + ", user=" + userId
                        + ", applying owner font: " + ownerFont);
    
                long delayMs = Intent.ACTION_USER_SWITCHED.equals(action) ? 800L : 0L;
    
                new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        IBinder b = ServiceManager.getService("overlay");
                        IOverlayManager om = IOverlayManager.Stub.asInterface(b);
                        if (om != null) {
                            om.setEnabledExclusiveInCategory(ownerFont, userId);
                            Slog.d(TAG, "Applied font " + ownerFont + " to user " + userId);
                        }
                    } catch (Exception e) {
                        Slog.e(TAG, "Failed to apply font to user " + userId, e);
                    }
                }, delayMs);
            }
        }, UserHandle.SYSTEM, filter, null, handler);
    
        Slog.d(TAG, "SystemUIFontApplier registered");
    }

    @Override
    public void onChange(boolean selfChange) {
        final String requestedFont = Settings.Global.getString(
                mContext.getContentResolver(), KEY_FONT_PACKAGE);
        Slog.d(TAG, "Font change request, font=" + requestedFont);
        // The overlay application loop below does synchronous cross-user binder IPC; run it off
        // the (main) observer thread so it cannot jank/ANR the UI before the restart.
        new Thread(() -> applyFontToAllUsersAndRestart(mContext, requestedFont),
                "SystemUIFontApplier").start();
    }

    private static void applyFontToAllUsersAndRestart(Context context, String fontPackage) {
        try {
            IBinder b = ServiceManager.getService("overlay");
            IOverlayManager om = IOverlayManager.Stub.asInterface(b);

            if (om == null) {
                Slog.e(TAG, "OverlayManager not found");
                scheduleRestart();
                return;
            }

            UserManager um = context.getSystemService(UserManager.class);
            List<UserInfo> users = um.getUsers(/* excludeDying= */ true);

            for (UserInfo user : users) {
                try {
                    if (fontPackage != null && !VALUE_NONE.equals(fontPackage)) {
                        om.setEnabledExclusiveInCategory(fontPackage, user.id);
                        Slog.d(TAG, "Applied font " + fontPackage + " to user " + user.id);
                    } else {
                        String current = getCurrentFontOverlay(context, om, user.id);
                        if (current != null) {
                            om.setEnabled(current, false, user.id);
                            Slog.d(TAG, "Disabled font overlay for user " + user.id);
                        }
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Failed to apply font to user " + user.id, e);
                }
            }

        } catch (Exception e) {
            Slog.e(TAG, "Failed during font application", e);
        }

        scheduleRestart();
    }

    private static String getCurrentFontOverlay(Context context, IOverlayManager om, int userId) {
        try {
            String value = Settings.Secure.getStringForUser(
                    context.getContentResolver(),
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                    userId
            );
            if (value == null) return null;
            JSONObject json = new JSONObject(value);
            return json.optString(OVERLAY_CATEGORY_FONT, null);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to read font for user " + userId, e);
            return null;
        }
    }

    private static void scheduleRestart() {
        Slog.d(TAG, "Scheduling SystemUI restart...");
        new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                android.view.WindowManagerGlobal.getWindowManagerService()
                        .removeRotationWatcher(null);
            } catch (Exception ignored) {}

            try {
                android.view.SurfaceControl.Transaction t =
                        new android.view.SurfaceControl.Transaction();
                t.apply();
            } catch (Exception ignored) {}

            Slog.d(TAG, "Restarting SystemUI now.");
            Process.killProcess(Process.myPid());
        }, 300);
    }
}