package com.aresstack.playwright4j.driver;

import com.aresstack.playwright4j.graal.HostDriverPipe;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Driver pipe for the --node-compat CLI mode (e.g. cli.js launch-server). Unlike the normal driver
 * pipe there is no length-prefixed protocol on stdout: process.stdout is the caller's real stdout
 * (it carries the ws:// endpoint line), and process.stderr is real stderr. writeMessage is unused.
 */
public final class RawIoDriverPipe implements HostDriverPipe {

    private final OutputStream out;
    private final OutputStream err;

    public RawIoDriverPipe(OutputStream out, OutputStream err) {
        this.out = out;
        this.err = err;
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public synchronized boolean writeOut(String chunk) {
        return writeTo(out, chunk);
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public synchronized boolean writeErr(String chunk) {
        return writeTo(err, chunk);
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public synchronized boolean writeMessage(String message) {
        return writeTo(out, message);
    }

    private static boolean writeTo(OutputStream stream, String chunk) {
        try {
            stream.write(chunk.getBytes(StandardCharsets.UTF_8));
            stream.flush();
            return true;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write node-compat output.", exception);
        }
    }
}
