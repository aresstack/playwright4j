package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public final class DisabledHostProcessLauncher implements HostProcessLauncher {

    @Override
    @HostAccess.Export
    public String launchChromium(String command, String arguments, String workingDirectory) {
        throw new UnsupportedOperationException("Process launching is not enabled for this Playwright4J host.");
    }

    @Override
    @HostAccess.Export
    public void close(String processId) {
        // Nothing to close.
    }

    @Override
    @HostAccess.Export
    public String spawnProcess(String command, String arguments, String workingDirectory) {
        throw new UnsupportedOperationException("Process launching is not enabled for this Playwright4J host.");
    }

    @Override
    @HostAccess.Export
    public void writeStdin(String processId, String base64) {
        // No process to write to.
    }

    @Override
    @HostAccess.Export
    public void endStdin(String processId) {
        // No process to end.
    }

    @Override
    @HostAccess.Export
    public String drainProcessEvents() {
        return "";
    }
}
