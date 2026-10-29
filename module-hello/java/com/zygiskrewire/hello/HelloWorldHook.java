package com.zygiskrewire.hello;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Registered on the app by the native hello module once the Application is
 * ready. Adds a "Hello World" banner to the top of every resumed activity,
 * proving the ZygiskRewire module pipeline end to end.
 */
public final class HelloWorldHook implements Application.ActivityLifecycleCallbacks {

    public static void attach(Application app) {
        app.registerActivityLifecycleCallbacks(new HelloWorldHook());
    }

    private HelloWorldHook() {}

    @Override
    public void onActivityResumed(Activity activity) {
        FrameLayout banner = new FrameLayout(activity);
        banner.setBackgroundColor(Color.parseColor("#0A0E12"));

        TextView text = new TextView(activity);
        text.setText("Hello World");
        text.setTextColor(Color.parseColor("#00E676"));
        text.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, 24, 0, 24);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        text.setLayoutParams(lp);
        banner.addView(text);

        // Adds to the activity's own content area, above app content.
        activity.addContentView(banner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
    }

    // Remaining lifecycle callbacks intentionally empty.
    @Override public void onActivityCreated(Activity activity, android.os.Bundle savedInstanceState) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
