package com.zygiskrewire.hello;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Registered on the app by the native hello module for the two targeted
 * detector apps. Adds a persistent "CHECKS BYPASSED" banner to the top of
 * every resumed activity (and retroactively to an activity that already
 * resumed before registration — see showNow):
 *
 *   - red rounded card (#D32F2F, subtle #B71C1C border, white content)
 *     spanning the full window width, top edge at y=0 of the window (drawn
 *     under the status bar), content kept below the notch via the
 *     status-bar inset;
 *   - content padded below the status-bar inset via an insets listener;
 *   - slide-down + fade-in entrance (~250ms), checkmark glow pulse twice;
 *   - no auto-hide: it stays until the user dismisses it via the ✕ control
 *     or a vertical swipe-up on the banner card (slide up + fade out ~250ms,
 *     then removed from the view tree).
 *
 * The full-width background strip is not clickable and not focusable, so
 * touches pass through to the app; only the compact card consumes touches
 * (for the swipe-up dismissal) and the ✕ control is clickable.
 */
public final class HelloWorldHook implements Application.ActivityLifecycleCallbacks {

    private static final String TAG = "helloworld-zygisk";
    private static final String BANNER_TAG = "zygiskrewire_hello_banner";

    // Banner card goes red (owner request); everything else stays dark ops.
    private static final int COLOR_CARD_BG = Color.parseColor("#D32F2F");
    private static final int COLOR_CARD_BORDER = Color.parseColor("#B71C1C");
    private static final int COLOR_WHITE = Color.WHITE;
    private static final int COLOR_WHITE_70 = 0xB3FFFFFF; // 70% white
    private static final int COLOR_CHECK_CIRCLE = Color.WHITE;
    private static final int COLOR_CHECK_GLYPH = Color.parseColor("#D32F2F");

    private static final long SLIDE_IN_MS = 250;
    private static final long PULSE_MS = 1000; // two scale-up/down pulses
    private static final long SLIDE_OUT_MS = 250;

    public static void attach(Application app) {
        attach(app, null);
    }

    /** Called by the native module with the matched detector package name. */
    public static void attach(Application app, String packageName) {
        app.registerActivityLifecycleCallbacks(
                new HelloWorldHook(packageName == null ? "unknown" : packageName));
    }

    private final String packageName;

    private HelloWorldHook(String packageName) {
        this.packageName = packageName;
    }

    @Override
    public void onActivityResumed(Activity activity) {
        showNow(activity, packageName);
    }

    /**
     * Shows the banner on an already-created activity. The native worker
     * registers the lifecycle callbacks shortly after the process spawns,
     * which can land after the launch activity's onResume — so the native
     * side calls this for the currently focused activity right after
     * registering.
     */
    public static void showNow(Activity activity, String packageName) {
        // The native retro-show path calls this on its worker thread; view
        // work must happen on the main looper.
        Runnable show = () -> {
            try {
                showNowInner(activity, packageName);
            } catch (Throwable t) {
                android.util.Log.e(TAG, "showNow failed", t);
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            show.run();
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(show);
        }
    }

    private static void showNowInner(Activity activity, String packageName) {
        android.util.Log.i(TAG, "showNow: enter pkg=" + packageName);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            android.util.Log.i(TAG, "showNow: activity not showable, skip");
            return;
        }
        View decorView = activity.getWindow() != null
                ? activity.getWindow().getDecorView() : null;
        if (!(decorView instanceof android.view.ViewGroup)) return;
        final android.view.ViewGroup decor = (android.view.ViewGroup) decorView;
        if (decor.findViewWithTag(BANNER_TAG) != null) {
            return; // banner already up on this activity
        }

        final String pkg = packageName == null ? "unknown" : packageName;
        int dp16 = dp(activity, 16);
        int dp12 = dp(activity, 12);
        int dp8 = dp(activity, 8);

        // Full-width strip at y=0 of the window (added straight onto the
        // decor view so it draws behind/under the status bar). Not
        // clickable, not focusable: touches fall through to the app.
        final FrameLayout overlay = new FrameLayout(activity);
        overlay.setTag(BANNER_TAG);
        overlay.setClickable(false);
        overlay.setFocusable(false);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD_BG);
        bg.setCornerRadius(dp(activity, 16));
        bg.setStroke(dp(activity, 2), COLOR_CARD_BORDER);
        overlay.setBackground(bg);

        // Content row: checkmark circle + texts + ✕. Positioned fully below
        // the status-bar inset (+8dp) so nothing overlaps the punch-hole
        // camera; the card background still extends up behind the status bar.
        final LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp16, statusBarHeight(activity) + dp8, dp16, dp12);

        TextView check = new TextView(activity);
        check.setText("✓");
        check.setTextColor(COLOR_CHECK_GLYPH);
        check.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        check.setGravity(Gravity.CENTER);
        check.setIncludeFontPadding(false);
        int circle = dp(activity, 28);
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(COLOR_CHECK_CIRCLE);
        check.setBackground(circleBg);
        check.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        check.setShadowLayer(dp(activity, 8), 0f, 0f, COLOR_WHITE); // glow
        LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(circle, circle);
        check.setLayoutParams(checkLp);
        row.addView(check);

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText("CHECKS BYPASSED");
        title.setTextColor(COLOR_WHITE);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        TextView subline = new TextView(activity);
        subline.setText("ZygiskRewire · " + pkg);
        subline.setTextColor(COLOR_WHITE_70);
        subline.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        subline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        textCol.addView(title);
        textCol.addView(subline);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = dp12;
        textCol.setLayoutParams(textLp);
        row.addView(textCol);

        // ✕ dismissal control, >= 44dp tap target.
        TextView close = new TextView(activity);
        close.setText("✕");
        close.setTextColor(COLOR_WHITE);
        close.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        close.setGravity(Gravity.CENTER);
        int tap = dp(activity, 44);
        close.setMinimumWidth(tap);
        close.setMinimumHeight(tap);
        close.setPadding(dp(activity, 8), 0, dp(activity, 8), 0);
        close.setOnClickListener(v -> dismiss(activity, overlay));
        row.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        // Vertical swipe-up on the card also dismisses. The card consumes
        // its own gesture; the full-width strip around it stays pass-through.
        final float[] swipeStart = {-1f};
        row.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    swipeStart[0] = event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (swipeStart[0] >= 0f
                            && (swipeStart[0] - event.getY()) > dp(activity, 64)) {
                        swipeStart[0] = -1f;
                        dismiss(activity, overlay);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    swipeStart[0] = -1f;
                    return true;
                default:
                    return false;
            }
        });

        overlay.addView(row, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL));

        // Pad the content below the status-bar inset (+8dp, so no text/icon
        // overlaps the punch-hole camera); the background stays at y=0
        // behind the status bar.
        final int hPad = dp16;
        final int vPad = dp12;
        overlay.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.statusBars()).top;
            } else {
                top = insets.getSystemWindowInsetTop();
            }
            row.setPadding(hPad, top + dp8, hPad, vPad);
            return insets;
        });

        // Attach to the decor view directly: its frame starts at y=0 of the
        // window, above the (possibly inset) content area.
        decor.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));
        overlay.requestApplyInsets();
        android.util.Log.i(TAG, "showNow: banner attached to decor");

        animateIn(overlay, check);
        android.util.Log.i(TAG, "showNow: entrance animation started");
    }

    private static void animateIn(final View overlay, final View check) {
        overlay.setAlpha(0f);
        // Fully off-screen before layout is measurable; animate after the
        // first layout pass so the offset matches the real height.
        overlay.setTranslationY(-dp(overlay.getContext(), 300));
        overlay.post(() -> {
            overlay.setTranslationY(-overlay.getHeight());
            overlay.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(SLIDE_IN_MS)
                    .withEndAction(() -> pulse(check))
                    .start();
        });
    }

    /** Dismissal: slide up + fade out, then remove from the view tree. */
    private static void dismiss(final Activity activity, final View overlay) {
        if (overlay.getParent() == null) return;
        overlay.animate()
                .alpha(0f)
                .translationY(-overlay.getHeight() - dp(activity, 24))
                .setDuration(SLIDE_OUT_MS)
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        if (overlay.getParent() instanceof android.view.ViewGroup) {
                            ((android.view.ViewGroup) overlay.getParent()).removeView(overlay);
                        }
                    }
                })
                .start();
    }

    /** Two glow pulses: scale the checkmark circle up and back down twice. */
    private static void pulse(View check) {
        ObjectAnimator sx = ObjectAnimator.ofFloat(
                check, "scaleX", 1f, 1.35f, 1f, 1.35f, 1f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(
                check, "scaleY", 1f, 1.35f, 1f, 1.35f, 1f);
        sx.setDuration(PULSE_MS);
        sy.setDuration(PULSE_MS);
        sx.start();
        sy.start();
    }

    /**
     * Status-bar height from the framework dimen, used as the notch-safe
     * initial top padding until the insets listener supplies the real inset.
     */
    private static int statusBarHeight(Activity activity) {
        android.content.res.Resources res = activity.getResources();
        int id = res.getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? res.getDimensionPixelSize(id) : dp(activity, 32);
    }

    private static int dp(Activity activity, int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v,
                activity.getResources().getDisplayMetrics());
    }

    private static int dp(android.content.Context ctx, int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }

    // Remaining lifecycle callbacks intentionally empty.
    @Override public void onActivityCreated(Activity activity, android.os.Bundle savedInstanceState) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
