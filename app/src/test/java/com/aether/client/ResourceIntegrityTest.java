package com.aether.client;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Structural checks over the resource tree.
 *
 * <p>These are not interesting assertions. They exist because a resource file missing its
 * XML declaration, or a Persian string whose name has no English counterpart, fails deep
 * inside AAPT or at runtime on a device -- not here -- with a message that points at a
 * generated intermediate rather than at the file that is wrong. AAPT in particular reports
 * a missing declaration as "ParseError at [1,1]" against {@code build/intermediates},
 * which tells the reader nothing about which of their files to open.
 */
public class ResourceIntegrityTest {

    private static File resDir() {
        // Gradle runs tests with the module directory as the working directory.
        File direct = new File("src/main/res");
        if (direct.isDirectory()) return direct;
        File viaParent = new File("app/src/main/res");
        return viaParent.isDirectory() ? viaParent : new File("src/main/res");
    }

    private static List<File> xmlFiles(File root) {
        List<File> found = new ArrayList<>();
        collect(root, found);
        return found;
    }

    private static void collect(File directory, List<File> into) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collect(child, into);
            else if (child.getName().endsWith(".xml")) into.add(child);
        }
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void everyResourceFileOpensWithAnXmlDeclaration() throws IOException {
        File root = resDir();
        assertTrue("res directory not found at " + root.getAbsolutePath(), root.isDirectory());

        List<String> offenders = new ArrayList<>();
        for (File file : xmlFiles(root)) {
            String text = read(file).trim();
            if (!text.startsWith("<?xml")) {
                offenders.add(file.getName() + " starts with \""
                        + text.substring(0, Math.min(20, text.length())) + "\"");
            }
        }
        assertTrue("these resource files have no XML declaration: " + offenders, offenders.isEmpty());
    }

    @Test
    public void everyResourceFileClosesItsRootElement() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (File file : xmlFiles(resDir())) {
            String text = read(file);
            if (!text.trim().endsWith(">")) {
                offenders.add(file.getName() + " does not end with '>'");
            }
        }
        assertTrue("these resource files look truncated: " + offenders, offenders.isEmpty());
    }

    /**
     * A Persian string with no English counterpart falls back to the English text at
     * runtime, silently, in the one language the Persian user chose to avoid.
     */
    @Test
    public void englishAndPersianDeclareTheSameStringNames() throws IOException {
        File english = new File(resDir(), "values/strings.xml");
        File persian = new File(resDir(), "values-fa/strings.xml");
        assertTrue("strings.xml not found", english.isFile());
        assertTrue("values-fa/strings.xml not found", persian.isFile());

        List<String> left = names(read(english));
        List<String> right = names(read(persian));

        List<String> missingPersian = new ArrayList<>(left);
        missingPersian.removeAll(right);
        List<String> missingEnglish = new ArrayList<>(right);
        missingEnglish.removeAll(left);

        assertTrue("present in English but not Persian: " + missingPersian,
                missingPersian.isEmpty());
        assertTrue("present in Persian but not English: " + missingEnglish,
                missingEnglish.isEmpty());
    }

    private static List<String> names(String xml) {
        List<String> found = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("name=\"([a-z0-9_]+)\"").matcher(xml);
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }

    /**
     * The updater hands an APK to the installer through a FileProvider, so the authority
     * the code builds and the one the manifest declares have to be the same string. They
     * are assembled separately in both places, which is exactly the kind of duplication
     * that survives review.
     */
    @Test
    public void theUpdateFileProviderPathsExist() {
        File paths = new File(resDir(), "xml/update_file_paths.xml");
        assertTrue("the manifest points FileProvider at a file that is not there",
                paths.isFile());
    }

    @Test
    public void theApplicationIdIsTheOneTheCodeExpects() throws IOException {
        // AppUpdater builds the authority as packageName + ".updates", so this has to be
        // com.aether.client for that to resolve.
        String manifest = read(new File("src/main/AndroidManifest.xml"));
        if (manifest.isEmpty()) {
            manifest = read(new File("app/src/main/AndroidManifest.xml"));
        }
        assertTrue("applicationId is not com.aether.client",
                manifest.contains("com.aether.client")
                        || manifest.contains("${applicationId}"));
    }
}