package com.nikolink.dynamiclyrics.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.nikolink.dynamiclyrics.R
import com.nikolink.dynamiclyrics.lyrics.NikoLiveLyricsEngine
import com.nikolink.dynamiclyrics.media.DynamicLyricsNotificationListenerService
import com.nikolink.dynamiclyrics.media.NikoMediaSessionStateHolder
import com.nikolink.dynamiclyrics.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * DynamicLyricsWidgetService (v1.0.0)
 * -----------------------------------
 * Single Serialized 30 FPS (`33ms`) Engine + Silent Live Lyrics Notification:
 *   - Drives all Home Screen Widget updates from a single serialized coroutine loop (woken up
 *     instantly via `wakeUpSignal` on Play/Pause or button taps) so two threads NEVER race to
 *     draw or parcel widget Bitmaps simultaneously (eliminating the Play/Pause blink!).
 *   - Also updates the Silent Compact Live Lyrics Notification (`R.layout.notification_live_lyrics_compact`)
 *     when `DynamicWidgetSettings.notificationLyricsEnabled` is enabled.
 */
class DynamicLyricsWidgetService : Service() {

    companion object {
        private const val CHANNEL_ID = "dynamic_lyrics_silent_live_channel"
        private const val NOTIFICATION_ID = 4102

        private val wakeUpSignal = Channel<Unit>(Channel.CONFLATED)

        fun shouldRunService(context: Context): Boolean {
            DynamicWidgetSettings.ensureLoaded(context)
            return DynamicLyricsAppWidgetProvider.hasActiveWidgets(context) ||
                DynamicWidgetSettings.notificationLyricsEnabled
        }

        fun ensureRunning(context: Context) {
            try {
                val appCtx = context.applicationContext
                if (!shouldRunService(appCtx)) return
                val intent = Intent(appCtx, DynamicLyricsWidgetService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
            } catch (_: Throwable) {}
        }

        /**
         * Wakes up the single serialized 30 FPS render loop immediately without spawning
         * a second competing thread!
         */
        fun wakeUpNow(context: Context) {
            ensureRunning(context)
            wakeUpSignal.trySend(Unit)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null
    private var lastTrackQueryKey: String = ""
    private var lastNotifUpdateElapsedMs: Long = 0L
    private var lastNotifEnabledState: Boolean = true
    private var lastNotifActiveLineIdx: Int = -99

    @Volatile
    private var isScreenInteractive: Boolean = true

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> isScreenInteractive = false
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    isScreenInteractive = true
                    wakeUpSignal.trySend(Unit)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DynamicWidgetSettings.ensureLoaded(this)
        ensureSilentChannelCreated()
        startForeground(NOTIFICATION_ID, buildCurrentNotification())

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            isScreenInteractive = pm?.isInteractive ?: true
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenStateReceiver, filter)
        } catch (_: Throwable) {}

        NikoMediaSessionStateHolder.onImmediateWidgetUpdateRequested = {
            wakeUpSignal.trySend(Unit)
        }

        startSerialized30FpsLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureSilentChannelCreated()
        try {
            startForeground(NOTIFICATION_ID, buildCurrentNotification())
        } catch (_: Throwable) {}
        if (tickerJob?.isActive != true) {
            startSerialized30FpsLoop()
        } else {
            wakeUpSignal.trySend(Unit)
        }
        return START_STICKY
    }

