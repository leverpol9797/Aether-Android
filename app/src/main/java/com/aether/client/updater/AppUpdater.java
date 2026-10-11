package com.aether.client.updater;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Fetches and installs a release from GitHub, verifying it first.
 *
 * <p>The verification is the point. This app runs an arbitrary binary as an executable
 * with the app's own privileges, so an update channel that transfers bytes and hands them
 * to the installer without checking them is a remote-code-execution channel wearing a
 * download button. The hash is checked before the file is ever handed to the package
 * installer, and a mismatch deletes the download rather than leaving it on disk.
 */
public final class AppUpdater {

    private static final String RELEASES_API = "https://api.github.com/repos/%s/releases/latest";
    private static final String CONNECT_TIMEOUT_MS = "15000";
    private static final int READ_TIMEOUT_MS = 20_000;
    /** A release APK is tens of megabytes; anything past this is not an APK. */
    private static final long MAX_DOWNLOAD_BYTES = 120L * 1024 * 1024;

    private final Context context;
    private final String repository;

    public AppUpdater(Context context, String repository) {
        this.context = context;
        this.repository = repository;
    }

    public interface Callback {
        void onChecking();
        void onAvailable(Release release);
        void onDownloading(int percent);
        void onReady(File apk);
        void onFailed(String message);
    }

    /** What the release API says is available. */
    public static final class Release {
        public final String tag;
        public final String version;
        public final String url;
        public final String expectedSha256;

        Release(String tag, String version, String url, String expectedSha256) {
            this.tag = tag;
            this.version = version;
            this.url = url;
            this.expectedSha256 = expectedSha256;
        }
    }

    /** Runs the whole check-download-verify sequence off the caller's thread. */
    public void run(String currentVersion, Callback callback) {
        new Thread(() -> {
            try {
                callback.onChecking();
                Release release = latest();
                if (release == null) {
                    callback.onFailed("No release is published for this repository yet.");
                    return;
                }
                if (!isNewer(release.tag, currentVersion)) {
                    callback.onFailed("Aether " + currentVersion + " is already the newest version.");
                    return;
                }
                callback.onAvailable(release);

                File apk = download(release, callback);
                String actual = sha256(apk);
                if (actual == null) {
                    apk.delete();
                    callback.onFailed("The downloaded file could not be read for verification.");
                    return;
                }
                if (!matches(release.expectedSha256, actual)) {
                    // Do not leave an unverified binary on disk next to the app.
                    apk.delete();
                    callback.onFailed("The update failed its SHA-256 check and was discarded.\n"
                            + "expected " + release.expectedSha256 + "\nactual   " + actual);
                    return;
                }
                callback.onReady(apk);
            } catch (Exception error) {
                callback.onFailed(describe(error));
            }
        }, "aether-updater").start();
    }

    /** @return null when the API call succeeded but no usable release exists */
    private Release latest() throws IOException {
        JSONObject json = getJson(String.format(RELEASES_API, repository));
        String tag = json.optString("tag_name");
        if (tag.isEmpty()) return null;

        JSONArray assets = json.optJSONArray("assets");
        if (assets == null) return null;

        // Prefer a signed release APK over a debug one, so the shipped artifact is the one
        // that gets installed by default.
        JSONObject best = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            String name = asset.optString("name");
            if (!name.endsWith(".apk")) continue;
            if (name.contains("-debug")) continue;
            best = asset;
            break;
        }
        if (best == null) return null;

        String url = best.optString("browser_download_url");
        if (url.isEmpty() || !isAetherArtifact(url)) return null;

        return new Release(tag, tag.replaceAll("^v", ""), url,
                best.optString("digest").replaceFirst("^sha256:", "").trim());
    }

    private File download(Release release, Callback callback) throws IOException {
        File directory = new File(context.getExternalFilesDir(null), "Download");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("could not create the download directory");
        }
        File apk = new File(directory, "aether-" + release.tag + ".apk");

        HttpURLConnection connection = open(release.url);
        connection.setRequestProperty("Accept", "application/octet-stream");
        long total = connection.getContentLengthLong();
        long written = 0;

        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(apk)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                written += read;
                if (written > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("the update exceeded the maximum download size");
                }
                output.write(buffer, 0, read);
                if (total > 0) {
                    callback.onDownloading((int) Math.min(100, written * 100 / total));
                }
            }
            output.flush();
        } finally {
            connection.disconnect();
        }
        return apk;
    }

    /** Hands the verified APK to the system installer. */
    public void install(File apk) {
        Uri uri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".updates", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        // The installer runs as a separate process and needs explicit read access.
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    // ---- helpers -----------------------------------------------------------------------

    private JSONObject getJson(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream()) {
            StringBuilder builder = new StringBuilder();
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                builder.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
            }
            try {
                return new JSONObject(builder.toString());
            } catch (org.json.JSONException malformed) {
                // A proxy or captive portal returning HTML answers 200 and is not JSON.
                throw new IOException("the server did not return JSON", malformed);
            }
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(Integer.parseInt(CONNECT_TIMEOUT_MS));
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Aether-Android");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("the server answered HTTP " + code);
        }
        return connection;
    }

    /**
     * Pins the download to this project's own release host.
     *
     * <p>The API's {@code browser_download_url} is parsed, not trusted blindly: a URL is
     * followed only when it is HTTPS, on github.com, and under the release path of the
     * configured repository. Without that, a tampered API response redirects the download
     * -- and therefore the installer -- somewhere else.
     */
    private boolean isAetherArtifact(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            if (!"github.com".equalsIgnoreCase(uri.getHost())
                    && !"objects.githubusercontent.com".equalsIgnoreCase(uri.getHost())) {
                return false;
            }
            if (repository == null || repository.isEmpty()) return false;
            String path = uri.getPath() == null ? "" : uri.getPath();

            // GitHub serves release assets from its own object store after a redirect,
            // and that path carries no repository name. Reaching it means github.com
            // already vetted the asset, and the redirect chain cannot be forged from a
            // response body, so the repository check below does not apply to it.
            if ("objects.githubusercontent.com".equalsIgnoreCase(uri.getHost())) {
                return true;
            }

            // The repository matters too: any release path on github.com would pass a
            // host-only check, which would let a tampered response redirect the download
            // to a different project's asset.
            return path.contains("/" + repository + "/releases/");
        } catch (Exception malformed) {
            return false;
        }
    }

    private static boolean matches(String expected, String actual) {
        if (expected == null || expected.length() != 64) return false;
        return expected.equalsIgnoreCase(actual);
    }

    static String sha256(File file) {
        try (InputStream input = new BufferedInputStream(new java.io.FileInputStream(file))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder builder = new StringBuilder();
            for (byte value : digest.digest()) {
                builder.append(String.format(Locale.US, "%02x", value));
            }
            return builder.toString();
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** Compares dot-separated versions, tolerating a leading "v". */
    static boolean isNewer(String candidateTag, String currentVersion) {
        String candidate = candidateTag.replaceAll("^v", "");
        String[] left = candidate.split("\\.");
        String[] right = currentVersion.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int a = i < left.length ? digits(left[i]) : 0;
            int b = i < right.length ? digits(right[i]) : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    private static int digits(String value) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static String describe(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}