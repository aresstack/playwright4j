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
    public String readFileBase64(String path) {
        try {
            return java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(Paths.get(path)));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read file: " + path, exception);
        }
    }

    @Override
    @HostAccess.Export
    public long lastModifiedMillis(String path) {
        try {
            return Files.getLastModifiedTime(Paths.get(path)).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    @Override
    @HostAccess.Export
    public long sizeBytes(String path) {
        try {
            return Files.size(Paths.get(path));
        } catch (IOException exception) {
            return 0L;
        }
    }

    @Override
    @HostAccess.Export
    public boolean isDirectorySync(String path) {
        return Files.isDirectory(Paths.get(path));
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

    @Override
    @HostAccess.Export
    public void writeFileBase64(String path, String base64) {
        try {
            Path target = Paths.get(path);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            byte[] bytes = base64 == null || base64.isEmpty()
                    ? new byte[0]
                    : java.util.Base64.getDecoder().decode(base64);
            Files.write(target, bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write file: " + path, exception);
        }
    }

    @Override
    @HostAccess.Export
    public void appendFileBase64(String path, String base64) {
        try {
            Path target = Paths.get(path);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            byte[] bytes = base64 == null || base64.isEmpty()
                    ? new byte[0]
                    : java.util.Base64.getDecoder().decode(base64);
            Files.write(target, bytes, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot append file: " + path, exception);
        }
    }

    @Override
    @HostAccess.Export
    public void deleteFile(String path) {
        try {
            Files.deleteIfExists(Paths.get(path));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot delete file: " + path, exception);
        }
    }

    @Override
    @HostAccess.Export
    public void deleteRecursively(String path) {
        Path root = Paths.get(path);
        if (!Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot delete recursively: " + path, exception);
        }
    }

    @Override
    @HostAccess.Export
    public void copyFile(String source, String destination) {
        try {
            Path target = Paths.get(destination);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.copy(Paths.get(source), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot copy file: " + source + " -> " + destination, exception);
        }
    }

    @Override
    @HostAccess.Export
    public void rename(String source, String destination) {
        try {
            Path target = Paths.get(destination);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.move(Paths.get(source), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot move file: " + source + " -> " + destination, exception);
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
