package com.microsoft.playwright.impl.driver;

import com.aresstack.playwright4j.graal.Playwright4JHost;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class Driver {

    private final Map<String, String> environment;

    private Driver(Map<String, String> environment) {
        this.environment = new LinkedHashMap<>(environment);
    }

    public static Driver ensureDriverInstalled(Map<String, String> environment, boolean installBrowsers) {
        return new Driver(environment);
    }

    public static Driver ensureDriverInstalled(Map<String, String> environment, Boolean installBrowsers) {
        return new Driver(environment);
    }

    public static Driver createAndInstall(Map<String, String> environment, boolean installBrowsers) {
        return new Driver(environment);
    }

    public static Driver createAndInstall(Map<String, String> environment, Boolean installBrowsers) {
        return new Driver(environment);
    }

    /** Extra driver JVM options, whitespace separated; double quotes group an option with spaces. */
    static final String JVM_ARGS_PROPERTY = "playwright4j.driver.jvmArgs";

    /** Read by GraalDriverMain: a file of system properties that must not appear on the command line. */
    public static final String SECRET_PROPERTIES_FILE_PROPERTY = "playwright4j.driver.secretPropertiesFile";

    /**
     * Copied as {@code -D} from this JVM to the driver JVM, so the driver's TLS (APIRequestContext,
     * WebSocket), proxy and video settings match the application's.
     */
    static final String[] FORWARDED_SYSTEM_PROPERTIES = {
            "playwright4j.debug",
            "playwright4j.debug.file",
            "playwright4j.unhandledRejections",
            Playwright4JHost.NATIVE_BEHAVIOR_PROPERTY,
            Playwright4JHost.NATURAL_POINTER_PROPERTY,
            "playwright4j.video.vlc.path",
            "javax.net.ssl.trustStore",
            "javax.net.ssl.trustStoreType",
            "javax.net.ssl.trustStoreProvider",
            "javax.net.ssl.keyStore",
            "javax.net.ssl.keyStoreType",
            "javax.net.ssl.keyStoreProvider",
            "jdk.tls.client.protocols",
            "https.protocols",
            "http.proxyHost",
            "http.proxyPort",
            "https.proxyHost",
            "https.proxyPort",
            "http.nonProxyHosts",
            "socksProxyHost",
            "socksProxyPort",
            "socksProxyVersion",
            "java.net.useSystemProxies",
            "jdk.http.auth.tunneling.disabledSchemes",
            "jdk.http.auth.proxying.disabledSchemes",
    };

    /** Forwarded like the above, but through an owner-only temp file instead of the command line. */
    static final String[] SECRET_SYSTEM_PROPERTIES = {
            "javax.net.ssl.trustStorePassword",
            "javax.net.ssl.keyStorePassword",
    };

    public ProcessBuilder createProcessBuilder() {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(DriverClasspath.resolve(Driver.class.getClassLoader(), driverCacheRoot().resolve("classpath")));
        // Force IPv4 so a "localhost" ws endpoint (e.g. from launch-server) resolves to 127.0.0.1,
        // matching the host net server's IPv4 bind; otherwise java.net.http may try ::1 first and
        // fail without falling back (flaky connect on reconnection).
        command.add("-Djava.net.preferIPv4Stack=true");
        for (String property : FORWARDED_SYSTEM_PROPERTIES) {
            String value = System.getProperty(property);
            if (value != null && !value.trim().isEmpty()) {
                command.add("-D" + property + "=" + value);
            }
        }
        Path secrets = writeSecretProperties();
        if (secrets != null) {
            command.add("-D" + SECRET_PROPERTIES_FILE_PROPERTY + "=" + secrets);
        }
        // Last, so an explicit option (e.g. -Xmx, -Dhttps.proxyHost=...) wins over a forwarded one.
        command.addAll(parseJvmArgs(System.getProperty(JVM_ARGS_PROPERTY)));
        command.add(DriverClasspath.DRIVER_MAIN_CLASS);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.environment().putAll(environment);
        return processBuilder;
    }

    /** Splits on whitespace outside double quotes; quotes are removed. */
    static java.util.List<String> parseJvmArgs(String value) {
        java.util.List<String> args = new java.util.ArrayList<>();
        if (value == null) {
            return args;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean inArg = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                inArg = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (inArg) {
                    args.add(current.toString());
                    current.setLength(0);
                    inArg = false;
                }
            } else {
                current.append(c);
                inArg = true;
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unbalanced quote in -D" + JVM_ARGS_PROPERTY + "=" + value);
        }
        if (inArg) {
            args.add(current.toString());
        }
        return args;
    }

    /**
     * Writes the set secret properties to an owner-only file that the driver JVM reads and deletes at
     * startup; null when none is set. deleteOnExit covers a driver that never started.
     */
    static Path writeSecretProperties() {
        java.util.Properties secrets = new java.util.Properties();
        for (String property : SECRET_SYSTEM_PROPERTIES) {
            String value = System.getProperty(property);
            if (value != null) {
                secrets.setProperty(property, value);
            }
        }
        if (secrets.isEmpty()) {
            return null;
        }
        try {
            Path file;
            try {
                file = Files.createTempFile("playwright4j-driver-", ".properties",
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException notPosix) {
                // Windows: the user's temp directory is private to the user.
                file = Files.createTempFile("playwright4j-driver-", ".properties");
            }
            file.toFile().deleteOnExit();
            try (java.io.OutputStream output = Files.newOutputStream(file)) {
                secrets.store(output, null);
            }
            return file;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot pass TLS store passwords to the playwright4j driver JVM", exception);
        }
    }

    private String javaExecutable() {
        String executableName = isWindows() ? "java.exe" : "java";
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + executableName;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public Path driverDir() {
        Path dir = driverDirFor(driverCacheRoot(), packageId());
        ensurePackageExtracted(dir);
        return dir;
    }

    static Path driverCacheRoot() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "playwright4j-driver");
    }

    /**
     * One directory per Playwright Core package content: an upgrade (new package id) never reuses
     * files extracted by an older playwright4j version.
     */
    static Path driverDirFor(Path root, String packageId) {
        return root.resolve(packageId);
    }

    /**
     * SHA-256 (prefix) over the repackaged driver/package/** tree, written at build time by
     * playwright4j's driverPackageId task.
     */
    static String packageId() {
        try (InputStream input = Driver.class.getClassLoader().getResourceAsStream(PACKAGE_ID_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing " + PACKAGE_ID_RESOURCE + " (playwright4j jar incomplete)");
            }
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int read;
            while ((read = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
            String id = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!id.matches("[0-9a-f]{16,64}")) {
                throw new IllegalStateException("Invalid " + PACKAGE_ID_RESOURCE + ": " + id);
            }
            return id;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read " + PACKAGE_ID_RESOURCE, exception);
        }
    }

    private static final String PACKAGE_ID_RESOURCE = "driver/package-id.txt";
    private static final String EXTRACTED_MARKER = ".playwright4j-extracted";

    // FileChannel locks are held per JVM; threads of one JVM must serialize before taking one.
    private static final Object EXTRACT_LOCK = new Object();
    private static final long MOVE_RETRY_SECONDS = 10;

    /**
     * Some upstream APIs (e.g. trace viewer) read static assets relative to driverDir(), i.e.
     * {@code driverDir/package/lib/...}. Those assets live on the classpath under
     * {@code driver/package/}. Extract that tree once into driverDir so file-based access works
     * without a real Node driver installation.
     *
     * <p>The slow path runs under an in-JVM lock plus a file lock on {@code driverDir/.lock}, so
     * concurrent JVMs (e.g. a test JVM and its launch-server JVMs) never delete a tree another one
     * just finished. Extraction goes to a sibling staging directory that is moved into place, so a
     * half-written tree is never reused; the move is retried briefly because on Windows antivirus or
     * indexer handles on just-written files make the rename fail for a moment.
     */
    static void ensurePackageExtracted(Path driverDir) {
        Path packageDir = driverDir.resolve("package");
        if (Files.exists(packageDir.resolve(EXTRACTED_MARKER))) {
            return;
        }
        String prefix = com.aresstack.playwright4j.graal.PlaywrightDriverBundleSource.PACKAGE_PREFIX;
        URL probe = Driver.class.getClassLoader().getResource(prefix + "cli.js");
        if (probe == null) {
            throw new IllegalStateException("Missing Playwright Core package resource " + prefix + "cli.js");
        }
        try {
            Files.createDirectories(driverDir);
            synchronized (EXTRACT_LOCK) {
                try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(driverDir.resolve(".lock"),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                     java.nio.channels.FileLock ignored = channel.lock()) {
                    if (Files.exists(packageDir.resolve(EXTRACTED_MARKER))) {
                        return; // another JVM finished while we waited for the lock
                    }
                    deleteStaleEntries(driverDir, packageDir);
                    extractLocked(probe, prefix, driverDir, packageDir);
                }
            }
        } catch (IOException | java.net.URISyntaxException exception) {
            throw new IllegalStateException("Cannot extract driver package assets to " + driverDir, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while extracting driver package assets to " + driverDir, exception);
        }
    }

    /** Under the lock: a package dir without marker and old staging dirs are leftovers of a crash. */
    private static void deleteStaleEntries(Path driverDir, Path packageDir) throws IOException {
        if (Files.exists(packageDir)) {
            deleteTree(packageDir);
        }
        try (java.util.stream.Stream<Path> siblings = Files.list(driverDir)) {
            siblings.filter(path -> path.getFileName().toString().startsWith("package-"))
                    .forEach(Driver::deleteTree);
        }
    }

    private static void extractLocked(URL probe, String prefix, Path driverDir, Path packageDir)
            throws IOException, java.net.URISyntaxException, InterruptedException {
        // Not createTempDirectory: that would make the shared tree rwx------ on POSIX.
        Path staging = driverDir.resolve("package-" + java.util.UUID.randomUUID()).toAbsolutePath().normalize();
        Files.createDirectory(staging);
        try {
            if ("jar".equals(probe.getProtocol())) {
                URLConnection connection = probe.openConnection();
                connection.setUseCaches(false);
                if (connection instanceof java.net.JarURLConnection) {
                    try (JarFile jarFile = ((java.net.JarURLConnection) connection).getJarFile()) {
                        extractFromJar(jarFile, prefix, staging);
                    }
                }
            } else if ("file".equals(probe.getProtocol())) {
                // Exploded classpath (IDE / Gradle test runs): copy the resource directory.
                copyTree(Paths.get(probe.toURI()).getParent(), staging);
            }
            if (!Files.isRegularFile(staging.resolve("cli.js"))) {
                throw new IOException("Extracted Playwright Core package has no cli.js (from " + probe + ")");
            }
            Files.write(staging.resolve(EXTRACTED_MARKER), new byte[0]);
            moveWithRetry(staging, packageDir);
            staging = null;
        } finally {
            if (staging != null) {
                deleteTree(staging);
            }
        }
    }

    private static void moveWithRetry(Path staging, Path packageDir) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(MOVE_RETRY_SECONDS);
        while (true) {
            try {
                Files.move(staging, packageDir, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException moveFailed) {
                if (System.nanoTime() - deadline >= 0) {
                    throw moveFailed;
                }
                Thread.sleep(200);
            }
        }
    }

    private static void extractFromJar(JarFile jarFile, String prefix, Path packageDir) throws IOException {
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith(prefix) || entry.isDirectory()) {
                continue;
            }
            // Map driver/package/X -> packageDir/X (packageDir is absolute and normalized)
            Path target = packageDir.resolve(name.substring(prefix.length())).normalize();
            if (!target.startsWith(packageDir)) {
                continue;
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            try (InputStream input = jarFile.getInputStream(entry)) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path root) {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
        }
    }

    public Map<String, String> environment() {
        return new LinkedHashMap<>(environment);
    }
}
