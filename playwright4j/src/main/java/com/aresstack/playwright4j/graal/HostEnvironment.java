package com.aresstack.playwright4j.graal;

public interface HostEnvironment {

    String platform();

    String architecture();

    String currentWorkingDirectory();

    String getEnvironmentValue(String name);

    /**
     * Names of all environment variables, unit-separator ({@code \u001F}) delimited, so the
     * node-compat layer can enumerate {@code process.env} (e.g. {@code {...process.env}}).
     */
    default String environmentNames() {
        return "";
    }
}
