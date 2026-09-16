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

import android.content.ClipboardQueueEntry;
import android.content.ContentValues;
import android.content.pm.UserInfo;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.os.Environment;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.SparseIntArray;
import android.util.Slog;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.UUID;

final class ClipboardQueueStore {
    private static final String TAG = "ClipboardQueueStore";
    private static final long MAX_DATABASE_BYTES = 192L * 1024 * 1024;
    private static final long CLOCK_PERSIST_MILLIS = 30_000;
    private final SQLiteDatabase mDb;
    private long mClockPersistedElapsed;
    private long mClockWriteElapsed = -1;
    private long mNow;
    private long mElapsed;

    ClipboardQueueStore(long clockFloor, long floorElapsed) throws IOException, ErrnoException {
        String stage = "create_directory";
        SQLiteDatabase database = null;
        try {
            File directory = new File(Environment.getDataSystemDirectory(), "clipboard_queue");
            if (!directory.isDirectory() && !directory.mkdir()) {
                throw new IOException("Queue directory unavailable");
            }
            stage = "chmod_directory";
            Os.chmod(directory.getPath(), 0700);
            stage = "sync_parent_directory";
            syncDirectory(directory.getParentFile());
            stage = "prepare_database_files";
            File databaseFile = new File(directory, "queue.db");
            FileDescriptor descriptor = Os.open(databaseFile.getPath(), OsConstants.O_RDWR
                    | OsConstants.O_CREAT | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0600);
            try {
                Os.fchmod(descriptor, 0600);
            } finally {
                Os.close(descriptor);
            }
            File journalFile = new File(directory, "queue.db-journal");
            if (journalFile.exists()) Os.chmod(journalFile.getPath(), 0600);
            stage = "open_database";
            database = SQLiteDatabase.openDatabase(databaseFile,
                    new SQLiteDatabase.OpenParams.Builder()
                            .setOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY
                                    | SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                            .setJournalMode("DELETE")
                            .setSynchronousMode("FULL")
                            .setErrorHandler(db -> {
                                throw new SQLiteDatabaseCorruptException("Queue storage unavailable");
                            })
                            .build());
            mDb = database;
            stage = "foreign_keys";
            mDb.setForeignKeyConstraintsEnabled(true);
            stage = "durability_pragmas";
            mDb.execPerConnectionSQL("PRAGMA secure_delete=ON", null);
            mDb.execPerConnectionSQL("PRAGMA temp_store=MEMORY", null);
            long secureDelete = DatabaseUtils.longForQuery(mDb, "PRAGMA secure_delete", null);
            long tempStore = DatabaseUtils.longForQuery(mDb, "PRAGMA temp_store", null);
            long synchronous = DatabaseUtils.longForQuery(mDb, "PRAGMA synchronous", null);
            String journal = DatabaseUtils.stringForQuery(mDb, "PRAGMA journal_mode", null);
            if (secureDelete != 1 || tempStore != 2 || synchronous != 2
                    || !"delete".equalsIgnoreCase(journal)) {
                throw new IOException("Queue durability configuration: secure_delete=" + secureDelete
                        + " synchronous=" + synchronous + " journal_mode=" + journal);
            }
            stage = "maximum_size";
            mDb.setMaximumSize(MAX_DATABASE_BYTES);
            mDb.execPerConnectionSQL("PRAGMA max_page_count="
                    + (MAX_DATABASE_BYTES / mDb.getPageSize()), null);
            stage = "schema_transaction";
            mDb.beginTransaction();
            try {
                if (mDb.getVersion() == 0) {
                    mDb.execSQL("CREATE TABLE items (id TEXT PRIMARY KEY NOT NULL,"
                            + "sender_id INTEGER NOT NULL, sender_serial INTEGER NOT NULL,"
                            + "recipient_id INTEGER NOT NULL, recipient_serial INTEGER NOT NULL,"
                            + "mime TEXT NOT NULL, size INTEGER NOT NULL, written INTEGER NOT NULL,"
                            + "sensitive INTEGER NOT NULL, sha256 BLOB NOT NULL,"
                            + "created INTEGER NOT NULL, expires INTEGER NOT NULL,"
                            + "committed INTEGER NOT NULL)");
                    mDb.execSQL("CREATE TABLE chunks (item_id TEXT NOT NULL REFERENCES items(id)"
                            + " ON DELETE CASCADE, offset INTEGER NOT NULL, data BLOB NOT NULL,"
                            + "PRIMARY KEY(item_id, offset))");
                    mDb.execSQL("CREATE TABLE clock (value INTEGER NOT NULL)");
                    mDb.execSQL("INSERT INTO clock VALUES (0)");
                    mDb.setVersion(1);
                } else if (mDb.getVersion() != 1 && mDb.getVersion() != 2) {
                    throw new IOException("Unsupported queue schema");
                }
                if (mDb.getVersion() == 1) {
                    mDb.execSQL("CREATE TABLE expiry_notices (sender_id INTEGER NOT NULL,"
                            + "sender_serial INTEGER NOT NULL, recipient_id INTEGER NOT NULL,"
                            + "recipient_serial INTEGER NOT NULL, expired_at INTEGER NOT NULL,"
                            + "PRIMARY KEY(sender_serial,recipient_serial))");
                    mDb.setVersion(2);
                }
                mDb.execSQL("UPDATE items SET expires=MIN(expires,CASE WHEN created>?"
                        + " THEN ? ELSE created+? END) WHERE committed=1",
                        new Object[] {Long.MAX_VALUE - RETENTION_MILLIS,
                                Long.MAX_VALUE, RETENTION_MILLIS});
                mDb.delete("items", "committed=0", null);
                mDb.setTransactionSuccessful();
            } finally {
                mDb.endTransaction();
            }
            stage = "read_clock";
            mNow = Math.max(0, DatabaseUtils.longForQuery(mDb, "SELECT value FROM clock", null));
            mElapsed = SystemClock.elapsedRealtime();
            mClockPersistedElapsed = mElapsed;
            mNow = Math.max(mNow, saturatedAdd(clockFloor, Math.max(0, mElapsed - floorElapsed)));
            stage = "sync_queue_directory";
            syncDirectory(directory);
            Slog.i(TAG, "storage_ready: secure_delete=1 synchronous=2 journal_mode=delete");
        } catch (IOException | ErrnoException | RuntimeException e) {
            Slog.e(TAG, "storage_init/" + stage + " failed: " + e.getClass().getName());
            if (database != null) {
                try {
                    database.close();
                } catch (RuntimeException closeFailure) {
                    Slog.e(TAG, "storage_init/close failed: " + closeFailure.getClass().getName());
                }
            }
            throw e;
        }
    }

