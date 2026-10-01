package com.xauusd.mobileengine;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;

public final class SecurityStore {
    private static final String KS = "AndroidKeyStore";
    private static final String ALIAS = "xauusd_v52_secure";
    private static final String PREF = "xauusd_secure";

    private final Context context;
    private final SharedPreferences prefs;

    public SecurityStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        ensureKey();
    }

    private void ensureKey() {
        try {
            KeyStore ks = KeyStore.getInstance(KS);
            ks.load(null);
            if (ks.containsAlias(ALIAS)) return;

            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            kg.generateKey();
        } catch (Exception e) {
            throw new IllegalStateException("Secure storage unavailable", e);
        }
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
    }

    public void put(String name, String value) {
        try {
            if (value == null) value = "";
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] data = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + data.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(data, 0, packed, iv.length, data.length);
            prefs.edit().putString(name, Base64.getUrlEncoder().withoutPadding().encodeToString(packed)).apply();
        } catch (Exception e) {
            throw new IllegalStateException("Secure write failed", e);
        }
    }

    public String get(String name, String fallback) {
        String packed = prefs.getString(name, null);
        if (packed == null) return fallback;
        try {
            byte[] all = Base64.getUrlDecoder().decode(packed);
            byte[] iv = new byte[12];
            byte[] data = new byte[all.length - iv.length];
            System.arraycopy(all, 0, iv, 0, iv.length);
            System.arraycopy(all, iv.length, data, 0, data.length);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return fallback;
        }
    }

    public void remove(String name) { prefs.edit().remove(name).apply(); }
    public void clear() { prefs.edit().clear().apply(); }
    public SharedPreferences rawPrefs() { return prefs; }
}
