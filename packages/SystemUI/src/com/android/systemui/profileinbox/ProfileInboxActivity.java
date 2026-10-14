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

package com.android.systemui.profileinbox;

import static android.content.ProfileInboxContract.*;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ProfileInboxEntry;
import android.content.ProfileInboxContents;
import android.content.ProfileInboxTarget;
import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.IProfileInbox;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
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
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.DefaultItemAnimator;
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

public final class ProfileInboxActivity extends Activity {
    private static final String TAG = "ProfileInboxActivity";
    private static final String EXTRA_OPEN_INBOX = "com.android.systemui.profileinbox.OPEN_INBOX";
    private static final int MAX_THUMBNAILS_PER_REFRESH = 4;
    private static final long THUMBNAIL_READ_BUDGET = 8L * 1024 * 1024;
    private static final int MAX_THUMBNAIL_EDGE = 192;
    private static final long MAX_THUMBNAIL_DECODE_BYTES = 4L * 1024 * 1024;
    private static final int PICK_IMAGE = 1;
    private static final int SAVE_ITEM = 2;
    private static final int PREVIEW_CHARACTERS = 512;
    private static final int MAX_IMAGE_PREVIEW_EDGE = 2048;
    private static final long PROVIDER_OPEN_MILLIS = 15_000;
    private static final long TRANSFER_MILLIS = 120_000;
    private static final ExecutorService sCancelWorker = Executors.newSingleThreadExecutor();
    private static final ExecutorService sCloseWorker = Executors.newSingleThreadExecutor();
    private static final ThreadPoolExecutor sProviderWorker = new ThreadPoolExecutor(1, 1,
            0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> mThumbnails = new LinkedHashMap<>();
    private RecyclerView mInboxList;
    private InboxAdapter mInboxAdapter;
    private InboxSnapshot mInboxSnapshot;
    private PendingDelete mPendingDelete;
    private PendingDelete mCommittingDelete;
    private Operation mThumbnailOperation;
    private boolean mOpenInboxRequested;
    private boolean mRelaunchPageRequested;
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private ProfileInboxPageLayout mContent;
    private TextInputEditText mText;
    private AlertDialog mDialog;
    private boolean mResumed;
    private boolean mBusy;
    private boolean mShowingInbox;
    private boolean mInitialPageChosen;
    private boolean mRefreshPending;
    private int mInboxCount = -1;
    private ProfileInboxEntry[] mEntries = new ProfileInboxEntry[0];
    private TextView mDisplayedMetadata;
    private int mPicker;
    private volatile int mGeneration;
    private Operation mOperation;
    private final ThreadLocal<Operation> mWorkerOperation = new ThreadLocal<>();
    private boolean mInterruptedSave;
    private boolean mPickerRejected;
    private String mDisplayedStaticLabel;
    private Uri mPickerResult;
    private ProfileInboxEntry mExportEntry;
    private ProfileInboxEntry mDisplayedEntry;

    private final Runnable mCountdown = new Runnable() {
        @Override
        public void run() {
            if (!mResumed || !isActingUser()) return;
            updateInboxCount();
            if (mInboxList != null) {
                for (int i = 0; i < mInboxList.getChildCount(); i++) {
                    RecyclerView.ViewHolder holder = mInboxList.getChildViewHolder(mInboxList.getChildAt(i));
                    if (holder instanceof InboxRow row && row.entry != null) bindRow(row, row.entry);
                }
            }
            if (mDisplayedEntry != null && mDialog != null && mDialog.isShowing()) {
                if (expired(mDisplayedEntry)) {
                    mDialog.dismiss();
                    toast(R.string.profile_inbox_expired);
                } else if (mDisplayedMetadata != null) {
                    mDisplayedMetadata.setText(entryMessage(mDisplayedEntry, mDisplayedStaticLabel));
                }
            }
            if (mInboxCount > 0 || mPendingDelete != null) mHandler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver mGuard = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            closeInbox();
        }
    };

    public static Intent createIntent(Context context, boolean openInbox) {
        return new Intent(context, ProfileInboxActivity.class).putExtra(EXTRA_OPEN_INBOX, openInbox);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mRelaunchPageRequested = true;
        cancelWork();
        mOpenInboxRequested = intent.getBooleanExtra(EXTRA_OPEN_INBOX, false);
        mInitialPageChosen = false;
        mRefreshPending = true;
        if (mDialog != null) mDialog.dismiss();
        runPendingWork();
    }

    @Override
    protected void onCreate(Bundle state) {
        setTheme(R.style.Theme_SystemUI_ProfileInbox);
        super.onCreate(null);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setHideOverlayWindows(true);
        setRecentsScreenshotEnabled(false);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        registerReceiver(mGuard, filter, Context.RECEIVER_NOT_EXPORTED);
        mOpenInboxRequested = getIntent().getBooleanExtra(EXTRA_OPEN_INBOX, false);
        ProfileInboxProvider.pruneOnLaunch(this);
        if (mOpenInboxRequested) {
            ProfileInboxContents inbox = new ProfileInboxContents();
            inbox.entries = mEntries;
            showInbox(new InboxSnapshot(inbox));
        } else {
            showHome();
        }
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
            toast(R.string.profile_inbox_save_error);
        }
        if (mPickerRejected) {
            mPickerRejected = false;
            toast(R.string.profile_inbox_picker_error);
        }
        mHandler.removeCallbacks(mCountdown);
        mCountdown.run();
        mRefreshPending = true;
        runPendingWork();
    }

    private void runPendingWork() {
        if (!mResumed || mBusy || isFinishing() || isDestroyed() || !isActingUser()) return;
        if (mPickerResult != null) {
            Uri uri = mPickerResult;
            mPickerResult = null;
            int picker = mPicker;
            mPicker = 0;
            if (picker == PICK_IMAGE) {
                importImage(uri, null, true);
            } else if (picker == SAVE_ITEM && mExportEntry != null) {
                ProfileInboxEntry entry = mExportEntry;
                mExportEntry = null;
                save(entry, uri);
            }
        } else if (mRefreshPending && mDialog == null) {
            mRefreshPending = false;
            refreshInbox(false);
        }
    }

    @Override
    protected void onPause() {
        mResumed = false;
        if (mContent != null) mContent.resetGesture();
        abandonPendingDelete();
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
        mThumbnails.clear();
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
        if (mContent != null) mContent.resetGesture();
        abandonPendingDelete();
        cancelWork();
        mHandler.removeCallbacks(mCountdown);
        mPickerResult = null;
        mExportEntry = null;
        if (mText != null) mText.setText("");
        if (mContent != null) mContent.removeAllViews();
        mThumbnails.clear();
        mInboxAdapter = null;
        mInboxList = null;
        mInboxSnapshot = null;
        if (mDialog != null) mDialog.dismiss();
        finishAndRemoveTask();
    }

