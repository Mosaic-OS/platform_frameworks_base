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

package com.android.internal.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.TreeSet;

/** @hide */
public final class ScreenshotProtectionUtils {
    public static final int MAX_PACKAGES_LENGTH = 16384;
    private static final int MAX_PACKAGE_LENGTH = 255;
    private static final Set<String> NEVER_ALLOWED = Set.of(
            "android", "com.android.systemui", "com.android.settings");

    private ScreenshotProtectionUtils() {}

    public static boolean canAllow(String packageName) {
        return packageName != null && !NEVER_ALLOWED.contains(packageName);
    }

    public static boolean isEffectivelyAllowed(boolean detected, boolean allowed, boolean blocked) {
        return !blocked && (allowed || !detected);
    }

    public static void setOverride(String packageName, boolean allow, boolean detected,
            Set<String> allowed, Set<String> blocked) {
        allowed.remove(packageName);
        blocked.remove(packageName);
        if (canAllow(packageName) && allow == detected) {
            (allow ? allowed : blocked).add(packageName);
        }
    }

    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        try {
            parse(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static Set<String> parse(String value) {
        if (value == null || value.isEmpty()) {
            return new HashSet<>();
        }
        if (value.length() > MAX_PACKAGES_LENGTH) {
            throw new IllegalArgumentException("Screenshot protection list is too long");
        }
        final Set<String> packages = new HashSet<>();
        for (String packageName : value.split(",", -1)) {
            if (!isPackageName(packageName) || !packages.add(packageName)) {
                throw new IllegalArgumentException("Invalid screenshot protection list");
            }
        }
        return packages;
    }

    public static String serialize(Set<String> packages) {
        final String value = String.join(",", new TreeSet<>(packages));
        if (!isValid(value)) {
            throw new IllegalArgumentException("Invalid screenshot protection list");
        }
        return value;
    }

    public static String identity(int uid, long firstInstallTime) {
        if (uid < 0 || firstInstallTime <= 0) {
            throw new IllegalArgumentException("Invalid package installation identity");
        }
        return uid + ":" + firstInstallTime;
    }

    public static boolean areIdentitiesValid(String value) {
        if (value == null) {
            return false;
        }
        try {
            parseIdentities(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static Map<String, String> parseIdentities(String value) {
        final Map<String, String> identities = new HashMap<>();
        if (value == null || value.isEmpty()) {
            return identities;
        }
        if (value.length() > MAX_PACKAGES_LENGTH * 4) {
            throw new IllegalArgumentException("Screenshot protection identities are too long");
        }
        for (String entry : value.split(",", -1)) {
            final String[] parts = entry.split(":", -1);
            if (parts.length != 3 || !isPackageName(parts[0])) {
                throw new IllegalArgumentException("Invalid screenshot protection identity");
            }
            final String identity = identity(Integer.parseInt(parts[1]), Long.parseLong(parts[2]));
            if (!identity.equals(parts[1] + ":" + parts[2])
                    || identities.put(parts[0], identity) != null) {
                throw new IllegalArgumentException("Invalid screenshot protection identities");
            }
        }
        return identities;
    }

    public static String serializeIdentities(Map<String, String> identities) {
        final StringBuilder value = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(identities).entrySet()) {
            if (value.length() != 0) {
                value.append(',');
            }
            value.append(entry.getKey()).append(':').append(entry.getValue());
        }
        final String result = value.toString();
        if (!areIdentitiesValid(result)) {
            throw new IllegalArgumentException("Invalid screenshot protection identities");
        }
        return result;
    }

    public static boolean isPackageName(String name) {
        if ("android".equals(name)) {
            return true;
        }
        if (name.isEmpty() || name.length() > MAX_PACKAGE_LENGTH) {
            return false;
        }
        boolean start = true;
        boolean separator = false;
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                start = false;
            } else if (!start && ((c >= '0' && c <= '9') || c == '_')) {
                continue;
            } else if (!start && c == '.') {
                start = true;
                separator = true;
            } else {
                return false;
            }
        }
        return separator && !start;
    }
}
