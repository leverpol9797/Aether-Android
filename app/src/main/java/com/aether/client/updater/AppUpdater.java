package com.aether.client.updater;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

public class AppUpdater {

    public interface UpdateCallback {
        void onUpdateAvailable(String versionName, String downloadUrl, String sha256);
        void onNoUpdate();
        void onError(String message);
    }

    private static final String GITHUB_RELEASE_URL = "https://api.github.com/repos/leverpol9797/Aether-Android/releases/latest";

    public static void checkForUpdates(Context context, UpdateCallback callback) {
        new Thread(() -> {
            try {
                URL url = new URL(GITHUB_RELEASE_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONObject release = new JSONObject(sb.toString());
                    String tagName = release.optString("tag_name", "");

                    if (!tagName.isEmpty() && !tagName.equals("v2.3.0")) {
                        // Update available
                        String downloadUrl = "";
                        String expectedSha256 = "";

                        new Handler(Looper.getMainLooper()).post(() ->
                                callback.onUpdateAvailable(tagName, downloadUrl, expectedSha256)
                        );
                        return;
                    }
                }

                new Handler(Looper.getMainLooper()).post(callback::onNoUpdate);

            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() ->
                        callback.onError("Update check failed: " + e.getMessage())
                );
            }
        }).start();
    }

    public static boolean verifyFileSha256(byte[] fileData, String expectedHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(fileData);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().equalsIgnoreCase(expectedHash);
        } catch (Exception e) {
            return false;
        }
    }
}
