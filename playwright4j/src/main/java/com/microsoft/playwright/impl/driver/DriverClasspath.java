package com.microsoft.playwright.impl.driver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Builds the {@code -cp} of the driver JVM from where the running playwright4j and GraalVM classes
 * were actually loaded, instead of {@code java.class.path}.
 *
 * <p>{@code java.class.path} is empty or only the outer archive in Spring Boot fat jars, application
 * servers, OSGi containers, jlink images or launchers with their own class loader. This class walks
 * the class loader chain of playwright4j ({@link URLClassLoader} URLs, which covers Spring Boot's
 * launcher, Tomcat/Jetty web app loaders and most plugin loaders), adds the code sources of a few
 * anchor classes (playwright4j, GraalVM polyglot/Truffle/JS) and {@code java.class.path}. Jars
 * nested in another jar ({@code jar:file:app.jar!/BOOT-INF/lib/x.jar!/} or Spring Boot 3.2+
 * {@code jar:nested:/app.jar/!BOOT-INF/lib/x.jar}) are copied once into the driver cache, keyed by
 * the entry's CRC and size, because a JVM cannot put a nested jar on its classpath.
 *
 * <p>{@code -Dplaywright4j.driver.classpath=<path-list>} replaces the detection entirely, for
 * containers whose class loaders expose no file locations at all.
 */
final class DriverClasspath {

    static final String CLASSPATH_PROPERTY = "playwright4j.driver.classpath";

    static final String DRIVER_MAIN_CLASS = "com.aresstack.playwright4j.driver.GraalDriverMain";

    /** Class files whose jar must be on the driver classpath; missing ones are skipped. */
    static final String[] ANCHOR_RESOURCES = {
            DRIVER_MAIN_CLASS.replace('.', '/') + ".class",
            "com/microsoft/playwright/impl/driver/Driver.class",
            "org/graalvm/polyglot/Context.class",
            "com/oracle/truffle/api/TruffleLanguage.class",
            "com/oracle/truffle/js/lang/JavaScriptLanguage.class",
            "org/jcodec/api/awt/AWTSequenceEncoder.class",
    };

    private DriverClasspath() {
    }

