package com.truelockmc.streambert;

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

final class TokenStore {
    private static final String KEY_ALIAS = "streambert-tmdb";
    private final SharedPreferences preferences;

    TokenStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    String get() throws Exception {
        String value = preferences.getString("tmdb", null);
        if (value == null) return null;
        String[] fields = value.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(),
                new GCMParameterSpec(128, Base64.decode(fields[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(fields[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    void set(String token) throws Exception {
        SharedPreferences.Editor editor = preferences.edit();
        if (token.isEmpty()) {
            editor.remove("tmdb");
        } else {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            editor.putString("tmdb", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                    + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP));
        }
        if (!editor.commit()) throw new IllegalStateException("Could not save TMDB token");
    }
}
