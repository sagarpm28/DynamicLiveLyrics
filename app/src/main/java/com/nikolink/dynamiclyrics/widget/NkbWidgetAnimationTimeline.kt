package com.nikolink.dynamiclyrics.widget

import android.os.SystemClock
import com.nikolink.dynamiclyrics.ui.NikoFastGaussianBlur
import kotlin.math.PI

/**
 * NkbWidgetAnimationTimeline
 * --------------------------
 * Drives all high-refresh (up to 60 FPS) continuous and event-driven animations inside the Home Screen Widget:
 *   1. Universal NKB (`Niko Kinetic Bloom Blur`) + Elastic Bounce Physics (`420ms`) on all 6
 *      transport buttons (`PREV`, `SEEK_BACK`, `PLAY_PAUSE`, `SEEK_FWD`, `NEXT`, `LIKE`) and `PROVIDER`.
 *   2. Universal NKB Stage Bloom (`460ms`) on Track Change, Provider Switch, and Mode/Theme Switch.
 *   3. Smooth Line Transition (`360ms`): cubic-bezier vertical scroll + scale (`0.93f -> 1.00f`
 *      on entering active line, `1.05f -> 1.00f` on exiting line) + brightness/alpha cross-fade.
 *   4. Continuous monotonic phase accumulators for Wavy Progress Bar, Wavy Ring, and Bokeh Orbs.
 */
object NkbWidgetAnimationTimeline {

    enum class WidgetButtonId {
        PREV,
        SEEK_BACK,
        PLAY_PAUSE,
        SEEK_FWD,
        NEXT,
        LIKE,
        PROVIDER
    }

    private const val BUTTON_NKB_DURATION_MS = 420L
    private const val STAGE_NKB_DURATION_MS = 460L
    private const val LINE_SLIDE_DURATION_MS = 360L

    private val buttonTapStartElapsedMs = LongArray(WidgetButtonId.entries.size) { -10_000L }

    @Volatile
    private var stageNkbStartElapsedMs: Long = -10_000L

    @Volatile
    private var lastObservedTrackKey: String = ""

    @Volatile
    private var lastObservedLineIndex: Int = -1

    @Volatile
    private var lineSlideStartElapsedMs: Long = -10_000L

    @Volatile
    private var lineSlideDirection: Float = 1f

    @Volatile
    private var lastTickElapsedMs: Long = SystemClock.elapsedRealtime()

    @Volatile
    var wavePhaseRad: Float = 0f
        private set

    @Volatile
    var ringPhaseRad: Float = 0f
        private set

    @Volatile
    var bokehTimeSec: Float = 1.4f
        private set

    @Volatile
    var waveAmplitudeRatio: Float = 0f
        private set

    fun triggerButtonNkb(button: WidgetButtonId) {
        buttonTapStartElapsedMs[button.ordinal] = SystemClock.elapsedRealtime()
    }

    fun triggerStageNkbBloom() {
        stageNkbStartElapsedMs = SystemClock.elapsedRealtime()
    }

    fun onTrackOrLineUpdated(trackKey: String, activeLineIndex: Int) {
        val now = SystemClock.elapsedRealtime()
        if (trackKey.isNotBlank() && trackKey != lastObservedTrackKey) {
            if (lastObservedTrackKey.isNotBlank()) {
                stageNkbStartElapsedMs = now
            }
            lastObservedTrackKey = trackKey
            lastObservedLineIndex = activeLineIndex
            return
        }
        if (activeLineIndex != lastObservedLineIndex) {
            if (lastObservedLineIndex >= 0 && activeLineIndex >= 0) {
                lineSlideDirection = if (activeLineIndex > lastObservedLineIndex) 1f else -1f
                lineSlideStartElapsedMs = now
            }
            lastObservedLineIndex = activeLineIndex
        }
    }

    fun stepFrame(isPlaying: Boolean, nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val dtMs = (nowElapsedMs - lastTickElapsedMs).coerceIn(1L, 120L)
        lastTickElapsedMs = nowElapsedMs
        val dtSec = dtMs / 1000f

        val targetAmp = if (isPlaying) 1f else 0f
        val step = dtSec / 0.34f
        waveAmplitudeRatio = if (waveAmplitudeRatio < targetAmp) {
            (waveAmplitudeRatio + step).coerceAtMost(targetAmp)
        } else {
            (waveAmplitudeRatio - step).coerceAtLeast(targetAmp)
        }

        if (isPlaying || waveAmplitudeRatio > 0.01f) {
            val twoPi = (2.0 * PI).toFloat()
            wavePhaseRad = (wavePhaseRad + dtSec * 3.2f) % twoPi
            ringPhaseRad = (ringPhaseRad + dtSec * 2.6f) % twoPi
        }

        val bokehSpeed = if (isPlaying) 1.0f else 0.24f
        bokehTimeSec = (bokehTimeSec + dtSec * bokehSpeed) % 7200f
    }

    fun hasActiveTransientAnimations(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (nowElapsedMs - stageNkbStartElapsedMs in 0..STAGE_NKB_DURATION_MS) return true
        if (nowElapsedMs - lineSlideStartElapsedMs in 0..LINE_SLIDE_DURATION_MS) return true
        if (waveAmplitudeRatio > 0.01f && waveAmplitudeRatio < 0.99f) return true
        for (startMs in buttonTapStartElapsedMs) {
            if (nowElapsedMs - startMs in 0..BUTTON_NKB_DURATION_MS) return true
        }
        return false
    }

    fun getButtonNkbState(
        button: WidgetButtonId,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): Pair<Float, Float> {
        val elapsed = nowElapsedMs - buttonTapStartElapsedMs[button.ordinal]
        if (elapsed !in 0..BUTTON_NKB_DURATION_MS) {
            return 1.0f to 0.0f
        }
        val t = (elapsed.toFloat() / BUTTON_NKB_DURATION_MS.toFloat()).coerceIn(0f, 1f)
        val scale = NikoFastGaussianBlur.computeNkbBounceScale(t, DynamicWidgetSettings.bouncePhysics)
        val bloom = NikoFastGaussianBlur.computeNkbBloomFactor(t, DynamicWidgetSettings.nkbIntensity)
        return scale to bloom
    }

    fun getStageNkbBloomFactor(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Float {
        val elapsed = nowElapsedMs - stageNkbStartElapsedMs
        if (elapsed !in 0..STAGE_NKB_DURATION_MS) return 0f
        val t = (elapsed.toFloat() / STAGE_NKB_DURATION_MS.toFloat()).coerceIn(0f, 1f)
        return NikoFastGaussianBlur.computeNkbBloomFactor(t, DynamicWidgetSettings.nkbIntensity)
    }

    fun getLineSlideOffsetFraction(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Float {
        val elapsed = nowElapsedMs - lineSlideStartElapsedMs
        if (elapsed !in 0..LINE_SLIDE_DURATION_MS) return 0f
        val t = (elapsed.toFloat() / LINE_SLIDE_DURATION_MS.toFloat()).coerceIn(0f, 1f)
        val inv = 1f - t
        val remaining = inv * inv * inv
        return remaining * lineSlideDirection
    }

    fun getLineEntryEasedProgress(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Float {
        val elapsed = nowElapsedMs - lineSlideStartElapsedMs
        if (elapsed !in 0..LINE_SLIDE_DURATION_MS) return 1f
        val t = (elapsed.toFloat() / LINE_SLIDE_DURATION_MS.toFloat()).coerceIn(0f, 1f)
        val inv = 1f - t
        return (1f - inv * inv * inv).coerceIn(0f, 1f)
    }
}
