package com.nikolink.dynamiclyrics.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.nikolink.dynamiclyrics.lyrics.LiveLyricsResult
import com.nikolink.dynamiclyrics.lyrics.NikoLiveLyricsEngine
import com.nikolink.dynamiclyrics.lyrics.SyncedLyricLine
import com.nikolink.dynamiclyrics.lyrics.SyncedLyricWord
import com.nikolink.dynamiclyrics.media.NowPlayingMediaState
import com.nikolink.dynamiclyrics.ui.NikoFastGaussianBlur
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * DynamicLyricsNotificationRenderer (v1.0.0)
 * ------------------------------------------
 * Renders the Compact Silent Live Lyrics Notification (`R.layout.notification_live_lyrics_compact`):
 *   - No navigation buttons, no card background (`Color.TRANSPARENT`).
 *   - Compact ~2-line height (`680x124` px):
 *       1. Top half-line of what has passed (`prevLine`) — Gaussian-blurred & faded toward top edge.
 *       2. Middle active line (`currLine`) — crystal sharp with live word-to-word left-to-right glow
 *          sweep (or line glow) and smooth vertical scroll transition, using the user's selected
 *          Bold / Italic / Custom `.ttf` font.
 *       3. Bottom half-line of what is next (`nextLine`) — Gaussian-blurred & faded toward bottom edge.
 */
object DynamicLyricsNotificationRenderer {

    private const val NOTIF_W = 680
    private const val NOTIF_H = 124

    private val renderLock = Any()
    private val backBuffer = Bitmap.createBitmap(NOTIF_W, NOTIF_H, Bitmap.Config.ARGB_8888)
    private val backCanvas = Canvas(backBuffer)

    private val neighborBlurBuffer = Bitmap.createBitmap(NOTIF_W, NOTIF_H, Bitmap.Config.ARGB_8888)
    private val neighborBlurCanvas = Canvas(neighborBlurBuffer)

    private val outRing = Array(3) { Bitmap.createBitmap(NOTIF_W, NOTIF_H, Bitmap.Config.ARGB_8888) }
    private var ringSlot = 0

    private val atomicCopyPaint = Paint().apply {
        isFilterBitmap = false
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    // Vertical DST_IN alpha mask: fades out top 28% and bottom 28% so the top/bottom half-lines fade smoothly
    private val edgeFadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        shader = LinearGradient(
            0f,
            0f,
            0f,
            NOTIF_H.toFloat(),
            intArrayOf(
                0x00FFFFFF,
                0x70FFFFFF,
                0xFFFFFFFF.toInt(),
                0xFFFFFFFF.toInt(),
                0x70FFFFFF,
                0x00FFFFFF
            ),
            floatArrayOf(0.0f, 0.16f, 0.30f, 0.70f, 0.84f, 1.0f),
            Shader.TileMode.CLAMP
        )
    }

    private data class SyllableSpan(
        val startFrac: Float,
        val endFrac: Float,
        val startTimeMs: Long,
        val endTimeMs: Long
    )

