package com.jhopanstore.litevpn.core;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/**
 * Offline AES-GCM license codec. The encryption key is derived from the
 * customer HWID, so only the device with that HWID can decrypt the payload.
 */
public final class LicenseCodec {
    private static final String SALT = "jhopanstore-license-v1";
    private static final String PREFIX = "JLS1:";
    private static final byte VERSION = 0x01;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private LicenseCodec() {}

    public static boolean isEncoded(String text) { return text != null && text.startsWith(PREFIX); }

    public static String encode(License license) throws Exception {
        JSONObject root = new JSONObject();
        root.put("v", license.vless);
        root.put("n", license.name);
        root.put("m", license.message);
        root.put("c", license.connectedMessage);
        root.put("h", license.hwid);
        root.put("l", license.lock);
        root.put("e", license.expiry);
        byte[] plain = root.toString().getBytes(StandardCharsets.UTF_8);

        SecretKeySpec key = keyFor(license.hwid);
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] ct = cipher.doFinal(plain);

        byte[] payload = new byte[1 + IV_LEN + ct.length];
        payload[0] = VERSION;
        System.arraycopy(iv, 0, payload, 1, IV_LEN);
        System.arraycopy(ct, 0, payload, 1 + IV_LEN, ct.length);
        return PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP);
    }

    public static License decode(String text, String deviceHwid) throws Exception {
        if (!isEncoded(text)) throw new IllegalArgumentException("Invalid license format");
        byte[] payload = Base64.decode(text.substring(PREFIX.length()), Base64.NO_WRAP);
        if (payload.length < 1 + IV_LEN) throw new IllegalArgumentException("Corrupt license");
        byte version = payload[0];
        if (version != VERSION) throw new IllegalArgumentException("Unsupported license version");
        byte[] iv = new byte[IV_LEN];
        byte[] ct = new byte[payload.length - 1 - IV_LEN];
        System.arraycopy(payload, 1, iv, 0, IV_LEN);
        System.arraycopy(payload, 1 + IV_LEN, ct, 0, ct.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keyFor(deviceHwid), new GCMParameterSpec(TAG_BITS, iv));
        byte[] plain = cipher.doFinal(ct);
        JSONObject root = new JSONObject(new String(plain, StandardCharsets.UTF_8));

        String hwid = root.optString("h", "");
        if (!hwid.equalsIgnoreCase(deviceHwid)) throw new IllegalArgumentException("License is not for this device");
        return new License(
            root.optString("v", ""),
            root.optString("n", ""),
            root.optString("m", ""),
            root.optString("c", ""),
            hwid,
            root.optBoolean("l", false),
            root.optLong("e", 0)
        );
    }

    public static boolean expired(License license) {
        return license.expiry > 0 && System.currentTimeMillis() > license.expiry;
    }

    private static SecretKeySpec keyFor(String hwid) throws Exception {
        byte[] bytes = (hwid + SALT).getBytes(StandardCharsets.UTF_8);
        byte[] key = MessageDigest.getInstance("SHA-256").digest(bytes);
        return new SecretKeySpec(key, "AES");
    }
}
