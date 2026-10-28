package com.nikolink.dynamiclyrics.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * NikoFastGaussianBlur
 * --------------------
 * Ported directly from NikoLink's 20-bit fixed-point reciprocal separable Gaussian blur engine
 * (`NikoRustHybridBridge` & `NikoGlassmorphismEngine`).
 *
 * Powers:
 *  1. Xiaomi HyperOS Frosted Blur backdrop generation from Album Art / Wallpaper in < 1.5ms.
 *  2. Universal NKB (`Niko Kinetic Bloom Blur`) inside both the 30 FPS Home Screen Widget
 *     and the DynamicLiveLyrics Studio App (`22px -> 14px -> 8px -> 3.5px -> sharp`).
 */
object NikoFastGaussianBlur {

    private val scratchLock = Any()
    private var scratchA = IntArray(0)
    private var scratchB = IntArray(0)
    private var dvTable = IntArray(0)
    private var cachedRadius = -1

    /**
     * Generates a scaled, vibrancy-boosted, multi-pass HyperOS Frosted Gaussian Blurred bitmap
     * suitable for widget and card backgrounds.
     */
    fun createHyperOsFrostedBackdrop(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        blurRadius: Int = 22,
        saturationBoost: Float = 1.35f
    ): Bitmap {
        val sampleW = (targetWidth / 4).coerceIn(32, 160)
        val sampleH = (targetHeight / 4).coerceIn(32, 160)
        val small = Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(small)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        // Center-crop source into small buffer
        val srcW = source.width.coerceAtLeast(1)
        val srcH = source.height.coerceAtLeast(1)
        val scale = max(sampleW.toFloat() / srcW.toFloat(), sampleH.toFloat() / srcH.toFloat())
        val cropW = (sampleW / scale).roundToInt().coerceIn(1, srcW)
        val cropH = (sampleH / scale).roundToInt().coerceIn(1, srcH)
        val srcLeft = ((srcW - cropW) / 2).coerceAtLeast(0)
        val srcTop = ((srcH - cropH) / 2).coerceAtLeast(0)

        canvas.drawBitmap(
            source,
            Rect(srcLeft, srcTop, srcLeft + cropW, srcTop + cropH),
            Rect(0, 0, sampleW, sampleH),
            paint
        )

        if (blurRadius > 0) {
            blurBitmapInPlace(small, blurRadius.coerceIn(1, 38), passes = 3, saturationBoost = saturationBoost)
        }
        return small
    }

    /**
     * Creates a downscaled NKB (`Niko Kinetic Bloom Blur`) snapshot of a rendered bitmap
     * for kinetic bloom transitions (`nkbProgress` in `0f..1f`, where `1f` is peak bloom).
     */
    fun applyNkbBloomOverlay(
        source: Bitmap,
        nkbIntensity: Float,
        bloomFactor: Float
    ): Bitmap? {
        val effective = (nkbIntensity * bloomFactor).coerceIn(0f, 1.5f)
        if (effective <= 0.03f) return null
        val w = (source.width / 3).coerceIn(48, 240)
        val h = (source.height / 3).coerceIn(48, 240)
        val bloomBmp = Bitmap.createScaledBitmap(source, w, h, true)
        val radius = (effective * 16f).roundToInt().coerceIn(1, 24)
        blurBitmapInPlace(bloomBmp, radius, passes = 2, saturationBoost = 1.18f)
        return bloomBmp
    }

