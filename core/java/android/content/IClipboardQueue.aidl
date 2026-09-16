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

import android.content.ClipboardQueueInbox;
import android.content.ClipboardQueueTarget;

/** @hide */
interface IClipboardQueue {
    @PermissionManuallyEnforced
    ClipboardQueueTarget[] getTargets();
    @PermissionManuallyEnforced
    String beginInsert(int targetId, int targetSerial, String mimeType, int size,
            in byte[] sha256, boolean sensitive);
    @PermissionManuallyEnforced
    void append(String id, int offset, in byte[] data);
    @PermissionManuallyEnforced
    void commitInsert(String id);
    @PermissionManuallyEnforced
    void abortInsert(String id);
    @PermissionManuallyEnforced
    ClipboardQueueInbox listInbox();
    @PermissionManuallyEnforced
    byte[] read(String id, int offset);
    @PermissionManuallyEnforced
    void delete(String id);
    @PermissionManuallyEnforced
    void clearInbox();
}
