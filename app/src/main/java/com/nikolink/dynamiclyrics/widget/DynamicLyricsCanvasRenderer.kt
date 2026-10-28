package com.nikolink.dynamiclyrics.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * DynamicLyricsCanvasRenderer (v1.0.0 Full Release)
 * -------------------------------------------------
 * High-FPS (Up to 60 FPS) Zero-Allocation Offscreen Canvas Renderer:
 *   - 6-Button NKB Transport Deck: `Prev | -5s | Play/Pause | +5s | Next | Like`
 *   - Real Per-Button NKB Gaussian Blur (`NikoFastGaussianBlur.blurBitmapInPlace`) + Elastic Bounce
 *     rendered on a reusable offscreen `btnNkbBitmap` whenever any transport button is tapped!
 *   - Supports Bold, Italic, Built-in Font Styles, and User-Imported `.ttf`/`.otf` Custom Fonts.
 */
object DynamicLyricsCanvasRenderer {

    private val renderLock = Any()
    private val backBufferPool = HashMap<String, Bitmap>()
    private val ringBufferPool = HashMap<String, Array<Bitmap>>()
    private val ringBufferIndex = HashMap<String, Int>()
    private val atomicCopyPaint = Paint().apply {
        isFilterBitmap = false
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
    }

    // Reusable 96x96 offscreen buffer for real per-button Gaussian NKB blur!
    private const val BTN_NKB_BUF_DIM = 96
    private val btnNkbBitmap: Bitmap by lazy {
        Bitmap.createBitmap(BTN_NKB_BUF_DIM, BTN_NKB_BUF_DIM, Bitmap.Config.ARGB_8888)
    }
    private val btnNkbCanvas: Canvas by lazy { Canvas(btnNkbBitmap) }
    private val btnNkbSrcRect = Rect(0, 0, BTN_NKB_BUF_DIM, BTN_NKB_BUF_DIM)
    private val btnNkbDstRect = RectF()

    private var cachedBackdropFingerprint: Long = -1L
    private var cachedBackdropBlurSetting: Int = -1
    private var cachedFrostedBackdrop: Bitmap? = null

