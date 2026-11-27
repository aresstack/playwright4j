package com.aresstack.playwright4j.bidi;

import com.google.gson.JsonObject;
import com.microsoft.playwright.Page;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Add-ons in a Firefox that Playwright drives over WebDriver BiDi ({@code webExtension.install}).
 * Firefox installs the add-on as a temporary one: it need not be signed, it runs in every tab of the
 * browser, and it is gone when the browser ends, so an application installs it after each start.
 *
 * <p>The command goes over Playwright's own BiDi session, the only one Firefox accepts, like
 * {@link FirefoxBidi}; it runs no script in a page and leaves Playwright's pages and contexts alone.
 * Use it from the thread that uses the page. Chromium has no WebDriver BiDi session in Playwright:
 * there these methods throw {@link UnsupportedOperationException}; load an extension there with the
 * {@code --load-extension} launch argument instead.
 */
public final class FirefoxExtensions {

    private FirefoxExtensions() {
    }

    /**
     * Installs the add-on at {@code location} as a temporary add-on of the browser that shows
     * {@code page}: an unpacked add-on folder (with its {@code manifest.json}) or a packed
     * {@code .xpi}/{@code .zip} file. Answers the add-on id Firefox assigned, for {@link #uninstall}.
     */
    public static String install(Page page, Path location) {
        Path path = location.toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("No add-on at " + path);
        }
        JsonObject data = new JsonObject();
        data.addProperty("type", Files.isDirectory(path) ? "path" : "archivePath");
        data.addProperty("path", path.toString());
        JsonObject params = new JsonObject();
        params.add("extensionData", data);
        try (FirefoxBidi bidi = FirefoxBidi.open(page)) {
            return bidi.send("webExtension.install", params).get("extension").getAsString();
        }
    }

    /** Removes the add-on {@code extensionId} that {@link #install} answered. */
    public static void uninstall(Page page, String extensionId) {
        JsonObject params = new JsonObject();
        params.addProperty("extension", extensionId);
        try (FirefoxBidi bidi = FirefoxBidi.open(page)) {
            bidi.send("webExtension.uninstall", params);
        }
    }
}
