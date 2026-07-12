package com.aresstack.playwright4j.graal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class PlaywrightDriverBundleSource {

    private final ClassLoader classLoader;

    public PlaywrightDriverBundleSource(ClassLoader classLoader) {
        if (classLoader == null) {
            throw new IllegalArgumentException("ClassLoader must not be null.");
        }

        this.classLoader = classLoader;
    }

    public String cliScriptResourceName() {
        return "driver/" + platformDirectory() + "/package/cli.js";
    }

    public String readCliScript() {
        return readRequiredResource(cliScriptResourceName());
    }

    @org.graalvm.polyglot.HostAccess.Export
    public String packageResourceName(String packageRelativePath) {
        return "driver/" + platformDirectory() + "/package/" + packageRelativePath;
    }

    public String readPackageResource(String packageRelativePath) {
        return readRequiredResource(packageResourceName(packageRelativePath));
    }

    @org.graalvm.polyglot.HostAccess.Export
    public boolean hasResource(String resourceName) {
        try (InputStream inputStream = classLoader.getResourceAsStream(resourceName)) {
            return inputStream != null;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not check Playwright driver-bundle resource: " + resourceName, exception);
        }
    }

    @org.graalvm.polyglot.HostAccess.Export
    public String readResource(String resourceName) {
        return readRequiredResource(resourceName);
    }

    private String platformDirectory() {
        String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        if (operatingSystem.contains("windows")) {
            return "win32_x64";
        }

        if (operatingSystem.contains("linux")) {
            if ("aarch64".equals(architecture)) {
                return "linux-arm64";
            }

            return "linux";
        }

        if (operatingSystem.contains("mac os x") || operatingSystem.contains("darwin")) {
            if ("aarch64".equals(architecture)) {
                return "mac-arm64";
            }

            return "mac";
        }

        throw new IllegalStateException("Unsupported operating system for Playwright driver bundle: " + operatingSystem);
    }

    private String readRequiredResource(String resourceName) {
        try (InputStream inputStream = classLoader.getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing Playwright driver-bundle resource: " + resourceName);
            }

            return new String(readAllBytes(inputStream), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read Playwright driver-bundle resource: " + resourceName, exception);
        }
    }

    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int readBytes;

        while ((readBytes = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, readBytes);
        }

        return outputStream.toByteArray();
    }
}
