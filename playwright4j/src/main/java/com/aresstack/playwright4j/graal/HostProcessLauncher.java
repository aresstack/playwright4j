package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public interface HostProcessLauncher {

    @HostAccess.Export
    String launchChromium(String command, String arguments, String workingDirectory);

    @HostAccess.Export
    void close(String processId);
}
