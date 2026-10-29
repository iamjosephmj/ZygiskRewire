package tech.thessemaj.zygiskrewire;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/**
 * Page 3 — MODULES. One bordered card per third-party module from the
 * state json (name + loaded state), max 4 visible with generous spacing;
 * any further modules collapse into a "+N more" line.
 */
public final class ModulesPageView extends View {

    private static final int MAX_CARDS = 4;

    private HudModel model;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tintPaint = new Paint();

    public ModulesPageView(Context context) {
        super(context);
        paint.setTypeface(Typeface.MONOSPACE);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1f);
    }

    public void setModel(HudModel value) {
        model = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(HudModel.COLOR_BG);
        if (model == null) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        float pad = 32 * d;
        float labelSize = 12f * sp;
        float bodySize = 14f * sp;
        paint.setTextSize(bodySize);
        Paint.FontMetrics bfm = paint.getFontMetrics();
        float bodyH = bfm.descent - bfm.ascent;

        // Section label (12sp caps at 70% text).
        paint.setTextSize(labelSize);
        paint.setColor((HudModel.ALPHA_SECONDARY << 24) | (HudModel.COLOR_TEXT & 0xFFFFFF));
        canvas.drawText("LOADED MODULES", pad, pad + labelSize, paint);
        float y = pad + labelSize + 32 * d;

        int total = model.modules.size();
        if (total == 0) {
            // Explicit empty state.
            paint.setTextSize(bodySize);
            paint.setColor((HudModel.ALPHA_SECONDARY << 24) | (HudModel.COLOR_TEXT & 0xFFFFFF));
            canvas.drawText("(no hook modules loaded)", pad, y + bodyH / 2, paint);
            return;
        }

        int shown = Math.min(total, MAX_CARDS);
        float cardH = 64 * d;
        float gap = 24 * d;
        float cardW = getWidth() - 2 * pad;
        for (int i = 0; i < shown; i++) {
            HudModel.ModuleCard card = model.modules.get(i);
            drawCard(canvas, pad, y, cardW, cardH, card, bodySize, labelSize, d);
            y += cardH + gap;
        }
        if (total > shown) {
            paint.setTextSize(bodySize);
            paint.setColor((HudModel.ALPHA_SECONDARY << 24) | (HudModel.COLOR_TEXT & 0xFFFFFF));
            canvas.drawText("+" + (total - shown) + " more", pad, y + bodyH / 2, paint);
        }
    }

    private void drawCard(Canvas canvas, float x, float y, float w, float h,
                          HudModel.ModuleCard card, float bodySize, float labelSize, float d) {
        // Accent 5 % tint behind loaded cards (60/30/10 rule: cards are
        // structure, only the dot carries the full accent).
        if (card.loaded) {
            tintPaint.setColor(HudModel.COLOR_GREEN);
            tintPaint.setAlpha(13); // 5 %
            canvas.drawRect(x, y, x + w, y + h, tintPaint);
        }
        strokePaint.setColor(HudModel.COLOR_BORDER);
        canvas.drawRect(x, y, x + w, y + h, strokePaint);

        // Status dot (the accent) + module name (14sp body, 100% text).
        float dotR = 4 * d;
        float dotCx = x + 24 * d;
        float cy = y + h / 2;
        dotPaint.setColor(card.loaded ? HudModel.COLOR_GREEN : HudModel.COLOR_DIM);
        canvas.drawCircle(dotCx, cy, dotR, dotPaint);

        paint.setTextSize(bodySize);
        paint.setColor((HudModel.ALPHA_HEADING << 24) | (HudModel.COLOR_TEXT & 0xFFFFFF));
        float nameBaseline = cy + bodySize * 0.35f;
        canvas.drawText(card.name, dotCx + dotR + 12 * d, nameBaseline, paint);

        // Loaded state (12sp label, right-aligned inside the card).
        paint.setTextSize(labelSize);
        paint.setColor((HudModel.ALPHA_SECONDARY << 24) | (HudModel.COLOR_TEXT & 0xFFFFFF));
        String state = card.loaded ? "LOADED" : "OFF";
        float stateW = paint.measureText(state);
        canvas.drawText(state, x + w - 24 * d - stateW, cy + labelSize * 0.35f, paint);
    }
}
