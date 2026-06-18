package com.aresstack.playwright4j.driver;

import com.aresstack.playwright4j.graal.HostDriverPipe;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class StandardIoDriverPipe implements HostDriverPipe {

    private final OutputStream out;
    private final OutputStream err;
    private final StringBuilder pendingOut = new StringBuilder();

    public StandardIoDriverPipe(OutputStream out, OutputStream err) {
        this.out = out;
        this.err = err;
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public synchronized boolean writeOut(String chunk) {
        pendingOut.append(chunk);
        flushCompleteMessages();
        return true;
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public synchronized boolean writeMessage(String message) {
        writeLengthPrefixedMessage(message);
        return true;
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public synchronized boolean writeErr(String chunk) {
        try {
            err.write(chunk.getBytes(StandardCharsets.UTF_8));
            err.flush();
            return true;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write driver stderr.", exception);
        }
    }

    private void flushCompleteMessages() {
        int end = pendingOut.indexOf("\0");

        while (end >= 0) {
            String message = pendingOut.substring(0, end);
            pendingOut.delete(0, end + 1);
            writeLengthPrefixedMessage(message);
            end = pendingOut.indexOf("\0");
        }
    }

    private void writeLengthPrefixedMessage(String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);

        try {
            writeIntLE(out, bytes.length);
            out.write(bytes);
            out.flush();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write driver protocol message.", exception);
        }
    }

    private static void writeIntLE(OutputStream out, int value) throws IOException {
        out.write(value >>> 0 & 255);
        out.write(value >>> 8 & 255);
        out.write(value >>> 16 & 255);
        out.write(value >>> 24 & 255);
    }
}
