package com.aresstack.playwright4j.graal;

/**
 * A stand-in browser for the kill tests: sleeps until it is killed. With "tree" it first starts
 * one more copy of itself, like a browser's content process.
 */
public final class SleepingProcessMain {

    private SleepingProcessMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "tree".equals(args[0])) {
            new ProcessBuilder(javaExecutable(), "-cp", classPath(), SleepingProcessMain.class.getName())
                    .inheritIO().start();
        }
        System.out.println("ready");
        System.out.flush();
        Thread.sleep(600_000L);
    }

    /** Only this class's own directory: the class needs nothing else, and Windows limits command lines. */
    static String classPath() {
        try {
            return java.nio.file.Paths.get(SleepingProcessMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static String javaExecutable() {
        return java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }
}
