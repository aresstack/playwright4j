package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public interface HostProcessLauncher {

    /**
     * Launches a local Chromium process and returns a value of the form
     * {@code processId + "" + wsEndpoint}, where {@code wsEndpoint} is the
     * DevTools WebSocket endpoint discovered from the {@code DevToolsActivePort} file.
     */
    @HostAccess.Export
    String launchChromium(String command, String arguments, String workingDirectory);

    @HostAccess.Export
    void close(String processId);

    /**
     * Spawns a general (non-Chromium) child process, e.g. the ffmpeg video encoder. Arguments are
     * unit-separator delimited. Returns a process id. stdout/stderr/exit are delivered via
     * {@link #drainProcessEvents()}; input bytes are fed to the child via {@link #writeStdin}.
     */
    @HostAccess.Export
    String spawnProcess(String command, String arguments, String workingDirectory);

    /** Writes Base64-encoded bytes to a spawned process's stdin. */
    @HostAccess.Export
    void writeStdin(String processId, String base64);

    /** Closes a spawned process's stdin (signals EOF, e.g. so ffmpeg finalizes its output). */
    @HostAccess.Export
    void endStdin(String processId);

    /** Drains queued process events (stdout/stderr data, exit) as a newline-separated batch. */
    @HostAccess.Export
    String drainProcessEvents();
}
