package tech.thessemaj.zygiskrewire;

import java.util.ArrayList;
import java.util.List;

/**
 * Palette plus the shared, read-only HUD state snapshot that all three
 * pager pages draw from. Built by {@code CarrierActivity} after every root
 * refresh and handed to the pages atomically.
 */
public final class HudModel {

    // Palette (HUD contract).
    public static final int COLOR_BG = 0xFF0A0E12;
    public static final int COLOR_GREEN = 0xFF00E676;
    public static final int COLOR_RED = 0xFFFF5252;
    public static final int COLOR_AMBER = 0xFFFFC107;
    public static final int COLOR_TEXT = 0xFFE0E6ED;
    public static final int COLOR_DIM = 0xFF546E7A;
    public static final int COLOR_BORDER = 0xFF1E2A32;

    // Text hierarchy via opacity of the single text color (#E0E6ED):
    // headings 100%, body 80%, secondary 60-70% — no separate dim hues.
    public static final int ALPHA_HEADING = 255;
    public static final int ALPHA_BODY = 204;      // 80 %
    public static final int ALPHA_SECONDARY = 179; // 70 %
    public static final int ALPHA_DIM = 153;       // 60 %

    /** Injection pipeline node state. */
    public enum StageState { ACTIVE, BLOCKED, OFF }

    /** One pipeline rail node: caps label + plain-language subtitle. */
    public static final class Stage {
        public final String label;
        public final String subtitle;
        public final StageState state;

        public Stage(String label, String subtitle, StageState state) {
            this.label = label;
            this.subtitle = subtitle;
            this.state = state;
        }
    }

    /** One terminal strip row: {@code [TAG] <text>} + subtitle. */
    public static final class Line {
        public final String tag;
        public final String text;
        public final int color;
        public final String subtitle;
        /** Text alpha for neutral/dim rows (value rows keep their color). */
        public final int alpha;

        public Line(String tag, String text, int color, String subtitle) {
            this(tag, text, color, subtitle, ALPHA_HEADING);
        }

        public Line(String tag, String text, int color, String subtitle, int alpha) {
            this.tag = tag;
            this.text = text;
            this.color = color;
            this.subtitle = subtitle;
            this.alpha = alpha;
        }
    }

    /** One third-party module card (page 3). */
    public static final class ModuleCard {
        public final String name;
        public final boolean loaded;

        public ModuleCard(String name, boolean loaded) {
            this.name = name;
            this.loaded = loaded;
        }
    }

    /** Hero headline text, e.g. {@code SECURE} — the LED dot is drawn by the page. */
    public final String heroText;
    public final int heroColor;
    /** Dim subline under the hero: "daemon up · zygote injected · 2 modules". */
    public final String heroSubline;
    public final List<Stage> stages;
    public final List<Line> lines;
    public final List<ModuleCard> modules;

    public HudModel(String heroText, int heroColor, String heroSubline,
                    List<Stage> stages, List<Line> lines, List<ModuleCard> modules) {
        this.heroText = heroText;
        this.heroColor = heroColor;
        this.heroSubline = heroSubline;
        this.stages = stages;
        this.lines = lines;
        this.modules = modules;
    }

    public static List<Stage> emptyStages() {
        return new ArrayList<>();
    }
}
