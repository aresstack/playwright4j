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

    public Value evaluateCommonJsEntry(String sourceName, String script) {
        String normalizedScript = removeHashbang(script);
        String wrapper = "(function(entryScript) {"
                + "var module = { exports: {} };"
                + "var require = globalThis.__playwright4jCreateRequire(" + quoteJavaScriptString(sourceName) + ");"
                + "var factory = new Function('require', 'module', 'exports', '__filename', '__dirname', entryScript);"
                + "factory(require, module, module.exports, " + quoteJavaScriptString(sourceName) + ", globalThis.__playwright4jDirname(" + quoteJavaScriptString(sourceName) + "));"
                + "return module.exports;"
                + "})(" + quoteJavaScriptString(normalizedScript) + ");";

        return evaluate(sourceName + "#commonjs-entry", wrapper);
    }

    public Value readGlobal(String name) {
        return context.getBindings(LANGUAGE_ID).getMember(name);
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
