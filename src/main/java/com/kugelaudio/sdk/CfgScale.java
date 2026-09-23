package com.kugelaudio.sdk;

/**
 * Classifier-free guidance scale bounds and clamping.
 *
 * <p>Values outside {@code [MIN, MAX]} are clamped into the band (both
 * client-side and by the server).
 */
final class CfgScale {

    static final double MIN = 1.2;
    static final double MAX = 2.5;

    private CfgScale() {}

    /** Clamp {@code cfgScale} into [1.2, 2.5]. */
    static double clamp(double cfgScale) {
        return Math.min(MAX, Math.max(MIN, cfgScale));
    }
}
