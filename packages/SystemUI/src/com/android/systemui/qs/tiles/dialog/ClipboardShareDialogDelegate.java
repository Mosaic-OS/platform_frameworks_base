package com.android.systemui.qs.tiles.dialog;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
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

import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class ClipboardShareDialogDelegate implements SystemUIDialog.Delegate {

    private static final String TAG = "ClipboardShareDialog";

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

        final List<UserInfo> targetUsers = new ArrayList<>();
        for (UserInfo user : um.getAliveUsers()) {
            if (user.id != currentUserId && user.isEnabled() && !user.isManagedProfile()) {
                targetUsers.add(user);
            }
        }

        if (targetUsers.isEmpty()) return null;

        View root = LayoutInflater.from(mContext)
                .inflate(R.layout.clipboard_share_dialog, null, false);

        TextView title = root.findViewById(R.id.clipboard_share_title);
        title.setText(R.string.clipboard_share_title);

        final ClipData finalClipData = clipData;
        RecyclerView rv = root.findViewById(R.id.clipboard_user_list);
        rv.setLayoutManager(new LinearLayoutManager(mContext));
        rv.setAdapter(new UserAdapter(mContext, targetUsers, um, user -> {
            dialog.dismiss();
            final int targetUserId = user.id;
            mBgExecutor.execute(() -> pushClipboardToUser(finalClipData, targetUserId));
        }));

        root.findViewById(R.id.clipboard_share_cancel)
                .setOnClickListener(v -> dialog.dismiss());

        return root;
    }

    private void pushClipboardToUser(ClipData clip, int targetUserId) {
        try {
            ActivityManager am = mContext.getSystemService(ActivityManager.class);
            if (am != null && !am.isUserRunning(targetUserId)) {
                Log.i(TAG, "User " + targetUserId + " not running, starting in background...");
                try {
                    IActivityManager iam = ActivityManager.getService();
                    iam.startUserInBackground(targetUserId);
                } catch (RemoteException re) {
                    Log.e(TAG, "RemoteException starting user " + targetUserId, re);
                    showError();
                    return;
                }

                long deadline = System.currentTimeMillis() + 3000;
                while (!am.isUserRunning(targetUserId)
                        && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }

                if (!am.isUserRunning(targetUserId)) {
                    Log.w(TAG, "User " + targetUserId + " still not running after timeout");
                    showError();
                    return;
                }
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

    /**
     * Returns a text-only copy of {@code original} so that no content URIs or Intents (which can
     * carry cross-user URI permission grants) are shared across the user boundary. Returns
     * {@code null} when the clip carries no plain text, in which case the caller aborts the share.
     */
    private ClipData makeSafeClip(ClipData original) {
        if (original == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < original.getItemCount(); i++) {
            // Use the literal text only; do NOT coerce URIs/Intents to text (that would read the
            // referenced content, defeating the point of sanitizing).
            CharSequence text = original.getItemAt(i).getText();
            if (text != null && text.length() > 0) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(text);
            }
        }
        if (sb.length() == 0) return null;
        CharSequence label = original.getDescription() != null
                ? original.getDescription().getLabel() : null;
        return ClipData.newPlainText(label, sb.toString());
    }


    interface OnUserSelectedListener {
        void onUserSelected(UserInfo user);
    }

    private static class UserAdapter extends RecyclerView.Adapter<UserAdapter.VH> {
        private final Context mCtx;
        private final List<UserInfo> mUsers;
        private final UserManager mUm;
        private final OnUserSelectedListener mListener;

        UserAdapter(Context ctx, List<UserInfo> users, UserManager um,
                OnUserSelectedListener listener) {
            mCtx = ctx;
            mUsers = users;
            mUm = um;
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
            android.graphics.Bitmap bmp = mUm.getUserIcon(user.id);
            Drawable avatar = bmp != null
                    ? new BitmapDrawable(mCtx.getResources(), bmp)
                    : mCtx.getDrawable(com.android.settingslib.R.drawable.ic_account_circle);
            holder.icon.setImageDrawable(avatar);
            holder.name.setText(user.name);
            holder.itemView.setOnClickListener(v -> mListener.onUserSelected(user));
        }

        @Override
        public int getItemCount() { return mUsers.size(); }

        static class VH extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView name;
            VH(View v) {
                super(v);
                icon = v.findViewById(R.id.icon);
                name = v.findViewById(R.id.name);
            }
        }
    }

    @AssistedFactory
    public interface Factory {
        ClipboardShareDialogDelegate create(Context context);
    }
}