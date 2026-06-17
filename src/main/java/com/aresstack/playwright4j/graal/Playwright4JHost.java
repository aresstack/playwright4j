package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public final class Playwright4JHost {

    private final HostEnvironment environment;
    private final HostFileSystem fileSystem;
    private final MissingHostFunctionReporter missingHostFunctionReporter;

    public Playwright4JHost(
            HostEnvironment environment,
            HostFileSystem fileSystem,
            MissingHostFunctionReporter missingHostFunctionReporter) {
        this.environment = environment;
        this.fileSystem = fileSystem;
        this.missingHostFunctionReporter = missingHostFunctionReporter;
    }

    public static Playwright4JHost createDefault() {
        return new Playwright4JHost(
                new FixedHostEnvironment(),
                new EmptyHostFileSystem(),
                new RecordingMissingHostFunctionReporter());
    }

    @HostAccess.Export
    public HostEnvironment environment() {
        return environment;
    }

    @HostAccess.Export
    public HostFileSystem fileSystem() {
        return fileSystem;
    }

    @HostAccess.Export
    public MissingHostFunctionReporter missingHostFunctionReporter() {
        return missingHostFunctionReporter;
    }
}
