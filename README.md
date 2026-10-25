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

> **Version note.** The current release is `0.2.1`. It fixes Firefox
> `launchPersistentContext()` (persistent profile), which fails in `0.2.0`. The older release
> `0.1.0` supports Chrome and Edge only and does not resolve with Maven (its POM declares
> `org.graalvm.polyglot:js` without `<type>pom</type>`); use `0.2.1` instead.

## Quick start

### 1. Add the dependency

Add Playwright4J next to the official Playwright Java API, and exclude Microsoft's
Node-based driver from it. `driver` would shadow Playwright4J's driver implementation,
and `driver-bundle` (about 200 MB) ships `node`/`node.exe` for every platform, which
Playwright4J never runs.

Gradle:

```groovy
dependencies {
    implementation 'com.aresstack:playwright4j:0.2.1'
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
  <version>0.2.1</version>
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

Firefox (since `0.2.0`) uses the `moz-firefox` channel and the path of the installed
Firefox binary:

```java
import java.nio.file.Paths;

Browser browser = playwright.firefox().launch(
    new BrowserType.LaunchOptions()
        .setChannel("moz-firefox")
        .setExecutablePath(Paths.get("C:\\Program Files\\Mozilla Firefox\\firefox.exe"))
        .setHeadless(true));
```

A persistent profile directory works the same way (since `0.2.1`), headed or headless:

```java
BrowserContext context = playwright.firefox().launchPersistentContext(
    Paths.get("profiles/firefox"),
    new BrowserType.LaunchPersistentContextOptions()
        .setChannel("moz-firefox")
        .setExecutablePath(Paths.get("C:\\Program Files\\Mozilla Firefox\\firefox.exe"))
        .setHeadless(false));
```

Everything after `launch()` / `launchPersistentContext()` is the regular Playwright API.

## Supported browsers

| Browser | Browser type / channel | Protocol | Status |
| --- | --- | --- | --- |
| Google Chrome | `chromium`, channel `chrome` | CDP | Tested (upstream contract suite + CI smoke) |
| Microsoft Edge | `chromium`, channel `msedge` | CDP | Tested (upstream contract suite + CI smoke) |
| Mozilla Firefox | `firefox`, channel `moz-firefox` | WebDriver BiDi | Smoke-tested only (`launch`, `launchPersistentContext`), since `0.2.0` |
| WebKit | – | – | Not supported, fails fast with an unsupported-engine error |

"Tested" means Chrome and Edge run against the broad upstream Playwright Java contract
suite. Firefox is covered by a launch/navigate/close smoke test on Windows CI, which is a
release gate, but it has **not** been run against the full contract suite. Expect gaps in
less common Firefox features.

`BrowserType.connect()` to a `launch-server` also works without Node.js (since `0.2.0`):
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

### Driver JVM

The Playwright Core runs in a separate driver JVM started with the same `java` as the application.
Its classpath is taken from where playwright4j and GraalVM were actually loaded (the class loader
chain and the classes' code sources), not from `java.class.path`, so Spring Boot executable jars,
application servers and custom class loaders work. Jars nested in another jar (Spring Boot
`BOOT-INF/lib/`) are copied once into `<java.io.tmpdir>/playwright4j-driver/classpath/`.

| System property | Effect |
| --- | --- |
| `playwright4j.driver.jvmArgs` | Extra driver JVM options, whitespace separated, `"..."` keeps spaces, e.g. `-Dplaywright4j.driver.jvmArgs="-Xmx1g -Dhttps.proxyHost=proxy"`. Applied last, so they win over forwarded properties. |
| `playwright4j.driver.classpath` | Replaces the detection with an explicit path list, for containers (e.g. OSGi) whose class loaders expose no file locations. |
| `playwright4j.bidi.nativeBehavior` | `true` keeps Firefox's own behaviour where Playwright changes it for testing (Firefox and Camoufox over WebDriver BiDi only; default `false`). See [docs/browser-signals.md](docs/browser-signals.md#native-behavior-opt-in). |
| `playwright4j.bidi.unhandledPromptDelay` | `MIN-MAX` in milliseconds (at most 10000): dialogs no handler takes and HTTP auth challenges without matching credentials get Playwright's usual answer after a random delay in that range (Firefox and Camoufox over WebDriver BiDi only; default off). See [docs/browser-signals.md](docs/browser-signals.md#dialogs-and-http-authentication-without-a-handler-firefox). |
| `playwright4j.bidi.naturalPointer` | `true` moves the mouse along a curved path and gives clicks a press-and-release timing, where the caller sets no `steps` or `delay` (Firefox and Camoufox over WebDriver BiDi only; default `false`). See [docs/browser-signals.md](docs/browser-signals.md#natural-pointer-opt-in). |

These properties of the application JVM are forwarded to the driver JVM when set: the
`javax.net.ssl.trustStore*`/`keyStore*` settings, `jdk.tls.client.protocols`, `https.protocols`,
`http(s).proxyHost`/`Port`, `http.nonProxyHosts`, `socksProxy*`, `java.net.useSystemProxies`,
`jdk.http.auth.*.disabledSchemes`, `playwright4j.video.vlc.path`, `playwright4j.bidi.nativeBehavior`,
`playwright4j.bidi.unhandledPromptDelay`, `playwright4j.bidi.naturalPointer` and the `playwright4j.debug*` settings. Without `jdk.http.auth.tunneling.disabledSchemes` the driver
allows Basic proxy credentials on `CONNECT`, which `APIRequestContext` needs with an authenticating proxy. Store passwords are passed in an owner-only temporary file that the driver deletes at
startup, never on the command line.

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
