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
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read file: " + path, exception);
        }
    }

    @Override
    @HostAccess.Export
    public String createTempDirectory(String prefix) {
        try {
            Path base = Paths.get(System.getProperty("java.io.tmpdir"));
            Files.createDirectories(base);
            return Files.createTempDirectory(base, sanitizePrefix(prefix)).toAbsolutePath().toString();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create temporary directory for prefix: " + prefix, exception);
        }
    }

    @Override
    @HostAccess.Export
    public void createDirectories(String path) {
        try {
            Files.createDirectories(Paths.get(path));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create directories: " + path, exception);
        }
    }

    @Override
    @HostAccess.Export
    public void writeFile(String path, String content) {
        try {
            Path target = Paths.get(path);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write file: " + path, exception);
        }
    }

    private static String sanitizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "playwright4j-";
        }

        String lastSegment = prefix.replace('\\', '/');
        int separatorIndex = lastSegment.lastIndexOf('/');
        if (separatorIndex >= 0) {
            lastSegment = lastSegment.substring(separatorIndex + 1);
        }

        return lastSegment.isEmpty() ? "playwright4j-" : lastSegment;
    }
}
