/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.DrawableWrapper
import android.util.PathParser
import com.android.settingslib.graph.ThemedBatteryDrawable
import com.android.systemui.res.R
import com.android.systemui.battery.BatterySpecs.BATTERY_HEIGHT
import com.android.systemui.battery.BatterySpecs.BATTERY_HEIGHT_WITH_SHIELD
import com.android.systemui.battery.BatterySpecs.BATTERY_WIDTH
import com.android.systemui.battery.BatterySpecs.BATTERY_WIDTH_WITH_SHIELD
import com.android.systemui.battery.BatterySpecs.SHIELD_LEFT_OFFSET
import com.android.systemui.battery.BatterySpecs.SHIELD_STROKE
import com.android.systemui.battery.BatterySpecs.SHIELD_TOP_OFFSET

class AccessorizedBatteryDrawable(
    private val context: Context,
    frameColor: Int,
) : DrawableWrapper(ThemedBatteryDrawable(context, frameColor)) {
    enum class AccessoryKind { NONE, SHIELD, PAUSE }

    private val mainBatteryDrawable: ThemedBatteryDrawable
        get() = drawable as ThemedBatteryDrawable

    private val shieldPath = Path()
    private val pausePath = Path()
    private val scaledAccessory = Path()
    private val scaleMatrix = Matrix()

    private var accessoryLeftOffsetScaled = SHIELD_LEFT_OFFSET
    private var accessoryTopOffsetScaled = SHIELD_TOP_OFFSET

    private var density = context.resources.displayMetrics.density

    private val dualTone =
        context.resources.getBoolean(com.android.internal.R.bool.config_batterymeterDualTone)

    private val accessoryTransparentOutlinePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
            p.color = Color.TRANSPARENT
            p.strokeWidth = ThemedBatteryDrawable.PROTECTION_MIN_STROKE_WIDTH
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            p.style = Paint.Style.FILL_AND_STROKE
        }

    private val accessoryPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
            p.color = Color.MAGENTA
            p.style = Paint.Style.FILL
            p.isDither = true
        }

    init {
        loadPaths()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        updateSizes()
    }

    var accessoryKind: AccessoryKind = AccessoryKind.NONE
        set(value) {
            if (field == value) return
            field = value
            updateSizes()
            postInvalidate()
        }

    private val hasAccessory: Boolean
        get() = accessoryKind != AccessoryKind.NONE

    private fun updateSizes() {
        val b = bounds
        if (b.isEmpty) {
            return
        }

        val mainWidth = BatterySpecs.getMainBatteryWidth(b.width().toFloat(), hasAccessory)
        val mainHeight = BatterySpecs.getMainBatteryHeight(b.height().toFloat(), hasAccessory)

        drawable?.setBounds(
            b.left,
            b.top,
            /* right= */ b.left + mainWidth.toInt(),
            /* bottom= */ b.top + mainHeight.toInt()
        )

        if (hasAccessory) {
            val sx = b.right / BATTERY_WIDTH_WITH_SHIELD
            val sy = b.bottom / BATTERY_HEIGHT_WITH_SHIELD
            scaleMatrix.setScale(sx, sy)
            val path = if (accessoryKind == AccessoryKind.PAUSE) pausePath else shieldPath
            path.transform(scaleMatrix, scaledAccessory)

            accessoryLeftOffsetScaled = sx * SHIELD_LEFT_OFFSET
            accessoryTopOffsetScaled = sy * SHIELD_TOP_OFFSET

            val scaledStrokeWidth =
                (sx * SHIELD_STROKE).coerceAtLeast(
                    ThemedBatteryDrawable.PROTECTION_MIN_STROKE_WIDTH
                )
            accessoryTransparentOutlinePaint.strokeWidth = scaledStrokeWidth
        }
    }

    override fun getIntrinsicHeight(): Int {
        val height =
            if (hasAccessory) {
                BATTERY_HEIGHT_WITH_SHIELD
            } else {
                BATTERY_HEIGHT
            }
        return (height * density).toInt()
    }

    override fun getIntrinsicWidth(): Int {
        val width =
            if (hasAccessory) {
                BATTERY_WIDTH_WITH_SHIELD
            } else {
                BATTERY_WIDTH
            }
        return (width * density).toInt()
    }

    override fun draw(c: Canvas) {
        c.saveLayer(null, null)
        super.draw(c)

        if (hasAccessory) {
            c.translate(accessoryLeftOffsetScaled, accessoryTopOffsetScaled)
            // Clear the battery behind the accessory so its silhouette stays legible.
            c.drawPath(scaledAccessory, accessoryTransparentOutlinePaint)
            c.drawPath(scaledAccessory, accessoryPaint)
        }
        c.restore()
    }

    override fun getOpacity(): Int {
        return PixelFormat.OPAQUE
    }

    override fun setAlpha(p0: Int) {
        // Unused internally -- see [ThemedBatteryDrawable.setAlpha].
    }

    override fun setColorFilter(colorfilter: ColorFilter?) {
        super.setColorFilter(colorFilter)
        accessoryPaint.colorFilter = colorFilter
    }

    /** Sets whether the battery is currently charging. */
    fun setCharging(charging: Boolean) {
        mainBatteryDrawable.charging = charging
    }

    /** Returns whether the battery is currently charging. */
    fun getCharging(): Boolean {
        return mainBatteryDrawable.charging
    }

    /** Sets the current level (out of 100) of the battery. */
    fun setBatteryLevel(level: Int) {
        mainBatteryDrawable.setBatteryLevel(level)
    }

    /** Sets whether power save is enabled. */
    fun setPowerSaveEnabled(powerSaveEnabled: Boolean) {
        mainBatteryDrawable.powerSaveEnabled = powerSaveEnabled
    }

    /** Returns whether power save is currently enabled. */
    fun getPowerSaveEnabled(): Boolean {
        return mainBatteryDrawable.powerSaveEnabled
    }

    /** Sets the colors to use for the icon. */
    fun setColors(fgColor: Int, bgColor: Int, singleToneColor: Int) {
        accessoryPaint.color = if (dualTone) fgColor else singleToneColor
        mainBatteryDrawable.setColors(fgColor, bgColor, singleToneColor)
    }

    /** Notifies this drawable that the density might have changed. */
    fun notifyDensityChanged() {
        density = context.resources.displayMetrics.density
    }

    private fun loadPaths() {
        val shieldPathString = context.resources.getString(R.string.config_batterymeterShieldPath)
        shieldPath.set(PathParser.createPathFromPathData(shieldPathString))
        val pausePathString = context.resources.getString(R.string.config_batterymeterPausePath)
        pausePath.set(PathParser.createPathFromPathData(pausePathString))
    }

    private val invalidateRunnable: () -> Unit = { invalidateSelf() }

    private fun postInvalidate() {
        unscheduleSelf(invalidateRunnable)
        scheduleSelf(invalidateRunnable, 0)
    }
}
