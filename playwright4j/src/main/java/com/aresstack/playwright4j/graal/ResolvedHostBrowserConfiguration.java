package com.aresstack.playwright4j.graal;

import com.aresstack.playwright4j.driver.browser.BrowserExecutable;
import com.aresstack.playwright4j.driver.browser.ChromiumExecutableProvider;
import org.graalvm.polyglot.HostAccess;

public final class ResolvedHostBrowserConfiguration implements HostBrowserConfiguration {

    private final ChromiumExecutableProvider chromiumExecutableProvider;

    public ResolvedHostBrowserConfiguration(ChromiumExecutableProvider chromiumExecutableProvider) {
        this.chromiumExecutableProvider = chromiumExecutableProvider;
    }

    @Override
    @HostAccess.Export
    public String localChromiumExecutablePath() {
        BrowserExecutable executable = chromiumExecutableProvider.findChromium();
        if (executable == null) {
            return "";
        }
        return executable.executablePath();
    }
}
