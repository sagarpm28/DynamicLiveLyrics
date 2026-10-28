package com.nikolink.dynamiclyrics.media

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.BitmapDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import com.nikolink.dynamiclyrics.lyrics.NikoLiveLyricsEngine
import com.nikolink.dynamiclyrics.widget.DynamicWidgetSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NowPlayingMediaState(
    val packageName: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val basePositionMs: Long = 0L,
    val lastPositionUpdateElapsedMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val albumArt: Bitmap? = null,
    val albumArtFingerprint: Long = 0L,
    val dominantAccentArgb: Int? = null,
    val isLiked: Boolean = false
) {
    val positionMs: Long get() = basePositionMs
    val lastPositionUpdateElapsedRealtime: Long get() = lastPositionUpdateElapsedMs
    val dominantColor: Int get() = dominantAccentArgb ?: 0xFF38BDF8.toInt()
    val appName: String
        get() = when {
            packageName.contains("spotify", true) -> "Spotify"
            packageName.contains("youtube", true) -> "YouTube Music"
            packageName.contains("apple", true) -> "Apple Music"
            packageName.contains("metrolist", true) -> "Metrolist"
            packageName.contains("outertune", true) -> "OuterTune"
            packageName.contains("rimusic", true) -> "RiMusic"
            packageName.contains("poweramp", true) -> "Poweramp"
            packageName.contains("musicolet", true) -> "Musicolet"
            packageName.isNotBlank() -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            else -> "Music"
        }

    fun currentPositionMs(): Long {
        val raw = if (!isPlaying) {
            basePositionMs.coerceAtLeast(0L)
        } else {
            val elapsed = (SystemClock.elapsedRealtime() - lastPositionUpdateElapsedMs).coerceAtLeast(0L)
            (basePositionMs + (elapsed * playbackSpeed).toLong()).coerceAtLeast(0L)
        }
        val withOffset = (raw - DynamicWidgetSettings.lyricsOffsetMs).coerceAtLeast(0L)
        return if (durationMs > 0L) withOffset.coerceIn(0L, durationMs) else withOffset
    }
}

