package com.aresstack.playwright4j.signals;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Collects the automation signals a web page can observe from a Playwright-launched browser, so
 * that two runtimes (playwright4j's GraalJS driver vs. the official Node.js driver, or Firefox
 * over BiDi vs. another build) can be compared with a plain {@code diff} of their reports.
 *
 * <p>The probe serves a neutral page from a local HTTP/1.1 server on 127.0.0.1. It records every
 * request head exactly as sent (header order and case preserved) and lets an ordinary page
 * script report what it sees (navigator.webdriver, UA, injected globals, ...). Nothing is read
 * through page.evaluate, so the values are what any site's own script would observe. It also
 * records the browser's real command line, selected environment variables and, for Firefox, the
 * user.js Playwright wrote into the profile.
 *
 * <p>It only depends on the public Playwright Java API, Gson and the JDK, so the same source can
 * be compiled against the official {@code com.microsoft.playwright:playwright} with its Node
 * driver for a reference run (outside this project). Configuration via environment variables:
 * <ul>
 *   <li>{@code PROBE_BROWSER}: firefox | chromium (default firefox)</li>
 *   <li>{@code PROBE_CHANNEL}: e.g. moz-firefox, chrome, msedge (default moz-firefox for firefox);
 *   {@code none} uses Playwright's own browser build (official driver only)</li>
 *   <li>{@code PROBE_EXECUTABLE}: browser binary (default: FIREFOX_EXECUTABLE for firefox)</li>
 *   <li>{@code PROBE_HEADLESS}: true | false (default false, like GPT Desktop)</li>
 *   <li>{@code PROBE_PERSISTENT}: true | false (default true, like GPT Desktop)</li>
 *   <li>{@code PROBE_PLAIN}: true starts the binary directly, without Playwright (baseline)</li>
 *   <li>{@code PROBE_FIREFOX_PREFS}: extra prefs, {@code name=value,...}</li>
 *   <li>{@code PROBE_ARGS} / {@code PROBE_IGNORE_DEFAULT_ARGS}: comma-separated browser arguments</li>
 *   <li>{@code PROBE_NO_VIEWPORT}: true disables Playwright's default 1280x720 viewport emulation</li>
 *   <li>{@code PROBE_EXPOSE_BINDING}: name of a page.exposeBinding to install, as an app would</li>
 *   <li>{@code PROBE_LABEL}: name of the run in the report</li>
 *   <li>{@code PROBE_OUT}: report file to write</li>
 * </ul>
 */
public final class BrowserSignalsProbe {
    private static final Gson GSON = new Gson();

    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final CompletableFuture<String> pageReport = new CompletableFuture<>();

    public static void main(String[] args) throws Exception {
        String report = new BrowserSignalsProbe().run(Config.fromEnvironment());
        System.out.println(report);
    }

    /** Runs the probe once and returns the report; also writes it to {@code config.out} if set. */
    public String run(Config config) throws Exception {
        Map<String, String> signals = new TreeMap<>();
        StringBuilder details = new StringBuilder();
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> serve(server), "signals-probe-server");
            acceptor.setDaemon(true);
            acceptor.start();
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/";

