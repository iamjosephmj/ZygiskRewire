package tech.thessemaj.zygiskrewire;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Payload-carrier app and module manager — dark ops-terminal HUD.
 *
 * Three-page horizontal swipe (programmatic pager, no dependencies):
 *  1. STATUS hero — huge SECURE/OFFLINE headline + collapsible terminal strip
 *     (hero LED pulse and the busy cursor live only here).
 *  2. INJECTION pipeline — vertical rail of the five injection stages.
 *  3. MODULES — one card per loaded third-party module.
 * A persistent bottom bar (REFRESH / START / STOP) stays pinned on every
 * page, out of content flow.
 *
 * All module interaction runs on worker threads via {@link SuShell} and is
 * triggered only by explicit button presses (root is requested then, and
 * only then). Derived paths are defensive: a missing payload id or an
 * absent {@code /dev/<id>} renders neutral readout lines, never a crash.
 */
public class CarrierActivity extends Activity {

    private static final String PROPS_PATH = "payload/identity.props";
    private static final String MISSING_ID = "(missing)";
    /** Build scheme from scripts/gen_identity.sh: MOD_ID = "syscfg-" + 8 hex. */
    private static final Pattern ID_PATTERN =
            Pattern.compile("^[A-Za-z][A-Za-z0-9-]{0,31}$");

    // Terminal strip tag column, padded so bracket columns align in monospace.
    private static final String TAG_DAEMON = "[DAEMON ] ";
    private static final String TAG_ZYGOTE = "[ZYGOTE ] ";
    private static final String TAG_OVERLAY = "[OVERLAY] ";
    private static final String TAG_MODULES = "[MODULES] ";
    private static final String TAG_ROOT = "[ROOT   ] ";
    private static final String TAG_SYSTEM = "[SYSTEM ] ";
    private static final String TAG_CMD = "[CMD    ] ";
    private static final String LED = "● ";

    /** ctl candidates discovered by the last refresh: {@code /dev/<id>/bin/<name>}. */
    private volatile List<String> ctlCandidates = new ArrayList<>();
    /** Result line of the last control command, shown by the next refresh. */
    private volatile String lastCmdLine;

    private HudPager pager;
    private StatusPageView statusPage;
    private PipelinePageView pipelinePage;
    private ModulesPageView modulesPage;
    private TextView pageIndicator;
    private TextView footerStamp;
    private TextView rootBadge;
    private Button refreshButton;
    private Button startButton;
    private Button stopButton;

