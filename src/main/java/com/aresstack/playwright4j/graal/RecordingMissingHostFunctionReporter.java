package com.aresstack.playwright4j.graal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.graalvm.polyglot.HostAccess;

public final class RecordingMissingHostFunctionReporter implements MissingHostFunctionReporter {

    private final List<String> missingFunctions = new ArrayList<String>();

    @Override
    @HostAccess.Export
    public void reportMissingHostFunction(String name) {
        missingFunctions.add(name);
    }

    public List<String> missingFunctions() {
        return Collections.unmodifiableList(missingFunctions);
    }
}
