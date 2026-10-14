/*
 * Copyright (C) 2026 The MosaicOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.profileinbox;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.util.function.BooleanSupplier;

public final class ProfileInboxPageLayout extends FrameLayout {
    private final int mSlop;
    private final int mMaximumVelocity;
    private final float mFlingVelocity;
    private final float mDensity;
    private final Rect mEditorBounds = new Rect();
    private final int[] mLocation = new int[2];
    private BooleanSupplier mCanSwipe = () -> false;
    private Runnable mOnSwipe = () -> {};
    private VelocityTracker mVelocity;
    private boolean mTracking;
    private boolean mIntercepted;
    private boolean mShowingInbox;
    private float mDownX;
    private float mDownY;

    public ProfileInboxPageLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        mDensity = getResources().getDisplayMetrics().density;
        mSlop = configuration.getScaledTouchSlop();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mFlingVelocity = Math.max(configuration.getScaledMinimumFlingVelocity(), 800 * mDensity);
    }

    void setPageSwipe(BooleanSupplier canSwipe, Runnable onSwipe) {
        mCanSwipe = canSwipe;
        mOnSwipe = onSwipe;
    }

    void setShowingInbox(boolean inbox) {
        mShowingInbox = inbox;
    }

    void resetGesture() {
        mTracking = false;
        mIntercepted = false;
        if (mVelocity != null) mVelocity.recycle();
        mVelocity = null;
    }

    private int swipeDirection(boolean inbox) {
        int direction = inbox ? 1 : -1;
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL ? -direction : direction;
    }

    private boolean inBackEdge(MotionEvent event) {
        View root = getRootView();
        root.getLocationOnScreen(mLocation);
        float x = event.getRawX() - mLocation[0];
        WindowInsets insets = getRootWindowInsets();
        Insets gestures = insets == null ? Insets.NONE : insets.getInsets(WindowInsets.Type.systemGestures());
        return x <= Math.max(24 * mDensity, gestures.left)
                || x >= root.getWidth() - Math.max(24 * mDensity, gestures.right);
    }

    private boolean inEditor(View view, MotionEvent event) {
        if (view.getVisibility() != VISIBLE) return false;
        if (view instanceof EditText) {
            view.getLocationOnScreen(mLocation);
            mEditorBounds.set(mLocation[0], mLocation[1],
                    mLocation[0] + view.getWidth(), mLocation[1] + view.getHeight());
            int margin = Math.round(24 * mDensity);
            mEditorBounds.inset(-margin, -margin);
            return mEditorBounds.contains(Math.round(event.getRawX()), Math.round(event.getRawY()));
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (inEditor(group.getChildAt(i), event)) return true;
            }
        }
        return false;
    }

    private boolean allowed(MotionEvent event) {
        int obscured = MotionEvent.FLAG_WINDOW_IS_OBSCURED | MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED;
        return event.getPointerCount() == 1 && (event.getFlags() & obscured) == 0 && mCanSwipe.getAsBoolean();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!allowed(event)) resetGesture();
        boolean handled = super.dispatchTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) resetGesture();
        return handled;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            resetGesture();
            AccessibilityManager accessibility = getContext().getSystemService(AccessibilityManager.class);
            if (!allowed(event) || inBackEdge(event) || inEditor(this, event)
                    || accessibility != null && accessibility.isTouchExplorationEnabled()) return false;
            mDownX = event.getX();
            mDownY = event.getY();
            mTracking = true;
            mVelocity = VelocityTracker.obtain();
            mVelocity.addMovement(event);
        } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            resetGesture();
        } else if (mTracking) {
            if (!allowed(event)
                    || event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout()) {
                resetGesture();
                return false;
            }
            mVelocity.addMovement(event);
            float dx = event.getX() - mDownX;
            float dy = event.getY() - mDownY;
            if (Math.max(Math.abs(dx), Math.abs(dy)) > mSlop) {
                if (Math.abs(dx) <= 2 * Math.abs(dy) || dx * swipeDirection(mShowingInbox) <= 0) {
                    resetGesture();
                } else if (Math.abs(dx) >= 2 * mSlop) {
                    mIntercepted = true;
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mIntercepted) return super.onTouchEvent(event);
        if (!allowed(event) || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            resetGesture();
            return true;
        }
        mVelocity.addMovement(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float dx = event.getX() - mDownX;
            float dy = event.getY() - mDownY;
            mVelocity.computeCurrentVelocity(1000, mMaximumVelocity);
            float vx = mVelocity.getXVelocity();
            float vy = mVelocity.getYVelocity();
            boolean distance = Math.abs(dx) >= Math.max(48 * mDensity, getWidth() * 0.18f);
            boolean fling = Math.abs(dx) >= 2 * mSlop && Math.abs(vx) >= mFlingVelocity
                    && Math.abs(vx) > 2 * Math.abs(vy) && dx * vx > 0;
            boolean switchPage = dx * swipeDirection(mShowingInbox) > 0 && Math.abs(dx) > 2 * Math.abs(dy)
                    && (distance || fling);
            resetGesture();
            if (switchPage) mOnSwipe.run();
        }
        return true;
    }

    void animatePageChange(boolean inbox) {
        if (getChildCount() == 0 || !ValueAnimator.areAnimatorsEnabled()) return;
        View page = getChildAt(0);
        page.animate().cancel();
        page.setTranslationX(swipeDirection(inbox) * Math.min(getWidth() * 0.18f, 64 * mDensity));
        page.animate().translationX(0).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
    }

    @Override
    protected void onDetachedFromWindow() {
        resetGesture();
        super.onDetachedFromWindow();
    }
}
