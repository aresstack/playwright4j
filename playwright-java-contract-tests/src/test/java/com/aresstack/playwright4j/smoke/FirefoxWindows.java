package com.aresstack.playwright4j.smoke;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Reads the visible top-level Firefox browser windows from the operating system, so a test can
 * count real windows instead of trusting {@code context.pages()}. Windows: EnumWindows through
 * PowerShell (class MozillaWindowClass, visible, not owned). Linux/X11: xdotool (visible windows
 * of WM_CLASS firefox). Each window carries the id of the process that owns it.
 */
final class FirefoxWindows {
    static final class Window {
        final long pid;
        final String title;

        Window(long pid, String title) {
            this.pid = pid;
            this.title = title;
        }

        @Override
        public String toString() {
            return "pid " + pid + " \"" + title + "\"";
        }
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    // C# compiled in memory by PowerShell's Add-Type; nothing is written to the project or disk.
    private static final String ENUM_WINDOWS_SCRIPT = String.join("\n",
            "$ErrorActionPreference = 'Stop'",
            "Add-Type -TypeDefinition @'",
            "using System;",
            "using System.Collections.Generic;",
            "using System.Runtime.InteropServices;",
            "using System.Text;",
            "public static class Playwright4jFirefoxWindows {",
            "  private delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);",
            "  [DllImport(\"user32.dll\")] private static extern bool EnumWindows(EnumProc callback, IntPtr lParam);",
            "  [DllImport(\"user32.dll\")] private static extern bool IsWindowVisible(IntPtr hWnd);",
            "  [DllImport(\"user32.dll\")] private static extern IntPtr GetWindow(IntPtr hWnd, uint command);",
            "  [DllImport(\"user32.dll\", CharSet = CharSet.Unicode)] private static extern int GetClassName(IntPtr hWnd, StringBuilder name, int size);",
            "  [DllImport(\"user32.dll\", CharSet = CharSet.Unicode)] private static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int size);",
            "  [DllImport(\"user32.dll\")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);",
            "  public static List<string> List() {",
            "    List<string> result = new List<string>();",
            "    EnumProc callback = delegate (IntPtr hWnd, IntPtr lParam) {",
            "      if (!IsWindowVisible(hWnd) || GetWindow(hWnd, 4) != IntPtr.Zero) return true;",
            "      StringBuilder className = new StringBuilder(256);",
            "      GetClassName(hWnd, className, className.Capacity);",
            "      if (className.ToString() != \"MozillaWindowClass\") return true;",
            "      StringBuilder title = new StringBuilder(512);",
            "      GetWindowText(hWnd, title, title.Capacity);",
            "      uint pid;",
            "      GetWindowThreadProcessId(hWnd, out pid);",
            "      result.Add(pid + \"\\t\" + title);",
            "      return true;",
            "    };",
            "    EnumWindows(callback, IntPtr.Zero);",
            "    GC.KeepAlive(callback);",
            "    return result;",
            "  }",
            "}",
            "'@",
            "[Playwright4jFirefoxWindows]::List() | ForEach-Object { [Console]::Out.WriteLine($_) }",
            "");

    private FirefoxWindows() {
    }

    /**
     * Whether this platform can list windows: Windows always; Linux with an X display, xdotool, and
     * Firefox on X11 (with WAYLAND_DISPLAY set, Firefox uses Wayland unless MOZ_ENABLE_WAYLAND=0,
     * and xdotool cannot see its windows).
     */
    static boolean available() {
        if (WINDOWS) {
            return true;
        }
        String display = System.getenv("DISPLAY");
        String wayland = System.getenv("WAYLAND_DISPLAY");
        boolean firefoxOnX11 = wayland == null || wayland.isEmpty() || "0".equals(System.getenv("MOZ_ENABLE_WAYLAND"));
        return display != null && !display.isEmpty() && firefoxOnX11 && onPath("xdotool");
    }

    /** Visible top-level Firefox browser windows owned by one of the given processes. */
    static List<Window> ownedBy(Collection<Long> pids) throws IOException, InterruptedException {
        List<Window> result = new ArrayList<>();
        for (Window window : all()) {
            if (pids.contains(window.pid)) {
                result.add(window);
            }
        }
        return result;
    }

    static List<Window> all() throws IOException, InterruptedException {
        return WINDOWS ? windowsViaPowerShell() : windowsViaXdotool();
    }

    private static List<Window> windowsViaPowerShell() throws IOException, InterruptedException {
        // -EncodedCommand (UTF-16LE, Base64) passes the multi-line script as one command.
        String encoded = Base64.getEncoder().encodeToString(ENUM_WINDOWS_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        String output = run("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
        List<Window> windows = new ArrayList<>();
        for (String line : output.split("\\R")) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                windows.add(new Window(Long.parseLong(line.substring(0, tab).trim()), line.substring(tab + 1)));
            }
        }
        return windows;
    }

    private static List<Window> windowsViaXdotool() throws IOException, InterruptedException {
        List<Window> windows = new ArrayList<>();
        String ids;
        try {
            ids = run("xdotool", "search", "--onlyvisible", "--class", "firefox");
        } catch (IOException noMatch) {
            // xdotool exits with 1 when no window matches.
            return windows;
        }
        for (String id : ids.split("\\s+")) {
            if (id.isEmpty()) {
                continue;
            }
            long pid;
            try {
                pid = Long.parseLong(run("xdotool", "getwindowpid", id).trim());
            } catch (IOException | NumberFormatException noPid) {
                continue;
            }
            String title;
            try {
                title = run("xdotool", "getwindowname", id).trim();
            } catch (IOException noTitle) {
                title = "";
            }
            windows.add(new Window(pid, title));
        }
        return windows;
    }

