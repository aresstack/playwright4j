package com.aresstack.playwright4j.graal;

public interface HostFileSystem {

    boolean existsSync(String path);

    String readFileSync(String path, String encoding);

    boolean isFile(String path);

    boolean isDirectory(String path);

    long size(String path);

    long lastModifiedMillis(String path);

    void createDirectories(String path);

    String createTempDirectory(String prefix);

    void writeFile(String path, String content);
}
