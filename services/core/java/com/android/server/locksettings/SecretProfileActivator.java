package com.android.server.locksettings;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import java.util.UUID;

public class SecretProfileActivator {
    private static final String KEY_SECRET_USER_ID = "secret_profile_user_id";

    static void activate(Context context, LockSettingsStorage storage) {
        try {
            doActivate(context, storage);
        } catch (Exception e) {
            // Keep failures non-fatal
        }
    }

    private static void doActivate(Context context, LockSettingsStorage storage) throws Exception {
        int userId = getOrCreateSecretUser(context, storage);
        if (userId == UserHandle.USER_NULL) {
            return;
        }
        ActivityManager.getService().switchUser(userId);
    }

    private static int getOrCreateSecretUser(Context context, LockSettingsStorage storage)
            throws Exception {
        UserManagerInternal umInternal = LocalServices.getService(UserManagerInternal.class);
        if (umInternal == null) {
            return UserHandle.USER_NULL;
        }

        String stored = storage.readKeyValue(KEY_SECRET_USER_ID, null, UserHandle.USER_SYSTEM);
        if (stored != null && !stored.isEmpty()) {
            try {
                int storedId = Integer.parseInt(stored);
                // Use the internal (unfiltered) lookup: the public getUserInfo() hides the secret
                // user, so it would report null here and force a duplicate creation.
                UserInfo existing = umInternal.getUserInfo(storedId);
                if (existing != null && !existing.partial && !existing.preCreated) {
                    return storedId;
                }
            } catch (NumberFormatException e) {
                // stored value is corrupt, fall through to create a new user
            }
        }

        UserInfo created = umInternal.createUserEvenWhenDisallowed(
                UUID.randomUUID().toString(),
                UserManager.USER_TYPE_FULL_SECONDARY,
                UserInfo.FLAG_FULL,
                /* disallowedPackages= */ null,
                UserManagerInternal.CREATE_USER_HIDDEN_TOKEN);
        if (created == null) {
            return UserHandle.USER_NULL;
        }

        try {
            storage.writeKeyValue(KEY_SECRET_USER_ID, String.valueOf(created.id),
                    UserHandle.USER_SYSTEM);
        } catch (Throwable t) {
            try {
                umInternal.removeUserEvenWhenDisallowed(created.id);
            } catch (Throwable removeError) {}
            return UserHandle.USER_NULL;
        }
        return created.id;
    }
}