    /** True while a root operation runs on the worker thread. */
    private volatile boolean busy;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** Fully-populated snapshot of one refresh, rendered on the UI thread. */
    private static final class ModuleState {
        String payloadId = MISSING_ID;
        boolean rootOk;
        String rootImpl;
        boolean runtimePresent;
        boolean jsonPresent;
        int monitorState = -1;      // 0 TRACING, 1 STOPPING, 2 STOPPED, 3 EXITING
        int daemonRunning = -1;     // <daemon>_st.64.state, 0 or 1
        boolean zygoteInjected;     // zygote.64
        boolean overlayMounted;
        String overlayLib;
        List<String> modules = new ArrayList<>();
        String systemNote;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No root here — root is requested only from the buttons below.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(HudModel.COLOR_BG);
        getWindow().setNavigationBarColor(HudModel.COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(HudModel.COLOR_BG);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, 0);
        // Edge-to-edge (targetSdk 35+): pad the HUD below the system bars.
        final int basePad = pad;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(basePad, basePad + top, basePad, bottom / 2);
            return insets.consumeSystemWindowInsets();
        });

        root.addView(buildHeader());

        pager = new HudPager(this);
        statusPage = new StatusPageView(this);
        pipelinePage = new PipelinePageView(this);
        modulesPage = new ModulesPageView(this);
        pager.addView(statusPage);
        pager.addView(pipelinePage);
        pager.addView(modulesPage);
        pager.setOnPageSettledListener(index -> pageIndicator.setText(
                (index + 1) + " / " + pager.getChildCount()));
        LinearLayout.LayoutParams pagerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        pagerParams.topMargin = dp(8);
        root.addView(pager, pagerParams);

        statusPage.setModel(initialModel());
        pipelinePage.setModel(initialModel());
        modulesPage.setModel(initialModel());

        root.addView(buildFooter());
        root.addView(buildBottomBar());

        setContentView(root);
    }

    // ------------------------------------------------------------------ HUD

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("ZYGISKREWIRE");
        title.setTypeface(Typeface.MONOSPACE);
        title.setTextColor(HudModel.COLOR_TEXT);
        title.setTextSize(12);
        title.setLetterSpacing(0.2f);
        header.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView idView = new TextView(this);
        idView.setText("payload " + readPayloadId());
        idView.setTypeface(Typeface.MONOSPACE);
        idView.setTextColor(HudModel.COLOR_DIM);
        idView.setTextSize(12);
        idView.setPadding(0, dp(2), 0, 0);
        header.addView(idView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return header;
    }

    private View buildFooter() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        footerStamp = new TextView(this);
        footerStamp.setText("--:--:--");
        footerStamp.setTypeface(Typeface.MONOSPACE);
        footerStamp.setTextColor(HudModel.COLOR_DIM);
        footerStamp.setTextSize(12);
        row.addView(footerStamp, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        pageIndicator = new TextView(this);
        pageIndicator.setText("1 / 3");
        pageIndicator.setTypeface(Typeface.MONOSPACE);
        pageIndicator.setTextColor(HudModel.COLOR_DIM);
        pageIndicator.setTextSize(12);
        pageIndicator.setPadding(0, 0, dp(12), 0);
        row.addView(pageIndicator, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        rootBadge = makeBadge("ROOT --", HudModel.COLOR_DIM);
        row.addView(rootBadge, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** Persistent bottom control bar — pinned on every page. */
    private View buildBottomBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(14));

        refreshButton = makeTerminalButton("REFRESH", v -> refreshStatus());
        startButton = makeTerminalButton("START", v -> controlDaemon("start"));
        stopButton = makeTerminalButton("STOP", v -> controlDaemon("stop"));

        int gap = dp(10);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(0, 0, gap, 0);
        row.addView(refreshButton, params);
        params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(0, 0, gap, 0);
        row.addView(startButton, params);
        row.addView(stopButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /**
     * Sharp rectangular bordered button, terminal-styled: monospace caps,
     * 1 px border in the palette border color, square corners. Disabled
     * (busy) state dims the text and drops the fill.
     */
    private Button makeTerminalButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(true);
        button.setTypeface(Typeface.MONOSPACE);
        button.setTextSize(12);
        button.setTextColor(buttonColors());
        button.setBackgroundDrawable(buttonBackground());
        button.setPadding(0, dp(14), 0, dp(14));
        button.setOnClickListener(listener);
        // Plain Activity + framework Button: strip the default elevation shadow
        // so the border reads as a flat terminal box.
        button.setStateListAnimator(null);
        return button;
    }

    private android.content.res.ColorStateList buttonColors() {
        return new android.content.res.ColorStateList(
                new int[][]{
                        {-android.R.attr.state_enabled},
                        android.util.StateSet.WILD_CARD,
                },
                new int[]{HudModel.COLOR_DIM, HudModel.COLOR_TEXT});
    }

    private StateListDrawable buttonBackground() {
        GradientDrawable enabled = new GradientDrawable();
        enabled.setShape(GradientDrawable.RECTANGLE);
        enabled.setColor(0xFF0D141A);
        enabled.setStroke(dp(1), HudModel.COLOR_BORDER);

        GradientDrawable disabled = new GradientDrawable();
        disabled.setShape(GradientDrawable.RECTANGLE);
        disabled.setColor(Color.TRANSPARENT);
        disabled.setStroke(dp(1), HudModel.COLOR_BORDER);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, disabled);
        states.addState(android.util.StateSet.WILD_CARD, enabled);
        return states;
    }

    private TextView makeBadge(String text, int color) {
        TextView badge = new TextView(this);
        badge.setText(text);
        badge.setTypeface(Typeface.MONOSPACE);
        badge.setTextSize(12);
        badge.setTextColor(color);
        badge.setBackgroundDrawable(badgeBorder(color));
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        return badge;
    }

    private GradientDrawable badgeBorder(int color) {
        GradientDrawable border = new GradientDrawable();
        border.setShape(GradientDrawable.RECTANGLE);
        border.setColor(Color.TRANSPARENT);
        border.setStroke(dp(1), color);
        return border;
    }

    private void restyleBadge(TextView badge, String text, int color) {
        badge.setText(text);
        badge.setTextColor(color);
        badge.setBackgroundDrawable(badgeBorder(color));
    }

    private HudModel initialModel() {
        List<HudModel.Stage> stages = new ArrayList<>();
        for (String label : STAGE_LABELS) {
            stages.add(new HudModel.Stage(label, "waiting for the first status read",
                    HudModel.StageState.OFF));
        }
        List<HudModel.Line> lines = new ArrayList<>();
        lines.add(new HudModel.Line(TAG_SYSTEM,
                "tap REFRESH to read module status", HudModel.COLOR_DIM,
                "root is requested on button press"));
        return new HudModel("PENDING", HudModel.COLOR_AMBER,
                "waiting for the first status read", stages, lines,
                new ArrayList<HudModel.ModuleCard>());
    }

    // ---------------------------------------------------------------- state

    private void refreshStatus() {
        if (busy) {
            return;
        }
        setBusy(true);

        new Thread(() -> {
            ModuleState state = new ModuleState();
            state.payloadId = readPayloadId();

            if (MISSING_ID.equals(state.payloadId)) {
                state.systemNote = "payload id missing from assets — cannot derive /dev/<id>";
            } else {
                // Probe root first so "su denied" and "no runtime dir" render
                // distinct neutral states (ls of a missing path also exits 1).
                String who = SuShell.run("id -u");
                state.rootOk = who != null && "0".equals(who.trim());
                if (state.rootOk) {
                    String bin = SuShell.run("ls /dev/" + state.payloadId + "/bin 2>/dev/null");
                    state.runtimePresent = bin != null && !bin.trim().isEmpty();
                    if (state.runtimePresent) {
                        ctlCandidates = findCtlCandidates(state.payloadId, bin);
                        String json = SuShell.run("cat /dev/" + state.payloadId + "/*.json 2>/dev/null");
                        parseStateJson(state, json);
                        if (!state.jsonPresent) {
                            state.systemNote = "state json missing in /dev/" + state.payloadId
                                    + "/ (monitor has not reported)";
                        }
                        String ovl = SuShell.run(
                                "ls /dev/" + state.payloadId + "/ovl/lib64 2>/dev/null");
                        if (ovl != null && !ovl.trim().isEmpty()) {
                            state.overlayLib = ovl.trim().split("\n")[0].trim();
                        }
                        String mounts = SuShell.run("cat /proc/mounts 2>/dev/null");
                        state.overlayMounted = mounts != null && mounts.contains(
                                "upperdir=/dev/" + state.payloadId + "/ovl/lib64");
                    } else {
                        state.systemNote = "module not running — /dev/" + state.payloadId
                                + "/ does not exist";
                    }
                }
            }

            final ModuleState snapshot = state;
            runOnUiThread(() -> {
                renderState(snapshot);
                setBusy(false);
            });
        }).start();
    }

    /** Fills {@code state} from the runtime state json (tolerates null/absent). */
    private static void parseStateJson(ModuleState state, String json) {
        state.jsonPresent = json != null && !json.trim().isEmpty();
        if (!state.jsonPresent) {
            return;
        }
        Matcher m = JSON_ROOT.matcher(json);
        if (m.find()) {
            state.rootImpl = m.group(1);
        }
        m = JSON_MONITOR_STATE.matcher(json);
        if (m.find()) {
            state.monitorState = Integer.parseInt(m.group(1));
        }
        m = JSON_DAEMON_STATE.matcher(json);
        if (m.find()) {
            state.daemonRunning = Integer.parseInt(m.group(1));
        }
        m = JSON_ZYGOTE_INJECTED.matcher(json);
        if (m.find()) {
            state.zygoteInjected = "1".equals(m.group(1));
        }
        m = JSON_MODULES.matcher(json);
        if (m.find()) {
            for (String item : m.group(1).split(",")) {
                String name = item.trim().replace("\"", "");
                if (!name.isEmpty()) {
                    state.modules.add(name);
                }
            }
        }
    }

    // Fixed output format of loader/src/ptracer/monitor.c update_status().
    private static final Pattern JSON_ROOT =
            Pattern.compile("\"root\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern JSON_MONITOR_STATE =
            Pattern.compile("\"monitor\"\\s*:\\s*\\{\\s*\"state\"\\s*:\\s*\"(\\d)\"");
    private static final Pattern JSON_DAEMON_STATE =
            Pattern.compile("\"64\"\\s*:\\s*\\{\\s*\"state\"\\s*:\\s*(\\d)");
    private static final Pattern JSON_ZYGOTE_INJECTED =
            Pattern.compile("\"zygote\"\\s*:\\s*\\{[^}]*\"64\"\\s*:\\s*(\\d)");
    private static final Pattern JSON_MODULES =
            Pattern.compile("\"64\"\\s*:\\s*\\{[^{]*?\"modules\"\\s*:\\s*\\[(.*?)\\]",
                    Pattern.DOTALL);

    /** Injection pipeline node labels, in flow order. */
    private static final String[] STAGE_LABELS =
            {"MONITOR", "DAEMON", "ZYGOTE INJECTION", "SYSTEM OVERLAY", "MODULES"};

    /**
     * Maps a refresh snapshot onto the shared HUD model (hero + pipeline +
     * terminal strip + module cards) and pushes it to all three pages.
     */
    private void renderState(ModuleState state) {
        boolean reported = state.runtimePresent && state.jsonPresent;
        boolean monitorUp = reported && state.monitorState == 0;
        boolean daemonUp = reported && state.daemonRunning == 1;

        // ---- Hero (page 1).
        String heroText;
        int heroColor;
        String heroSubline;
        if (MISSING_ID.equals(state.payloadId)) {
            heroText = "OFFLINE";
            heroColor = HudModel.COLOR_RED;
            heroSubline = "payload id missing from app assets";
        } else if (!state.rootOk) {
            heroText = "PENDING";
            heroColor = HudModel.COLOR_AMBER;
            heroSubline = "grant root to this app, then tap REFRESH";
        } else if (!state.runtimePresent) {
            heroText = "OFFLINE";
            heroColor = HudModel.COLOR_RED;
            heroSubline = "module runtime /dev/" + state.payloadId + "/ not found";
        } else if (!reported) {
            heroText = "OFFLINE";
            heroColor = HudModel.COLOR_RED;
            heroSubline = "monitor has not reported yet — tap START";
        } else if (daemonUp && state.zygoteInjected) {
            heroText = "SECURE";
            heroColor = HudModel.COLOR_GREEN;
            heroSubline = "daemon up · zygote injected · "
                    + state.modules.size() + " modules"
                    + (state.overlayMounted ? "" : " · overlay down");
        } else {
            heroText = "OFFLINE";
            heroColor = HudModel.COLOR_RED;
            heroSubline = daemonUp ? "daemon up · zygote not injected"
                    : "daemon down · zygote not injected";
        }

        // ---- Pipeline (page 2).
        List<HudModel.Stage> stages = new ArrayList<>();
        if (reported) {
            stages.add(new HudModel.Stage(STAGE_LABELS[0],
                    monitorUp ? "ptracer watch service is up" : "ptracer is stopping or stopped",
                    monitorUp ? HudModel.StageState.ACTIVE : HudModel.StageState.BLOCKED));
            stages.add(new HudModel.Stage(STAGE_LABELS[1],
                    daemonUp ? "background service is up" : "background service is down",
                    daemonUp ? HudModel.StageState.ACTIVE : HudModel.StageState.BLOCKED));
            stages.add(new HudModel.Stage(STAGE_LABELS[2],
                    state.zygoteInjected ? "code hooked into app process spawning"
                            : "app processes start unhooked",
                    state.zygoteInjected ? HudModel.StageState.ACTIVE
                            : HudModel.StageState.BLOCKED));
            stages.add(new HudModel.Stage(STAGE_LABELS[3],
                    state.overlayMounted
                            ? "/system/lib64/" + state.overlayLib + " is mounted"
                            : "no system library swap is mounted",
                    state.overlayMounted ? HudModel.StageState.ACTIVE
                            : HudModel.StageState.BLOCKED));
            stages.add(new HudModel.Stage(STAGE_LABELS[4],
                    state.modules.isEmpty() ? "no hook modules are loaded"
                            : state.modules.size() + " hook modules loaded into apps",
                    state.modules.isEmpty() ? HudModel.StageState.BLOCKED
                            : HudModel.StageState.ACTIVE));
        } else {
            String why = MISSING_ID.equals(state.payloadId) ? "no payload id"
                    : !state.rootOk ? "root missing" : !state.runtimePresent
                    ? "module runtime not found" : "no state report yet";
            for (String label : STAGE_LABELS) {
                stages.add(new HudModel.Stage(label, why, HudModel.StageState.OFF));
            }
        }

        // ---- Terminal strip (page 1, collapsible) + module cards (page 3).
        List<HudModel.Line> lines = new ArrayList<>();
        String cmdLine = lastCmdLine;
        if (cmdLine != null) {
            lines.add(new HudModel.Line(TAG_CMD, cmdLine,
                    cmdLine.contains("failed") ? HudModel.COLOR_RED
                            : HudModel.COLOR_TEXT, null));
        }
        List<HudModel.ModuleCard> cards = new ArrayList<>();

        if (MISSING_ID.equals(state.payloadId)) {
            // Stale / mismatched / missing id: fail-safe neutral text only.
            lines.add(new HudModel.Line(TAG_SYSTEM, state.systemNote != null
                    ? state.systemNote : "payload id unreadable",
                    HudModel.COLOR_AMBER, "cannot derive the runtime path"));
            lines.add(unknownLine(TAG_DAEMON, "background service status unknown"));
            lines.add(unknownLine(TAG_ZYGOTE, "injection status unknown"));
            lines.add(unknownLine(TAG_OVERLAY, "system swap status unknown"));
            lines.add(unknownLine(TAG_MODULES, "loaded module list unknown"));
            lines.add(unknownLine(TAG_ROOT, "root status unknown"));
        } else if (!state.rootOk) {
            // No root: amber UNAVAILABLE, everything else dimmed.
            lines.add(new HudModel.Line(TAG_ROOT, LED + "UNAVAILABLE",
                    HudModel.COLOR_AMBER, "no root provider answered the su request"));
            lines.add(unknownLine(TAG_DAEMON, "background service status unknown"));
            lines.add(unknownLine(TAG_ZYGOTE, "injection status unknown"));
            lines.add(unknownLine(TAG_OVERLAY, "system swap status unknown"));
            lines.add(unknownLine(TAG_MODULES, "loaded module list unknown"));
        } else if (!reported) {
            fillDownLines(lines, state);
        } else {
            fillLiveLines(lines, state);
            for (String module : state.modules) {
                cards.add(new HudModel.ModuleCard(module, true));
            }
        }

        HudModel model = new HudModel(heroText, heroColor, heroSubline,
                stages, lines, cards);
        statusPage.setModel(model);
        pipelinePage.setModel(model);
        modulesPage.setModel(model);

        // Footer widgets. Refresh completion gets a deliberate ending:
        // the affirmation line on the peak (SECURE) state.
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        footerStamp.setText(("SECURE".equals(heroText)
                ? "all systems nominal · " : "state updated · ") + stamp);
        int badgeColor = state.rootOk ? HudModel.COLOR_GREEN : HudModel.COLOR_AMBER;
        restyleBadge(rootBadge,
                state.rootOk ? "ROOT OK" : "ROOT N/A", badgeColor);
    }

    /** Readout lines for the runtime-down / no-report states (red STOPPED). */
    private void fillDownLines(List<HudModel.Line> lines, ModuleState state) {
        lines.add(new HudModel.Line(TAG_DAEMON, LED + "STOPPED",
                HudModel.COLOR_RED, "background service is down"));
        lines.add(new HudModel.Line(TAG_ZYGOTE, LED + "NOT INJECTED",
                HudModel.COLOR_RED, "app processes start unhooked"));
        lines.add(new HudModel.Line(TAG_OVERLAY, LED + "NOT MOUNTED",
                HudModel.COLOR_RED, "no system library swap is mounted"));
        lines.add(new HudModel.Line(TAG_MODULES, "(none)",
                HudModel.COLOR_TEXT, "no hook modules are loaded",
                HudModel.ALPHA_DIM));
        lines.add(new HudModel.Line(TAG_ROOT, LED + grantedText(state),
                HudModel.COLOR_GREEN, "root provider is available"));
        if (state.systemNote != null) {
            lines.add(new HudModel.Line(TAG_SYSTEM, state.systemNote,
                    HudModel.COLOR_TEXT, "stale or mismatched payload id looks like this",
                    HudModel.ALPHA_DIM));
        }
    }

    /** Readout lines for a reported (live or partial) module state. */
    private void fillLiveLines(List<HudModel.Line> lines, ModuleState state) {
        boolean daemonUp = state.daemonRunning == 1;
        lines.add(new HudModel.Line(TAG_DAEMON,
                LED + (daemonUp ? "RUNNING" : "STOPPED"),
                daemonUp ? HudModel.COLOR_GREEN : HudModel.COLOR_RED,
                daemonUp ? "background service is up" : "background service is down"));
        lines.add(new HudModel.Line(TAG_ZYGOTE,
                LED + (state.zygoteInjected ? "INJECTED (64-bit)" : "NOT INJECTED"),
                state.zygoteInjected ? HudModel.COLOR_GREEN : HudModel.COLOR_RED,
                state.zygoteInjected ? "code hooked into app process spawning"
                        : "app processes start unhooked"));
        String overlayText = state.overlayMounted
                ? "/system/lib64/" + state.overlayLib + " mounted"
                : "NOT MOUNTED";
        lines.add(new HudModel.Line(TAG_OVERLAY, LED + overlayText,
                state.overlayMounted ? HudModel.COLOR_GREEN : HudModel.COLOR_RED,
                state.overlayMounted ? "system library swap is mounted"
                        : "no system library swap is mounted"));
        String moduleList = state.modules.isEmpty()
                ? "(none)" : joinCommas(state.modules);
        lines.add(new HudModel.Line(TAG_MODULES, moduleList,
                state.modules.isEmpty() ? HudModel.COLOR_TEXT : HudModel.COLOR_TEXT,
                state.modules.isEmpty() ? "no hook modules are loaded"
                        : "hook modules loaded into apps",
                state.modules.isEmpty() ? HudModel.ALPHA_DIM : HudModel.ALPHA_BODY));
        lines.add(new HudModel.Line(TAG_ROOT, LED + grantedText(state),
                HudModel.COLOR_GREEN, "root provider is available"));
    }

    private static String grantedText(ModuleState state) {
        String impl = state.rootImpl != null && !state.rootImpl.isEmpty()
                ? state.rootImpl : "SU";
        return "GRANTED (" + impl + ")";
    }

    private static HudModel.Line unknownLine(String tag, String subtitle) {
        // Neutral/unknown rows render as text color at 60% opacity.
        return new HudModel.Line(tag, "—", HudModel.COLOR_TEXT, subtitle,
                HudModel.ALPHA_DIM);
    }

    private static String joinCommas(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(item);
        }
        return sb.toString();
    }

    // --------------------------------------------------------------- control

    private void controlDaemon(final String action) {
        if (busy) {
            return;
        }
        setBusy(true);

        new Thread(() -> {
            String id = readPayloadId();
            String result;

            if (MISSING_ID.equals(id)) {
                result = "ctl " + action + " failed — payload id missing";
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
                    result = "ctl " + action + " failed — is the module running?";
                }
            }

            lastCmdLine = result;
            runOnUiThread(() -> {
                // Status may have changed; refresh it (renders the cmd line too).
                setBusy(false);
                refreshStatus();
            });
        }).start();
    }

    private void setBusy(boolean value) {
        busy = value;
        refreshButton.setEnabled(!value);
        startButton.setEnabled(!value);
        stopButton.setEnabled(!value);
        // Blinking cursor lives only in the page-1 hero.
        statusPage.setBusy(value);
    }

    // ------------------------------------------------------------- plumbing

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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ----------------------------------------------------------------- assets

    /**
     * Reads the payload id from assets. Only ids matching the build scheme
     * ({@code scripts/gen_identity.sh}: letter first, then letters/digits/
     * dashes, max 32 chars) are accepted; anything else falls through to
     * "(missing)" — the id never crosses a boundary, it is only ever
     * concatenated into su-run shell commands like {@code cat /dev/<id>/*.json},
     * and the pattern keeps shell metacharacters out of those.
     */
    private String readPayloadId() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(getAssets().open(PROPS_PATH)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0 && "id".equals(line.substring(0, eq).trim())) {
                    String value = line.substring(eq + 1).trim();
                    if (ID_PATTERN.matcher(value).matches()) {
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
