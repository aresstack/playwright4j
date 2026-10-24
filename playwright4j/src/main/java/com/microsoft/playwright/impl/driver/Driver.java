package com.microsoft.playwright.impl.driver;

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

    private static final String[] FORWARDED_SYSTEM_PROPERTIES = {
            "playwright4j.debug"
    };

    public ProcessBuilder createProcessBuilder() {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
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
        command.add("com.aresstack.playwright4j.driver.GraalDriverMain");

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.environment().putAll(environment);
        return processBuilder;
    }

    private String javaExecutable() {
        String executableName = isWindows() ? "java.exe" : "java";
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + executableName;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public Path driverDir() {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "playwright4j-driver");
        ensurePackageExtracted(dir);
        return dir;
    }

    /**
     * Some upstream APIs (e.g. trace viewer) read static assets relative to driverDir(), i.e.
     * {@code driverDir/package/lib/...}. Those assets live inside the bundled driver jar under
     * {@code driver/package/}. Extract that tree once into driverDir so file-based
     * access works without a real Node driver installation.
     */
    private static void ensurePackageExtracted(Path driverDir) {
        Path marker = driverDir.resolve("package").resolve(".playwright4j-extracted");
        if (Files.exists(marker)) {
            return;
        }
        String prefix = com.aresstack.playwright4j.graal.PlaywrightDriverBundleSource.PACKAGE_PREFIX;
        try {
            URL probe = Driver.class.getClassLoader().getResource(prefix + "cli.js");
            if (probe == null) {
                return;
            }
            if ("jar".equals(probe.getProtocol())) {
                URLConnection connection = probe.openConnection();
                if (connection instanceof java.net.JarURLConnection) {
                    JarFile jarFile = ((java.net.JarURLConnection) connection).getJarFile();
                    extractFromJar(jarFile, prefix, driverDir);
                }
            }
            Files.createDirectories(marker.getParent());
            Files.write(marker, new byte[0]);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot extract driver package assets to " + driverDir, exception);
        }
    }

    private static void extractFromJar(JarFile jarFile, String prefix, Path driverDir) throws IOException {
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith(prefix) || entry.isDirectory()) {
                continue;
            }
            // Map driver/package/X -> driverDir/package/X
            Path target = driverDir.resolve("package").resolve(name.substring(prefix.length()));
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            try (InputStream input = jarFile.getInputStream(entry)) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public Map<String, String> environment() {
        return new LinkedHashMap<>(environment);
    }
}
