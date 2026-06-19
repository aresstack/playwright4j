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
}
