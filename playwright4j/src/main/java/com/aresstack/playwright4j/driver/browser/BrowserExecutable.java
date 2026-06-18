package com.aresstack.playwright4j.driver.browser;

public final class BrowserExecutable {

    private final String browserName;
    private final String executablePath;

    public BrowserExecutable(String browserName, String executablePath) {
        this.browserName = browserName;
        this.executablePath = executablePath;
    }

    public String browserName() {
        return browserName;
    }

    public String executablePath() {
        return executablePath;
    }
}
