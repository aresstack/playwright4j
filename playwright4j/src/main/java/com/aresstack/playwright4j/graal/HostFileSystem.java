package com.aresstack.playwright4j.graal;

public interface HostFileSystem {

    boolean existsSync(String path);

    String readFileSync(String path, String encoding);

    /** Reads a file's raw bytes and returns them Base64-encoded (binary-safe). */
    String readFileBase64(String path);

    /** Last-modified time in epoch milliseconds, or 0 if unavailable. */
    long lastModifiedMillis(String path);

    /** File size in bytes, or 0 if unavailable. */
    long sizeBytes(String path);

    /** Whether the path refers to an existing directory. */
    boolean isDirectorySync(String path);

    String createTempDirectory(String prefix);

    void createDirectories(String path);

    void writeFile(String path, String content);
}
