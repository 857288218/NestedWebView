package com.example.testcustomview.view;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.AnimationUtils;
import android.webkit.WebView;
import android.widget.OverScroller;
import android.widget.ScrollView;

import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.InputDeviceCompat;
import androidx.core.view.NestedScrollingChild2;
import androidx.core.view.NestedScrollingChildHelper;
import androidx.core.view.NestedScrollingParent;
import androidx.core.view.NestedScrollingParentHelper;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityRecordCompat;

// 解决CoordinatorLayout和WebView滑动冲突，以及webview横向滑动和ViewPager等横滑View冲突
public class NestedWebView extends WebView implements NestedScrollingChild2, NestedScrollingParent {

    private static final int INVALID_POINTER = -1;
    private static final String TAG = "NestedWebView";

    private final int[] mScrollOffset = new int[2];
    private final int[] mScrollConsumed = new int[2];

    private int mLastMotionY;
    private int mLastMotionX;
    private final NestedScrollingParentHelper mParentHelper;
    private final NestedScrollingChildHelper mChildHelper;
    private boolean mIsBeingDragged = false;
    private VelocityTracker mVelocityTracker;
    private int mTouchSlop;
    private int mActivePointerId = INVALID_POINTER;
    private int mNestedYOffset;
    private OverScroller mScroller;
    private int mMinimumVelocity;
    private int mMaximumVelocity;
    private static final AccessibilityDelegate ACCESSIBILITY_DELEGATE = new AccessibilityDelegate();
    private ViewGroup horizontallyScrollableParent;

    // 是否是横向滑动
    private boolean mScrollHorizontalEstablish = false;
    // 是否为竖向滑动
    private boolean mScrollVerticalEstablish = false;

    public NestedWebView(Context context) {
        this(context, null);
    }

