package com.wsvdmeer.pwncompanion.utils

import android.content.Context
import android.util.DisplayMetrics

/**
 * Dimension utility extensions for converting between DP and PX.
 * Used for responsive UI layout calculations.
 */
object DimensionExtensions {

    /**
     * Convert density-independent pixels (DP) to physical pixels (PX).
     * Accounts for device screen density.
     *
     * @param context Android context for accessing display metrics
     * @param dp Density-independent pixels
     * @return Physical pixels adjusted for device density
     */
    fun dpToPx(context: Context, dp: Float): Int {
        val displayMetrics = context.resources.displayMetrics
        return (dp * displayMetrics.density).toInt()
    }

    /**
     * Convert density-independent pixels (DP) to physical pixels (PX) as Float.
     *
     * @param context Android context for accessing display metrics
     * @param dp Density-independent pixels
     * @return Physical pixels as float
     */
    fun dpToPxFloat(context: Context, dp: Float): Float {
        val displayMetrics = context.resources.displayMetrics
        return dp * displayMetrics.density
    }

    /**
     * Convert physical pixels (PX) to density-independent pixels (DP).
     *
     * @param context Android context for accessing display metrics
     * @param px Physical pixels
     * @return Density-independent pixels
     */
    fun pxToDp(context: Context, px: Float): Int {
        val displayMetrics = context.resources.displayMetrics
        return (px / displayMetrics.density).toInt()
    }

    /**
     * Get device screen density (pixels per inch).
     *
     * @param context Android context
     * @return Density value (1.0 = baseline 160 dpi)
     */
    fun getScreenDensity(context: Context): Float {
        return context.resources.displayMetrics.density
    }

    /**
     * Get device screen density category.
     *
     * @param context Android context
     * @return Density category (ldpi, mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi)
     */
    fun getScreenDensityCategory(context: Context): String {
        val density = context.resources.displayMetrics.densityDpi
        return when (density) {
            DisplayMetrics.DENSITY_LOW -> "ldpi"
            DisplayMetrics.DENSITY_MEDIUM -> "mdpi"
            DisplayMetrics.DENSITY_HIGH -> "hdpi"
            DisplayMetrics.DENSITY_XHIGH -> "xhdpi"
            DisplayMetrics.DENSITY_XXHIGH -> "xxhdpi"
            DisplayMetrics.DENSITY_XXXHIGH -> "xxxhdpi"
            else -> "unknown"
        }
    }
}
