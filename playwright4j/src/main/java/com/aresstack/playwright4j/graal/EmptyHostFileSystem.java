package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public final class EmptyHostFileSystem implements HostFileSystem {

    @Override
    @HostAccess.Export
    public boolean existsSync(String path) {
        return false;
    }

    @Override
    @HostAccess.Export
    public String readFileSync(String path, String encoding) {
        return "";
    }

    @Override
    @HostAccess.Export
    public boolean isFile(String path) {
        return false;
    }

    @Override
    @HostAccess.Export
    public boolean isDirectory(String path) {
        return false;
    }

    @Override
    @HostAccess.Export
    public long size(String path) {
        return 0L;
    }

    @Override
    @HostAccess.Export
    public long lastModifiedMillis(String path) {
        return 0L;
    }

    @Override
    @HostAccess.Export
    public void createDirectories(String path) {
    }

    @Override
    @HostAccess.Export
    public String createTempDirectory(String prefix) {
        return prefix;
    }

    @Override
    @HostAccess.Export
    public void writeFile(String path, String content) {
    }
}
