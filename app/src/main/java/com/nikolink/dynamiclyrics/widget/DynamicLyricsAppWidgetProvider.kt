package com.nikolink.dynamiclyrics.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import com.nikolink.dynamiclyrics.R
import com.nikolink.dynamiclyrics.lyrics.NikoLiveLyricsEngine
import com.nikolink.dynamiclyrics.media.DynamicLyricsNotificationListenerService
import com.nikolink.dynamiclyrics.ui.MainActivity
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

open class DynamicLyricsAppWidgetProvider : AppWidgetProvider() {

    protected open val providerDefaultLayout: WidgetLayoutMode = WidgetLayoutMode.CLASSIC_STACKED

    companion object {
        const val ACTION_PLAY_PAUSE = "com.nikolink.dynamiclyrics.ACTION_PLAY_PAUSE"
        const val ACTION_PREV = "com.nikolink.dynamiclyrics.ACTION_PREV"
        const val ACTION_NEXT = "com.nikolink.dynamiclyrics.ACTION_NEXT"
        const val ACTION_LIKE = "com.nikolink.dynamiclyrics.ACTION_LIKE"
        const val ACTION_CYCLE_PROVIDER = "com.nikolink.dynamiclyrics.ACTION_CYCLE_PROVIDER"
        const val ACTION_SEEK_BACK = "com.nikolink.dynamiclyrics.ACTION_SEEK_BACK"
        const val ACTION_SEEK_FWD = "com.nikolink.dynamiclyrics.ACTION_SEEK_FWD"
        const val ACTION_OPEN_PLAYER = "com.nikolink.dynamiclyrics.ACTION_OPEN_PLAYER"
        const val ACTION_REFRESH = "com.nikolink.dynamiclyrics.ACTION_REFRESH"

        private val widgetUpdateLock = Any()
        private val boundXmlLayoutByWidgetId = ConcurrentHashMap<Int, Int>()

        private data class ProviderSpec(
            val cls: Class<out DynamicLyricsAppWidgetProvider>,
            val defaultLayout: WidgetLayoutMode,
            val fallbackWidthDp: Int,
            val fallbackHeightDp: Int
        )

        private val ALL_PROVIDER_SPECS = listOf(
            ProviderSpec(DynamicLyricsAppWidgetProvider::class.java, WidgetLayoutMode.CLASSIC_STACKED, 290, 220),
            ProviderSpec(DynamicLyricsSplitWidgetProvider::class.java, WidgetLayoutMode.SPLIT_SIDE_BY_SIDE, 330, 165),
            ProviderSpec(DynamicLyricsVerticalWidgetProvider::class.java, WidgetLayoutMode.VERTICAL_TOWER, 165, 330),
            ProviderSpec(DynamicLyricsCompactWidgetProvider::class.java, WidgetLayoutMode.COMPACT_STRIP, 320, 105),
            ProviderSpec(DynamicLyricsTabletXlWidgetProvider::class.java, WidgetLayoutMode.SPLIT_SIDE_BY_SIDE, 460, 290)
        )

        fun hasActiveWidgets(context: Context): Boolean {
            return try {
                val mgr = AppWidgetManager.getInstance(context)
                ALL_PROVIDER_SPECS.any { spec ->
                    val ids = mgr.getAppWidgetIds(ComponentName(context, spec.cls))
                    ids != null && ids.isNotEmpty()
                }
            } catch (_: Throwable) {
                false
            }
        }

        fun requestImmediateUpdate(context: Context, forceFullLayout: Boolean = false) {
            try {
                if (forceFullLayout) {
                    boundXmlLayoutByWidgetId.clear()
                    updateAllWidgetsNow(context, forceFullLayout = true)
                }
                DynamicLyricsWidgetService.wakeUpNow(context)
            } catch (_: Throwable) {}
        }

        private fun resolveLayoutXmlRes(layoutMode: WidgetLayoutMode): Int {
            return when (layoutMode) {
                WidgetLayoutMode.SPLIT_SIDE_BY_SIDE -> R.layout.widget_dynamic_lyrics_split
                WidgetLayoutMode.COMPACT_STRIP -> R.layout.widget_dynamic_lyrics_compact
                else -> R.layout.widget_dynamic_lyrics
            }
        }

        @Suppress("DEPRECATION")
        private fun resolveWidgetDimensionsAndLayout(
            context: Context,
            mgr: AppWidgetManager,
            appWidgetId: Int,
            spec: ProviderSpec
        ): Triple<Int, Int, WidgetLayoutMode> {
            val opts: Bundle? = try { mgr.getAppWidgetOptions(appWidgetId) } catch (_: Throwable) { null }
            val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            var resolvedW = 0f
            var resolvedH = 0f

            if (opts != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sizes: ArrayList<SizeF>? = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        opts.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
                    } else {
                        opts.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                    }
                } catch (_: Throwable) {
                    null
                }
                if (!sizes.isNullOrEmpty()) {
                    val chosen = if (sizes.size == 1) {
                        sizes[0]
                    } else if (isLandscape) {
                        sizes.maxByOrNull { it.width } ?: sizes[0]
                    } else {
                        sizes.maxByOrNull { it.height } ?: sizes[0]
                    }
                    if (chosen.width > 40f && chosen.height > 40f) {
                        resolvedW = chosen.width
                        resolvedH = chosen.height
                    }
                }
            }

