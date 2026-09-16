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

package com.android.server.clipboard;

import static android.content.ClipboardQueueContract.*;

import android.annotation.PermissionManuallyEnforced;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ClipboardQueueEntry;
import android.content.ClipboardQueueInbox;
import android.content.ClipboardQueueTarget;
import android.content.Context;
import android.content.IClipboardQueue;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteFullException;
import android.ext.settings.CrossProfileClipboardAccessSettings;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.locksettings.LockSettingsInternal;
import com.android.server.pm.UserManagerInternal;

import java.util.ArrayList;
import java.util.HashMap;

public final class ClipboardQueueService extends SystemService {
    private static final String TAG = "ClipboardQueueService";
    private static final long SWEEP_MILLIS = 60L * 60 * 1000;
    private static final long RETRY_MILLIS = 60 * 1000;
    private static final long MAX_RETRY_MILLIS = 60L * 60 * 1000;
    private static final long LOG_WINDOW_MILLIS = 60_000;
    private static final HashMap<String, Integer> sFailures = new HashMap<>();
    private static final Handler sLogHandler = new Handler(Looper.getMainLooper());
    private long mRetryDelay = RETRY_MILLIS;
    private long mRetryAt = Long.MAX_VALUE;
    private boolean mSweepScheduled;
    private boolean mStartFailed;
    private long mRecoveryNow;
    private long mRecoveryElapsed;
    private long mNextAlarm = Long.MAX_VALUE;
    private final Object mLock = new Object();
    private ClipboardQueueStore mStore;
    private Handler mHandler;
    private boolean mFailed;

