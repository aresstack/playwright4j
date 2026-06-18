package com.microsoft.playwright.impl.driver;

import java.io.File;
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

    public ProcessBuilder createProcessBuilder() {
        ProcessBuilder processBuilder = new ProcessBuilder(
                javaExecutable(),
                "-cp",
                System.getProperty("java.class.path"),
                "com.aresstack.playwright4j.driver.GraalDriverMain");
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

    public Map<String, String> environment() {
        return new LinkedHashMap<>(environment);
    }
}
