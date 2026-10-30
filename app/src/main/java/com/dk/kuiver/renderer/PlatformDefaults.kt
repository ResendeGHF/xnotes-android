package com.dk.kuiver.renderer

/**
 * Platform-specific defaults for KuiverViewer configuration.
 *
 * Vendored note: upstream declares this as `expect` with per-platform `actuals`;
 * this copy inlines the Android values so the library builds as plain app sources.
 */
internal object PlatformDefaults {
    /**
     * Default pan velocity for scroll/trackpad gestures, in dp per scroll unit.
     * Web platforms use a lower value to compensate for higher sensitivity.
     */
    val defaultPanVelocity: Float = 15f

    /**
     * Default zoom sensitivity for scroll/trackpad gestures, in scale exponent per scroll unit.
     * Web platforms use a lower value to compensate for higher sensitivity.
     */
    val defaultZoomVelocity: Float = 0.05f
}