    private val cachedBokehOrbBitmap: Bitmap by lazy {
        val dim = 64
        val bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val half = dim * 0.5f
        p.shader = RadialGradient(
            half,
            half,
            half,
            intArrayOf(0xFFFFFFFF.toInt(), 0x7AFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(half, half, half, p)
        bmp
    }

    private val cardBoundsRect = RectF()
    private val stageBoundsRect = RectF()
    private val artBoundsRect = RectF()
    private val bokehOrbRect = RectF()
    private val btnBoundsRect = RectF()
    private val iconBoundsRect = RectF()

    private val clipPath = Path()
    private val stageClipPath = Path()
    private val wavePath = Path()
    private val ringTrackPath = Path()
    private val ringActivePath = Path()
    private val iconPath = Path()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

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

    private data class ResolvedPalette(
        val isMaterialYou: Boolean,
        val isDark: Boolean,
        val isAmoled: Boolean,
        val accentColor: Int,
        val secondaryContainerColor: Int,
        val cardSurfaceTop: Int,
        val cardSurfaceBottom: Int,
        val cardBorderTop: Int,
        val cardBorderBottom: Int,
        val stageBgColor: Int,
        val stageBorderColor: Int,
        val fgBaseColor: Int,
        val fgSecondaryColor: Int,
        val activeSungWordColor: Int,
        val activeNowSingingWordColor: Int,
        val inactiveWordColor: Int,
        val neighborLineColor: Int,
        val outerLineColor: Int,
        val btnBgColor: Int,
        val btnFgColor: Int,
        val btnBorderColor: Int,
        val playBtnBgColor: Int,
        val playBtnFgColor: Int,
        val likeActiveColor: Int
    )

    private fun obtainBackBuffer(w: Int, h: Int): Bitmap {
        val key = "${w}x${h}"
        val existing = backBufferPool[key]
        if (existing != null && !existing.isRecycled && existing.width == w && existing.height == h) {
            existing.eraseColor(Color.TRANSPARENT)
            return existing
        }
        if (backBufferPool.size >= 6) {
            backBufferPool.clear()
        }
        val created = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        backBufferPool[key] = created
        return created
    }

    /**
     * Copies the 100% finished `backBuffer` atomically (`PorterDuff.Mode.SRC`, zero `eraseColor`)
     * into the next slot of a 4-bitmap ring buffer so `RemoteViews` / `AppWidgetHostView` NEVER
     * reads a cleared or half-rendered Bitmap during Play/Pause or rapid NKB updates!
     */
    private fun swapCompletedFrameToRingBuffer(backBuffer: Bitmap): Bitmap {
        val w = backBuffer.width
        val h = backBuffer.height
        val key = "${w}x${h}"
        var ring = ringBufferPool[key]
        if (ring == null || ring.any { it.isRecycled || it.width != w || it.height != h }) {
            if (ringBufferPool.size >= 6) {
                ringBufferPool.clear()
                ringBufferIndex.clear()
            }
            ring = Array(4) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
            ringBufferPool[key] = ring
        }
        val nextSlot = ((ringBufferIndex[key] ?: 0) + 1) and 3
        ringBufferIndex[key] = nextSlot
        val outBmp = ring[nextSlot]
        val outCanvas = Canvas(outBmp)
        outCanvas.drawBitmap(backBuffer, 0f, 0f, atomicCopyPaint)
        return outBmp
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

    @Suppress("NewApi")
    private fun getSystemMonetColor(context: Context?, resId: Int, fallback: Int): Int {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return fallback
        return try {
            context.getColor(resId)
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun resolvePalette(context: Context?, nowPlaying: NowPlayingMediaState): ResolvedPalette {
        val isDark = if (context != null) {
            DynamicWidgetSettings.isDarkEffective(context)
        } else {
            DynamicWidgetSettings.themeStyle != WidgetThemeStyle.LIGHT_HYPEROS
        }
        val isAmoled = DynamicWidgetSettings.themeStyle == WidgetThemeStyle.AMOLED_GLASS
        val isMaterialYou = DynamicWidgetSettings.visualEngineMode == VisualEngineMode.MATERIAL_YOU_MONET

        val phoneSystemAccent = if (isDark) {
            getSystemMonetColor(context, android.R.color.system_accent1_200, 0xFFD8B888.toInt())
        } else {
            getSystemMonetColor(context, android.R.color.system_accent1_600, 0xFF8C6B3F.toInt())
        }
        val albumAccent = nowPlaying.dominantAccentArgb ?: phoneSystemAccent

        val useAlbumColor = DynamicWidgetSettings.accentColorSource == AccentColorSource.ALBUM_ART &&
            nowPlaying.dominantAccentArgb != null
        val rawAccent = if (useAlbumColor) albumAccent else phoneSystemAccent

        if (isMaterialYou) {
            val surfHigh = when {
                isAmoled -> 0xFF07090E.toInt()
                isDark -> getSystemMonetColor(
                    context,
                    android.R.color.system_neutral1_900,
                    blendColors(0xFF14171F.toInt(), rawAccent, 0.12f)
                )
                else -> getSystemMonetColor(
                    context,
                    android.R.color.system_neutral1_50,
                    blendColors(0xFFF7F8FC.toInt(), rawAccent, 0.08f)
                )
            }
            val surfLowest = when {
                isAmoled -> 0xFF000000.toInt()
                isDark -> getSystemMonetColor(
                    context,
                    android.R.color.system_neutral1_800,
                    blendColors(0xFF1D222E.toInt(), rawAccent, 0.15f)
                )
                else -> getSystemMonetColor(
                    context,
                    android.R.color.system_neutral1_100,
                    blendColors(0xFFEEF1F8.toInt(), rawAccent, 0.12f)
                )
            }
            val secContainer = if (isDark) {
                if (useAlbumColor) {
                    blendColors(0xFF232938.toInt(), rawAccent, 0.34f)
                } else {
                    getSystemMonetColor(context, android.R.color.system_accent2_800, 0xFF283044.toInt())
                }
            } else {
                if (useAlbumColor) {
                    blendColors(0xFFE6ECF8.toInt(), rawAccent, 0.25f)
                } else {
                    getSystemMonetColor(context, android.R.color.system_accent2_100, 0xFFDCE4F5.toInt())
                }
            }
            val fgBase = if (isDark) 0xFFF4F6FB.toInt() else 0xFF14171F.toInt()
            val fgSec = withAlpha(fgBase, 0.72f)
            val sungWord = if (isDark) blendColors(Color.WHITE, rawAccent, 0.38f) else blendColors(0xFF10141D.toInt(), rawAccent, 0.62f)
            val nowWord = if (isDark) blendColors(Color.WHITE, rawAccent, 0.68f) else rawAccent

            return ResolvedPalette(
                isMaterialYou = true,
                isDark = isDark,
                isAmoled = isAmoled,
                accentColor = rawAccent,
                secondaryContainerColor = secContainer,
                cardSurfaceTop = surfHigh,
                cardSurfaceBottom = blendColors(surfHigh, rawAccent, DynamicWidgetSettings.surfaceTintLevel * 0.45f),
                cardBorderTop = withAlpha(rawAccent, if (isDark) 0.38f else 0.28f),
                cardBorderBottom = withAlpha(rawAccent, 0.18f),
                stageBgColor = surfLowest,
                stageBorderColor = withAlpha(rawAccent, 0.22f),
                fgBaseColor = fgBase,
                fgSecondaryColor = fgSec,
                activeSungWordColor = sungWord,
                activeNowSingingWordColor = nowWord,
                inactiveWordColor = withAlpha(fgBase, if (isDark) 0.38f else 0.44f),
                neighborLineColor = withAlpha(fgBase, if (isDark) 0.38f else 0.44f),
                outerLineColor = withAlpha(fgBase, if (isDark) 0.18f else 0.24f),
                btnBgColor = secContainer,
                btnFgColor = fgBase,
                btnBorderColor = withAlpha(rawAccent, 0.24f),
                playBtnBgColor = rawAccent,
                playBtnFgColor = if (isDark) 0xFF0A0E17.toInt() else Color.WHITE,
                likeActiveColor = 0xFFFF3B6B.toInt()
            )
        } else {
            val opacity = DynamicWidgetSettings.glassOpacity.coerceIn(0.40f, 0.98f)
            val tint = DynamicWidgetSettings.surfaceTintLevel.coerceIn(0f, 0.85f)
            val baseSurf = when {
                isAmoled -> 0xFF05070B.toInt()
                isDark -> 0xFF0C1018.toInt()
                else -> 0xFFFBF9F4.toInt()
            }
            val tintedTop = withAlpha(blendColors(baseSurf, rawAccent, tint * 0.65f), opacity)
            val tintedBot = withAlpha(blendColors(baseSurf, rawAccent, tint * 1.15f), (opacity + 0.04f).coerceAtMost(0.99f))
            val rimScale = DynamicWidgetSettings.rimBorderIntensity.coerceIn(0f, 2f)
            val borderTop = withAlpha(
                if (isDark) blendColors(Color.WHITE, rawAccent, 0.28f) else Color.WHITE,
                ((if (isDark) 0.34f else 0.78f) * rimScale).coerceIn(0f, 1f)
            )
            val borderBot = withAlpha(rawAccent, (0.28f * rimScale).coerceIn(0f, 1f))
            val fgBase = if (isDark) Color.WHITE else 0xFF18140F.toInt()
            val sungWord = if (isDark) blendColors(Color.WHITE, rawAccent, 0.26f) else blendColors(0xFF14110C.toInt(), rawAccent, 0.44f)
            val nowWord = if (isDark) blendColors(Color.WHITE, rawAccent, 0.52f) else blendColors(0xFF100D09.toInt(), rawAccent, 0.64f)
            val glassBtnBg = withAlpha(blendColors(Color.WHITE, rawAccent, 0.20f), if (isDark) 0.18f else 0.48f)

            return ResolvedPalette(
                isMaterialYou = false,
                isDark = isDark,
                isAmoled = isAmoled,
                accentColor = rawAccent,
                secondaryContainerColor = withAlpha(blendColors(baseSurf, rawAccent, 0.35f), 0.55f),
                cardSurfaceTop = tintedTop,
                cardSurfaceBottom = tintedBot,
                cardBorderTop = borderTop,
                cardBorderBottom = borderBot,
                stageBgColor = withAlpha(
                    if (isDark) blendColors(0xFF12101A.toInt(), rawAccent, 0.08f)
                    else blendColors(Color.WHITE, rawAccent, 0.08f),
                    if (isDark) 0.22f else 0.32f
                ),
                stageBorderColor = withAlpha(blendColors(fgBase, rawAccent, 0.35f), 0.20f),
                fgBaseColor = fgBase,
                fgSecondaryColor = withAlpha(blendColors(fgBase, rawAccent, 0.22f), 0.70f),
                activeSungWordColor = sungWord,
                activeNowSingingWordColor = nowWord,
                inactiveWordColor = withAlpha(blendColors(fgBase, rawAccent, 0.12f), if (isDark) 0.38f else 0.44f),
                neighborLineColor = withAlpha(blendColors(fgBase, rawAccent, 0.16f), if (isDark) 0.38f else 0.44f),
                outerLineColor = withAlpha(blendColors(fgBase, rawAccent, 0.18f), if (isDark) 0.16f else 0.22f),
                btnBgColor = glassBtnBg,
                btnFgColor = fgBase,
                btnBorderColor = withAlpha(Color.WHITE, if (isDark) 0.28f else 0.62f),
                playBtnBgColor = if (isDark) sungWord else rawAccent,
                playBtnFgColor = if (isDark) 0xFF101318.toInt() else Color.WHITE,
                likeActiveColor = 0xFFFF3B6B.toInt()
            )
        }
    }

    private fun mergeSyllablesIntoWholeWords(rawWords: List<SyncedLyricWord>): List<MergedVisualWord> {
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

            val fullTextBuilder = StringBuilder()
            for (syl in group) {
                fullTextBuilder.append(syl.text.trim())
            }
            val mergedText = fullTextBuilder.toString()
            if (mergedText.isNotEmpty()) {
                val totalChars = mergedText.length.coerceAtLeast(1).toFloat()
                val spans = ArrayList<SyllableSpan>(group.size)
                var charCursor = 0
                for (syl in group) {
                    val trimmedLen = syl.text.trim().length.coerceAtLeast(1)
                    val sFrac = (charCursor.toFloat() / totalChars).coerceIn(0f, 1f)
                    charCursor += trimmedLen
                    val eFrac = (charCursor.toFloat() / totalChars).coerceIn(sFrac, 1f)
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

    fun renderWidgetBitmap(
        context: Context,
        state: NowPlayingMediaState,
        lyrics: LiveLyricsResult?,
        isLoadingLyrics: Boolean,
        preferredProvider: NikoLiveLyricsEngine.LyricsProviderOption,
        currentPosMs: Long,
        widthPx: Int = 640,
        heightPx: Int = 460,
        layoutOverride: WidgetLayoutMode? = null
    ): Bitmap {
        val effectiveLyrics = lyrics ?: NikoLiveLyricsEngine.lyricsState.value
        val nowElapsed = SystemClock.elapsedRealtime()
        NkbWidgetAnimationTimeline.stepFrame(state.isPlaying, nowElapsed)

        val density = context.resources.displayMetrics.density.coerceAtLeast(1.5f)
        val widthDp = (widthPx / density).roundToInt().coerceAtLeast(105)
        val heightDp = (heightPx / density).roundToInt().coerceAtLeast(68)
        val resolvedLayout = layoutOverride ?: DynamicWidgetSettings.resolveEffectiveLayout(widthDp, heightDp)

        return renderWidgetBitmapInternal(
            context = context,
            widthPx = widthPx,
            heightPx = heightPx,
            nowPlaying = state,
            lyricsState = effectiveLyrics.copy(isLoading = isLoadingLyrics),
            positionMs = currentPosMs,
            layoutMode = resolvedLayout,
            nowElapsedMs = nowElapsed
        )
    }

    fun renderWidgetBitmap(
        widthPx: Int = 520,
        heightPx: Int = 560,
        nowPlaying: NowPlayingMediaState,
        lyricsState: LiveLyricsResult,
        positionMs: Long,
        bokehPhaseRad: Float
    ): Bitmap {
        val nowElapsed = SystemClock.elapsedRealtime()
        NkbWidgetAnimationTimeline.stepFrame(nowPlaying.isPlaying, nowElapsed)
        val layout = DynamicWidgetSettings.resolveEffectiveLayout(
            (widthPx / 2f).roundToInt(),
            (heightPx / 2f).roundToInt()
        )
        return renderWidgetBitmapInternal(
            context = null,
            widthPx = widthPx,
            heightPx = heightPx,
            nowPlaying = nowPlaying,
            lyricsState = lyricsState,
            positionMs = positionMs,
            layoutMode = layout,
            nowElapsedMs = nowElapsed
        )
    }

    private fun renderWidgetBitmapInternal(
        context: Context?,
        widthPx: Int,
        heightPx: Int,
        nowPlaying: NowPlayingMediaState,
        lyricsState: LiveLyricsResult,
        positionMs: Long,
        layoutMode: WidgetLayoutMode,
        nowElapsedMs: Long
    ): Bitmap = synchronized(renderLock) {
        val w = widthPx.coerceIn(220, 960)
        val h = heightPx.coerceIn(130, 960)
        val bmp = obtainBackBuffer(w, h)
        val canvas = Canvas(bmp)
        val minDim = min(w, h).toFloat()
        val palette = resolvePalette(context, nowPlaying)

        val primaryTypeface = DynamicWidgetSettings.resolveWidgetTypeface(
            context = context,
            forceBold = DynamicWidgetSettings.isLyricsBold,
            forceItalic = DynamicWidgetSettings.isLyricsItalic
        )
        val subTypeface = DynamicWidgetSettings.resolveWidgetTypeface(
            context = context,
            forceBold = false,
            forceItalic = DynamicWidgetSettings.isLyricsItalic
        )

        val lines = lyricsState.lines
        val activeLineIdx = NikoLiveLyricsEngine.findActiveLineIndex(lines, positionMs)
        val trackKey = "${nowPlaying.title.trim().lowercase()}|${nowPlaying.artist.trim().lowercase()}"
        NkbWidgetAnimationTimeline.onTrackOrLineUpdated(trackKey, activeLineIdx)

        val outerPad = (minDim * 0.012f).coerceIn(2f, 6f)
        val cornerRadius = (minDim * 0.095f).coerceIn(20f, 42f)
        cardBoundsRect.set(outerPad, outerPad, w - outerPad, h - outerPad)

        clipPath.reset()
        clipPath.addRoundRect(cardBoundsRect, cornerRadius, cornerRadius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)

        if (!palette.isMaterialYou) {
            val art = nowPlaying.albumArt
            val blurRadiusSetting = (DynamicWidgetSettings.blurIntensity * 22f).roundToInt().coerceIn(0, 42)
            if (art != null && !art.isRecycled && blurRadiusSetting > 0) {
                if (cachedFrostedBackdrop == null ||
                    cachedBackdropFingerprint != nowPlaying.albumArtFingerprint ||
                    cachedBackdropBlurSetting != blurRadiusSetting
                ) {
                    cachedBackdropFingerprint = nowPlaying.albumArtFingerprint
                    cachedBackdropBlurSetting = blurRadiusSetting
                    cachedFrostedBackdrop?.recycle()
                    cachedFrostedBackdrop = NikoFastGaussianBlur.createHyperOsFrostedBackdrop(
                        source = art,
                        targetWidth = w,
                        targetHeight = h,
                        blurRadius = blurRadiusSetting,
                        saturationBoost = 1.32f
                    )
                }
                cachedFrostedBackdrop?.let { blurred ->
                    bgPaint.alpha = (DynamicWidgetSettings.glassOpacity * 245f).roundToInt().coerceIn(60, 255)
                    canvas.drawBitmap(
                        blurred,
                        Rect(0, 0, blurred.width, blurred.height),
                        cardBoundsRect,
                        bgPaint
                    )
                    bgPaint.alpha = 255
                }
            }
        }

        fillPaint.shader = LinearGradient(
            0f,
            cardBoundsRect.top,
            w.toFloat(),
            cardBoundsRect.bottom,
            intArrayOf(palette.cardSurfaceTop, palette.cardSurfaceBottom),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(cardBoundsRect, cornerRadius, cornerRadius, fillPaint)
        fillPaint.shader = null

        if (!palette.isMaterialYou && DynamicWidgetSettings.innerSheenIntensity > 0.02f) {
            val sheenAlpha = (0.14f * DynamicWidgetSettings.innerSheenIntensity).coerceIn(0f, 0.35f)
            fillPaint.shader = LinearGradient(
                0f,
                cardBoundsRect.top,
                0f,
                cardBoundsRect.top + h * 0.28f,
                withAlpha(Color.WHITE, sheenAlpha),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(cardBoundsRect, cornerRadius, cornerRadius, fillPaint)
            fillPaint.shader = null
        }

        canvas.restore()

        strokePaint.strokeWidth = (minDim * 0.0055f).coerceIn(1.5f, 3.0f)
        strokePaint.shader = LinearGradient(
            0f,
            cardBoundsRect.top,
            w.toFloat(),
            cardBoundsRect.bottom,
            palette.cardBorderTop,
            palette.cardBorderBottom,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(cardBoundsRect, cornerRadius, cornerRadius, strokePaint)
        strokePaint.shader = null

        when (layoutMode) {
            WidgetLayoutMode.SPLIT_SIDE_BY_SIDE -> renderSplitSideBySideLayout(
                canvas = canvas,
                w = w,
                h = h,
                palette = palette,
                primaryTypeface = primaryTypeface,
                subTypeface = subTypeface,
                nowPlaying = nowPlaying,
                lyricsState = lyricsState,
                activeLineIdx = activeLineIdx,
                positionMs = positionMs,
                nowElapsedMs = nowElapsedMs
            )
            WidgetLayoutMode.COMPACT_STRIP -> renderCompactStripLayout(
                canvas = canvas,
                w = w,
                h = h,
                palette = palette,
                primaryTypeface = primaryTypeface,
                subTypeface = subTypeface,
                nowPlaying = nowPlaying,
                lyricsState = lyricsState,
                activeLineIdx = activeLineIdx,
                positionMs = positionMs,
                nowElapsedMs = nowElapsedMs
            )
            WidgetLayoutMode.VERTICAL_TOWER -> renderStackedOrVerticalLayout(
                canvas = canvas,
                w = w,
                h = h,
                palette = palette,
                primaryTypeface = primaryTypeface,
                subTypeface = subTypeface,
                nowPlaying = nowPlaying,
                lyricsState = lyricsState,
                activeLineIdx = activeLineIdx,
                positionMs = positionMs,
                nowElapsedMs = nowElapsedMs,
                forceVerticalTower = true
            )
            else -> renderStackedOrVerticalLayout(
                canvas = canvas,
                w = w,
                h = h,
                palette = palette,
                primaryTypeface = primaryTypeface,
                subTypeface = subTypeface,
                nowPlaying = nowPlaying,
                lyricsState = lyricsState,
                activeLineIdx = activeLineIdx,
                positionMs = positionMs,
                nowElapsedMs = nowElapsedMs,
                forceVerticalTower = false
            )
        }

        val stageBloom = NkbWidgetAnimationTimeline.getStageNkbBloomFactor(nowElapsedMs)
        if (stageBloom > 0.04f) {
            val bloomBmp = NikoFastGaussianBlur.applyNkbBloomOverlay(
                source = bmp,
                nkbIntensity = DynamicWidgetSettings.nkbIntensity,
                bloomFactor = stageBloom
            )
            if (bloomBmp != null) {
                canvas.save()
                canvas.clipPath(clipPath)
                bgPaint.alpha = (stageBloom * 210f).roundToInt().coerceIn(0, 235)
                canvas.drawBitmap(
                    bloomBmp,
                    Rect(0, 0, bloomBmp.width, bloomBmp.height),
                    cardBoundsRect,
                    bgPaint
                )
                bgPaint.alpha = 255
                canvas.restore()
                bloomBmp.recycle()
            }
        }

        return swapCompletedFrameToRingBuffer(bmp)
    }

    private fun renderStackedOrVerticalLayout(
        canvas: Canvas,
        w: Int,
        h: Int,
        palette: ResolvedPalette,
        primaryTypeface: Typeface,
        subTypeface: Typeface,
        nowPlaying: NowPlayingMediaState,
        lyricsState: LiveLyricsResult,
        activeLineIdx: Int,
        positionMs: Long,
        nowElapsedMs: Long,
        forceVerticalTower: Boolean
    ) {
        val wf = w.toFloat()
        val hf = h.toFloat()
        val padH = (wf * 0.045f).coerceIn(12f, 28f)
        val left = padH
        val right = wf - padH
        val innerW = (right - left).coerceAtLeast(100f)

        val pillTop = hf * 0.015f
        val pillBottom = hf * 0.09f
        val headerTop = hf * 0.09f
        val headerBottom = hf * 0.34f
        val stageTop = hf * 0.34f
        val stageBottom = hf * 0.74f
        val progressTop = hf * 0.74f
        val progressBottom = hf * 0.84f
        val buttonsTop = hf * 0.84f
        val buttonsBottom = hf * 0.985f

        drawProviderHeaderPill(
            canvas = canvas,
            centerX = wf * 0.5f,
            centerY = (pillTop + pillBottom) * 0.5f,
            maxWidth = innerW,
            refDim = min(w, h).toFloat(),
            palette = palette,
            primaryTypeface = primaryTypeface,
            nowPlaying = nowPlaying,
            lyricsState = lyricsState,
            nowElapsedMs = nowElapsedMs
        )

        val headerH = (headerBottom - headerTop).coerceAtLeast(40f)
        val useCenteredTopArt = forceVerticalTower || (h >= 360 && w >= 240)
        if (useCenteredTopArt) {
            val titleSize = (min(wf, hf) * 0.042f).coerceIn(15f, 26f)
            val artistSize = (titleSize * 0.76f).coerceIn(12f, 20f)
            val textBlockH = titleSize + artistSize + 6f
            val artSize = min(innerW * 0.42f, (headerH - textBlockH - 8f)).coerceIn(42f, 136f)
            val artLeft = (wf - artSize) * 0.5f
            artBoundsRect.set(artLeft, headerTop + 2f, artLeft + artSize, headerTop + 2f + artSize)
            drawAlbumArtWithWavyRing(
                canvas = canvas,
                rect = artBoundsRect,
                albumArt = nowPlaying.albumArt,
                palette = palette
            )
            val centerTitles = forceVerticalTower || (w < h * 0.85f)
            drawTitleAndArtistBlock(
                canvas = canvas,
                left = left,
                right = right,
                topY = artBoundsRect.bottom + 4f,
                title = nowPlaying.title.ifBlank { "Not Playing" },
                artist = nowPlaying.artist.ifBlank { "Play any track to sync lyrics" },
                titleSizePx = titleSize,
                artistSizePx = artistSize,
                centered = centerTitles,
                palette = palette,
                primaryTypeface = primaryTypeface,
                subTypeface = subTypeface
            )
        } else {
            val thumbSize = (headerH * 0.78f).coerceIn(40f, 82f)
            val thumbTop = headerTop + (headerH - thumbSize) * 0.5f
            artBoundsRect.set(left, thumbTop, left + thumbSize, thumbTop + thumbSize)
            drawAlbumArtWithWavyRing(
                canvas = canvas,
                rect = artBoundsRect,
                albumArt = nowPlaying.albumArt,
                palette = palette
            )
            val textLeft = artBoundsRect.right + (wf * 0.03f).coerceIn(10f, 18f)
            val titleSize = (thumbSize * 0.40f).coerceIn(15f, 24f)
            val artistSize = (titleSize * 0.78f).coerceIn(12f, 18f)
            val textTop = thumbTop + (thumbSize - (titleSize + artistSize + 4f)) * 0.5f
            drawTitleAndArtistBlock(
                canvas = canvas,
                left = textLeft,
                right = right,
                topY = textTop,
                title = nowPlaying.title.ifBlank { "Not Playing" },
                artist = nowPlaying.artist.ifBlank { "Play any track to sync lyrics" },
                titleSizePx = titleSize,
                artistSizePx = artistSize,
                centered = false,
                palette = palette,
                primaryTypeface = primaryTypeface,
                subTypeface = subTypeface
            )
        }

        stageBoundsRect.set(left, stageTop + 2f, right, stageBottom - 2f)
        drawLiveLyricsStageBox(
            canvas = canvas,
            stageRect = stageBoundsRect,
            palette = palette,
            primaryTypeface = primaryTypeface,
            subTypeface = subTypeface,
            nowPlaying = nowPlaying,
            lyricsState = lyricsState,
            activeLineIdx = activeLineIdx,
            positionMs = positionMs,
            nowElapsedMs = nowElapsedMs
        )

        drawWavyProgressRow(
            canvas = canvas,
            left = left,
            right = right,
            centerY = (progressTop + progressBottom) * 0.5f,
            rowHeight = (progressBottom - progressTop),
            nowPlaying = nowPlaying,
            positionMs = positionMs,
            palette = palette,
            primaryTypeface = primaryTypeface
        )

        val btnBandH = (buttonsBottom - buttonsTop).coerceAtLeast(28f)
        val btnSize = min((wf / 6f) * 0.76f, btnBandH * 0.78f).coerceIn(24f, 58f)
        drawSixTransportButtonsInEqualColumns(
            canvas = canvas,
            bandLeft = 0f,
            bandRight = wf,
            centerY = (buttonsTop + buttonsBottom) * 0.5f,
            btnSize = btnSize,
            nowPlaying = nowPlaying,
            palette = palette,
            primaryTypeface = primaryTypeface,
            nowElapsedMs = nowElapsedMs
        )
    }

    /**
     * LAYOUT 2: `SPLIT_SIDE_BY_SIDE`
     * Percentage-locked to `widget_dynamic_lyrics_split.xml`:
     *   - Left 42% (`0.00w .. 0.42w`): Header `0%..58%` Y, Progress `58%..74%` Y, 6 Buttons `74%..100%` Y
     *   - Right 58% (`0.42w .. 1.00w`): Provider Pill `0%..15%` Y, Lyrics Stage `15%..100%` Y
     */
    private fun renderSplitSideBySideLayout(
        canvas: Canvas,
        w: Int,
        h: Int,
        palette: ResolvedPalette,
        primaryTypeface: Typeface,
        subTypeface: Typeface,
        nowPlaying: NowPlayingMediaState,
        lyricsState: LiveLyricsResult,
        activeLineIdx: Int,
        positionMs: Long,
        nowElapsedMs: Long
    ) {
        val wf = w.toFloat()
        val hf = h.toFloat()
        val pad = (min(wf, hf) * 0.040f).coerceIn(10f, 22f)

        val splitX = wf * 0.42f
        val leftPaneLeft = pad
        val leftPaneRight = splitX - pad * 0.45f
        val leftPaneW = (leftPaneRight - leftPaneLeft).coerceAtLeast(80f)

        val rightPaneLeft = splitX + pad * 0.45f
        val rightPaneRight = wf - pad
        val rightPaneW = (rightPaneRight - rightPaneLeft).coerceAtLeast(100f)

        val leftHeaderTop = pad
        val leftHeaderBottom = hf * 0.58f
        val leftProgressTop = hf * 0.58f
        val leftProgressBottom = hf * 0.74f
        val leftButtonsTop = hf * 0.74f
        val leftButtonsBottom = hf - pad * 0.5f

        val headerAvailH = (leftHeaderBottom - leftHeaderTop).coerceAtLeast(40f)
        val titleSize = (min(leftPaneW, hf) * 0.072f).coerceIn(13f, 20f)
        val artistSize = (titleSize * 0.78f).coerceIn(10.5f, 16f)
        val textBlockH = titleSize + artistSize + 6f

        if (headerAvailH >= textBlockH + 50f && hf >= 185f) {
            // Strictly square Album Art (width == height == artSize)
            val artSize = min(leftPaneW * 0.52f, headerAvailH - textBlockH - 8f).coerceIn(38f, 114f)
            val artLeft = leftPaneLeft + (leftPaneW - artSize) * 0.5f
            artBoundsRect.set(artLeft, leftHeaderTop, artLeft + artSize, leftHeaderTop + artSize)
            drawAlbumArtWithWavyRing(
                canvas = canvas,
                rect = artBoundsRect,
                albumArt = nowPlaying.albumArt,
                palette = palette
            )
            drawTitleAndArtistBlock(
                canvas = canvas,
                left = leftPaneLeft,
                right = leftPaneRight,
                topY = artBoundsRect.bottom + 5f,
                title = nowPlaying.title.ifBlank { "Not Playing" },
                artist = nowPlaying.artist.ifBlank { "Play music" },
                titleSizePx = titleSize,
                artistSizePx = artistSize,
                centered = true,
                palette = palette,
                primaryTypeface = primaryTypeface,
                subTypeface = subTypeface
            )
        } else {
            val thumbSize = min(headerAvailH * 0.80f, leftPaneW * 0.32f).coerceIn(34f, 62f)
            val thumbTop = leftHeaderTop + (headerAvailH - thumbSize) * 0.5f
            artBoundsRect.set(leftPaneLeft, thumbTop, leftPaneLeft + thumbSize, thumbTop + thumbSize)
            drawAlbumArtWithWavyRing(
                canvas = canvas,
                rect = artBoundsRect,
                albumArt = nowPlaying.albumArt,
                palette = palette
            )
            val tLeft = artBoundsRect.right + 8f
            val tTop = thumbTop + (thumbSize - (titleSize + artistSize + 3f)) * 0.5f
            drawTitleAndArtistBlock(
                canvas = canvas,
                left = tLeft,
                right = leftPaneRight,
                topY = tTop,
                title = nowPlaying.title.ifBlank { "Not Playing" },
                artist = nowPlaying.artist.ifBlank { "Play music" },
                titleSizePx = titleSize,
                artistSizePx = artistSize,
                centered = false,
                palette = palette,
                primaryTypeface = primaryTypeface,
                subTypeface = subTypeface
            )
        }

        drawWavyProgressRow(
            canvas = canvas,
            left = leftPaneLeft,
            right = leftPaneRight,
            centerY = (leftProgressTop + leftProgressBottom) * 0.5f,
            rowHeight = (leftProgressBottom - leftProgressTop),
            nowPlaying = nowPlaying,
            positionMs = positionMs,
            palette = palette,
            primaryTypeface = primaryTypeface
        )

        val btnBandH = (leftButtonsBottom - leftButtonsTop).coerceAtLeast(24f)
        val btnSize = min((splitX / 6f) * 0.82f, btnBandH * 0.78f).coerceIn(22f, 46f)
        drawSixTransportButtonsInEqualColumns(
            canvas = canvas,
            bandLeft = 0f,
            bandRight = splitX,
            centerY = (leftButtonsTop + leftButtonsBottom) * 0.5f,
            btnSize = btnSize,
            nowPlaying = nowPlaying,
            palette = palette,
            primaryTypeface = primaryTypeface,
            nowElapsedMs = nowElapsedMs
        )

        val rightPillBottom = hf * 0.15f
        drawProviderHeaderPill(
            canvas = canvas,
            centerX = rightPaneLeft + rightPaneW * 0.5f,
            centerY = (pad * 0.5f + rightPillBottom) * 0.5f,
            maxWidth = rightPaneW,
            refDim = min(rightPaneW, hf),
            palette = palette,
            primaryTypeface = primaryTypeface,
            nowPlaying = nowPlaying,
            lyricsState = lyricsState,
            nowElapsedMs = nowElapsedMs
        )

        stageBoundsRect.set(rightPaneLeft, rightPillBottom + 2f, rightPaneRight, hf - pad)
        drawLiveLyricsStageBox(
            canvas = canvas,
            stageRect = stageBoundsRect,
            palette = palette,
            primaryTypeface = primaryTypeface,
            subTypeface = subTypeface,
            nowPlaying = nowPlaying,
            lyricsState = lyricsState,
            activeLineIdx = activeLineIdx,
            positionMs = positionMs,
            nowElapsedMs = nowElapsedMs
        )
    }

    /**
     * LAYOUT 4: `COMPACT_STRIP`
     * Percentage-locked to `widget_dynamic_lyrics_compact.xml`:
     *   - Top 32% Y: Left 44% X Header, Right 56% X 6 Transport Buttons
     *   - Middle 50% Y (`32%..82%`): Live Lyrics Stage
     *   - Bottom 18% Y (`82%..100%`): Wavy Progress Bar
     */
    private fun renderCompactStripLayout(
        canvas: Canvas,
        w: Int,
        h: Int,
        palette: ResolvedPalette,
        primaryTypeface: Typeface,
        subTypeface: Typeface,
        nowPlaying: NowPlayingMediaState,
        lyricsState: LiveLyricsResult,
        activeLineIdx: Int,
        positionMs: Long,
        nowElapsedMs: Long
    ) {
        val wf = w.toFloat()
        val hf = h.toFloat()
        val padH = (wf * 0.032f).coerceIn(10f, 20f)

        val topBandBottom = hf * 0.32f
        val stageBandTop = hf * 0.32f
        val stageBandBottom = hf * 0.82f
        val progressBandTop = hf * 0.82f
        val progressBandBottom = hf * 0.98f

        val headerSplitX = wf * 0.44f
        val thumbSize = (topBandBottom * 0.72f).coerceIn(28f, 52f)
        val thumbTop = (topBandBottom - thumbSize) * 0.5f
        artBoundsRect.set(padH, thumbTop, padH + thumbSize, thumbTop + thumbSize)
        drawAlbumArtWithWavyRing(
            canvas = canvas,
            rect = artBoundsRect,
            albumArt = nowPlaying.albumArt,
            palette = palette
        )

        val titleSize = (thumbSize * 0.42f).coerceIn(13f, 20f)
        val artistSize = (titleSize * 0.78f).coerceIn(10.5f, 15.5f)
        val titleTop = thumbTop + (thumbSize - (titleSize + artistSize + 3f)) * 0.5f
        drawTitleAndArtistBlock(
            canvas = canvas,
            left = artBoundsRect.right + 8f,
            right = headerSplitX - 6f,
            topY = titleTop,
            title = nowPlaying.title.ifBlank { "Not Playing" },
            artist = nowPlaying.artist.ifBlank { "Play music" },
            titleSizePx = titleSize,
            artistSizePx = artistSize,
            centered = false,
            palette = palette,
            primaryTypeface = primaryTypeface,
            subTypeface = subTypeface
        )

        val btnSize = min(((wf - headerSplitX) / 6f) * 0.80f, topBandBottom * 0.74f).coerceIn(22f, 42f)
        drawSixTransportButtonsInEqualColumns(
            canvas = canvas,
            bandLeft = headerSplitX,
            bandRight = wf,
            centerY = topBandBottom * 0.5f,
            btnSize = btnSize,
            nowPlaying = nowPlaying,
            palette = palette,
            primaryTypeface = primaryTypeface,
            nowElapsedMs = nowElapsedMs
        )

        stageBoundsRect.set(padH, stageBandTop + 2f, wf - padH, stageBandBottom - 2f)
        drawLiveLyricsStageBox(
            canvas = canvas,
            stageRect = stageBoundsRect,
            palette = palette,
            primaryTypeface = primaryTypeface,
            subTypeface = subTypeface,
            nowPlaying = nowPlaying,
            lyricsState = lyricsState,
            activeLineIdx = activeLineIdx,
            positionMs = positionMs,
            nowElapsedMs = nowElapsedMs
        )

        drawWavyProgressRow(
            canvas = canvas,
            left = padH,
            right = wf - padH,
            centerY = (progressBandTop + progressBandBottom) * 0.5f,
            rowHeight = (progressBandBottom - progressBandTop),
            nowPlaying = nowPlaying,
            positionMs = positionMs,
            palette = palette,
            primaryTypeface = primaryTypeface
        )
    }

    private fun drawProviderHeaderPill(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        maxWidth: Float,
        refDim: Float,
        palette: ResolvedPalette,
        primaryTypeface: Typeface,
        nowPlaying: NowPlayingMediaState,
        lyricsState: LiveLyricsResult,
        nowElapsedMs: Long
    ) {
        val availableCount = lyricsState.availableProviders.size
        val label = when {
            lyricsState.isLoading -> "Enquiring All Lyrics Providers..."
            lyricsState.provider != null -> {
                val suffix = if (availableCount > 1) " ($availableCount found • tap to switch)" else ""
                "Lyrics from ${lyricsState.provider.displayName}$suffix"
            }
            nowPlaying.title.isBlank() -> "Live Lyrics • Ready for Playback"
            else -> "No Lyrics Found • Tap to retry"
        }

        val (scale, bloom) = NkbWidgetAnimationTimeline.getButtonNkbState(
            NkbWidgetAnimationTimeline.WidgetButtonId.PROVIDER,
            nowElapsedMs
        )

        textPaint.typeface = primaryTypeface
        textPaint.textSize = (refDim * 0.029f).coerceIn(11f, 17.5f)
        textPaint.color = withAlpha(blendColors(palette.fgBaseColor, palette.accentColor, 0.25f), 0.78f)
        textPaint.textAlign = Paint.Align.CENTER

        val clipped = TextUtils.ellipsize(label, textPaint, maxWidth * 0.92f, TextUtils.TruncateAt.END).toString()
        val textW = textPaint.measureText(clipped)
        val pillH = textPaint.textSize * 1.5f
        val pillW = (textW + textPaint.textSize * 1.4f).coerceAtMost(maxWidth)

        canvas.save()
        if (scale != 1.0f) {
            canvas.scale(scale, scale, centerX, centerY)
        }
        if (bloom > 0.02f) {
            glowPaint.color = withAlpha(palette.accentColor, (bloom * 0.42f).coerceIn(0f, 0.58f))
            canvas.drawRoundRect(
                centerX - pillW * 0.54f,
                centerY - pillH * 0.55f,
                centerX + pillW * 0.54f,
                centerY + pillH * 0.55f,
                pillH * 0.6f,
                pillH * 0.6f,
                glowPaint
            )
        }
        val baseline = centerY - (textPaint.descent() + textPaint.ascent()) * 0.5f
        canvas.drawText(clipped, centerX, baseline, textPaint)
        canvas.restore()
    }

    private fun drawAlbumArtWithWavyRing(
        canvas: Canvas,
        rect: RectF,
        albumArt: Bitmap?,
        palette: ResolvedPalette
    ) {
        val size = rect.width()
        val radius = (size * 0.22f).coerceIn(10f, 30f)
        val cx = rect.centerX()
        val cy = rect.centerY()

        val waveAmpRatio = NkbWidgetAnimationTimeline.waveAmplitudeRatio * DynamicWidgetSettings.waveAmplitude
        if (waveAmpRatio > 0.03f && size >= 48f) {
            val baseR = size * 0.53f
            val amp = (size * 0.024f * waveAmpRatio).coerceIn(0.6f, 4.0f)
            val phase = NkbWidgetAnimationTimeline.ringPhaseRad
            val lobes = 8
            val steps = 72
            ringTrackPath.reset()
            for (i in 0..steps) {
                val theta = (i.toFloat() / steps.toFloat()) * (2f * PI.toFloat())
                val r = baseR + amp * sin((lobes * theta + phase).toDouble()).toFloat()
                val x = cx + r * cos(theta.toDouble()).toFloat()
                val y = cy + r * sin(theta.toDouble()).toFloat()
                if (i == 0) ringTrackPath.moveTo(x, y) else ringTrackPath.lineTo(x, y)
            }
            ringTrackPath.close()
            strokePaint.strokeWidth = (size * 0.022f).coerceIn(1.4f, 2.8f)
            strokePaint.color = withAlpha(palette.accentColor, (0.36f * waveAmpRatio).coerceIn(0.05f, 0.46f))
            canvas.drawPath(ringTrackPath, strokePaint)
        }

        fillPaint.color = palette.secondaryContainerColor
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        if (albumArt != null && !albumArt.isRecycled) {
            canvas.save()
            stageClipPath.reset()
            stageClipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.clipPath(stageClipPath)
            canvas.drawBitmap(
                albumArt,
                Rect(0, 0, albumArt.width, albumArt.height),
                rect,
                bgPaint
            )
            canvas.restore()
        } else {
            strokePaint.color = palette.accentColor
            strokePaint.strokeWidth = (size * 0.045f).coerceIn(1.8f, 3.6f)
            canvas.drawCircle(cx, cy, size * 0.24f, strokePaint)
            fillPaint.color = palette.accentColor
            canvas.drawCircle(cx, cy, size * 0.07f, fillPaint)
        }

        strokePaint.strokeWidth = (size * 0.02f).coerceIn(1.2f, 2.4f)
        strokePaint.color = withAlpha(palette.accentColor, 0.42f)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
    }

    private fun drawTitleAndArtistBlock(
        canvas: Canvas,
        left: Float,
        right: Float,
        topY: Float,
        title: String,
        artist: String,
        titleSizePx: Float,
        artistSizePx: Float,
        centered: Boolean,
        palette: ResolvedPalette,
        primaryTypeface: Typeface,
        subTypeface: Typeface
    ) {
        val maxW = (right - left).coerceAtLeast(40f)
        val anchorX = if (centered) (left + right) * 0.5f else left
        val align = if (centered) Paint.Align.CENTER else Paint.Align.LEFT

        textPaint.textAlign = align
        textPaint.typeface = primaryTypeface
        textPaint.textSize = titleSizePx
        textPaint.color = blendColors(palette.fgBaseColor, palette.accentColor, 0.14f)
        val ellipsizedTitle = TextUtils.ellipsize(title, textPaint, maxW, TextUtils.TruncateAt.END).toString()
        val titleBaseline = topY - textPaint.ascent()
        canvas.drawText(ellipsizedTitle, anchorX, titleBaseline, textPaint)

        textPaint.typeface = subTypeface
        textPaint.textSize = artistSizePx
        textPaint.color = palette.fgSecondaryColor
        val ellipsizedArtist = TextUtils.ellipsize(artist, textPaint, maxW, TextUtils.TruncateAt.END).toString()
        val artistBaseline = titleBaseline + textPaint.descent() + 2f - textPaint.ascent()
        canvas.drawText(ellipsizedArtist, anchorX, artistBaseline, textPaint)
    }

    private fun drawLiveLyricsStageBox(
        canvas: Canvas,
        stageRect: RectF,
        palette: ResolvedPalette,
        primaryTypeface: Typeface,
        subTypeface: Typeface,
        nowPlaying: NowPlayingMediaState,
        lyricsState: LiveLyricsResult,
        activeLineIdx: Int,
        positionMs: Long,
        nowElapsedMs: Long
    ) {
        val stageW = stageRect.width()
        val stageH = stageRect.height()
        if (stageW <= 20f || stageH <= 20f) return

        val stageRadius = (min(stageW, stageH) * 0.12f).coerceIn(14f, 26f)
        stageClipPath.reset()
        stageClipPath.addRoundRect(stageRect, stageRadius, stageRadius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(stageClipPath)

        fillPaint.color = palette.stageBgColor
        canvas.drawRect(stageRect, fillPaint)

        if (!palette.isMaterialYou) {
            drawBokehStageOrbs(
                canvas = canvas,
                stageRect = stageRect,
                palette = palette,
                isPlaying = nowPlaying.isPlaying
            )
        }

        val lines = lyricsState.lines
        val padX = (stageW * 0.055f).coerceIn(10f, 24f)
        val innerTextW = (stageW - padX * 2f).coerceAtLeast(60f).toInt()
        val stageCx = stageRect.centerX()
        val stageCy = stageRect.centerY()

        val fontScale = DynamicWidgetSettings.fontScale.coerceIn(0.75f, 1.40f)
        val baseActiveSp = min(stageW / 13.5f, stageH / 5.4f).coerceIn(13.5f, 27f) * fontScale
        val neighborSp = (baseActiveSp * 0.78f).coerceIn(11f, 20.5f)
        val outerSp = (baseActiveSp * 0.66f).coerceIn(9.5f, 16.5f)

        when {
            nowPlaying.title.isBlank() -> {
                drawCenteredWrappedText(
                    canvas = canvas,
                    text = "Play any music track to stream live word & line synced lyrics",
                    centerX = stageCx,
                    centerY = stageCy,
                    maxWidth = innerTextW,
                    textSizePx = neighborSp,
                    color = palette.fgSecondaryColor,
                    typeface = primaryTypeface,
                    maxLines = 3
                )
            }
            lyricsState.isLoading -> {
                drawWavyCircularCountdownRing(
                    canvas = canvas,
                    cx = stageCx,
                    cy = stageCy - neighborSp * 0.7f,
                    radius = (min(stageW, stageH) * 0.11f).coerceIn(14f, 26f),
                    progress = 0.68f,
                    activeColor = palette.activeSungWordColor
                )
                drawCenteredWrappedText(
                    canvas = canvas,
                    text = "Syncing live word-by-word lyrics…",
                    centerX = stageCx,
                    centerY = stageCy + neighborSp * 1.15f,
                    maxWidth = innerTextW,
                    textSizePx = neighborSp * 0.92f,
                    color = palette.fgSecondaryColor,
                    typeface = primaryTypeface,
                    maxLines = 2
                )
            }
            lines.isEmpty() -> {
                drawCenteredWrappedText(
                    canvas = canvas,
                    text = lyricsState.errorMessage ?: "Instrumental or no synced lyrics found",
                    centerX = stageCx,
                    centerY = stageCy,
                    maxWidth = innerTextW,
                    textSizePx = neighborSp,
                    color = palette.fgSecondaryColor,
                    typeface = primaryTypeface,
                    maxLines = 3
                )
            }
            else -> {
                val firstLineStart = lines.first().startTimeMs
                val isInIntroGap = firstLineStart > 1000L && positionMs in 1L until firstLineStart
                val introProg = if (isInIntroGap) (positionMs.toFloat() / firstLineStart.toFloat()).coerceIn(0.02f, 1f) else 0f

                val activeLineObj = if (activeLineIdx >= 0) lines.getOrNull(activeLineIdx) else null
                val upcomingLineObj = if (activeLineIdx >= 0) lines.getOrNull(activeLineIdx + 1) else lines.firstOrNull()
                val currentVocalEndMs = when {
                    activeLineObj == null -> 0L
                    activeLineObj.words.isNotEmpty() -> activeLineObj.words.last().endTimeMs
                    else -> activeLineObj.startTimeMs + (activeLineObj.text.length * 95L).coerceIn(2200L, 4400L)
                }
                val isInMidGap = if (activeLineObj != null && upcomingLineObj != null) {
                    val gapDur = upcomingLineObj.startTimeMs - currentVocalEndMs
                    gapDur >= 2200L && positionMs in (currentVocalEndMs + 120L) until upcomingLineObj.startTimeMs
                } else false
                val midGapProg = if (isInMidGap && upcomingLineObj != null) {
                    val totalGap = (upcomingLineObj.startTimeMs - currentVocalEndMs).coerceAtLeast(1L)
                    ((positionMs - currentVocalEndMs).toFloat() / totalGap.toFloat()).coerceIn(0.02f, 1f)
                } else 0f

                val showInstrumentalRing = isInIntroGap || isInMidGap
                val ringProgress = if (isInIntroGap) introProg else midGapProg

                val slideFrac = NkbWidgetAnimationTimeline.getLineSlideOffsetFraction(nowElapsedMs)
                val lineEntryProg = NkbWidgetAnimationTimeline.getLineEntryEasedProgress(nowElapsedMs)
                val slideOffsetY = slideFrac * (baseActiveSp * 1.42f)

                val centerIdx = if (activeLineIdx >= 0) activeLineIdx else 0
                val prev2Line = if (activeLineIdx >= 2) lines.getOrNull(centerIdx - 2) else null
                val prev1Line = if (activeLineIdx >= 1) lines.getOrNull(centerIdx - 1) else null
                val currLine = lines.getOrNull(centerIdx)
                val next1Line = lines.getOrNull(centerIdx + 1)
                val next2Line = lines.getOrNull(centerIdx + 2)

                val isCompactStage = stageH < 120f
                val isLineActiveNow = activeLineIdx >= 0 && !isInIntroGap
                val activeLineScale = 0.93f + 0.07f * lineEntryProg

                val activeBlockH = if (currLine != null) {
                    canvas.save()
                    canvas.scale(activeLineScale, activeLineScale, stageCx, stageCy + slideOffsetY)
                    val hDrawn = drawProgressiveSweepActiveLyricLine(
                        canvas = canvas,
                        line = currLine,
                        isLineActiveNow = isLineActiveNow,
                        lineEntryProgress = lineEntryProg,
                        positionMs = positionMs,
                        centerX = stageCx,
                        centerY = stageCy + slideOffsetY,
                        maxWidth = innerTextW,
                        maxBlockHeight = stageH * 0.56f,
                        fontSizePx = baseActiveSp,
                        palette = palette,
                        primaryTypeface = primaryTypeface
                    )
                    canvas.restore()
                    hDrawn
                } else {
                    baseActiveSp * 1.3f
                }

                val activeHalfH = activeBlockH * 0.5f
                val lineGap = (stageH * 0.040f).coerceIn(4f, 11f)
                val showOuterLines = stageH >= 180f && activeBlockH <= stageH * 0.36f

                val upperAnchorBottomY = stageCy + slideOffsetY - activeHalfH - lineGap
                if (showInstrumentalRing && !isCompactStage) {
                    val ringR = (min(stageW, stageH) * 0.085f).coerceIn(12f, 22f)
                    drawWavyCircularCountdownRing(
                        canvas = canvas,
                        cx = stageCx,
                        cy = upperAnchorBottomY - ringR - 2f,
                        radius = ringR,
                        progress = ringProgress,
                        activeColor = palette.activeSungWordColor
                    )
                } else if (!isCompactStage && prev1Line != null) {
                    val prev1Color = blendColors(palette.activeSungWordColor, palette.neighborLineColor, lineEntryProg)
                    val prev1Scale = 1.05f - 0.05f * lineEntryProg
                    val p1CenterY = upperAnchorBottomY - neighborSp * 0.65f
                    canvas.save()
                    canvas.scale(prev1Scale, prev1Scale, stageCx, p1CenterY)
                    val p1H = drawCenteredWrappedText(
                        canvas = canvas,
                        text = prev1Line.text,
                        centerX = stageCx,
                        centerY = p1CenterY,
                        maxWidth = innerTextW,
                        textSizePx = neighborSp,
                        color = prev1Color,
                        typeface = primaryTypeface,
                        maxLines = if (showOuterLines) 1 else 2
                    )
                    canvas.restore()

                    if (showOuterLines && prev2Line != null) {
                        val p2CenterY = upperAnchorBottomY - p1H - lineGap * 0.8f - outerSp * 0.6f
                        if (p2CenterY - outerSp * 0.6f >= stageRect.top + 4f) {
                            drawCenteredWrappedText(
                                canvas = canvas,
                                text = prev2Line.text,
                                centerX = stageCx,
                                centerY = p2CenterY,
                                maxWidth = innerTextW,
                                textSizePx = outerSp,
                                color = palette.outerLineColor,
                                typeface = subTypeface,
                                maxLines = 1
                            )
                        }
                    }
                }

                val lowerAnchorTopY = stageCy + slideOffsetY + activeHalfH + lineGap
                if (!isCompactStage && next1Line != null) {
                    val n1H = drawCenteredWrappedText(
                        canvas = canvas,
                        text = next1Line.text,
                        centerX = stageCx,
                        centerY = lowerAnchorTopY + neighborSp * 0.65f,
                        maxWidth = innerTextW,
                        textSizePx = neighborSp,
                        color = palette.neighborLineColor,
                        typeface = primaryTypeface,
                        maxLines = if (showOuterLines) 1 else 2
                    )
                    if (showOuterLines && next2Line != null) {
                        val n2CenterY = lowerAnchorTopY + n1H + lineGap * 0.8f + outerSp * 0.6f
                        if (n2CenterY + outerSp * 0.6f <= stageRect.bottom - 4f) {
                            drawCenteredWrappedText(
                                canvas = canvas,
                                text = next2Line.text,
                                centerX = stageCx,
                                centerY = n2CenterY,
                                maxWidth = innerTextW,
                                textSizePx = outerSp,
                                color = palette.outerLineColor,
                                typeface = subTypeface,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        val vignetteH = (stageH * 0.11f).coerceIn(10f, 24f)
        val vignetteColor = withAlpha(palette.cardSurfaceTop, if (palette.isMaterialYou) 0.55f else 0.28f)
        fillPaint.shader = LinearGradient(
            0f,
            stageRect.top,
            0f,
            stageRect.top + vignetteH,
            vignetteColor,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(stageRect.left, stageRect.top, stageRect.right, stageRect.top + vignetteH, fillPaint)

        fillPaint.shader = LinearGradient(
            0f,
            stageRect.bottom - vignetteH,
            0f,
            stageRect.bottom,
            Color.TRANSPARENT,
            vignetteColor,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(stageRect.left, stageRect.bottom - vignetteH, stageRect.right, stageRect.bottom, fillPaint)
        fillPaint.shader = null

        canvas.restore()

        strokePaint.strokeWidth = 1.4f
        strokePaint.color = palette.stageBorderColor
        canvas.drawRoundRect(stageRect, stageRadius, stageRadius, strokePaint)
    }

    private fun drawBokehStageOrbs(
        canvas: Canvas,
        stageRect: RectF,
        palette: ResolvedPalette,
        isPlaying: Boolean
    ) {
        val w = stageRect.width()
        val h = stageRect.height()
        val minDim = min(w, h)
        val tSec = NkbWidgetAnimationTimeline.bokehTimeSec
        val intensity = if (isPlaying) 1.0f else 0.38f
        val orbBmp = cachedBokehOrbBitmap
        val srcRect = Rect(0, 0, orbBmp.width, orbBmp.height)

        for (i in 0 until 6) {
            val fi = i.toFloat()
            val fadeWave = ((sin((tSec * (0.58f + i * 0.11f) + fi * 1.35f).toDouble()) + 1.0) * 0.5).toFloat()
            val scaleWave = ((cos((tSec * (0.44f + i * 0.09f) + fi * 0.95f).toDouble()) + 1.0) * 0.5).toFloat()
            val alpha = ((0.09f + fadeWave * 0.30f) * intensity).coerceIn(0.03f, 0.38f)

            val baseX = when (i) {
                0 -> 0.20f; 1 -> 0.78f; 2 -> 0.28f; 3 -> 0.74f; 4 -> 0.50f; else -> 0.52f
            }
            val baseY = when (i) {
                0 -> 0.24f; 1 -> 0.28f; 2 -> 0.76f; 3 -> 0.72f; 4 -> 0.36f; else -> 0.66f
            }
            val driftX = sin((tSec * (0.31f + i * 0.04f) + fi * 1.2f).toDouble()).toFloat() * 0.18f
            val driftY = cos((tSec * (0.27f + i * 0.05f) + fi * 1.5f).toDouble()).toFloat() * 0.18f

            val cx = stageRect.left + ((baseX + driftX).coerceIn(0.08f, 0.92f)) * w
            val cy = stageRect.top + ((baseY + driftY).coerceIn(0.08f, 0.92f)) * h
            val r = (minDim * (0.32f + scaleWave * 0.24f)).coerceIn(36f, 160f)

            val tintColor = if (i % 2 == 0) {
                blendColors(palette.accentColor, Color.WHITE, 0.25f)
            } else {
                blendColors(palette.accentColor, 0xFFFF7EB3.toInt(), 0.24f)
            }
            bgPaint.colorFilter = android.graphics.PorterDuffColorFilter(
                withAlpha(tintColor, alpha),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            bokehOrbRect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawBitmap(orbBmp, srcRect, bokehOrbRect, bgPaint)
        }
        bgPaint.colorFilter = null
    }

    private fun drawProgressiveSweepActiveLyricLine(
        canvas: Canvas,
        line: SyncedLyricLine,
        isLineActiveNow: Boolean,
        lineEntryProgress: Float,
        positionMs: Long,
        centerX: Float,
        centerY: Float,
        maxWidth: Int,
        maxBlockHeight: Float,
        fontSizePx: Float,
        palette: ResolvedPalette,
        primaryTypeface: Typeface
    ): Float {
        if (!isLineActiveNow || line.words.isEmpty()) {
            val lineColor = if (isLineActiveNow) {
                blendColors(palette.neighborLineColor, palette.activeSungWordColor, lineEntryProgress)
            } else {
                palette.neighborLineColor
            }
            val glowCol = if (isLineActiveNow && lineEntryProgress > 0.1f) {
                withAlpha(palette.activeSungWordColor, 0.28f * lineEntryProgress)
            } else 0
            val charLen = line.text.length
            val autoFitSp = when {
                charLen > 68 -> fontSizePx * 0.78f
                charLen > 48 -> fontSizePx * 0.88f
                else -> fontSizePx
            }
            return drawCenteredWrappedText(
                canvas = canvas,
                text = line.text,
                centerX = centerX,
                centerY = centerY,
                maxWidth = maxWidth,
                textSizePx = autoFitSp,
                color = lineColor,
                typeface = primaryTypeface,
                maxLines = 4,
                glowColor = glowCol
            )
        }

        val mergedWords = mergeSyllablesIntoWholeWords(line.words)
        if (mergedWords.isEmpty()) {
            return drawCenteredWrappedText(
                canvas = canvas,
                text = line.text,
                centerX = centerX,
                centerY = centerY,
                maxWidth = maxWidth,
                textSizePx = fontSizePx,
                color = palette.activeSungWordColor,
                typeface = primaryTypeface,
                maxLines = 4
            )
        }

        textPaint.typeface = primaryTypeface
        textPaint.textAlign = Paint.Align.LEFT

        var effectiveFontSize = fontSizePx
        val wrappedRows = ArrayList<MutableList<Int>>()
        val rowWidths = ArrayList<Float>()
        var spaceW = 0f

        for (attempt in 0..3) {
            textPaint.textSize = effectiveFontSize
            spaceW = textPaint.measureText(" ")
            wrappedRows.clear()
            rowWidths.clear()

            var currentRow = ArrayList<Int>()
            var currentRowW = 0f

            for (idx in mergedWords.indices) {
                val wText = mergedWords[idx].text
                val wPx = textPaint.measureText(wText)
                val needed = if (currentRow.isEmpty()) wPx else (currentRowW + spaceW + wPx)
                if (currentRow.isNotEmpty() && needed > maxWidth) {
                    wrappedRows.add(currentRow)
                    rowWidths.add(currentRowW)
                    currentRow = ArrayList()
                    currentRow.add(idx)
                    currentRowW = wPx
                } else {
                    currentRow.add(idx)
                    currentRowW = needed
                }
            }
            if (currentRow.isNotEmpty()) {
                wrappedRows.add(currentRow)
                rowWidths.add(currentRowW)
            }

            val lineH = effectiveFontSize * 1.24f
            val totalH = wrappedRows.size * lineH
            if ((wrappedRows.size <= 2 || totalH <= maxBlockHeight) && wrappedRows.size <= 3) {
                break
            }
            effectiveFontSize = (effectiveFontSize * 0.86f).coerceAtLeast(11.5f)
        }

        textPaint.textSize = effectiveFontSize
        spaceW = textPaint.measureText(" ")
        val lineH = effectiveFontSize * 1.24f
        val totalBlockH = wrappedRows.size * lineH
        var rowCenterY = centerY - (totalBlockH - lineH) * 0.5f

        for (rIdx in wrappedRows.indices) {
            val wordIndices = wrappedRows[rIdx]
            val rWidth = rowWidths[rIdx].coerceAtLeast(1f)
            val rowHorizScale = if (rWidth > maxWidth) (maxWidth.toFloat() / rWidth) else 1.0f
            var cursorX = centerX - (rWidth * rowHorizScale) * 0.5f

            if (rowHorizScale < 1.0f) {
                canvas.save()
                canvas.scale(rowHorizScale, rowHorizScale, centerX, rowCenterY)
                cursorX = centerX - rWidth * 0.5f
            }

            for (i in wordIndices.indices) {
                val mw = mergedWords[wordIndices[i]]
                val wordText = mw.text
                val wordW = textPaint.measureText(wordText).coerceAtLeast(1f)
                val sweepProg = mw.computeSweepProgress(positionMs)

                val isActivelySweeping = sweepProg in 0.005f..0.995f
                val liftOffsetPx = if (isActivelySweeping) {
                    -sin(sweepProg * PI.toFloat()) * (effectiveFontSize * 0.042f)
                } else 0f

                val postSungElapsed = (positionMs - mw.endTimeMs).coerceAtLeast(0L)
                val afterglowDecay = (1f - (postSungElapsed.toFloat() / 650f)).coerceIn(0f, 1f)
                val baselineY = rowCenterY + liftOffsetPx - (textPaint.descent() + textPaint.ascent()) * 0.5f

                when {
                    sweepProg <= 0.005f -> {
                        textPaint.shader = null
                        textPaint.clearShadowLayer()
                        textPaint.color = palette.inactiveWordColor
                        canvas.drawText(wordText, cursorX, baselineY, textPaint)
                    }
                    sweepProg >= 0.995f -> {
                        textPaint.shader = null
                        textPaint.color = palette.activeSungWordColor
                        val glowAlpha = 0.22f + 0.14f * afterglowDecay
                        textPaint.setShadowLayer(11f, 0f, 0f, withAlpha(palette.activeSungWordColor, glowAlpha))
                        canvas.drawText(wordText, cursorX, baselineY, textPaint)
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
                            intArrayOf(
                                palette.activeSungWordColor,
                                palette.activeNowSingingWordColor,
                                palette.inactiveWordColor,
                                palette.inactiveWordColor
                            ),
                            floatArrayOf(0f, leadStop, trailStop, 1f),
                            Shader.TileMode.CLAMP
                        )
                        textPaint.setShadowLayer(
                            12f,
                            0f,
                            0f,
                            withAlpha(palette.activeSungWordColor, 0.34f * sweepProg.coerceIn(0.2f, 1f))
                        )
                        canvas.drawText(wordText, cursorX, baselineY, textPaint)
                        textPaint.shader = null
                    }
                }

                cursorX += wordW + spaceW
            }

            if (rowHorizScale < 1.0f) {
                canvas.restore()
            }
            rowCenterY += lineH
        }
        textPaint.shader = null
        textPaint.clearShadowLayer()
        return totalBlockH
    }

    @Suppress("DEPRECATION")
    private fun drawCenteredWrappedText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        maxWidth: Int,
        textSizePx: Float,
        color: Int,
        typeface: Typeface,
        maxLines: Int,
        glowColor: Int = 0
    ): Float {
        if (text.isBlank() || maxWidth <= 10) return 0f
        textPaint.shader = null
        textPaint.typeface = typeface
        textPaint.textSize = textSizePx
        textPaint.color = color
        textPaint.textAlign = Paint.Align.LEFT
        if (glowColor != 0) {
            textPaint.setShadowLayer(12f, 0f, 0f, glowColor)
        } else {
            textPaint.clearShadowLayer()
        }

        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, textPaint, maxWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build()
        } else {
            StaticLayout(text, textPaint, maxWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false)
        }

        val h = layout.height.toFloat()
        canvas.save()
        canvas.translate(centerX - maxWidth * 0.5f, centerY - h * 0.5f)
        layout.draw(canvas)
        canvas.restore()
        textPaint.clearShadowLayer()
        return h
    }

    private fun drawWavyCircularCountdownRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        progress: Float,
        activeColor: Int
    ) {
        val waveAmpRatio = NkbWidgetAnimationTimeline.waveAmplitudeRatio.coerceAtLeast(0.25f) *
            DynamicWidgetSettings.waveAmplitude
        val waveAmp = (radius * 0.11f * waveAmpRatio).coerceIn(0.6f, 3.8f)
        val phaseRad = NkbWidgetAnimationTimeline.ringPhaseRad
        val lobes = 6
        val totalSteps = 96
        val startAngleRad = (-PI / 2.0).toFloat()
        val fullSweepRad = (2.0 * PI).toFloat()

        ringTrackPath.reset()
        for (step in 0..totalSteps) {
            val frac = step.toFloat() / totalSteps.toFloat()
            val theta = startAngleRad + frac * fullSweepRad
            val r = radius + waveAmp * sin((lobes * theta - phaseRad).toDouble()).toFloat()
            val x = cx + r * cos(theta.toDouble()).toFloat()
            val y = cy + r * sin(theta.toDouble()).toFloat()
            if (step == 0) ringTrackPath.moveTo(x, y) else ringTrackPath.lineTo(x, y)
        }
        ringTrackPath.close()
        strokePaint.strokeWidth = (radius * 0.14f).coerceIn(2.0f, 4.0f)
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = withAlpha(activeColor, 0.24f)
        canvas.drawPath(ringTrackPath, strokePaint)

        val clampedProg = progress.coerceIn(0.03f, 1f)
        val wholeSteps = (totalSteps * clampedProg).toInt().coerceAtLeast(1)
        ringActivePath.reset()
        for (step in 0..wholeSteps) {
            val frac = step.toFloat() / totalSteps.toFloat()
            val theta = startAngleRad + frac * fullSweepRad
            val r = radius + waveAmp * sin((lobes * theta - phaseRad).toDouble()).toFloat()
            val x = cx + r * cos(theta.toDouble()).toFloat()
            val y = cy + r * sin(theta.toDouble()).toFloat()
            if (step == 0) ringActivePath.moveTo(x, y) else ringActivePath.lineTo(x, y)
        }
        val endTheta = startAngleRad + clampedProg * fullSweepRad
        val endR = radius + waveAmp * sin((lobes * endTheta - phaseRad).toDouble()).toFloat()
        ringActivePath.lineTo(
            cx + endR * cos(endTheta.toDouble()).toFloat(),
            cy + endR * sin(endTheta.toDouble()).toFloat()
        )
        strokePaint.strokeWidth = (radius * 0.17f).coerceIn(2.4f, 4.8f)
        strokePaint.color = activeColor
        canvas.drawPath(ringActivePath, strokePaint)
    }

    private fun drawWavyProgressRow(
        canvas: Canvas,
        left: Float,
        right: Float,
        centerY: Float,
        rowHeight: Float,
        nowPlaying: NowPlayingMediaState,
        positionMs: Long,
        palette: ResolvedPalette,
        primaryTypeface: Typeface
    ) {
        val durationMs = nowPlaying.durationMs.coerceAtLeast(0L)
        val progress = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f

        val labelSize = (rowHeight * 0.42f).coerceIn(10.5f, 15.5f)
        textPaint.shader = null
        textPaint.typeface = primaryTypeface
        textPaint.textSize = labelSize
        textPaint.color = withAlpha(palette.fgBaseColor, 0.64f)

        val curLabel = formatMmSs(positionMs)
        val totLabel = formatMmSs(durationMs)
        val labelW = max(textPaint.measureText(curLabel), textPaint.measureText("00:00")) + 6f
        val baseline = centerY - (textPaint.descent() + textPaint.ascent()) * 0.5f

        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(curLabel, left, baseline, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(totLabel, right, baseline, textPaint)

        val barLeft = left + labelW + 4f
        val barRight = (right - labelW - 4f).coerceAtLeast(barLeft + 24f)
        val barW = barRight - barLeft
        val activeEndX = barLeft + barW * progress
        val strokeW = (rowHeight * 0.14f).coerceIn(3.0f, 5.2f)
        val waveAmpRatio = NkbWidgetAnimationTimeline.waveAmplitudeRatio * DynamicWidgetSettings.waveAmplitude
        val maxAmpPx = (rowHeight * 0.14f * waveAmpRatio).coerceIn(0f, 4.8f)
        val wavelengthPx = (barW * 0.14f).coerceIn(28f, 56f)
        val phaseRad = NkbWidgetAnimationTimeline.wavePhaseRad

        strokePaint.strokeWidth = strokeW
        strokePaint.strokeCap = Paint.Cap.ROUND

        if (activeEndX < barRight) {
            strokePaint.color = withAlpha(palette.fgBaseColor, 0.22f)
            canvas.drawLine(activeEndX, centerY, barRight, centerY, strokePaint)
        }

        if (activeEndX > barLeft + 1f) {
            strokePaint.color = palette.activeSungWordColor
            if (maxAmpPx < 0.25f) {
                canvas.drawLine(barLeft, centerY, activeEndX, centerY, strokePaint)
            } else {
                wavePath.reset()
                var x = barLeft
                var first = true
                val stepPx = 3.0f
                while (x <= activeEndX) {
                    val angle = ((x - barLeft) / wavelengthPx) * (2f * PI.toFloat()) - phaseRad
                    val y = centerY + sin(angle.toDouble()).toFloat() * maxAmpPx
                    if (first) {
                        wavePath.moveTo(x, y)
                        first = false
                    } else {
                        wavePath.lineTo(x, y)
                    }
                    x += stepPx
                }
                val endAngle = ((activeEndX - barLeft) / wavelengthPx) * (2f * PI.toFloat()) - phaseRad
                val endY = centerY + sin(endAngle.toDouble()).toFloat() * maxAmpPx
                wavePath.lineTo(activeEndX, endY)
                canvas.drawPath(wavePath, strokePaint)

                fillPaint.color = palette.activeSungWordColor
                canvas.drawCircle(activeEndX, endY, strokeW * 1.18f, fillPaint)
                return
            }
        }

        fillPaint.color = palette.activeSungWordColor
        canvas.drawCircle(activeEndX, centerY, strokeW * 1.15f, fillPaint)
    }

    /**
     * Draws all 6 NKB Transport Buttons (`Prev | -5s | Play/Pause | +5s | Next | Like`) centered
     * in 6 equal columns of `[bandLeft .. bandRight]` (`bandW / 6f`).
     *
     * When any button has `bloom > 0.03f` (tapped), renders that button into `btnNkbBitmap`,
     * applies real **20-bit Fixed-Point Gaussian NKB Blur (`NikoFastGaussianBlur.blurBitmapInPlace`)**
     * to the actual button & icon (`16px -> 10px -> 4px -> 0px sharp`), and draws it with elastic
     * spring bounce (`1.0 -> 0.76 -> 1.20 -> 0.94 -> 1.0`)!
     */
    private fun drawSixTransportButtonsInEqualColumns(
        canvas: Canvas,
        bandLeft: Float,
        bandRight: Float,
        centerY: Float,
        btnSize: Float,
        nowPlaying: NowPlayingMediaState,
        palette: ResolvedPalette,
        primaryTypeface: Typeface,
        nowElapsedMs: Long
    ) {
        val buttons = listOf(
            NkbWidgetAnimationTimeline.WidgetButtonId.PREV,
            NkbWidgetAnimationTimeline.WidgetButtonId.SEEK_BACK,
            NkbWidgetAnimationTimeline.WidgetButtonId.PLAY_PAUSE,
            NkbWidgetAnimationTimeline.WidgetButtonId.SEEK_FWD,
            NkbWidgetAnimationTimeline.WidgetButtonId.NEXT,
            NkbWidgetAnimationTimeline.WidgetButtonId.LIKE
        )

        val bandW = (bandRight - bandLeft).coerceAtLeast(btnSize * 6f)
        val colW = bandW / 6f

        for (i in buttons.indices) {
            val btnId = buttons[i]
            val cx = bandLeft + colW * (i + 0.5f)
            val (scale, bloom) = NkbWidgetAnimationTimeline.getButtonNkbState(btnId, nowElapsedMs)

            val isPlayBtn = btnId == NkbWidgetAnimationTimeline.WidgetButtonId.PLAY_PAUSE
            val isLikeBtn = btnId == NkbWidgetAnimationTimeline.WidgetButtonId.LIKE
            val isLiked = nowPlaying.isLiked

            val bgColor = when {
                isPlayBtn -> palette.playBtnBgColor
                isLikeBtn && isLiked -> withAlpha(palette.likeActiveColor, if (palette.isDark) 0.30f else 0.22f)
                else -> palette.btnBgColor
            }
            val fgColor = when {
                isPlayBtn -> palette.playBtnFgColor
                isLikeBtn && isLiked -> palette.likeActiveColor
                else -> palette.btnFgColor
            }
            val borderColor = when {
                isPlayBtn -> withAlpha(Color.WHITE, 0.38f)
                isLikeBtn && isLiked -> withAlpha(palette.likeActiveColor, 0.72f)
                else -> palette.btnBorderColor
            }

            if (bloom > 0.03f) {
                // REAL NKB GAUSSIAN BLOOM BLUR ON THE BUTTON ITSELF!
                // 1. Render the button at center of `btnNkbBitmap` (96x96)
                btnNkbBitmap.eraseColor(Color.TRANSPARENT)
                val localCenter = BTN_NKB_BUF_DIM * 0.5f
                val localBtnSize = BTN_NKB_BUF_DIM * 0.62f
                drawSingleButtonVisual(
                    targetCanvas = btnNkbCanvas,
                    cx = localCenter,
                    cy = localCenter,
                    btnSize = localBtnSize,
                    btnId = btnId,
                    isPlaying = nowPlaying.isPlaying,
                    isLiked = isLiked,
                    bgColor = bgColor,
                    fgColor = fgColor,
                    borderColor = borderColor,
                    primaryTypeface = primaryTypeface
                )

                // 2. Apply real 20-bit fixed-point Gaussian Blur to the button bitmap!
                val nkbRadius = (bloom * 12f).roundToInt().coerceIn(1, 18)
                NikoFastGaussianBlur.blurBitmapInPlace(
                    bitmap = btnNkbBitmap,
                    radius = nkbRadius,
                    passes = 2,
                    saturationBoost = 1.20f
                )

                canvas.save()
                canvas.scale(scale, scale, cx, centerY)

                // Draw radial NKB bloom halo behind the blurred button
                val haloR = btnSize * (0.68f + bloom * 0.36f)
                val haloColor = if (isLikeBtn) palette.likeActiveColor else palette.accentColor
                glowPaint.shader = RadialGradient(
                    cx,
                    centerY,
                    haloR,
                    intArrayOf(
                        withAlpha(haloColor, (0.58f * bloom).coerceIn(0f, 0.78f)),
                        withAlpha(haloColor, (0.22f * bloom).coerceIn(0f, 0.40f)),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.58f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, centerY, haloR, glowPaint)
                glowPaint.shader = null

                // Draw the Gaussian-blurred NKB button bitmap
                val dstHalf = (btnSize / 0.62f) * 0.5f
                btnNkbDstRect.set(cx - dstHalf, centerY - dstHalf, cx + dstHalf, centerY + dstHalf)
                canvas.drawBitmap(btnNkbBitmap, btnNkbSrcRect, btnNkbDstRect, bgPaint)

                // As bloom resolves toward 0f, cross-fade in the crisp vector button
                if (bloom < 0.45f) {
                    val sharpAlpha = ((0.45f - bloom) / 0.45f).coerceIn(0f, 1f)
                    drawSingleButtonVisual(
                        targetCanvas = canvas,
                        cx = cx,
                        cy = centerY,
                        btnSize = btnSize,
                        btnId = btnId,
                        isPlaying = nowPlaying.isPlaying,
                        isLiked = isLiked,
                        bgColor = withAlpha(bgColor, (Color.alpha(bgColor) / 255f) * sharpAlpha),
                        fgColor = withAlpha(fgColor, sharpAlpha),
                        borderColor = withAlpha(borderColor, (Color.alpha(borderColor) / 255f) * sharpAlpha),
                        primaryTypeface = primaryTypeface
                    )
                }
                canvas.restore()
            } else {
                canvas.save()
                if (scale != 1.0f) {
                    canvas.scale(scale, scale, cx, centerY)
                }
                drawSingleButtonVisual(
                    targetCanvas = canvas,
                    cx = cx,
                    cy = centerY,
                    btnSize = btnSize,
                    btnId = btnId,
                    isPlaying = nowPlaying.isPlaying,
                    isLiked = isLiked,
                    bgColor = bgColor,
                    fgColor = fgColor,
                    borderColor = borderColor,
                    primaryTypeface = primaryTypeface
                )
                canvas.restore()
            }
        }
    }

    private fun drawSingleButtonVisual(
        targetCanvas: Canvas,
        cx: Float,
        cy: Float,
        btnSize: Float,
        btnId: NkbWidgetAnimationTimeline.WidgetButtonId,
        isPlaying: Boolean,
        isLiked: Boolean,
        bgColor: Int,
        fgColor: Int,
        borderColor: Int,
        primaryTypeface: Typeface
    ) {
        val corner = btnSize * 0.36f
        val half = btnSize * 0.5f
        btnBoundsRect.set(cx - half, cy - half, cx + half, cy + half)
        fillPaint.color = bgColor
        targetCanvas.drawRoundRect(btnBoundsRect, corner, corner, fillPaint)

        strokePaint.strokeWidth = (btnSize * 0.032f).coerceIn(1.2f, 2.2f)
        strokePaint.color = borderColor
        targetCanvas.drawRoundRect(btnBoundsRect, corner, corner, strokePaint)

        val iconR = btnSize * 0.22f
        when (btnId) {
            NkbWidgetAnimationTimeline.WidgetButtonId.PREV -> drawSkipPrevIcon(targetCanvas, cx, cy, iconR, fgColor)
            NkbWidgetAnimationTimeline.WidgetButtonId.SEEK_BACK -> drawSeekDeltaIcon(targetCanvas, cx, cy, iconR, fgColor, "-5", primaryTypeface)
            NkbWidgetAnimationTimeline.WidgetButtonId.PLAY_PAUSE -> {
                if (isPlaying) {
                    drawPauseIcon(targetCanvas, cx, cy, iconR, fgColor)
                } else {
                    drawPlayIcon(targetCanvas, cx, cy, iconR, fgColor)
                }
            }
            NkbWidgetAnimationTimeline.WidgetButtonId.SEEK_FWD -> drawSeekDeltaIcon(targetCanvas, cx, cy, iconR, fgColor, "+5", primaryTypeface)
            NkbWidgetAnimationTimeline.WidgetButtonId.NEXT -> drawSkipNextIcon(targetCanvas, cx, cy, iconR, fgColor)
            NkbWidgetAnimationTimeline.WidgetButtonId.LIKE -> drawHeartIcon(targetCanvas, cx, cy, iconR, fgColor, isLiked)
            else -> {}
        }
    }

    /**
     * Draws a crisp circular seek arc + "-5" / "+5" label inside the `-5s` and `+5s` transport buttons.
     */
    private fun drawSeekDeltaIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        color: Int,
        label: String,
        typeface: Typeface
    ) {
        val arcR = r * 1.18f
        iconBoundsRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        strokePaint.strokeWidth = (r * 0.20f).coerceIn(1.4f, 2.4f)
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = withAlpha(color, 0.82f)
        val isBack = label.startsWith("-")
        val startAngle = if (isBack) -65f else -115f
        val sweepAngle = if (isBack) -275f else 275f
        canvas.drawArc(iconBoundsRect, startAngle, sweepAngle, false, strokePaint)

        textPaint.shader = null
        textPaint.clearShadowLayer()
        textPaint.typeface = typeface
        textPaint.textSize = (r * 1.02f).coerceIn(9f, 17f)
        textPaint.color = color
        textPaint.textAlign = Paint.Align.CENTER
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) * 0.5f
        canvas.drawText(label, cx, baseline, textPaint)
    }

    private fun drawPlayIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        iconPath.reset()
        iconPath.moveTo(cx - r * 0.55f, cy - r * 0.85f)
        iconPath.lineTo(cx + r * 0.85f, cy)
        iconPath.lineTo(cx - r * 0.55f, cy + r * 0.85f)
        iconPath.close()
        fillPaint.color = color
        canvas.drawPath(iconPath, fillPaint)
    }

    private fun drawPauseIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fillPaint.color = color
        val barW = r * 0.45f
        val barH = r * 1.65f
        val gap = r * 0.32f
        iconBoundsRect.set(cx - gap - barW, cy - barH * 0.5f, cx - gap, cy + barH * 0.5f)
        canvas.drawRoundRect(iconBoundsRect, barW * 0.35f, barW * 0.35f, fillPaint)
        iconBoundsRect.set(cx + gap, cy - barH * 0.5f, cx + gap + barW, cy + barH * 0.5f)
        canvas.drawRoundRect(iconBoundsRect, barW * 0.35f, barW * 0.35f, fillPaint)
    }

    private fun drawSkipPrevIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fillPaint.color = color
        val barW = r * 0.28f
        iconBoundsRect.set(cx - r * 0.85f, cy - r * 0.78f, cx - r * 0.85f + barW, cy + r * 0.78f)
        canvas.drawRoundRect(iconBoundsRect, barW * 0.4f, barW * 0.4f, fillPaint)

        iconPath.reset()
        iconPath.moveTo(cx + r * 0.82f, cy - r * 0.78f)
        iconPath.lineTo(cx - r * 0.35f, cy)
        iconPath.lineTo(cx + r * 0.82f, cy + r * 0.78f)
        iconPath.close()
        canvas.drawPath(iconPath, fillPaint)
    }

    private fun drawSkipNextIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fillPaint.color = color
        val barW = r * 0.28f
        iconBoundsRect.set(cx + r * 0.85f - barW, cy - r * 0.78f, cx + r * 0.85f, cy + r * 0.78f)
        canvas.drawRoundRect(iconBoundsRect, barW * 0.4f, barW * 0.4f, fillPaint)

        iconPath.reset()
        iconPath.moveTo(cx - r * 0.82f, cy - r * 0.78f)
        iconPath.lineTo(cx + r * 0.35f, cy)
        iconPath.lineTo(cx - r * 0.82f, cy + r * 0.78f)
        iconPath.close()
        canvas.drawPath(iconPath, fillPaint)
    }

    private fun drawHeartIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, filled: Boolean) {
        iconPath.reset()
        val topY = cy - r * 0.32f
        val botY = cy + r * 0.86f
        iconPath.moveTo(cx, botY)
        iconPath.cubicTo(cx - r * 1.25f, cy + r * 0.15f, cx - r * 0.95f, cy - r * 0.95f, cx, topY)
        iconPath.cubicTo(cx + r * 0.95f, cy - r * 0.95f, cx + r * 1.25f, cy + r * 0.15f, cx, botY)
        iconPath.close()
        if (filled) {
            fillPaint.color = color
            canvas.drawPath(iconPath, fillPaint)
        } else {
            strokePaint.strokeWidth = (r * 0.22f).coerceIn(1.6f, 2.8f)
            strokePaint.color = color
            canvas.drawPath(iconPath, strokePaint)
        }
    }

    private fun formatMmSs(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
        val mins = totalSec / 60
        val secs = totalSec % 60
        return "%d:%02d".format(mins, secs)
    }
}
