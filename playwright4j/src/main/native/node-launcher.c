/*
 * playwright4j node.exe compatibility launcher.
 *
 * Some upstream Playwright-Java tests (notably TestBrowserTypeConnect) start the browser server by
 * spawning "<driverDir>/node.exe <driverDir>/package/cli.js launch-server ..." directly via Java's
 * ProcessBuilder, then read the ws:// endpoint from stdout. There is no real Node here, so this tiny
 * native executable stands in for node.exe: it re-execs the Java GraalJS driver in Node-CLI
 * compatibility mode, passing the original cli.js + arguments through unchanged.
 *
 * It does not contain any Playwright logic. The command prefix (java executable, classpath, main
 * class, --node-compat) is written by Driver.driverDir() into "node-launcher.cfg" next to this
 * executable; we read it, append our own argv[1..], and spawn. stdin/stdout/stderr are inherited so
 * the launch-server ws:// line reaches the test unchanged, and the child's exit code is propagated.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <process.h>
#include <windows.h>

int main(int argc, char **argv) {
    char exePath[MAX_PATH];
    DWORD pathLen = GetModuleFileNameA(NULL, exePath, MAX_PATH);
    if (pathLen == 0 || pathLen >= MAX_PATH) {
        fprintf(stderr, "playwright4j node launcher: cannot determine own path\n");
        return 2;
    }

    char cfgPath[MAX_PATH];
    char *lastSlash = strrchr(exePath, '\\');
    if (lastSlash != NULL) {
        size_t dirLen = (size_t)(lastSlash - exePath) + 1;
        memcpy(cfgPath, exePath, dirLen);
        strcpy(cfgPath + dirLen, "node-launcher.cfg");
    } else {
        strcpy(cfgPath, "node-launcher.cfg");
    }

    FILE *cfg = fopen(cfgPath, "rb");
    if (cfg == NULL) {
        fprintf(stderr, "playwright4j node launcher: cannot open %s\n", cfgPath);
        return 2;
    }

    char **prefix = NULL;
    int prefixCount = 0;
    char line[65536];
    while (fgets(line, sizeof(line), cfg) != NULL) {
        size_t n = strlen(line);
        while (n > 0 && (line[n - 1] == '\n' || line[n - 1] == '\r')) {
            line[--n] = '\0';
        }
        if (n == 0) {
            continue;
        }
        char **grown = (char **)realloc(prefix, sizeof(char *) * (size_t)(prefixCount + 1));
        if (grown == NULL) {
            fprintf(stderr, "playwright4j node launcher: out of memory\n");
            return 2;
        }
        prefix = grown;
        prefix[prefixCount] = _strdup(line);
        prefixCount++;
    }
    fclose(cfg);

    if (prefixCount == 0) {
        fprintf(stderr, "playwright4j node launcher: empty config %s\n", cfgPath);
        return 2;
    }

    int newCount = prefixCount + (argc - 1);
    const char **spawnArgv = (const char **)malloc(sizeof(char *) * (size_t)(newCount + 1));
    if (spawnArgv == NULL) {
        fprintf(stderr, "playwright4j node launcher: out of memory\n");
        return 2;
    }

    int k = 0;
    int i;
    for (i = 0; i < prefixCount; i++) {
        spawnArgv[k++] = prefix[i];
    }
    for (i = 1; i < argc; i++) {
        spawnArgv[k++] = argv[i];
    }
    spawnArgv[k] = NULL;

    intptr_t rc = _spawnv(_P_WAIT, spawnArgv[0], spawnArgv);
    if (rc == -1) {
        fprintf(stderr, "playwright4j node launcher: failed to spawn %s (errno %d)\n", spawnArgv[0], errno);
        return 2;
    }
    return (int)rc;
}
