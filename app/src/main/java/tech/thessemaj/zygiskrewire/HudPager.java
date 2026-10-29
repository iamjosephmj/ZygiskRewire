package tech.thessemaj.zygiskrewire;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/**
 * Minimal programmatic horizontal pager (three full-width pages) — no
 * support-library ViewPager, no new dependencies. Children are laid out
 * side by side; horizontal drags are intercepted and snapped to the
 * nearest page (50 % threshold or fling velocity), vertical motion and
 * plain taps pass through to the page views.
 */
public final class HudPager extends ViewGroup {

    /** Notified (main thread) when the settled page changes. */
    public interface OnPageSettled {
        void onPageSettled(int index);
    }

    private static final int FLING_MIN_VELOCITY = 1200; // px/s

    private int currentPage;
    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private boolean horizontalDrag;
    private boolean dragging;
    private int touchSlop;
    private VelocityTracker velocityTracker;
    private ValueAnimator snapAnimator;
    private OnPageSettled pageSettledListener;

    public HudPager(Context context) {
        super(context);
        touchSlop = Math.round(8 * getResources().getDisplayMetrics().density);
    }

    public void setOnPageSettledListener(OnPageSettled listener) {
        pageSettledListener = listener;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    /** Animates to the given page (used by nothing today; kept symmetric). */
    public void setCurrentPage(int index) {
        int clamped = Math.max(0, Math.min(index, getChildCount() - 1));
        currentPage = clamped;
        smoothTo(clamped * getWidth());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int childHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(childWidth, childHeight);
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.layout(i * width, 0, (i + 1) * width, getHeight());
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginTracking(event);
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!horizontalDrag && Math.abs(dx) > touchSlop
                        && Math.abs(dx) > Math.abs(dy)) {
                    horizontalDrag = true;
                }
                if (horizontalDrag) {
                    return true;
                }
                break;
            default:
                break;
        }
        return horizontalDrag;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginTracking(event);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (velocityTracker != null) {
                    velocityTracker.addMovement(event);
                }
                if (!dragging) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                        dragging = true;
                        lastX = event.getX();
                        // Cancel children presses once a drag starts.
                        cancelLongPress();
                    }
                }
                if (dragging) {
                    float delta = lastX - event.getX();
                    lastX = event.getX();
                    scrollTo(clampScroll((int) (getScrollX() + delta)), 0);
                    return true;
                }
                return false;

            case MotionEvent.ACTION_UP:
                if (dragging) {
                    if (velocityTracker != null) {
                        velocityTracker.addMovement(event);
                        velocityTracker.computeCurrentVelocity(1000);
                    }
                    snapAfterDrag(velocityTracker != null
                            ? velocityTracker.getXVelocity() : 0);
                    endTracking();
                    return true;
                }
                endTracking();
                return false;

            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    snapAfterDrag(0);
                }
                endTracking();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private void beginTracking(MotionEvent event) {
        if (snapAnimator != null) {
            snapAnimator.cancel();
        }
        downX = event.getX();
        downY = event.getY();
        lastX = downX;
        horizontalDrag = false;
        dragging = false;
        velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);
    }

    private void endTracking() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        horizontalDrag = false;
        dragging = false;
    }

    private void snapAfterDrag(float xVelocity) {
        int width = Math.max(getWidth(), 1);
        int target;
        if (Math.abs(xVelocity) > FLING_MIN_VELOCITY) {
            target = xVelocity < 0 ? currentPage + 1 : currentPage - 1;
        } else {
            target = Math.round(getScrollX() / (float) width);
        }
        target = Math.max(0, Math.min(target, getChildCount() - 1));
        currentPage = target;
        smoothTo(target * width);
    }

    private void smoothTo(int x) {
        if (snapAnimator != null) {
            snapAnimator.cancel();
        }
        snapAnimator = ValueAnimator.ofInt(getScrollX(), x);
        snapAnimator.setDuration(220);
        snapAnimator.setInterpolator(new DecelerateInterpolator());
        snapAnimator.addUpdateListener(animation ->
                scrollTo((Integer) animation.getAnimatedValue(), 0));
        snapAnimator.start();
        if (pageSettledListener != null) {
            pageSettledListener.onPageSettled(currentPage);
        }
    }

    private int clampScroll(int x) {
        int max = Math.max(0, (getChildCount() - 1) * Math.max(getWidth(), 1));
        return Math.max(0, Math.min(x, max));
    }

    @Override
    protected void onDetachedFromWindow() {
        if (snapAnimator != null) {
            snapAnimator.cancel();
        }
        super.onDetachedFromWindow();
    }
}
