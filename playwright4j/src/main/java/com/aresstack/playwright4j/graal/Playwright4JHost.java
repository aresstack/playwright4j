package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public final class Playwright4JHost {

    private final HostEnvironment environment;
    private final HostFileSystem fileSystem;
    private final HostHttpClient httpClient;
    private final HostWebSocketClient webSocketClient;
    private final HostDriverPipe driverPipe;
    private final HostBrowserConfiguration browserConfiguration;
    private final MissingHostFunctionReporter missingHostFunctionReporter;

    public Playwright4JHost(
            HostEnvironment environment,
            HostFileSystem fileSystem,
            MissingHostFunctionReporter missingHostFunctionReporter) {
        this(environment, fileSystem, new JdkHostHttpClient(), new JdkHostWebSocketClient(), new NoopHostDriverPipe(), new DisabledHostBrowserConfiguration(), missingHostFunctionReporter);
    }

    public Playwright4JHost(
            HostEnvironment environment,
            HostFileSystem fileSystem,
            HostHttpClient httpClient,
            HostWebSocketClient webSocketClient,
            MissingHostFunctionReporter missingHostFunctionReporter) {
        this(environment, fileSystem, httpClient, webSocketClient, new NoopHostDriverPipe(), new DisabledHostBrowserConfiguration(), missingHostFunctionReporter);
    }

    public Playwright4JHost(
            HostEnvironment environment,
            HostFileSystem fileSystem,
            HostHttpClient httpClient,
            HostWebSocketClient webSocketClient,
            HostDriverPipe driverPipe,
            MissingHostFunctionReporter missingHostFunctionReporter) {
        this(environment, fileSystem, httpClient, webSocketClient, driverPipe, new DisabledHostBrowserConfiguration(), missingHostFunctionReporter);
    }

    public Playwright4JHost(
            HostEnvironment environment,
            HostFileSystem fileSystem,
            HostHttpClient httpClient,
            HostWebSocketClient webSocketClient,
            HostDriverPipe driverPipe,
            HostBrowserConfiguration browserConfiguration,
            MissingHostFunctionReporter missingHostFunctionReporter) {
        this.environment = environment;
        this.fileSystem = fileSystem;
        this.httpClient = httpClient;
        this.webSocketClient = webSocketClient;
        this.driverPipe = driverPipe;
        this.browserConfiguration = browserConfiguration;
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
    public HostHttpClient httpClient() {
        return httpClient;
    }

    @HostAccess.Export
    public HostWebSocketClient webSocketClient() {
        return webSocketClient;
    }

    @HostAccess.Export
    public HostDriverPipe driverPipe() {
        return driverPipe;
    }

    @HostAccess.Export
    public HostBrowserConfiguration browserConfiguration() {
        return browserConfiguration;
    }

    @HostAccess.Export
    public MissingHostFunctionReporter missingHostFunctionReporter() {
        return missingHostFunctionReporter;
    }
}
