package com.aresstack.playwright4j.spike;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

final class InstalledChromeLocator {

    Path locateChromeExecutable() {
        String configuredPath = System.getProperty("playwright4j.chrome.executablePath", "");

        if (!configuredPath.isBlank()) {
            return Paths.get(configuredPath);
        }

        for (Path candidate : chromeCandidates()) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException("Could not locate Google Chrome. Provide -Pplaywright4j.chrome.executablePath=<path-to-chrome.exe>.");
    }

    private List<Path> chromeCandidates() {
        List<Path> candidates = new ArrayList<Path>();
        addCandidate(candidates, System.getenv("PROGRAMFILES"), "Google", "Chrome", "Application", "chrome.exe");
        addCandidate(candidates, System.getenv("PROGRAMFILES(X86)"), "Google", "Chrome", "Application", "chrome.exe");
        addCandidate(candidates, System.getenv("LOCALAPPDATA"), "Google", "Chrome", "Application", "chrome.exe");
        return candidates;
    }

    private void addCandidate(List<Path> candidates, String root, String... children) {
        if (root == null || root.isBlank()) {
            return;
        }

        candidates.add(Paths.get(root, children));
    }
}
