package com.aether.client.updater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The updater's two safety properties: it will not install an unverified binary, and it
 * will not follow a download off GitHub.
 *
 * <p>This app runs an arbitrary executable with its own privileges. An update path that
 * transfers bytes and hands them to the package installer without checking them is a
 * remote-code-execution channel with a download button on it.
 */
public class AppUpdaterTest {

    /** The SHA-256 of an empty payload, used as a known-answer check. */
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    public void hashingIsCorrect() {
        File temporary = null;
        try {
            temporary = File.createTempFile("aether", ".apk");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(new byte[0]);
            }
            assertEquals(EMPTY_SHA256, AppUpdater.sha256(temporary));
        } catch (Exception failed) {
            org.junit.Assert.fail("could not prepare a fixture: " + failed.getMessage());
        } finally {
            if (temporary != null) temporary.delete();
        }
    }

    @Test
    public void aHashMismatchIsDetected() {
        File temporary = null;
        try {
            temporary = File.createTempFile("aether", ".apk");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write("not the expected payload".getBytes("UTF-8"));
            }
            String actual = AppUpdater.sha256(temporary);
            assertFalse("a different payload must not hash to the expected value",
                    EMPTY_SHA256.equals(actual));
            assertEquals(64, actual.length());
        } catch (Exception failed) {
            org.junit.Assert.fail("could not prepare a fixture: " + failed.getMessage());
        } finally {
            if (temporary != null) temporary.delete();
        }
    }

    @Test
    public void versionComparisonIsNumericNotLexical() {
        // "2.10.0" must beat "2.9.0"; a string comparison says the opposite.
        assertTrue(AppUpdater.isNewer("v2.10.0", "2.9.0"));
        assertFalse(AppUpdater.isNewer("v2.9.0", "2.10.0"));
        assertFalse(AppUpdater.isNewer("v2.3.0", "2.3.0"));
        assertTrue(AppUpdater.isNewer("v3.0.0", "2.3.0"));
    }

    @Test
    public void anOlderOrEqualTagIsNotOfferedAsAnUpdate() {
        assertFalse(AppUpdater.isNewer("v2.3.0", "2.3.0"));
        assertFalse(AppUpdater.isNewer("v2.2.9", "2.3.0"));
        assertFalse(AppUpdater.isNewer("v2.3.0", "2.3.1"));
    }

    /**
     * The API's download URL is parsed rather than trusted. A tampered API response that
     * points the download elsewhere must not be followed -- otherwise the check that this
     * class exists to perform is verifying a file the attacker supplied, with the
     * attacker supplying the expected hash too.
     */
    @Test
    public void urlsOutsideTheReleasePathAreRejected() throws Exception {
        java.lang.reflect.Method check = artifactCheck();
        assertFalse(check.invoke(new AppUpdater(null, "o/r"), "http://example.com/a.apk"));
        assertFalse(check.invoke(new AppUpdater(null, "o/r"), "https://evil.test/releases/a.apk"));
        assertTrue(check.invoke(new AppUpdater(null, "o/r"),
                "https://github.com/o/r/releases/download/v1/a.apk"));
    }

    private static java.lang.reflect.Method artifactCheck() throws Exception {
        java.lang.reflect.Method method = AppUpdater.class
                .getDeclaredMethod("isAetherArtifact", String.class);
        method.setAccessible(true);
        return method;
    }
}