object NikoMediaSessionStateHolder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _nowPlaying = MutableStateFlow(NowPlayingMediaState())
    val nowPlaying: StateFlow<NowPlayingMediaState> = _nowPlaying.asStateFlow()

    @Volatile
    private var appContextRef: Context? = null
    @Volatile
    private var activeController: MediaController? = null
    private var activeControllerCallback: MediaController.Callback? = null
    private var pollerJob: Job? = null
    @Volatile
    private var activeNotificationActions: Array<Notification.Action>? = null
    @Volatile
    private var activeNotificationPackage: String = ""
    private val sessionLikedTracks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    var onImmediateWidgetUpdateRequested: (() -> Unit)? = null

    fun getCachedContext(): Context? = appContextRef

    fun updateActiveNotificationActions(pkg: String, actions: Array<Notification.Action>?) {
        activeNotificationPackage = pkg
        activeNotificationActions = actions
    }

    private fun trackKey(title: String, artist: String): String =
        "${title.trim().lowercase()}|${artist.trim().lowercase()}"

    private fun inspectBundleForLikedState(bundle: Bundle?): Boolean? {
        if (bundle == null) return null
        return runCatching<Boolean?> {
            for (k in bundle.keySet()) {
                val low = k.lowercase()
                if (low.contains("dislike") || low.contains("thumb_down")) continue
                if (low == "liked" || low == "is_liked" || low == "isliked" ||
                    low == "favorite" || low == "is_favorite" || low == "isfavorite" ||
                    low == "in_library" || low == "saved" || low == "is_saved" ||
                    low == "thumbs_up" || low == "heart" || low == "starred"
                ) {
                    @Suppress("DEPRECATION")
                    val v = bundle.get(k)
                    when (v) {
                        is Boolean -> return@runCatching v
                        is Int -> return@runCatching v > 0
                        is Long -> return@runCatching v > 0L
                        is String -> {
                            val sv = v.lowercase()
                            if (sv == "true" || sv == "1" || sv == "liked" || sv == "like") return@runCatching true
                            if (sv == "false" || sv == "0" || sv == "unliked" || sv == "none") return@runCatching false
                        }
                    }
                }
                if (low.contains("like_status") || low.contains("likestatus") || low.contains("rating_state")) {
                    @Suppress("DEPRECATION")
                    val v = bundle.get(k)
                    when (v) {
                        is Int -> return@runCatching v == 1 || v == 2
                        is String -> {
                            val sv = v.lowercase()
                            if (sv.contains("like") && !sv.contains("dislike") && !sv.contains("indifferent") && !sv.contains("none")) {
                                return@runCatching true
                            }
                            if (sv.contains("indifferent") || sv.contains("none") || sv.contains("dislike")) {
                                return@runCatching false
                            }
                        }
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun resolveRemoteDrawableName(pkg: String, resId: Int): String {
        if (pkg.isBlank() || resId == 0) return ""
        val ctx = appContextRef ?: return ""
        return runCatching {
            ctx.packageManager.getResourcesForApplication(pkg).getResourceEntryName(resId).lowercase()
        }.getOrDefault("")
    }

    private fun classifyIconOrActionLikedState(id: String, label: String, iconResName: String): Boolean? {
        val combined = "$id $label $iconResName"
        if (combined.contains("dislike") || combined.contains("thumb_down") || combined.contains("thumbs_down") ||
            combined.contains("thumb down") || combined.contains("don't like")
        ) {
            return null
        }
        val isLikeRelated = combined.contains("like") || combined.contains("heart") || combined.contains("fav") ||
            combined.contains("thumb_up") || combined.contains("thumbs_up") || combined.contains("thumb up") ||
            combined.contains("love") || combined.contains("star") || combined.contains("saved")
        if (!isLikeRelated) return null

        if (id.contains("unlike") || id.contains("remove_like") || id.contains("unfavorite") ||
            id.contains("remove_fav") || id.contains("remove_from_fav") || id.contains("remove_from_lib") ||
            label.contains("unlike") || label.contains("remove from liked") || label.contains("remove favorite") ||
            label.contains("unfavorite") || label == "liked" || label == "saved" || label.contains("in library")
        ) {
            return true
        }

        if (iconResName.isNotEmpty()) {
            val isOutlineOrOff = iconResName.contains("border") ||
                iconResName.contains("outline") ||
                iconResName.contains("unliked") ||
                iconResName.contains("unfav") ||
                iconResName.contains("unselected") ||
                iconResName.contains("inactive") ||
                iconResName.contains("_off") ||
                iconResName.endsWith("off") ||
                iconResName.contains("empty") ||
                iconResName.contains("none") ||
                iconResName.contains("add_")
            if (isOutlineOrOff) {
                return false
            }

            val isFilledOrActive = iconResName.contains("filled") ||
                iconResName.contains("fill") ||
                iconResName.contains("selected") ||
                iconResName.contains("active") ||
                iconResName.contains("liked") ||
                iconResName.contains("saved") ||
                iconResName.contains("_on") ||
                iconResName.endsWith("on") ||
                iconResName == "ic_favorite" ||
                iconResName == "favorite" ||
                iconResName == "ic_heart" ||
                iconResName == "heart" ||
                iconResName == "ic_thumb_up" ||
                iconResName == "thumb_up" ||
                iconResName == "ic_like" ||
                iconResName == "like"
            if (isFilledOrActive) {
                return true
            }
        }

        if (id.contains("add_like") || id.contains("add_fav") || label.contains("add to liked") || label.contains("add to fav")) {
            return false
        }

        return null
    }

    /**
     * 5-Signal Real-Time Track Liked State Detector:
     * Accurately detects if the currently playing track is ALREADY liked in Spotify, YouTube Music,
     * Metrolist, OuterTune, RiMusic, SimpMusic, Apple Music, Poweramp, Musicolet, Samsung Music, etc.!
     */
    private fun detectControllerLikedState(controller: MediaController, title: String, artist: String): Boolean {
        val key = trackKey(title, artist)
        val pkg = controller.packageName ?: activeNotificationPackage

        // 1. Check MediaMetadata Rating
        val meta = runCatching { controller.metadata }.getOrNull()
        val userRating = runCatching {
            meta?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
                ?: meta?.getRating(MediaMetadata.METADATA_KEY_RATING)
        }.getOrNull()
        if (userRating != null && userRating.isRated) {
            val ratedPositive = when (userRating.ratingStyle) {
                Rating.RATING_HEART -> userRating.hasHeart()
                Rating.RATING_THUMB_UP_DOWN -> userRating.isThumbUp
                Rating.RATING_3_STARS, Rating.RATING_4_STARS, Rating.RATING_5_STARS -> userRating.starRating >= 3.0f
                Rating.RATING_PERCENTAGE -> userRating.percentRating >= 50f
                else -> false
            }
            if (ratedPositive) sessionLikedTracks.add(key) else sessionLikedTracks.remove(key)
            return ratedPositive
        }

        // 2. Check PlaybackState.extras & MediaController.extras
        val pb = runCatching { controller.playbackState }.getOrNull()
        inspectBundleForLikedState(pb?.extras)?.let { detected ->
            if (detected) sessionLikedTracks.add(key) else sessionLikedTracks.remove(key)
            return detected
        }
        inspectBundleForLikedState(runCatching { controller.extras }.getOrNull())?.let { detected ->
            if (detected) sessionLikedTracks.add(key) else sessionLikedTracks.remove(key)
            return detected
        }

        // 3. Check PlaybackState.CustomAction (ID, label, extras, and APK drawable resource name)
        val customActions = pb?.customActions.orEmpty()
        for (ca in customActions) {
            inspectBundleForLikedState(ca.extras)?.let { detected ->
                if (detected) sessionLikedTracks.add(key) else sessionLikedTracks.remove(key)
                return detected
            }
            val id = ca.action.lowercase()
            val label = (ca.name?.toString() ?: "").lowercase()
            val iconResName = resolveRemoteDrawableName(pkg, ca.icon)
            classifyIconOrActionLikedState(id, label, iconResName)?.let { detected ->
                if (detected) sessionLikedTracks.add(key) else sessionLikedTracks.remove(key)
                return detected
            }
        }

        // 4. Check Active Media Notification Actions
        val notifActions = activeNotificationActions.orEmpty()
        for (act in notifActions) {
            inspectBundleForLikedState(act.extras)?.let { detected ->
                if (detected) sessionLikedTracks.add(key) else sessionLikedTracks.remove(key)
                return detected
            }
            val actTitle = (act.title?.toString() ?: "").lowercase()
            val iconResId = runCatching { act.getIcon()?.resId ?: 0 }.getOrElse {
                @Suppress("DEPRECATION")
                act.icon
            }
            val iconResName = resolveRemoteDrawableName(pkg, iconResId)
            classifyIconOrActionLikedState("", actTitle, iconResName)?.let { detected ->
                if (detected) sessionLikedTracks.add(key) else sessionLikedTracks.remove(key)
                return detected
            }
        }

        return sessionLikedTracks.contains(key)
    }

    fun ensureListenerPermission(context: Context) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        scope.launch(Dispatchers.IO) {
            refreshActiveSessions(appCtx)
        }
        startContinuousSessionPoller(appCtx)
    }

    private fun startContinuousSessionPoller(appCtx: Context) {
        if (pollerJob?.isActive == true) return
        pollerJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val ctrl = activeController
                if (ctrl != null) {
                    extractStateFromController(ctrl)
                } else {
                    refreshActiveSessions(appCtx)
                }
                delay(600L)
            }
        }
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        return runCatching<Boolean> {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
            enabled.contains(context.packageName)
        }.getOrDefault(false)
    }

    fun bindController(controller: MediaController?, fallbackArt: Bitmap? = null) {
        if (controller == null) return
        val current = activeController
        if (current?.sessionToken == controller.sessionToken) {
            extractStateFromController(controller, fallbackArt)
            return
        }
        runCatching {
            activeControllerCallback?.let { cb -> current?.unregisterCallback(cb) }
        }
        activeController = controller
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                extractStateFromController(controller)
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                extractStateFromController(controller)
            }

            override fun onSessionDestroyed() {
                if (activeController?.sessionToken == controller.sessionToken) {
                    activeController = null
                }
            }
        }
        activeControllerCallback = callback
        runCatching {
            controller.registerCallback(callback)
        }
        extractStateFromController(controller, fallbackArt)
    }

    fun extractStateFromController(controller: MediaController, notificationLargeIcon: Bitmap? = null) {
        runCatching {
            val meta = controller.metadata
            val pb = controller.playbackState
            val title = (meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: "").trim()
            val artist = (meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: "").trim()
            val album = (meta?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "").trim()
            val duration = (meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
            val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: notificationLargeIcon

            val stateCode = pb?.state ?: PlaybackState.STATE_NONE
            val isPlaying = stateCode == PlaybackState.STATE_PLAYING || stateCode == PlaybackState.STATE_BUFFERING
            val rawPos = (pb?.position ?: 0L).coerceAtLeast(0L)
            val nowElapsed = SystemClock.elapsedRealtime()
            val rawUpdateTime = pb?.lastPositionUpdateTime ?: 0L
            val validElapsedUpdate = if (rawUpdateTime in 1L..(nowElapsed + 2000L) && (nowElapsed - rawUpdateTime) < 3_600_000L) {
                rawUpdateTime
            } else {
                nowElapsed
            }
            val speed = (pb?.playbackSpeed ?: 1f).let { if (it <= 0f) 1f else it }

            if (title.isNotBlank() || artist.isNotBlank()) {
                val prev = _nowPlaying.value
                val trackChanged = (title != prev.title || artist != prev.artist)

                val polledEstimatedPos = rawPos + ((nowElapsed - validElapsedUpdate).coerceAtLeast(0L) * speed).toLong()
                val prevEstimatedPos = prev.currentPositionMs()
                val isContinuousNormalPlayback = !trackChanged &&
                    isPlaying &&
                    prev.isPlaying &&
                    prev.lastPositionUpdateElapsedMs > 0L &&
                    kotlin.math.abs(polledEstimatedPos - prevEstimatedPos) < 1350L

                val pos = if (isContinuousNormalPlayback) prev.basePositionMs else rawPos
                val lastUpdate = if (isContinuousNormalPlayback) prev.lastPositionUpdateElapsedMs else validElapsedUpdate
                val finalArt = art ?: if (!trackChanged) prev.albumArt else null
                val newFingerprint = computeBitmapFingerprint(finalArt)

                val updatedAccent = when {
                    finalArt != null && (newFingerprint != prev.albumArtFingerprint || prev.dominantAccentArgb == null) -> {
                        extractVibrantAlbumArtAccentArgb(finalArt)
                    }
                    finalArt != null -> prev.dominantAccentArgb
                    trackChanged -> computeDeterministicTrackFallbackAccent(title, artist)
                    else -> prev.dominantAccentArgb
                }

                val likedNow = detectControllerLikedState(controller, title, artist)
                val stateChangedSignificantly = trackChanged ||
                    isPlaying != prev.isPlaying ||
                    likedNow != prev.isLiked ||
                    newFingerprint != prev.albumArtFingerprint

                _nowPlaying.value = NowPlayingMediaState(
                    packageName = controller.packageName ?: prev.packageName,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = duration,
                    isPlaying = isPlaying,
                    basePositionMs = pos,
                    lastPositionUpdateElapsedMs = lastUpdate,
                    playbackSpeed = speed,
                    albumArt = finalArt,
                    albumArtFingerprint = newFingerprint,
                    dominantAccentArgb = updatedAccent,
                    isLiked = likedNow
                )
                NikoLiveLyricsEngine.onTrackChanged(
                    title = title,
                    artist = artist,
                    album = album,
                    durationSec = (duration / 1000L).toInt()
                )
                if (stateChangedSignificantly) {
                    onImmediateWidgetUpdateRequested?.invoke()
                }
            }
        }
    }

    private fun computeBitmapFingerprint(bmp: Bitmap?): Long {
        if (bmp == null || bmp.isRecycled) return 0L
        return runCatching<Long> {
            val w = bmp.width.coerceAtLeast(1)
            val h = bmp.height.coerceAtLeast(1)
            var hash = (w.toLong() shl 32) xor h.toLong()
            for (gy in 1..4) {
                val y = (h * gy / 5).coerceIn(0, h - 1)
                for (gx in 1..4) {
                    val x = (w * gx / 5).coerceIn(0, w - 1)
                    val px = bmp.getPixel(x, y).toLong() and 0xFFFFFFFFL
                    hash = (hash * 31L) xor px
                }
            }
            hash
        }.getOrDefault(0L)
    }

    private val accentScratchLock = Any()
    private val reusableArtPixels = IntArray(24 * 24)
    private val reusableHsv = FloatArray(3)
    private val reusableHueBuckets = FloatArray(36)
    private val reusableSatAccum = FloatArray(36)
    private val reusableCountBucket = IntArray(36)

    private fun computeDeterministicTrackFallbackAccent(title: String, artist: String): Int {
        val seed = (title.hashCode() * 31 + artist.hashCode()) and 0x7FFFFFFF
        val hue = (seed % 360).toFloat()
        return AndroidColor.HSVToColor(floatArrayOf(hue, 0.46f, 0.88f))
    }

    private fun extractVibrantAlbumArtAccentArgb(src: Bitmap): Int? {
        return runCatching<Int?> {
            val scaled = Bitmap.createScaledBitmap(src, 24, 24, true)
            synchronized(accentScratchLock) {
                scaled.getPixels(reusableArtPixels, 0, 24, 0, 0, 24, 24)
                java.util.Arrays.fill(reusableHueBuckets, 0f)
                java.util.Arrays.fill(reusableSatAccum, 0f)
                java.util.Arrays.fill(reusableCountBucket, 0)

                var bestScore = -1f
                var bestHue = 38f
                var bestSat = 0.45f

                for (px in reusableArtPixels) {
                    val a = (px ushr 24) and 0xFF
                    if (a < 128) continue
                    AndroidColor.colorToHSV(px, reusableHsv)
                    val h = reusableHsv[0]
                    val s = reusableHsv[1]
                    val v = reusableHsv[2]
                    if (v < 0.16f || (v > 0.95f && s < 0.14f) || s < 0.10f) continue

                    val bucket = ((h / 10f).toInt()).coerceIn(0, 35)
                    val weight = (s * 1.45f + v * 0.55f)
                    reusableHueBuckets[bucket] += weight
                    reusableSatAccum[bucket] += s
                    reusableCountBucket[bucket] += 1

                    if (reusableHueBuckets[bucket] > bestScore) {
                        bestScore = reusableHueBuckets[bucket]
                        bestHue = bucket * 10f + 5f
                        bestSat = (reusableSatAccum[bucket] / reusableCountBucket[bucket].coerceAtLeast(1)).coerceIn(0.38f, 0.68f)
                    }
                }

                if (bestScore <= 0f) {
                    reusableHsv[0] = 38f
                    reusableHsv[1] = 0.42f
                    reusableHsv[2] = 0.86f
                    return@runCatching AndroidColor.HSVToColor(reusableHsv)
                }

                reusableHsv[0] = bestHue
                reusableHsv[1] = bestSat.coerceIn(0.38f, 0.66f)
                reusableHsv[2] = 0.88f
                AndroidColor.HSVToColor(reusableHsv)
            }
        }.getOrNull()
    }

    fun selectBestController(controllers: List<MediaController>?): MediaController? {
        if (controllers.isNullOrEmpty()) return null
        return controllers.firstOrNull {
            val playing = it.playbackState?.state == PlaybackState.STATE_PLAYING
            val hasTitle = !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
            playing && hasTitle
        } ?: controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull {
            !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
        } ?: controllers.firstOrNull()
    }

    fun refreshActiveSessions(context: Context) {
        appContextRef = context.applicationContext
        runCatching {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return@runCatching
            val cn = ComponentName(context, DynamicLyricsNotificationListenerService::class.java)
            val controllers = msm.getActiveSessions(cn)
            val preferred = selectBestController(controllers)
            if (preferred != null) {
                bindController(preferred)
            }
        }
    }

    fun togglePlayPause(context: Context) {
        val cur = _nowPlaying.value
        val nowElapsed = SystemClock.elapsedRealtime()
        val frozenPos = (cur.currentPositionMs() + DynamicWidgetSettings.lyricsOffsetMs).coerceAtLeast(0L)
        _nowPlaying.value = cur.copy(
            isPlaying = !cur.isPlaying,
            basePositionMs = frozenPos,
            lastPositionUpdateElapsedMs = nowElapsed
        )
        onImmediateWidgetUpdateRequested?.invoke()

        scope.launch(Dispatchers.Main) {
            val ctrl = activeController
            val handled = runCatching<Boolean> {
                if (ctrl != null) {
                    val state = ctrl.playbackState?.state
                    if (state == PlaybackState.STATE_PLAYING) {
                        ctrl.transportControls.pause()
                    } else {
                        ctrl.transportControls.play()
                    }
                    true
                } else false
            }.getOrDefault(false)

            if (!handled) {
                dispatchAudioMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
            delay(160)
            refreshActiveSessions(context)
        }
    }

    fun skipNext(context: Context) {
        scope.launch(Dispatchers.Main) {
            val ctrl = activeController
            val handled = runCatching<Boolean> {
                if (ctrl != null) {
                    ctrl.transportControls.skipToNext()
                    true
                } else false
            }.getOrDefault(false)

            if (!handled) {
                dispatchAudioMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            }
            delay(180)
            refreshActiveSessions(context)
            onImmediateWidgetUpdateRequested?.invoke()
        }
    }

    fun skipPrevious(context: Context) {
        scope.launch(Dispatchers.Main) {
            val ctrl = activeController
            val handled = runCatching<Boolean> {
                if (ctrl != null) {
                    ctrl.transportControls.skipToPrevious()
                    true
                } else false
            }.getOrDefault(false)

            if (!handled) {
                dispatchAudioMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
            delay(180)
            refreshActiveSessions(context)
            onImmediateWidgetUpdateRequested?.invoke()
        }
    }

    fun toggleLikeCurrentTrack(context: Context) {
        appContextRef = context.applicationContext
        val cur = _nowPlaying.value
        val ctrl = activeController
        val liveLiked = if (ctrl != null) detectControllerLikedState(ctrl, cur.title, cur.artist) else cur.isLiked
        val newLiked = !liveLiked
        val key = trackKey(cur.title, cur.artist)
        if (newLiked) {
            sessionLikedTracks.add(key)
        } else {
            sessionLikedTracks.remove(key)
        }
        _nowPlaying.value = cur.copy(isLiked = newLiked)
        onImmediateWidgetUpdateRequested?.invoke()

        scope.launch(Dispatchers.Main) {
            var dispatched = false

            if (ctrl != null) {
                // Priority 1: PlaybackState.CustomAction
                runCatching {
                    val customActions = ctrl.playbackState?.customActions.orEmpty()
                    val likeCandidates = customActions.filter { ca ->
                        val id = ca.action.lowercase()
                        val name = (ca.name?.toString() ?: "").lowercase()
                        val iconName = resolveRemoteDrawableName(ctrl.packageName ?: "", ca.icon)
                        val combined = "$id $name $iconName"
                        val isDislike = combined.contains("dislike") || combined.contains("thumb_down") ||
                            combined.contains("thumbs_down") || combined.contains("thumb down") || combined.contains("don't like")
                        val isLikeRelated = combined.contains("like") || combined.contains("heart") || combined.contains("fav") ||
                            combined.contains("thumb_up") || combined.contains("thumbs_up") || combined.contains("thumb up") ||
                            combined.contains("love") || combined.contains("star") || combined.contains("rating") ||
                            combined.contains("save")
                        !isDislike && isLikeRelated
                    }
                    val matchedAction = if (likeCandidates.size > 1) {
                        if (newLiked) {
                            likeCandidates.firstOrNull { ca ->
                                val s = "${ca.action} ${ca.name}".lowercase()
                                !s.contains("unlike") && !s.contains("remove") && !s.contains("unfav")
                            } ?: likeCandidates.first()
                        } else {
                            likeCandidates.firstOrNull { ca ->
                                val s = "${ca.action} ${ca.name}".lowercase()
                                s.contains("unlike") || s.contains("remove") || s.contains("unfav")
                            } ?: likeCandidates.first()
                        }
                    } else {
                        likeCandidates.firstOrNull()
                    }

                    if (matchedAction != null) {
                        ctrl.transportControls.sendCustomAction(matchedAction, matchedAction.extras)
                        dispatched = true
                    }
                }

                // Priority 2: MediaSession Rating API (ONLY if no CustomAction handled it!)
                if (!dispatched && ctrl.ratingType != Rating.RATING_NONE) {
                    runCatching {
                        when (ctrl.ratingType) {
                            Rating.RATING_HEART -> {
                                ctrl.transportControls.setRating(Rating.newHeartRating(newLiked))
                                dispatched = true
                            }
                            Rating.RATING_THUMB_UP_DOWN -> {
                                ctrl.transportControls.setRating(
                                    if (newLiked) Rating.newThumbRating(true) else Rating.newUnratedRating(Rating.RATING_THUMB_UP_DOWN)
                                )
                                dispatched = true
                            }
                            Rating.RATING_3_STARS -> {
                                ctrl.transportControls.setRating(
                                    if (newLiked) Rating.newStarRating(Rating.RATING_3_STARS, 3f) else Rating.newUnratedRating(Rating.RATING_3_STARS)
                                )
                                dispatched = true
                            }
                            Rating.RATING_4_STARS -> {
                                ctrl.transportControls.setRating(
                                    if (newLiked) Rating.newStarRating(Rating.RATING_4_STARS, 4f) else Rating.newUnratedRating(Rating.RATING_4_STARS)
                                )
                                dispatched = true
                            }
                            Rating.RATING_5_STARS -> {
                                ctrl.transportControls.setRating(
                                    if (newLiked) Rating.newStarRating(Rating.RATING_5_STARS, 5f) else Rating.newUnratedRating(Rating.RATING_5_STARS)
                                )
                                dispatched = true
                            }
                            Rating.RATING_PERCENTAGE -> {
                                ctrl.transportControls.setRating(
                                    if (newLiked) Rating.newPercentageRating(100f) else Rating.newUnratedRating(Rating.RATING_PERCENTAGE)
                                )
                                dispatched = true
                            }
                        }
                    }
                }
            }

            // Priority 3: Active Media Notification Action PendingIntent
            if (!dispatched) {
                runCatching {
                    val notifActions = activeNotificationActions.orEmpty()
                    val likeNotifAction = notifActions.firstOrNull { act ->
                        val title = (act.title?.toString() ?: "").lowercase()
                        val iconResId = runCatching { act.getIcon()?.resId ?: 0 }.getOrElse {
                            @Suppress("DEPRECATION")
                            act.icon
                        }
                        val iconName = resolveRemoteDrawableName(activeNotificationPackage, iconResId)
                        val combined = "$title $iconName"
                        val isDislike = combined.contains("dislike") || combined.contains("thumb down") || combined.contains("thumbs down") || combined.contains("thumb_down")
                        val isLike = combined.contains("like") || combined.contains("heart") || combined.contains("fav") ||
                            combined.contains("love") || combined.contains("thumb") || combined.contains("save")
                        !isDislike && isLike && act.actionIntent != null
                    }
                    if (likeNotifAction != null) {
                        likeNotifAction.actionIntent.send()
                        dispatched = true
                    }
                }
            }

            if (!dispatched && ctrl != null) {
                runCatching {
                    ctrl.transportControls.setRating(
                        if (newLiked) Rating.newHeartRating(true) else Rating.newUnratedRating(Rating.RATING_HEART)
                    )
                }
            }

            delay(200L)
            refreshActiveSessions(context)
            onImmediateWidgetUpdateRequested?.invoke()
            delay(320L)
            refreshActiveSessions(context)
            onImmediateWidgetUpdateRequested?.invoke()
        }
    }

    fun seekTo(context: Context, positionMs: Long) {
        scope.launch(Dispatchers.Main) {
            runCatching {
                activeController?.transportControls?.seekTo(positionMs.coerceAtLeast(0L))
                val cur = _nowPlaying.value
                _nowPlaying.value = cur.copy(
                    basePositionMs = positionMs.coerceAtLeast(0L),
                    lastPositionUpdateElapsedMs = SystemClock.elapsedRealtime()
                )
                onImmediateWidgetUpdateRequested?.invoke()
            }
        }
    }

    fun seekRelative(context: Context, deltaMs: Long) {
        val cur = _nowPlaying.value
        val target = (cur.currentPositionMs() + deltaMs).coerceIn(0L, if (cur.durationMs > 0L) cur.durationMs else Long.MAX_VALUE)
        seekTo(context, target)
    }

    private fun dispatchAudioMediaKey(context: Context, keyCode: Int) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (am != null) {
                    val now = SystemClock.uptimeMillis()
                    am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
                    am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
                }
            }
        }
    }
}

