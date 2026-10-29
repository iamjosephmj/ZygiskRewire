package tech.thessemaj.zygiskrewire;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Payload-carrier app and module manager. Displays this build's payload id
 * read from {@code assets/payload/identity.props}, and — on explicit user
 * action only — reads the module's tmpfs runtime state from
 * {@code /dev/<id>/} and starts/stops the daemon via the ptracer's
 * {@code ctl} interface, through {@link SuShell}.
 *
 * All derived paths are defensive: a missing payload id or an absent
 * {@code /dev/<id>} renders neutral text, never a crash.
 */
public class CarrierActivity extends Activity {

    private static final String PROPS_PATH = "payload/identity.props";
    private static final String MISSING_ID = "(missing)";

    private TextView statusView;
    private Button refreshButton;
    private Button startButton;
    private Button stopButton;

    /** True while a root operation runs on the worker thread. */
    private volatile boolean busy;

    /** ctl candidates discovered by the last refresh: {@code /dev/<id>/bin/<name>}. */
    private volatile List<String> ctlCandidates = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No root here — root is requested only from the buttons below.

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        TextView idView = new TextView(this);
        idView.setText("payload id: " + readPayloadId());
        root.addView(idView);

        statusView = new TextView(this);
        statusView.setPadding(0, 32, 0, 32);
        statusView.setText("Tap Refresh to read module status.\n"
                + "(This requests root — approve the prompt if it appears.)");
        root.addView(statusView);

        refreshButton = makeButton("Refresh", v -> refreshStatus());
        root.addView(refreshButton);

        startButton = makeButton("Start daemon", v -> controlDaemon("start"));
        root.addView(startButton);

        stopButton = makeButton("Stop daemon", v -> controlDaemon("stop"));
        root.addView(stopButton);

        setContentView(root);
    }

    private Button makeButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        return button;
    }

    private void setBusy(boolean value) {
        busy = value;
        refreshButton.setEnabled(!value);
        startButton.setEnabled(!value);
        stopButton.setEnabled(!value);
    }

    // ---------------------------------------------------------------- status

    private void refreshStatus() {
        if (busy) {
            return;
        }
        setBusy(true);
        setStatus("Reading status…");

        new Thread(() -> {
            String id = readPayloadId();
            String report;
            List<String> candidates = new ArrayList<>();

            if (MISSING_ID.equals(id)) {
                report = "payload id missing from assets — cannot derive /dev/<id>";
            } else {
                // Probe root first so "su denied" and "no runtime dir" render
                // distinct neutral states (ls of a missing path also exits 1).
                String who = SuShell.run("id -u");
                if (who == null || !who.trim().equals("0")) {
                    report = "root unavailable — grant root to this app, then Refresh again";
                } else {
                    String bin = SuShell.run("ls /dev/" + id + "/bin 2>/dev/null");
                    if (bin == null || bin.trim().isEmpty()) {
                        report = "module not running — /dev/" + id + "/ does not exist";
                    } else {
                        candidates = findCtlCandidates(id, bin);
                        String json = SuShell.run("cat /dev/" + id + "/*.json 2>/dev/null");
                        report = buildStatusReport(id, candidates, json);
                    }
                }
            }

            final String text = report;
            final List<String> found = candidates;
            runOnUiThread(() -> {
                ctlCandidates = found;
                setStatus(text);
                setBusy(false);
            });
        }).start();
    }

    /**
     * Picks ctl-binary candidates from a {@code ls /dev/<id>/bin} listing:
     * executables ending in this device's bitness suffix ("64"/"32"). With
     * several matches (daemon + ptracer both end in the suffix) the ptracer
     * — the name ending in 'p' before the suffix — is listed first, but all
     * candidates are kept so the caller can try each until one succeeds.
     */
    private static List<String> findCtlCandidates(String id, String binListing) {
        List<String> names = new ArrayList<>();
        String suffix = is64BitDevice() ? "64" : "32";
        for (String name : binListing.split("\n")) {
            name = name.trim();
            if (name.endsWith(suffix)) {
                names.add(name);
            }
        }
        // Ptracer first: per the build's naming scheme it ends in 'p'.
        names.sort((a, b) -> Boolean.compare(b.endsWith("p" + suffix), a.endsWith("p" + suffix)));
        List<String> paths = new ArrayList<>();
        for (String name : names) {
            paths.add("/dev/" + id + "/bin/" + name);
        }
        return paths;
    }

    private static boolean is64BitDevice() {
        for (String abi : android.os.Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi) || "x86_64".equals(abi)) {
                return true;
            }
        }
        return false;
    }

    private static String buildStatusReport(String id, List<String> ctlPaths, String json) {
        StringBuilder sb = new StringBuilder();
        sb.append("runtime: /dev/").append(id).append('\n');
        sb.append("ctl: ").append(ctlPaths.isEmpty() ? "(none found in bin/)" : joinLines(ctlPaths))
                .append('\n');
        sb.append("state:\n");
        sb.append(json == null || json.trim().isEmpty()
                ? "  (no state json in /dev/" + id + "/)"
                : json);
        return sb.toString();
    }

    // --------------------------------------------------------------- control

    private void controlDaemon(final String action) {
        if (busy) {
            return;
        }
        setBusy(true);
        setStatus("Sending ctl " + action + "…");

        new Thread(() -> {
            String id = readPayloadId();
            String result;

            if (MISSING_ID.equals(id)) {
                result = "payload id missing — cannot locate ctl binary";
            } else {
                List<String> candidates = ctlCandidates;
                if (candidates.isEmpty()) {
                    String bin = SuShell.run("ls /dev/" + id + "/bin 2>/dev/null");
                    candidates = bin == null || bin.trim().isEmpty()
                            ? new ArrayList<>() : findCtlCandidates(id, bin);
                }

                // Try each candidate until one sends the command successfully.
                String sent = null;
                for (String path : candidates) {
                    String out = SuShell.run(path + " ctl " + action + " 2>/dev/null");
                    if (out != null) {
                        sent = path;
                        break;
                    }
                }

                if (sent != null) {
                    result = "ctl " + action + " sent via " + sent;
                } else {
                    result = "ctl " + action + " failed — is the module running?\n"
                            + "(root unavailable, or monitor not started)";
                }
            }

            final String text = result;
            runOnUiThread(() -> {
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
                setStatus(text);
                setBusy(false);
                // Status may have changed; refresh it (non-root op if su fails).
                refreshStatus();
            });
        }).start();
    }

    private void setStatus(String text) {
        statusView.setText(text);
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("  ").append(line);
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------- assets

    private String readPayloadId() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(getAssets().open(PROPS_PATH)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0 && "id".equals(line.substring(0, eq).trim())) {
                    String value = line.substring(eq + 1).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (Exception e) {
            // Fall through to the neutral error text.
        }
        return MISSING_ID;
    }
}
