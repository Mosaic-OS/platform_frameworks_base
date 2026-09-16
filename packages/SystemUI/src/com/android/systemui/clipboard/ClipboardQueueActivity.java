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

package com.android.systemui.clipboard;

import static android.content.ClipboardQueueContract.*;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ClipboardQueueEntry;
import android.content.ClipboardQueueTarget;
import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.IClipboardQueue;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.DocumentsContract;
import android.text.BidiFormatter;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.clipboardoverlay.ClipboardListener;
import com.android.systemui.res.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class ClipboardQueueActivity extends Activity {
    private static final String TAG = "ClipboardQueueActivity";
    private static final int PICK_IMAGE = 1;
    private static final int SAVE_ITEM = 2;
    private static final long PROVIDER_OPEN_MILLIS = 15_000;
    private static final long TRANSFER_MILLIS = 120_000;
    private static final ExecutorService sCancelWorker = Executors.newSingleThreadExecutor();
    private static final ExecutorService sCloseWorker = Executors.newSingleThreadExecutor();
    private static final ThreadPoolExecutor sProviderWorker = new ThreadPoolExecutor(1, 1,
            0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<TextView, ClipboardQueueEntry> mInboxRows = new LinkedHashMap<>();
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private LinearLayout mContent;
    private TextInputEditText mText;
    private AlertDialog mDialog;
    private boolean mResumed;
    private boolean mBusy;
    private int mPicker;
    private volatile int mGeneration;
    private Operation mOperation;
    private final ThreadLocal<Operation> mWorkerOperation = new ThreadLocal<>();
    private boolean mInterruptedSave;
    private boolean mPickerRejected;
    private String mDisplayedStaticLabel;
    private Uri mPickerResult;
    private ClipboardQueueEntry mExportEntry;
    private ClipboardQueueEntry mDisplayedEntry;

    private final Runnable mCountdown = new Runnable() {
        @Override
        public void run() {
            if (!mResumed || !isActingUser() || mInboxRows.isEmpty()) return;
            boolean active = false;
            for (Map.Entry<TextView, ClipboardQueueEntry> row : mInboxRows.entrySet()) {
                row.getKey().setText(remainingLabel(row.getValue()));
                active |= !expired(row.getValue());
            }
            if (mDisplayedEntry != null && mDialog != null && mDialog.isShowing()) {
                mDialog.setMessage(entryMessage(mDisplayedEntry, mDisplayedStaticLabel));
            }
            if (active) mHandler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver mGuard = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            closeInbox();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        setTheme(R.style.Theme_SystemUI_ClipboardQueue);
        super.onCreate(null);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setHideOverlayWindows(true);
        setRecentsScreenshotEnabled(false);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        registerReceiver(mGuard, filter, Context.RECEIVER_NOT_EXPORTED);
        ClipboardQueueProvider.pruneOnLaunch(this);
        showHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        if (!isActingUser()) {
            closeInbox();
            return;
        }
        setBusy(mBusy);
        if (mInterruptedSave) {
            mInterruptedSave = false;
            toast(R.string.clipboard_queue_save_error);
        }
        if (mPickerRejected) {
            mPickerRejected = false;
            toast(R.string.clipboard_queue_picker_error);
        }
        mHandler.removeCallbacks(mCountdown);
        mCountdown.run();
        if (mPickerResult != null) {
            Uri uri = mPickerResult;
            mPickerResult = null;
            int picker = mPicker;
            mPicker = 0;
            if (picker == PICK_IMAGE) {
                importImage(uri, null, true);
            } else if (picker == SAVE_ITEM && mExportEntry != null) {
                ClipboardQueueEntry entry = mExportEntry;
                mExportEntry = null;
                save(entry, uri);
            }
        }
    }

    @Override
    protected void onPause() {
        mResumed = false;
        cancelWork();
        mHandler.removeCallbacks(mCountdown);
        mDisplayedEntry = null;
        if (mDialog != null) mDialog.dismiss();
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (mText != null) mText.setText("");
        if (mPicker == 0) closeInbox();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mGuard);
        mHandler.removeCallbacks(mCountdown);
        cancelWork();
        mWorker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
    }

    private boolean isActingUser() {
        try {
            checkActingUser();
            return true;
        } catch (RuntimeException e) {
            logFailure("acting_user", e);
            return false;
        }
    }

    private void checkActingUser() {
        UserManager users = getSystemService(UserManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (ActivityManager.getCurrentUser() != getUserId()) {
            throw new SecurityException("Activity is not the foreground user");
        }
        if (users == null) throw new SecurityException("Activity UserManager unavailable");
        if (keyguard == null) throw new SecurityException("Activity KeyguardManager unavailable");
        if (!users.isUserUnlocked()) throw new SecurityException("Activity user is not unlocked");
        if (keyguard.isDeviceLocked()) throw new SecurityException("Activity device is locked");
    }

    private void requireActingUser() {
        Operation operation = mWorkerOperation.get();
        if (operation != null) operation.check();
        checkActingUser();
        if (Thread.currentThread().isInterrupted()) {
            throw new SecurityException("Activity work interrupted");
        }
    }

    private void closeInbox() {
        mResumed = false;
        cancelWork();
        mHandler.removeCallbacks(mCountdown);
        mPickerResult = null;
        mExportEntry = null;
        if (mText != null) mText.setText("");
        if (mContent != null) mContent.removeAllViews();
        if (mDialog != null) mDialog.dismiss();
        finishAndRemoveTask();
    }

    private IClipboardQueue queue() throws IOException {
        requireActingUser();
        IClipboardQueue queue = IClipboardQueue.Stub.asInterface(
                ServiceManager.getService(SERVICE_NAME));
        if (queue == null) {
            logServiceNotFound();
            throw new ServiceNotFoundException();
        }
        return queue;
    }

    private static void logServiceNotFound() {
        String checkResult;
        try {
            checkResult = ServiceManager.checkService(SERVICE_NAME) == null ? "null" : "present";
        } catch (RuntimeException e) {
            checkResult = "threw:" + e.getClass().getName();
        }
        String context;
        try {
            context = SELinux.getContext();
        } catch (RuntimeException e) {
            context = "threw:" + e.getClass().getName();
        }
        Log.e(TAG, "service_lookup_failed: name=" + SERVICE_NAME
                + " uid=" + Process.myUid() + " pid=" + Process.myPid()
                + " selinux=" + context + " getService=null checkService=" + checkResult);
    }

    private void newPage(int title, int layout) {
        cancelWork();
        mHandler.removeCallbacks(mCountdown);
        mInboxRows.clear();
        mDisplayedEntry = null;
        if (mText != null) mText.setText("");
        mText = null;
        setContentView(R.layout.clipboard_queue_activity);
        View root = findViewById(R.id.clipboard_queue_root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsets.CONSUMED;
        });
        MaterialToolbar toolbar = findViewById(R.id.clipboard_queue_toolbar);
        toolbar.setTitle(title);
        toolbar.setNavigationContentDescription(title == R.string.clipboard_queue_title
                ? android.R.string.cancel : R.string.clipboard_queue_compose);
        toolbar.setNavigationOnClickListener(view -> {
            if (!mResumed || !isActingUser()) return;
            if (title == R.string.clipboard_queue_title) closeInbox();
            else showHome();
        });
        mContent = findViewById(R.id.clipboard_queue_content);
        getLayoutInflater().inflate(layout, mContent, true);
        protect(root);
        root.requestFocus();
        ScrollView scroll = findViewById(R.id.clipboard_queue_scroll);
        scroll.scrollTo(0, 0);
        root.requestApplyInsets();
    }

    private static void protect(View view) {
        view.setSaveEnabled(false);
        view.setSaveFromParentEnabled(false);
        view.setFilterTouchesWhenObscured(true);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        view.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) protect(group.getChildAt(i));
        }
    }

    private void button(int id, Runnable action) {
        findViewById(id).setOnClickListener(view -> {
            if (!mBusy && mResumed && isActingUser()) action.run();
        });
    }

    private void showHome() {
        newPage(R.string.clipboard_queue_title, R.layout.clipboard_queue_home);
        mText = findViewById(R.id.clipboard_queue_text);
        button(R.id.clipboard_queue_send_text, () -> chooseText(mText.getText(), true));
        button(R.id.clipboard_queue_import_clipboard, this::importClipboard);
        button(R.id.clipboard_queue_choose_image, () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES,
                            new String[] {"image/png", "image/jpeg", "image/webp"});
            launchPicker(intent, PICK_IMAGE);
        });
        button(R.id.clipboard_queue_open, this::openInbox);
        button(R.id.clipboard_queue_about, () -> showAbout(null));
    }

    private void showAbout(Runnable onReturn) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        View content = LayoutInflater.from(builder.getContext())
                .inflate(R.layout.clipboard_queue_about, null, false);
        show(builder.setTitle(R.string.clipboard_queue_about)
                .setView(content)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (onReturn != null) onReturn.run();
                })
                .setOnCancelListener(dialog -> {
                    if (onReturn != null) onReturn.run();
                }));
    }

    private void importClipboard() {
        try {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0 || clip.getItemCount() > MAX_INBOX_ITEMS) {
                toast(R.string.clipboard_queue_clipboard_error);
                return;
            }
            boolean sensitive = clip.getDescription().getExtras() != null
                    && clip.getDescription().getExtras().getBoolean(
                            ClipDescription.EXTRA_IS_SENSITIVE, false);
            String[] labels = new String[clip.getItemCount()];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = getString(R.string.clipboard_queue_clipboard_item, i + 1);
            }
            show(new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.clipboard_queue_import_clipboard)
                    .setItems(labels, (dialog, which) -> {
                        ClipData.Item item = clip.getItemAt(which);
                        if (item.getIntent() != null) {
                            toast(R.string.clipboard_queue_clipboard_error);
                        } else if (item.getUri() != null) {
                            importImage(item.getUri(), clip.getDescription(), sensitive);
                        } else if (item.getText() != null) {
                            chooseText(item.getText(), sensitive);
                        } else {
                            toast(R.string.clipboard_queue_clipboard_error);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null));
        } catch (RuntimeException e) {
            logFailure("import_clipboard", e);
            toast(R.string.clipboard_queue_clipboard_error);
        }
    }

    private static void validateUri(Uri uri) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme())
                || uri.getAuthority() == null || uri.getAuthority().isEmpty()
                || uri.getAuthority().contains("@") || uri.getEncodedAuthority().contains("@")) {
            throw new IOException("Unsupported document");
        }
    }

    private Uri uriForCurrentUser(Uri uri) throws IOException {
        if (uri != null && uri.getAuthority() != null && uri.getAuthority().contains("@")) {
            if (!Integer.toString(getUserId()).equals(uri.getUserInfo())) {
                throw new IOException("Unsupported document");
            }
            uri = ContentProvider.getUriWithoutUserId(uri);
        }
        validateUri(uri);
        return uri;
    }

    private String imageMimeType(ClipDescription description, String detected) {
        String mime = null;
        if (description != null) {
            for (int i = 0; i < description.getMimeTypeCount(); i++) {
                String candidate = description.getMimeType(i);
                if (isSupportedMimeType(candidate) && !"text/plain".equals(candidate)) {
                    if (mime == null) mime = candidate;
                    if (candidate.equals(detected)) {
                        mime = candidate;
                        break;
                    }
                }
            }
        }
        if (!isSupportedMimeType(mime) || "text/plain".equals(mime)) mime = detected;
        if (detected == null || !detected.equals(mime)) {
            throw new ServiceSpecificException(ERROR_INVALID);
        }
        return mime;
    }

    private void importImage(Uri uri, ClipDescription description, boolean sensitive) {
        work("import_image", () -> {
            Uri imageUri = uriForCurrentUser(uri);
            Operation operation = mWorkerOperation.get();
            operation.beginOpen();
            AssetFileDescriptor opened;
            try {
                opened = getContentResolver().openAssetFileDescriptor(imageUri, "r",
                        operation.signal);
                operation.track(opened);
            } finally {
                operation.endOpen();
            }
            if (opened == null) throw new IOException("Document unavailable");
            try (AssetFileDescriptor descriptor = opened;
                    InputStream stream = operation.track(descriptor.createInputStream());
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[CHUNK_BYTES];
                int headerSize = 0;
                while (headerSize < 12) {
                    requireActingUser();
                    int size = stream.read(buffer, headerSize, 12 - headerSize);
                    if (size == -1) break;
                    headerSize += size;
                }
                String mime = imageMimeType(description,
                        sniffImageMimeType(buffer, headerSize));
                output.write(buffer, 0, headerSize);
                int size;
                while (true) {
                    requireActingUser();
                    size = stream.read(buffer);
                    requireActingUser();
                    if (size == -1) break;
                    if (output.size() + size > MAX_ITEM_BYTES) {
                        throw new ServiceSpecificException(ERROR_INVALID);
                    }
                    output.write(buffer, 0, size);
                }
                return new Payload(output.toByteArray(), mime, sensitive);
            } catch (IOException | SecurityException e) {
                requireActingUser();
                if (description != null) throw new ClipboardImageReadException();
                throw e;
            }
        }, this::chooseTarget, R.string.clipboard_queue_import_error);
    }

    private void chooseText(CharSequence text, boolean sensitive) {
        if (text == null || text.length() == 0 || text.length() > MAX_TEXT_BYTES) {
            toast(R.string.clipboard_queue_invalid);
            return;
        }
        chooseTarget(new Payload(text.toString().getBytes(StandardCharsets.UTF_8),
                "text/plain", sensitive));
    }

    private void chooseTarget(Payload payload) {
        if (payload.data.length == 0 || payload.data.length > MAX_ITEM_BYTES
                || ("text/plain".equals(payload.mime) && payload.data.length > MAX_TEXT_BYTES)) {
            toast(R.string.clipboard_queue_invalid);
            return;
        }
        work("get_targets", () -> queue().getTargets(), targets -> {
            if (targets.length == 0) {
                toast(R.string.clipboard_queue_no_targets);
                return;
            }
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this,
                    R.style.ThemeOverlay_SystemUI_ClipboardQueue_Picker);
            if (builder.getBackground() instanceof MaterialShapeDrawable background) {
                background.setStroke(getResources().getDisplayMetrics().density,
                        builder.getContext().getColor(R.color.clipboard_queue_outline));
            }
            View root = LayoutInflater.from(builder.getContext())
                    .inflate(R.layout.clipboard_share_dialog, null, false);
            TextView title = root.findViewById(R.id.clipboard_share_title);
            title.setText(R.string.clipboard_queue_choose_target);
            RecyclerView list = root.findViewById(R.id.clipboard_user_list);
            list.setLayoutManager(new LinearLayoutManager(builder.getContext()));
            int rowHeight = Math.round(72 * getResources().getDisplayMetrics().density);
            View page = findViewById(R.id.clipboard_queue_root);
            int availableHeight = page.getHeight() - page.getPaddingTop() - page.getPaddingBottom();
            list.getLayoutParams().height = Math.min(targets.length * rowHeight,
                    Math.max(rowHeight, availableHeight / 2));
            list.setAdapter(new TargetAdapter(targets, target -> {
                if (mBusy || !mResumed || !isActingUser()) return;
                confirmSend(payload, target);
            }));
            root.findViewById(R.id.clipboard_share_cancel).setOnClickListener(view -> {
                if (mDialog != null) mDialog.dismiss();
            });
            protect(root);
            show(builder.setView(root));
            if (mDialog != null && mDialog.isShowing()) {
                int width = getResources().getDimensionPixelSize(R.dimen.large_dialog_width);
                if (width > 0) {
                    Rect padding = new Rect();
                    mDialog.getWindow().getDecorView().getBackground().getPadding(padding);
                    width += padding.left + padding.right;
                }
                mDialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }, R.string.clipboard_queue_unavailable);
    }

    private final class TargetAdapter extends RecyclerView.Adapter<TargetAdapter.Holder> {
        private final ClipboardQueueTarget[] mTargets;
        private final Consumer<ClipboardQueueTarget> mSelected;

        TargetAdapter(ClipboardQueueTarget[] targets, Consumer<ClipboardQueueTarget> selected) {
            mTargets = targets;
            mSelected = selected;
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int type) {
            View row = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.clipboard_share_user_item, parent, false);
            protect(row);
            row.setFocusable(true);
            return new Holder(row);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            ClipboardQueueTarget target = mTargets[position];
            holder.target = target;
            holder.status.setVisibility(View.GONE);
            boolean duplicate = false;
            for (ClipboardQueueTarget other : mTargets) {
                if (other.userId != target.userId && target.name.equals(other.name)) {
                    duplicate = true;
                    break;
                }
            }
            holder.name.setText(target.name.isEmpty() || duplicate ? targetName(target)
                    : BidiFormatter.getInstance().unicodeWrap(target.name));
            holder.itemView.setOnClickListener(view -> mSelected.accept(target));
            holder.icon.setImageResource(com.android.settingslib.R.drawable.ic_account_circle);
            int generation = mGeneration;
            mWorker.execute(() -> {
                try {
                    requireActingUser();
                    UserManager users = getSystemService(UserManager.class);
                    if (users == null || users.getUserSerialNumber(target.userId)
                            != target.serialNumber) return;
                    boolean running = users.isUserRunning(target.userId);
                    Bitmap icon = users.getUserIcon(target.userId);
                    if (users.getUserSerialNumber(target.userId)
                            != target.serialNumber) return;
                    mHandler.post(() -> {
                        if (generation == mGeneration && mResumed && isActingUser()
                                && holder.target == target && holder.itemView.isAttachedToWindow()) {
                            if (icon != null) holder.icon.setImageBitmap(icon);
                            holder.status.setText(R.string.clipboard_share_not_running);
                            holder.status.setVisibility(running ? View.GONE : View.VISIBLE);
                        }
                    });
                } catch (RuntimeException e) {
                    logFailure("target_icon", e);
                }
            });
        }

        @Override
        public int getItemCount() {
            return mTargets.length;
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView status;
            ClipboardQueueTarget target;

            Holder(View view) {
                super(view);
                icon = view.findViewById(R.id.icon);
                name = view.findViewById(R.id.name);
                status = view.findViewById(R.id.status);
            }
        }
    }

    private String targetName(ClipboardQueueTarget target) {
        return getString(R.string.clipboard_queue_profile,
                BidiFormatter.getInstance().unicodeWrap(target.name), target.userId);
    }

    private void confirmSend(Payload payload, ClipboardQueueTarget target) {
        show(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clipboard_queue_send)
                .setMessage(getString(R.string.clipboard_queue_confirm_send, targetName(target)))
                .setNeutralButton(R.string.clipboard_queue_conditions,
                        (dialog, which) -> showAbout(() -> confirmSend(payload, target)))
                .setPositiveButton(R.string.clipboard_queue_send,
                        (dialog, which) -> send(payload, target))
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void send(Payload payload, ClipboardQueueTarget target) {
        work("send", () -> {
            IClipboardQueue queue = queue();
            String id = null;
            boolean committing = false;
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.data);
                id = queue.beginInsert(target.userId, target.serialNumber, payload.mime,
                        payload.data.length, hash, payload.sensitive);
                for (int offset = 0; offset < payload.data.length; offset += CHUNK_BYTES) {
                    requireActingUser();
                    queue.append(id, offset, Arrays.copyOfRange(payload.data, offset,
                            Math.min(offset + CHUNK_BYTES, payload.data.length)));
                }
                requireActingUser();
                committing = true;
                queue.commitInsert(id);
                return true;
            } catch (Exception e) {
                logFailure(committing ? "commit_insert" : "upload", e);
                if (e instanceof ServiceSpecificException refused
                        && (refused.errorCode == ERROR_EXPORT_BLOCKED
                        || refused.errorCode == ERROR_IMPORT_BLOCKED
                        || refused.errorCode == ERROR_TARGET_IMPORT_BLOCKED)) {
                    committing = false;
                }
                if (committing) throw new UncertainDeliveryException();
                throw e;
            } finally {
                if (id != null && !committing) {
                    try {
                        queue.abortInsert(id);
                    } catch (Exception e) {
                        logFailure("abort_insert", e);
                    }
                }
            }
        }, result -> {
            if (mText != null) mText.setText("");
            toast(R.string.clipboard_queue_sent);
        }, R.string.clipboard_queue_send_error);
    }

    private void openInbox() {
        work("list_inbox", () -> queue().listInbox(), inbox -> {
            ClipboardQueueEntry[] entries = inbox.entries;
            newPage(R.string.clipboard_queue_inbox, R.layout.clipboard_queue_inbox);
            button(R.id.clipboard_queue_compose, this::showHome);
            button(R.id.clipboard_queue_refresh, this::openInbox);
            findViewById(R.id.clipboard_queue_empty).setVisibility(
                    entries.length == 0 ? View.VISIBLE : View.GONE);
            if (inbox.lastExpiredAt != 0) {
                TextView notice = findViewById(R.id.clipboard_queue_expiry_notice);
                notice.setText(getString(R.string.clipboard_queue_expiry_notice,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(new Date(inbox.lastExpiredAt))));
                notice.setVisibility(View.VISIBLE);
            }
            ViewGroup rows = findViewById(R.id.clipboard_queue_rows);
            for (ClipboardQueueEntry entry : entries) {
                View row = getLayoutInflater().inflate(R.layout.clipboard_queue_inbox_item,
                        rows, false);
                TextView label = row.findViewById(R.id.clipboard_queue_entry_label);
                label.setText(entryStaticLabel(entry));
                TextView remaining = row.findViewById(R.id.clipboard_queue_entry_remaining);
                remaining.setText(remainingLabel(entry));
                protect(row);
                row.setOnClickListener(view -> {
                    if (!mBusy && mResumed && isActingUser()) showEntry(entry);
                });
                View delete = row.findViewById(R.id.clipboard_queue_delete);
                protect(delete);
                delete.setOnClickListener(view -> {
                    if (!mBusy && mResumed && isActingUser()) confirmDelete(entry);
                });
                rows.addView(row);
                mInboxRows.put(remaining, entry);
            }
            mCountdown.run();
            findViewById(R.id.clipboard_queue_clear).setVisibility(
                    entries.length != 0 || inbox.lastExpiredAt != 0 ? View.VISIBLE : View.GONE);
            button(R.id.clipboard_queue_clear, () -> confirmClear(this::openInbox));
        }, R.string.clipboard_queue_unavailable);
    }

    private void confirmDelete(ClipboardQueueEntry entry) {
        show(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clipboard_queue_delete)
                .setMessage(R.string.clipboard_queue_delete_confirm)
                .setPositiveButton(R.string.clipboard_queue_delete,
                        (dialog, which) -> delete(entry, false))
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void confirmClear(Runnable afterClear) {
        show(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clipboard_queue_clear)
                .setMessage(R.string.clipboard_queue_clear_confirm)
                .setPositiveButton(R.string.clipboard_queue_clear, (dialog, which) ->
                        work("clear_inbox", () -> {
                            queue().clearInbox();
                            return true;
                        }, result -> afterClear.run(), R.string.clipboard_queue_change_error))
                .setNegativeButton(android.R.string.cancel, null));
    }

    private String entryStaticLabel(ClipboardQueueEntry entry) {
        DateFormat date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        String sender = BidiFormatter.getInstance().unicodeWrap(
                entry.senderName == null ? "" : entry.senderName);
        if (entry.senderNameAmbiguous) {
            sender = getString(R.string.clipboard_queue_profile, sender, entry.senderId);
        }
        return getString(R.string.clipboard_queue_row_type, entry.mimeType,
                Formatter.formatFileSize(this, entry.size))
                + "\n" + getString(R.string.clipboard_queue_row_from, sender)
                + "\n" + getString(R.string.clipboard_queue_row_sent,
                        date.format(new Date(entry.createdAt)))
                + "\n" + getString(R.string.clipboard_queue_row_expires,
                        date.format(new Date(entry.expiresAt)));
    }

    private static boolean expired(ClipboardQueueEntry entry) {
        return SystemClock.elapsedRealtime() >= entry.expiresElapsedRealtime;
    }

    private String remainingLabel(ClipboardQueueEntry entry) {
        long remaining = entry.expiresElapsedRealtime - SystemClock.elapsedRealtime();
        return remaining <= 0 ? getString(R.string.clipboard_queue_expired)
                : getString(R.string.clipboard_queue_remaining,
                        DateUtils.formatElapsedTime((remaining + 999) / 1000));
    }

    private String entryMessage(ClipboardQueueEntry entry, String staticLabel) {
        return staticLabel + "\n" + remainingLabel(entry);
    }

    private void showEntry(ClipboardQueueEntry entry) {
        if (expired(entry)) {
            toast(R.string.clipboard_queue_expired);
            openInbox();
            return;
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        View actions = LayoutInflater.from(builder.getContext())
                .inflate(R.layout.clipboard_queue_copy, null, false);
        entryAction(actions, R.id.clipboard_queue_copy, () -> copy(entry));
        entryAction(actions, R.id.clipboard_queue_cut, () -> cut(entry));
        actions.findViewById(R.id.clipboard_queue_cut).setVisibility(
                "text/plain".equals(entry.mimeType) ? View.VISIBLE : View.GONE);
        entryAction(actions, R.id.clipboard_queue_save, () -> saveEntry(entry));
        entryAction(actions, R.id.clipboard_queue_cancel, () -> {});
        String staticLabel = entryStaticLabel(entry);
        show(builder.setView(actions)
                .setTitle(R.string.clipboard_queue_item)
                .setMessage(entryMessage(entry, staticLabel)));
        mDisplayedEntry = entry;
        mDisplayedStaticLabel = staticLabel;
    }

    private void entryAction(View actions, int id, Runnable action) {
        View button = actions.findViewById(id);
        protect(button);
        button.setOnClickListener(view -> {
            if (mBusy || !mResumed || !isActingUser()) return;
            if (mDialog != null) mDialog.dismiss();
            mDisplayedEntry = null;
            action.run();
        });
    }

    private void saveEntry(ClipboardQueueEntry entry) {
        mExportEntry = entry;
        String suffix = switch (entry.mimeType) {
            case "text/plain" -> ".txt";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> "";
        };
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(entry.mimeType)
                .putExtra(Intent.EXTRA_TITLE, "profile-message-" + entry.id + suffix);
        launchPicker(intent, SAVE_ITEM);
    }

    private void cut(ClipboardQueueEntry entry) {
        if (!"text/plain".equals(entry.mimeType)) {
            show(new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.clipboard_queue_cut)
                    .setMessage(R.string.clipboard_queue_cut_image_unavailable)
                    .setPositiveButton(R.string.clipboard_queue_save,
                            (dialog, which) -> saveEntry(entry))
                    .setNegativeButton(android.R.string.cancel, null));
            return;
        }
        copy(entry, () -> delete(entry, true));
    }

    private void delete(ClipboardQueueEntry entry, boolean afterCopy) {
        work("delete", () -> {
            try {
                queue().delete(entry.id);
                return 0;
            } catch (Exception e) {
                if (!afterCopy) throw e;
                logFailure("cut_delete", e);
                for (ClipboardQueueEntry remaining : queue().listInbox().entries) {
                    if (entry.id.equals(remaining.id)) {
                        return R.string.clipboard_queue_cut_delete_error;
                    }
                }
                return R.string.clipboard_queue_cut_delete_uncertain;
            }
        }, message -> {
            if (message != 0) toast(message);
            else openInbox();
        }, afterCopy ? R.string.clipboard_queue_cut_delete_uncertain
                : R.string.clipboard_queue_change_error);
    }

    private void launchPicker(Intent intent, int request) {
        try {
            intent.setPackage(DocumentsContract.PACKAGE_DOCUMENTS_UI);
            ResolveInfo resolved = getPackageManager().resolveActivity(intent,
                    PackageManager.MATCH_SYSTEM_ONLY);
            if (resolved == null || resolved.activityInfo == null
                    || !DocumentsContract.PACKAGE_DOCUMENTS_UI.equals(
                            resolved.activityInfo.packageName)
                    || (resolved.activityInfo.applicationInfo.flags
                            & ApplicationInfo.FLAG_SYSTEM) == 0) {
                throw new SecurityException("System document picker unavailable");
            }
            intent.setClassName(resolved.activityInfo.packageName, resolved.activityInfo.name);
            mPicker = request;
            startActivityForResult(intent, request);
        } catch (RuntimeException e) {
            logFailure("launch_picker", e);
            mPicker = 0;
            mExportEntry = null;
            toast(R.string.clipboard_queue_picker_error);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != mPicker) return;
        if (result == RESULT_OK && data != null && data.getData() != null) {
            try {
                int grant = request == SAVE_ITEM ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        : Intent.FLAG_GRANT_READ_URI_PERMISSION;
                Uri uri = uriForCurrentUser(data.getData());
                if ((data.getFlags() & grant) == 0
                        || !DocumentsContract.isDocumentUri(this, uri)
                        || checkUriPermission(uri, Process.myPid(), Process.myUid(), grant)
                                != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Document grant unavailable");
                }
                mPickerResult = uri;
                return;
            } catch (IOException | RuntimeException e) {
                logFailure("picker_result", e);
                mPickerRejected = true;
            }
        }
        mPickerResult = null;
        mPicker = 0;
        mExportEntry = null;
    }

    private void save(ClipboardQueueEntry entry, Uri uri) {
        work("save_copy", () -> {
            Uri destination = uriForCurrentUser(uri);
            Operation operation = mWorkerOperation.get();
            operation.expiresAt(entry.expiresElapsedRealtime);
            byte[] data = readEntry(entry);
            requireActingUser();
            if (expired(entry)) throw new ExpiredItemException();
            operation.beginOpen();
            ParcelFileDescriptor descriptor;
            try {
                descriptor = getContentResolver().openFileDescriptor(destination, "wt",
                        operation.signal);
                operation.track(descriptor);
            } finally {
                operation.endOpen();
            }
            if (descriptor == null) throw new IOException("Destination unavailable");
            try (ParcelFileDescriptor.AutoCloseOutputStream stream = operation.track(
                    new ParcelFileDescriptor.AutoCloseOutputStream(descriptor))) {
                for (int offset = 0; offset < data.length; offset += CHUNK_BYTES) {
                    requireActingUser();
                    if (expired(entry)) throw new ExpiredItemException();
                    stream.write(data, offset, Math.min(CHUNK_BYTES, data.length - offset));
                }
                requireActingUser();
                stream.flush();
                requireActingUser();
                stream.getFD().sync();
                requireActingUser();
            }
            return true;
        }, result -> toast(R.string.clipboard_queue_saved), R.string.clipboard_queue_save_error);
    }

    private byte[] readEntry(ClipboardQueueEntry entry) throws Exception {
        mWorkerOperation.get().expiresAt(entry.expiresElapsedRealtime);
        if (entry.size <= 0 || entry.size > MAX_ITEM_BYTES) {
            throw new IOException("Invalid queued item size");
        }
        IClipboardQueue queue = queue();
        byte[] data = new byte[entry.size];
        for (int offset = 0; offset < data.length; offset += CHUNK_BYTES) {
            requireActingUser();
            if (expired(entry)) throw new ExpiredItemException();
            byte[] chunk;
            try {
                chunk = queue.read(entry.id, offset);
            } catch (ServiceSpecificException e) {
                logFailure("read", e);
                if (e.errorCode == ERROR_MISSING && expired(entry)) {
                    throw new ExpiredItemException();
                }
                throw e;
            }
            if (chunk == null || chunk.length != Math.min(CHUNK_BYTES, data.length - offset)) {
                throw new IOException("Incomplete item");
            }
            System.arraycopy(chunk, 0, data, offset, chunk.length);
        }
        if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(data),
                entry.sha256)) {
            throw new IOException("Item checksum mismatch");
        }
        return data;
    }

    private void copy(ClipboardQueueEntry entry) {
        copy(entry, () -> toast(R.string.clipboard_queue_copied));
    }

    private void copy(ClipboardQueueEntry entry, Runnable afterCopy) {
        work("copy", () -> {
            if (!isSupportedMimeType(entry.mimeType)
                    || ("text/plain".equals(entry.mimeType) && entry.size > MAX_TEXT_BYTES)) {
                throw new IOException("Invalid queued item size");
            }
            byte[] data = readEntry(entry);
            ClipData clip;
            ClipboardQueueProvider provider = null;
            Uri uri = null;
            if ("text/plain".equals(entry.mimeType)) {
                String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data)).toString();
                clip = ClipData.newPlainText(getString(R.string.clipboard_queue_item), text);
            } else {
                requireActingUser();
                if (expired(entry)) throw new ExpiredItemException();
                try (ContentProviderClient client = getContentResolver()
                        .acquireContentProviderClient(ClipboardQueueProvider.AUTHORITY)) {
                    if (client == null || !(client.getLocalContentProvider()
                            instanceof ClipboardQueueProvider local)) {
                        throw new CopyProviderException();
                    }
                    provider = local;
                    try {
                        uri = provider.store(data, entry.mimeType);
                    } catch (IOException | RuntimeException e) {
                        throw new CopyImageWriteException();
                    }
                    try (ParcelFileDescriptor descriptor = client.openFile(uri, "r")) {
                        if (descriptor == null || !entry.mimeType.equals(client.getType(uri))) {
                            throw new CopyProviderException();
                        }
                    } catch (IOException | RuntimeException e) {
                        throw new CopyProviderException();
                    }
                    clip = new ClipData(getString(R.string.clipboard_queue_item),
                            new String[] {entry.mimeType}, new ClipData.Item(uri));
                } catch (Exception e) {
                    if (provider != null && uri != null) provider.discard(uri);
                    if (e instanceof CopyImageWriteException) throw e;
                    throw new CopyProviderException();
                }
            }
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, entry.sensitive);
            extras.putBoolean(ClipboardListener.EXTRA_SUPPRESS_OVERLAY, true);
            clip.getDescription().setExtras(extras);
            return new PendingCopy(clip, provider, uri);
        }, pending -> {
            if (expired(entry)) {
                toast(R.string.clipboard_queue_expired);
                return;
            }
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard == null) {
                toast(R.string.clipboard_queue_copy_error);
                return;
            }
            clipboard.setPrimaryClip(pending.clip);
            pending.published = true;
            afterCopy.run();
        }, R.string.clipboard_queue_copy_error, pending -> {
            if (pending != null && !pending.published && pending.provider != null) {
                pending.provider.discard(pending.uri);
            }
        });
    }

    private static final class PendingCopy {
        final ClipData clip;
        final ClipboardQueueProvider provider;
        final Uri uri;
        boolean published;

        PendingCopy(ClipData clip, ClipboardQueueProvider provider, Uri uri) {
            this.clip = clip;
            this.provider = provider;
            this.uri = uri;
        }
    }

    private void setBusy(boolean busy) {
        mBusy = busy;
        LinearProgressIndicator progress = findViewById(R.id.clipboard_queue_progress);
        if (busy) {
            if (progress.getIndeterminateDrawable().isHiding()) {
                progress.setVisibility(View.INVISIBLE);
            }
            progress.show();
        } else {
            progress.hide();
        }
    }

    private interface Task<T> {
        T run() throws Exception;
    }

    private <T> void work(String operation, Task<T> task, Consumer<T> success, int failure) {
        work(operation, task, success, failure, result -> {});
    }

    private <T> void work(String operation, Task<T> task, Consumer<T> success, int failure,
            Consumer<T> cleanup) {
        if (mBusy || !mResumed || !isActingUser()) return;
        setBusy(true);
        int generation = mGeneration;
        Operation current = new Operation(operation, generation);
        mOperation = current;
        mWorker.execute(() -> {
            mWorkerOperation.set(current);
            T value = null;
            int error = 0;
            try {
                requireActingUser();
                value = current.hasProviderIo() ? current.runProvider(task) : task.run();
                requireActingUser();
            } catch (Exception e) {
                logFailure(operation, e);
                error = "save_copy".equals(operation) ? R.string.clipboard_queue_save_error
                        : "send".equals(operation) && e instanceof OperationCanceledException
                                ? R.string.clipboard_queue_uncertain : errorMessage(e, failure);
            } finally {
                current.finish();
                mWorkerOperation.remove();
            }
            final T result = value;
            final int message = error;
            runOnUiThread(() -> {
                try {
                    if (mOperation == current) {
                        mOperation = null;
                        setBusy(false);
                    }
                    if (generation != mGeneration || isFinishing() || isDestroyed()) return;
                    if (!mResumed) return;
                    if (!isActingUser()) {
                        closeInbox();
                    } else if (message != 0) {
                        toast(message);
                    } else {
                        try {
                            success.accept(result);
                        } catch (RuntimeException e) {
                            logFailure(operation + "/display", e);
                            toast(errorMessage(e, failure));
                        }
                    }
                } finally {
                    cleanup.accept(result);
                }
            });
        });
    }

    private void cancelWork() {
        mGeneration++;
        if (mOperation != null) {
            if ("save_copy".equals(mOperation.name)) {
                if (mResumed) toast(R.string.clipboard_queue_save_error);
                else mInterruptedSave = true;
            }
            mOperation.cancel();
        }
    }

    private final class Operation {
        final String name;
        final int generation;
        final CancellationSignal signal = new CancellationSignal();
        final ArrayList<Closeable> resources = new ArrayList<>();
        final Runnable timeout = this::cancel;
        final Runnable openTimeout = this::cancel;
        FutureTask<?> providerTask;
        volatile boolean cancelled;
        volatile long deadline = Long.MAX_VALUE;
        long openDeadline = Long.MAX_VALUE;

        Operation(String name, int generation) {
            this.name = name;
            this.generation = generation;
            if (hasProviderIo()) {
                deadline = SystemClock.elapsedRealtime() + TRANSFER_MILLIS;
                mHandler.postDelayed(timeout, TRANSFER_MILLIS);
            }
        }

        boolean hasProviderIo() {
            return "import_image".equals(name) || "save_copy".equals(name);
        }

        <T> T runProvider(Task<T> task) throws Exception {
            FutureTask<T> future = new FutureTask<>(() -> {
                mWorkerOperation.set(this);
                try {
                    requireActingUser();
                    return task.run();
                } finally {
                    mWorkerOperation.remove();
                }
            });
            synchronized (resources) {
                check();
                providerTask = future;
            }
            try {
                sProviderWorker.execute(future);
                return future.get(Math.max(1, deadline - SystemClock.elapsedRealtime()),
                        TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Exception cause) throw cause;
                if (e.getCause() instanceof Error cause) throw cause;
                throw new IOException("Document unavailable");
            } catch (InterruptedException | TimeoutException e) {
                cancel();
                throw new OperationCanceledException();
            }
        }

        void check() {
            if (cancelled || generation != mGeneration
                    || SystemClock.elapsedRealtime() >= Math.min(deadline, openDeadline)) {
                cancel();
                throw new OperationCanceledException();
            }
        }

        void expiresAt(long expiry) {
            deadline = Math.min(deadline, expiry);
            mHandler.removeCallbacks(timeout);
            mHandler.postDelayed(timeout, Math.max(0, deadline - SystemClock.elapsedRealtime()));
            check();
        }

        void beginOpen() {
            check();
            openDeadline = SystemClock.elapsedRealtime() + PROVIDER_OPEN_MILLIS;
            mHandler.postDelayed(openTimeout, PROVIDER_OPEN_MILLIS);
        }

        void endOpen() {
            mHandler.removeCallbacks(openTimeout);
            check();
            openDeadline = Long.MAX_VALUE;
        }

        <T extends Closeable> T track(T resource) throws IOException {
            if (resource == null) return null;
            synchronized (resources) {
                if (!cancelled) {
                    resources.add(resource);
                    return resource;
                }
            }
            resource.close();
            throw new OperationCanceledException();
        }

        void cancel() {
            ArrayList<Closeable> closing;
            FutureTask<?> task;
            synchronized (resources) {
                if (cancelled) return;
                cancelled = true;
                closing = new ArrayList<>(resources);
                resources.clear();
                task = providerTask;
            }
            if (task != null) task.cancel(true);
            mHandler.removeCallbacks(timeout);
            mHandler.removeCallbacks(openTimeout);
            // Provider cancellation may block, so descriptor closure uses an independent worker.
            if (!closing.isEmpty()) sCloseWorker.execute(() -> {
                for (Closeable resource : closing) {
                    try {
                        resource.close();
                    } catch (IOException | RuntimeException e) {
                        logFailure("cancel_close", e);
                    }
                }
            });
            if (task != null) sCancelWorker.execute(() -> {
                try {
                    signal.cancel();
                } catch (RuntimeException e) {
                    logFailure("cancel_provider", e);
                }
            });
        }

        void finish() {
            mHandler.removeCallbacks(timeout);
            mHandler.removeCallbacks(openTimeout);
            synchronized (resources) {
                if (providerTask == null || !providerTask.isCancelled()) resources.clear();
            }
        }
    }

    private static int errorMessage(Exception e, int fallback) {
        if (fallback == R.string.clipboard_queue_cut_delete_uncertain) return fallback;
        if (e instanceof CharacterCodingException) return R.string.clipboard_queue_copy_invalid_text;
        if (e instanceof CopyImageWriteException) return R.string.clipboard_queue_copy_image_error;
        if (e instanceof CopyProviderException) return R.string.clipboard_queue_copy_provider_error;
        if (e instanceof ClipboardImageReadException) {
            return R.string.clipboard_queue_clipboard_image_unreadable;
        }
        if (e instanceof ExpiredItemException) return R.string.clipboard_queue_expired;
        if (e instanceof UncertainDeliveryException) return R.string.clipboard_queue_uncertain;
        if (e instanceof ServiceNotFoundException) return R.string.clipboard_queue_service_missing;
        if (e instanceof SecurityException) return R.string.clipboard_queue_not_authorized;
        if (e instanceof RemoteException) return R.string.clipboard_queue_connection_error;
        if (e instanceof ServiceSpecificException refused) {
            return switch (refused.errorCode) {
                case ERROR_FULL -> R.string.clipboard_queue_full;
                case ERROR_INVALID -> R.string.clipboard_queue_invalid;
                case ERROR_MISSING -> R.string.clipboard_queue_missing;
                case ERROR_EXPORT_BLOCKED -> R.string.clipboard_queue_export_blocked;
                case ERROR_IMPORT_BLOCKED -> R.string.clipboard_queue_import_blocked;
                case ERROR_TARGET_IMPORT_BLOCKED -> R.string.clipboard_queue_target_import_blocked;
                case ERROR_UNAVAILABLE -> R.string.clipboard_queue_unavailable;
                case ERROR_STORAGE -> R.string.clipboard_queue_storage_error;
                default -> fallback;
            };
        }
        return fallback;
    }

    private static void logFailure(String operation, Exception e) {
        // Document providers and clipboard implementations may include private content in errors.
        String message = e.getMessage();
        if (!isLocalDiagnostic(message)) message = "[external message redacted]";
        String code = e instanceof ServiceSpecificException refused
                ? " (code " + refused.errorCode + ")" : "";
        Log.e(TAG, operation + " failed: " + e.getClass().getName() + ": " + message + code);
    }

    private static boolean isLocalDiagnostic(String message) {
        if (message == null) return true;
        return switch (message) {
            case "clipboard_queue service not found", "Queue item expired",
                    "Queue delivery status unknown", "Unsupported document", "Document unavailable",
                    "Invalid queued item size", "Incomplete item", "Item checksum mismatch",
                    "Destination unavailable", "Activity is not the foreground user",
                    "Activity UserManager unavailable", "Activity KeyguardManager unavailable",
                    "Activity user is not unlocked", "Activity device is locked",
                    "Activity work interrupted" -> true;
            default -> false;
        };
    }

    private void show(MaterialAlertDialogBuilder builder) {
        if (!mResumed || !isActingUser()) return;
        if (mDialog != null) mDialog.dismiss();
        mDisplayedEntry = null;
        mDialog = builder.create();
        AlertDialog shown = mDialog;
        shown.setOnDismissListener(dialog -> {
            if (mDialog == shown) {
                mDialog = null;
                mDisplayedEntry = null;
            }
        });
        mDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        mDialog.getWindow().setHideOverlayWindows(true);
        mDialog.show();
        protect(mDialog.getWindow().getDecorView());
        for (int button : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_NEUTRAL}) {
            Button view = mDialog.getButton(button);
            if (view != null) view.setFilterTouchesWhenObscured(true);
        }
    }

    private void toast(int message) {
        if (mResumed && isActingUser()) {
            Snackbar snackbar = Snackbar.make(findViewById(R.id.clipboard_queue_root),
                    message, Snackbar.LENGTH_LONG)
                    .setTextMaxLines(4)
                    .setAction(android.R.string.ok, view -> {});
            protect(snackbar.getView());
            snackbar.show();
        }
    }

    private static final class Payload {
        final byte[] data;
        final String mime;
        final boolean sensitive;

        Payload(byte[] data, String mime, boolean sensitive) {
            this.data = data;
            this.mime = mime;
            this.sensitive = sensitive;
        }
    }

    private static final class ClipboardImageReadException extends IOException {}

    private static final class CopyProviderException extends IOException {}

    private static final class CopyImageWriteException extends IOException {}

    private static final class ServiceNotFoundException extends IOException {
        ServiceNotFoundException() {
            super("clipboard_queue service not found");
        }
    }

    private static final class ExpiredItemException extends Exception {
        ExpiredItemException() {
            super("Queue item expired");
        }
    }

    private static final class UncertainDeliveryException extends Exception {
        UncertainDeliveryException() {
            super("Queue delivery status unknown");
        }
    }
}
