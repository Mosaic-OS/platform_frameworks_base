package com.android.server.locksettings;

import android.annotation.Nullable;
import android.os.HandlerThread;
import android.os.UserHandle;

import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;

import java.util.Objects;
import java.util.UUID;

import static com.android.internal.widget.LockDomain.Primary;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;

public class SecretPasswordHelper {
    private final LockSettingsService lockSettingsService;
    private final LockSettingsStorage lockSettingsStorage;
    private final SyntheticPasswordManager spManager;
    private final HandlerThread backgroundThread;

    SecretPasswordHelper(LockSettingsService lockSettingsService,
            LockSettingsStorage lockSettingsStorage, SyntheticPasswordManager spManager) {
        var bgThread = new HandlerThread(UUID.randomUUID().toString());
        bgThread.start();
        this.backgroundThread = bgThread;
        this.lockSettingsService = lockSettingsService;
        this.lockSettingsStorage = lockSettingsStorage;
        this.spManager = spManager;
    }

    protected void onVerifyCredentialResult(@Nullable VerifyCredentialResponse res, @Nullable LockscreenCredential credential) {
        if (res != null && res.isMatched()) {
            return;
        }

        if (credential == null) {
            return;
        }

        // Secret credentials are stored in CE (credential-encrypted) storage.
        // If the owner user hasn't been unlocked yet (e.g. first unlock after boot),
        // CE storage is not available and reading from it would crash with
        // IllegalStateException. Skip the check entirely in that case.
        if (!android.os.storage.StorageManager.isCeStorageUnlocked(UserHandle.USER_SYSTEM)) {
            return;
        }

        // original credential is zeroized after this method returns
        LockscreenCredential credentialCopy = credential.duplicate();

        // credential verification is slow, don't block the current (usually binder) thread
        boolean posted = backgroundThread.getThreadHandler().post(() -> {
            final boolean isSecretCredential;
            try {
                isSecretCredential = isSecretCredential(credentialCopy);
            } finally {
                // invalid credential might be similar to the actual credential
                credentialCopy.zeroize();
            }
            if (isSecretCredential) {
                  SecretProfileActivator.activate(
                  lockSettingsService.getContext(),
                  lockSettingsStorage
                );
            }
        });
        if (!posted) {
            credentialCopy.zeroize();
        }
    }

    private void checkOwnerCredential(LockscreenCredential ownerCredential) {
        int userId = UserHandle.USER_SYSTEM;

        if (lockSettingsService.getCredentialType(userId) == CREDENTIAL_TYPE_NONE) {
            if (!ownerCredential.isNone()) {
                throw new IllegalArgumentException("!ownerCredential.isNone()");
            }
        } else {
            VerifyCredentialResponse response = lockSettingsService.checkCredential(ownerCredential,
                    Primary, userId, null);

            if (!response.isMatched()) {
                throw new SecurityException("owner credential verification failed; " + response);
            }
        }
    }

    protected void setSecretCredentials(LockscreenCredential ownerCredential,
                                 LockscreenCredential pin, LockscreenCredential password) {
        Objects.requireNonNull(ownerCredential, "ownerCredential");
        Objects.requireNonNull(pin, "pin");
        Objects.requireNonNull(password, "password");

        checkOwnerCredential(ownerCredential);

        if (pin.isNone() && password.isNone()) {
            // exception handling is delegated to the caller
            SecretCredentials.delete(lockSettingsStorage);
            return;
        }

        SecretCredential.validate(pin, CREDENTIAL_TYPE_PIN);
        SecretCredential.validate(password, CREDENTIAL_TYPE_PASSWORD);

        // exception handling is delegated to the caller
        SecretCredentials.create(spManager, pin, password).save(lockSettingsStorage);
    }

    protected boolean hasSecretCredentials(LockscreenCredential ownerCredential) {
        checkOwnerCredential(ownerCredential);
        return SecretCredentials.maybeGet(lockSettingsStorage) != null;
    }

    protected boolean secretCredentialsExist() {
        return SecretCredentials.maybeGet(lockSettingsStorage) != null;
    }

    private boolean isSecretCredential(LockscreenCredential credential) {
        int credentialType = credential.getType();
        switch (credentialType) {
            case CREDENTIAL_TYPE_PIN:
            case CREDENTIAL_TYPE_PASSWORD:
                SecretCredentials dc = SecretCredentials.maybeGet(lockSettingsStorage);
                return dc != null && dc.get(credentialType).verify(spManager, credential);
            default:
                return false;
        }
    }
}
