package com.android.systemui.qs.tiles.dialog;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IProgressListener;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClipboardShareDialogDelegate implements SystemUIDialog.Delegate {

    private static final String TAG = "ClipboardShareDialog";

    // How long to wait for a just-started profile to reach the running state before giving up.
    private static final long START_TIMEOUT_MS = 8000;

    private final Context mContext;
    private final SystemUIDialog.Factory mDialogFactory;
    private final ClipboardShareDialogManager mManager;
    private final Executor mBgExecutor;

    @AssistedInject
    public ClipboardShareDialogDelegate(
            @Assisted Context context,
            SystemUIDialog.Factory dialogFactory,
            ClipboardShareDialogManager manager,
            @Background Executor bgExecutor) {
        mContext = context;
        mDialogFactory = dialogFactory;
        mManager = manager;
        mBgExecutor = bgExecutor;
    }

    @Override
    public SystemUIDialog createDialog() {
        SystemUIDialog dialog = mDialogFactory.create(this, mContext);
        View content = buildContentView(dialog);
        if (content != null) {
            dialog.setView(content);
        }
        dialog.setOnDismissListener(d -> mManager.destroyDialog());
        return dialog;
    }

    private View buildContentView(SystemUIDialog dialog) {
        final int currentUserId = ActivityManager.getCurrentUser();
        final int secretUserId = new LockPatternUtils(mContext).getSecretProfileUserId();

        // Never let the secret profile share its clipboard out either: if it is the foreground
        // user, the tile does nothing.
        if (currentUserId == secretUserId) {
            Log.i(TAG, "Current user is the secret profile; clipboard share disabled");
            return null;
        }

        UserManager um = mContext.getSystemService(UserManager.class);
        if (um == null) return null;

        ClipData clipData;
        try {
            Context userCtx = mContext.createContextAsUser(UserHandle.of(currentUserId), 0);
            ClipboardManager cm = userCtx.getSystemService(ClipboardManager.class);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            clipData = cm.getPrimaryClip();
        } catch (Exception e) {
            Log.e(TAG, "Cannot read clipboard for user " + currentUserId, e);
            return null;
        }

        if (clipData == null || clipData.getItemCount() == 0) return null;

        final ActivityManager am = mContext.getSystemService(ActivityManager.class);
        final List<UserInfo> targetUsers = getEligibleTargetUsers(um, currentUserId, secretUserId);

        if (targetUsers.isEmpty()) return null;

        View root = LayoutInflater.from(mContext)
                .inflate(R.layout.clipboard_share_dialog, null, false);

        TextView title = root.findViewById(R.id.clipboard_share_title);
        title.setText(R.string.clipboard_share_title);

        final ClipData finalClipData = clipData;
        RecyclerView rv = root.findViewById(R.id.clipboard_user_list);
        rv.setLayoutManager(new LinearLayoutManager(mContext));
        rv.setAdapter(new UserAdapter(mContext, targetUsers, um, am, mBgExecutor, user -> {
            dialog.dismiss();
            final int targetUserId = user.id;
            if (am != null && am.isUserRunning(targetUserId)) {
                mBgExecutor.execute(() -> pushClipboardToUser(finalClipData, targetUserId));
            } else {
                // Stopped profile: the platform cannot deliver a clip to a user that is not
                // running (ClipboardService rejects writes for a stopped user). Ask for explicit
                // consent to start it — which overrides its background policy — before doing so.
                showStartConfirmation(user, finalClipData);
            }
        }));

        root.findViewById(R.id.clipboard_share_cancel)
                .setOnClickListener(v -> dialog.dismiss());

        return root;
    }

    /**
     * The users a clipboard share may target, and the single source of truth the tile's
     * availability state must also use so the tile and dialog never disagree. A user is eligible
     * when it is: not the current user, not the secret profile ({@code secretUserId}), a full
     * (non-profile) user (which excludes managed / clone / private-space profiles), and enabled.
     *
     * <p>Stopped users are intentionally included here: they can still be targeted, but only after
     * the user consents to starting them (see {@link #showStartConfirmation}). Whether a given user
     * is currently running is decided at tap time, not during enumeration.
     *
     * @param secretUserId {@link com.android.internal.widget.LockPatternUtils#getSecretProfileUserId()},
     *                     or {@link UserHandle#USER_NULL} when there is no secret profile.
     */
    public static List<UserInfo> getEligibleTargetUsers(UserManager um, int currentUserId,
            int secretUserId) {
        final List<UserInfo> out = new ArrayList<>();
        if (um == null) return out;
        final List<UserInfo> users;
        try {
            users = um.getAliveUsers();
        } catch (Exception e) {
            Log.e(TAG, "Failed to enumerate users", e);
            return out;
        }
        for (UserInfo user : users) {
            if (user == null) continue;
            if (user.id == currentUserId) continue;
            // Defense in depth: getAliveUsers() already hides the secret profile server-side, but
            // exclude it here too so it can never be a target even if that filtering changes.
            if (secretUserId != UserHandle.USER_NULL && user.id == secretUserId) continue;
            if (!user.isEnabled() || !user.isFull()) continue;
            out.add(user);
        }
        return out;
    }

    /**
     * Asks the user to confirm starting a stopped target profile before sharing to it. Starting the
     * profile keeps it running, which overrides its background-execution setting, so this is never
     * done silently.
     */
    private void showStartConfirmation(UserInfo user, ClipData clip) {
        final int targetUserId = user.id;
        SystemUIDialog confirm = mDialogFactory.create(mContext);
        confirm.setTitle(R.string.clipboard_share_title);
        confirm.setMessage(mContext.getString(
                R.string.clipboard_share_start_profile_message, user.name));
        confirm.setPositiveButton(R.string.clipboard_share_start_button, (d, w) ->
                mBgExecutor.execute(() -> startProfileThenPush(clip, targetUserId)));
        confirm.setNegativeButton(android.R.string.cancel, null);
        confirm.show();
    }

    /**
     * Starts a stopped target profile in the background, then injects the clip once it is running.
     */
    private void startProfileThenPush(ClipData clip, int targetUserId) {
        mContext.getMainExecutor().execute(() ->
                Toast.makeText(mContext, R.string.clipboard_share_starting,
                        Toast.LENGTH_SHORT).show());

        final AtomicBoolean handled = new AtomicBoolean(false);
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final Runnable timeout = () -> {
            if (handled.compareAndSet(false, true)) {
                Log.w(TAG, "Timed out starting user " + targetUserId);
                showError();
            }
        };

        final IProgressListener listener = new IProgressListener.Stub() {
            private void onRunning() {
                if (handled.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeout);
                    mBgExecutor.execute(() -> pushClipboardToUser(clip, targetUserId));
                }
            }
            @Override public void onStarted(int id, Bundle extras) { onRunning(); }
            @Override public void onProgress(int id, int progress, Bundle extras) { onRunning(); }
            @Override public void onFinished(int id, Bundle extras) { onRunning(); }
        };

        try {
            IActivityManager iam = ActivityManager.getService();
            boolean started = iam.startUserInBackgroundWithListener(targetUserId, listener);
            if (!started) {
                if (handled.compareAndSet(false, true)) {
                    Log.w(TAG, "startUserInBackground returned false for user " + targetUserId);
                    showError();
                }
                return;
            }
            mainHandler.postDelayed(timeout, START_TIMEOUT_MS);
        } catch (RemoteException e) {
            if (handled.compareAndSet(false, true)) {
                Log.e(TAG, "Failed to start user " + targetUserId, e);
                showError();
            }
        }
    }

    private void pushClipboardToUser(ClipData clip, int targetUserId) {
        try {
            // Final guard: never write to the secret profile, whatever the list contained.
            final int secretUserId = new LockPatternUtils(mContext).getSecretProfileUserId();
            if (targetUserId == secretUserId || targetUserId == ActivityManager.getCurrentUser()) {
                Log.w(TAG, "Refusing clipboard share to user " + targetUserId);
                showError();
                return;
            }

            // The target must be running for ClipboardService to accept the clip. For a stopped
            // profile this is only reached after startProfileThenPush() has started it.
            ActivityManager am = mContext.getSystemService(ActivityManager.class);
            if (am == null || !am.isUserRunning(targetUserId)) {
                Log.w(TAG, "Target user " + targetUserId + " is not running; aborting share");
                showError();
                return;
            }

            Context userCtx = mContext.createContextAsUser(UserHandle.of(targetUserId), 0);
            ClipboardManager targetCm = userCtx.getSystemService(ClipboardManager.class);
            if (targetCm == null) {
                Log.w(TAG, "ClipboardManager null for user " + targetUserId);
                showError();
                return;
            }

            ClipData safeClip = makeSafeClip(clip);
            if (safeClip == null) {
                Log.w(TAG, "No shareable plain text in clip; aborting share");
                showError();
                return;
            }
            targetCm.setPrimaryClip(safeClip);
            mContext.getMainExecutor().execute(() ->
                    Toast.makeText(mContext, R.string.clipboard_share_success,
                            Toast.LENGTH_SHORT).show());

            Log.i(TAG, "Clipboard pushed from user " + ActivityManager.getCurrentUser()
                    + " to user " + targetUserId);

        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException pushing clipboard to user " + targetUserId, se);
            showError();
        } catch (Exception e) {
            Log.e(TAG, "Error pushing clipboard to user " + targetUserId, e);
            showError();
        }
    }

    private void showError() {
        mContext.getMainExecutor().execute(() ->
                Toast.makeText(mContext, R.string.clipboard_share_error,
                        Toast.LENGTH_SHORT).show());
    }

    private ClipData makeSafeClip(ClipData original) {
        if (original == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < original.getItemCount(); i++) {
            // Use the literal text only; do not coerce URIs/Intents to text
            CharSequence text = original.getItemAt(i).getText();
            if (text != null && text.length() > 0) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(text);
            }
        }
        if (sb.length() == 0) return null;
        // Drop the label to avoid leaking metadata across the user boundary.
        ClipData safe = ClipData.newPlainText(null, sb.toString());
        // Preserve the sensitive flag so redaction still applies for the target user.
        ClipDescription origDesc = original.getDescription();
        if (origDesc != null && origDesc.getExtras() != null
                && origDesc.getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            safe.getDescription().setExtras(extras);
        }
        return safe;
    }


    interface OnUserSelectedListener {
        void onUserSelected(UserInfo user);
    }

    private static class UserAdapter extends RecyclerView.Adapter<UserAdapter.VH> {
        private final Context mCtx;
        private final List<UserInfo> mUsers;
        private final UserManager mUm;
        private final ActivityManager mAm;
        private final Executor mBgExecutor;
        private final OnUserSelectedListener mListener;

        UserAdapter(Context ctx, List<UserInfo> users, UserManager um, ActivityManager am,
                Executor bgExecutor, OnUserSelectedListener listener) {
            mCtx = ctx;
            mUsers = users;
            mUm = um;
            mAm = am;
            mBgExecutor = bgExecutor;
            mListener = listener;
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(mCtx)
                    .inflate(R.layout.clipboard_share_user_item, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            UserInfo user = mUsers.get(position);
            holder.name.setText(user.name);
            holder.itemView.setOnClickListener(v -> mListener.onUserSelected(user));

            // Show a "Not running" hint on stopped profiles so the user knows a tap will offer to
            // start the profile rather than sharing immediately.
            final boolean running = mAm != null && mAm.isUserRunning(user.id);
            if (running) {
                holder.status.setVisibility(View.GONE);
            } else {
                holder.status.setText(R.string.clipboard_share_not_running);
                holder.status.setVisibility(View.VISIBLE);
            }

            final int userId = user.id;
            holder.icon.setImageDrawable(
                    mCtx.getDrawable(com.android.settingslib.R.drawable.ic_account_circle));
            holder.boundUserId = userId;
            mBgExecutor.execute(() -> {
                Bitmap bmp = mUm.getUserIcon(userId);
                if (bmp == null) return;
                Drawable avatar = new BitmapDrawable(mCtx.getResources(), bmp);
                mCtx.getMainExecutor().execute(() -> {
                    if (holder.boundUserId == userId) {
                        holder.icon.setImageDrawable(avatar);
                    }
                });
            });
        }

        @Override
        public int getItemCount() { return mUsers.size(); }

        static class VH extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView name;
            TextView status;
            int boundUserId = UserHandle.USER_NULL;
            VH(View v) {
                super(v);
                icon = v.findViewById(R.id.icon);
                name = v.findViewById(R.id.name);
                status = v.findViewById(R.id.status);
            }
        }
    }

    @AssistedFactory
    public interface Factory {
        ClipboardShareDialogDelegate create(Context context);
    }
}
