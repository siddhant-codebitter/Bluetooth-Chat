package com.example.bluetoothchat.crypto;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Who am I? Stores this phone's identity and app settings:
 *
 * - An EC key pair (P-256), created on first launch.
 * - The device ID, derived from the public key (see CryptoManager.deriveDeviceId),
 *   so nobody can claim your ID with a different key.
 * - The display name, and the relay / mesh on-off switches.
 *
 * The private key is encrypted with an AES key that lives in the Android
 * Keystore before it is saved, so the file on disk is useless without this phone.
 */
public class IdentityManager {

    private static final String TAG = "IdentityManager";

    private static final String PREFS = "identity_prefs";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_NAME = "display_name";
    private static final String KEY_PUBLIC = "public_key";
    private static final String KEY_PRIVATE = "private_key";
    private static final String KEY_RELAY = "relay_enabled";
    private static final String KEY_MESH = "mesh_enabled";

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String WRAP_ALIAS = "bluetoothchat_identity_wrap_key";
    private static final String PREFIX_WRAPPED = "w:";
    private static final String PREFIX_RAW = "r:";

    public static final int MAX_NAME_LENGTH = 24;

    private final SharedPreferences prefs;
    private PrivateKey privateKey; // cached after first load

    public IdentityManager(@NonNull Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ensureIdentity();
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    /** Full device ID (32 hex characters). Used as source/destination in packets. */
    @NonNull
    public String getDeviceId() {
        return prefs.getString(KEY_DEVICE_ID, "");
    }

    /** First 8 characters of the device ID, for showing in the UI. */
    @NonNull
    public String getShortDeviceId() {
        String id = getDeviceId();
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    /** Public key (X.509 encoding) as Base64. Safe to share with other phones. */
    @NonNull
    public String getPublicKeyBase64() {
        return prefs.getString(KEY_PUBLIC, "");
    }

    /** The private key. Never send or log this. */
    @NonNull
    public synchronized PrivateKey getPrivateKey() {
        if (privateKey == null) {
            ensureIdentity();
        }
        return privateKey;
    }

    // ------------------------------------------------------------------
    // Display name
    // ------------------------------------------------------------------

    @NonNull
    public String getDisplayName() {
        return prefs.getString(KEY_NAME, "");
    }

    /** Trims, collapses spaces and limits the length. */
    public void setDisplayName(String name) {
        String clean = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (clean.length() > MAX_NAME_LENGTH) {
            clean = clean.substring(0, MAX_NAME_LENGTH).trim();
        }
        prefs.edit().putString(KEY_NAME, clean).apply();
    }

    /** True once the user has picked a name (onboarding finished). */
    public boolean hasDisplayName() {
        return !getDisplayName().isEmpty();
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    /** Whether this phone passes on messages for other phones. Default: on. */
    public boolean isRelayEnabled() {
        return prefs.getBoolean(KEY_RELAY, true);
    }

    public void setRelayEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_RELAY, enabled).apply();
    }

    /** Whether the mesh (scanning, connecting, service) should be running. Default: on. */
    public boolean isMeshEnabled() {
        return prefs.getBoolean(KEY_MESH, true);
    }

    public void setMeshEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_MESH, enabled).apply();
    }

    // ------------------------------------------------------------------
    // Key creation and storage
    // ------------------------------------------------------------------

    private synchronized void ensureIdentity() {
        String id = prefs.getString(KEY_DEVICE_ID, null);
        String pub = prefs.getString(KEY_PUBLIC, null);
        String priv = prefs.getString(KEY_PRIVATE, null);

        if (id != null && pub != null && priv != null) {
            try {
                privateKey = decodePrivateKey(priv);
                return;
            } catch (GeneralSecurityException | IOException | RuntimeException e) {
                // E.g. the Keystore key was lost. The old identity cannot be used any more.
                Log.w(TAG, "Stored identity unreadable, creating a new one", e);
            }
        }
        generateIdentity();
    }

    private void generateIdentity() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();

            byte[] publicBytes = pair.getPublic().getEncoded();
            byte[] privateBytes = pair.getPrivate().getEncoded();

            prefs.edit()
                    .putString(KEY_DEVICE_ID, CryptoManager.deriveDeviceId(publicBytes))
                    .putString(KEY_PUBLIC, Base64.encodeToString(publicBytes, Base64.NO_WRAP))
                    .putString(KEY_PRIVATE, encodePrivateKey(privateBytes))
                    .apply();

            Arrays.fill(privateBytes, (byte) 0);
            privateKey = pair.getPrivate();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot create device identity", e);
        }
    }

    /** Encrypts the private key with the Keystore key. Falls back to plain storage if unavailable. */
    @NonNull
    private String encodePrivateKey(@NonNull byte[] pkcs8) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(pkcs8);

            byte[] packed = new byte[1 + iv.length + encrypted.length];
            packed[0] = (byte) iv.length;
            System.arraycopy(iv, 0, packed, 1, iv.length);
            System.arraycopy(encrypted, 0, packed, 1 + iv.length, encrypted.length);
            return PREFIX_WRAPPED + Base64.encodeToString(packed, Base64.NO_WRAP);
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            Log.w(TAG, "Keystore unavailable, storing private key without wrapping", e);
            return PREFIX_RAW + Base64.encodeToString(pkcs8, Base64.NO_WRAP);
        }
    }

    @NonNull
    private PrivateKey decodePrivateKey(@NonNull String stored)
            throws GeneralSecurityException, IOException {
        byte[] pkcs8;
        if (stored.startsWith(PREFIX_WRAPPED)) {
            byte[] packed = Base64.decode(stored.substring(PREFIX_WRAPPED.length()),
                    Base64.NO_WRAP);
            int ivLength = packed[0] & 0xFF;
            byte[] iv = Arrays.copyOfRange(packed, 1, 1 + ivLength);
            byte[] encrypted = Arrays.copyOfRange(packed, 1 + ivLength, packed.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrapKey(), new GCMParameterSpec(128, iv));
            pkcs8 = cipher.doFinal(encrypted);
        } else if (stored.startsWith(PREFIX_RAW)) {
            pkcs8 = Base64.decode(stored.substring(PREFIX_RAW.length()), Base64.NO_WRAP);
        } else {
            throw new GeneralSecurityException("Unknown private key format");
        }

        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
    }

    @NonNull
    private SecretKey getOrCreateWrapKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(WRAP_ALIAS)) {
            return (SecretKey) keyStore.getKey(WRAP_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}