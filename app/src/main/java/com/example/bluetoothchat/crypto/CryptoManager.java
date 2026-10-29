package com.example.bluetoothchat.crypto;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * End-to-end encryption between two phones, so relay phones only see ciphertext.
 *
 * How it works:
 *   1. Both phones have a P-256 key pair (see IdentityManager).
 *   2. ECDH(my private key, their public key) gives a shared secret that only
 *      the two of them can compute.
 *   3. HKDF-SHA256 turns the secret into an AES-256 key.
 *   4. Messages are encrypted with AES-GCM (random 12-byte IV). GCM also detects
 *      tampering, and the 'aad' parameter ties a message to its routing header
 *      (e.g. source + destination ID) so a relay cannot re-address it.
 *
 * Encrypted output layout: [12-byte IV][ciphertext + 16-byte tag]
 *
 * Device IDs are the first 16 bytes of SHA-256(public key). Always check a
 * received public key with isPeerKeyValid() before trusting it, so a relay
 * cannot swap in its own key for someone else's ID.
 */
public class CryptoManager {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8;
    private static final String HKDF_INFO_PREFIX = "BluetoothChat/v1/message-key";

    private final IdentityManager identity;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, SecretKey> keyCache = new ConcurrentHashMap<>();

    public CryptoManager(@NonNull IdentityManager identity) {
        this.identity = identity;
    }

    // ------------------------------------------------------------------
    // Encrypt / decrypt
    // ------------------------------------------------------------------

    /**
     * @param plaintext           data to protect (e.g. message text as UTF-8)
     * @param peerPublicKeyBase64 the recipient's public key
     * @param aad                 extra data that must match when decrypting (may be null)
     * @return IV followed by ciphertext
     */
    @NonNull
    public byte[] encrypt(@NonNull byte[] plaintext,
                          @NonNull String peerPublicKeyBase64,
                          @Nullable byte[] aad) throws GeneralSecurityException {
        SecretKey key = getSharedKey(peerPublicKeyBase64);
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        if (aad != null) cipher.updateAAD(aad);
        byte[] encrypted = cipher.doFinal(plaintext);

        byte[] out = new byte[IV_LENGTH + encrypted.length];
        System.arraycopy(iv, 0, out, 0, IV_LENGTH);
        System.arraycopy(encrypted, 0, out, IV_LENGTH, encrypted.length);
        return out;
    }

    /**
     * @throws GeneralSecurityException if the data was changed, the key is wrong
     *                                  or 'aad' does not match what was used to encrypt
     */
    @NonNull
    public byte[] decrypt(@NonNull byte[] data,
                          @NonNull String peerPublicKeyBase64,
                          @Nullable byte[] aad) throws GeneralSecurityException {
        if (data.length < IV_LENGTH + TAG_LENGTH_BYTES) {
            throw new GeneralSecurityException("Encrypted data too short");
        }
        SecretKey key = getSharedKey(peerPublicKeyBase64);
        byte[] iv = Arrays.copyOfRange(data, 0, IV_LENGTH);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        if (aad != null) cipher.updateAAD(aad);
        return cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH);
    }

    // ------------------------------------------------------------------
    // Identity helpers (static, no keys needed)
    // ------------------------------------------------------------------

    /** Device ID = first 16 bytes of SHA-256(public key), as 32 hex characters. */
    @NonNull
    public static String deriveDeviceId(@NonNull byte[] publicKeyEncoded) {
        byte[] hash = sha256(publicKeyEncoded);
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 16; i++) {
            sb.append(String.format("%02x", hash[i] & 0xFF));
        }
        return sb.toString();
    }

    /**
     * True if 'peerPublicKeyBase64' is a valid P-256 key AND it matches the claimed
     * device ID. Reject the peer if this returns false.
     */
    public static boolean isPeerKeyValid(@NonNull String peerDeviceId,
                                         @NonNull String peerPublicKeyBase64) {
        try {
            byte[] encoded = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP);
            if (!deriveDeviceId(encoded).equalsIgnoreCase(peerDeviceId)) return false;
            decodePublicKey(encoded); // throws if it is not a valid EC key
            return true;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Formats an ID for people to compare, e.g. "3F9A 1C7E 02B4 ...". */
    @NonNull
    public static String formatFingerprint(@NonNull String deviceId) {
        String upper = deviceId.toUpperCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < upper.length(); i += 4) {
            if (i > 0) sb.append(' ');
            sb.append(upper, i, Math.min(i + 4, upper.length()));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Key agreement
    // ------------------------------------------------------------------

    @NonNull
    private SecretKey getSharedKey(@NonNull String peerPublicKeyBase64)
            throws GeneralSecurityException {
        SecretKey cached = keyCache.get(peerPublicKeyBase64);
        if (cached != null) return cached;

        byte[] peerEncoded;
        try {
            peerEncoded = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Bad public key encoding", e);
        }
        PublicKey peerKey = decodePublicKey(peerEncoded);

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(identity.getPrivateKey());
        agreement.doPhase(peerKey, true);
        byte[] secret = agreement.generateSecret();

        // Order the two public keys so both phones build the identical HKDF 'info'.
        String mine = identity.getPublicKeyBase64();
        String first = mine.compareTo(peerPublicKeyBase64) <= 0 ? mine : peerPublicKeyBase64;
        String second = first.equals(mine) ? peerPublicKeyBase64 : mine;
        byte[] info = (HKDF_INFO_PREFIX + "|" + first + "|" + second)
                .getBytes(StandardCharsets.UTF_8);

        byte[] keyBytes = hkdfSha256(secret, info);
        Arrays.fill(secret, (byte) 0);

        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        Arrays.fill(keyBytes, (byte) 0);
        keyCache.put(peerPublicKeyBase64, key);
        return key;
    }

    @NonNull
    private static PublicKey decodePublicKey(@NonNull byte[] encoded)
            throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
    }

    /** HKDF (RFC 5869) with SHA-256, no salt, producing one 32-byte block. */
    @NonNull
    private static byte[] hkdfSha256(@NonNull byte[] inputKeyMaterial, @NonNull byte[] info)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");

        // Extract
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(inputKeyMaterial);

        // Expand (first block only: 32 bytes = AES-256 key)
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 0x01);
        byte[] okm = mac.doFinal();

        Arrays.fill(prk, (byte) 0);
        return okm;
    }

    @NonNull
    private static byte[] sha256(@NonNull byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}