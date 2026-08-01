package com.fifteencode.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecurePreferences {
    private static final String STORE = "15code_secure_credentials";
    private static final String KEY_ALIAS = "15code_android_credentials_v1";
    private final SharedPreferences prefs;

    SecurePreferences(Context context) {
        prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    String get(String key) {
        String encoded = prefs.getString(key, null);
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
            int ivLength = packed[0] & 0xff;
            byte[] iv = new byte[ivLength];
            byte[] encrypted = new byte[packed.length - ivLength - 1];
            System.arraycopy(packed, 1, iv, 0, ivLength);
            System.arraycopy(packed, 1 + ivLength, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            remove(key);
            return null;
        }
    }

    void put(String key, String value) {
        if (value == null || value.isEmpty()) {
            remove(key);
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[1 + iv.length + encrypted.length];
            packed[0] = (byte) iv.length;
            System.arraycopy(iv, 0, packed, 1, iv.length);
            System.arraycopy(encrypted, 0, packed, 1 + iv.length, encrypted.length);
            prefs.edit().putString(key, Base64.encodeToString(packed, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            throw new IllegalStateException("无法安全保存登录凭证", e);
        }
    }

    void remove(String key) {
        prefs.edit().remove(key).apply();
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
