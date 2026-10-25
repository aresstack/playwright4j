package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * process.env is enumerable and writable like Node's, and child_process.spawn hands options.env
 * (Playwright's launch env) to the host as the child's complete environment.
 */
final class NodeCompatProcessEnvTest {

    @Test
    void processEnvIsEnumerableWritableAndCaseInsensitiveOnWindows() {
        try (GraalPlaywrightRuntime runtime = runtime("win32", new RecordingProcessLauncher())) {
            assertEquals("Path,SystemRoot", eval(runtime, "Object.keys(process.env).join(',')"));
            assertEquals("C:\\Windows", eval(runtime, "process.env.SYSTEMROOT"));
            assertEquals("true", eval(runtime, "String('PATH' in process.env)"));
            assertEquals("C:\\Windows", eval(runtime, "({ ...process.env }).SystemRoot"));

            eval(runtime, "process.env.PLAYWRIGHT4J_ENV_TEST = 'set'; delete process.env.Path; ''");
            assertEquals("SystemRoot,PLAYWRIGHT4J_ENV_TEST", eval(runtime, "Object.keys(process.env).join(',')"));
            assertEquals("set", eval(runtime, "process.env.playwright4j_env_test"));
            assertEquals("false", eval(runtime, "String('Path' in process.env)"));
        }
    }

    @Test
    void processEnvNamesAreCaseSensitiveElsewhere() {
        try (GraalPlaywrightRuntime runtime = runtime("linux", new RecordingProcessLauncher())) {
            assertEquals("false", eval(runtime, "String('PATH' in process.env)"));
            assertEquals("true", eval(runtime, "String('Path' in process.env)"));
        }
    }

    @Test
    void spawnPassesOptionsEnvAsTheCompleteEnvironment() {
        RecordingProcessLauncher launcher = new RecordingProcessLauncher();
        try (GraalPlaywrightRuntime runtime = runtime("linux", launcher)) {
            eval(runtime, "require('child_process').spawn('tool', ['a'], { env: { A: '1', B: 'x = y', DROPPED: undefined } }); ''");
            assertEquals("A=1\u001EB=x = y", launcher.environment);

            eval(runtime, "require('child_process').spawn('tool', ['a'], {}); ''");
            assertNull(launcher.environment, "no env: the child inherits the host environment");

            eval(runtime, "require('child_process').spawn('tool', ['a'], { env: { ...process.env, MOZ_CRASHREPORTER: '1' } }); ''");
            assertEquals("Path=/bin\u001ESystemRoot=C:\\Windows\u001EMOZ_CRASHREPORTER=1", launcher.environment);
        }
    }

    @Test
    void theLauncherReplacesTheInheritedEnvironment() {
        ProcessBuilder builder = new ProcessBuilder("tool");
        builder.environment().put("PLAYWRIGHT4J_ENV_INHERITED", "1");
        JdkHostProcessLauncher.applyEnvironment(builder, null);
        assertEquals("1", builder.environment().get("PLAYWRIGHT4J_ENV_INHERITED"));

        JdkHostProcessLauncher.applyEnvironment(builder, "A=1\u001EB=x = y");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("A", "1");
        expected.put("B", "x = y");
        assertEquals(expected, new LinkedHashMap<>(builder.environment()));

        // Camoufox fingerprint chunks: long and non-ASCII values are passed on unchanged.
        String chunk = "{\"x\":\"\u00fc\u4e2d\"}" + "a".repeat(2047);
        JdkHostProcessLauncher.applyEnvironment(builder, "CAMOU_CONFIG_1=" + chunk);
        assertEquals(chunk, builder.environment().get("CAMOU_CONFIG_1"));
    }

    private static GraalPlaywrightRuntime runtime(String platform, HostProcessLauncher launcher) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("Path", "/bin");
        environment.put("SystemRoot", "C:\\Windows");
        Playwright4JHost host = new Playwright4JHost(
                new FixedHostEnvironment(platform, "x64", "/", environment),
                new LocalHostFileSystem(),
                new JdkHostHttpClient(),
                new JdkHostWebSocketClient(),
                new NoopHostDriverPipe(),
                launcher,
                new RecordingMissingHostFunctionReporter());
        GraalPlaywrightRuntime runtime = new GraalPlaywrightRuntime(host);
        runtime.loadNodeCompatibilityLayer();
        return runtime;
    }

    private static String eval(GraalPlaywrightRuntime runtime, String source) {
        return runtime.evaluate("process-env-test.js", source).asString();
    }
}