    /**
     * In-place 20-bit fixed-point separable box/Gaussian approximation blur.
     */
    fun blurBitmapInPlace(
        bitmap: Bitmap,
        radius: Int,
        passes: Int = 3,
        saturationBoost: Float = 1.0f
    ) {
        val r = radius.coerceIn(1, 45)
        val w = bitmap.width
        val h = bitmap.height
        val wh = w * h
        if (w <= 2 || h <= 2) return

        synchronized(scratchLock) {
            if (scratchA.size < wh) {
                scratchA = IntArray(wh)
                scratchB = IntArray(wh)
            }
            val div = 2 * r + 1
            if (cachedRadius != r || dvTable.size < 256 * div) {
                cachedRadius = r
                dvTable = IntArray(256 * div)
                val invDiv = ((1 shl 20) + div / 2) / div
                for (i in 0 until 256 * div) {
                    dvTable[i] = (i * invDiv) ushr 20
                }
            }

            val pix = scratchA
            val tmp = scratchB
            val dv = dvTable
            bitmap.getPixels(pix, 0, w, 0, 0, w, h)

            repeat(passes.coerceIn(1, 4)) {
                // Horizontal pass: pix -> tmp
                for (y in 0 until h) {
                    val rowOffset = y * w
                    var rSum = 0
                    var gSum = 0
                    var bSum = 0
                    var aSum = 0
                    for (i in -r..r) {
                        val px = pix[rowOffset + i.coerceIn(0, w - 1)]
                        aSum += (px ushr 24) and 0xFF
                        rSum += (px ushr 16) and 0xFF
                        gSum += (px ushr 8) and 0xFF
                        bSum += px and 0xFF
                    }
                    for (x in 0 until w) {
                        tmp[rowOffset + x] = (dv[aSum] shl 24) or (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum]
                        val removeIdx = (x - r).coerceIn(0, w - 1)
                        val addIdx = (x + r + 1).coerceIn(0, w - 1)
                        val pOut = pix[rowOffset + removeIdx]
                        val pIn = pix[rowOffset + addIdx]
                        aSum += ((pIn ushr 24) and 0xFF) - ((pOut ushr 24) and 0xFF)
                        rSum += ((pIn ushr 16) and 0xFF) - ((pOut ushr 16) and 0xFF)
                        gSum += ((pIn ushr 8) and 0xFF) - ((pOut ushr 8) and 0xFF)
                        bSum += (pIn and 0xFF) - (pOut and 0xFF)
                    }
                }

                // Vertical pass: tmp -> pix
                for (x in 0 until w) {
                    var rSum = 0
                    var gSum = 0
                    var bSum = 0
                    var aSum = 0
                    for (i in -r..r) {
                        val py = i.coerceIn(0, h - 1) * w + x
                        val px = tmp[py]
                        aSum += (px ushr 24) and 0xFF
                        rSum += (px ushr 16) and 0xFF
                        gSum += (px ushr 8) and 0xFF
                        bSum += px and 0xFF
                    }
                    for (y in 0 until h) {
                        pix[y * w + x] = (dv[aSum] shl 24) or (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum]
                        val removeY = (y - r).coerceIn(0, h - 1)
                        val addY = (y + r + 1).coerceIn(0, h - 1)
                        val pOut = tmp[removeY * w + x]
                        val pIn = tmp[addY * w + x]
                        aSum += ((pIn ushr 24) and 0xFF) - ((pOut ushr 24) and 0xFF)
                        rSum += ((pIn ushr 16) and 0xFF) - ((pOut ushr 16) and 0xFF)
                        gSum += ((pIn ushr 8) and 0xFF) - ((pOut ushr 8) and 0xFF)
                        bSum += (pIn and 0xFF) - (pOut and 0xFF)
                    }
                }
            }

            if (saturationBoost != 1.0f) {
                val boost = (saturationBoost * 256f).roundToInt()
                for (i in 0 until wh) {
                    val c = pix[i]
                    val a = (c ushr 24) and 0xFF
                    val rv = (c ushr 16) and 0xFF
                    val gv = (c ushr 8) and 0xFF
                    val bv = c and 0xFF
                    // BT.601 luma
                    val luma = (rv * 77 + gv * 150 + bv * 29) ushr 8
                    val nr = (luma + (((rv - luma) * boost) shr 8)).coerceIn(0, 255)
                    val ng = (luma + (((gv - luma) * boost) shr 8)).coerceIn(0, 255)
                    val nb = (luma + (((bv - luma) * boost) shr 8)).coerceIn(0, 255)
                    pix[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            }

            bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        }
    }

    /**
     * Computes NikoLink's elastic bounce multiplier (`1.0 -> 0.78 -> 1.18 -> 0.95 -> 1.0`)
     * for normalized progress `t` in `0f..1f`.
     */
    fun computeNkbBounceScale(t: Float, bounceIntensity: Float = 1.0f): Float {
        val clamped = t.coerceIn(0f, 1f)
        if (clamped >= 0.995f || bounceIntensity <= 0.01f) return 1.0f
        val b = bounceIntensity.coerceIn(0f, 2f)
        // Immediate tactile press compression at t=0.0f (0.82f), springing out to 1.18f and settling to 1.0f
        val damping = (1f - clamped) * (1f - clamped * 0.20f)
        val wave = kotlin.math.cos(clamped * 2.35f * Math.PI.toFloat())
        return (1f - (0.20f * b) * wave * damping).coerceIn(0.68f, 1.32f)
    }

    /**
     * Computes NKB bloom intensity (`0f..1.5f`) over normalized progress `t` in `0f..1f`.
     * Starts immediately at high bloom (`0.88f`) at `t = 0f`, peaks at `1.0f` (`t = 0.14f`),
     * and resolves smoothly (`22px -> 14px -> 8px -> 3.5px -> 0px sharp`) to `0f` at `t = 1.0f`.
     */
    fun computeNkbBloomFactor(t: Float, nkbIntensity: Float = 1.0f): Float {
        val clamped = t.coerceIn(0f, 1f)
        if (clamped >= 0.995f || nkbIntensity <= 0.01f) return 0f
        val envelope = if (clamped < 0.14f) {
            0.88f + 0.12f * (clamped / 0.14f)
        } else {
            val rem = (1f - clamped) / 0.86f
            rem * rem
        }
        return (envelope * nkbIntensity.coerceIn(0f, 2f)).coerceIn(0f, 1.5f)
    }
}
