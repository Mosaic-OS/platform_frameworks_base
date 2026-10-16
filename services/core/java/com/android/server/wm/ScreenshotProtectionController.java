/*
 * Copyright (C) 2026 The MosaicOS Project
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

package com.android.server.wm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;
import android.view.WindowManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ScreenshotProtectionUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

final class ScreenshotProtectionController {
    private static final String TAG = "ScreenshotProtection";
    private static final String SETTING = Settings.Secure.SCREENSHOT_PROTECTION_ALLOWED_PACKAGES;
    private static final String IDENTITIES = Settings.Secure.SCREENSHOT_PROTECTION_PACKAGE_IDENTITIES;
    private static final String BLOCKED = Settings.Secure.SCREENSHOT_PROTECTION_BLOCKED_PACKAGES;
    private static final String DETECTED = Settings.Secure.SCREENSHOT_PROTECTION_DETECTED_PACKAGES;
    private static final long RETRY_DELAY_MS = 5000;
    private static final long DETECTION_DELAY_MS = 1000;

    private final WindowManagerService mService;
    private final Context mContext;
    private final Handler mHandler;
    @GuardedBy("mService.mGlobalLock")
    private final Map<Integer, UserState> mUsers = new HashMap<>();
    @GuardedBy("mService.mGlobalLock")
    private final Map<Integer, Set<String>> mStaleSnapshots = new HashMap<>();
    @GuardedBy("mService.mGlobalLock")
    private boolean mDetectionScheduled;
    private final Map<Integer, Set<String>> mPendingRemovals = new HashMap<>();
    private final Map<Integer, Runnable> mRetries = new HashMap<>();
    private final Set<Integer> mRemovedUsers = new HashSet<>();
    private volatile boolean mStarted;
    private volatile boolean mDisabled;
    private final Runnable mPersistDetections = this::persistDetections;
    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override
        public void onPackageRemoved(String packageName, int uid) {
            revokePackage(packageName, getChangingUserId());
        }

        @Override
        public void onPackageAdded(String packageName, int uid) {
            revokePackage(packageName, getChangingUserId());
        }
    };

    // Every field is guarded by the WM lock, including observations awaiting handler validation
    private static final class UserState {
        Map<String, Integer> allowed = Collections.emptyMap();
        Set<String> requestedAllowed = Collections.emptySet();
        Set<String> identityMatches = Collections.emptySet();
        Set<String> blocked = Collections.emptySet();
        final Set<String> detected = new HashSet<>();
        final Map<String, Integer> pendingDetected = new HashMap<>();
        int detectionSize;
        boolean loaded;
    }

    ScreenshotProtectionController(WindowManagerService service) {
        mService = service;
        mContext = service.mContext;
        mHandler = BackgroundThread.getHandler();
    }

    @GuardedBy("mService.mGlobalLock")
    boolean isAllowedLocked(WindowState window) {
        final String packageName = window.getOwningPackage();
        if (!ScreenshotProtectionUtils.canAllow(packageName) || window.getOwningUid() < 0) {
            return false;
        }
        final UserState user = mUsers.get(window.mScreenshotProtectionUserId);
        if (user == null || user.blocked.contains(packageName)) {
            return false;
        }
        final Integer uid = user.allowed.get(packageName);
        return uid != null && uid == window.getOwningUid();
    }

    @GuardedBy("mService.mGlobalLock")
    boolean isBlockedLocked(WindowState window) {
        final String packageName = window.getOwningPackage();
        if (!ScreenshotProtectionUtils.canAllow(packageName) || window.getOwningUid() < 0) {
            return false;
        }
        final UserState user = mUsers.get(window.mScreenshotProtectionUserId);
        return user != null && user.blocked.contains(packageName);
    }

    @GuardedBy("mService.mGlobalLock")
    void recordSecureWindowLocked(WindowState window) {
        if (mDisabled || (window.mAttrs.flags & WindowManager.LayoutParams.FLAG_SECURE) == 0
                || window.getOwningUid() < 0 || window.getOwningPackage() == null) {
            return;
        }
        final String packageName = window.getOwningPackage();
        UserState user = mUsers.get(window.mScreenshotProtectionUserId);
        if (user == null) {
            user = new UserState();
            mUsers.put(window.mScreenshotProtectionUserId, user);
        }
        if (user.detected.contains(packageName) || user.pendingDetected.containsKey(packageName)) {
            return;
        }
        if (!ScreenshotProtectionUtils.isPackageName(packageName)
                || user.detectionSize + packageName.length() + 1
                        > ScreenshotProtectionUtils.MAX_PACKAGES_LENGTH + 1) {
            return;
        }
        user.pendingDetected.put(packageName, window.getOwningUid());
        user.detectionSize += packageName.length() + 1;
        if (mStarted && !mDetectionScheduled) {
            try {
                mDetectionScheduled = mHandler.postDelayed(mPersistDetections, DETECTION_DELAY_MS);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Unable to schedule screenshot protection detection");
            }
        }
    }

    private void persistDetections() {
        final ArrayList<Integer> users = new ArrayList<>();
        synchronized (mService.mGlobalLock) {
            mDetectionScheduled = false;
            for (Map.Entry<Integer, UserState> entry : mUsers.entrySet()) {
                if (!entry.getValue().pendingDetected.isEmpty()) {
                    users.add(entry.getKey());
                }
            }
        }
        for (int userId : users) {
            reloadUser(userId);
        }
    }

    void start() {
        mHandler.post(() -> {
            if (mStarted || mDisabled) {
                return;
            }
            try {
                mPackageMonitor.register(mContext, UserHandle.ALL, mHandler);
                final IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_USER_STARTED);
                filter.addAction(Intent.ACTION_USER_UNLOCKED);
                filter.addAction(Intent.ACTION_USER_REMOVED);
                mContext.registerReceiverAsUser(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                                UserHandle.USER_NULL);
                        if (userId < 0) {
                            return;
                        }
                        if (Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
                            cancelRetry(userId);
                            mPendingRemovals.remove(userId);
                            mRemovedUsers.add(userId);
                            publish(userId, Collections.emptyMap(), Collections.emptySet(),
                                    Collections.emptySet(), Collections.emptySet(), false);
                            synchronized (mService.mGlobalLock) {
                                mUsers.remove(userId);
                            }
                        } else {
                            mRemovedUsers.remove(userId);
                            reloadUser(userId);
                        }
                    }
                }, UserHandle.ALL, filter, null, mHandler);
                final ContentObserver observer = new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange, Collection<Uri> uris, int flags, int userId) {
                        if (userId >= 0) {
                            reloadUser(userId);
                        } else {
                            reloadAllUsers();
                        }
                    }
                };
                for (String key : new String[] {SETTING, IDENTITIES, BLOCKED, DETECTED}) {
                    mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(key),
                            false, observer, UserHandle.USER_ALL);
                }
                mStarted = true;
                reloadAllUsers();
            } catch (RuntimeException e) {
                disable();
                Slog.w(TAG, "Unable to start screenshot protection controls");
            }
        });
    }

    private void reloadAllUsers() {
        if (!mStarted) {
            return;
        }
        try {
            for (UserInfo user : mContext.getSystemService(UserManager.class).getUsers()) {
                reloadUser(user.id);
            }
        } catch (RuntimeException e) {
            disable();
            Slog.w(TAG, "Unable to load screenshot protection users");
        }
    }

    private void revokePackage(String packageName, int userId) {
        if (!mStarted || userId < 0 || packageName == null) {
            return;
        }
        mPendingRemovals.computeIfAbsent(userId, id -> new HashSet<>()).add(packageName);
        synchronized (mService.mGlobalLock) {
            final UserState user = mUsers.get(userId);
            if (user != null) {
                forgetDetectionLocked(user, packageName);
            }
        }
        failUser(userId, Collections.emptySet());
        reloadUser(userId);
    }

    private void reloadUser(int userId) {
        if (!mStarted || userId < 0 || mRemovedUsers.contains(userId)) {
            return;
        }
        Set<String> blocked = Collections.emptySet();
        try {
            blocked = ScreenshotProtectionUtils.parse(read(BLOCKED, userId));
            final Set<String> packages = ScreenshotProtectionUtils.parse(read(SETTING, userId));
            final Map<String, String> identities = ScreenshotProtectionUtils.parseIdentities(read(IDENTITIES, userId));
            final String detectedValue = read(DETECTED, userId);
            final Set<String> storedDetected = ScreenshotProtectionUtils.parse(detectedValue);
            final Set<String> knownDetected;
            final Map<String, Integer> pending;
            synchronized (mService.mGlobalLock) {
                final UserState user = mUsers.computeIfAbsent(userId, id -> new UserState());
                knownDetected = new HashSet<>(user.detected);
                pending = new HashMap<>(user.pendingDetected);
            }
            final Set<String> removed = mPendingRemovals.computeIfAbsent(userId, id -> new HashSet<>());
            final Set<String> candidates = new HashSet<>(packages);
            candidates.addAll(blocked);
            candidates.addAll(identities.keySet());
            candidates.addAll(storedDetected);
            candidates.addAll(knownDetected);
            candidates.addAll(pending.keySet());
            final Map<String, Integer> allowed = new HashMap<>();
            final Set<String> detected = new HashSet<>();
            final Set<String> identityMatches = new HashSet<>();
            for (String packageName : candidates) {
                if (removed.contains(packageName)) {
                    continue;
                }
                try {
                    final PackageInfo info = mContext.getPackageManager().getPackageInfoAsUser(
                            packageName, PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId);
                    if (info.applicationInfo == null
                            || UserHandle.getUserId(info.applicationInfo.uid) != userId) {
                        throw new IllegalStateException("Package ownership is unavailable");
                    }
                    final int uid = info.applicationInfo.uid;
                    if (packages.contains(packageName) && ScreenshotProtectionUtils.identity(uid, info.firstInstallTime)
                            .equals(identities.get(packageName))) {
                        identityMatches.add(packageName);
                        if (ScreenshotProtectionUtils.canAllow(packageName)) {
                            allowed.put(packageName, uid);
                        }
                    }
                    final Integer observedUid = pending.get(packageName);
                    if (knownDetected.contains(packageName) || storedDetected.contains(packageName)
                            || (observedUid != null && observedUid == uid)) {
                        detected.add(packageName);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    removed.add(packageName);
                }
            }
            if (packages.removeAll(removed)) {
                writeVerified(SETTING, ScreenshotProtectionUtils.serialize(packages), userId);
            }
            if (identities.keySet().removeAll(removed)) {
                writeVerified(IDENTITIES, ScreenshotProtectionUtils.serializeIdentities(identities), userId);
            }
            if (blocked.removeAll(removed)) {
                writeVerified(BLOCKED, ScreenshotProtectionUtils.serialize(blocked), userId);
            }
            final Set<String> merged;
            synchronized (mService.mGlobalLock) {
                final UserState user = mUsers.get(userId);
                for (String packageName : removed) {
                    forgetDetectionLocked(user, packageName);
                }
                for (Map.Entry<String, Integer> entry : pending.entrySet()) {
                    if (user.pendingDetected.remove(entry.getKey(), entry.getValue())) {
                        user.detectionSize -= entry.getKey().length() + 1;
                    }
                }
                for (String packageName : new TreeSet<>(detected)) {
                    if (!user.detected.contains(packageName) && !user.pendingDetected.containsKey(packageName)
                            && user.detectionSize + packageName.length() + 1
                                    <= ScreenshotProtectionUtils.MAX_PACKAGES_LENGTH + 1) {
                        user.detected.add(packageName);
                        user.detectionSize += packageName.length() + 1;
                    }
                }
                merged = new HashSet<>(user.detected);
            }
            final String updatedDetected = ScreenshotProtectionUtils.serialize(merged);
            if (!updatedDetected.equals(detectedValue == null ? "" : detectedValue)) {
                writeVerified(DETECTED, updatedDetected, userId);
            }
            if (!packages.equals(ScreenshotProtectionUtils.parse(read(SETTING, userId)))
                    || !identities.equals(ScreenshotProtectionUtils.parseIdentities(read(IDENTITIES, userId)))
                    || !blocked.equals(ScreenshotProtectionUtils.parse(read(BLOCKED, userId)))
                    || !merged.equals(ScreenshotProtectionUtils.parse(read(DETECTED, userId)))) {
                throw new IllegalStateException("Screenshot protection state changed during reload");
            }
            mPendingRemovals.remove(userId);
            cancelRetry(userId);
            publish(userId, allowed, blocked, packages, identityMatches, true);
        } catch (RuntimeException e) {
            failUser(userId, blocked);
            scheduleRetry(userId);
            Slog.w(TAG, "Unable to refresh screenshot protection setting");
        }
    }

    @GuardedBy("mService.mGlobalLock")
    private void forgetDetectionLocked(UserState user, String packageName) {
        if (user.detected.remove(packageName)) {
            user.detectionSize -= packageName.length() + 1;
        }
        if (user.pendingDetected.remove(packageName) != null) {
            user.detectionSize -= packageName.length() + 1;
        }
    }

    private String read(String key, int userId) {
        return Settings.Secure.getStringForUser(mContext.getContentResolver(), key, userId);
    }

    private void writeVerified(String key, String value, int userId) {
        if (!Settings.Secure.putStringForUser(mContext.getContentResolver(), key, value, userId)
                || !value.equals(read(key, userId))) {
            throw new IllegalStateException("Screenshot protection state was not saved");
        }
    }

    private void failUser(int userId, Set<String> attemptedBlocks) {
        synchronized (mService.mGlobalLock) {
            final UserState user = mUsers.computeIfAbsent(userId, id -> new UserState());
            final Set<String> blocked = new HashSet<>(user.blocked);
            blocked.addAll(attemptedBlocks);
            publish(userId, Collections.emptyMap(), blocked, user.requestedAllowed,
                    Collections.emptySet(), false);
        }
    }

    private void scheduleRetry(int userId) {
        if (!mStarted || mRetries.containsKey(userId)) {
            return;
        }
        final Runnable retry = () -> {
            mRetries.remove(userId);
            reloadUser(userId);
        };
        mRetries.put(userId, retry);
        mHandler.postDelayed(retry, RETRY_DELAY_MS);
    }

    private void cancelRetry(int userId) {
        final Runnable retry = mRetries.remove(userId);
        if (retry != null) {
            mHandler.removeCallbacks(retry);
        }
    }

    private void publish(int userId, Map<String, Integer> allowed, Set<String> blocked,
            Set<String> requestedAllowed, Set<String> identityMatches, boolean loaded) {
        synchronized (mService.mGlobalLock) {
            final UserState user = mUsers.computeIfAbsent(userId, id -> new UserState());
            user.requestedAllowed = requestedAllowed;
            user.identityMatches = identityMatches;
            user.loaded = loaded;
            if (allowed.equals(user.allowed) && blocked.equals(user.blocked)) {
                return;
            }
            final Set<String> changed = mStaleSnapshots.computeIfAbsent(userId, id -> new HashSet<>());
            changed.addAll(user.allowed.keySet());
            changed.addAll(allowed.keySet());
            changed.addAll(user.blocked);
            changed.addAll(blocked);
            user.allowed = allowed;
            user.blocked = blocked;
            try {
                mService.mRoot.refreshSecureSurfaceState();
                invalidateSnapshotsLocked();
            } catch (RuntimeException e) {
                disable();
                Slog.w(TAG, "Unable to refresh secure surfaces");
            }
        }
    }

    @GuardedBy("mService.mGlobalLock")
    private void invalidateSnapshotsLocked() {
        for (Task task : mService.mAtmService.getRecentTasks().getRawTasks()) {
            final Set<String> packages = mStaleSnapshots.get(task.mUserId);
            if (packages == null) {
                continue;
            }
            if ((task.realActivity != null && packages.contains(task.realActivity.getPackageName()))
                    || task.forAllActivities((Predicate<ActivityRecord>) activity ->
                            packages.contains(activity.packageName))) {
                mService.mTaskSnapshotController.removeAndDeleteSnapshot(task.mTaskId, task.mUserId);
                mService.mSnapshotController.notifySnapshotReleased(task.mTaskId);
            }
        }
        mStaleSnapshots.clear();
    }

    private void disable() {
        mStarted = false;
        mDisabled = true;
        synchronized (mService.mGlobalLock) {
            for (Map.Entry<Integer, UserState> entry : mUsers.entrySet()) {
                final UserState user = entry.getValue();
                mStaleSnapshots.computeIfAbsent(entry.getKey(), id -> new HashSet<>())
                        .addAll(user.allowed.keySet());
                user.allowed = Collections.emptyMap();
                user.loaded = false;
            }
            try {
                mService.mRoot.refreshSecureSurfaceState();
                invalidateSnapshotsLocked();
            } catch (RuntimeException e) {
                mHandler.postDelayed(this::disable, RETRY_DELAY_MS);
                Slog.w(TAG, "Unable to restore secure surfaces; retrying");
            }
        }
    }

    @GuardedBy("mService.mGlobalLock")
    void dumpLocked(PrintWriter pw) {
        pw.println("WINDOW MANAGER SCREENSHOT PROTECTION");
        pw.println("  started=" + mStarted + " disabled=" + mDisabled);
        for (Map.Entry<Integer, UserState> entry : new TreeMap<>(mUsers).entrySet()) {
            final UserState user = entry.getValue();
            pw.println("  User " + entry.getKey() + " (cacheLoaded=" + user.loaded + "):");
            pw.println("    allowed:");
            for (String packageName : new TreeSet<>(user.requestedAllowed)) {
                final String match = user.loaded
                        ? Boolean.toString(user.identityMatches.contains(packageName)) : "unknown";
                pw.println("      " + packageName + " identity-match=" + match);
            }
            pw.println("    blocked: " + new TreeSet<>(user.blocked));
            pw.println("    detected: " + new TreeSet<>(user.detected));
            pw.println("    pending detection: " + new TreeSet<>(user.pendingDetected.keySet()));
        }
    }
}
