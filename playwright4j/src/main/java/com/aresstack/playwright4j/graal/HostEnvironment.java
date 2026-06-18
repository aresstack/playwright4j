package com.aresstack.playwright4j.graal;

public interface HostEnvironment {

    String platform();

    String architecture();

    String currentWorkingDirectory();

    String getEnvironmentValue(String name);
}
