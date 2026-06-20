package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public final class Playwright4JHost {

    private final HostEnvironment environment;
    private final HostFileSystem fileSystem;
    private final HostHttpClient httpClient;
    private final HostWebSocketClient webSocketClient;
    private final HostDriverPipe driverPipe;
    private final HostProcessLauncher processLauncher;
    private final MissingHostFunctionReporter missingHostFunctionReporter;

    public Playwright4JHost(
            HostEnvironment environment,
            HostFileSystem fileSystem,
            MissingHostFunctionReporter missingHostFunctionReporter) {
        this(environment, fileSystem, new JdkHostHttpClient(), new JdkHostWebSocketClient(), new NoopHostDriverPipe(), missingHostFunctionReporter);
    }

    public Playwright4JHost(
            HostEnvironment environment,
            HostFileSystem fileSystem,
            HostHttpClient httpClient,
            HostWebSocketClient webSocketClient,
            MissingHostFunctionReporter missingHostFunctionReporter) {
        this(environment, fileSystem, httpClient, webSocketClient, new NoopHostDriverPipe(), missingHostFunctionReporter);
    }

    public Playwright4JHost(
            HostEnvironment environment,
            HostFileSystem fileSystem,
            HostHttpClient httpClient,
            HostWebSocketClient webSocketClient,
            HostDriverPipe driverPipe,
            MissingHostFunctionReporter missingHostFunctionReporter) {
        this(environment, fileSystem, httpClient, webSocketClient, driverPipe,
                new DisabledHostProcessLauncher(), missingHostFunctionReporter);
    }

    public Playwright4JHost(
            HostEnvironment environment,
            HostFileSystem fileSystem,
            HostHttpClient httpClient,
            HostWebSocketClient webSocketClient,
            HostDriverPipe driverPipe,
            HostProcessLauncher processLauncher,
            MissingHostFunctionReporter missingHostFunctionReporter) {
        this.environment = environment;
        this.fileSystem = fileSystem;
        this.httpClient = httpClient;
        this.webSocketClient = webSocketClient;
        this.driverPipe = driverPipe;
        this.processLauncher = processLauncher;
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
    public HostProcessLauncher processLauncher() {
        return processLauncher;
    }

    @HostAccess.Export
    public void debugLog(String message) {
        Playwright4JDebug.log("[pw4j-js] " + message);
    }

    /**
     * Computes a message digest (e.g. sha1, sha256, md5) over Base64-encoded input and returns
     * the lower-case hex digest. Backs the runtime's crypto.createHash shim.
     */
    @HostAccess.Export
    public String digestHex(String algorithm, String base64Data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance(mapDigestAlgorithm(algorithm));
            byte[] data = base64Data == null || base64Data.isEmpty()
                    ? new byte[0]
                    : java.util.Base64.getDecoder().decode(base64Data);
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalArgumentException("Unsupported digest algorithm: " + algorithm, exception);
        }
    }

    private static String mapDigestAlgorithm(String algorithm) {
        String normalized = algorithm == null ? "" : algorithm.toLowerCase().replace("-", "");
        if (normalized.equals("sha1")) {
            return "SHA-1";
        }
        if (normalized.equals("sha256")) {
            return "SHA-256";
        }
        if (normalized.equals("sha512")) {
            return "SHA-512";
        }
        if (normalized.equals("md5")) {
            return "MD5";
        }
        return algorithm;
    }

    @HostAccess.Export
    public MissingHostFunctionReporter missingHostFunctionReporter() {
        return missingHostFunctionReporter;
    }
}
