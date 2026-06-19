package com.aresstack.playwright4j.graal;

public interface HostFileSystem {

    boolean existsSync(String path);

    String readFileSync(String path, String encoding);

    String createTempDirectory(String prefix);

    void createDirectories(String path);

    void writeFile(String path, String content);
}
