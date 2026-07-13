package com.android.server.locksettings;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.PasswordValidationError;
import com.android.server.locksettings.SyntheticPasswordManager.PasswordData;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

class SecretCredential {
    private static final int MAX_FIELD_LENGTH = 64 * 1024;
    private final PasswordData salt;
    private final byte[] hashedCredential;

    SecretCredential(PasswordData salt, byte[] hashedCredential) {
        this.salt = salt;
        this.hashedCredential = hashedCredential;
    }

    static SecretCredential create(LockscreenCredential credential, SyntheticPasswordManager spm) {
        PasswordData salt = PasswordData.create(credential.getType(),
                LockPatternUtils.PIN_LENGTH_UNAVAILABLE);
        byte[] hashedCredential = spm.stretchLskf(credential, salt);
        return new SecretCredential(salt, hashedCredential);
    }

    boolean verify(SyntheticPasswordManager spm, LockscreenCredential credential) {
        return MessageDigest.isEqual(hashedCredential, spm.stretchLskf(credential, salt));
    }

    int getType() {
        return salt.credentialType;
    }

    void serialize(DataOutputStream s) throws IOException {
        byte[] saltBytes = salt.toBytes();
        s.writeInt(saltBytes.length);
        s.write(saltBytes);
        s.writeInt(hashedCredential.length);
        s.write(hashedCredential);
    }

    static SecretCredential deserialize(DataInputStream s) throws IOException {
        PasswordData salt = PasswordData.fromBytes(readBoundedBytes(s));
        byte[] hashedCredential = readBoundedBytes(s);
        return new SecretCredential(salt, hashedCredential);
    }

    private static byte[] readBoundedBytes(DataInputStream s) throws IOException {
        int len = s.readInt();
        if (len < 0 || len > MAX_FIELD_LENGTH) {
            throw new IOException("Invalid serialized field length: " + len);
        }
        byte[] out = s.readNBytes(len);
        if (out.length != len) {
            throw new EOFException("Truncated serialized field: expected " + len
                    + " bytes, got " + out.length);
        }
        return out;
    }

    static void validate(LockscreenCredential credential, int expectedType) {
        int type = credential.getType();
        if (type != expectedType) {
            throw new IllegalArgumentException("type mismatch: expected " + expectedType + ", got " + type);
        }

        List<PasswordValidationError> validationErrors =
                LockPatternUtils.validateSecretCredential(credential);
        if (!validationErrors.isEmpty()) {
            throw new IllegalArgumentException("validation failed: " +
                    Arrays.toString(validationErrors.toArray()));
        }
    }
}
