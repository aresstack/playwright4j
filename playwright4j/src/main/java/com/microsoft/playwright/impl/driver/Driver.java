package com.microsoft.playwright.impl.driver;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

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
        return Paths.get(System.getProperty("java.io.tmpdir"), "playwright4j-driver");
    }

    public Map<String, String> environment() {
        return new LinkedHashMap<>(environment);
    }
}