            long launchedAfter = System.currentTimeMillis() - 1000;
            Path userDataDir = config.persistent || config.plain ? Files.createTempDirectory("signals-probe-profile") : null;
            try {
                if (config.plain) {
                    runPlainBrowser(config, userDataDir, url, launchedAfter, signals, details);
                } else {
                    runWithPlaywright(config, userDataDir, url, launchedAfter, signals, details);
                }
            } finally {
                if (userDataDir != null) {
                    Path userJs = userDataDir.resolve("user.js");
                    if (Files.isRegularFile(userJs)) {
                        details.append("\n## user.js written into the profile\n\n")
                                .append(new String(Files.readAllBytes(userJs), StandardCharsets.UTF_8)).append('\n');
                    }
                    deleteQuietly(userDataDir);
                }
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("# Browser signals: ").append(config.label).append("\n\n");
        report.append("config: browser=").append(config.browser).append(" channel=").append(config.channel)
                .append(" headless=").append(config.headless).append(" persistent=").append(config.persistent)
                .append(" plain=").append(config.plain).append(" executable=").append(config.executable)
                .append(" firefoxPrefs=").append(config.firefoxPrefs).append(" args=").append(config.args)
                .append(" ignoreDefaultArgs=").append(config.ignoreDefaultArgs).append(" noViewport=").append(config.noViewport)
                .append(" exposeBinding=").append(config.exposeBinding).append("\n");
        report.append("collected: ").append(Instant.now()).append("\n\n## Signals\n\n");
        signals.forEach((key, value) -> report.append(key).append(" = ").append(value).append('\n'));
        report.append("\n## Requests (header order and case as sent)\n");
        synchronized (requests) {
            for (String request : requests) {
                report.append('\n').append(request);
            }
        }
        report.append(details);
        String text = report.toString();
        if (config.out != null) {
            Path out = Paths.get(config.out);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.write(out, text.getBytes(StandardCharsets.UTF_8));
        }
        return text;
    }

    private void runWithPlaywright(Config config, Path userDataDir, String url, long launchedAfter,
                                   Map<String, String> signals, StringBuilder details) throws Exception {
        try (Playwright playwright = Playwright.create()) {
            BrowserType type = "chromium".equals(config.browser) ? playwright.chromium() : playwright.firefox();
            BrowserContext context;
            Browser browser = null;
            if (config.persistent) {
                BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                        .setHeadless(config.headless);
                if (!config.firefoxPrefs.isEmpty()) {
                    options.setFirefoxUserPrefs(config.firefoxPrefs);
                }
                if (config.noViewport) {
                    options.setViewportSize(null);
                }
                if (!config.ignoreDefaultArgs.isEmpty()) {
                    options.setIgnoreDefaultArgs(config.ignoreDefaultArgs);
                }
                if (!config.args.isEmpty()) {
                    options.setArgs(config.args);
                }
                if (config.channel != null) {
                    options.setChannel(config.channel);
                }
                if (config.executable != null) {
                    options.setExecutablePath(Paths.get(config.executable));
                }
                context = type.launchPersistentContext(userDataDir, options);
            } else {
                BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(config.headless);
                if (!config.firefoxPrefs.isEmpty()) {
                    options.setFirefoxUserPrefs(config.firefoxPrefs);
                }
                if (!config.ignoreDefaultArgs.isEmpty()) {
                    options.setIgnoreDefaultArgs(config.ignoreDefaultArgs);
                }
                if (!config.args.isEmpty()) {
                    options.setArgs(config.args);
                }
                if (config.channel != null) {
                    options.setChannel(config.channel);
                }
                if (config.executable != null) {
                    options.setExecutablePath(Paths.get(config.executable));
                }
                browser = type.launch(options);
                context = browser.newContext(config.noViewport
                        ? new Browser.NewContextOptions().setViewportSize(null) : new Browser.NewContextOptions());
            }
            try {
                signals.put("java.browserVersion", browser != null ? browser.version()
                        : context.browser() != null ? context.browser().version() : "?");
                describeBrowserProcess(config.browser, launchedAfter, signals, details);

                Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
                if (config.exposeBinding != null) {
                    page.exposeBinding(config.exposeBinding, (source, arguments) -> null);
                }
                page.navigate(url);
                putPageSignals(pageReport.get(30, TimeUnit.SECONDS), signals);
                // Let the subresource and favicon requests arrive before the report is cut.
                Thread.sleep(1000);
            } finally {
                context.close();
                if (browser != null) {
                    browser.close();
                }
            }
        }
    }

