package com.cy.xposedshot.ui

import android.app.Activity
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors

/**
 * 三个页面共用的界面工具。
 *
 * targetSdk 37 强制 edge-to-edge，根布局必须自己吃掉 insets，否则标题会被状态栏压住。
 */
object Ui {

    /** 套用壁纸动态取色；必须在 setContentView 之前调用。 */
    fun applyDynamicColor(activity: Activity) {
        DynamicColors.applyToActivityIfAvailable(activity)
    }

    fun applySystemInsets(activity: Activity, target: View, basePadding: Int) {
        ViewCompat.setOnApplyWindowInsetsListener(activity.window.decorView) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            target.setPadding(
                basePadding + bars.left,
                basePadding + bars.top,
                basePadding + bars.right,
                basePadding + bars.bottom,
            )
            insets
        }
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.setDecorFitsSystemWindows(false)
        }
        ViewCompat.requestApplyInsets(activity.window.decorView)
    }

    /** Material 3 的标题：字号大、字重中等。 */
    fun title(activity: Activity, text: String): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = 32f
            setTextColor(color(activity, com.google.android.material.R.attr.colorOnSurface))
        }

    fun body(activity: Activity, text: String, sizeSp: Float = 14f): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = sizeSp
            setLineSpacing(0f, 1.3f)
            setTextColor(color(activity, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }

    fun color(activity: Activity, attr: Int, fallback: Int = 0xFF888888.toInt()): Int =
        runCatching { MaterialColors.getColor(activity.window.decorView, attr, fallback) }
            .getOrDefault(fallback)
}