    static String resolve(ClassLoader loader, Path nestedJarCache) {
        String configured = System.getProperty(CLASSPATH_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        Set<URL> urls = new LinkedHashSet<>();
        for (String resource : ANCHOR_RESOURCES) {
            URL url = loader == null ? null : loader.getResource(resource);
            if (url != null) {
                urls.add(url);
            }
        }
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            if (current instanceof URLClassLoader) {
                for (URL url : ((URLClassLoader) current).getURLs()) {
                    urls.add(url);
                }
            }
        }
        Set<String> entries = new LinkedHashSet<>();
        for (URL url : urls) {
            String entry;
            try {
                entry = toClasspathEntry(url, nestedJarCache);
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Cannot put " + url + " on the playwright4j driver classpath"
                        + " (set -D" + CLASSPATH_PROPERTY + " to override)", exception);
            }
            if (entry != null) {
                entries.add(entry);
            }
        }
        String javaClassPath = System.getProperty("java.class.path", "");
        for (String element : javaClassPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!element.isEmpty()) {
                entries.add(element);
            }
        }
        URL main = loader == null ? null : loader.getResource(ANCHOR_RESOURCES[0]);
        if (main == null || entries.isEmpty()) {
            throw new IllegalStateException("Cannot locate " + DRIVER_MAIN_CLASS + " for the playwright4j driver JVM;"
                    + " set -D" + CLASSPATH_PROPERTY + " to the playwright4j and GraalVM jars");
        }
        return String.join(File.pathSeparator, entries);
    }

    /**
     * Maps a class loader URL or a class file resource URL to a local classpath entry, or null when
     * the location is not usable by another JVM (e.g. a directory inside a jar, OSGi bundle URLs).
     */
    static String toClasspathEntry(URL url, Path nestedJarCache) throws IOException {
        return toClasspathEntry(url.toString(), nestedJarCache);
    }

    /**
     * String form, because Spring Boot's {@code jar:nested:} URLs only parse as {@link URL} once its
     * handler is registered.
     */
    static String toClasspathEntry(String url, Path nestedJarCache) throws IOException {
        if (url.startsWith("file:")) {
            Path path = fileUrlToPath(url);
            // A class file resource from an exploded directory: climb to the classpath root.
            if (url.endsWith(".class")) {
                return classRoot(path, url).toString();
            }
            return path.toString();
        }
        if (url.startsWith("jar:")) {
            return jarUrlToEntry(url.substring("jar:".length()), nestedJarCache);
        }
        return null;
    }

    private static String jarUrlToEntry(String spec, Path nestedJarCache) throws IOException {
        // Spring Boot 3.2+: nested:/outer.jar/!BOOT-INF/lib/inner.jar!/<resource>
        if (spec.startsWith("nested:")) {
            String rest = spec.substring("nested:".length());
            int separator = rest.indexOf("/!");
            if (separator < 0) {
                return null;
            }
            // Same path form as a file: URL ("/C:/..." on Windows), so let the URI parser map it.
            Path outer = fileUrlToPath("file:" + rest.substring(0, separator));
            String inner = rest.substring(separator + 2);
            int innerEnd = inner.indexOf("!/");
            if (innerEnd >= 0) {
                inner = inner.substring(0, innerEnd);
            }
            inner = decode(inner);
            if (!inner.endsWith(".jar")) {
                return null; // e.g. BOOT-INF/classes/: the application's own classes
            }
            return extractNested(outer, inner, nestedJarCache).toString();
        }
        // file:/outer.jar!/<resource>  or  file:/outer.jar!/BOOT-INF/lib/inner.jar!/<resource>
        String[] parts = spec.split("!/", -1);
        if (!parts[0].startsWith("file:")) {
            return null;
        }
        Path outer = fileUrlToPath(parts[0]);
        String inner = parts.length >= 2 ? parts[1] : "";
        if (inner.endsWith(".jar")) {
            return extractNested(outer, decode(inner), nestedJarCache).toString();
        }
        // A resource of the outer jar itself. A directory such as BOOT-INF/classes/ is not
        // loadable from another JVM, and the driver does not need the application's classes.
        boolean ownResource = inner.isEmpty()
                || inner.endsWith(".class") && !inner.startsWith("BOOT-INF/") && !inner.startsWith("WEB-INF/");
        return ownResource ? outer.toString() : null;
    }

    /** Copies {@code entryName} of {@code outerJar} into the cache once; returns the copy. */
    static Path extractNested(Path outerJar, String entryName, Path cache) throws IOException {
        try (JarFile jar = new JarFile(outerJar.toFile())) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) {
                throw new IOException("No entry " + entryName + " in " + outerJar);
            }
            String fileName = entryName.substring(entryName.lastIndexOf('/') + 1);
            String key = sha256(outerJar.toAbsolutePath() + "!" + entryName + "#" + entry.getCrc() + "#" + entry.getSize());
            Path dir = cache.resolve(key.substring(0, 32));
            Path target = dir.resolve(fileName);
            if (Files.isRegularFile(target) && (entry.getSize() < 0 || Files.size(target) == entry.getSize())) {
                return target;
            }
            Files.createDirectories(dir);
            Path staging = Files.createTempFile(dir, fileName, ".part");
            try {
                try (InputStream input = jar.getInputStream(entry)) {
                    Files.copy(input, staging, StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException moveFailed) {
                    // Another JVM or thread won the race (or Windows holds the target open).
                    if (!Files.isRegularFile(target)) {
                        throw moveFailed;
                    }
                }
            } finally {
                Files.deleteIfExists(staging);
            }
            return target;
        }
    }

    private static Path classRoot(Path classFile, String asString) {
        String resource = null;
        for (String anchor : ANCHOR_RESOURCES) {
            if (asString.endsWith("/" + anchor)) {
                resource = anchor;
                break;
            }
        }
        Path root = classFile;
        int depth = resource == null ? 1 : resource.split("/").length;
        for (int i = 0; i < depth && root.getParent() != null; i++) {
            root = root.getParent();
        }
        return root;
    }

    private static Path fileUrlToPath(String fileUrl) {
        try {
            return Paths.get(new java.net.URI(fileUrl.replace(" ", "%20")));
        } catch (URISyntaxException | IllegalArgumentException exception) {
            return Paths.get(decode(fileUrl.substring("file:".length())));
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
