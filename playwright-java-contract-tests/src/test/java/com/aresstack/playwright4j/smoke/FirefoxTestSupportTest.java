package com.aresstack.playwright4j.smoke;

import com.aresstack.playwright4j.smoke.FirefoxTestSupport.FirefoxProcess;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The leftover-process guard of the Firefox smokes: a Firefox background task of the machine
 * (the Windows default-browser agent) and its content processes are not leaks, a browser the test
 * started is.
 */
public class FirefoxTestSupportTest {
    private static final String CAMOUFOX = "\"D:\\a\\_temp\\camoufox\\camoufox.exe\" -no-remote -profile D:\\a\\_temp\\p";
    private static final String CONTENT = " -contentproc -parentBuildID 20261019185856 -prefsHandle 1684:26054 -win32kLockedDown -appDir \"C:\\Program Files\\Mozilla Firefox\\browser\" - 1 socket";
    // As seen on the Windows runner (CI run 40306805235): the agent starts a second firefox.exe
    // with the same arguments, which starts the content process.
    private static final String BACKGROUND = "\"C:\\Program Files\\Mozilla Firefox\\firefox.exe\" --backgroundtask defaultagent do-task 308046B0AF4A39CB";

    @Test
    void theBackgroundTaskAndItsContentProcessesAreNotLeaks() {
        Map<Long, FirefoxProcess> details = details(
                new FirefoxProcess(2440, 5292, BACKGROUND),
                new FirefoxProcess(2148, 2440, BACKGROUND),
                new FirefoxProcess(7752, 2148, "\"C:\\Program Files\\Mozilla Firefox\\firefox.exe\"" + CONTENT));
        assertEquals(pids(), FirefoxTestSupport.withoutBackgroundTasks(pids(2440, 2148, 7752), details));
    }

    @Test
    void aBrowserTheTestStartedIsALeak() {
        Map<Long, FirefoxProcess> details = details(
                new FirefoxProcess(2440, 5292, BACKGROUND),
                new FirefoxProcess(7752, 2440, "\"C:\\Program Files\\Mozilla Firefox\\firefox.exe\"" + CONTENT),
                new FirefoxProcess(100, 1, CAMOUFOX),
                new FirefoxProcess(101, 100, "\"D:\\a\\_temp\\camoufox\\camoufox.exe\"" + CONTENT),
                new FirefoxProcess(102, 100, "/opt/firefox/firefox-bin -contentproc -childID 2 -isForBrowser"));
        assertEquals(pids(100, 101, 102), FirefoxTestSupport.withoutBackgroundTasks(pids(2440, 7752, 100, 101, 102), details));
    }

    // A process whose details could not be read (it started while being listed) still counts, and
    // a stale parent pid that points back into the set does not loop.
    @Test
    void aProcessWithoutDetailsOrWithACyclicParentIsKept() {
        Map<Long, FirefoxProcess> details = details(
                new FirefoxProcess(100, 101, CAMOUFOX),
                new FirefoxProcess(101, 100, "\"D:\\a\\_temp\\camoufox\\camoufox.exe\"" + CONTENT));
        assertEquals(pids(100, 101, 200), FirefoxTestSupport.withoutBackgroundTasks(pids(100, 101, 200), details));
    }

    private static Map<Long, FirefoxProcess> details(FirefoxProcess... processes) {
        Map<Long, FirefoxProcess> details = new HashMap<>();
        for (FirefoxProcess process : processes) {
            details.put(process.pid, process);
        }
        return details;
    }

    private static Set<Long> pids(long... pids) {
        Set<Long> set = new HashSet<>();
        for (long pid : pids) {
            set.add(pid);
        }
        return set;
    }
}
