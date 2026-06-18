package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public final class DisabledHostBrowserConfiguration implements HostBrowserConfiguration {

    @Override
    @HostAccess.Export
    public String localChromiumExecutablePath() {
        return "";
    }
}
