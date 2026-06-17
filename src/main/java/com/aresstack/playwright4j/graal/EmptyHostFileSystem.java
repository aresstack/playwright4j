package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public final class EmptyHostFileSystem implements HostFileSystem {

    @Override
    @HostAccess.Export
    public boolean existsSync(String path) {
        return false;
    }

    @Override
    @HostAccess.Export
    public String readFileSync(String path, String encoding) {
        return "";
    }
}
