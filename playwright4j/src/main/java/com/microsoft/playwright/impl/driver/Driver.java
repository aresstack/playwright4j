package com.microsoft.playwright.impl.driver;

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

    public static Driver createAndInstall(Map<String, String> environment, boolean installBrowsers) {
        return new Driver(environment);
    }

    public ProcessBuilder createProcessBuilder() {
        throw new UnsupportedOperationException(
                "playwright4j replaces the external Node driver; patch PlaywrightImpl to use the Graal driver runtime instead of ProcessBuilder.");
    }

    public Map<String, String> environment() {
        return new LinkedHashMap<>(environment);
    }
}
