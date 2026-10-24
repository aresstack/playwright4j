<h1 align="center">
  <img src="docs/assets/banner.png" alt="Playwright4J: Java + GraalJS runtime for Playwright Core" width="100%">
</h1>

<p align="center">
  <a href="https://central.sonatype.com/artifact/com.aresstack/playwright4j"><img src="https://img.shields.io/maven-central/v/com.aresstack/playwright4j.svg" alt="Maven Central"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License: Apache 2.0"></a>
</p>

Playwright4J runs Microsoft's Playwright Core JavaScript driver inside the JVM using
GraalJS. It preserves the official Playwright Java API without requiring a separate
Node.js runtime, native helper executables, or browser downloads.

- **Java 21**, plain JVM dependency, no Node.js installation
- **Chrome and Edge** over the Chrome DevTools Protocol (CDP)
- **Firefox** over WebDriver BiDi, without geckodriver
- **Official Playwright Java API**: your code uses `com.microsoft.playwright.*`, there is
  no Playwright4J-specific API to learn

> **Version note.** The latest published release is `com.aresstack:playwright4j:0.1.0`.
> It supports Chrome and Edge only. Firefox support and the Node-free `launch-server`
> described below are not released yet and will ship with the next release. Until
> then, build from source or use `0.1.0` for Chromium only.

## Quick start

### 1. Add the dependency

Add Playwright4J next to the official Playwright Java API, and exclude Microsoft's
Node-based driver from it. `driver` would shadow Playwright4J's driver implementation,
and `driver-bundle` (about 200 MB) ships `node`/`node.exe` for every platform, which
Playwright4J never runs.

Gradle:

```groovy
dependencies {
    implementation 'com.aresstack:playwright4j:<version>'
    implementation('com.microsoft.playwright:playwright:1.59.0') {
        exclude group: 'com.microsoft.playwright', module: 'driver'
        exclude group: 'com.microsoft.playwright', module: 'driver-bundle'
    }
}
```

Maven:

```xml
<dependency>
  <groupId>com.aresstack</groupId>
  <artifactId>playwright4j</artifactId>
  <version>VERSION</version>
</dependency>
<dependency>
  <groupId>com.microsoft.playwright</groupId>
  <artifactId>playwright</artifactId>
  <version>1.59.0</version>
  <exclusions>
    <exclusion>
      <groupId>com.microsoft.playwright</groupId>
      <artifactId>driver</artifactId>
    </exclusion>
    <exclusion>
      <groupId>com.microsoft.playwright</groupId>
      <artifactId>driver-bundle</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

Keep the Playwright Java API version aligned with the Playwright Core version bundled in
Playwright4J (currently 1.59.0).

### 2. Launch a browser

Playwright4J drives browsers that are already installed on the machine. Chrome:

```java
import com.microsoft.playwright.*;

try (Playwright playwright = Playwright.create()) {
    Browser browser = playwright.chromium().launch(
        new BrowserType.LaunchOptions()
            .setChannel("chrome")   // or "msedge" for Microsoft Edge
            .setHeadless(true));

    Page page = browser.newPage();
    page.navigate("https://example.com");
    System.out.println(page.title());

    browser.close();
}
```

Firefox (next release) uses the `moz-firefox` channel and the path of the installed
Firefox binary:

```java
Browser browser = playwright.firefox().launch(
    new BrowserType.LaunchOptions()
        .setChannel("moz-firefox")
        .setExecutablePath(Paths.get("C:\\Program Files\\Mozilla Firefox\\firefox.exe"))
        .setHeadless(true));
```

Everything after `launch()` is the regular Playwright API.

## Supported browsers

| Browser | Browser type / channel | Protocol | Status |
| --- | --- | --- | --- |
| Google Chrome | `chromium`, channel `chrome` | CDP | Tested (upstream contract suite + CI smoke) |
| Microsoft Edge | `chromium`, channel `msedge` | CDP | Tested (upstream contract suite + CI smoke) |
| Mozilla Firefox | `firefox`, channel `moz-firefox` | WebDriver BiDi | Smoke-tested only, next release |
| WebKit | – | – | Not supported, fails fast with an unsupported-engine error |

"Tested" means Chrome and Edge run against the broad upstream Playwright Java contract
suite. Firefox is covered by a launch/navigate/close smoke test on Windows CI, which is a
release gate, but it has **not** been run against the full contract suite. Expect gaps in
less common Firefox features.

`BrowserType.connect()` to a `launch-server` also works without Node.js (next release):
the server is the same JVM driver started in `--node-compat` mode
(`java ... com.aresstack.playwright4j.driver.GraalDriverMain --node-compat cli.js launch-server ...`).

## How it works

```text
          Your code: official Playwright Java API
                          |
                     Playwright4J
        JVM + GraalJS + Playwright Core (JavaScript)
                 /                      \
               CDP                 WebDriver BiDi
                |                        |
          Chrome / Edge               Firefox
```

- The Playwright Java client talks to its driver as usual. Playwright4J replaces the
  Node.js driver process with Playwright Core running on GraalJS inside the same JVM.
- Node.js and OS facilities that Playwright Core needs (process launch, WebSocket,
  HTTP/HTTPS, filesystem, streams, buffers, crypto, zlib, TLS) are provided by Java host
  adapters.
- Chrome and Edge are driven over CDP. Firefox is driven over WebDriver BiDi using
  Playwright's own BiDi implementation; Firefox exposes the BiDi WebSocket itself.
- The Playwright Core JavaScript package is taken from Microsoft's
  `com.microsoft.playwright:driver-bundle` **at build time** and copied into the jar as
  `driver/package/**`. The Node.js executables in that artifact are dropped, and the
  build fails if a native file ends up in the package. `driver-bundle` is not a runtime
  dependency of Playwright4J.

**About native code.** Playwright4J itself ships no native executables: no Node.js, no
`node.exe` launcher, no geckodriver. The GraalVM dependencies (`org.graalvm.polyglot`,
Truffle) contain GraalVM's own native support libraries, for example `libtruffleattach` in
`truffle-api`, which the Truffle runtime may load on a stock JDK. Those are part of GraalVM,
are not downloaded separately and are not used to control a browser.

## Tests and known limitations

- Chrome/Edge smokes, the Firefox BiDi smoke, `launch-server`/`connect` tests and a
  no-native-binaries check run on CI and gate releases.
- The full upstream contract suite runs as a non-blocking audit with a short, documented
  list of known failures.

Details, the test layers and the historical test report are in
[`docs/testing.md`](docs/testing.md). Known limitations and their analysis are in
[`problems.md`](problems.md).

## Building from source

Requires JDK 21.

```bash
./gradlew build
```

Modules:

| Path | Content |
| --- | --- |
| `playwright4j/` | The library: GraalJS runtime, Java host adapters, repackaged Playwright Core |
| `playwright-java-contract-tests/` | Upstream Playwright Java contract tests and browser smokes |
| `consumer-check/` | Standalone build that consumes the library like a user and checks the classpath |

The upstream Playwright Core version is pinned in `playwright4j/build.gradle`
(`playwrightDriverBundle 'com.microsoft.playwright:driver-bundle:1.59.0'`).

## Publishing

Releases go to Maven Central under `com.aresstack:playwright4j`. See
[`PUBLISHING.md`](PUBLISHING.md).

## License

See [`LICENSE`](LICENSE). Attribution for Playwright and other third-party code is in
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).
