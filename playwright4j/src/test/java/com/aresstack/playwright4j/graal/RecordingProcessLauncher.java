package com.aresstack.playwright4j.graal;

/** Records the environment a spawn hands to the host (public: GraalJS only calls public host classes). */
public final class RecordingProcessLauncher implements HostProcessLauncher {
    volatile String environment;

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public String launchChromium(String command, String arguments, String workingDirectory) {
        throw new UnsupportedOperationException();
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public void close(String processId) {
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public String spawnProcess(String command, String arguments, String workingDirectory) {
        throw new UnsupportedOperationException("expected spawnProcessWithEnvironment");
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public String spawnProcessWithEnvironment(String command, String arguments, String workingDirectory, String environment) {
        this.environment = environment;
        return "recorded";
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public void writeStdin(String processId, String base64) {
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public void endStdin(String processId) {
    }

    @Override
    @org.graalvm.polyglot.HostAccess.Export
    public String drainProcessEvents() {
        return "";
    }
}
