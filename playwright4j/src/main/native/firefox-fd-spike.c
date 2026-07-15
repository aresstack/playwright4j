/*
 * Slice 2 standalone spike (issue #10): launch Playwright's Firefox with REAL inherited fd 3/4
 * pipes and do a minimal Juggler handshake. NOT part of the build; NOT wired into Java/JS.
 *
 * Firefox (-juggler-pipe) speaks a \0-delimited JSON protocol over:
 *   fd 3 = Firefox READS commands  (parent writes)
 *   fd 4 = Firefox WRITES events   (parent reads)
 * Windows has no POSIX fd inheritance; instead the MSVCRT inherited-handle block is passed via
 * STARTUPINFO.lpReserved2, and the child's C runtime maps fd 0..count-1 to those handles.
 *
 * Usage: firefox-fd-spike.exe <firefox.exe path> [profileDir]
 * Prints the Browser.getInfo version read back from fd 4 on success.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>

/* MSVCRT fd-flag bits (as libuv writes them). */
#define FOPEN 0x01
#define FPIPE 0x08
#define FDEV  0x40

static void die(const char *msg) {
    fprintf(stderr, "SPIKE_FAIL: %s (err=%lu)\n", msg, GetLastError());
    exit(2);
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <firefox.exe> [profileDir]\n", argv[0]);
        return 2;
    }
    const char *firefox = argv[1];

    char profile[MAX_PATH];
    if (argc >= 3) {
        strncpy(profile, argv[2], MAX_PATH - 1);
        profile[MAX_PATH - 1] = '\0';
    } else {
        char tmp[MAX_PATH];
        GetTempPathA(MAX_PATH, tmp);
        snprintf(profile, MAX_PATH, "%spw4j_fx_spike_%lu", tmp, GetCurrentProcessId());
        CreateDirectoryA(profile, NULL);
    }
    fprintf(stderr, "SPIKE: firefox=%s\nSPIKE: profile=%s\n", firefox, profile);

    SECURITY_ATTRIBUTES sa;
    sa.nLength = sizeof(sa);
    sa.lpSecurityDescriptor = NULL;
    sa.bInheritHandle = TRUE;

    /* Pipe 1: commands, parent -> child (child reads it as fd 3). */
    HANDLE p1_read = NULL, p1_write = NULL;   /* p1_read = child end (fd3) */
    if (!CreatePipe(&p1_read, &p1_write, &sa, 0)) die("CreatePipe(commands)");
    if (!SetHandleInformation(p1_write, HANDLE_FLAG_INHERIT, 0)) die("uninherit p1_write");

    /* Pipe 2: events, child -> parent (child writes it as fd 4). */
    HANDLE p2_read = NULL, p2_write = NULL;    /* p2_write = child end (fd4) */
    if (!CreatePipe(&p2_read, &p2_write, &sa, 0)) die("CreatePipe(events)");
    if (!SetHandleInformation(p2_read, HANDLE_FLAG_INHERIT, 0)) die("uninherit p2_read");

    HANDLE hIn  = GetStdHandle(STD_INPUT_HANDLE);
    HANDLE hOut = GetStdHandle(STD_OUTPUT_HANDLE);
    HANDLE hErr = GetStdHandle(STD_ERROR_HANDLE);
    SetHandleInformation(hOut, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);
    SetHandleInformation(hErr, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);

    /* Build the MSVCRT inherited-handle block for fds 0..4:
     *   [int count][count flag bytes][count HANDLEs]  */
    const int count = 5;
    unsigned char flags[5];
    HANDLE handles[5];
    flags[0] = FOPEN | FDEV;  handles[0] = hIn;
    flags[1] = FOPEN | FDEV;  handles[1] = hOut;
    flags[2] = FOPEN | FDEV;  handles[2] = hErr;
    flags[3] = FOPEN | FPIPE; handles[3] = p1_read;   /* fd3: child reads commands  */
    flags[4] = FOPEN | FPIPE; handles[4] = p2_write;  /* fd4: child writes events   */

    size_t blockSize = sizeof(int) + count * (sizeof(unsigned char) + sizeof(HANDLE));
    unsigned char *block = (unsigned char *)malloc(blockSize);
    *(int *)block = count;
    memcpy(block + sizeof(int), flags, count);
    memcpy(block + sizeof(int) + count, handles, count * sizeof(HANDLE));

    /* Command line: firefox.exe -no-remote -headless -profile <dir> -juggler-pipe -silent */
    char cmd[4096];
    snprintf(cmd, sizeof(cmd),
             "\"%s\" -no-remote -headless -profile \"%s\" -juggler-pipe -silent",
             firefox, profile);

    HANDLE job = CreateJobObjectA(NULL, NULL);
    if (job) {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION ji;
        memset(&ji, 0, sizeof(ji));
        ji.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        SetInformationJobObject(job, JobObjectExtendedLimitInformation, &ji, sizeof(ji));
    }

    STARTUPINFOA si;
    memset(&si, 0, sizeof(si));
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESTDHANDLES;
    si.hStdInput = hIn;
    si.hStdOutput = hOut;
    si.hStdError = hErr;
    si.cbReserved2 = (WORD)blockSize;
    si.lpReserved2 = block;

    PROCESS_INFORMATION pi;
    memset(&pi, 0, sizeof(pi));
    if (!CreateProcessA(NULL, cmd, NULL, NULL, TRUE, CREATE_SUSPENDED, NULL, NULL, &si, &pi))
        die("CreateProcess(firefox)");
    if (job) AssignProcessToJobObject(job, pi.hProcess);
    ResumeThread(pi.hThread);
    CloseHandle(pi.hThread);
    fprintf(stderr, "SPIKE: launched pid=%lu\n", pi.dwProcessId);

    /* Parent no longer needs the child ends. */
    CloseHandle(p1_read);
    CloseHandle(p2_write);

    /* Write the first two Juggler commands, each \0-terminated. */
    const char *msg1 = "{\"method\":\"Browser.enable\",\"params\":{\"attachToDefaultContext\":false,\"userPrefs\":[]},\"id\":1}";
    const char *msg2 = "{\"method\":\"Browser.getInfo\",\"id\":2}";
    DWORD wrote;
    WriteFile(p1_write, msg1, (DWORD)strlen(msg1), &wrote, NULL);
    WriteFile(p1_write, "\0", 1, &wrote, NULL);
    WriteFile(p1_write, msg2, (DWORD)strlen(msg2), &wrote, NULL);
    WriteFile(p1_write, "\0", 1, &wrote, NULL);
    fprintf(stderr, "SPIKE: wrote Browser.enable + Browser.getInfo\n");

    /* Read events from fd4 until we see the id:2 getInfo result (or time out via read failure). */
    char acc[65536];
    size_t accLen = 0;
    char buf[4096];
    DWORD got;
    int found = 0, guard = 0;
    while (!found && guard++ < 200) {
        if (!ReadFile(p2_read, buf, sizeof(buf), &got, NULL) || got == 0) break;
        for (DWORD i = 0; i < got; i++) {
            if (buf[i] == '\0') {
                acc[accLen] = '\0';
                if (strstr(acc, "\"id\":2") && strstr(acc, "version")) {
                    fprintf(stderr, "SPIKE: RECV getInfo -> %s\n", acc);
                    char *v = strstr(acc, "\"version\":\"");
                    if (v) { v += 11; char *e = strchr(v, '"'); if (e) *e = '\0'; printf("SPIKE_VERSION=%s\n", v); }
                    found = 1; break;
                } else {
                    fprintf(stderr, "SPIKE: RECV %.120s\n", acc);
                }
                accLen = 0;
            } else if (accLen < sizeof(acc) - 1) {
                acc[accLen++] = buf[i];
            }
        }
    }

    fprintf(stderr, found ? "SPIKE: HANDSHAKE_OK\n" : "SPIKE: HANDSHAKE_FAILED\n");

    /* Teardown: closing the job kills Firefox (KILL_ON_JOB_CLOSE). */
    CloseHandle(p1_write);
    CloseHandle(p2_read);
    if (job) CloseHandle(job);
    CloseHandle(pi.hProcess);
    free(block);
    return found ? 0 : 1;
}