    public ClipboardQueueService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        String stage = "publish";
        boolean registering = false;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mHandler.post(ClipboardQueueService.this::sweep);
            }
        };
        try {
            publishBinderService(SERVICE_NAME, mBinder);
            stage = "verify_publish";
            IBinder published = ServiceManager.checkService(SERVICE_NAME);
            if (published != mBinder) throw new IllegalStateException("Queue binder not published");
            stage = "register_receiver";
            IntentFilter filter = new IntentFilter(Intent.ACTION_USER_REMOVED);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            registering = true;
            getContext().registerReceiver(receiver, filter, null, mHandler,
                    Context.RECEIVER_NOT_EXPORTED);
            Slog.i(TAG, "service_published: " + SERVICE_NAME);
            stage = "start_storage";
            if (!mHandler.post(this::sweep)) throw new IllegalStateException("Queue handler stopped");
        } catch (RuntimeException e) {
            synchronized (mLock) {
                mFailed = true;
                mStartFailed = true;
            }
            mHandler.removeCallbacksAndMessages(null);
            if (registering) {
                try {
                    getContext().unregisterReceiver(receiver);
                } catch (RuntimeException cleanup) {
                    logFailure("service_start/receiver_cleanup", cleanup);
                    e.addSuppressed(cleanup);
                }
            }
            thread.quitSafely();
            logFailure("service_start/" + stage, e);
            throw e;
        }
    }

    private long prune() {
        UserInfo[] users = users().getUserInfos();
        int secret = secretId();
        try {
            return prune(users, secret, true);
        } catch (SQLiteFullException e) {
            logFailure("prune/storage_full; retrying without expiry notices", e);
            return prune(users, secret, false);
        }
    }

    private long prune(UserInfo[] users, int secret, boolean recordNotices) {
        mStore.begin();
        RuntimeException failure = null;
        try {
            long now = mStore.prune(users, secret, recordNotices);
            mStore.succeed();
            return now;
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            endTransaction(failure);
        }
    }

    private void endTransaction(RuntimeException failure) {
        try {
            mStore.end(failure == null);
        } catch (RuntimeException e) {
            if (failure == null) throw e;
            failure.addSuppressed(e);
        }
    }

    private void scheduleExpiry() {
        scheduleAlarm(mStore.nextExpiryElapsed());
    }

    private void scheduleAlarm(long when) {
        if (when == mNextAlarm) return;
        AlarmManager alarms = getContext().getSystemService(AlarmManager.class);
        if (alarms == null) throw unavailable("AlarmManager unavailable");
        try {
            if (when == Long.MAX_VALUE) {
                alarms.cancel(mExpiryAlarm);
            } else {
                // System-UID alarms without a WorkSource are unrestricted during device idle.
                alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, TAG,
                        mExpiryAlarm, mHandler);
            }
            mNextAlarm = when;
        } catch (RuntimeException e) {
            logFailure("schedule_alarm", e);
            throw unavailable("Queue expiry alarm unavailable");
        }
    }

    private void updateSweep() {
        boolean needed = !mFailed && mStore != null && mStore.hasMaintenance();
        if (!needed) {
            mHandler.removeCallbacks(mSweep);
            mSweepScheduled = false;
        } else if (!mSweepScheduled) {
            mSweepScheduled = mHandler.postDelayed(mSweep, SWEEP_MILLIS);
        }
    }

    private void maintenanceSucceeded() {
        scheduleExpiry();
        updateSweep();
        mRetryAt = Long.MAX_VALUE;
        mRetryDelay = RETRY_MILLIS;
    }

    private void retryMaintenance() {
        mHandler.removeCallbacks(mSweep);
        mSweepScheduled = false;
        long now = SystemClock.elapsedRealtime();
        if (mRetryAt == Long.MAX_VALUE || mRetryAt <= now) {
            mRetryAt = now + mRetryDelay;
            mRetryDelay = Math.min(MAX_RETRY_MILLIS, mRetryDelay * 2);
        }
        try {
            scheduleAlarm(Math.min(mRetryAt, mNextAlarm > now ? mNextAlarm : Long.MAX_VALUE));
        } catch (RuntimeException e) {
            logFailure("sweep_retry_alarm", e);
            mSweepScheduled = mHandler.postDelayed(mSweep, Math.max(1, mRetryAt - now));
        }
    }

    private void sweep() {
        synchronized (mLock) {
            if (mStartFailed) return;
            mHandler.removeCallbacks(mSweep);
            mSweepScheduled = false;
            try {
                if (mStore == null || mFailed) {
                    if (mStore != null) {
                        mRecoveryNow = mStore.now();
                        mRecoveryElapsed = SystemClock.elapsedRealtime();
                        mStore.close();
                        mStore = null;
                    }
                    mStore = new ClipboardQueueStore(mRecoveryNow, mRecoveryElapsed);
                    mFailed = false;
                }
                prune();
                maintenanceSucceeded();
            } catch (Exception e) {
                if (mStore == null || e instanceof SQLiteException
                        && !(e instanceof SQLiteFullException)) mFailed = true;
                logFailure("sweep", e);
                retryMaintenance();
            }
        }
    }

    private final AlarmManager.OnAlarmListener mExpiryAlarm = () -> {
        synchronized (mLock) {
            mNextAlarm = Long.MAX_VALUE;
        }
        sweep();
    };
    private final Runnable mSweep = this::sweep;

    private UserManagerInternal users() {
        UserManagerInternal users = LocalServices.getService(UserManagerInternal.class);
        if (users == null) throw unavailable("UserManagerInternal unavailable");
        return users;
    }

    private int secretId() {
        LockSettingsInternal settings = LocalServices.getService(LockSettingsInternal.class);
        if (settings == null) throw unavailable("LockSettingsInternal unavailable");
        return settings.getSecretProfileUserId();
    }

    private boolean eligible(UserInfo user, int secretId) {
        return user != null && user.id != secretId && user.isFull() && user.isEnabled()
                && !user.partial && !user.preCreated && !user.isGuest() && !user.isEphemeral()
                && !user.isRestricted() && !user.isDemo()
                && !users().getUserRestriction(user.id, UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE);
    }

    private String profileName(UserInfo user) {
        if (user.id == UserHandle.USER_SYSTEM && (user.name == null || user.name.isEmpty())) {
            return getContext().getString(com.android.internal.R.string.owner_name);
        }
        if (user.name == null) return "";
        StringBuilder name = new StringBuilder();
        for (int offset = 0; offset < user.name.length() && name.length() < 100;) {
            int codePoint = user.name.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SURROGATE) continue;
            if (name.length() + Character.charCount(codePoint) > 100) break;
            name.appendCodePoint(codePoint);
        }
        return name.toString();
    }

    private UserInfo authorize(int uid) {
        int userId = UserHandle.getUserId(uid);
        try {
            if (getContext().getPackageManager().getPackageUidAsUser(
                    "com.android.systemui", userId) != uid) {
                throw new SecurityException("SystemUI required");
            }
        } catch (PackageManager.NameNotFoundException e) {
            logFailure("authorize/package_lookup", e);
            throw new SecurityException("SystemUI package unavailable");
        }
        UserInfo user = users().getUserInfo(userId);
        ActivityManagerInternal activity = LocalServices.getService(ActivityManagerInternal.class);
        KeyguardManager keyguard = getContext().getSystemService(KeyguardManager.class);
        if (!eligible(user, secretId())) {
            throw new SecurityException("Caller profile ineligible");
        }
        if (activity == null) throw unavailable("ActivityManagerInternal unavailable");
        if (keyguard == null) throw unavailable("KeyguardManager unavailable");
        if (activity.getCurrentUserId() != userId) {
            throw new SecurityException("Caller is not the foreground user");
        }
        if (!users().isUserUnlocked(userId)) {
            throw new SecurityException("Caller user is not unlocked");
        }
        if (keyguard.isDeviceLocked(userId)) {
            throw new SecurityException("Caller device is locked");
        }
        return user;
    }

    private final class Access {
        final UserInfo user;
        final long now;
        final ArrayList<UserInfo> peers = new ArrayList<>();
        final ArrayList<UserInfo> eligiblePeers = new ArrayList<>();
        final ArrayList<UserInfo> imports = new ArrayList<>();
        boolean exports;

        Access(UserInfo user, long now) {
            this.user = user;
            this.now = now;
        }

        UserInfo removalPeer(int id, int serial) {
            UserInfo peer = users().getUserInfo(id);
            if (id == user.id || peer == null || peer.serialNumber != serial) {
                throw new SecurityException("Profile unavailable");
            }
            peers.add(peer);
            return peer;
        }

        UserInfo peer(int id, int serial) {
            UserInfo peer = removalPeer(id, serial);
            if (!eligible(peer, secretId())) throw new SecurityException("Profile unavailable");
            eligiblePeers.add(peer);
            return peer;
        }

        boolean hasDuplicateProfileName(UserInfo sender) {
            String name = profileName(sender);
            int secret = secretId();
            for (UserInfo other : users().getUserInfos()) {
                if (other.id != sender.id && eligible(other, secret)
                        && name.equals(profileName(other))) {
                    if (other.id != user.id) peer(other.id, other.serialNumber);
                    return true;
                }
            }
            return false;
        }

        void requireExport() {
            exports = true;
            if (!CrossProfileClipboardAccessSettings.isExportAccessAllowed(getContext(), user.id)) {
                throw new ServiceSpecificException(ERROR_EXPORT_BLOCKED, "Clipboard export disabled");
            }
        }

        void checkImport(UserInfo recipient) {
            if (!CrossProfileClipboardAccessSettings.isImportAccessAllowed(
                    getContext(), recipient.id)) {
                throw new ServiceSpecificException(recipient.id == user.id
                        ? ERROR_IMPORT_BLOCKED : ERROR_TARGET_IMPORT_BLOCKED,
                        "Clipboard import disabled");
            }
        }

        void requireImport(UserInfo recipient) {
            checkImport(recipient);
            imports.add(recipient);
        }

        UserInfo target(int id, int serial) {
            UserInfo target = peer(id, serial);
            requireImport(target);
            return target;
        }

        void checkSource(String id) {
            int[] source = mStore.sourceOf(id, user);
            peer(source[0], source[1]);
        }

        void recheck() {
            if (exports) requireExport();
            for (UserInfo recipient : imports) checkImport(recipient);
            for (UserInfo peer : peers) {
                UserInfo current = users().getUserInfo(peer.id);
                if (current == null || current.serialNumber != peer.serialNumber) {
                    throw new SecurityException("Profile changed");
                }
            }
            int secret = secretId();
            for (UserInfo peer : eligiblePeers) {
                UserInfo current = users().getUserInfo(peer.id);
                if (!eligible(current, secret) || current.serialNumber != peer.serialNumber) {
                    throw new SecurityException("Profile changed");
                }
            }
        }
    }

    private interface Operation<T> {
        T run(Access access);
    }

    private <T> T call(String operation, Operation<T> action) {
        return call(operation, null, action);
    }

    private <T> T call(String operation, String chunkId, Operation<T> action) {
        try {
            getContext().enforceCallingPermission(PERMISSION, "SystemUI queue permission required");
            int uid = Binder.getCallingUid();
            long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserInfo user = authorize(uid);
                    if (mFailed) {
                        throw new ServiceSpecificException(ERROR_STORAGE, "Queue storage failed");
                    }
                    if (mStore == null) throw unavailable("Queue storage is opening");
                    try {
                        boolean chunk = "append".equals(operation) || "read".equals(operation);
                        long now = chunk ? mStore.now() : prune();
                        if (!chunk) scheduleExpiry();
                        mStore.begin();
                        RuntimeException failure = null;
                        T result;
                        try {
                            if (chunk) mStore.persistClock(false);
                            Access access = new Access(user, now);
                            result = action.run(access);
                            access.recheck();
                            if (authorize(uid).serialNumber != user.serialNumber) {
                                throw new SecurityException("User changed");
                            }
                            if (chunk) mStore.requireLive(chunkId);
                            // A rollback must keep the old deadline until its transaction has ended.
                            if (!chunk) scheduleAlarm(Math.min(mNextAlarm, mStore.nextExpiryElapsed()));
                            mStore.succeed();
                        } catch (RuntimeException e) {
                            failure = e;
                            throw e;
                        } finally {
                            endTransaction(failure);
                            if (!chunk && failure != null && !(failure instanceof SQLiteException)) {
                                try {
                                    maintenanceSucceeded();
                                } catch (RuntimeException cleanup) {
                                    failure.addSuppressed(cleanup);
                                    retryMaintenance();
                                }
                            }
                        }
                        if (!chunk) maintenanceSucceeded();
                        return result;
                    } catch (SQLiteFullException e) {
                        logFailure(operation + "/storage_full", e);
                        retryMaintenance();
                        throw new ServiceSpecificException(ERROR_FULL, "Queue storage full");
                    } catch (SQLiteException e) {
                        mFailed = true;
                        retryMaintenance();
                        logFailure(operation + "/storage; existing data retained", e);
                        throw new ServiceSpecificException(ERROR_STORAGE, "Queue storage failed");
                    } catch (ServiceSpecificException e) {
                        if (e.errorCode == ERROR_UNAVAILABLE) retryMaintenance();
                        throw e;
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } catch (RuntimeException e) {
            logFailure(operation, e);
            throw e;
        }
    }

    private static void logFailure(String operation, Exception e) {
        synchronized (sFailures) {
            Integer repeats = sFailures.get(operation);
            if (repeats != null) {
                sFailures.put(operation, repeats == Integer.MAX_VALUE ? repeats : repeats + 1);
                return;
            }
            sFailures.put(operation, 0);
            sLogHandler.postDelayed(() -> {
                synchronized (sFailures) {
                    int suppressed = sFailures.remove(operation);
                    if (suppressed != 0) {
                        Slog.e(TAG, operation + ": suppressed " + suppressed
                                + " repeated failures in " + LOG_WINDOW_MILLIS + " ms");
                    }
                }
            }, LOG_WINDOW_MILLIS);
        }
        String code = e instanceof ServiceSpecificException refused
                ? " (code " + refused.errorCode + ")" : "";
        Slog.e(TAG, operation + " failed: " + e.getClass().getName() + code);
    }

    private static ServiceSpecificException unavailable(String reason) {
        ServiceSpecificException error = new ServiceSpecificException(ERROR_UNAVAILABLE, reason);
        logFailure("dependency", error);
        return error;
    }

    private final IClipboardQueue.Stub mBinder = new IClipboardQueue.Stub() {
        @Override
        @PermissionManuallyEnforced
        public ClipboardQueueTarget[] getTargets() {
            return call("getTargets", access -> {
                access.requireExport();
                ArrayList<ClipboardQueueTarget> targets = new ArrayList<>();
                int secret = secretId();
                for (UserInfo user : users().getUserInfos()) {
                    if (user.id == access.user.id || !eligible(user, secret)
                            || !CrossProfileClipboardAccessSettings.isImportAccessAllowed(
                                    getContext(), user.id)) continue;
                    access.target(user.id, user.serialNumber);
                    ClipboardQueueTarget target = new ClipboardQueueTarget();
                    target.userId = user.id;
                    target.serialNumber = user.serialNumber;
                    target.name = profileName(user);
                    targets.add(target);
                }
                return targets.toArray(new ClipboardQueueTarget[0]);
            });
        }

        @Override
        @PermissionManuallyEnforced
        public String beginInsert(int targetId, int targetSerial, String mimeType, int size,
                byte[] sha256, boolean sensitive) {
            return call("beginInsert", access -> {
                access.requireExport();
                return mStore.insert(access.user, access.target(targetId, targetSerial),
                        mimeType, size, sha256, sensitive, access.now);
            });
        }

        @Override
        @PermissionManuallyEnforced
        public void append(String id, int offset, byte[] data) {
            call("append", id, access -> {
                access.requireExport();
                ClipboardQueueStore.Pending pending = mStore.pending(id, access.user);
                access.target(pending.targetId, pending.targetSerial);
                mStore.append(id, pending, offset, data);
                return null;
            });
        }

        @Override
        @PermissionManuallyEnforced
        public void commitInsert(String id) {
            call("commitInsert", access -> {
                access.requireExport();
                ClipboardQueueStore.Pending pending = mStore.pending(id, access.user);
                access.target(pending.targetId, pending.targetSerial);
                mStore.commit(id, pending, access.now);
                return null;
            });
        }

        @Override
        @PermissionManuallyEnforced
        public void abortInsert(String id) {
            call("abortInsert", access -> {
                ClipboardQueueStore.Pending pending = mStore.pending(id, access.user);
                access.removalPeer(pending.targetId, pending.targetSerial);
                mStore.remove(id);
                return null;
            });
        }

        @Override
        @PermissionManuallyEnforced
        public ClipboardQueueInbox listInbox() {
            return call("listInbox", access -> {
                access.requireImport(access.user);
                ClipboardQueueInbox inbox = new ClipboardQueueInbox();
                ArrayList<ClipboardQueueEntry> entries = new ArrayList<>();
                for (ClipboardQueueEntry entry : mStore.list(access.user)) {
                    if (eligible(users().getUserInfo(entry.senderId), secretId())) {
                        UserInfo sender = access.peer(entry.senderId, entry.senderSerial);
                        entry.senderName = profileName(sender);
                        entry.senderNameAmbiguous = access.hasDuplicateProfileName(sender);
                        entries.add(entry);
                    }
                }
                inbox.entries = entries.toArray(new ClipboardQueueEntry[0]);
                for (ClipboardQueueStore.ExpiryNotice notice : mStore.expiryNotices(access.user)) {
                    if (eligible(users().getUserInfo(notice.senderId), secretId())) {
                        access.peer(notice.senderId, notice.senderSerial);
                        inbox.lastExpiredAt = Math.max(inbox.lastExpiredAt, notice.expiredAt);
                    }
                }
                return inbox;
            });
        }

        @Override
        @PermissionManuallyEnforced
        public byte[] read(String id, int offset) {
            return call("read", id, access -> {
                access.requireImport(access.user);
                access.checkSource(id);
                return mStore.read(id, offset);
            });
        }

        @Override
        @PermissionManuallyEnforced
        public void delete(String id) {
            call("delete", access -> {
                int[] peer = mStore.peerForDelete(id, access.user);
                access.removalPeer(peer[0], peer[1]);
                mStore.remove(id);
                return null;
            });
        }

        @Override
        @PermissionManuallyEnforced
        public void clearInbox() {
            call("clearInbox", access -> {
                for (ClipboardQueueEntry entry : mStore.list(access.user)) {
                    access.removalPeer(entry.senderId, entry.senderSerial);
                }
                for (ClipboardQueueStore.ExpiryNotice notice : mStore.expiryNotices(access.user)) {
                    access.removalPeer(notice.senderId, notice.senderSerial);
                }
                mStore.clear(access.user);
                return null;
            });
        }
    };
}
