package tech.thessemaj.zygiskrewire;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * Page 2 — INJECTION pipeline. A vertical rail MONITOR → DAEMON →
 * ZYGOTE INJECTION → SYSTEM OVERLAY → MODULES: nodes fill green as each
 * stage activates, show a red X on failure, and stay dim when unknown.
 * Each node carries its caps label plus the plain-language subtitle.
 */
public final class PipelinePageView extends View {

    private HudModel model;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint railPaint = new Paint();
    private final Paint xPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler tickHandler = new Handler(Looper.getMainLooper());

    private boolean ticking;

    /** True while any pulse-driven animation needs frames. */
    private boolean hasPulseAnimation() {
        if (model == null) {
            return false;
        }
        for (HudModel.Stage stage : model.stages) {
            if (stage.state == HudModel.StageState.ACTIVE) {
                return true;
            }
        }
        return false;
    }

    // 50 ms invalidate tick, run ONLY while the window is visible and an
    // ACTIVE stage keeps its node pulse alive; the loop stops itself when
    // the work runs out and is restarted by visibility/model changes.
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            if (hasPulseAnimation()) {
                tickHandler.postDelayed(this, TICK_MS);
            } else {
                ticking = false;
            }
        }
    };

    private static final long TICK_MS = 50L;

    private void ensureTicking() {
        if (!ticking && getWindowVisibility() == VISIBLE && hasPulseAnimation()) {
            ticking = true;
            tickHandler.post(tick);
        }
    }

    private void stopTicking() {
        tickHandler.removeCallbacks(tick);
        ticking = false;
    }

    public PipelinePageView(Context context) {
        super(context);
        paint.setTypeface(Typeface.MONOSPACE);
        railPaint.setStrokeWidth(2f);
        xPaint.setStrokeWidth(2.5f);
        xPaint.setStyle(Paint.Style.STROKE);
    }

    public void setModel(HudModel value) {
        model = value;
        invalidate();
        if (hasPulseAnimation()) {
            ensureTicking();
        } else {
            stopTicking();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ensureTicking();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopTicking();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            ensureTicking();
        } else {
            stopTicking();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(HudModel.COLOR_BG);
        if (model == null || model.stages.isEmpty()) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        float pad = 32 * d;
        float labelSize = 12f * sp;
        float bodySize = 14f * sp;

        int n = model.stages.size();
        float railX = pad + 9 * d;
        float usableH = getHeight() - 2 * pad;
        float spacing = Math.min(96 * d, usableH / n); // 8-pt grid: 96 dp rows

        paint.setTextSize(labelSize);
        Paint.FontMetrics lfm = paint.getFontMetrics();
        paint.setTextSize(bodySize);
        Paint.FontMetrics bfm = paint.getFontMetrics();
        float rowH = Math.max(spacing, (lfm.descent - lfm.ascent)
                + (bfm.descent - bfm.ascent) + 8 * d);

        float y = pad + rowH / 2;

        double phase = (System.currentTimeMillis() % 1600L) / 1600.0;
        float pulse = (float) (0.5 - 0.5 * Math.cos(phase * 2 * Math.PI));

        for (int i = 0; i < n; i++) {
            HudModel.Stage stage = model.stages.get(i);

            // Rail connector to the next node, green where the flow is live.
            if (i + 1 < n) {
                railPaint.setColor(stage.state == HudModel.StageState.ACTIVE
                        ? HudModel.COLOR_GREEN : HudModel.COLOR_BORDER);
                canvas.drawLine(railX, y + 9 * d, railX, y + rowH - 9 * d, railPaint);
            }

            // Node.
            if (stage.state == HudModel.StageState.ACTIVE) {
                glowPaint.setColor(HudModel.COLOR_GREEN);
                glowPaint.setAlpha((int) (35 + 55 * pulse));
                canvas.drawCircle(railX, y, 13 * d, glowPaint);
                fillPaint.setColor(HudModel.COLOR_GREEN);
                canvas.drawCircle(railX, y, 9 * d, fillPaint);
            } else {
                // Node ring: fillPaint toggles its own style; the TEXT paint
                // must stay FILL or every glyph downstream draws stroke-only.
                int ring = stage.state == HudModel.StageState.BLOCKED
                        ? HudModel.COLOR_RED : HudModel.COLOR_DIM;
                fillPaint.setColor(ring);
                fillPaint.setStyle(Paint.Style.STROKE);
                fillPaint.setStrokeWidth(2f);
                canvas.drawCircle(railX, y, 9 * d, fillPaint);
                fillPaint.setStyle(Paint.Style.FILL);
                if (stage.state == HudModel.StageState.BLOCKED) {
                    // Red X.
                    xPaint.setColor(HudModel.COLOR_RED);
                    float k = 4 * d;
                    canvas.drawLine(railX - k, y - k, railX + k, y + k, xPaint);
                    canvas.drawLine(railX - k, y + k, railX + k, y - k, xPaint);
                }
            }

            // Label (12sp caps, 100% text) above, subtitle (14sp body at
            // 70% text) below — tiers via opacity of the single text hue.
            float textX = railX + 20 * d;
            paint.setTextSize(labelSize);
            paint.setColor((stage.state == HudModel.StageState.OFF
                    ? HudModel.ALPHA_DIM : HudModel.ALPHA_HEADING) << 24
                    | (HudModel.COLOR_TEXT & 0xFFFFFF));
            float labelBaseline = y - 6 * d;
            canvas.drawText(stage.label, textX, labelBaseline, paint);

            paint.setTextSize(bodySize);
            paint.setColor((stage.state == HudModel.StageState.OFF
                    ? HudModel.ALPHA_DIM : HudModel.ALPHA_SECONDARY) << 24
                    | (HudModel.COLOR_TEXT & 0xFFFFFF));
            canvas.drawText(stage.subtitle, textX,
                    labelBaseline + (bfm.descent - bfm.ascent), paint);

            y += rowH;
        }
    }
}