    private IProfileInbox profileInbox() throws IOException {
        requireActingUser();
        IProfileInbox profileInbox = IProfileInbox.Stub.asInterface(
                ServiceManager.getService(SERVICE_NAME));
        if (profileInbox == null) {
            logServiceNotFound();
            throw new ServiceNotFoundException();
        }
        return profileInbox;
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

    private void newPage(int layout) {
        cancelWork();
        mHandler.removeCallbacks(mCountdown);
        mInboxList = null;
        mInboxAdapter = null;
        mDisplayedEntry = null;
        if (mText != null) mText.setText("");
        mText = null;
        if (mContent == null) {
            setContentView(R.layout.profile_inbox_activity);
            View root = findViewById(R.id.profile_inbox_root);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return WindowInsets.CONSUMED;
            });
            MaterialToolbar toolbar = findViewById(R.id.profile_inbox_toolbar);
            toolbar.setTitle(R.string.profile_inbox_title);
            toolbar.setNavigationContentDescription(android.R.string.cancel);
            toolbar.setNavigationOnClickListener(view -> {
                if (mResumed && isActingUser()) closeInbox();
            });
            button(R.id.profile_inbox_send_tab, () -> changePage(false));
            button(R.id.profile_inbox_inbox_tab, () -> changePage(true));
            button(R.id.profile_inbox_about, () -> showAbout(null));
            mContent = findViewById(R.id.profile_inbox_content);
            mContent.setPageSwipe(() -> mResumed && !mBusy && mDialog == null
                    && (mText == null || !mText.hasSelection()),
                    () -> act(() -> changePage(!mShowingInbox)));
            root.requestApplyInsets();
        }
        mContent.resetGesture();
        mContent.removeAllViews();
        getLayoutInflater().inflate(layout, mContent, true);
        View root = findViewById(R.id.profile_inbox_root);
        protect(root);
        updateTabs();
        root.requestFocus();
        ScrollView scroll = findViewById(R.id.profile_inbox_scroll);
        if (scroll != null) scroll.scrollTo(0, 0);
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
        findViewById(id).setOnClickListener(view -> act(action));
    }

    private void act(Runnable action) {
        if (mBusy || !mResumed || !isActingUser()) return;
        if (mPendingDelete != null) commitPendingDelete(action, () -> {});
        else action.run();
    }

    private void showHome() {
        mShowingInbox = false;
        newPage(R.layout.profile_inbox_home);
        mText = findViewById(R.id.profile_inbox_text);
        button(R.id.profile_inbox_send_text, () -> chooseText(mText.getText(), false));
        button(R.id.profile_inbox_import_clipboard, this::importClipboard);
        button(R.id.profile_inbox_choose_image, () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES,
                            new String[] {"image/png", "image/jpeg", "image/webp"});
            launchPicker(intent, PICK_IMAGE);
        });
        mCountdown.run();
    }

    private void showAbout(Runnable onReturn) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        View content = LayoutInflater.from(builder.getContext())
                .inflate(R.layout.profile_inbox_about, null, false);
        show(builder.setTitle(R.string.profile_inbox_about)
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
            if (clip == null || clip.getItemCount() == 0) {
                toast(R.string.profile_inbox_clipboard_empty);
                return;
            }
            boolean sensitive = clip.getDescription().getExtras() != null
                    && clip.getDescription().getExtras().getBoolean(
                            ClipDescription.EXTRA_IS_SENSITIVE, false);
            ClipData.Item item = clip.getItemAt(0);
            if (item.getIntent() != null || item.getIntentSender() != null) {
                toast(R.string.profile_inbox_clipboard_error);
            } else if (item.getUri() != null) {
                importImage(item.getUri(), clip.getDescription(), sensitive);
            } else if (item.getText() == null || item.getText().length() == 0) {
                toast(R.string.profile_inbox_clipboard_empty);
            } else {
                chooseText(item.getText(), sensitive);
            }
        } catch (RuntimeException e) {
            logFailure("import_clipboard", e);
            toast(R.string.profile_inbox_clipboard_error);
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
        }, this::chooseTarget, R.string.profile_inbox_import_error);
    }

    private void chooseText(CharSequence text, boolean sensitive) {
        if (text == null || text.length() == 0 || text.length() > MAX_TEXT_BYTES) {
            toast(R.string.profile_inbox_invalid);
            return;
        }
        chooseTarget(new Payload(text.toString().getBytes(StandardCharsets.UTF_8),
                "text/plain", sensitive));
    }

    private void chooseTarget(Payload payload) {
        if (payload.data.length == 0 || payload.data.length > MAX_ITEM_BYTES
                || ("text/plain".equals(payload.mime) && payload.data.length > MAX_TEXT_BYTES)) {
            toast(R.string.profile_inbox_invalid);
            return;
        }
        work("get_targets", () -> profileInbox().getTargets(), targets -> {
            if (targets.length == 0) {
                toast(R.string.profile_inbox_no_targets);
                return;
            }
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this,
                    R.style.ThemeOverlay_SystemUI_ProfileInbox_Picker);
            if (builder.getBackground() instanceof MaterialShapeDrawable background) {
                background.setStroke(getResources().getDisplayMetrics().density,
                        builder.getContext().getColor(R.color.profile_inbox_outline));
            }
            View root = LayoutInflater.from(builder.getContext())
                    .inflate(R.layout.profile_inbox_target_dialog, null, false);
            TextView title = root.findViewById(R.id.profile_inbox_target_title);
            title.setText(R.string.profile_inbox_choose_target);
            RecyclerView list = root.findViewById(R.id.profile_inbox_target_list);
            list.setLayoutManager(new LinearLayoutManager(builder.getContext()));
            int rowHeight = Math.round(72 * getResources().getDisplayMetrics().density);
            View page = findViewById(R.id.profile_inbox_root);
            int availableHeight = page.getHeight() - page.getPaddingTop() - page.getPaddingBottom();
            list.getLayoutParams().height = Math.min(targets.length * rowHeight,
                    Math.max(rowHeight, availableHeight / 2));
            list.setAdapter(new TargetAdapter(targets, target -> {
                if (mBusy || !mResumed || !isActingUser()) return;
                confirmSend(payload, target);
            }));
            root.findViewById(R.id.profile_inbox_target_cancel).setOnClickListener(view -> {
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
        }, R.string.profile_inbox_unavailable);
    }

    private final class TargetAdapter extends RecyclerView.Adapter<TargetAdapter.Holder> {
        private final ProfileInboxTarget[] mTargets;
        private final Consumer<ProfileInboxTarget> mSelected;

        TargetAdapter(ProfileInboxTarget[] targets, Consumer<ProfileInboxTarget> selected) {
            mTargets = targets;
            mSelected = selected;
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int type) {
            View row = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.profile_inbox_target_user_item, parent, false);
            protect(row);
            row.setFocusable(true);
            return new Holder(row);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            ProfileInboxTarget target = mTargets[position];
            holder.target = target;
            holder.status.setVisibility(View.GONE);
            boolean duplicate = false;
            for (ProfileInboxTarget other : mTargets) {
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
                            holder.status.setText(R.string.profile_inbox_not_running);
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
            ProfileInboxTarget target;

            Holder(View view) {
                super(view);
                icon = view.findViewById(R.id.icon);
                name = view.findViewById(R.id.name);
                status = view.findViewById(R.id.status);
            }
        }
    }

    private String targetName(ProfileInboxTarget target) {
        return getString(R.string.profile_inbox_profile,
                BidiFormatter.getInstance().unicodeWrap(target.name), target.userId);
    }

    private void confirmSend(Payload payload, ProfileInboxTarget target) {
        show(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.profile_inbox_send)
                .setMessage(getString(R.string.profile_inbox_confirm_send, targetName(target)))
                .setNeutralButton(R.string.profile_inbox_conditions,
                        (dialog, which) -> showAbout(() -> confirmSend(payload, target)))
                .setPositiveButton(R.string.profile_inbox_send,
                        (dialog, which) -> send(payload, target))
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void send(Payload payload, ProfileInboxTarget target) {
        work("send", () -> {
            IProfileInbox profileInbox = profileInbox();
            String id = null;
            boolean committing = false;
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.data);
                id = profileInbox.beginInsert(target.userId, target.serialNumber, payload.mime,
                        payload.data.length, hash, payload.sensitive);
                for (int offset = 0; offset < payload.data.length; offset += CHUNK_BYTES) {
                    requireActingUser();
                    profileInbox.append(id, offset, Arrays.copyOfRange(payload.data, offset,
                            Math.min(offset + CHUNK_BYTES, payload.data.length)));
                }
                requireActingUser();
                committing = true;
                profileInbox.commitInsert(id);
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
                        profileInbox.abortInsert(id);
                    } catch (Exception e) {
                        logFailure("abort_insert", e);
                    }
                }
            }
        }, result -> {
            if (mText != null) mText.setText("");
            toast(R.string.profile_inbox_sent);
        }, R.string.profile_inbox_send_error);
    }

    private void updateTabs() {
        ProfileInboxTabs tabs = findViewById(R.id.profile_inbox_tabs);
        if (tabs == null) return;
        Button inbox = findViewById(R.id.profile_inbox_inbox_tab);
        inbox.setText(mInboxCount < 0 ? getString(R.string.profile_inbox_inbox)
                : getString(R.string.profile_inbox_inbox_count, mInboxCount));
        tabs.setInboxSelected(mShowingInbox);
        for (int i = 0; i < tabs.getChildCount(); i++) tabs.getChildAt(i).setEnabled(!mBusy);
        if (mContent != null) mContent.setShowingInbox(mShowingInbox);
    }

    private void changePage(boolean inbox) {
        if (inbox == mShowingInbox) return;
        if (inbox) openInbox();
        else showHome();
        mContent.animatePageChange(inbox);
    }

    private void openInbox() {
        if (!mShowingInbox) {
            InboxSnapshot snapshot = mInboxSnapshot;
            if (snapshot == null) {
                ProfileInboxContents inbox = new ProfileInboxContents();
                inbox.entries = mEntries;
                snapshot = new InboxSnapshot(inbox);
            }
            showInbox(snapshot);
        }
        refreshInbox(true);
    }

    private void refreshInbox(boolean open) {
        boolean initial = !mInitialPageChosen;
        boolean previews = open || mShowingInbox || initial;
        work("list_inbox", () -> {
            ProfileInboxContents inbox = profileInbox().listInbox();
            if (inbox.entries == null || inbox.entries.length > MAX_INBOX_ITEMS) {
                throw new IOException("Invalid inbox");
            }
            InboxSnapshot snapshot = new InboxSnapshot(inbox);
            if (previews) {
                for (ProfileInboxEntry entry : inbox.entries) {
                    requireActingUser();
                    if (entry.sensitive || !"text/plain".equals(entry.mimeType) || expired(entry)) continue;
                    try {
                        String text = readText(entry, true);
                        int end = Math.min(PREVIEW_CHARACTERS, text.length());
                        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                        snapshot.previews.put(entry.id, text.substring(0, end)
                                + (end < text.length() ? "\u2026" : ""));
                    } catch (IOException | ExpiredItemException e) {
                        logFailure("preview_text", e);
                    } catch (ServiceSpecificException e) {
                        if (e.errorCode != ERROR_MISSING) throw e;
                        logFailure("preview_text", e);
                    }
                }
            }
            return snapshot;
        }, snapshot -> {
            mInboxSnapshot = snapshot;
            mEntries = snapshot.inbox.entries;
            updateInboxCount();
            ArrayList<String> keys = new ArrayList<>();
            for (ProfileInboxEntry entry : mEntries) {
                if (!entry.sensitive && !expired(entry)) keys.add(thumbnailKey(entry));
            }
            mThumbnails.keySet().retainAll(keys);
            mInitialPageChosen = true;
            boolean typed = mText != null && mText.length() > 0;
            if (open || mOpenInboxRequested || (!initial && mShowingInbox)
                    || (initial && mInboxCount > 0 && (!typed || mRelaunchPageRequested))) {
                showInbox(snapshot);
            } else if (mShowingInbox) {
                showHome();
            } else {
                updateTabs();
            }
            mOpenInboxRequested = false;
            mRelaunchPageRequested = false;
            mHandler.removeCallbacks(mCountdown);
            mCountdown.run();
        }, R.string.profile_inbox_unavailable);
    }

    private void showInbox(InboxSnapshot snapshot) {
        if (mShowingInbox && mInboxAdapter != null) {
            mInboxAdapter.snapshot = snapshot;
            mInboxAdapter.entries.clear();
            mInboxAdapter.entries.addAll(Arrays.asList(snapshot.inbox.entries));
            mInboxAdapter.notifyDataSetChanged();
            loadThumbnails(snapshot);
            return;
        }
        mShowingInbox = true;
        newPage(R.layout.profile_inbox_inbox);
        mInboxList = findViewById(R.id.profile_inbox_rows);
        mInboxList.setLayoutManager(new LinearLayoutManager(this));
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        mInboxList.setItemAnimator(animator);
        mInboxAdapter = new InboxAdapter(snapshot);
        mInboxList.setAdapter(mInboxAdapter);
        loadThumbnails(snapshot);
    }

    private void updateInboxCount() {
        if (mInboxSnapshot == null) return;
        int count = 0;
        for (ProfileInboxEntry entry : mEntries) {
            if (!expired(entry) && !isHidden(entry)) count++;
        }
        if (mInboxCount != count) {
            mInboxCount = count;
            updateTabs();
            if (mInboxAdapter != null) mInboxAdapter.notifyItemChanged(0);
        }
    }

    private boolean isHidden(ProfileInboxEntry entry) {
        return (mPendingDelete != null && mPendingDelete.entry.id.equals(entry.id))
                || (mCommittingDelete != null && mCommittingDelete.entry.id.equals(entry.id));
    }

    private void bindRow(InboxRow row, ProfileInboxEntry entry) {
        row.entry = entry;
        boolean text = "text/plain".equals(entry.mimeType);
        if (expired(entry)) {
            row.preview.setText(R.string.profile_inbox_expired);
        } else if (text) {
            String preview = entry.sensitive ? getString(R.string.profile_inbox_sensitive)
                    : mInboxAdapter.snapshot.previews.get(entry.id);
            row.preview.setText(preview != null ? preview : getString(R.string.profile_inbox_preview_unavailable));
        } else {
            String label = getString(R.string.profile_inbox_image_size,
                    Formatter.formatFileSize(this, entry.size));
            row.preview.setText(entry.sensitive ? label + "\n" + getString(R.string.profile_inbox_sensitive) : label);
        }
        row.summary.setText(entrySummary(entry));
        row.thumbnail.setVisibility(text ? View.GONE : View.VISIBLE);
        Bitmap thumbnail = text || entry.sensitive || expired(entry) ? null : mThumbnails.get(thumbnailKey(entry));
        if (thumbnail != null) {
            row.thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.thumbnail.setImageBitmap(thumbnail);
        } else {
            row.thumbnail.setScaleType(ImageView.ScaleType.CENTER);
            row.thumbnail.setImageResource(R.drawable.profile_inbox_image);
        }
    }

    private void rowAction(InboxRow row, Consumer<ProfileInboxEntry> action) {
        ProfileInboxEntry entry = row.entry;
        if (entry == null || row.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
        act(() -> {
            if (mInboxAdapter != null && mInboxAdapter.entries.contains(entry)) action.accept(entry);
        });
    }

    private final class InboxAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        InboxSnapshot snapshot;
        final ArrayList<ProfileInboxEntry> entries;

        InboxAdapter(InboxSnapshot snapshot) {
            this.snapshot = snapshot;
            entries = new ArrayList<>(Arrays.asList(snapshot.inbox.entries));
        }

        @Override
        public int getItemCount() {
            return entries.size() + 1;
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? 0 : 1;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            View view = getLayoutInflater().inflate(type == 0 ? R.layout.profile_inbox_inbox_header
                    : R.layout.profile_inbox_inbox_item, parent, false);
            protect(view);
            if (type != 0) return new InboxRow(view);
            view.findViewById(R.id.profile_inbox_refresh).setOnClickListener(v -> act(() -> openInbox()));
            view.findViewById(R.id.profile_inbox_clear).setOnClickListener(
                    v -> act(() -> confirmClear(ProfileInboxActivity.this::openInbox)));
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof InboxRow row) {
                bindRow(row, entries.get(position - 1));
                return;
            }
            View header = holder.itemView;
            header.findViewById(R.id.profile_inbox_empty).setVisibility(
                    mInboxCount == 0 ? View.VISIBLE : View.GONE);
            TextView notice = header.findViewById(R.id.profile_inbox_expiry_notice);
            notice.setVisibility(snapshot.inbox.lastExpiredAt == 0 ? View.GONE : View.VISIBLE);
            if (snapshot.inbox.lastExpiredAt != 0) {
                notice.setText(getString(R.string.profile_inbox_expiry_notice,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(new Date(snapshot.inbox.lastExpiredAt))));
            }
            header.findViewById(R.id.profile_inbox_clear).setVisibility(
                    !entries.isEmpty() || snapshot.inbox.lastExpiredAt != 0 ? View.VISIBLE : View.GONE);
        }

        @Override
        public void onViewRecycled(RecyclerView.ViewHolder holder) {
            if (holder instanceof InboxRow row) {
                row.thumbnail.setImageDrawable(null);
                row.entry = null;
            }
            super.onViewRecycled(holder);
        }
    }

    private static final class InboxSnapshot {
        final ProfileInboxContents inbox;
        final Map<String, String> previews = new LinkedHashMap<>();

        InboxSnapshot(ProfileInboxContents inbox) {
            this.inbox = inbox;
        }
    }

    private final class InboxRow extends RecyclerView.ViewHolder {
        ProfileInboxEntry entry;
        final TextView preview;
        final TextView summary;
        final ImageView thumbnail;

        InboxRow(View view) {
            super(view);
            preview = view.findViewById(R.id.profile_inbox_entry_label);
            summary = view.findViewById(R.id.profile_inbox_entry_summary);
            thumbnail = view.findViewById(R.id.profile_inbox_thumbnail);
            view.setOnClickListener(v -> rowAction(this, ProfileInboxActivity.this::showEntry));
            view.findViewById(R.id.profile_inbox_copy).setOnClickListener(
                    v -> rowAction(this, ProfileInboxActivity.this::copy));
            view.findViewById(R.id.profile_inbox_delete).setOnClickListener(
                    v -> rowAction(this, ProfileInboxActivity.this::stageDelete));
            view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            R.id.profile_inbox_copy, getString(R.string.profile_inbox_copy)));
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            R.id.profile_inbox_delete, getString(R.string.profile_inbox_delete)));
                }

                @Override
                public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    if (action == R.id.profile_inbox_copy || action == R.id.profile_inbox_delete) {
                        if (mBusy || !mResumed || !isActingUser()) return false;
                        rowAction(InboxRow.this, action == R.id.profile_inbox_copy
                                ? ProfileInboxActivity.this::copy : ProfileInboxActivity.this::confirmDelete);
                        return true;
                    }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
        }
    }

    private void stageDelete(ProfileInboxEntry entry) {
        if (mBusy || !mResumed || !isActingUser() || mInboxAdapter == null) {
            return;
        }
        int index = mInboxAdapter.entries.indexOf(entry);
        if (index < 0 || expired(entry)) return;
        PendingDelete pending = new PendingDelete(entry, index, mInboxAdapter);
        mPendingDelete = pending;
        mInboxAdapter.entries.remove(index);
        mInboxAdapter.notifyItemRemoved(index + 1);
        updateInboxCount();
        pending.bar = Snackbar.make(findViewById(R.id.profile_inbox_root),
                R.string.profile_inbox_deleted, Snackbar.LENGTH_LONG)
                .setAction(R.string.profile_inbox_undo, view -> {
                    if (!mBusy && mResumed && isActingUser() && mPendingDelete == pending) {
                        abandonPendingDelete();
                    }
                });
        pending.bar.addCallback(new Snackbar.Callback() {
            @Override
            public void onDismissed(Snackbar bar, int event) {
                if (mPendingDelete != pending) return;
                if (event != Snackbar.Callback.DISMISS_EVENT_ACTION && mResumed && isActingUser()) {
                    commitPendingDelete(() -> {}, () -> {});
                } else {
                    abandonPendingDelete();
                }
            }
        });
        protect(pending.bar.getView());
        pending.bar.show();
    }

    private void abandonPendingDelete() {
        PendingDelete pending = mPendingDelete;
        mPendingDelete = null;
        if (pending == null) return;
        pending.bar.dismiss();
        restoreDeletedRow(pending);
    }

    private void restoreDeletedRow(PendingDelete pending) {
        if (mInboxAdapter == pending.adapter && !pending.adapter.entries.contains(pending.entry)) {
            int index = Math.min(pending.index, pending.adapter.entries.size());
            pending.adapter.entries.add(index, pending.entry);
            pending.adapter.notifyItemInserted(index + 1);
        }
        updateInboxCount();
    }

    private void commitPendingDelete(Runnable after, Runnable failed) {
        if (mBusy || !mResumed || !isActingUser()) return;
        PendingDelete pending = mPendingDelete;
        if (pending == null) {
            after.run();
            return;
        }
        mPendingDelete = null;
        mCommittingDelete = pending;
        pending.bar.dismiss();
        work("delete", () -> {
            profileInbox().delete(pending.entry.id);
            return true;
        }, result -> {
            pending.committed = true;
            mEntries = Arrays.stream(mEntries).filter(entry -> !entry.id.equals(pending.entry.id))
                    .toArray(ProfileInboxEntry[]::new);
            if (mInboxSnapshot != null) {
                mInboxSnapshot.inbox.entries = mEntries;
                mInboxSnapshot.previews.remove(pending.entry.id);
            }
            mThumbnails.remove(thumbnailKey(pending.entry));
            mCommittingDelete = null;
            updateInboxCount();
            after.run();
        }, R.string.profile_inbox_change_error, result -> {
            if (mCommittingDelete == pending) mCommittingDelete = null;
            if (!pending.committed) {
                restoreDeletedRow(pending);
                failed.run();
            }
        });
    }

    private static final class PendingDelete {
        final ProfileInboxEntry entry;
        final int index;
        final InboxAdapter adapter;
        Snackbar bar;
        boolean committed;

        PendingDelete(ProfileInboxEntry entry, int index, InboxAdapter adapter) {
            this.entry = entry;
            this.index = index;
            this.adapter = adapter;
        }
    }

    private void confirmDelete(ProfileInboxEntry entry) {
        show(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.profile_inbox_delete)
                .setMessage(R.string.profile_inbox_delete_confirm)
                .setPositiveButton(R.string.profile_inbox_delete,
                        (dialog, which) -> delete(entry, false))
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void confirmClear(Runnable afterClear) {
        show(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.profile_inbox_clear)
                .setMessage(R.string.profile_inbox_clear_confirm)
                .setPositiveButton(R.string.profile_inbox_clear, (dialog, which) ->
                        work("clear_inbox", () -> {
                            profileInbox().clearInbox();
                            return true;
                        }, result -> afterClear.run(), R.string.profile_inbox_change_error))
                .setNegativeButton(android.R.string.cancel, null));
    }

    private String entrySender(ProfileInboxEntry entry) {
        String sender = BidiFormatter.getInstance().unicodeWrap(
                entry.senderName == null ? "" : entry.senderName);
        if (entry.senderNameAmbiguous) {
            sender = getString(R.string.profile_inbox_profile, sender, entry.senderId);
        }
        return sender;
    }

    private String entrySummary(ProfileInboxEntry entry) {
        return getString(R.string.profile_inbox_row_summary, entrySender(entry),
                DateUtils.getRelativeTimeSpanString(entry.createdAt, System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS));
    }

    private String entryStaticLabel(ProfileInboxEntry entry) {
        DateFormat date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        return getString(R.string.profile_inbox_row_type, entry.mimeType,
                Formatter.formatFileSize(this, entry.size))
                + "\n" + getString(R.string.profile_inbox_row_from, entrySender(entry))
                + "\n" + getString(R.string.profile_inbox_row_sent,
                        date.format(new Date(entry.createdAt)))
                + "\n" + getString(R.string.profile_inbox_row_expires,
                        date.format(new Date(entry.expiresAt)));
    }

    private static boolean expired(ProfileInboxEntry entry) {
        return SystemClock.elapsedRealtime() >= entry.expiresElapsedRealtime;
    }

    private String remainingLabel(ProfileInboxEntry entry) {
        long remaining = entry.expiresElapsedRealtime - SystemClock.elapsedRealtime();
        return remaining <= 0 ? getString(R.string.profile_inbox_expired)
                : getString(R.string.profile_inbox_remaining,
                        DateUtils.formatElapsedTime((remaining + 999) / 1000));
    }

    private String entryMessage(ProfileInboxEntry entry, String staticLabel) {
        return staticLabel + "\n" + remainingLabel(entry);
    }

    private void showEntry(ProfileInboxEntry entry) {
        if (expired(entry)) {
            toast(R.string.profile_inbox_expired);
            openInbox();
            return;
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        View actions = LayoutInflater.from(builder.getContext())
                .inflate(R.layout.profile_inbox_copy, null, false);
        boolean text = "text/plain".equals(entry.mimeType);
        TextView metadata = actions.findViewById(R.id.profile_inbox_entry_metadata);
        String staticLabel = entryStaticLabel(entry);
        metadata.setText(entryMessage(entry, staticLabel));
        TextView content = actions.findViewById(R.id.profile_inbox_entry_text);
        actions.findViewById(R.id.profile_inbox_entry_box).setVisibility(text ? View.VISIBLE : View.GONE);
        content.setText(R.string.profile_inbox_progress);
        entryAction(actions, R.id.profile_inbox_copy, () -> copy(entry));
        entryAction(actions, R.id.profile_inbox_cut, () -> cut(entry));
        actions.findViewById(R.id.profile_inbox_cut).setVisibility(text ? View.VISIBLE : View.GONE);
        entryAction(actions, R.id.profile_inbox_show_image, () -> showImage(entry));
        actions.findViewById(R.id.profile_inbox_show_image).setVisibility(text ? View.GONE : View.VISIBLE);
        entryAction(actions, R.id.profile_inbox_save, () -> saveEntry(entry));
        entryAction(actions, R.id.profile_inbox_delete, () -> confirmDelete(entry));
        if (!show(builder.setView(actions).setTitle(R.string.profile_inbox_item)
                .setNegativeButton(android.R.string.cancel, null))) return;
        mDisplayedEntry = entry;
        mDisplayedStaticLabel = staticLabel;
        mDisplayedMetadata = metadata;
        if (text) {
            work("read_text", () -> {
                try {
                    return readText(entry, false);
                } catch (Exception e) {
                    requireActingUser();
                    logFailure("read_text", e);
                    return getString(errorMessage(e, R.string.profile_inbox_read_text_error));
                }
            }, value -> {
                if (mDisplayedEntry == entry && mDialog != null && mDialog.isShowing() && !expired(entry)) {
                    content.setText(value);
                }
            }, R.string.profile_inbox_read_text_error);
        }
    }

    private String readText(ProfileInboxEntry entry, boolean preview) throws Exception {
        if (!"text/plain".equals(entry.mimeType) || entry.size > MAX_TEXT_BYTES) {
            throw new IOException("Invalid inbox item size");
        }
        // Batch previews must not share one item's operation deadline with the rest of the inbox.
        byte[] data = preview ? readEntryData(entry) : readEntry(entry);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Invalid queued text");
        }
    }

    private void showImage(ProfileInboxEntry entry) {
        View page = findViewById(R.id.profile_inbox_root);
        int width = Math.max(1, Math.min(MAX_IMAGE_PREVIEW_EDGE,
                page.getWidth() - page.getPaddingLeft() - page.getPaddingRight()));
        int height = Math.max(1, Math.min(MAX_IMAGE_PREVIEW_EDGE,
                (page.getHeight() - page.getPaddingTop() - page.getPaddingBottom()) / 2));
        work("show_image", () -> decodeImage(entry, width, height), preview -> {
            if (expired(entry)) {
                toast(R.string.profile_inbox_expired);
                return;
            }
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
            ImageView image = new ImageView(builder.getContext());
            image.setAdjustViewBounds(true);
            image.setMaxWidth(width);
            image.setMaxHeight(height);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription(getString(R.string.profile_inbox_image));
            image.setImageBitmap(preview.bitmap);
            preview.displayed = show(builder.setTitle(R.string.profile_inbox_image)
                    .setView(image)
                    .setPositiveButton(R.string.profile_inbox_back_to_details,
                            (dialog, which) -> showEntry(entry))
                    .setNegativeButton(android.R.string.cancel, null), () -> image.setImageDrawable(null));
            if (preview.displayed) mDisplayedEntry = entry;
        }, R.string.profile_inbox_image_error, preview -> {
            if (preview != null && !preview.displayed) preview.bitmap.recycle();
        });
    }

    private static String thumbnailKey(ProfileInboxEntry entry) {
        return entry.id + ":" + Arrays.toString(entry.sha256);
    }

    private void cancelThumbnails() {
        if (mThumbnailOperation != null) {
            mThumbnailOperation.cancel();
            mThumbnailOperation = null;
        }
    }

    private void loadThumbnails(InboxSnapshot snapshot) {
        ArrayList<ProfileInboxEntry> newest = new ArrayList<>(Arrays.asList(snapshot.inbox.entries));
        newest.sort((first, second) -> Long.compare(second.createdAt, first.createdAt));
        ArrayList<ProfileInboxEntry> selected = new ArrayList<>();
        long remaining = THUMBNAIL_READ_BUDGET;
        for (ProfileInboxEntry entry : newest) {
            if (entry.sensitive || expired(entry) || "text/plain".equals(entry.mimeType)
                    || !isSupportedMimeType(entry.mimeType) || entry.size <= 0 || entry.size > MAX_ITEM_BYTES
                    || mThumbnails.containsKey(thumbnailKey(entry))) continue;
            if (entry.size > remaining) continue;
            selected.add(entry);
            remaining -= entry.size;
            if (selected.size() == MAX_THUMBNAILS_PER_REFRESH) break;
        }
        if (selected.isEmpty()) return;
        int edge = Math.max(1, Math.min(MAX_THUMBNAIL_EDGE,
                Math.round(56 * getResources().getDisplayMetrics().density)));
        Operation current = new Operation("thumbnail", mGeneration);
        mThumbnailOperation = current;
        mWorker.execute(() -> {
            mWorkerOperation.set(current);
            try {
                for (ProfileInboxEntry entry : selected) {
                    requireActingUser();
                    Bitmap bitmap = null;
                    try {
                        bitmap = decodeThumbnail(entry, edge);
                    } catch (IOException | ExpiredItemException e) {
                        requireActingUser();
                        logFailure("thumbnail", e);
                    } catch (ServiceSpecificException e) {
                        if (e.errorCode != ERROR_MISSING) throw e;
                        logFailure("thumbnail", e);
                    }
                    Bitmap result = bitmap;
                    runOnUiThread(() -> {
                        if (current.cancelled || current.generation != mGeneration
                                || !mResumed || isFinishing() || isDestroyed() || !isActingUser()
                                || expired(entry) || mInboxAdapter == null) {
                            if (result != null) result.recycle();
                            return;
                        }
                        mThumbnails.put(thumbnailKey(entry), result);
                        int position = mInboxAdapter.entries.indexOf(entry);
                        if (position >= 0) mInboxAdapter.notifyItemChanged(position + 1);
                    });
                }
            } catch (Exception e) {
                logFailure("thumbnail", e);
            } finally {
                current.finish();
                mWorkerOperation.remove();
                runOnUiThread(() -> {
                    if (mThumbnailOperation == current) mThumbnailOperation = null;
                });
            }
        });
    }

    private Bitmap decodeThumbnail(ProfileInboxEntry entry, int edge) throws Exception {
        if (entry.sensitive || "text/plain".equals(entry.mimeType)) {
            throw new IOException("Unsupported image preview");
        }
        Bitmap decoded = null;
        Bitmap thumbnail = null;
        try {
            byte[] data = readEntryData(entry);
            if (!isSupportedMimeType(entry.mimeType)
                    || !entry.mimeType.equals(sniffImageMimeType(data, data.length))) {
                throw new IOException("Unsupported image preview");
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                throw new IOException("Invalid image dimensions");
            }
            int sample = 1;
            while (sample <= Integer.MAX_VALUE / 2
                    && options.outWidth / (sample * 2L) >= edge && options.outHeight / (sample * 2L) >= edge) {
                sample *= 2;
            }
            long width = (options.outWidth + (long) sample - 1) / sample;
            long height = (options.outHeight + (long) sample - 1) / sample;
            if (width > MAX_THUMBNAIL_DECODE_BYTES / 4 / height) {
                throw new IOException("Image preview unavailable");
            }
            requireActingUser();
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decoded = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            requireActingUser();
            if (decoded == null || decoded.getAllocationByteCount() > MAX_THUMBNAIL_DECODE_BYTES) {
                throw new IOException("Image preview unavailable");
            }
            int side = Math.min(decoded.getWidth(), decoded.getHeight());
            int left = (decoded.getWidth() - side) / 2;
            int top = (decoded.getHeight() - side) / 2;
            thumbnail = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888);
            new Canvas(thumbnail).drawBitmap(decoded, new Rect(left, top, left + side, top + side),
                    new Rect(0, 0, edge, edge), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            requireActingUser();
            if (expired(entry)) throw new ExpiredItemException();
            Bitmap result = thumbnail;
            thumbnail = null;
            return result;
        } catch (OutOfMemoryError e) {
            throw new IOException("Image preview unavailable");
        } finally {
            if (decoded != null) decoded.recycle();
            if (thumbnail != null) thumbnail.recycle();
        }
    }

    private ImagePreview decodeImage(ProfileInboxEntry entry, int width, int height) throws Exception {
        Bitmap bitmap = null;
        try {
            byte[] data = readEntry(entry);
            if (!isSupportedMimeType(entry.mimeType) || "text/plain".equals(entry.mimeType)
                    || !entry.mimeType.equals(sniffImageMimeType(data, data.length))) {
                throw new IOException("Unsupported image preview");
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                throw new IOException("Invalid image dimensions");
            }
            int sample = 1;
            while ((options.outWidth + (long) sample - 1) / sample > width
                    || (options.outHeight + (long) sample - 1) / sample > height) {
                if (sample > Integer.MAX_VALUE / 2) throw new IOException("Invalid image dimensions");
                sample *= 2;
            }
            requireActingUser();
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            requireActingUser();
            if (expired(entry)) throw new ExpiredItemException();
            if (bitmap == null || bitmap.getWidth() > width || bitmap.getHeight() > height
                    || bitmap.getAllocationByteCount() > 4 * MAX_IMAGE_PREVIEW_EDGE * MAX_IMAGE_PREVIEW_EDGE) {
                throw new IOException("Image preview unavailable");
            }
            ImagePreview result = new ImagePreview(bitmap);
            bitmap = null;
            return result;
        } catch (OutOfMemoryError e) {
            throw new IOException("Image preview unavailable");
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private static final class ImagePreview {
        final Bitmap bitmap;
        boolean displayed;

        ImagePreview(Bitmap bitmap) {
            this.bitmap = bitmap;
        }
    }

    private void entryAction(View actions, int id, Runnable action) {
        View button = actions.findViewById(id);
        protect(button);
        button.setOnClickListener(view -> {
            act(() -> {
                if (mDialog != null) mDialog.dismiss();
                mDisplayedEntry = null;
                action.run();
            });
        });
    }

    private void saveEntry(ProfileInboxEntry entry) {
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

    private void cut(ProfileInboxEntry entry) {
        if (!"text/plain".equals(entry.mimeType)) {
            show(new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.profile_inbox_cut)
                    .setMessage(R.string.profile_inbox_cut_image_unavailable)
                    .setPositiveButton(R.string.profile_inbox_save,
                            (dialog, which) -> saveEntry(entry))
                    .setNegativeButton(android.R.string.cancel, null));
            return;
        }
        copy(entry, () -> delete(entry, true));
    }

    private void delete(ProfileInboxEntry entry, boolean afterCopy) {
        work("delete", () -> {
            try {
                profileInbox().delete(entry.id);
                return 0;
            } catch (Exception e) {
                if (!afterCopy) throw e;
                logFailure("cut_delete", e);
                for (ProfileInboxEntry remaining : profileInbox().listInbox().entries) {
                    if (entry.id.equals(remaining.id)) {
                        return R.string.profile_inbox_cut_delete_error;
                    }
                }
                return R.string.profile_inbox_cut_delete_uncertain;
            }
        }, message -> {
            if (message != 0) toast(message);
            else openInbox();
        }, afterCopy ? R.string.profile_inbox_cut_delete_uncertain
                : R.string.profile_inbox_change_error);
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
            toast(R.string.profile_inbox_picker_error);
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

    private void save(ProfileInboxEntry entry, Uri uri) {
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
        }, result -> toast(R.string.profile_inbox_saved), R.string.profile_inbox_save_error);
    }

    private byte[] readEntry(ProfileInboxEntry entry) throws Exception {
        mWorkerOperation.get().expiresAt(entry.expiresElapsedRealtime);
        return readEntryData(entry);
    }

    private byte[] readEntryData(ProfileInboxEntry entry) throws Exception {
        if (entry.size <= 0 || entry.size > MAX_ITEM_BYTES) {
            throw new IOException("Invalid inbox item size");
        }
        IProfileInbox profileInbox = profileInbox();
        byte[] data = new byte[entry.size];
        for (int offset = 0; offset < data.length; offset += CHUNK_BYTES) {
            requireActingUser();
            if (expired(entry)) throw new ExpiredItemException();
            byte[] chunk;
            try {
                chunk = profileInbox.read(entry.id, offset);
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
        requireActingUser();
        if (expired(entry)) throw new ExpiredItemException();
        return data;
    }

    private void copy(ProfileInboxEntry entry) {
        copy(entry, () -> toast(R.string.profile_inbox_copied));
    }

    private void copy(ProfileInboxEntry entry, Runnable afterCopy) {
        work("copy", () -> {
            if (!isSupportedMimeType(entry.mimeType)
                    || ("text/plain".equals(entry.mimeType) && entry.size > MAX_TEXT_BYTES)) {
                throw new IOException("Invalid inbox item size");
            }
            byte[] data = readEntry(entry);
            ClipData clip;
            ProfileInboxProvider provider = null;
            Uri uri = null;
            if ("text/plain".equals(entry.mimeType)) {
                String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data)).toString();
                clip = ClipData.newPlainText(getString(R.string.profile_inbox_item), text);
            } else {
                requireActingUser();
                if (expired(entry)) throw new ExpiredItemException();
                try (ContentProviderClient client = getContentResolver()
                        .acquireContentProviderClient(ProfileInboxProvider.AUTHORITY)) {
                    if (client == null || !(client.getLocalContentProvider()
                            instanceof ProfileInboxProvider local)) {
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
                    clip = new ClipData(getString(R.string.profile_inbox_item),
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
                toast(R.string.profile_inbox_expired);
                return;
            }
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard == null) {
                toast(R.string.profile_inbox_copy_error);
                return;
            }
            clipboard.setPrimaryClip(pending.clip);
            pending.published = true;
            afterCopy.run();
        }, R.string.profile_inbox_copy_error, pending -> {
            if (pending != null && !pending.published && pending.provider != null) {
                pending.provider.discard(pending.uri);
            }
        });
    }

    private static final class PendingCopy {
        final ClipData clip;
        final ProfileInboxProvider provider;
        final Uri uri;
        boolean published;

        PendingCopy(ClipData clip, ProfileInboxProvider provider, Uri uri) {
            this.clip = clip;
            this.provider = provider;
            this.uri = uri;
        }
    }

    private void setBusy(boolean busy) {
        mBusy = busy;
        updateTabs();
        LinearProgressIndicator progress = findViewById(R.id.profile_inbox_progress);
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
        if (mPendingDelete != null) {
            commitPendingDelete(() -> work(operation, task, success, failure, cleanup), () -> {});
            return;
        }
        cancelThumbnails();
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
                error = "save_copy".equals(operation) ? R.string.profile_inbox_save_error
                        : "send".equals(operation) && e instanceof OperationCanceledException
                                ? R.string.profile_inbox_uncertain : errorMessage(e, failure);
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
                    runPendingWork();
                }
            });
        });
    }

    private void cancelWork() {
        cancelThumbnails();
        mGeneration++;
        if (mOperation != null) {
            if ("save_copy".equals(mOperation.name)) {
                if (mResumed) toast(R.string.profile_inbox_save_error);
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
        if (fallback == R.string.profile_inbox_cut_delete_uncertain) return fallback;
        if (e instanceof CharacterCodingException) return R.string.profile_inbox_copy_invalid_text;
        if (e instanceof CopyImageWriteException) return R.string.profile_inbox_copy_image_error;
        if (e instanceof CopyProviderException) return R.string.profile_inbox_copy_provider_error;
        if (e instanceof ClipboardImageReadException) {
            return R.string.profile_inbox_clipboard_image_unreadable;
        }
        if (e instanceof ExpiredItemException) return R.string.profile_inbox_expired;
        if (e instanceof UncertainDeliveryException) return R.string.profile_inbox_uncertain;
        if (e instanceof ServiceNotFoundException) return R.string.profile_inbox_service_missing;
        if (e instanceof SecurityException) return R.string.profile_inbox_not_authorized;
        if (e instanceof RemoteException) return R.string.profile_inbox_connection_error;
        if (e instanceof ServiceSpecificException refused) {
            return switch (refused.errorCode) {
                case ERROR_FULL -> R.string.profile_inbox_full;
                case ERROR_INVALID -> R.string.profile_inbox_invalid;
                case ERROR_MISSING -> R.string.profile_inbox_missing;
                case ERROR_EXPORT_BLOCKED -> R.string.profile_inbox_export_blocked;
                case ERROR_IMPORT_BLOCKED -> R.string.profile_inbox_import_blocked;
                case ERROR_TARGET_IMPORT_BLOCKED -> R.string.profile_inbox_target_import_blocked;
                case ERROR_UNAVAILABLE -> R.string.profile_inbox_unavailable;
                case ERROR_STORAGE -> R.string.profile_inbox_storage_error;
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
            case "profile_inbox service not found", "Profile inbox item expired",
                    "Profile inbox delivery status unknown", "Unsupported document", "Document unavailable",
                    "Invalid inbox item size", "Incomplete item", "Item checksum mismatch",
                    "Destination unavailable", "Activity is not the foreground user",
                    "Activity UserManager unavailable", "Activity KeyguardManager unavailable",
                    "Activity user is not unlocked", "Activity device is locked",
                    "Activity work interrupted" -> true;
            default -> false;
        };
    }

    private boolean show(MaterialAlertDialogBuilder builder) {
        return show(builder, () -> {});
    }

    private boolean show(MaterialAlertDialogBuilder builder, Runnable onDismiss) {
        if (!mResumed || !isActingUser()) return false;
        if (mDialog != null) mDialog.dismiss();
        mDisplayedEntry = null;
        mDisplayedMetadata = null;
        mDialog = builder.create();
        AlertDialog shown = mDialog;
        shown.setOnDismissListener(dialog -> {
            if (mDialog == shown) {
                mDialog = null;
                mDisplayedEntry = null;
                mDisplayedMetadata = null;
            }
            onDismiss.run();
            mHandler.post(this::runPendingWork);
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
        return true;
    }

    private void toast(int message) {
        if (mPendingDelete != null) {
            act(() -> toast(message));
            return;
        }
        if (mResumed && isActingUser()) {
            Snackbar snackbar = Snackbar.make(findViewById(R.id.profile_inbox_root),
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
            super("profile_inbox service not found");
        }
    }

    private static final class ExpiredItemException extends Exception {
        ExpiredItemException() {
            super("Profile inbox item expired");
        }
    }

    private static final class UncertainDeliveryException extends Exception {
        UncertainDeliveryException() {
            super("Profile inbox delivery status unknown");
        }
    }
}
