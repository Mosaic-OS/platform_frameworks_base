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

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;

import com.android.systemui.res.R;

public final class ProfileInboxTabs extends LinearLayout {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPill = new Path();
    private final RectF mBounds = new RectF();
    private final ColorStateList mBackground;
    private final int mOutline;
    private final float mStroke;

    public ProfileInboxTabs(Context context, AttributeSet attrs) {
        super(context, attrs);
        mBackground = context.getColorStateList(R.color.profile_inbox_segment_background);
        mOutline = context.getColor(R.color.profile_inbox_outline);
        mStroke = getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        TypedArray attributes = getContext().obtainStyledAttributes(new int[] {android.R.attr.colorControlHighlight});
        ColorStateList ripple = ColorStateList.valueOf(attributes.getColor(0, Color.TRANSPARENT));
        attributes.recycle();
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setBackground(new RippleDrawable(ripple, null, new ColorDrawable(Color.WHITE)));
            getChildAt(i).setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setCheckable(true);
                    info.setChecked(host.isSelected());
                }

                @Override
                public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
                    super.onInitializeAccessibilityEvent(host, event);
                    event.setChecked(host.isSelected());
                }
            });
        }
    }

    void setInboxSelected(boolean inbox) {
        getChildAt(0).setSelected(!inbox);
        getChildAt(1).setSelected(inbox);
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        float radius = Math.min(getWidth(), getHeight()) / 2f;
        mBounds.set(0, 0, getWidth(), getHeight());
        mPill.rewind();
        mPill.addRoundRect(mBounds, radius, radius, Path.Direction.CW);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mBackground.getDefaultColor());
        canvas.drawPath(mPill, mPaint);
        int save = canvas.save();
        canvas.clipPath(mPill);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.isSelected()) {
                mPaint.setColor(mBackground.getColorForState(child.getDrawableState(), mBackground.getDefaultColor()));
                canvas.drawRect(child.getLeft(), 0, child.getRight(), getHeight(), mPaint);
            }
        }
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
        mPaint.setColor(mOutline);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(mStroke);
        mBounds.inset(mStroke / 2, mStroke / 2);
        float strokeRadius = Math.max(0, radius - mStroke / 2);
        canvas.drawRoundRect(mBounds, strokeRadius, strokeRadius, mPaint);
        float divider = getLayoutDirection() == LAYOUT_DIRECTION_RTL
                ? getChildAt(0).getLeft() : getChildAt(0).getRight();
        canvas.drawLine(divider, mStroke, divider, getHeight() - mStroke, mPaint);
    }
}
