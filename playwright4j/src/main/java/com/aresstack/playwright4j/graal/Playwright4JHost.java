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
    private final JdkHostNetServer netServer = new JdkHostNetServer();

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

    /**
     * Raw DEFLATE (no zlib header/checksum) over Base64 input, returning Base64 output. Backs the
     * runtime's zlib.deflateRaw used by Playwright's HAR zip writer. level < 0 selects the default.
     */
    @HostAccess.Export
    public String deflateRaw(String base64Data, int level) {
        byte[] data = decodeBase64(base64Data);
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(
                level < 0 ? java.util.zip.Deflater.DEFAULT_COMPRESSION : level, true);
        try {
            deflater.setInput(data);
            deflater.finish();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(64, data.length / 2));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                out.write(buffer, 0, count);
            }
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } finally {
            deflater.end();
        }
    }

    /** Raw INFLATE (counterpart of {@link #deflateRaw}) over Base64 input, returning Base64. */
    @HostAccess.Export
    public String inflateRaw(String base64Data) {
        byte[] data = decodeBase64(base64Data);
        java.util.zip.Inflater inflater = new java.util.zip.Inflater(true);
        try {
            inflater.setInput(data);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(64, data.length * 3));
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, count);
            }
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (java.util.zip.DataFormatException exception) {
            throw new IllegalStateException("Cannot inflate raw deflate stream", exception);
        } finally {
            inflater.end();
        }
    }

    /** gzip (with header/trailer) over Base64 input, returning Base64. Backs zlib.gzip. */
    @HostAccess.Export
    public String gzip(String base64Data) {
        byte[] data = decodeBase64(base64Data);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(64, data.length / 2));
            try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(out)) {
                gzip.write(data);
            }
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot gzip data", exception);
        }
    }

    /** gunzip counterpart of {@link #gzip}. Backs zlib.gunzip. */
    @HostAccess.Export
    public String gunzip(String base64Data) {
        byte[] data = decodeBase64(base64Data);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(64, data.length * 3));
            try (java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = gzip.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
            }
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot gunzip data", exception);
        }
    }

    private static byte[] decodeBase64(String base64Data) {
        return base64Data == null || base64Data.isEmpty()
                ? new byte[0]
                : java.util.Base64.getDecoder().decode(base64Data);
    }

    @HostAccess.Export
    public JdkHostNetServer netServer() {
        return netServer;
    }

    @HostAccess.Export
    public MissingHostFunctionReporter missingHostFunctionReporter() {
        return missingHostFunctionReporter;
    }
}
