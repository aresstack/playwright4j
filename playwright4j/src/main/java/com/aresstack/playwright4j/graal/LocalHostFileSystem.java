package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class LocalHostFileSystem implements HostFileSystem {

    @Override
    @HostAccess.Export
    public boolean existsSync(String path) {
        return Files.exists(Paths.get(path));
    }

    @Override
    @HostAccess.Export
    public String readFileSync(String path, String encoding) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(path, e);
        }
    }

    @Override
    @HostAccess.Export
    public boolean isFile(String path) {
        return Files.isRegularFile(Paths.get(path));
    }

    @Override
    @HostAccess.Export
    public boolean isDirectory(String path) {
        return Files.isDirectory(Paths.get(path));
    }

    @Override
    @HostAccess.Export
    public long size(String path) {
        try {
            return Files.size(Paths.get(path));
        } catch (IOException e) {
            throw new IllegalStateException(path, e);
        }
    }

    @Override
    @HostAccess.Export
    public long lastModifiedMillis(String path) {
        try {
            return Files.getLastModifiedTime(Paths.get(path)).toMillis();
        } catch (IOException e) {
            throw new IllegalStateException(path, e);
        }
    }

    @Override
    @HostAccess.Export
    public void createDirectories(String path) {
        try {
            Files.createDirectories(Paths.get(path));
        } catch (IOException e) {
            throw new IllegalStateException(path, e);
        }
    }

    @Override
    @HostAccess.Export
    public String createTempDirectory(String prefix) {
        try {
            Path prefixPath = Paths.get(prefix);
            Path parent = prefixPath.getParent();
            String name = prefixPath.getFileName().toString();
            if (parent == null) {
                return Files.createTempDirectory(name).toString();
            }
            Files.createDirectories(parent);
            return Files.createTempDirectory(parent, name).toString();
        } catch (IOException e) {
            throw new IllegalStateException(prefix, e);
        }
    }

    @Override
    @HostAccess.Export
    public void writeFile(String path, String content) {
        try {
            Path filePath = Paths.get(path);
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(path, e);
        }
    }
}