    // Baseline without any automation: the browser binary started directly with a fresh profile
    // and the probe URL on its command line, as a user would open it.
    private void runPlainBrowser(Config config, Path userDataDir, String url, long launchedAfter,
                                 Map<String, String> signals, StringBuilder details) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(config.executable);
        if ("chromium".equals(config.browser)) {
            command.add("--user-data-dir=" + userDataDir);
            command.add("--no-first-run");
            command.add("--no-default-browser-check");
        } else {
            command.add("-no-remote");
            command.add("-profile");
            command.add(userDataDir.toString());
        }
        command.addAll(config.args);
        command.add(url);
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            String json = pageReport.get(60, TimeUnit.SECONDS);
            describeBrowserProcess(config.browser, launchedAfter, signals, details);
            putPageSignals(json, signals);
            Thread.sleep(1000);
        } finally {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void putPageSignals(String json, Map<String, String> signals) {
        JsonObject reported = GSON.fromJson(json, JsonObject.class);
        for (Map.Entry<String, JsonElement> entry : reported.entrySet()) {
            JsonElement value = entry.getValue();
            signals.put("page." + entry.getKey(), value.isJsonPrimitive() ? value.getAsString() : value.toString());
        }
    }

    // The newest browser main process started after the launch: its real command line shows the
    // arguments the driver (and the playwright4j runtime) passed, its environment on Linux the
    // variables Playwright meant to set (MOZ_CRASHREPORTER, ...).
    private static void describeBrowserProcess(String browser, long launchedAfter, Map<String, String> signals,
                                               StringBuilder details) {
        // The earliest started match is the main (or launcher) process. On Windows the JDK reports no
        // arguments for other processes, so a process without arguments is kept, not dropped.
        List<ProcessHandle> candidates = ProcessHandle.allProcesses()
                .filter(p -> p.info().startInstant().map(i -> i.toEpochMilli() >= launchedAfter).orElse(false))
                .filter(p -> p.info().command().map(c -> isBrowserBinary(browser, c)).orElse(false))
                .filter(p -> p.info().arguments().map(a -> isMainProcess(browser, a)).orElse(true))
                .sorted(Comparator.comparing(p -> p.info().startInstant().orElse(Instant.MAX)))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            signals.put("process.found", "false");
            return;
        }
        ProcessHandle main = candidates.get(0);
        signals.put("process.found", "true");
        details.append("\n## Browser command line\n\n").append(main.info().command().orElse("?")).append('\n');
        if (!main.info().arguments().isPresent()) {
            details.append("  (arguments are not available on this OS)\n");
        }
        for (String argument : main.info().arguments().orElse(new String[0])) {
            details.append("  ").append(argument).append('\n');
        }
        Path environ = Paths.get("/proc", String.valueOf(main.pid()), "environ");
        if (Files.isReadable(environ)) {
            try {
                String[] variables = new String(Files.readAllBytes(environ), StandardCharsets.UTF_8).split("\0");
                List<String> relevant = new ArrayList<>();
                for (String variable : variables) {
                    if (variable.startsWith("MOZ_") || variable.startsWith("SNAP") || variable.startsWith("PW_")
                            || variable.startsWith("PLAYWRIGHT") || variable.startsWith("GOOGLE_")) {
                        relevant.add(variable);
                    }
                }
                Collections.sort(relevant);
                signals.put("process.env", relevant.isEmpty() ? "(none)" : String.join(" ", relevant));
            } catch (IOException ignored) {
                signals.put("process.env", "(unreadable)");
            }
        }
    }

    private static boolean isBrowserBinary(String browser, String command) {
        String name = Paths.get(command).getFileName().toString().toLowerCase(Locale.ROOT);
        if ("chromium".equals(browser)) {
            return !name.contains("crashpad")
                    && (name.startsWith("chrome") || name.startsWith("msedge") || name.startsWith("chromium"));
        }
        return name.equals("firefox") || name.equals("firefox.exe") || name.equals("firefox-bin");
    }

    private static boolean isMainProcess(String browser, String[] arguments) {
        for (String argument : arguments) {
            if ("chromium".equals(browser) ? argument.startsWith("--type=") : argument.equals("-contentproc")) {
                return false;
            }
        }
        return true;
    }

    private void serve(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread handler = new Thread(() -> handle(socket), "signals-probe-request");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            while (true) {
                String head = readHead(in);
                if (head == null) {
                    return;
                }
                requests.add(head);
                String[] lines = head.split("\r\n");
                String[] requestLine = lines[0].split(" ");
                String method = requestLine[0];
                String path = requestLine.length > 1 ? requestLine[1] : "/";
                int contentLength = 0;
                for (String line : lines) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }
                byte[] body = readFully(in, contentLength);
                if ("POST".equals(method) && path.startsWith("/report")) {
                    pageReport.complete(new String(body, StandardCharsets.UTF_8));
                    respond(out, "204 No Content", "text/plain", new byte[0]);
                } else if (path.equals("/")) {
                    respond(out, "200 OK", "text/html; charset=utf-8", PAGE.getBytes(StandardCharsets.UTF_8));
                } else if (path.startsWith("/style.css")) {
                    respond(out, "200 OK", "text/css", "body{font-family:sans-serif}".getBytes(StandardCharsets.UTF_8));
                } else if (path.startsWith("/fetch.json")) {
                    respond(out, "200 OK", "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
                } else {
                    respond(out, "404 Not Found", "text/plain", new byte[0]);
                }
            }
        } catch (IOException ignored) {
            // Connection closed by the browser.
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3))
                    ? matched + 1 : 0;
            if (matched == 4) {
                return head.toString("ISO-8859-1");
            }
        }
        return null;
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        return body;
    }

    private static void respond(OutputStream out, String status, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    // Best effort: a browser that is still shutting down may hold or remove profile files, which
    // must not cost the finished report.
    private static void deleteQuietly(Path dir) {
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | java.io.UncheckedIOException ignored) {
            // Left in the temp directory.
        }
    }

    /** Probe settings; see the class comment for the environment variables. */
    public static final class Config {
        public String browser = "firefox";
        public String channel;
        public String executable;
        public boolean headless;
        public boolean persistent = true;
        public boolean plain;
        public boolean noViewport;
        public String exposeBinding;
        public Map<String, Object> firefoxPrefs = new TreeMap<>();
        public List<String> args = new ArrayList<>();
        public List<String> ignoreDefaultArgs = new ArrayList<>();
        public String label = "unnamed";
        public String out;

        public static Config fromEnvironment() {
            Config config = new Config();
            config.browser = env("PROBE_BROWSER", "firefox");
            config.channel = env("PROBE_CHANNEL", "firefox".equals(config.browser) ? "moz-firefox" : null);
            if ("none".equals(config.channel)) {
                // Playwright's own browser build, e.g. its Juggler Firefox, from the Playwright cache.
                config.channel = null;
            }
            config.executable = env("PROBE_EXECUTABLE", "firefox".equals(config.browser) ? env("FIREFOX_EXECUTABLE", null) : null);
            config.headless = Boolean.parseBoolean(env("PROBE_HEADLESS", "false"));
            config.persistent = Boolean.parseBoolean(env("PROBE_PERSISTENT", "true"));
            config.plain = Boolean.parseBoolean(env("PROBE_PLAIN", "false"));
            config.noViewport = Boolean.parseBoolean(env("PROBE_NO_VIEWPORT", "false"));
            config.exposeBinding = env("PROBE_EXPOSE_BINDING", null);
            for (String pref : list(env("PROBE_FIREFOX_PREFS", null))) {
                String[] keyValue = pref.split("=", 2);
                String value = keyValue.length > 1 ? keyValue[1] : "true";
                config.firefoxPrefs.put(keyValue[0], "true".equals(value) || "false".equals(value)
                        ? (Object) Boolean.valueOf(value) : value.matches("-?\\d+") ? (Object) Integer.valueOf(value) : value);
            }
            config.args = list(env("PROBE_ARGS", null));
            config.ignoreDefaultArgs = list(env("PROBE_IGNORE_DEFAULT_ARGS", null));
            config.label = env("PROBE_LABEL", config.browser + "-" + (config.plain ? "plain" : config.channel));
            config.out = env("PROBE_OUT", null);
            return config;
        }

        private static List<String> list(String value) {
            List<String> result = new ArrayList<>();
            if (value != null) {
                for (String item : value.split(",")) {
                    if (!item.trim().isEmpty()) {
                        result.add(item.trim());
                    }
                }
            }
            return result;
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.trim().isEmpty() ? fallback : value.trim();
        }
    }

    // A plain page whose own script reports what it can observe; the stylesheet and the fetch
    // add a subresource and a CORS-mode request to the header capture.
    private static final String PAGE = "<!doctype html><html><head><meta charset=utf-8><title>signals</title>"
            + "<link rel=stylesheet href=/style.css></head><body><h1>signals</h1><script>\n"
            + "(async () => {\n"
            + "  const r = {};\n"
            + "  const safe = (name, fn) => { try { const v = fn(); r[name] = v === undefined ? 'undefined' : v; }"
            + " catch (e) { r[name] = 'error: ' + e; } };\n"
            + "  const n = navigator;\n"
            + "  safe('navigator.webdriver', () => String(n.webdriver));\n"
            + "  safe('navigator.webdriver.in', () => String('webdriver' in n));\n"
            + "  safe('navigator.webdriver.getter', () => {"
            + " const d = Object.getOwnPropertyDescriptor(Navigator.prototype, 'webdriver');"
            + " return d ? Function.prototype.toString.call(d.get) : 'no descriptor'; });\n"
            + "  safe('navigator.userAgent', () => n.userAgent);\n"
            + "  safe('navigator.appVersion', () => n.appVersion);\n"
            + "  safe('navigator.platform', () => n.platform);\n"
            + "  safe('navigator.oscpu', () => String(n.oscpu));\n"
            + "  safe('navigator.vendor', () => n.vendor);\n"
            + "  safe('navigator.buildID', () => String(n.buildID));\n"
            + "  safe('navigator.language', () => n.language);\n"
            + "  safe('navigator.languages', () => JSON.stringify(n.languages));\n"
            + "  safe('navigator.hardwareConcurrency', () => String(n.hardwareConcurrency));\n"
            + "  safe('navigator.deviceMemory', () => String(n.deviceMemory));\n"
            + "  safe('navigator.maxTouchPoints', () => String(n.maxTouchPoints));\n"
            + "  safe('navigator.cookieEnabled', () => String(n.cookieEnabled));\n"
            + "  safe('navigator.doNotTrack', () => String(n.doNotTrack));\n"
            + "  safe('navigator.globalPrivacyControl', () => String(n.globalPrivacyControl));\n"
            + "  safe('navigator.pdfViewerEnabled', () => String(n.pdfViewerEnabled));\n"
            + "  safe('navigator.plugins', () => Array.from(n.plugins || []).map(p => p.name).join(', ') || '(none)');\n"
            + "  safe('navigator.mimeTypes.length', () => String((n.mimeTypes || []).length));\n"
            + "  safe('navigator.userAgentData', () => n.userAgentData ? JSON.stringify({brands: n.userAgentData.brands,"
            + " mobile: n.userAgentData.mobile, platform: n.userAgentData.platform}) : 'undefined');\n"
            + "  safe('window.chrome', () => typeof window.chrome === 'undefined' ? 'undefined'"
            + " : JSON.stringify(Object.keys(window.chrome)));\n"
            + "  safe('screen', () => [screen.width, screen.height, screen.availWidth, screen.availHeight,"
            + " screen.colorDepth].join('x'));\n"
            + "  safe('window.devicePixelRatio', () => String(devicePixelRatio));\n"
            + "  safe('window.inner', () => innerWidth + 'x' + innerHeight);\n"
            + "  safe('window.outer', () => outerWidth + 'x' + outerHeight);\n"
            + "  safe('window.screenXY', () => screenX + ',' + screenY);\n"
            + "  safe('intl.timeZone', () => Intl.DateTimeFormat().resolvedOptions().timeZone);\n"
            + "  safe('intl.locale', () => Intl.DateTimeFormat().resolvedOptions().locale);\n"
            + "  safe('date.offset', () => String(new Date().getTimezoneOffset()));\n"
            + "  safe('notification.permission', () => typeof Notification === 'undefined' ? 'undefined'"
            + " : Notification.permission);\n"
            + "  try { r['permissions.notifications'] = (await n.permissions.query({name: 'notifications'})).state; }"
            + " catch (e) { r['permissions.notifications'] = 'error: ' + e; }\n"
            + "  safe('document.hasFocus', () => String(document.hasFocus()));\n"
            + "  safe('document.visibilityState', () => document.visibilityState);\n"
            + "  safe('media.colorScheme', () => matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');\n"
            + "  safe('media.reducedMotion', () => String(matchMedia('(prefers-reduced-motion: reduce)').matches));\n"
            + "  safe('media.pointerFine', () => String(matchMedia('(pointer: fine)').matches));\n"
            + "  safe('webgl', () => { const gl = document.createElement('canvas').getContext('webgl');"
            + " if (!gl) return 'unavailable'; const ext = gl.getExtension('WEBGL_debug_renderer_info');"
            + " return ext ? gl.getParameter(ext.UNMASKED_VENDOR_WEBGL) + ' | ' + gl.getParameter(ext.UNMASKED_RENDERER_WEBGL)"
            + " : gl.getParameter(gl.VENDOR) + ' | ' + gl.getParameter(gl.RENDERER); });\n"
            + "  safe('history.length', () => String(history.length));\n"
            + "  safe('window.opener', () => String(window.opener !== null));\n"
            + "  safe('error.stackSample', () => (new Error('x').stack || '').split('\\n').slice(0, 2).join(' / '));\n"
            // Globals in the main frame that a fresh same-origin iframe does not have. Playwright installs
            // bindings and init scripts into every frame, so those show up under suspiciousNames instead.
            + "  safe('window.mainFrameOnlyGlobals', () => { const f = document.createElement('iframe');"
            + " document.body.appendChild(f); const base = new Set(Object.getOwnPropertyNames(f.contentWindow));"
            + " const extra = Object.getOwnPropertyNames(window).filter(k => !base.has(k) && !/^\\d+$/.test(k));"
            + " f.remove(); return extra.join(', ') || '(none)'; });\n"
            // Test-only interfaces a pref can expose to every page (dom.testing.testutils.enabled
            // exposes TestUtils, which Playwright's BiDi Firefox profile turns on for requestGC()).
            + "  safe('window.testInterfaces', () => ['TestUtils', 'SpecialPowers', 'Components', 'netscape']"
            + ".map(k => k + ':' + typeof window[k]).join(', '));\n"
            + "  safe('window.suspiciousNames',() => Object.getOwnPropertyNames(window).concat(Object.getOwnPropertyNames(document))"
            + ".filter(k => /playwright|__pw|cdc_|webdriver|selenium|driver|juggler|bidi|binding|marionette/i.test(k))"
            + ".join(', ') || '(none)');\n"
            + "  try { await fetch('/fetch.json', {headers: {'X-Probe': '1'}}); r['fetch'] = 'ok'; }"
            + " catch (e) { r['fetch'] = 'error: ' + e; }\n"
            + "  await fetch('/report', {method: 'POST', headers: {'Content-Type': 'application/json'},"
            + " body: JSON.stringify(r)});\n"
            + "})();\n"
            + "</script></body></html>";
}
