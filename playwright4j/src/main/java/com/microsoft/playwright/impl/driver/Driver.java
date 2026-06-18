package com.microsoft.playwright.impl.driver;

import com.aresstack.playwright4j.driver.browser.Playwright4JBrowserSettings;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        Playwright4JBrowserSettings browserSettings = Playwright4JBrowserSettings.fromSystemPropertiesAndEnvironment(environment);
        ProcessBuilder processBuilder = new ProcessBuilder(javaCommand(browserSettings));
        processBuilder.environment().putAll(environment);
        browserSettings.applyToEnvironment(processBuilder.environment());
        return processBuilder;
    }

    private List<String> javaCommand(Playwright4JBrowserSettings browserSettings) {
        List<String> command = new ArrayList<String>();
        command.add(javaExecutable());

        for (Map.Entry<String, String> property : browserSettings.childJavaProperties().entrySet()) {
            command.add("-D" + property.getKey() + "=" + property.getValue());
        }

        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("com.aresstack.playwright4j.driver.GraalDriverMain");
        return command;
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
