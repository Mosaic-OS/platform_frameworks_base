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

package android.content;

/** @hide */
public final class ClipboardQueueContract {
    public static final String SERVICE_NAME = "clipboard_queue";
    public static final String PERMISSION = "android.permission.MANAGE_CLIPBOARD_QUEUE";
    public static final int CHUNK_BYTES = 48 * 1024;
    public static final int MAX_TEXT_BYTES = 128 * 1024;
    public static final int MAX_ITEM_BYTES = 8 * 1024 * 1024;
    public static final long MAX_INBOX_BYTES = 32L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    public static final int MAX_INBOX_ITEMS = 64;
    public static final int MAX_TOTAL_ITEMS = 256;
    public static final long RETENTION_MILLIS = 24L * 60 * 60 * 1000;
    public static final long UPLOAD_MILLIS = 10L * 60 * 1000;
    public static final int ERROR_FULL = 1;
    public static final int ERROR_UNAVAILABLE = 2;
    public static final int ERROR_INVALID = 3;
    public static final int ERROR_MISSING = 4;
    public static final int ERROR_EXPORT_BLOCKED = 5;
    public static final int ERROR_IMPORT_BLOCKED = 6;
    public static final int ERROR_TARGET_IMPORT_BLOCKED = 7;

    public static final int ERROR_STORAGE = 8;

    private ClipboardQueueContract() {}

    public static String sniffImageMimeType(byte[] data, int length) {
        if (length >= 8 && data[0] == (byte) 137 && data[1] == 80 && data[2] == 78
                && data[3] == 71 && data[4] == 13 && data[5] == 10 && data[6] == 26
                && data[7] == 10) {
            return "image/png";
        }
        if (length >= 3 && data[0] == (byte) 0xff && data[1] == (byte) 0xd8
                && data[2] == (byte) 0xff) {
            return "image/jpeg";
        }
        if (length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F'
                && data[3] == 'F' && data[8] == 'W' && data[9] == 'E' && data[10] == 'B'
                && data[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    public static boolean isSupportedMimeType(String mime) {
        return "text/plain".equals(mime) || "image/png".equals(mime)
                || "image/jpeg".equals(mime) || "image/webp".equals(mime);
    }
}
