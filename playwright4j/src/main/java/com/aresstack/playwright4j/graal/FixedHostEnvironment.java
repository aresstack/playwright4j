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
        return value == null ? "" : value;
    }
}
