package com.aresstack.playwright4j.graal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

public final class GraalPlaywrightRuntime implements AutoCloseable {

    private static final String LANGUAGE_ID = "js";
    private static final String RUNTIME_RESOURCE_ROOT = "/com/aresstack/playwright4j/runtime/";

    private final Context context;

    public GraalPlaywrightRuntime(Playwright4JHost host) {
        this(host, null);
    }

    public GraalPlaywrightRuntime(Playwright4JHost host, PlaywrightDriverBundleSource driverBundleSource) {
        this.context = Context.newBuilder(LANGUAGE_ID)
                .allowHostAccess(createHostAccess())
                .allowHostClassLookup(new DenyAllHostClassLookup())
                .allowIO(false)
                // System.out is the length-prefixed driver protocol channel to the Playwright
                // Java client. Any stray JS output (console.log, print, ...) must not land
                // there, so route the guest's stdout/stderr to the process stderr instead.
                .out(System.err)
                .err(System.err)
                .option("js.ecmascript-version", "latest")
                .build();

        this.context.getBindings(LANGUAGE_ID).putMember("__playwright4jHost", host);

        if (driverBundleSource != null) {
            this.context.getBindings(LANGUAGE_ID).putMember("__playwright4jDriverBundleSource", driverBundleSource);
        }
    }

    public void loadNodeCompatibilityLayer() {
        evaluateRuntimeResource("node-compat-bootstrap.js");
    }

    public Value evaluate(String sourceName, String script) {
        try {
            Source source = Source.newBuilder(LANGUAGE_ID, script, sourceName).build();
            return context.eval(source);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not evaluate JavaScript source: " + sourceName, exception);
        }
    }

    public void setProcessArguments(String... arguments) {
        StringBuilder script = new StringBuilder("process.argv = [");

        for (int index = 0; index < arguments.length; index++) {
            if (index > 0) {
                script.append(", ");
            }

            script.append(quoteJavaScriptString(arguments[index]));
        }

        script.append("];");
        evaluate("playwright4j-process-argv.js", script.toString());
    }

    public Value evaluateCommonJsEntryAsGlobal(String globalName, String sourceName, String script) {
        String normalizedScript = removeHashbang(script);
        String wrapper = "globalThis[" + quoteJavaScriptString(globalName) + "] = "
                + commonJsEntryExpression(sourceName, normalizedScript) + ";"
                + "globalThis[" + quoteJavaScriptString(globalName) + "];";

        return evaluate(sourceName + "#commonjs-global", wrapper);
    }

    public Value evaluateCommonJsEntry(String sourceName, String script) {
        String normalizedScript = removeHashbang(script);
        return evaluate(sourceName + "#commonjs-entry", commonJsEntryExpression(sourceName, normalizedScript));
    }

    private String commonJsEntryExpression(String sourceName, String normalizedScript) {
        return "(function(entryScript) {"
                + "var module = { exports: {} };"
                + "var require = globalThis.__playwright4jCreateRequire(" + quoteJavaScriptString(sourceName) + ");"
                + "var factory = new Function('require', 'module', 'exports', '__filename', '__dirname', entryScript);"
                + "factory(require, module, module.exports, " + quoteJavaScriptString(sourceName) + ", globalThis.__playwright4jDirname(" + quoteJavaScriptString(sourceName) + "));"
                + "return module.exports;"
                + "})(" + quoteJavaScriptString(normalizedScript) + ")";
    }

    public Value readGlobal(String name) {
        return context.getBindings(LANGUAGE_ID).getMember(name);
    }

    /**
     * Drains all host-backed transports (browser CDP pipes and WebSocket transports) once,
     * delivering any pending messages into the JS event loop. Returns the number of raw
     * messages that were pulled so the caller can detect progress.
     */
    public int drainTransports() {
        int drained = 0;
        drained += executeGlobalIntFunctionIfPresent("__playwright4jDrainBrowserPipes");
        drained += executeGlobalIntFunctionIfPresent("__playwright4jDrainTransports");
        drained += executeGlobalIntFunctionIfPresent("__playwright4jDrainHttp");
        return drained;
    }

    /**
     * Fires every JS timer whose deadline has elapsed. Returns the number fired.
     */
    public int runDueTimers() {
        return executeGlobalIntFunctionIfPresent("__playwright4jRunDueTimers");
    }

    /**
     * Whether any delayed JS timer is still scheduled (and therefore some operation may be
     * waiting on it).
     */
    public boolean hasPendingTimers() {
        Value value = readGlobal("__playwright4jHasPendingTimers");
        if (value != null && value.canExecute()) {
            Value result = value.execute();
            return result != null && result.isBoolean() && result.asBoolean();
        }
        return false;
    }

    private int executeGlobalIntFunctionIfPresent(String name) {
        Value value = readGlobal(name);
        if (value != null && value.canExecute()) {
            Value result = value.execute();
            if (result != null && result.fitsInInt()) {
                return result.asInt();
            }
        }
        return 0;
    }

    private void evaluateRuntimeResource(String resourceName) {
        evaluate(resourceName, readRuntimeResource(resourceName));
    }

    private String removeHashbang(String script) {
        if (script.startsWith("#!")) {
            int lineBreakIndex = script.indexOf('\n');

            if (lineBreakIndex >= 0) {
                return script.substring(lineBreakIndex + 1);
            }

            return "";
        }

        return script;
    }

    private String quoteJavaScriptString(String value) {
        StringBuilder builder = new StringBuilder();
        builder.append('\"');

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);

            switch (character) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\"':
                    builder.append("\\\"");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    builder.append(character);
                    break;
            }
        }

        builder.append('\"');
        return builder.toString();
    }

    private String readRuntimeResource(String resourceName) {
        String fullResourceName = RUNTIME_RESOURCE_ROOT + resourceName;

        try (InputStream inputStream = GraalPlaywrightRuntime.class.getResourceAsStream(fullResourceName)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("Missing runtime resource: " + fullResourceName);
            }

            return new String(readAllBytes(inputStream), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read runtime resource: " + fullResourceName, exception);
        }
    }

    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int readBytes;

        while ((readBytes = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, readBytes);
        }

        return outputStream.toByteArray();
    }

    private HostAccess createHostAccess() {
        return HostAccess.newBuilder()
                .allowAccessAnnotatedBy(HostAccess.Export.class)
                .build();
    }

    @Override
    public void close() {
        context.close();
    }
}