    // Brings the given process's Firefox window to the front and presses Ctrl+W there, only once
    // the window has the keyboard focus. Exit code 2: no window, 3: the window did not get focus.
    private static final String CLOSE_TAB_SCRIPT = String.join("\n",
            "$ErrorActionPreference = 'Stop'",
            "Add-Type -TypeDefinition @'",
            "using System;",
            "using System.Runtime.InteropServices;",
            "using System.Text;",
            "using System.Threading;",
            "public static class Playwright4jFirefoxKeys {",
            "  private delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);",
            "  [DllImport(\"user32.dll\")] private static extern bool EnumWindows(EnumProc callback, IntPtr lParam);",
            "  [DllImport(\"user32.dll\")] private static extern bool IsWindowVisible(IntPtr hWnd);",
            "  [DllImport(\"user32.dll\")] private static extern IntPtr GetWindow(IntPtr hWnd, uint command);",
            "  [DllImport(\"user32.dll\", CharSet = CharSet.Unicode)] private static extern int GetClassName(IntPtr hWnd, StringBuilder name, int size);",
            "  [DllImport(\"user32.dll\")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);",
            "  [DllImport(\"user32.dll\")] private static extern bool IsIconic(IntPtr hWnd);",
            "  [DllImport(\"user32.dll\")] private static extern bool ShowWindow(IntPtr hWnd, int command);",
            "  [DllImport(\"user32.dll\")] private static extern bool SetForegroundWindow(IntPtr hWnd);",
            "  [DllImport(\"user32.dll\")] private static extern IntPtr GetForegroundWindow();",
            "  [DllImport(\"user32.dll\")] private static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);",
            "  private const uint KEYUP = 2;",
            "  public static int CloseTab(uint pid) {",
            "    IntPtr target = IntPtr.Zero;",
            "    EnumProc callback = delegate (IntPtr hWnd, IntPtr lParam) {",
            "      if (!IsWindowVisible(hWnd) || GetWindow(hWnd, 4) != IntPtr.Zero) return true;",
            "      StringBuilder className = new StringBuilder(256);",
            "      GetClassName(hWnd, className, className.Capacity);",
            "      uint owner;",
            "      GetWindowThreadProcessId(hWnd, out owner);",
            "      if (className.ToString() != \"MozillaWindowClass\" || owner != pid) return true;",
            "      target = hWnd;",
            "      return false;",
            "    };",
            "    EnumWindows(callback, IntPtr.Zero);",
            "    GC.KeepAlive(callback);",
            "    if (target == IntPtr.Zero) return 2;",
            "    if (IsIconic(target)) ShowWindow(target, 9);",
            "    if (!SetForegroundWindow(target)) {",
            "      // Windows lets a process take the foreground right after keyboard input; a lone Alt counts.",
            "      keybd_event(0x12, 0, 0, UIntPtr.Zero);",
            "      keybd_event(0x12, 0, KEYUP, UIntPtr.Zero);",
            "      SetForegroundWindow(target);",
            "    }",
            "    for (int i = 0; i < 20 && GetForegroundWindow() != target; i++) Thread.Sleep(100);",
            "    if (GetForegroundWindow() != target) return 3;",
            "    Thread.Sleep(300);",
            "    keybd_event(0x11, 0, 0, UIntPtr.Zero);",
            "    keybd_event(0x57, 0, 0, UIntPtr.Zero);",
            "    keybd_event(0x57, 0, KEYUP, UIntPtr.Zero);",
            "    keybd_event(0x11, 0, KEYUP, UIntPtr.Zero);",
            "    return 0;",
            "  }",
            "}",
            "'@",
            "exit [Playwright4jFirefoxKeys]::CloseTab(%d)",
            "");

    /**
     * Presses Ctrl+W in the Firefox window of the given process, as a user closing the current tab.
     * The keystroke is only sent once that window has the keyboard focus, so it never lands in
     * another application. Returns false when it could not be sent (no such window, or no focus).
     */
    static boolean closeTabWithKeyboard(long pid) throws IOException, InterruptedException {
        if (WINDOWS) {
            String script = String.format(Locale.ROOT, CLOSE_TAB_SCRIPT, pid);
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            try {
                run("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-EncodedCommand", encoded);
                return true;
            } catch (IOException notSent) {
                return false;
            }
        }
        String id;
        try {
            id = run("xdotool", "search", "--onlyvisible", "--pid", Long.toString(pid), "--class", "firefox").trim().split("\\s+")[0];
            run("xdotool", "windowfocus", "--sync", id);
            Thread.sleep(300);
            // xdotool key types into whatever has the focus; windowfocus may give up quietly.
            if (!run("xdotool", "getwindowfocus").trim().equals(id)) {
                return false;
            }
        } catch (IOException notFocused) {
            return false;
        }
        run("xdotool", "key", "--clearmodifiers", "ctrl+w");
        return true;
    }

    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream stream = process.getInputStream()) {
            stream.transferTo(out);
        }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timed out: " + String.join(" ", command));
        }
        String output = out.toString(StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException(String.join(" ", command) + " exited with " + process.exitValue() + ": " + output);
        }
        return output;
    }

    private static boolean onPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path candidate = Paths.get(dir, executable);
            if (Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }
}
