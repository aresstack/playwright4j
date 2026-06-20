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
    public String readFileBase64(String path) {
        return "";
    }

    @Override
    @HostAccess.Export
    public long lastModifiedMillis(String path) {
        return 0L;
    }

    @Override
    @HostAccess.Export
    public long sizeBytes(String path) {
        return 0L;
    }

    @Override
    @HostAccess.Export
    public boolean isDirectorySync(String path) {
        return false;
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

    @Override
    @HostAccess.Export
    public void writeFileBase64(String path, String base64) {
        throw new UnsupportedOperationException("EmptyHostFileSystem cannot write files.");
    }

    @Override
    @HostAccess.Export
    public void deleteFile(String path) {
        // No-op: nothing to delete on an empty filesystem.
    }

    @Override
    @HostAccess.Export
    public void deleteRecursively(String path) {
        // No-op: nothing to delete on an empty filesystem.
    }

    @Override
    @HostAccess.Export
    public void copyFile(String source, String destination) {
        throw new UnsupportedOperationException("EmptyHostFileSystem cannot copy files.");
    }

    @Override
    @HostAccess.Export
    public void rename(String source, String destination) {
        throw new UnsupportedOperationException("EmptyHostFileSystem cannot move files.");
    }
}
