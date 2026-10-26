package com.aresstack.playwright4j.graal;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdkHostProcessLauncherDescendantsTest {

    @Test
    void aProcessOlderThanItsRecordedParentIsNotADescendant() {
        Instant browserStart = Instant.ofEpochSecond(1000);
        List<JdkHostProcessLauncher.ProcessEntry> all = Arrays.asList(
                // Windows keeps a parent id after the parent exits; the browser later got that pid.
                entry(200, 100, 500),
                entry(300, 200, 600),
                entry(400, 100, 1001),
                entry(500, 400, 1002),
                entry(600, 1, 1003));

        assertEquals(Arrays.asList(400L, 500L),
                pids(JdkHostProcessLauncher.descendantsAmong(100, browserStart, all)));
    }

    private static JdkHostProcessLauncher.ProcessEntry entry(long pid, long parentPid, long startSecond) {
        return new JdkHostProcessLauncher.ProcessEntry(null, pid, parentPid, Instant.ofEpochSecond(startSecond));
    }

    private static List<Long> pids(List<JdkHostProcessLauncher.ProcessEntry> entries) {
        List<Long> pids = new ArrayList<Long>();
        for (JdkHostProcessLauncher.ProcessEntry entry : entries) {
            pids.add(entry.pid);
        }
        return pids;
    }
}