    private fun ensureSilentChannelCreated() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                }
                nm.createNotificationChannel(ch)
            } catch (_: Throwable) {}
        }
    }

    private fun buildCurrentNotification(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Notification {
        val openStudioIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val contentPi = PendingIntent.getActivity(this, 200, openStudioIntent, piFlags)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (DynamicWidgetSettings.notificationLyricsEnabled) {
            val state = DynamicLyricsNotificationListenerService.mediaState
            val posMs = DynamicLyricsNotificationListenerService.currentInterpolatedPositionMs()
            val lyrics = NikoLiveLyricsEngine.currentLyrics
            val bmp = DynamicLyricsNotificationRenderer.renderNotificationBitmap(
                context = this,
                nowPlaying = state,
                lyrics = lyrics,
                positionMs = posMs,
                nowElapsedMs = nowElapsedMs
            )
            val rv = RemoteViews(packageName, R.layout.notification_live_lyrics_compact)
            rv.setImageViewBitmap(R.id.notif_lyrics_canvas_image, bmp)
            builder.setCustomContentView(rv)
            builder.setCustomBigContentView(rv)
            builder.setContentTitle(state.title.ifBlank { "Dynamic Live Lyrics" })
            builder.setContentText(state.artist.ifBlank { "Live synced lyrics active" })
        } else {
            builder.setContentTitle("Dynamic Lyrics Widget (30 FPS)")
            builder.setContentText("Updating live lyrics on your home screen")
        }

        return builder.build()
    }

    private fun updateNotificationIfNeeded(nowElapsedMs: Long, force: Boolean = false) {
        try {
            val enabledNow = DynamicWidgetSettings.notificationLyricsEnabled
            val stateChanged = enabledNow != lastNotifEnabledState
            lastNotifEnabledState = enabledNow

            if (!enabledNow && !stateChanged && !force) return

            val lyrics = NikoLiveLyricsEngine.currentLyrics
            val posMs = DynamicLyricsNotificationListenerService.currentInterpolatedPositionMs()
            val activeLineIdx = NikoLiveLyricsEngine.findActiveLineIndex(lyrics?.lines.orEmpty(), posMs)
            val lineChanged = activeLineIdx != lastNotifActiveLineIdx
            lastNotifActiveLineIdx = activeLineIdx

            // Update at ~9 FPS (115ms) while playing so NotificationManager never rate-limits, or immediately on line/state change
            val minIntervalMs = if (lineChanged || stateChanged || force) 45L else 115L
            if (nowElapsedMs - lastNotifUpdateElapsedMs < minIntervalMs) return
            lastNotifUpdateElapsedMs = nowElapsedMs

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildCurrentNotification(nowElapsedMs))
        } catch (_: Throwable) {}
    }

    private fun startSerialized30FpsLoop() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            var idleRenderCounter = 0
            while (isActive) {
                val frameStartMs = SystemClock.elapsedRealtime()

                if (!isScreenInteractive) {
                    withTimeoutOrNull(1200L) { wakeUpSignal.receive() }
                    continue
                }

                val hasWidgets = DynamicLyricsAppWidgetProvider.hasActiveWidgets(this@DynamicLyricsWidgetService)
                val notifEnabled = DynamicWidgetSettings.notificationLyricsEnabled
                if (!hasWidgets && !notifEnabled) {
                    withTimeoutOrNull(1500L) { wakeUpSignal.receive() }
                    continue
                }

                val state = DynamicLyricsNotificationListenerService.mediaState
                val trackKey = "${state.title.trim().lowercase()}|${state.artist.trim().lowercase()}"
                if (state.title.isNotBlank() && trackKey != lastTrackQueryKey) {
                    lastTrackQueryKey = trackKey
                    NkbWidgetAnimationTimeline.triggerStageNkbBloom()
                    NikoLiveLyricsEngine.onTrackChanged(
                        title = state.title,
                        artist = state.artist,
                        album = state.album,
                        durationMs = state.durationMs
                    )
                }

                val isActivelyAnimating = state.isPlaying ||
                    NikoLiveLyricsEngine.isLoading ||
                    NkbWidgetAnimationTimeline.hasActiveTransientAnimations(frameStartMs)

                if (isActivelyAnimating) {
                    idleRenderCounter = 0
                    if (hasWidgets) {
                        DynamicLyricsAppWidgetProvider.updateAllWidgetsNow(this@DynamicLyricsWidgetService)
                    }
                    if (notifEnabled || lastNotifEnabledState != notifEnabled) {
                        updateNotificationIfNeeded(frameStartMs)
                    }
                    val renderDurationMs = SystemClock.elapsedRealtime() - frameStartMs
                    // Locked to 30 FPS (33ms frame interval)
                    val sleepMs = (33L - renderDurationMs).coerceIn(12L, 33L)
                    delay(sleepMs)
                } else {
                    if (idleRenderCounter == 0) {
                        // Draw one final settled frame right when playback pauses or NKB finishes
                        if (hasWidgets) {
                            DynamicLyricsAppWidgetProvider.updateAllWidgetsNow(this@DynamicLyricsWidgetService)
                        }
                        updateNotificationIfNeeded(frameStartMs, force = true)
                    } else if (idleRenderCounter % 12 == 0) {
                        if (hasWidgets) {
                            DynamicLyricsAppWidgetProvider.updateAllWidgetsNow(this@DynamicLyricsWidgetService)
                        }
                        if (notifEnabled != lastNotifEnabledState) {
                            updateNotificationIfNeeded(frameStartMs, force = true)
                        }
                    }
                    idleRenderCounter++
                    // Wait up to 240ms OR wake up immediately the instant any button is tapped or Play/Pause changes!
                    val woken = withTimeoutOrNull(240L) { wakeUpSignal.receive() }
                    if (woken != null) {
                        idleRenderCounter = 0
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Throwable) {}
        tickerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
