package com.example.aichat;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class ApiKeyStore {

    private static final String PREFS = "api_key_store";
    private static final String KEY_SALT = "salt";
    private static final String KEY_PWD = "pwd_hash";
    private static final String KEY_DATA = "data";

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean hasPassword(Context ctx) {
        return sp(ctx).contains(KEY_PWD);
    }

    public static void setPassword(Context ctx, String pwd) {
        String salt = randomHex(16);
        sp(ctx).edit()
                .putString(KEY_SALT, salt)
                .putString(KEY_PWD, sha256(salt + pwd))
                .apply();
    }

    public static boolean verifyPassword(Context ctx, String pwd) {
        String salt = sp(ctx).getString(KEY_SALT, "");
        String hash = sp(ctx).getString(KEY_PWD, "");
        return sha256(salt + pwd).equals(hash);
    }

    public static void save(Context ctx, String pwd, String id, String name,
                            String host, String apiKey, String model) throws Exception {
        List<String[]> all = readAll(ctx, pwd);
        JSONArray arr = new JSONArray();
        boolean replaced = false;
        for (String[] r : all) {
            if (r[0].equals(id)) {
                arr.put(toJson(id, name, host, apiKey, model));
                replaced = true;
            } else {
                arr.put(toJson(r[0], r[1], r[2], r[3], r[4]));
            }
        }
        if (!replaced) {
            if (id == null || id.isEmpty()) id = String.valueOf(System.currentTimeMillis());
            arr.put(toJson(id, name, host, apiKey, model));
        }
        writeRaw(ctx, pwd, arr.toString());
    }

    public static List<String[]> list(Context ctx, String pwd) throws Exception {
        return readAll(ctx, pwd);
    }

    public static void delete(Context ctx, String pwd, String id) throws Exception {
        List<String[]> all = readAll(ctx, pwd);
        JSONArray arr = new JSONArray();
        for (String[] r : all) {
            if (!r[0].equals(id)) {
                arr.put(toJson(r[0], r[1], r[2], r[3], r[4]));
            }
        }
        writeRaw(ctx, pwd, arr.toString());
    }

    private static List<String[]> readAll(Context ctx, String pwd) throws Exception {
        List<String[]> list = new ArrayList<>();
        String raw = sp(ctx).getString(KEY_DATA, "");
        if (raw.isEmpty()) return list;
        String json = decrypt(ctx, pwd, raw);
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            list.add(new String[]{
                    o.optString("id", ""),
                    o.optString("name", ""),
                    o.optString("host", ""),
                    o.optString("key", ""),
                    o.optString("model", "")
            });
        }
        return list;
    }

    private static void writeRaw(Context ctx, String pwd, String json) throws Exception {
        String enc = encrypt(ctx, pwd, json);
        sp(ctx).edit().putString(KEY_DATA, enc).apply();
    }

    private static JSONObject toJson(String id, String name, String host, String key, String model) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("host", host);
            o.put("key", key);
            o.put("model", model);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static SecretKeySpec keyFrom(String pwd) throws Exception {
        byte[] d = sha256Bytes("aichat|" + pwd);
        byte[] k = new byte[16];
        System.arraycopy(d, 0, k, 0, 16);
        return new SecretKeySpec(k, "AES");
    }

    private static String encrypt(Context ctx, String pwd, String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, keyFrom(pwd));
        byte[] iv = c.getIV();
        byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + enc.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(enc, 0, out, iv.length, enc.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static String decrypt(Context ctx, String pwd, String b64) throws Exception {
        byte[] all = Base64.decode(b64, Base64.NO_WRAP);
        byte[] iv = new byte[16];
        byte[] enc = new byte[all.length - 16];
        System.arraycopy(all, 0, iv, 0, 16);
        System.arraycopy(all, 16, enc, 0, enc.length);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, keyFrom(pwd), new IvParameterSpec(iv));
        return new String(c.doFinal(enc), StandardCharsets.UTF_8);
    }

    private static String sha256(String s) {
        try {
            byte[] d = sha256Bytes(s);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] sha256Bytes(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomHex(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append("0123456789abcdef".charAt((int) (Math.random() * 16)));
        }
        return sb.toString();
    }
}