    private static void syncDirectory(File directory) throws ErrnoException {
        FileDescriptor descriptor = Os.open(directory.getPath(),
                OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
        try {
            Os.fsync(descriptor);
        } finally {
            Os.close(descriptor);
        }
    }

    void close() {
        mDb.close();
    }

    void begin() {
        mDb.beginTransaction();
        mClockWriteElapsed = -1;
    }

    void succeed() {
        mDb.setTransactionSuccessful();
    }

    void end(boolean successful) {
        mDb.endTransaction();
        if (successful && mClockWriteElapsed >= 0) {
            mClockPersistedElapsed = mClockWriteElapsed;
        }
        mClockWriteElapsed = -1;
    }

    long now() {
        long elapsed = SystemClock.elapsedRealtime();
        mNow = Math.max(System.currentTimeMillis(),
                saturatedAdd(mNow, Math.max(0, elapsed - mElapsed)));
        mElapsed = elapsed;
        return mNow;
    }

    void persistClock(boolean force) {
        now();
        if (force || mElapsed - mClockPersistedElapsed >= CLOCK_PERSIST_MILLIS) {
            mDb.execSQL("UPDATE clock SET value=?", new Object[] {mNow});
            mClockWriteElapsed = mElapsed;
        }
    }

    long prune(UserInfo[] users, int secretId, boolean recordNotices) {
        persistClock(false);
        SparseIntArray serials = new SparseIntArray();
        for (UserInfo user : users) {
            if (user.id != secretId && !user.partial && !user.preCreated) {
                serials.put(user.id, user.serialNumber);
            }
        }
        purgeInvalidUsers("items", serials);
        purgeInvalidUsers("expiry_notices", serials);
        if (recordNotices) {
            mDb.execSQL("INSERT INTO expiry_notices"
                    + " SELECT sender_id,sender_serial,recipient_id,recipient_serial,MAX(expires)"
                    + " FROM items WHERE committed=1 AND expires<=?"
                    + " GROUP BY sender_id,sender_serial,recipient_id,recipient_serial"
                    + " ON CONFLICT(sender_serial,recipient_serial) DO UPDATE"
                    + " SET expired_at=MAX(expired_at,excluded.expired_at)", new Object[] {mNow});
        }
        mDb.delete("items", "expires<=?", new String[] {Long.toString(mNow)});
        return mNow;
    }

    private void purgeInvalidUsers(String table, SparseIntArray serials) {
        ArrayList<String> removed = new ArrayList<>();
        try (Cursor cursor = mDb.rawQuery("SELECT rowid,sender_id,sender_serial,"
                + "recipient_id,recipient_serial FROM " + table, null)) {
            while (cursor.moveToNext()) {
                if (serials.get(cursor.getInt(1), -1) != cursor.getInt(2)
                        || serials.get(cursor.getInt(3), -1) != cursor.getInt(4)) {
                    removed.add(cursor.getString(0));
                }
            }
        }
        for (String row : removed) {
            mDb.delete(table, "rowid=?", new String[] {row});
        }
    }

    boolean hasMaintenance() {
        return DatabaseUtils.longForQuery(mDb, "SELECT EXISTS(SELECT 1 FROM items)"
                + " OR EXISTS(SELECT 1 FROM expiry_notices)", null) != 0;
    }

    void requireLive(String id) {
        try (Cursor cursor = mDb.rawQuery("SELECT expires FROM items WHERE id=?",
                new String[] {id})) {
            if (!cursor.moveToFirst() || cursor.getLong(0) <= now()) throw error(ERROR_MISSING);
        }
    }

    long nextExpiryElapsed() {
        now();
        long expiry = DatabaseUtils.longForQuery(mDb,
                "SELECT COALESCE(MIN(expires),0) FROM items", null);
        return expiry == 0 ? Long.MAX_VALUE : toElapsed(expiry);
    }

    private long toElapsed(long time) {
        return saturatedAdd(mElapsed, time > mNow ? time - mNow : 0);
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long expiryAfter(long now, long duration) {
        if (now == Long.MAX_VALUE) throw error(ERROR_UNAVAILABLE);
        return saturatedAdd(now, duration);
    }

    String insert(UserInfo sender, UserInfo target, String mime, int size, byte[] hash,
            boolean sensitive, long now) {
        if (!isSupportedMimeType(mime) || size <= 0 || size > MAX_ITEM_BYTES
                || ("text/plain".equals(mime) && size > MAX_TEXT_BYTES)
                || hash == null || hash.length != 32) {
            throw error(ERROR_INVALID);
        }
        checkQuota("", null, MAX_TOTAL_ITEMS, MAX_TOTAL_BYTES, size);
        checkQuota(" WHERE recipient_serial=?",
                new String[] {Integer.toString(target.serialNumber)},
                MAX_INBOX_ITEMS, MAX_INBOX_BYTES, size);
        if (DatabaseUtils.longForQuery(mDb,
                "SELECT COUNT(*) FROM items WHERE committed=0", null) >= 4
                || DatabaseUtils.longForQuery(mDb,
                        "SELECT COUNT(*) FROM items WHERE committed=0 AND sender_serial=?",
                        new String[] {Integer.toString(sender.serialNumber)}) != 0) {
            throw error(ERROR_FULL);
        }
        String id = UUID.randomUUID().toString();
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("sender_id", sender.id);
        values.put("sender_serial", sender.serialNumber);
        values.put("recipient_id", target.id);
        values.put("recipient_serial", target.serialNumber);
        values.put("mime", mime);
        values.put("size", size);
        values.put("written", 0);
        values.put("sensitive", sensitive);
        values.put("sha256", hash);
        values.put("created", now);
        values.put("expires", expiryAfter(now, UPLOAD_MILLIS));
        values.put("committed", 0);
        mDb.insertOrThrow("items", null, values);
        return id;
    }

    private void checkQuota(String where, String[] args, int countLimit, long sizeLimit,
            int addedSize) {
        try (Cursor cursor = mDb.rawQuery("SELECT COUNT(*),COALESCE(SUM(size),0) FROM items"
                + where, args)) {
            cursor.moveToFirst();
            if (cursor.getLong(0) >= countLimit || cursor.getLong(1) + addedSize > sizeLimit) {
                throw error(ERROR_FULL);
            }
        }
    }

    Pending pending(String id, UserInfo sender) {
        validateId(id);
        requireLive(id);
        try (Cursor cursor = mDb.rawQuery("SELECT recipient_id,recipient_serial,mime,size,"
                + "written,sha256 FROM items WHERE id=? AND sender_id=?"
                + " AND sender_serial=? AND committed=0",
                new String[] {id, Integer.toString(sender.id), Integer.toString(sender.serialNumber)})) {
            if (!cursor.moveToFirst()) throw error(ERROR_MISSING);
            return new Pending(cursor.getInt(0), cursor.getInt(1), cursor.getString(2),
                    cursor.getInt(3), cursor.getInt(4), cursor.getBlob(5));
        }
    }

    void append(String id, Pending pending, int offset, byte[] data) {
        int expected = Math.min(CHUNK_BYTES, pending.size - pending.written);
        if (offset != pending.written || data == null || expected <= 0
                || data.length != expected) {
            throw error(ERROR_INVALID);
        }
        if (offset == 0 && !matchesMime(pending.mime, data)) throw error(ERROR_INVALID);
        ContentValues values = new ContentValues();
        values.put("item_id", id);
        values.put("offset", offset);
        values.put("data", data);
        mDb.insertOrThrow("chunks", null, values);
        mDb.execSQL("UPDATE items SET written=? WHERE id=?",
                new Object[] {offset + data.length, id});
    }

    void commit(String id, Pending pending, long now) {
        if (pending.written != pending.size) throw error(ERROR_INVALID);
        MessageDigest digest = newDigest();
        int size = 0;
        try (Cursor cursor = mDb.rawQuery("SELECT offset,data FROM chunks WHERE item_id=?"
                + " ORDER BY offset", new String[] {id})) {
            while (cursor.moveToNext()) {
                if (cursor.getInt(0) != size) throw error(ERROR_INVALID);
                byte[] data = cursor.getBlob(1);
                size += data.length;
                digest.update(data);
            }
        }
        if (size != pending.size || !MessageDigest.isEqual(digest.digest(), pending.hash)) {
            throw error(ERROR_INVALID);
        }
        requireLive(id);
        now = now();
        persistClock(true);
        mDb.execSQL("UPDATE items SET committed=1,created=?,expires=? WHERE id=?",
                new Object[] {now, expiryAfter(now, RETENTION_MILLIS), id});
    }

    ClipboardQueueEntry[] list(UserInfo recipient) {
        ArrayList<ClipboardQueueEntry> entries = new ArrayList<>();
        try (Cursor cursor = mDb.rawQuery("SELECT id,sender_id,sender_serial,mime,size,"
                + "created,expires,sensitive,sha256 FROM items WHERE recipient_id=?"
                + " AND recipient_serial=? AND committed=1 ORDER BY created DESC,id",
                new String[] {Integer.toString(recipient.id),
                        Integer.toString(recipient.serialNumber)})) {
            while (cursor.moveToNext()) {
                if (cursor.getLong(6) <= now()) continue;
                ClipboardQueueEntry entry = new ClipboardQueueEntry();
                entry.id = cursor.getString(0);
                entry.senderId = cursor.getInt(1);
                entry.senderSerial = cursor.getInt(2);
                entry.mimeType = cursor.getString(3);
                entry.size = cursor.getInt(4);
                entry.createdAt = cursor.getLong(5);
                entry.expiresAt = cursor.getLong(6);
                entry.expiresElapsedRealtime = toElapsed(entry.expiresAt);
                entry.sensitive = cursor.getInt(7) != 0;
                entry.sha256 = cursor.getBlob(8);
                entries.add(entry);
            }
        }
        return entries.toArray(new ClipboardQueueEntry[0]);
    }

    ArrayList<ExpiryNotice> expiryNotices(UserInfo recipient) {
        ArrayList<ExpiryNotice> notices = new ArrayList<>();
        try (Cursor cursor = mDb.rawQuery("SELECT sender_id,sender_serial,expired_at"
                + " FROM expiry_notices WHERE recipient_id=? AND recipient_serial=?",
                new String[] {Integer.toString(recipient.id),
                        Integer.toString(recipient.serialNumber)})) {
            while (cursor.moveToNext()) {
                notices.add(new ExpiryNotice(cursor.getInt(0), cursor.getInt(1), cursor.getLong(2)));
            }
        }
        return notices;
    }

    int[] peerForDelete(String id, UserInfo caller) {
        validateId(id);
        String userId = Integer.toString(caller.id);
        String serial = Integer.toString(caller.serialNumber);
        try (Cursor cursor = mDb.rawQuery("SELECT sender_id,sender_serial,"
                + "recipient_id,recipient_serial FROM items WHERE id=? AND"
                + " ((sender_id=? AND sender_serial=?) OR"
                + " (recipient_id=? AND recipient_serial=? AND committed=1))",
                new String[] {id, userId, serial, userId, serial})) {
            if (!cursor.moveToFirst()) throw error(ERROR_MISSING);
            int peer = cursor.getInt(0) == caller.id && cursor.getInt(1) == caller.serialNumber
                    ? 2 : 0;
            return new int[] {cursor.getInt(peer), cursor.getInt(peer + 1)};
        }
    }

    int[] sourceOf(String id, UserInfo recipient) {
        validateId(id);
        requireLive(id);
        try (Cursor cursor = mDb.rawQuery("SELECT sender_id,sender_serial FROM items WHERE id=?"
                + " AND recipient_id=? AND recipient_serial=? AND committed=1",
                new String[] {id, Integer.toString(recipient.id),
                        Integer.toString(recipient.serialNumber)})) {
            if (!cursor.moveToFirst()) throw error(ERROR_MISSING);
            return new int[] {cursor.getInt(0), cursor.getInt(1)};
        }
    }

    byte[] read(String id, int offset) {
        if (offset < 0 || offset >= MAX_ITEM_BYTES || offset % CHUNK_BYTES != 0) {
            throw error(ERROR_INVALID);
        }
        try (Cursor cursor = mDb.rawQuery("SELECT data FROM chunks WHERE item_id=? AND offset=?",
                new String[] {id, Integer.toString(offset)})) {
            if (!cursor.moveToFirst()) throw error(ERROR_MISSING);
            byte[] data = cursor.getBlob(0);
            if (data.length == 0 || data.length > CHUNK_BYTES) throw error(ERROR_INVALID);
            return data;
        }
    }

    void remove(String id) {
        mDb.delete("items", "id=?", new String[] {id});
    }

    void clear(UserInfo recipient) {
        String[] args = {Integer.toString(recipient.id), Integer.toString(recipient.serialNumber)};
        mDb.delete("items", "recipient_id=? AND recipient_serial=? AND committed=1", args);
        mDb.delete("expiry_notices", "recipient_id=? AND recipient_serial=?", args);
    }

    private static boolean matchesMime(String mime, byte[] data) {
        return "text/plain".equals(mime)
                || (mime != null && mime.equals(sniffImageMimeType(data, data.length)));
    }

    private static void validateId(String id) {
        if (id == null || id.length() != 36) throw error(ERROR_INVALID);
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw error(ERROR_INVALID);
        } catch (IllegalArgumentException e) {
            throw error(ERROR_INVALID);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ServiceSpecificException error(int code) {
        return new ServiceSpecificException(code, "Queue operation refused");
    }

    static final class ExpiryNotice {
        final int senderId;
        final int senderSerial;
        final long expiredAt;

        ExpiryNotice(int senderId, int senderSerial, long expiredAt) {
            this.senderId = senderId;
            this.senderSerial = senderSerial;
            this.expiredAt = expiredAt;
        }
    }

    static final class Pending {
        final int targetId;
        final int targetSerial;
        final String mime;
        final int size;
        final int written;
        final byte[] hash;

        Pending(int targetId, int targetSerial, String mime, int size, int written, byte[] hash) {
            this.targetId = targetId;
            this.targetSerial = targetSerial;
            this.mime = mime;
            this.size = size;
            this.written = written;
            this.hash = hash;
        }
    }
}
