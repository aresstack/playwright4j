package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

public interface HostHttpClient {

    @HostAccess.Export
    HostHttpResponse request(String method, String url, String body);
}
