package com.aresstack.playwright4j.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Playwright's force kill (processLauncher.killProcess: {@code taskkill /pid <pid> /T /F} through
 * spawnSync on Windows, {@code process.kill(-pid, 'SIGKILL')} elsewhere) must end the real host
 * process tree, so a browser whose close hangs is gone after the close timeout and the 'close'
 * event that Playwright's waitForCleanup waits for arrives.
 */
final class NodeCompatProcessKillTest {

    // The same statement sequence as Playwright's killProcess, per platform.
    private static final String PLAYWRIGHT_FORCE_KILL =
            "(function (child) {"
            + "  if (process.platform === 'win32') {"
            + "    const t = require('child_process').spawnSync(`taskkill /pid ${child.pid} /T /F`, { shell: true, windowsHide: true });"
            + "    return 'status=' + t.status + ' out=' + t.stdout.toString() + ' err=' + t.stderr.toString();"
            + "  }"
            + "  process.kill(-child.pid, 'SIGKILL');"
            + "  return 'killed';"
            + "})(globalThis.__killTestChild)";

    @Test
    void forceKillEndsTheProcessTreeOnWindows() throws Exception {
        forceKillEndsTheProcessTree("win32");
    }

    @Test
    void forceKillEndsTheProcessTreeElsewhere() throws Exception {
        forceKillEndsTheProcessTree("linux");
    }

    private void forceKillEndsTheProcessTree(String platform) throws Exception {
        JdkHostProcessLauncher launcher = new JdkHostProcessLauncher();
        try (GraalPlaywrightRuntime runtime = runtime(platform, launcher)) {
            spawnSleeper(runtime, "tree");
            long pid = Long.parseLong(eval(runtime, "String(globalThis.__killTestChild.pid)"));
            ProcessHandle browser = ProcessHandle.of(pid).orElseThrow(() -> new AssertionError("child.pid is not the real pid"));
            List<ProcessHandle> tree = waitForDescendant(browser);

            String result = eval(runtime, PLAYWRIGHT_FORCE_KILL);
            if ("win32".equals(platform)) {
                assertTrue(result.startsWith("status=0 out=SUCCESS"), result);
            }
            waitForClose(runtime);

            assertGone(browser);
            for (ProcessHandle descendant : tree) {
                assertGone(descendant);
            }
        } finally {
            launcher.closeAll();
        }
    }

    @Test
    void childKillTerminatesAndForceKillsAfterTheGracePeriod() throws Exception {
        JdkHostProcessLauncher launcher = new JdkHostProcessLauncher(500L);
        try (GraalPlaywrightRuntime runtime = runtime("linux", launcher)) {
            spawnSleeper(runtime, "tree");
            long pid = Long.parseLong(eval(runtime, "String(globalThis.__killTestChild.pid)"));
            ProcessHandle browser = ProcessHandle.of(pid).orElseThrow(AssertionError::new);
            List<ProcessHandle> tree = waitForDescendant(browser);

            eval(runtime, "String(globalThis.__killTestChild.kill())");
            waitForClose(runtime);

            assertGone(browser);
            for (ProcessHandle descendant : tree) {
                assertGone(descendant);
            }
        } finally {
            launcher.closeAll();
        }
    }

    @Test
    void unknownPidsBehaveLikeNode() {
        JdkHostProcessLauncher launcher = new JdkHostProcessLauncher();
        try (GraalPlaywrightRuntime runtime = runtime("win32", launcher)) {
            assertEquals("ESRCH", eval(runtime, "(function () { try { process.kill(-123456789, 'SIGKILL'); return 'no error'; } catch (e) { return e.code; } })()"));
            assertEquals("128", eval(runtime, "String(require('child_process').spawnSync('taskkill /pid 123456789 /T /F', { shell: true }).status)"));
        } finally {
            launcher.closeAll();
        }
    }

    @Test
    void signalZeroTestsForExistence() throws Exception {
        JdkHostProcessLauncher launcher = new JdkHostProcessLauncher();
        try (GraalPlaywrightRuntime runtime = runtime("linux", launcher)) {
            spawnSleeper(runtime, "single");
            assertEquals("true", eval(runtime, "String(process.kill(globalThis.__killTestChild.pid, 0))"));
            long pid = Long.parseLong(eval(runtime, "String(globalThis.__killTestChild.pid)"));
            assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "signal 0 must not kill");
        } finally {
            launcher.closeAll();
        }
    }

    private static void spawnSleeper(GraalPlaywrightRuntime runtime, String mode) {
        String java = SleepingProcessMain.javaExecutable().replace("\\", "\\\\");
        String classPath = SleepingProcessMain.classPath().replace("\\", "\\\\");
        eval(runtime, "globalThis.__killTestClosed = 'no';"
                + "globalThis.__killTestChild = require('child_process').spawn('" + java + "',"
                + " ['-cp', '" + classPath + "', '" + SleepingProcessMain.class.getName() + "', '" + mode + "'],"
                + " { detached: true, stdio: ['pipe', 'pipe', 'pipe'] });"
                + "globalThis.__killTestChild.once('close', function (code) { globalThis.__killTestClosed = 'yes'; });"
                + "''");
    }

    private static List<ProcessHandle> waitForDescendant(ProcessHandle browser) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            List<ProcessHandle> descendants = new ArrayList<>();
            browser.descendants().forEach(descendants::add);
            if (!descendants.isEmpty()) {
                return descendants;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("the stand-in browser did not start its child process");
    }

    // Pumps process events like the driver loop until the child's 'close' event fired.
    private static void waitForClose(GraalPlaywrightRuntime runtime) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            eval(runtime, "String(globalThis.__playwright4jDrainProcesses())");
            if ("yes".equals(eval(runtime, "globalThis.__killTestClosed"))) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("no 'close' event after the kill: Playwright's waitForCleanup would hang");
    }

    private static void assertGone(ProcessHandle process) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (process.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(25L);
        }
        assertFalse(process.isAlive(), "process " + process.pid() + " survived the kill");
    }

    private static GraalPlaywrightRuntime runtime(String platform, HostProcessLauncher launcher) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("Path", "/bin");
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
        return runtime.evaluate("process-kill-test.js", source).asString();
    }
}
