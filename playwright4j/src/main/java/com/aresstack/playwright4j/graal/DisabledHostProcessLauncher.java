package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public final class DisabledHostProcessLauncher implements HostProcessLauncher {

    @Override
    @HostAccess.Export
    public String launchChromium(String command, String arguments, String workingDirectory) {
        throw new IllegalStateException("Process launching is disabled in this Playwright4J host.");
    }

    @Override
    @HostAccess.Export
    public void close(String processId) {
    }
}