class DynamicLyricsNotificationListenerService : NotificationListenerService() {

    companion object {
        val mediaState: NowPlayingMediaState
            get() = NikoMediaSessionStateHolder.nowPlaying.value

        fun currentInterpolatedPositionMs(): Long =
            NikoMediaSessionStateHolder.nowPlaying.value.currentPositionMs()

        fun isNotificationAccessGranted(context: Context): Boolean =
            NikoMediaSessionStateHolder.isNotificationListenerEnabled(context)

        fun requestRefresh(context: Context) {
            NikoMediaSessionStateHolder.refreshActiveSessions(context)
        }

        fun togglePlayPause(context: Context) {
            NikoMediaSessionStateHolder.togglePlayPause(context)
        }

        fun skipToPrevious(context: Context) {
            NikoMediaSessionStateHolder.skipPrevious(context)
        }

        fun skipToNext(context: Context) {
            NikoMediaSessionStateHolder.skipNext(context)
        }

        fun toggleLikeCurrentTrack(context: Context) {
            NikoMediaSessionStateHolder.toggleLikeCurrentTrack(context)
        }

        fun seekTo(positionMs: Long) {
            val ctx = NikoMediaSessionStateHolder.getCachedContext() ?: return
            NikoMediaSessionStateHolder.seekTo(ctx, positionMs)
        }
    }


    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        val preferred = NikoMediaSessionStateHolder.selectBestController(controllers)
        if (preferred != null) {
            NikoMediaSessionStateHolder.bindController(preferred)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val cn = ComponentName(this, DynamicLyricsNotificationListenerService::class.java)
            msm?.addOnActiveSessionsChangedListener(sessionListener, cn)
            NikoMediaSessionStateHolder.refreshActiveSessions(this)
            activeNotifications?.forEach { inspectMediaNotification(it) }
        }
    }

    override fun onListenerDisconnected() {
        runCatching {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            msm?.removeOnActiveSessionsChangedListener(sessionListener)
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) {
            inspectMediaNotification(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        NikoMediaSessionStateHolder.refreshActiveSessions(this)
    }

    @Suppress("DEPRECATION")
    private fun inspectMediaNotification(sbn: StatusBarNotification) {
        runCatching {
            val notif = sbn.notification ?: return@runCatching
            NikoMediaSessionStateHolder.updateActiveNotificationActions(sbn.packageName ?: "", notif.actions)
            val extras = notif.extras ?: return@runCatching
            val largeIconBmp = runCatching<Bitmap?> {
                val drawable = notif.getLargeIcon()?.loadDrawable(applicationContext)
                (drawable as? BitmapDrawable)?.bitmap
            }.getOrNull()

            val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            }
            if (token != null) {
                val controller = MediaController(applicationContext, token)
                NikoMediaSessionStateHolder.bindController(controller, largeIconBmp)
            } else {
                NikoMediaSessionStateHolder.refreshActiveSessions(this)
            }
        }
    }
}
