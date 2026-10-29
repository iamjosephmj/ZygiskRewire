package tech.thessemaj.zygiskrewire;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Synchronous root-shell utility. Spawns {@code su} (no {@code -c}, so no
 * quoting gymnastics), feeds the command plus an {@code exit} to its stdin,
 * captures stdout until the process exits, and enforces a 5 s watchdog:
 * on timeout the process is destroyed and {@code null} is returned.
 *
 * Returns the captured stdout on a zero exit, {@code null} on any failure
 * (su absent/denied, non-zero exit, timeout, IO error). Callers own
 * threading — never call from the main thread; this blocks up to 5 s.
 *
 * Root is requested only when the caller invokes {@link #run}, i.e. only on
 * explicit user action (the manager's Refresh / Start / Stop buttons).
 */
public final class SuShell {

    private static final int TIMEOUT_MS = 5000;

    private SuShell() {
    }

    /**
     * Runs {@code cmd} as root. Returns stdout, or {@code null} on any
     * failure including the 5 s watchdog timeout.
     */
    public static String run(String cmd) {
        Process process;
        try {
            process = Runtime.getRuntime().exec("su");
        } catch (IOException e) {
            // su binary absent or exec blocked.
            return null;
        }

        // Watchdog: destroy the su process after the timeout so the blocking
        // reads below unblock (readLine sees EOF once the stream is torn down).
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(TIMEOUT_MS);
                process.destroy();
            } catch (InterruptedException ignored) {
                // Normal path: command finished before the timeout.
            }
        });
        watchdog.start();

        try {
            DataOutputStream stdin = new DataOutputStream(process.getOutputStream());
            stdin.write((cmd + "\nexit\n").getBytes("UTF-8"));
            stdin.flush();
            stdin.close();

            BufferedReader stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = stdout.readLine()) != null) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(line);
            }

            int exit = process.waitFor();
            return exit == 0 ? out.toString() : null;
        } catch (Exception e) {
            // Interrupted IO, broken pipe (root denied), waitFor failure…
            return null;
        } finally {
            watchdog.interrupt();
            process.destroy();
        }
    }
}
