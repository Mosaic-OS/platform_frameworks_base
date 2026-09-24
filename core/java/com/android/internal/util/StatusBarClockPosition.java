package com.android.internal.util;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.Surface;

public final class StatusBarClockPosition {
    public static final String SETTING = "status_bar_clock_position";
    public static final String LEFT = "left";
    public static final String CENTER = "center";
    public static final String RIGHT = "right";

    private StatusBarClockPosition() {}

    public static boolean isCenterAllowed(Context context) {
        try {
            final DisplayManager manager = context.getSystemService(DisplayManager.class);
            final Display display = manager == null ? null
                    : manager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null || display.getType() != Display.TYPE_INTERNAL) return false;
            final DisplayInfo info = new DisplayInfo();
            if (!display.getDisplayInfo(info) || info.logicalWidth <= 0
                    || info.logicalHeight <= 0) return false;
            if (info.displayCutout == null) return true;
            final DisplayCutout naturalCutout = info.displayCutout.getRotated(
                    info.logicalWidth, info.logicalHeight, info.rotation, Surface.ROTATION_0);
            final Rect top = naturalCutout.getBoundingRectTop();
            final float midpoint = info.getNaturalWidth() / 2f;
            return top.isEmpty() || top.left > midpoint || top.right < midpoint;
        } catch (RuntimeException e) {
            // Missing display information must never permit a clock under a cutout.
            return false;
        }
    }

    public static String resolve(Context context, String value) {
        if (RIGHT.equals(value)) return RIGHT;
        if (CENTER.equals(value) && isCenterAllowed(context)) return CENTER;
        return LEFT;
    }
}
