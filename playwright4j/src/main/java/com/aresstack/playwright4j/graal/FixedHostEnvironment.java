package com.aresstack.playwright4j.graal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.graalvm.polyglot.HostAccess;

public final class FixedHostEnvironment implements HostEnvironment {

    private final String platform;
    private final String architecture;
    private final String currentWorkingDirectory;
    private final Map<String, String> environmentValues;
    private final Map<String, String> environmentValuesByLowerCaseName;

    public FixedHostEnvironment() {
        this("win32", "x64", "/", Collections.<String, String>emptyMap());
    }

    public FixedHostEnvironment(
            String platform,
            String architecture,
            String currentWorkingDirectory,
            Map<String, String> environmentValues) {
        this.platform = platform;
        this.architecture = architecture;
        this.currentWorkingDirectory = currentWorkingDirectory;
        this.environmentValues = new LinkedHashMap<String, String>(environmentValues);
        this.environmentValuesByLowerCaseName = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : environmentValues.entrySet()) {
            this.environmentValuesByLowerCaseName.put(entry.getKey().toLowerCase(), entry.getValue());
        }
    }

    @Override
    @HostAccess.Export
    public String platform() {
        return platform;
    }

    @Override
    @HostAccess.Export
    public String architecture() {
        return architecture;
    }

    @Override
    @HostAccess.Export
    public String currentWorkingDirectory() {
        return currentWorkingDirectory;
    }

    @Override
    @HostAccess.Export
    public String getEnvironmentValue(String name) {
        String value = environmentValues.get(name);
        if (value == null && name != null) {
            // Node's process.env is case-insensitive on Windows; mirror that so the bundled
            // registry can resolve PROGRAMFILES / LOCALAPPDATA and friends.
            value = environmentValuesByLowerCaseName.get(name.toLowerCase());
        }
        return value == null ? "" : value;
    }
}
