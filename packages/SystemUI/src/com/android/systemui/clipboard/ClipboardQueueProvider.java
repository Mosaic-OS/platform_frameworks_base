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

import static android.content.ClipboardQueueContract.MAX_ITEM_BYTES;

import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.UUID;

public final class ClipboardQueueProvider extends ContentProvider {
    static final String AUTHORITY = "com.android.systemui.clipboardqueue";
    private static final String TAG = "ClipboardQueueProvider";
    private static final long LOG_WINDOW_MILLIS = 60_000;
    private static final HashMap<String, Integer> sFailures = new HashMap<>();
    private static final Handler sLogHandler = new Handler(Looper.getMainLooper());
    private static final long IMAGE_RETENTION_MILLIS = 60L * 60 * 1000;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mExpiry = this::prune;
    private File mFile;
    private Uri mUri;
    private String mMime;
    private String mName;
    private long mExpires;

    @Override
    public synchronized boolean onCreate() {
        try {
            Context storage = getContext().createCredentialProtectedStorageContext();
            mFile = new File(new File(storage.getCacheDir(), "clipboard_queue"), "image");
            clear();
        } catch (RuntimeException e) {
            mFile = null;
            logFailure("cache_init");
        }
        return true;
    }

    static void pruneOnLaunch(Context context) {
        try (ContentProviderClient client = context.getContentResolver()
                .acquireContentProviderClient(AUTHORITY)) {
            if (client != null && client.getLocalContentProvider()
                    instanceof ClipboardQueueProvider provider) provider.prune();
        } catch (RuntimeException e) {
            logFailure("provider_lookup");
        }
    }

    synchronized Uri store(byte[] data, String mime) throws IOException {
        String suffix = switch (mime) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> throw new IOException("Unsupported image type");
        };
        if (data.length == 0 || data.length > MAX_ITEM_BYTES) {
            throw new IOException("Invalid image size");
        }
        if (mFile == null || !clear()) throw new IOException("Image cache unavailable");
        File directory = mFile.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Image cache unavailable");
        }
        boolean complete = false;
        try {
            Os.chmod(directory.getPath(), 0700);
            try (FileOutputStream stream = new FileOutputStream(mFile)) {
                Os.fchmod(stream.getFD(), 0600);
                stream.write(data);
                stream.flush();
                stream.getFD().sync();
            }
            mName = "profile-image" + suffix;
            mMime = mime;
            mUri = new Uri.Builder().scheme("content").authority(AUTHORITY)
                    .appendPath(UUID.randomUUID().toString() + suffix).build();
            mExpires = SystemClock.elapsedRealtime() + IMAGE_RETENTION_MILLIS;
            mHandler.postDelayed(mExpiry, IMAGE_RETENTION_MILLIS);
            complete = true;
            return mUri;
        } catch (ErrnoException e) {
            throw new IOException("Image cache permissions unavailable");
        } finally {
            if (!complete) clear();
        }
    }

    synchronized void discard(Uri uri) {
        if (uri != null && uri.equals(mUri)) clear();
    }

    private synchronized void prune() {
        if (mUri == null || SystemClock.elapsedRealtime() >= mExpires) clear();
    }

    private boolean clear() {
        Uri previous = mUri;
        mUri = null;
        mMime = null;
        mName = null;
        mExpires = 0;
        mHandler.removeCallbacks(mExpiry);
        if (previous != null) {
            try {
                getContext().revokeUriPermission(previous, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException e) {
                logFailure("grant_cleanup");
            }
        }
        try {
            if (mFile != null && mFile.exists() && !mFile.delete()) {
                logFailure("cache_cleanup");
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            logFailure("cache_cleanup");
            return false;
        }
    }

    private static void logFailure(String operation) {
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
                        Log.e(TAG, operation + ": suppressed " + suppressed
                                + " repeated failures in " + LOG_WINDOW_MILLIS + " ms");
                    }
                }
            }, LOG_WINDOW_MILLIS);
        }
        Log.e(TAG, operation + " failed");
    }

    private void requireCurrentImage(Uri uri) throws FileNotFoundException {
        if (!UserHandle.isSameUser(Binder.getCallingUid(), Process.myUid())) {
            throw new SecurityException("Image belongs to another user");
        }
        prune();
        // A new token prevents an old URI grant from opening the next copied image.
        if (mUri == null || !mUri.equals(uri) || mFile == null || !mFile.isFile()) {
            throw new FileNotFoundException("Clipboard image unavailable");
        }
    }

    @Override
    public synchronized ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only image");
        requireCurrentImage(uri);
        return ParcelFileDescriptor.open(mFile, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public synchronized String getType(Uri uri) {
        try {
            requireCurrentImage(uri);
            return mMime;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getTypeAnonymous(Uri uri) {
        return null;
    }

    @Override
    public synchronized Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        try {
            requireCurrentImage(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] columns = projection == null
                ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = mName;
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = mFile.length();
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only image");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only image");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only image");
    }
}
