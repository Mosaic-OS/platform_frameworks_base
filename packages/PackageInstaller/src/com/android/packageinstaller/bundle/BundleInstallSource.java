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

package com.android.packageinstaller.bundle;

import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;

import java.util.Locale;
import java.util.Set;

public final class BundleInstallSource {
    private static final Set<String> MIME_TYPES = Set.of(
            "application/vnd.android.apks",
            "application/vnd.android.xapk",
            "application/vnd.android.apkm",
            "application/vnd.apkm",
            "application/xapk-package-archive");

    private BundleInstallSource() {}

    public static boolean isBundle(Intent intent) {
        Uri uri = intent.getData();
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            return false;
        }
        String type = intent.getType();
        if (type != null && MIME_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String name = uri.getLastPathSegment();
        if (name == null) {
            return false;
        }
        name = name.toLowerCase(Locale.ROOT);
        return name.endsWith(".apks") || name.endsWith(".xapk") || name.endsWith(".apkm");
    }
}
