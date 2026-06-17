package com.android.server.locksettings;

import android.annotation.Nullable;
import android.database.sqlite.SQLiteDiskIOException;
import android.os.UserHandle;

import com.android.internal.widget.LockscreenCredential;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import libcore.util.HexEncoding;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;

class SecretCredentials {
    final SecretCredential pin;
    final SecretCredential password;

    SecretCredentials(SecretCredential pin, SecretCredential password) {
        if (pin.getType() != CREDENTIAL_TYPE_PIN) {
            throw new IllegalArgumentException();
        }
        this.pin = pin;
        if (password.getType() != CREDENTIAL_TYPE_PASSWORD) {
            throw new IllegalArgumentException();
        }
        this.password = password;
    }

    static SecretCredentials create(SyntheticPasswordManager spm,
                                    LockscreenCredential pin, LockscreenCredential password) {
        return new SecretCredentials(SecretCredential.create(pin, spm),
                SecretCredential.create(password, spm));
    }

    private static final String LOCK_SETTINGS_STORAGE_KEY = "secret_credentials";
    private static final int LOCK_SETTINGS_STORAGE_USER_ID = UserHandle.USER_SYSTEM;

    void save(LockSettingsStorage lss) {
        lss.setString(LOCK_SETTINGS_STORAGE_KEY, serialize(), LOCK_SETTINGS_STORAGE_USER_ID);
    }

    @Nullable
    static SecretCredentials maybeGet(LockSettingsStorage lss) {
        String s = lss.getString(LOCK_SETTINGS_STORAGE_KEY, null, LOCK_SETTINGS_STORAGE_USER_ID);
        if (s == null) {
            return null;
        }
        return deserialize(s);
    }

    static void delete(LockSettingsStorage lss) {
        lss.removeKey(LOCK_SETTINGS_STORAGE_KEY, LOCK_SETTINGS_STORAGE_USER_ID);
    }

    SecretCredential get(int type) {
        return switch (type) {
            case CREDENTIAL_TYPE_PIN -> pin;
            case CREDENTIAL_TYPE_PASSWORD -> password;
            default -> throw new IllegalArgumentException(Integer.toString(type));
        };
    }

    private static final int VERSION = 0;

    private String serialize() {
        var bos = new ByteArrayOutputStream(1000);
        var s = new DataOutputStream(bos);
        try {
            s.writeByte(VERSION);
            pin.serialize(s);
            password.serialize(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        return HexEncoding.encodeToString(bos.toByteArray());
    }

    private static SecretCredentials deserialize(String str) {
        var s = new DataInputStream(new ByteArrayInputStream(HexEncoding.decode(str)));
        try {
            int version = s.readByte();
            if (version > VERSION) {
                throw new IllegalArgumentException(str);
            }
            var pin = SecretCredential.deserialize(s);
            var password = SecretCredential.deserialize(s);
            if (s.available() != 0) {
                throw new IllegalArgumentException(str);
            }
            return new SecretCredentials(pin, password);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
