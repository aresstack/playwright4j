package com.microsoft.playwright.impl.driver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverClasspathTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty(DriverClasspath.CLASSPATH_PROPERTY);
        System.clearProperty("javax.net.ssl.trustStorePassword");
    }

    @Test
    void springBoot2NestedJarIsCopiedIntoTheCache(@TempDir Path dir) throws IOException {
        byte[] inner = jarBytes("com/aresstack/playwright4j/driver/GraalDriverMain.class");
        Path fat = fatJar(dir.resolve("app with space.jar"), "BOOT-INF/lib/playwright4j.jar", inner);
        Path cache = dir.resolve("cache");

        String url = "jar:" + fat.toUri() + "!/BOOT-INF/lib/playwright4j.jar!/";
        String entry = DriverClasspath.toClasspathEntry(new URL(url), cache);

        assertTrue(entry.startsWith(cache.toString()), entry);
        assertTrue(entry.endsWith("playwright4j.jar"), entry);
        assertArrayEquals(inner, Files.readAllBytes(Path.of(entry)));
        // Resource URL of a class inside the nested jar maps to the same cached copy.
        String resource = "jar:" + fat.toUri() + "!/BOOT-INF/lib/playwright4j.jar!/com/aresstack/playwright4j/driver/GraalDriverMain.class";
        assertEquals(entry, DriverClasspath.toClasspathEntry(new URL(resource), cache));
    }

    @Test
    void springBoot32NestedUrlIsCopiedIntoTheCache(@TempDir Path dir) throws IOException {
        byte[] inner = jarBytes("org/graalvm/polyglot/Context.class");
        Path fat = fatJar(dir.resolve("app.jar"), "BOOT-INF/lib/polyglot-25.0.0.jar", inner);

        String url = "jar:nested:" + fat.toUri().getRawPath() + "/!BOOT-INF/lib/polyglot-25.0.0.jar!/";
        String entry = DriverClasspath.toClasspathEntry(url, dir.resolve("cache"));

        assertTrue(entry.endsWith("polyglot-25.0.0.jar"), entry);
        assertArrayEquals(inner, Files.readAllBytes(Path.of(entry)));
    }

    @Test
    void applicationClassesInsideTheFatJarAreSkipped(@TempDir Path dir) throws IOException {
        Path fat = fatJar(dir.resolve("app.jar"), "BOOT-INF/lib/x.jar", jarBytes("X.class"));
        assertNull(DriverClasspath.toClasspathEntry(new URL("jar:" + fat.toUri() + "!/BOOT-INF/classes!/"), dir));
        assertNull(DriverClasspath.toClasspathEntry(
                "jar:nested:" + fat.toUri().getRawPath() + "/!BOOT-INF/classes/!/", dir));
    }

    @Test
    void plainJarAndDirectoryEntriesStayAsTheyAre(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("lib.jar");
        Files.write(jar, jarBytes("org/graalvm/polyglot/Context.class"));
        assertEquals(jar.toString(), DriverClasspath.toClasspathEntry(jar.toUri().toURL(), dir));
        assertEquals(jar.toString(), DriverClasspath.toClasspathEntry(
                new URL("jar:" + jar.toUri() + "!/org/graalvm/polyglot/Context.class"), dir));

        Path classes = dir.resolve("classes");
        Path classFile = classes.resolve("com/aresstack/playwright4j/driver/GraalDriverMain.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, new byte[0]);
        assertEquals(classes.toString(), DriverClasspath.toClasspathEntry(classFile.toUri().toURL(), dir));
    }

    @Test
    void urlClassLoaderEntriesAreUsedWhenJavaClassPathDoesNotHaveThem(@TempDir Path dir) throws IOException {
        Path p4j = dir.resolve("p4j.jar");
        Files.write(p4j, jarBytes("com/aresstack/playwright4j/driver/GraalDriverMain.class"));
        Path other = dir.resolve("other.jar");
        Files.write(other, jarBytes("org/example/Other.class"));
        try (URLClassLoader loader = new URLClassLoader(new URL[]{p4j.toUri().toURL(), other.toUri().toURL()}, null)) {
            List<String> entries = Arrays.asList(DriverClasspath.resolve(loader, dir.resolve("cache")).split(File.pathSeparator));
            assertTrue(entries.contains(p4j.toString()), entries.toString());
            assertTrue(entries.contains(other.toString()), entries.toString());
        }
    }

    @Test
    void missingDriverMainFailsWithAHintAndThePropertyOverrides(@TempDir Path dir) throws IOException {
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> DriverClasspath.resolve(empty, dir));
            assertTrue(failure.getMessage().contains(DriverClasspath.CLASSPATH_PROPERTY), failure.getMessage());

            System.setProperty(DriverClasspath.CLASSPATH_PROPERTY, " a.jar" + File.pathSeparator + "b.jar ");
            assertEquals("a.jar" + File.pathSeparator + "b.jar", DriverClasspath.resolve(empty, dir));
        }
    }

    @Test
    void jvmArgsSplitOnWhitespaceAndKeepQuotedSpaces() {
        assertEquals(Arrays.asList("-Xmx2g", "-Dhttps.proxyHost=proxy", "-Dpath=C:\\Program Files\\x", ""),
                Driver.parseJvmArgs("  -Xmx2g\t-Dhttps.proxyHost=proxy \"-Dpath=C:\\Program Files\\x\" \"\" "));
        assertTrue(Driver.parseJvmArgs(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> Driver.parseJvmArgs("-Da=\"b"));
    }

    @Test
    void forwardsVideoTlsAndProxyPropertiesButNoPasswords() {
        List<String> forwarded = Arrays.asList(Driver.FORWARDED_SYSTEM_PROPERTIES);
        assertTrue(forwarded.contains("playwright4j.video.vlc.path"));
        assertTrue(forwarded.contains("javax.net.ssl.trustStore"));
        assertTrue(forwarded.contains("https.proxyHost"));
        for (String property : forwarded) {
            assertFalse(property.toLowerCase().contains("password"), property);
        }
    }

    @Test
    void passwordsGoThroughAFileThatTheDriverReadsAndDeletes() throws IOException {
        System.setProperty("javax.net.ssl.trustStorePassword", "s3cret");
        Path file = Driver.writeSecretProperties();
        try {
            ProcessBuilderView view = new ProcessBuilderView();
            assertFalse(view.commandContains("s3cret"));
            Properties properties = new Properties();
            try (java.io.InputStream input = Files.newInputStream(file)) {
                properties.load(input);
            }
            assertEquals("s3cret", properties.getProperty("javax.net.ssl.trustStorePassword"));
            System.clearProperty("javax.net.ssl.trustStorePassword");

            System.setProperty(com.aresstack.playwright4j.driver.GraalDriverMain.SECRET_PROPERTIES_FILE_PROPERTY, file.toString());
            com.aresstack.playwright4j.driver.GraalDriverMain.loadSecretProperties();
            assertEquals("s3cret", System.getProperty("javax.net.ssl.trustStorePassword"));
            assertFalse(Files.exists(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** The driver command line as a string, built with the current system properties. */
    private static final class ProcessBuilderView {
        private final String command = String.join(" ",
                Driver.ensureDriverInstalled(java.util.Collections.emptyMap(), false).createProcessBuilder().command());

        boolean commandContains(String text) {
            return command.contains(text);
        }
    }

    private static byte[] jarBytes(String entryName) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry(entryName));
            jar.write("x".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** Spring Boot stores nested jars uncompressed (STORED). */
    private static Path fatJar(Path target, String nestedName, byte[] nested) throws IOException {
        try (OutputStream out = Files.newOutputStream(target); JarOutputStream jar = new JarOutputStream(out)) {
            JarEntry entry = new JarEntry(nestedName);
            entry.setMethod(JarEntry.STORED);
            entry.setSize(nested.length);
            CRC32 crc = new CRC32();
            crc.update(nested);
            entry.setCrc(crc.getValue());
            jar.putNextEntry(entry);
            jar.write(nested);
            jar.closeEntry();
        }
        return target;
    }
}
