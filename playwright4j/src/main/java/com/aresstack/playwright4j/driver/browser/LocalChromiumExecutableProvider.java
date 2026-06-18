package com.aresstack.playwright4j.driver.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public final class LocalChromiumExecutableProvider implements ChromiumExecutableProvider {

    private final Playwright4JBrowserSettings settings;
    private final Map<String, String> environment;

    public LocalChromiumExecutableProvider(Playwright4JBrowserSettings settings, Map<String, String> environment) {
        this.settings = settings;
        this.environment = environment;
    }

    @Override
    public BrowserExecutable findChromium() {
        if (!settings.localChromiumFallbackEnabled()) {
            return null;
        }

        BrowserExecutable configuredExecutable = findConfiguredChromium();
        if (configuredExecutable != null) {
            return configuredExecutable;
        }

        return findInstalledChromium();
    }

    private BrowserExecutable findConfiguredChromium() {
        String configuredPath = settings.configuredChromiumExecutablePath();
        if (configuredPath == null) {
            return null;
        }

        Path executablePath = Paths.get(configuredPath);
        if (!Files.isRegularFile(executablePath)) {
            throw new IllegalStateException("Configured Chromium executable does not exist: " + executablePath);
        }

        return chromiumExecutable(executablePath);
    }

    private BrowserExecutable findInstalledChromium() {
        BrowserExecutable windowsChrome = firstExistingChromium(
                windowsEnvironmentPath("PROGRAMFILES", "Google", "Chrome", "Application", "chrome.exe"),
                windowsEnvironmentPath("PROGRAMFILES(X86)", "Google", "Chrome", "Application", "chrome.exe"),
                windowsEnvironmentPath("LOCALAPPDATA", "Google", "Chrome", "Application", "chrome.exe"));
        if (windowsChrome != null) {
            return windowsChrome;
        }

        BrowserExecutable macChrome = firstExistingChromium(
                Paths.get("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
        if (macChrome != null) {
            return macChrome;
        }

        return firstExistingChromium(
                Paths.get("/usr/bin/google-chrome"),
                Paths.get("/usr/bin/chromium"),
                Paths.get("/usr/bin/chromium-browser"));
    }

    private BrowserExecutable firstExistingChromium(Path... executablePaths) {
        for (Path executablePath : executablePaths) {
            if (executablePath != null && Files.isRegularFile(executablePath)) {
                return chromiumExecutable(executablePath);
            }
        }

        return null;
    }

    private BrowserExecutable chromiumExecutable(Path executablePath) {
        return new BrowserExecutable("chromium", executablePath.toAbsolutePath().toString());
    }

    private Path windowsEnvironmentPath(String environmentName, String first, String second, String third, String fourth) {
        String directory = environmentValue(environmentName);
        if (directory == null) {
            return null;
        }

        return Paths.get(directory, first, second, third, fourth);
    }

    private String environmentValue(String name) {
        String value = environment.get(name);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }

        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                return entry.getValue().trim();
            }
        }

        return null;
    }
}
