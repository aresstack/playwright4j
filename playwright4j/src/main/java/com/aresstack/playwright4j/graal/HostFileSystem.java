package com.aresstack.playwright4j.graal;

public interface HostFileSystem {

    boolean existsSync(String path);

    String readFileSync(String path, String encoding);

    /** Reads a file's raw bytes and returns them Base64-encoded (binary-safe). */
    String readFileBase64(String path);

    /** Last-modified time in epoch milliseconds, or 0 if unavailable. */
    long lastModifiedMillis(String path);

    /** Sets the file's last-modified time (epoch milliseconds), for Node's fs.utimes. */
    void setLastModifiedMillis(String path, long millis);

    /** File size in bytes, or 0 if unavailable. */
    long sizeBytes(String path);

    /** Whether the path refers to an existing directory. */
    boolean isDirectorySync(String path);

    String createTempDirectory(String prefix);

    void createDirectories(String path);

    void writeFile(String path, String content);

    /** Writes raw bytes (provided Base64-encoded) to a file, creating parent directories. */
    void writeFileBase64(String path, String base64);

    /** Appends raw bytes (provided Base64-encoded) to a file, creating it if absent. */
    void appendFileBase64(String path, String base64);

    /** Deletes a file (no error if it does not exist). Backs fs.unlink/rm. */
    void deleteFile(String path);

    /** Recursively deletes a file or directory tree (no error if absent). Backs fs.rm recursive. */
    void deleteRecursively(String path);

    /** Copies a file, creating destination parent directories. Backs fs.copyFile. */
    void copyFile(String source, String destination);

    /** Moves/renames a file, creating destination parent directories. Backs fs.rename. */
    void rename(String source, String destination);
}
