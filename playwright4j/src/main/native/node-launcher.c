/*
 * playwright4j node.exe compatibility launcher.
 *
 * Some upstream Playwright-Java tests (notably TestBrowserTypeConnect) start the browser server by
 * spawning "<driverDir>/node.exe <driverDir>/package/cli.js launch-server ..." directly via Java's
 * ProcessBuilder, then read the ws:// endpoint from stdout. There is no real Node here, so this tiny
 * native executable stands in for node.exe: it re-execs the Java GraalJS driver in Node-CLI
 * compatibility mode, passing the original cli.js + arguments through unchanged.
 *
 * It contains no Playwright logic. The command prefix (java executable, classpath, main class,
 * --node-compat) is written by Driver.driverDir() into "node-launcher.cfg" next to this executable;
 * we read it, append our own argv[1..] and spawn.
 *
 * Teardown: the child (and its own children, e.g. the browser) are placed in a Job Object with
 * JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE. When this launcher exits for any reason - including the caller
 * calling TerminateProcess on node.exe (ProcessBuilder.destroy) - the job handle closes and the JVM
 * and browser are killed too, so nothing is orphaned. stdio is inherited so the launch-server ws://
 * line reaches the caller unchanged, and the child's exit code is propagated.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>

/* Appends one argument to a Windows command line with CommandLineToArgvW-compatible quoting. */
static void appendQuotedArg(char **cmd, size_t *len, size_t *cap, const char *arg) {
    int needsQuotes = (arg[0] == '\0') || strpbrk(arg, " \t\"") != NULL;
    size_t argLen = strlen(arg);
    size_t worstCase = argLen * 2 + 4;
    if (*len + worstCase >= *cap) {
        *cap = (*len + worstCase) * 2;
        *cmd = (char *)realloc(*cmd, *cap);
    }
    char *out = *cmd + *len;
    if (*len > 0) {
        *out++ = ' ';
    }
    if (!needsQuotes) {
        memcpy(out, arg, argLen);
        out += argLen;
    } else {
        *out++ = '"';
        size_t i = 0;
        while (i < argLen) {
            size_t backslashes = 0;
            while (i < argLen && arg[i] == '\\') {
                backslashes++;
                i++;
            }
            if (i == argLen) {
                /* Escape trailing backslashes so they do not escape the closing quote. */
                size_t j;
                for (j = 0; j < backslashes * 2; j++) {
                    *out++ = '\\';
                }
            } else if (arg[i] == '"') {
                size_t j;
                for (j = 0; j < backslashes * 2 + 1; j++) {
                    *out++ = '\\';
                }
                *out++ = '"';
                i++;
            } else {
                size_t j;
                for (j = 0; j < backslashes; j++) {
                    *out++ = '\\';
                }
                *out++ = arg[i];
                i++;
            }
        }
        *out++ = '"';
    }
    *len = (size_t)(out - *cmd);
    (*cmd)[*len] = '\0';
}

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

    /* Build the child command line: config prefix lines, then this launcher's argv[1..]. */
    size_t cap = 4096;
    size_t len = 0;
    char *cmdLine = (char *)malloc(cap);
    cmdLine[0] = '\0';

    char line[65536];
    int haveProgram = 0;
    while (fgets(line, sizeof(line), cfg) != NULL) {
        size_t n = strlen(line);
        while (n > 0 && (line[n - 1] == '\n' || line[n - 1] == '\r')) {
            line[--n] = '\0';
        }
        if (n == 0) {
            continue;
        }
        appendQuotedArg(&cmdLine, &len, &cap, line);
        haveProgram = 1;
    }
    fclose(cfg);

    if (!haveProgram) {
        fprintf(stderr, "playwright4j node launcher: empty config %s\n", cfgPath);
        return 2;
    }

    int i;
    for (i = 1; i < argc; i++) {
        appendQuotedArg(&cmdLine, &len, &cap, argv[i]);
    }

    HANDLE job = CreateJobObjectA(NULL, NULL);
    if (job != NULL) {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION info;
        memset(&info, 0, sizeof(info));
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        SetInformationJobObject(job, JobObjectExtendedLimitInformation, &info, sizeof(info));
    }

    STARTUPINFOA startup;
    memset(&startup, 0, sizeof(startup));
    startup.cb = sizeof(startup);
    startup.dwFlags = STARTF_USESTDHANDLES;
    startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    startup.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
    startup.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    /* The child must inherit our std handles (the pipes ProcessBuilder gave us, notably the stdout
     * pipe carrying the ws:// endpoint the caller reads). Mark them inheritable, else the child's
     * output is lost and the caller blocks forever on readLine(). */
    if (startup.hStdInput != NULL && startup.hStdInput != INVALID_HANDLE_VALUE) {
        SetHandleInformation(startup.hStdInput, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);
    }
    if (startup.hStdOutput != NULL && startup.hStdOutput != INVALID_HANDLE_VALUE) {
        SetHandleInformation(startup.hStdOutput, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);
    }
    if (startup.hStdError != NULL && startup.hStdError != INVALID_HANDLE_VALUE) {
        SetHandleInformation(startup.hStdError, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);
    }

    PROCESS_INFORMATION proc;
    memset(&proc, 0, sizeof(proc));

    /* CREATE_SUSPENDED so the child is assigned to the job before it can spawn its own children. */
    BOOL created = CreateProcessA(NULL, cmdLine, NULL, NULL, TRUE, CREATE_SUSPENDED,
                                  NULL, NULL, &startup, &proc);
    if (!created) {
        fprintf(stderr, "playwright4j node launcher: CreateProcess failed (error %lu)\n", GetLastError());
        return 2;
    }

    if (job != NULL) {
        AssignProcessToJobObject(job, proc.hProcess);
    }
    ResumeThread(proc.hThread);
    CloseHandle(proc.hThread);

    WaitForSingleObject(proc.hProcess, INFINITE);
    DWORD exitCode = 0;
    GetExitCodeProcess(proc.hProcess, &exitCode);
    CloseHandle(proc.hProcess);
    /* Closing the job here (and on any TerminateProcess of this launcher) kills any survivors. */
    if (job != NULL) {
        CloseHandle(job);
    }
    return (int)exitCode;
}