            if (resolvedW <= 40f || resolvedH <= 40f) {
                val minW = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
                val maxW = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) ?: 0
                val minH = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
                val maxH = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0

                if (isLandscape) {
                    resolvedW = (if (maxW > 0) maxW else if (minW > 0) minW else spec.fallbackWidthDp).toFloat()
                    resolvedH = (if (minH > 0) minH else if (maxH > 0) maxH else spec.fallbackHeightDp).toFloat()
                } else {
                    resolvedW = (if (minW > 0) minW else if (maxW > 0) maxW else spec.fallbackWidthDp).toFloat()
                    resolvedH = (if (maxH > 0) maxH else if (minH > 0) minH else spec.fallbackHeightDp).toFloat()
                }
            }

            val widthDp = resolvedW.roundToInt().coerceIn(105, 1100)
            val heightDp = resolvedH.roundToInt().coerceIn(68, 1100)

            val effectiveLayout = DynamicWidgetSettings.resolveEffectiveLayout(
                widthDp = widthDp,
                heightDp = heightDp,
                providerDefault = spec.defaultLayout
            )

            // Exact v1.0.8 dimension resolution (untouched, works across all phone & tablet launchers)
            val targetLongEdgePx = 680f
            val maxDpEdge = max(widthDp, heightDp).toFloat().coerceAtLeast(1f)
            val scale = (targetLongEdgePx / maxDpEdge).coerceIn(1.35f, 2.35f)
            val widthPx = (widthDp * scale).roundToInt().coerceIn(220, 880)
            val heightPx = (heightDp * scale).roundToInt().coerceIn(130, 880)

            return Triple(widthPx, heightPx, effectiveLayout)
        }

        fun updateAllWidgetsNow(
            context: Context,
            preRenderedBitmap: Bitmap? = null,
            forceFullLayout: Boolean = false
        ) = synchronized(widgetUpdateLock) {
            try {
                val appCtx = context.applicationContext
                val mgr = AppWidgetManager.getInstance(appCtx)
                val state = DynamicLyricsNotificationListenerService.mediaState
                val currentPosMs = DynamicLyricsNotificationListenerService.currentInterpolatedPositionMs()
                val lyrics = NikoLiveLyricsEngine.currentLyrics
                val isLoading = NikoLiveLyricsEngine.isLoading
                val preferredProvider = NikoLiveLyricsEngine.preferredProvider

                val frameCache = HashMap<String, Bitmap>()

                for (spec in ALL_PROVIDER_SPECS) {
                    val ids = try {
                        mgr.getAppWidgetIds(ComponentName(appCtx, spec.cls))
                    } catch (_: Throwable) {
                        null
                    }
                    if (ids == null || ids.isEmpty()) continue

                    for (id in ids) {
                        val (wPx, hPx, layoutMode) = resolveWidgetDimensionsAndLayout(appCtx, mgr, id, spec)
                        val cacheKey = "${wPx}x${hPx}_${layoutMode.name}"
                        val bitmap = preRenderedBitmap ?: frameCache.getOrPut(cacheKey) {
                            DynamicLyricsCanvasRenderer.renderWidgetBitmap(
                                context = appCtx,
                                state = state,
                                lyrics = lyrics,
                                isLoadingLyrics = isLoading,
                                preferredProvider = preferredProvider,
                                currentPosMs = currentPosMs,
                                widthPx = wPx,
                                heightPx = hPx,
                                layoutOverride = layoutMode
                            )
                        }

                        val layoutRes = resolveLayoutXmlRes(layoutMode)
                        val prevLayoutRes = boundXmlLayoutByWidgetId[id]
                        val needsFullUpdate = forceFullLayout || prevLayoutRes != layoutRes

                        if (needsFullUpdate) {
                            boundXmlLayoutByWidgetId[id] = layoutRes
                            val fullViews = buildFullRemoteViews(appCtx, layoutRes, bitmap)
                            mgr.updateAppWidget(id, fullViews)
                        } else {
                            val partialViews = RemoteViews(appCtx.packageName, layoutRes)
                            partialViews.setImageViewBitmap(R.id.widget_canvas_image, bitmap)
                            mgr.partiallyUpdateAppWidget(id, partialViews)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        fun buildFullRemoteViews(context: Context, layoutResId: Int, bitmap: Bitmap): RemoteViews {
            val views = RemoteViews(context.packageName, layoutResId)
            views.setImageViewBitmap(R.id.widget_canvas_image, bitmap)

            views.setOnClickPendingIntent(
                R.id.btn_provider_pill,
                buildBroadcastPendingIntent(context, ACTION_CYCLE_PROVIDER, 101)
            )
            views.setOnClickPendingIntent(
                R.id.btn_header_open_app,
                buildBroadcastPendingIntent(context, ACTION_OPEN_PLAYER, 102)
            )
            views.setOnClickPendingIntent(
                R.id.btn_lyrics_stage,
                buildOpenStudioPendingIntent(context, 103)
            )
            views.setOnClickPendingIntent(
                R.id.btn_prev,
                buildBroadcastPendingIntent(context, ACTION_PREV, 106)
            )
            views.setOnClickPendingIntent(
                R.id.btn_seek_back,
                buildBroadcastPendingIntent(context, ACTION_SEEK_BACK, 104)
            )
            views.setOnClickPendingIntent(
                R.id.btn_play_pause,
                buildBroadcastPendingIntent(context, ACTION_PLAY_PAUSE, 107)
            )
            views.setOnClickPendingIntent(
                R.id.btn_seek_fwd,
                buildBroadcastPendingIntent(context, ACTION_SEEK_FWD, 105)
            )
            views.setOnClickPendingIntent(
                R.id.btn_next,
                buildBroadcastPendingIntent(context, ACTION_NEXT, 108)
            )
            views.setOnClickPendingIntent(
                R.id.btn_like,
                buildBroadcastPendingIntent(context, ACTION_LIKE, 109)
            )
            return views
        }

        private fun buildBroadcastPendingIntent(
            context: Context,
            action: String,
            requestCode: Int
        ): PendingIntent {
            val intent = Intent(context, DynamicLyricsAppWidgetProvider::class.java).apply {
                this.action = action
                `package` = context.packageName
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            return PendingIntent.getBroadcast(context, requestCode, intent, flags)
        }

        private fun buildOpenStudioPendingIntent(
            context: Context,
            requestCode: Int
        ): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            return PendingIntent.getActivity(context, requestCode, intent, flags)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        DynamicWidgetSettings.ensureLoaded(context)
        DynamicLyricsWidgetService.ensureRunning(context)
        updateAllWidgetsNow(context, forceFullLayout = true)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        DynamicWidgetSettings.ensureLoaded(context)
        DynamicLyricsWidgetService.ensureRunning(context)
        updateAllWidgetsNow(context, forceFullLayout = true)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        DynamicWidgetSettings.ensureLoaded(context)
        boundXmlLayoutByWidgetId.remove(appWidgetId)
        DynamicLyricsWidgetService.ensureRunning(context)
        updateAllWidgetsNow(context, forceFullLayout = true)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        DynamicWidgetSettings.ensureLoaded(context)
        when (intent.action) {
            ACTION_PLAY_PAUSE -> {
                NkbWidgetAnimationTimeline.triggerButtonNkb(NkbWidgetAnimationTimeline.WidgetButtonId.PLAY_PAUSE)
                DynamicLyricsNotificationListenerService.togglePlayPause(context)
                DynamicLyricsWidgetService.wakeUpNow(context)
            }
            ACTION_PREV -> {
                NkbWidgetAnimationTimeline.triggerButtonNkb(NkbWidgetAnimationTimeline.WidgetButtonId.PREV)
                NkbWidgetAnimationTimeline.triggerStageNkbBloom()
                DynamicLyricsNotificationListenerService.skipToPrevious(context)
                DynamicLyricsWidgetService.wakeUpNow(context)
            }
            ACTION_NEXT -> {
                NkbWidgetAnimationTimeline.triggerButtonNkb(NkbWidgetAnimationTimeline.WidgetButtonId.NEXT)
                NkbWidgetAnimationTimeline.triggerStageNkbBloom()
                DynamicLyricsNotificationListenerService.skipToNext(context)
                DynamicLyricsWidgetService.wakeUpNow(context)
            }
            ACTION_LIKE -> {
                NkbWidgetAnimationTimeline.triggerButtonNkb(NkbWidgetAnimationTimeline.WidgetButtonId.LIKE)
                DynamicLyricsNotificationListenerService.toggleLikeCurrentTrack(context)
                DynamicLyricsWidgetService.wakeUpNow(context)
            }
            ACTION_SEEK_BACK -> {
                NkbWidgetAnimationTimeline.triggerButtonNkb(NkbWidgetAnimationTimeline.WidgetButtonId.SEEK_BACK)
                val cur = DynamicLyricsNotificationListenerService.currentInterpolatedPositionMs()
                DynamicLyricsNotificationListenerService.seekTo((cur - 5_000L).coerceAtLeast(0L))
                DynamicLyricsWidgetService.wakeUpNow(context)
            }
            ACTION_SEEK_FWD -> {
                NkbWidgetAnimationTimeline.triggerButtonNkb(NkbWidgetAnimationTimeline.WidgetButtonId.SEEK_FWD)
                val state = DynamicLyricsNotificationListenerService.mediaState
                val cur = DynamicLyricsNotificationListenerService.currentInterpolatedPositionMs()
                val max = if (state.durationMs > 0L) state.durationMs else Long.MAX_VALUE
                DynamicLyricsNotificationListenerService.seekTo((cur + 5_000L).coerceAtMost(max))
                DynamicLyricsWidgetService.wakeUpNow(context)
            }
            ACTION_CYCLE_PROVIDER -> {
                NkbWidgetAnimationTimeline.triggerButtonNkb(NkbWidgetAnimationTimeline.WidgetButtonId.PROVIDER)
                NkbWidgetAnimationTimeline.triggerStageNkbBloom()
                val state = DynamicLyricsNotificationListenerService.mediaState
                NikoLiveLyricsEngine.cycleProvider(
                    title = state.title,
                    artist = state.artist,
                    album = state.album,
                    durationMs = state.durationMs
                )
                DynamicLyricsWidgetService.wakeUpNow(context)
            }
            ACTION_OPEN_PLAYER -> {
                val state = DynamicLyricsNotificationListenerService.mediaState
                val pkg = state.packageName
                var launched = false
                if (pkg.isNotBlank()) {
                    try {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                            launched = true
                        }
                    } catch (_: Throwable) {}
                }
                if (!launched) {
                    try {
                        val studioIntent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        context.startActivity(studioIntent)
                    } catch (_: Throwable) {}
                }
            }
            ACTION_REFRESH -> {
                DynamicLyricsWidgetService.ensureRunning(context)
                updateAllWidgetsNow(context, forceFullLayout = true)
            }
        }
    }
}

class DynamicLyricsSplitWidgetProvider : DynamicLyricsAppWidgetProvider() {
    override val providerDefaultLayout: WidgetLayoutMode = WidgetLayoutMode.SPLIT_SIDE_BY_SIDE
}

class DynamicLyricsVerticalWidgetProvider : DynamicLyricsAppWidgetProvider() {
    override val providerDefaultLayout: WidgetLayoutMode = WidgetLayoutMode.VERTICAL_TOWER
}

class DynamicLyricsCompactWidgetProvider : DynamicLyricsAppWidgetProvider() {
    override val providerDefaultLayout: WidgetLayoutMode = WidgetLayoutMode.COMPACT_STRIP
}

class DynamicLyricsTabletXlWidgetProvider : DynamicLyricsAppWidgetProvider() {
    override val providerDefaultLayout: WidgetLayoutMode = WidgetLayoutMode.SPLIT_SIDE_BY_SIDE
}
