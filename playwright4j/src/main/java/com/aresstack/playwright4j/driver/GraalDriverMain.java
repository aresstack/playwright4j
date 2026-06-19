package com.aresstack.playwright4j.driver;

import com.aresstack.playwright4j.driver.browser.LocalChromiumExecutableProvider;
import com.aresstack.playwright4j.driver.browser.Playwright4JBrowserSettings;
import com.aresstack.playwright4j.graal.*;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GraalDriverMain {

    private GraalDriverMain() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> environment = new LinkedHashMap<String, String>(System.getenv());
        environment.putIfAbsent("PW_LANG_NAME", "java");

        PlaywrightDriverBundleSource driverBundleSource = new PlaywrightDriverBundleSource(Thread.currentThread().getContextClassLoader());
        Playwright4JBrowserSettings browserSettings = Playwright4JBrowserSettings.fromSystemPropertiesAndEnvironment(environment);
        JdkHostProcessLauncher processLauncher = new JdkHostProcessLauncher();
        Playwright4JHost host = new Playwright4JHost(
                new FixedHostEnvironment(platform(), architecture(), Paths.get("").toAbsolutePath().toString(), environment),
                new LocalHostFileSystem(),
                new JdkHostHttpClient(),
                new JdkHostWebSocketClient(),
                new StandardIoDriverPipe(System.out, System.err),
                new ResolvedHostBrowserConfiguration(new LocalChromiumExecutableProvider(browserSettings, environment)),
                processLauncher,
                new RecordingMissingHostFunctionReporter());

        try (GraalPlaywrightRuntime runtime = new GraalPlaywrightRuntime(host, driverBundleSource)) {
            runtime.loadNodeCompatibilityLayer();
            runtime.setProcessArguments(processArguments(driverBundleSource, args));
            runtime.evaluateCommonJsEntry(driverBundleSource.cliScriptResourceName(), driverBundleSource.readCliScript());
            pumpInput(runtime);
        } finally {
            processLauncher.closeAll();
        }
    }

    private static String[] processArguments(PlaywrightDriverBundleSource driverBundleSource, String[] args) {
        String[] processArguments = new String[args.length + 2];
        processArguments[0] = "node";
        processArguments[1] = driverBundleSource.cliScriptResourceName();
        System.arraycopy(args, 0, processArguments, 2, args.length);
        return processArguments;
    }

    private static void pumpInput(GraalPlaywrightRuntime runtime) throws IOException {
        DataInputStream input = new DataInputStream(new BufferedInputStream(System.in));

        while (true) {
            try {
                String message = readLengthPrefixedMessage(input);
                System.err.println("[pw4j-protocol] IN " + (message.length() <= 300 ? message : message.substring(0, 300) + "..."));
                runtime.readGlobal("__playwright4jDriverPipeDeliver").execute(message);
                runtime.drainTransports();
            } catch (EOFException exception) {
                return;
            }
        }
    }

    private static String readLengthPrefixedMessage(DataInputStream input) throws IOException {
        int length = readIntLE(input);
        byte[] bytes = new byte[length];
        input.readFully(bytes, 0, length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readIntLE(DataInputStream input) throws IOException {
        int ch1 = input.read();
        int ch2 = input.read();
        int ch3 = input.read();
        int ch4 = input.read();

        if ((ch1 | ch2 | ch3 | ch4) < 0) {
            throw new EOFException();
        }

        return (ch4 << 24) + (ch3 << 16) + (ch2 << 8) + ch1;
    }

    private static String platform() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            return "win32";
        }
        if (osName.contains("mac")) {
            return "darwin";
        }
        return "linux";
    }

    private static String architecture() {
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        return osArch.contains("aarch64") || osArch.contains("arm64") ? "arm64" : "x64";
    }
}