    public NestedWebView(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.webViewStyle);
    }

    public NestedWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        initScrollView();
        mChildHelper = new NestedScrollingChildHelper(this);
        mParentHelper = new NestedScrollingParentHelper(this);
        setNestedScrollingEnabled(true);
        ViewCompat.setAccessibilityDelegate(this, ACCESSIBILITY_DELEGATE);
    }

    private void initScrollView() {
        mScroller = new OverScroller(getContext());
        final ViewConfiguration configuration = ViewConfiguration.get(getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        if (disallowIntercept) {
            recycleVelocityTracker();
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {

        final int action = ev.getAction();
        if ((action == MotionEvent.ACTION_MOVE) && (mIsBeingDragged)) {
            return true;
        }

        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_MOVE: {
                final int activePointerId = mActivePointerId;
                if (activePointerId == INVALID_POINTER) {
                    break;
                }

                final int pointerIndex = ev.findPointerIndex(activePointerId);
                if (pointerIndex == -1) {
                    Log.e(TAG, "Invalid pointerId=" + activePointerId
                            + " in onInterceptTouchEvent");
                    break;
                }

                final int y = (int) ev.getY(pointerIndex);
                final int yDiff = Math.abs(y - mLastMotionY);
                if (yDiff > mTouchSlop
                        && (getNestedScrollAxes() & ViewCompat.SCROLL_AXIS_VERTICAL) == 0) {
                    mIsBeingDragged = true;
                    mLastMotionY = y;
                    initVelocityTrackerIfNotExists();
                    mVelocityTracker.addMovement(ev);
                    mNestedYOffset = 0;
                    final ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                }
                final int x = (int) ev.getX(pointerIndex);
                final int xDiff = Math.abs(x - mLastMotionX);
                if (xDiff > mTouchSlop
                        && (getNestedScrollAxes() & ViewCompat.SCROLL_AXIS_HORIZONTAL) == 0) {
                    mLastMotionX = x;
                }
                break;
            }

            case MotionEvent.ACTION_DOWN: {
                mLastMotionY = (int) ev.getY();
                mLastMotionX = (int) ev.getX();
                mActivePointerId = ev.getPointerId(0);

                initOrResetVelocityTracker();
                mVelocityTracker.addMovement(ev);

                mScroller.computeScrollOffset();
                mIsBeingDragged = !mScroller.isFinished();
                startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL);
                break;
            }

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                mIsBeingDragged = false;
                mActivePointerId = INVALID_POINTER;
                recycleVelocityTracker();
                if (mScroller.springBack(getScrollX(), getScrollY(), 0, 0, 0, getScrollRange())) {
                    ViewCompat.postInvalidateOnAnimation(this);
                }
                stopNestedScroll();
                break;
            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
        }

        return mIsBeingDragged;
    }


    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean returnValue = false;
        initVelocityTrackerIfNotExists();

        MotionEvent vtev = MotionEvent.obtain(ev);

        final int actionMasked = ev.getActionMasked();

        if (actionMasked == MotionEvent.ACTION_DOWN) {
            mNestedYOffset = 0;
        }
        vtev.offsetLocation(0, mNestedYOffset);

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN: {
                returnValue = super.onTouchEvent(ev);
                if (mIsBeingDragged = !mScroller.isFinished()) {
                    final ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                }

                if (!mScroller.isFinished()) {
                    mScroller.abortAnimation();
                }
                mScrollHorizontalEstablish = false;
                mScrollVerticalEstablish = false;
                mLastMotionY = (int) ev.getY();
                mLastMotionX = (int) ev.getX();
                mActivePointerId = ev.getPointerId(0);
                startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH);
                if (horizontallyScrollableParent == null) {
                    horizontallyScrollableParent = findViewParentIfNeeds(this, 10);
                }
                if (horizontallyScrollableParent != null) {
                    horizontallyScrollableParent.requestDisallowInterceptTouchEvent(true);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                final int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                if (activePointerIndex == -1) {
                    Log.e(TAG, "Invalid pointerId=" + mActivePointerId + " in onTouchEvent");
                    break;
                }

                final int y = (int) ev.getY(activePointerIndex);
                final int x = (int) ev.getX(activePointerIndex);
                int deltaY = mLastMotionY - y;
                int deltaX = mLastMotionX - x;

                // 如果没有确定滑动方向，则重新确定
                if (!mScrollHorizontalEstablish && !mScrollVerticalEstablish) {
                    if (Math.abs(deltaX) > Math.abs(deltaY) * 1.5 && Math.abs(deltaX) > mTouchSlop) {
                        mScrollHorizontalEstablish = true;
                    } else if (Math.abs(deltaY) > Math.abs(deltaX) && Math.abs(deltaY) > mTouchSlop) {
                        mScrollVerticalEstablish = true;
                    }
                }
                if (mScrollHorizontalEstablish) {
                    ev.offsetLocation(0, deltaY);
                    // 横向滑动
                    returnValue = super.onTouchEvent(ev);
                } else {
                    if (dispatchNestedPreScroll(0, deltaY, mScrollConsumed, mScrollOffset,
                            ViewCompat.TYPE_TOUCH)) {
                        deltaY -= mScrollConsumed[1];
                        ev.offsetLocation(0, -mScrollOffset[1]);
                        vtev.offsetLocation(0, -mScrollOffset[1]);
                        mNestedYOffset += mScrollOffset[1];
                    }

                    boolean notMove = mScrollOffset[1] == 0;
                    if (!mIsBeingDragged && Math.abs(deltaY) > mTouchSlop) {
                        final ViewParent parent = getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                        mIsBeingDragged = true;
                        if (deltaY > 0) {
                            deltaY -= mTouchSlop;
                        } else {
                            deltaY += mTouchSlop;
                        }
                    }
                    if (mIsBeingDragged) {
                        mLastMotionY = y - mScrollOffset[1];
                        //借鉴tobiasrohloff的滚动处理  处理抖动问题
                        final int oldY = getScrollY();
                        int newScrollY = Math.max(0, oldY + deltaY);
                        int scrolledDeltaY = newScrollY - oldY;
                        int unconsumedY = deltaY - scrolledDeltaY;
                        if (dispatchNestedScroll(0, scrolledDeltaY, 0, unconsumedY, mScrollOffset,
                                ViewCompat.TYPE_TOUCH)) {
                            mLastMotionY -= mScrollOffset[1];
                            vtev.offsetLocation(0, mScrollOffset[1]);
                            mNestedYOffset += mScrollOffset[1];
                        }
                    }
                    notMove &= (mScrollOffset[1] == 0);
                    if (notMove) {
                        returnValue = super.onTouchEvent(ev);
                    } else {
                        final ViewParent parent = getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (mScrollHorizontalEstablish) {
                    // 横向滑动
                    ev.offsetLocation(0, mLastMotionY - ev.getY());
                    returnValue = super.onTouchEvent(ev);
                } else {
                    if (Math.abs(mNestedYOffset) < mTouchSlop) {
                        returnValue = super.onTouchEvent(ev);
                    } else {
                        ev.setAction(MotionEvent.ACTION_CANCEL);
                        returnValue = super.onTouchEvent(ev);
                    }
                    final VelocityTracker velocityTracker = mVelocityTracker;
                    velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                    int initialVelocity = (int) velocityTracker.getYVelocity();
                    if ((Math.abs(initialVelocity) > mMinimumVelocity)) {
                        flingWithNestedDispatch(-initialVelocity);
                    }
                    mActivePointerId = INVALID_POINTER;
                    endDrag();
                }
                mScrollVerticalEstablish = false;
                mScrollHorizontalEstablish = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                returnValue = true;
                if (mIsBeingDragged && getChildCount() > 0) {
                    if (mScroller.springBack(getScrollX(), getScrollY(), 0, 0, 0,
                            getScrollRange())) {
                        ViewCompat.postInvalidateOnAnimation(this);
                    }
                }
                mActivePointerId = INVALID_POINTER;
                mScrollVerticalEstablish = false;
                mScrollHorizontalEstablish = false;
                endDrag();
                break;
            case MotionEvent.ACTION_POINTER_DOWN: {
                final int index = ev.getActionIndex();
                mLastMotionY = (int) ev.getY(index);
                mLastMotionX = (int) ev.getX(index);
                mActivePointerId = ev.getPointerId(index);
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                mLastMotionY = (int) ev.getY(ev.findPointerIndex(mActivePointerId));
                mLastMotionX = (int) ev.getX(ev.findPointerIndex(mActivePointerId));
                break;
        }

        if (mVelocityTracker != null) {
            mVelocityTracker.addMovement(vtev);
        }
        vtev.recycle();
        return returnValue;
    }


    int getScrollRange() {
        //Using scroll range of webview instead of childs as NestedScrollView does.
        return computeVerticalScrollRange();
    }

    @Override
    protected void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY) {
        if (clampedX) {
            if (horizontallyScrollableParent == null) {
                horizontallyScrollableParent = findViewParentIfNeeds(this, 10);
            }
            if (horizontallyScrollableParent != null) {
                horizontallyScrollableParent.requestDisallowInterceptTouchEvent(false);
            }
        }
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY);
    }

    boolean overScrollByCompat(int deltaX, int deltaY,
                               int scrollX, int scrollY,
                               int scrollRangeX, int scrollRangeY,
                               int maxOverScrollX, int maxOverScrollY,
                               boolean isTouchEvent) {
        final int overScrollMode = getOverScrollMode();
        final boolean canScrollHorizontal =
                computeHorizontalScrollRange() > computeHorizontalScrollExtent();
        final boolean canScrollVertical =
                computeVerticalScrollRange() > computeVerticalScrollExtent();
        final boolean overScrollHorizontal = overScrollMode == View.OVER_SCROLL_ALWAYS
                || (overScrollMode == View.OVER_SCROLL_IF_CONTENT_SCROLLS && canScrollHorizontal);
        final boolean overScrollVertical = overScrollMode == View.OVER_SCROLL_ALWAYS
                || (overScrollMode == View.OVER_SCROLL_IF_CONTENT_SCROLLS && canScrollVertical);

        int newScrollX = scrollX + deltaX;
        if (!overScrollHorizontal) {
            maxOverScrollX = 0;
        }

        int newScrollY = scrollY + deltaY;
        if (!overScrollVertical) {
            maxOverScrollY = 0;
        }

        // Clamp values if at the limits and record
        final int left = -maxOverScrollX;
        final int right = maxOverScrollX + scrollRangeX;
        final int top = -maxOverScrollY;
        final int bottom = maxOverScrollY + scrollRangeY;

        boolean clampedX = false;
        if (newScrollX > right) {
            newScrollX = right;
            clampedX = true;
        } else if (newScrollX < left) {
            newScrollX = left;
            clampedX = true;
        }

        boolean clampedY = false;
        if (newScrollY > bottom) {
            newScrollY = bottom;
            clampedY = true;
        } else if (newScrollY < top) {
            newScrollY = top;
            clampedY = true;
        }

        if (clampedY && !hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH)) {
            mScroller.springBack(newScrollX, newScrollY, 0, 0, 0, getScrollRange());
        }
        // WebView往下滑时,不执行onOverScrolled防止WebView分页加载完下一页后继续惯性下滑
        // 不过这样会导致首次滑动CoordinatorLayout吸顶后不会再继续惯性滑动,如果webview没有分页加载逻辑或可接受加载完下一页还惯性滑动则放开此条件
        if (deltaY < 0) {
            onOverScrolled(newScrollX, newScrollY, clampedX, clampedY);
        }

        return clampedX || clampedY;
    }

    private float mVerticalScrollFactor;

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDeviceCompat.SOURCE_CLASS_POINTER) != 0) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_SCROLL: {
                    if (!mIsBeingDragged) {
                        final float vscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                        if (vscroll != 0) {
                            final int delta = (int) (vscroll * getVerticalScrollFactorCompat());
                            final int range = getScrollRange();
                            int oldScrollY = getScrollY();
                            int newScrollY = oldScrollY - delta;
                            if (newScrollY < 0) {
                                newScrollY = 0;
                            } else if (newScrollY > range) {
                                newScrollY = range;
                            }
                            if (newScrollY != oldScrollY) {
                                super.scrollTo(getScrollX(), newScrollY);
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private float getVerticalScrollFactorCompat() {
        if (mVerticalScrollFactor == 0) {
            TypedValue outValue = new TypedValue();
            final Context context = getContext();
            if (!context.getTheme().resolveAttribute(
                    android.R.attr.listPreferredItemHeight, outValue, true)) {
                throw new IllegalStateException(
                        "Expected theme to define listPreferredItemHeight.");
            }
            mVerticalScrollFactor = outValue.getDimension(
                    context.getResources().getDisplayMetrics());
        }
        return mVerticalScrollFactor;
    }


    private void endDrag() {
        mIsBeingDragged = false;
        recycleVelocityTracker();
        stopNestedScroll();
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = ev.getActionIndex();
        final int pointerId = ev.getPointerId(pointerIndex);
        if (pointerId == mActivePointerId) {
            // This was our active pointer going up. Choose a new
            // active pointer and adjust accordingly.
            // TODO: Make this decision more intelligent.
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mLastMotionY = (int) ev.getY(newPointerIndex);
            mLastMotionX = (int) ev.getX(newPointerIndex);
            mActivePointerId = ev.getPointerId(newPointerIndex);
            if (mVelocityTracker != null) {
                mVelocityTracker.clear();
            }
        }
    }

    private void initOrResetVelocityTracker() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        } else {
            mVelocityTracker.clear();
        }
    }

    private void initVelocityTrackerIfNotExists() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
    }

    private void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    private void flingWithNestedDispatch(int velocityY) {
        final int scrollY = getScrollY();
        final boolean canFling = (scrollY > 0 || velocityY > 0)
                && (scrollY < getScrollRange() || velocityY < 0);
        if (!dispatchNestedPreFling(0, velocityY)) {
            dispatchNestedFling(0, velocityY, canFling);
            fling(velocityY);
        }
    }

    public void fling(int velocityY) {
        flinging = true;
        startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_NON_TOUCH);
        mScroller.fling(getScrollX(), getScrollY(), // start
                0, velocityY, // velocities
                0, 0, // x
                Integer.MIN_VALUE, Integer.MAX_VALUE, // y
                0, 0); // overscroll
        mLastScrollerY = getScrollY();
        ViewCompat.postInvalidateOnAnimation(getRootView());
    }

    private int mLastScrollerY;
    private boolean flinging = false;

    @Override
    public void computeScroll() {
        if (flinging) {
            if (mScroller.computeScrollOffset()) {
                final int y = mScroller.getCurrY();

                int dy = y - mLastScrollerY;

                // Dispatch up to parent
                if (dispatchNestedPreScroll(0, dy, mScrollConsumed, null, ViewCompat.TYPE_NON_TOUCH)) {
                    dy -= mScrollConsumed[1];
                }

                if (dy != 0) {
                    final int range = getScrollRange();
                    final int oldScrollY = getScrollY();

                    overScrollByCompat(0, dy, getScrollX(), oldScrollY, 0, range, 0, 0, false);

                    final int scrolledDeltaY = getScrollY() - oldScrollY;
                    final int unconsumedY = dy - scrolledDeltaY;
                    dispatchNestedScroll(0, scrolledDeltaY, 0, unconsumedY, null, ViewCompat.TYPE_NON_TOUCH);
                }

                // Finally update the scroll positions and post an invalidation
                mLastScrollerY = y;
                ViewCompat.postInvalidateOnAnimation(this);
            } else {
                flinging = false;
                // We can't scroll any more, so stop any indirect scrolling
                if (hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH)) {
                    stopNestedScroll(ViewCompat.TYPE_NON_TOUCH);
                }
                // and reset the scroller y
                mLastScrollerY = 0;
            }
        } else {
            super.computeScroll();
        }
    }

    @Override
    public boolean isNestedScrollingEnabled() {
        return mChildHelper.isNestedScrollingEnabled();
    }

    @Override
    public void setNestedScrollingEnabled(boolean enabled) {
        mChildHelper.setNestedScrollingEnabled(enabled);
    }

    @Override
    public boolean startNestedScroll(int axes) {
        return mChildHelper.startNestedScroll(axes);
    }

    @Override
    public void stopNestedScroll() {
        if (!flinging) {
            mChildHelper.stopNestedScroll();
        }
    }

    @Override
    public boolean hasNestedScrollingParent() {
        return mChildHelper.hasNestedScrollingParent();
    }

    @Override
    public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed,
                                        int[] offsetInWindow) {
        return mChildHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedPreScroll(int dx, int dy, int[] consumed, int[] offsetInWindow) {
        return mChildHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedFling(float velocityX, float velocityY, boolean consumed) {
        return mChildHelper.dispatchNestedFling(velocityX, velocityY, consumed);
    }

    @Override
    public boolean dispatchNestedPreFling(float velocityX, float velocityY) {
        return mChildHelper.dispatchNestedPreFling(velocityX, velocityY);
    }

    @Override
    public int getNestedScrollAxes() {
        return mParentHelper.getNestedScrollAxes();
    }

    @Override
    public boolean startNestedScroll(int axes, int type) {
        return mChildHelper.startNestedScroll(axes, type);
    }

    @Override
    public void stopNestedScroll(int type) {
        mChildHelper.stopNestedScroll(type);
    }

    @Override
    public boolean hasNestedScrollingParent(int type) {
        return mChildHelper.hasNestedScrollingParent(type);
    }

    @Override
    public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed, int dxUnconsumed,
                                        int dyUnconsumed, int[] offsetInWindow, int type) {
        return mChildHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed,
                offsetInWindow, type);
    }

    @Override
    public boolean dispatchNestedPreScroll(int dx, int dy, int[] consumed, int[] offsetInWindow,
                                           int type) {
        return mChildHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type);
    }

    @Override
    public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed,
                               int dyUnconsumed) {
        final int oldScrollY = getScrollY();
        scrollBy(0, dyUnconsumed);
        final int myConsumed = getScrollY() - oldScrollY;
        final int myUnconsumed = dyUnconsumed - myConsumed;
        dispatchNestedScroll(0, myConsumed, 0, myUnconsumed, null);
    }

    @Override
    public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
        dispatchNestedPreScroll(dx, dy, consumed, null);
    }

    @Override
    public boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed) {
        if (!consumed) {
            flingWithNestedDispatch((int) velocityY);
            return true;
        }
        return false;
    }

    @Override
    public boolean onNestedPreFling(View target, float velocityX, float velocityY) {
        return dispatchNestedPreFling(velocityX, velocityY);
    }

    // nested scroll parent
    @Override
    public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes) {
        return (nestedScrollAxes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
    }

    @Override
    public void onNestedScrollAccepted(View child, View target, int nestedScrollAxes) {
        mParentHelper.onNestedScrollAccepted(child, target, nestedScrollAxes);
        startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL);
    }

    @Override
    public void onStopNestedScroll(View target) {
        mParentHelper.onStopNestedScroll(target);
        stopNestedScroll();
    }

    /**
     * Like {@link #scrollTo}, but scroll smoothly instead of immediately.
     *
     * @param x the position where to scroll on the X axis
     * @param y the position where to scroll on the Y axis
     */
    public final void smoothScrollTo(int x, int y) {
        smoothScrollBy(x - getScrollX(), y - getScrollY());
    }

    /**
     * Like {@link View#scrollBy}, but scroll smoothly instead of immediately.
     *
     * @param dx the number of pixels to scroll by on the X axis
     * @param dy the number of pixels to scroll by on the Y axis
     */
    private long mLastScroll;
    static final int ANIMATED_SCROLL_GAP = 250;

    public final void smoothScrollBy(int dx, int dy) {
        long duration = AnimationUtils.currentAnimationTimeMillis() - mLastScroll;
        if (duration > ANIMATED_SCROLL_GAP) {
            final int height = getHeight() - getPaddingBottom() - getPaddingTop();
            final int bottom = getHeight();
            final int maxY = Math.max(0, bottom - height);
            final int scrollY = getScrollY();
            dy = Math.max(0, Math.min(scrollY + dy, maxY)) - scrollY;

            mScroller.startScroll(getScrollX(), scrollY, 0, dy);
            ViewCompat.postInvalidateOnAnimation(this);
        } else {
            if (!mScroller.isFinished()) {
                mScroller.abortAnimation();
            }
            scrollBy(dx, dy);
        }
        mLastScroll = AnimationUtils.currentAnimationTimeMillis();
    }

    static class AccessibilityDelegate extends AccessibilityDelegateCompat {
        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
            if (super.performAccessibilityAction(host, action, arguments)) {
                return true;
            }
            final NestedWebView nsvHost = (NestedWebView) host;
            if (!nsvHost.isEnabled()) {
                return false;
            }
            switch (action) {
                case AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD: {
                    final int viewportHeight = nsvHost.getHeight() - nsvHost.getPaddingBottom()
                            - nsvHost.getPaddingTop();
                    final int targetScrollY = Math.min(nsvHost.getScrollY() + viewportHeight,
                            nsvHost.getScrollRange());
                    if (targetScrollY != nsvHost.getScrollY()) {
                        nsvHost.smoothScrollTo(0, targetScrollY);
                        return true;
                    }
                }
                return false;
                case AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD: {
                    final int viewportHeight = nsvHost.getHeight() - nsvHost.getPaddingBottom()
                            - nsvHost.getPaddingTop();
                    final int targetScrollY = Math.max(nsvHost.getScrollY() - viewportHeight, 0);
                    if (targetScrollY != nsvHost.getScrollY()) {
                        nsvHost.smoothScrollTo(0, targetScrollY);
                        return true;
                    }
                }
                return false;
            }
            return false;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            final NestedWebView nsvHost = (NestedWebView) host;
            info.setClassName(ScrollView.class.getName());
            if (nsvHost.isEnabled()) {
                final int scrollRange = nsvHost.getScrollRange();
                if (scrollRange > 0) {
                    info.setScrollable(true);
                    if (nsvHost.getScrollY() > 0) {
                        info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD);
                    }
                    if (nsvHost.getScrollY() < scrollRange) {
                        info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD);
                    }
                }
            }
        }

        @Override
        public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
            super.onInitializeAccessibilityEvent(host, event);
            final NestedWebView nsvHost = (NestedWebView) host;
            event.setClassName(ScrollView.class.getName());
            final boolean scrollable = nsvHost.getScrollRange() > 0;
            event.setScrollable(scrollable);
            event.setScrollX(nsvHost.getScrollX());
            event.setScrollY(nsvHost.getScrollY());
            AccessibilityRecordCompat.setMaxScrollX(event, nsvHost.getScrollX());
            AccessibilityRecordCompat.setMaxScrollY(event, nsvHost.getScrollRange());
        }
    }

    /**
     * Stop any current scroll
     * #fling(int, int)} or a touch-initiated fling.
     */
    public void stopScroll() {
        if (mScroller != null) {
            mScroller.forceFinished(true);
        }
    }

    private ViewGroup findViewParentIfNeeds(View tag, int depth) {
        if (depth < 0) {
            return null;
        }
        ViewParent parent = tag.getParent();
        if (parent instanceof ViewGroup) {
            if (canScrollHorizontal((ViewGroup) parent)) {
                return (ViewGroup) parent;
            } else {
                return findViewParentIfNeeds((View) parent, depth - 1);
            }
        }
        return null;
    }

    private boolean canScrollHorizontal(View view) {
        return view.canScrollHorizontally(1) || view.canScrollHorizontally(-1);
    }
}
