package com.aresstack.playwright4j.graal;

public interface HostEnvironment {

    String platform();

    String architecture();

    String currentWorkingDirectory();

    String getEnvironmentValue(String name);

    /** {@code os.homedir()}: the JVM's {@code user.home}. */
    default String homeDirectory() {
        return System.getProperty("user.home");
    }

    /** {@code os.tmpdir()}: the JVM's {@code java.io.tmpdir} without a trailing separator, as Node returns it. */
    default String temporaryDirectory() {
        String directory = System.getProperty("java.io.tmpdir");
        while (directory.length() > 1 && (directory.endsWith("/") || directory.endsWith("\\")) && !directory.endsWith(":\\")) {
            directory = directory.substring(0, directory.length() - 1);
        }
        return directory;
    }

    /**
     * Names of all environment variables, record-separator ({@code \u001E}) delimited like the launcher arguments, so the
     * node-compat layer can enumerate {@code process.env} (e.g. {@code {...process.env}}).
     */
    default String environmentNames() {
        return "";
    }
}
