package tech.thessemaj.zygiskrewire;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * Page 1 — STATUS hero. One huge state headline ("● SECURE" / "● OFFLINE")
 * with a dim subline, then a collapsible terminal detail strip (the
 * bracket-prefixed readout; collapsed by default, tap to expand). The
 * blinking busy cursor and the hero LED pulse live only here.
 *
 * Design system: 8-pt grid (48 dp hero pad), max four text sizes (hero
 * 40sp / terminal 13sp / body 14sp / labels 12sp caps), text tiers via
 * opacity of the single text color, 200 ms color crossfade on state
 * change, and a one-shot LED glow pulse at the SECURE transition.
 */
public final class StatusPageView extends View {

    private static final long BLINK_MS = 500L;
    private static final long CROSSFADE_MS = 200L;
    private static final long GLOW_PULSE_MS = 700L;

    private HudModel model;
    private boolean busy;
    private boolean cursorOn = true;
    private boolean expanded;

    // Hero color crossfade + one-shot completion glow.
    private int prevHeroColor = HudModel.COLOR_AMBER;
    private long crossfadeStart;
    private long glowPulseStart;
    private boolean wasSecure;

    private static final Typeface MONO_BOLD =
            Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tintPaint = new Paint();
    private final Paint linePaint = new Paint();
    private final Handler tickHandler = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            cursorOn = (now / BLINK_MS) % 2 == 0;
            invalidate();
            tickHandler.postDelayed(this, 50L);
        }
    };

    public StatusPageView(Context context) {
        super(context);
        paint.setTypeface(Typeface.MONOSPACE);
        linePaint.setColor(HudModel.COLOR_BORDER);
        linePaint.setStrokeWidth(1f);
        setOnClickListener(v -> {
            expanded = !expanded;
            requestLayout();
            invalidate();
        });
    }

    public void setModel(HudModel value) {
        boolean secure = value != null && "SECURE".equals(value.heroText);
        if (model != null && secure && !wasSecure) {
            glowPulseStart = System.currentTimeMillis();
        }
        if (model != null) {
            prevHeroColor = model.heroColor;
            crossfadeStart = System.currentTimeMillis();
        }
        wasSecure = secure;
        model = value;
        requestLayout();
        invalidate();
    }

    public void setBusy(boolean value) {
        busy = value;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        tickHandler.post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        tickHandler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------- metrics

    private static final class Metrics {
        float pad;
        float heroSize;
        float labelSize;
        float bodySize;
        float termSize;
        float heroH;
        float labelH;
        float bodyH;
        float termH;
        float totalH;
    }

    private Metrics measure() {
        Metrics m = new Metrics();
        float d = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        m.pad = 48 * d;                       // 8-pt grid: hero block 48
        m.heroSize = 40f * sp;
        m.labelSize = 12f * sp;
        m.bodySize = 14f * sp;
        m.termSize = 13f * sp;
        m.heroH = lineHeight(m, m.heroSize);
        m.labelH = lineHeight(m, m.labelSize);
        m.bodyH = lineHeight(m, m.bodySize);
        m.termH = lineHeight(m, m.termSize);

        float h = m.pad;
        h += m.heroH + 8 * d + m.labelH;      // hero + subline (related: 8)
        h += 32 * d;                          // between groups: 32
        h += m.labelH + 16 * d;               // toggle line
        if (expanded && model != null) {
            for (HudModel.Line line : model.lines) {
                h += m.termH + (line.subtitle != null ? m.labelH + 2 * d : 0) + 16 * d;
            }
        }
        if (busy) {
            h += 16 * d + m.termH;            // blinking cursor row
        }
        h += 24 * d;
        m.totalH = h;
        return m;
    }

    private float lineHeight(Metrics m, float size) {
        paint.setTextSize(size);
        Paint.FontMetrics fm = paint.getFontMetrics();
        return fm.descent - fm.ascent;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Metrics m = measure();
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize((int) m.totalH + 1, heightMeasureSpec));
    }

    // ---------------------------------------------------------------- draw

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(HudModel.COLOR_BG);
        if (model == null) {
            return;
        }
        Metrics m = measure();
        float d = getResources().getDisplayMetrics().density;
        float x = m.pad;
        float y = m.pad;
        long now = System.currentTimeMillis();
        double phase = (now % 1600L) / 1600.0;
        float pulse = (float) (0.5 - 0.5 * Math.cos(phase * 2 * Math.PI));

        // Hero: pulsing LED dot + huge state word, 200 ms color crossfade.
        paint.setTextSize(m.heroSize);
        paint.setTypeface(MONO_BOLD);
        Paint.FontMetrics fm = paint.getFontMetrics();
        int heroColor = crossfadeColor(prevHeroColor, model.heroColor, now);
        float dotR = 9 * d;
        float dotCy = y - fm.ascent - fm.descent / 2 + 2 * d;

        glowPaint.setColor(heroColor);
        glowPaint.setAlpha((int) (35 + 55 * pulse));
        canvas.drawCircle(x + dotR, dotCy, dotR * (1.6f + 0.5f * pulse), glowPaint);
        drawGlowPulse(canvas, x + dotR, dotCy, dotR, heroColor, now);
        paint.setColor(heroColor);
        canvas.drawCircle(x + dotR, dotCy, dotR, paint);
        canvas.drawText(model.heroText, x + dotR * 2 + 10 * d,
                y - fm.ascent, paint);
        paint.setTypeface(Typeface.MONOSPACE);
        y += m.heroH + 8 * d;

        // Dim subline (secondary tier: 70 % text).
        paint.setTextSize(m.labelSize);
        paint.setColor(text(HudModel.ALPHA_SECONDARY));
        canvas.drawText(model.heroSubline, x, baseline(y, m.labelSize), paint);
        y += m.labelH + 32 * d;

        // Divider + collapsible terminal detail strip.
        canvas.drawLine(x, y, x + getWidth() - 2 * m.pad, y, linePaint);
        y += 16 * d;
        paint.setTextSize(m.labelSize);
        paint.setColor(text(HudModel.ALPHA_SECONDARY));
        canvas.drawText(expanded ? "▾ TERMINAL DETAIL" : "▸ TERMINAL DETAIL",
                x, baseline(y, m.labelSize), paint);
        y += m.labelH + 16 * d;

        if (expanded) {
            for (HudModel.Line line : model.lines) {
                // Value-over-label: the tag is the small dim element, the
                // value the prominent one.
                paint.setTextSize(m.labelSize);
                paint.setColor(text(HudModel.ALPHA_DIM));
                canvas.drawText(line.tag, x, baseline(y, m.termSize), paint);
                float tagW = paint.measureText(line.tag);

                // Accent 5 % tint behind a positive overlay row.
                if (line.tag.startsWith("[OVERLAY]") && line.text.contains("mounted")) {
                    tintPaint.setColor(HudModel.COLOR_GREEN);
                    tintPaint.setAlpha(13); // 5 %
                    canvas.drawRect(x, y - 2 * d, x + getWidth() - 2 * m.pad,
                            y + m.termH + 2 * d
                                    + (line.subtitle != null ? m.labelH : 0), tintPaint);
                }

                paint.setTextSize(m.termSize);
                paint.setColor(line.color);
                paint.setAlpha(line.alpha);
                canvas.drawText(line.text, x + tagW, baseline(y, m.termSize), paint);
                paint.setAlpha(255);
                y += m.termH;
                if (line.subtitle != null) {
                    paint.setTextSize(m.labelSize);
                    paint.setColor(text(HudModel.ALPHA_SECONDARY));
                    canvas.drawText(line.subtitle, x + tagW,
                            baseline(y, m.labelSize), paint);
                    y += m.labelH + 2 * d;
                }
                y += 16 * d;
            }
        }

        // Busy: blinking cursor block, hero only.
        if (busy) {
            paint.setTextSize(m.termSize);
            paint.setColor(cursorOn ? text(HudModel.ALPHA_HEADING)
                    : text(HudModel.ALPHA_DIM));
            canvas.drawText("█", x, baseline(y, m.termSize), paint);
        }
    }

    /** Text color at an opacity tier of the single text hue. */
    private static int text(int alpha) {
        return (alpha << 24) | (HudModel.COLOR_TEXT & 0xFFFFFF);
    }

    /** 200 ms eased crossfade between the previous and new hero colors. */
    private int crossfadeColor(int from, int to, long now) {
        float t = (now - crossfadeStart) / (float) CROSSFADE_MS;
        if (t < 0) {
            t = 0;
        }
        if (t > 1) {
            t = 1;
        }
        t = t * t * (3 - 2 * t);
        return blend(from, to, t);
    }

    /** One-shot expanding glow ring when injection completes (peak moment). */
    private void drawGlowPulse(Canvas canvas, float cx, float cy, float dotR,
                               int color, long now) {
        long age = now - glowPulseStart;
        if (glowPulseStart == 0 || age < 0 || age > GLOW_PULSE_MS) {
            return;
        }
        float t = age / (float) GLOW_PULSE_MS;
        glowPaint.setColor(color);
        glowPaint.setAlpha((int) (110 * (1 - t)));
        canvas.drawCircle(cx, cy, dotR * (1.4f + 1.8f * t), glowPaint);
    }

    /** Baseline y for text of {@code size} whose top is at {@code top}. */
    private float baseline(float top, float size) {
        paint.setTextSize(size);
        return top - paint.getFontMetrics().ascent;
    }

    private static int blend(int from, int to, float t) {
        int fr = (from >> 16) & 0xFF;
        int fg = (from >> 8) & 0xFF;
        int fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF;
        int tg = (to >> 8) & 0xFF;
        int tb = to & 0xFF;
        int r = Math.round(fr + (tr - fr) * t);
        int g = Math.round(fg + (tg - fg) * t);
        int b = Math.round(fb + (tb - fb) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
