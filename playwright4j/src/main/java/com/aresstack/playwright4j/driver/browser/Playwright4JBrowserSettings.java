package com.aresstack.playwright4j.driver.browser;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Playwright4JBrowserSettings {

    public static final String CHROMIUM_EXECUTABLE_PATH_PROPERTY = "playwright4j.chrome.executablePath";
    public static final String CHROMIUM_EXECUTABLE_PATH_ENVIRONMENT = "PLAYWRIGHT4J_CHROME_EXECUTABLE_PATH";
    public static final String LOCAL_CHROMIUM_FALLBACK_PROPERTY = "playwright4j.localChromiumFallback";
    public static final String LOCAL_CHROMIUM_FALLBACK_ENVIRONMENT = "PLAYWRIGHT4J_LOCAL_CHROMIUM_FALLBACK";

    private final boolean localChromiumFallbackEnabled;
    private final String configuredChromiumExecutablePath;

    private Playwright4JBrowserSettings(boolean localChromiumFallbackEnabled, String configuredChromiumExecutablePath) {
        this.localChromiumFallbackEnabled = localChromiumFallbackEnabled;
        this.configuredChromiumExecutablePath = normalize(configuredChromiumExecutablePath);
    }

    public static Playwright4JBrowserSettings fromSystemPropertiesAndEnvironment(Map<String, String> environment) {
        String executablePath = firstText(
                System.getProperty(CHROMIUM_EXECUTABLE_PATH_PROPERTY),
                environment.get(CHROMIUM_EXECUTABLE_PATH_ENVIRONMENT));
        String fallbackEnabled = firstText(
                System.getProperty(LOCAL_CHROMIUM_FALLBACK_PROPERTY),
                environment.get(LOCAL_CHROMIUM_FALLBACK_ENVIRONMENT));

        return new Playwright4JBrowserSettings(!isFalse(fallbackEnabled), executablePath);
    }

    public boolean localChromiumFallbackEnabled() {
        return localChromiumFallbackEnabled;
    }

    public String configuredChromiumExecutablePath() {
        return configuredChromiumExecutablePath;
    }

    public Map<String, String> childJavaProperties() {
        Map<String, String> properties = new LinkedHashMap<String, String>();
        properties.put(LOCAL_CHROMIUM_FALLBACK_PROPERTY, Boolean.toString(localChromiumFallbackEnabled));

        if (configuredChromiumExecutablePath != null) {
            properties.put(CHROMIUM_EXECUTABLE_PATH_PROPERTY, configuredChromiumExecutablePath);
        }

        return properties;
    }

    public void applyToEnvironment(Map<String, String> targetEnvironment) {
        targetEnvironment.put(LOCAL_CHROMIUM_FALLBACK_ENVIRONMENT, Boolean.toString(localChromiumFallbackEnabled));

        if (configuredChromiumExecutablePath != null) {
            targetEnvironment.put(CHROMIUM_EXECUTABLE_PATH_ENVIRONMENT, configuredChromiumExecutablePath);
        }
    }

    private static String firstText(String first, String second) {
        String normalizedFirst = normalize(first);
        if (normalizedFirst != null) {
            return normalizedFirst;
        }

        return normalize(second);
    }

    private static boolean isFalse(String value) {
        return "false".equalsIgnoreCase(normalize(value));
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
