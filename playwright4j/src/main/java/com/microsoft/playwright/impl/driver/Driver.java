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
     * {@code driver/<platform>/package/}. Extract that tree once into driverDir so file-based
     * access works without a real Node driver installation.
     */
    private static void ensurePackageExtracted(Path driverDir) {
        Path marker = driverDir.resolve("package").resolve(".playwright4j-extracted");
        if (Files.exists(marker)) {
            return;
        }
        String prefix = "driver/" + platformDirectory() + "/package/";
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
            // Map driver/<platform>/package/X -> driverDir/package/X
            Path target = driverDir.resolve("package").resolve(name.substring(prefix.length()));
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            try (InputStream input = jarFile.getInputStream(entry)) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String platformDirectory() {
        String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (operatingSystem.contains("win")) {
            return "win32_x64";
        }
        if (operatingSystem.contains("linux")) {
            return "aarch64".equals(architecture) ? "linux-arm64" : "linux";
        }
        if (operatingSystem.contains("mac os x") || operatingSystem.contains("darwin")) {
            return "aarch64".equals(architecture) ? "mac-arm64" : "mac";
        }
        return "win32_x64";
    }

    public Map<String, String> environment() {
        return new LinkedHashMap<>(environment);
    }
}