    private data class MergedVisualWord(
        val text: String,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val syllables: List<SyllableSpan>
    ) {
        fun computeSweepProgress(positionMs: Long): Float {
            if (positionMs <= startTimeMs) return 0f
            if (positionMs >= endTimeMs) return 1f
            for (s in syllables) {
                if (positionMs <= s.endTimeMs) {
                    if (positionMs <= s.startTimeMs) return s.startFrac
                    val dur = (s.endTimeMs - s.startTimeMs).coerceAtLeast(40L)
                    val localProg = ((positionMs - s.startTimeMs).toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                    return (s.startFrac + (s.endFrac - s.startFrac) * localProg).coerceIn(0f, 1f)
                }
            }
            return 1f
        }
    }

    private fun mergeSyllables(rawWords: List<SyncedLyricWord>): List<MergedVisualWord> {
        if (rawWords.isEmpty()) return emptyList()
        val result = ArrayList<MergedVisualWord>(rawWords.size)
        var i = 0
        while (i < rawWords.size) {
            val first = rawWords[i]
            val group = ArrayList<SyncedLyricWord>(4)
            group.add(first)
            while (i + 1 < rawWords.size) {
                val prevRaw = rawWords[i].text
                val nextRaw = rawWords[i + 1].text
                val prevEndsWithSpace = prevRaw.isNotEmpty() && prevRaw.last().isWhitespace()
                val nextStartsWithSpace = nextRaw.isNotEmpty() && nextRaw.first().isWhitespace()
                if (!prevEndsWithSpace && !nextStartsWithSpace) {
                    i++
                    group.add(rawWords[i])
                } else {
                    break
                }
            }
            val sb = StringBuilder()
            for (syl in group) sb.append(syl.text.trim())
            val mergedText = sb.toString()
            if (mergedText.isNotEmpty()) {
                val totalChars = mergedText.length.coerceAtLeast(1).toFloat()
                val spans = ArrayList<SyllableSpan>(group.size)
                var cursor = 0
                for (syl in group) {
                    val len = syl.text.trim().length.coerceAtLeast(1)
                    val sFrac = (cursor.toFloat() / totalChars).coerceIn(0f, 1f)
                    cursor += len
                    val eFrac = (cursor.toFloat() / totalChars).coerceIn(sFrac, 1f)
                    spans.add(
                        SyllableSpan(
                            startFrac = sFrac,
                            endFrac = eFrac,
                            startTimeMs = syl.startTimeMs,
                            endTimeMs = syl.endTimeMs.coerceAtLeast(syl.startTimeMs + 40L)
                        )
                    )
                }
                result.add(
                    MergedVisualWord(
                        text = mergedText,
                        startTimeMs = group.first().startTimeMs,
                        endTimeMs = group.last().endTimeMs.coerceAtLeast(group.first().startTimeMs + 60L),
                        syllables = spans
                    )
                )
            }
            i++
        }
        return result
    }

    private fun blendColors(c1: Int, c2: Int, ratio: Float): Int {
        val f = ratio.coerceIn(0f, 1f)
        val inv = 1f - f
        val a = (Color.alpha(c1) * inv + Color.alpha(c2) * f).roundToInt().coerceIn(0, 255)
        val r = (Color.red(c1) * inv + Color.red(c2) * f).roundToInt().coerceIn(0, 255)
        val g = (Color.green(c1) * inv + Color.green(c2) * f).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(c1) * inv + Color.blue(c2) * f).roundToInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    private fun withAlpha(color: Int, alphaFraction: Float): Int {
        val a = (alphaFraction.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    fun renderNotificationBitmap(
        context: Context,
        nowPlaying: NowPlayingMediaState,
        lyrics: LiveLyricsResult?,
        positionMs: Long,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): Bitmap = synchronized(renderLock) {
        backBuffer.eraseColor(Color.TRANSPARENT)

        val nightFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDarkShade = nightFlags == Configuration.UI_MODE_NIGHT_YES

        val phoneAccent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val resId = if (isDarkShade) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
            runCatching { context.getColor(resId) }.getOrDefault(0xFFD8B888.toInt())
        } else {
            0xFFD8B888.toInt()
        }

        val rawAccent = if (DynamicWidgetSettings.accentColorSource == AccentColorSource.ALBUM_ART &&
            nowPlaying.dominantAccentArgb != null
        ) {
            nowPlaying.dominantAccentArgb
        } else {
            phoneAccent
        }

        val baseTextColor = if (isDarkShade) Color.WHITE else 0xFF14171F.toInt()
        val sungWordColor = if (isDarkShade) blendColors(Color.WHITE, rawAccent, 0.32f) else blendColors(0xFF12151E.toInt(), rawAccent, 0.55f)
        val activeSweepColor = if (isDarkShade) blendColors(Color.WHITE, rawAccent, 0.65f) else rawAccent
        val inactiveWordColor = withAlpha(baseTextColor, if (isDarkShade) 0.44f else 0.48f)
        val neighborColor = withAlpha(baseTextColor, if (isDarkShade) 0.52f else 0.56f)

        val primaryTypeface = DynamicWidgetSettings.resolveWidgetTypeface(
            context = context,
            forceBold = DynamicWidgetSettings.isLyricsBold,
            forceItalic = DynamicWidgetSettings.isLyricsItalic
        )

        val lines = lyrics?.lines.orEmpty()
        val activeIdx = NikoLiveLyricsEngine.findActiveLineIndex(lines, positionMs)
        val trackKey = "${nowPlaying.title.trim().lowercase()}|${nowPlaying.artist.trim().lowercase()}"
        NkbWidgetAnimationTimeline.onTrackOrLineUpdated(trackKey, activeIdx)

        val cx = NOTIF_W * 0.5f
        val cy = NOTIF_H * 0.5f
        val maxTextW = (NOTIF_W - 36).coerceAtLeast(200)
        val fontScale = DynamicWidgetSettings.fontScale.coerceIn(0.80f, 1.30f)
        val mainSp = (23.5f * fontScale).coerceIn(18f, 28f)
        val neighborSp = (17.5f * fontScale).coerceIn(14f, 21f)

        if (nowPlaying.title.isBlank()) {
            drawSingleLineCentered(
                canvas = backCanvas,
                text = "Play music to see live synced lyrics",
                cx = cx,
                cy = cy,
                maxWidth = maxTextW,
                textSizePx = neighborSp,
                color = neighborColor,
                typeface = primaryTypeface
            )
        } else if (lyrics?.isLoading == true) {
            drawSingleLineCentered(
                canvas = backCanvas,
                text = "Syncing lyrics for ${nowPlaying.title}…",
                cx = cx,
                cy = cy,
                maxWidth = maxTextW,
                textSizePx = neighborSp,
                color = neighborColor,
                typeface = primaryTypeface
            )
        } else if (lines.isEmpty()) {
            drawSingleLineCentered(
                canvas = backCanvas,
                text = "♪ ${nowPlaying.title} — ${nowPlaying.artist}",
                cx = cx,
                cy = cy,
                maxWidth = maxTextW,
                textSizePx = mainSp * 0.88f,
                color = sungWordColor,
                typeface = primaryTypeface
            )
        } else {
            val slideFrac = NkbWidgetAnimationTimeline.getLineSlideOffsetFraction(nowElapsedMs)
            val lineEntryProg = NkbWidgetAnimationTimeline.getLineEntryEasedProgress(nowElapsedMs)
            val slideOffsetY = slideFrac * 34f

            val centerIdx = if (activeIdx >= 0) activeIdx else 0
            val prevLine = if (activeIdx >= 1) lines.getOrNull(centerIdx - 1) else null
            val currLine = lines.getOrNull(centerIdx)
            val nextLine = if (activeIdx >= 0) lines.getOrNull(centerIdx + 1) else lines.getOrNull(1)

            // 1. Render Top Passed Half-Line & Bottom Next Half-Line onto `neighborBlurBuffer` and Gaussian-blur them!
            neighborBlurBuffer.eraseColor(Color.TRANSPARENT)
            val topHalfLineCy = (cy + slideOffsetY) - 43f
            val botHalfLineCy = (cy + slideOffsetY) + 43f

            if (prevLine != null && prevLine.text.isNotBlank()) {
                drawSingleLineCentered(
                    canvas = neighborBlurCanvas,
                    text = prevLine.text,
                    cx = cx,
                    cy = topHalfLineCy,
                    maxWidth = maxTextW,
                    textSizePx = neighborSp,
                    color = neighborColor,
                    typeface = primaryTypeface
                )
            }
            if (nextLine != null && nextLine.text.isNotBlank()) {
                drawSingleLineCentered(
                    canvas = neighborBlurCanvas,
                    text = nextLine.text,
                    cx = cx,
                    cy = botHalfLineCy,
                    maxWidth = maxTextW,
                    textSizePx = neighborSp,
                    color = neighborColor,
                    typeface = primaryTypeface
                )
            }

            // Apply real Gaussian blur to the top & bottom half-lines so they look softly blurred
            NikoFastGaussianBlur.blurBitmapInPlace(
                bitmap = neighborBlurBuffer,
                radius = 3,
                passes = 2,
                saturationBoost = 1.0f
            )
            backCanvas.drawBitmap(neighborBlurBuffer, 0f, 0f, bitmapPaint)

            // 2. Render Middle Current Line (sharp, with word-to-word left-to-right glow sweep)
            if (currLine != null) {
                val isLineActive = activeIdx >= 0
                val scale = 0.95f + 0.05f * lineEntryProg
                backCanvas.save()
                backCanvas.scale(scale, scale, cx, cy + slideOffsetY)
                drawActiveNotificationLine(
                    canvas = backCanvas,
                    line = currLine,
                    isLineActive = isLineActive,
                    lineEntryProg = lineEntryProg,
                    positionMs = positionMs,
                    cx = cx,
                    cy = cy + slideOffsetY,
                    maxWidth = maxTextW,
                    fontSizePx = mainSp,
                    sungWordColor = sungWordColor,
                    activeSweepColor = activeSweepColor,
                    inactiveWordColor = inactiveWordColor,
                    typeface = primaryTypeface
                )
                backCanvas.restore()
            }

            // 3. Apply Top & Bottom Edge Fade Mask (`PorterDuff.Mode.DST_IN`) so top & bottom half-lines fade smoothly
            backCanvas.drawRect(0f, 0f, NOTIF_W.toFloat(), NOTIF_H.toFloat(), edgeFadePaint)
        }

        ringSlot = (ringSlot + 1) % outRing.size
        val outBmp = outRing[ringSlot]
        Canvas(outBmp).drawBitmap(backBuffer, 0f, 0f, atomicCopyPaint)
        return outBmp
    }

    private fun drawSingleLineCentered(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        maxWidth: Int,
        textSizePx: Float,
        color: Int,
        typeface: Typeface
    ) {
        if (text.isBlank()) return
        textPaint.shader = null
        textPaint.clearShadowLayer()
        textPaint.typeface = typeface
        textPaint.textSize = textSizePx
        textPaint.color = color
        textPaint.textAlign = Paint.Align.CENTER

        val ellipsized = TextUtils.ellipsize(text.trim(), textPaint, maxWidth.toFloat(), TextUtils.TruncateAt.END).toString()
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) * 0.5f
        canvas.drawText(ellipsized, cx, baseline, textPaint)
    }

    @Suppress("DEPRECATION")
    private fun drawActiveNotificationLine(
        canvas: Canvas,
        line: SyncedLyricLine,
        isLineActive: Boolean,
        lineEntryProg: Float,
        positionMs: Long,
        cx: Float,
        cy: Float,
        maxWidth: Int,
        fontSizePx: Float,
        sungWordColor: Int,
        activeSweepColor: Int,
        inactiveWordColor: Int,
        typeface: Typeface
    ) {
        if (!isLineActive || line.words.isEmpty()) {
            val col = if (isLineActive) blendColors(inactiveWordColor, sungWordColor, lineEntryProg) else inactiveWordColor
            textPaint.shader = null
            textPaint.typeface = typeface
            textPaint.textSize = fontSizePx
            textPaint.color = col
            textPaint.textAlign = Paint.Align.LEFT
            if (isLineActive) {
                textPaint.setShadowLayer(8f, 0f, 0f, withAlpha(sungWordColor, 0.28f * lineEntryProg))
            } else {
                textPaint.clearShadowLayer()
            }
            val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(line.text, 0, line.text.length, textPaint, maxWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setIncludePad(false)
                    .build()
            } else {
                StaticLayout(line.text, textPaint, maxWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false)
            }
            val h = layout.height.toFloat()
            canvas.save()
            canvas.translate(cx - maxWidth * 0.5f, cy - h * 0.5f)
            layout.draw(canvas)
            canvas.restore()
            textPaint.clearShadowLayer()
            return
        }

        val mergedWords = mergeSyllables(line.words)
        if (mergedWords.isEmpty()) return

        textPaint.typeface = typeface
        textPaint.textAlign = Paint.Align.LEFT

        var effectiveSize = fontSizePx
        val rows = ArrayList<MutableList<Int>>()
        val rowWidths = ArrayList<Float>()
        var spaceW = 0f

        for (step in 0..2) {
            textPaint.textSize = effectiveSize
            spaceW = textPaint.measureText(" ")
            rows.clear()
            rowWidths.clear()
            var curRow = ArrayList<Int>()
            var curW = 0f
            for (idx in mergedWords.indices) {
                val wPx = textPaint.measureText(mergedWords[idx].text)
                val needed = if (curRow.isEmpty()) wPx else curW + spaceW + wPx
                if (curRow.isNotEmpty() && needed > maxWidth) {
                    rows.add(curRow)
                    rowWidths.add(curW)
                    curRow = ArrayList()
                    curRow.add(idx)
                    curW = wPx
                } else {
                    curRow.add(idx)
                    curW = needed
                }
            }
            if (curRow.isNotEmpty()) {
                rows.add(curRow)
                rowWidths.add(curW)
            }
            if (rows.size <= 1 || (rows.size <= 2 && effectiveSize <= fontSizePx * 0.85f)) {
                break
            }
            effectiveSize = (effectiveSize * 0.84f).coerceAtLeast(14f)
        }

        // Keep at most 2 compact rows in the middle active slot
        val displayRows = if (rows.size > 2) rows.take(2) else rows
        textPaint.textSize = effectiveSize
        spaceW = textPaint.measureText(" ")
        val lineH = effectiveSize * 1.18f
        val totalH = displayRows.size * lineH
        var rowCy = cy - (totalH - lineH) * 0.5f

        for (rIdx in displayRows.indices) {
            val wordIndices = displayRows[rIdx]
            val rW = rowWidths[rIdx].coerceAtLeast(1f)
            val horizScale = if (rW > maxWidth) (maxWidth.toFloat() / rW).coerceIn(0.75f, 1f) else 1f
            var cursorX = cx - (rW * horizScale) * 0.5f

            if (horizScale < 1f) {
                canvas.save()
                canvas.scale(horizScale, horizScale, cx, rowCy)
                cursorX = cx - rW * 0.5f
            }

            for (i in wordIndices.indices) {
                val mw = mergedWords[wordIndices[i]]
                val wText = mw.text
                val wordW = textPaint.measureText(wText).coerceAtLeast(1f)
                val sweepProg = mw.computeSweepProgress(positionMs)

                val isSweeping = sweepProg in 0.005f..0.995f
                val liftPx = if (isSweeping) -sin(sweepProg * PI.toFloat()) * (effectiveSize * 0.04f) else 0f
                val baselineY = rowCy + liftPx - (textPaint.descent() + textPaint.ascent()) * 0.5f

                when {
                    sweepProg <= 0.005f -> {
                        textPaint.shader = null
                        textPaint.clearShadowLayer()
                        textPaint.color = inactiveWordColor
                        canvas.drawText(wText, cursorX, baselineY, textPaint)
                    }
                    sweepProg >= 0.995f -> {
                        textPaint.shader = null
                        textPaint.color = sungWordColor
                        textPaint.setShadowLayer(8f, 0f, 0f, withAlpha(sungWordColor, 0.25f))
                        canvas.drawText(wText, cursorX, baselineY, textPaint)
                    }
                    else -> {
                        val leadStop = (sweepProg - 0.08f).coerceIn(0f, 0.96f)
                        val trailStop = (sweepProg + 0.14f).coerceIn(leadStop + 0.04f, 1f)
                        textPaint.color = Color.WHITE
                        textPaint.shader = LinearGradient(
                            cursorX,
                            0f,
                            cursorX + wordW,
                            0f,
                            intArrayOf(sungWordColor, activeSweepColor, inactiveWordColor, inactiveWordColor),
                            floatArrayOf(0f, leadStop, trailStop, 1f),
                            Shader.TileMode.CLAMP
                        )
                        textPaint.setShadowLayer(9f, 0f, 0f, withAlpha(sungWordColor, 0.32f))
                        canvas.drawText(wText, cursorX, baselineY, textPaint)
                        textPaint.shader = null
                    }
                }
                cursorX += wordW + spaceW
            }

            if (horizScale < 1f) {
                canvas.restore()
            }
            rowCy += lineH
        }
        textPaint.shader = null
        textPaint.clearShadowLayer()
    }
}
