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
    public String createTempDirectory(String prefix) {
        throw new UnsupportedOperationException("EmptyHostFileSystem cannot create temporary directories.");
    }

    @Override
    @HostAccess.Export
    public void createDirectories(String path) {
        throw new UnsupportedOperationException("EmptyHostFileSystem cannot create directories.");
    }

    @Override
    @HostAccess.Export
    public void writeFile(String path, String content) {
        throw new UnsupportedOperationException("EmptyHostFileSystem cannot write files.");
    }
}
