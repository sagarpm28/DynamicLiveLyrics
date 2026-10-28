package com.nikolink.dynamiclyrics.lyrics

import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class SyncedLyricWord(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long
) {
    val word: String get() = text
    val startMs: Long get() = startTimeMs
    val endMs: Long get() = endTimeMs
}

data class SyncedLyricLine(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
    val words: List<SyncedLyricWord> = emptyList()
)

enum class LyricsProviderSource(val displayName: String) {
    PAXSENIX("Paxsenix"),
    BETTER_LYRICS("BetterLyrics"),
    LYRICS_PLUS("LyricsPlus"),
    LRCLIB("LrcLib"),
    KUGOU("KuGou"),
    ZEMER("Zemer")
}

data class LiveLyricsResult(
    val trackKey: String = "",
    val title: String = "",
    val artist: String = "",
    val provider: LyricsProviderSource? = null,
    val availableProviders: List<LyricsProviderSource> = emptyList(),
    val isLoading: Boolean = false,
    val isWordSynced: Boolean = false,
    val lines: List<SyncedLyricLine> = emptyList(),
    val errorMessage: String? = null
)

// Easter Egg: "Look who is here" | "You aren't supposed to be here"
typealias LyricsUiState = LiveLyricsResult

object NikoLiveLyricsEngine {
    const val LYRICS_EASTER_EGG = "You know the source code is public — Bruh, it's vibe coded app, it ain't hiding any secrets"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Strict priority order:
    // 1. Paxsenix -> 2. BetterLyrics -> 3. LyricsPlus -> 4. LrcLib -> 5. KuGou -> 6. Zemer
    val PROVIDER_PRIORITY_ORDER = listOf(
        LyricsProviderSource.PAXSENIX,
        LyricsProviderSource.BETTER_LYRICS,
        LyricsProviderSource.LYRICS_PLUS,
        LyricsProviderSource.LRCLIB,
        LyricsProviderSource.KUGOU,
        LyricsProviderSource.ZEMER
    )

    // Positive + negative result cache so a track (or YouTube video without lyrics) NEVER loops!
    private val memoryCache = LruCache<String, LiveLyricsResult>(100)
    // Stores all providers that returned valid lyrics for a given trackKey during the full parallel inquiry
    private val multiProviderCache = ConcurrentHashMap<String, Map<LyricsProviderSource, List<SyncedLyricLine>>>()
    // Tracks keys that have already been fully inquired (including tracks/videos with 0 lyrics) to prevent any re-fetch loop
    private val completedInquiryKeys = ConcurrentHashMap<String, Long>()

    private val _lyricsState = MutableStateFlow(LiveLyricsResult())
    val lyricsState: StateFlow<LiveLyricsResult> = _lyricsState.asStateFlow()

    private var activeFetchJob: Job? = null
    private var lastTrackKey: String = ""
    private var lastTitle: String = ""
    private var lastArtist: String = ""
    private var lastAlbum: String = ""
    private var lastDurationSec: Int = 0


    enum class LyricsProviderOption(val id: String, val label: String, val source: LyricsProviderSource?) {
        AUTO("auto", "Auto (Best Synced)", null),
        PAXSENIX("paxsenix", "Paxsenix", LyricsProviderSource.PAXSENIX),
        BETTER_LYRICS("betterlyrics", "BetterLyrics", LyricsProviderSource.BETTER_LYRICS),
        LYRICS_PLUS("lyricsplus", "LyricsPlus", LyricsProviderSource.LYRICS_PLUS),
        LRCLIB("lrclib", "LrcLib", LyricsProviderSource.LRCLIB),
        KUGOU("kugou", "KuGou", LyricsProviderSource.KUGOU),
        ZEMER("zemer", "Zemer", LyricsProviderSource.ZEMER)
    }


    var preferredProvider: LyricsProviderOption = LyricsProviderOption.AUTO
        private set

    val currentLyrics: LiveLyricsResult?
        get() = _lyricsState.value.takeIf { it.lines.isNotEmpty() || it.provider != null }

    val isLoading: Boolean
        get() = _lyricsState.value.isLoading

    fun onTrackChanged(title: String, artist: String, album: String, durationMs: Long) {
        onTrackChanged(title, artist, album, (durationMs / 1000L).toInt().coerceAtLeast(0))
    }

    fun cycleProvider(title: String, artist: String, album: String, durationMs: Long) {
        if (title.isNotBlank() && lastTitle.isBlank()) {
            onTrackChanged(title, artist, album, durationMs)
        } else {
            cycleNextProvider()
        }
    }

    fun selectProvider(provider: LyricsProviderOption, title: String, artist: String, album: String, durationMs: Long) {
        preferredProvider = provider
        val targetSource = provider.source
        if (targetSource == null) {
            if (title.isNotBlank()) onTrackChanged(title, artist, album, durationMs)
            return
        }
        val key = lastTrackKey
        val providerMap = multiProviderCache[key]
        if (providerMap != null && providerMap.containsKey(targetSource)) {
            val chosenLines = providerMap[targetSource] ?: return
            val availableOrdered = PROVIDER_PRIORITY_ORDER.filter { providerMap.containsKey(it) }
            val hasWordSync = chosenLines.any { it.words.isNotEmpty() }
            val updated = LiveLyricsResult(
                trackKey = key,
                title = lastTitle,
                artist = lastArtist,
                provider = targetSource,
                availableProviders = availableOrdered,
                isLoading = false,
                isWordSynced = hasWordSync,
                lines = chosenLines
            )
            memoryCache.put(key, updated)
            _lyricsState.value = updated
        } else if (title.isNotBlank()) {
            onTrackChanged(title, artist, album, durationMs)
        }
    }

    fun findActiveLineIndex(lines: List<SyncedLyricLine>, currentPosMs: Long): Int {
        if (lines.isEmpty() || currentPosMs < lines[0].startTimeMs) return -1
        var low = 0
        var high = lines.size - 1
        var ans = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startTimeMs <= currentPosMs) {
                ans = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return ans
    }

    fun findActiveWordIndex(words: List<SyncedLyricWord>, currentPosMs: Long): Int {
        if (words.isEmpty()) return -1
        for (i in words.indices) {
            val w = words[i]
            if (currentPosMs in w.startTimeMs..w.endTimeMs) return i
        }
        return -1
    }

    fun onTrackChanged(title: String, artist: String, album: String, durationSec: Int) {
        val cleanTitle = cleanTrackMetadata(title)
        val cleanArtist = cleanArtistMetadata(artist)
        if (cleanTitle.isBlank()) return

        val key = "${cleanTitle.lowercase()}|${cleanArtist.lowercase()}"

        // CRITICAL FIX: If this exact track key is already loading OR has already finished its inquiry
        // (even if no lyrics were found, e.g. a YouTube video), NEVER start another search loop!
        if (key == lastTrackKey && (_lyricsState.value.isLoading || completedInquiryKeys.containsKey(key))) {
            return
        }

        lastTrackKey = key
        lastTitle = cleanTitle
        lastArtist = cleanArtist
        lastAlbum = album.trim()
        lastDurationSec = durationSec

        memoryCache.get(key)?.let { cached ->
            completedInquiryKeys[key] = System.currentTimeMillis()
            _lyricsState.value = cached
            return
        }

        inquireAllProvidersAndSelectBest(
            key = key,
            title = cleanTitle,
            artist = cleanArtist,
            album = lastAlbum,
            durationSec = durationSec,
            forceRefresh = false
        )
    }

    /**
     * Cycles through all providers that actually returned lyrics during the full parallel inquiry.
     * If no providers had lyrics and the user manually taps the header, triggers a single manual re-inquiry.
     */
    fun cycleNextProvider() {
        if (lastTitle.isBlank()) return
        val key = lastTrackKey
        val providerMap = multiProviderCache[key]
        if (!providerMap.isNullOrEmpty()) {
            val availableOrdered = PROVIDER_PRIORITY_ORDER.filter { providerMap.containsKey(it) }
            if (availableOrdered.isNotEmpty()) {
                val current = _lyricsState.value.provider
                val curIdx = if (current != null) availableOrdered.indexOf(current) else -1
                val nextProvider = availableOrdered[(curIdx + 1) % availableOrdered.size]
                val chosenLines = providerMap[nextProvider] ?: return
                val hasWords = chosenLines.any { it.words.size > 1 }
                val updated = LiveLyricsResult(
                    trackKey = key,
                    title = lastTitle,
                    artist = lastArtist,
                    provider = nextProvider,
                    availableProviders = availableOrdered,
                    isLoading = false,
                    isWordSynced = hasWords,
                    lines = chosenLines
                )
                memoryCache.put(key, updated)
                _lyricsState.value = updated
                return
            }
        }

        // Manual retry only when user explicitly taps the provider pill
        inquireAllProvidersAndSelectBest(
            key = key,
            title = lastTitle,
            artist = lastArtist,
            album = lastAlbum,
            durationSec = lastDurationSec,
            forceRefresh = true
        )
    }

    /**
     * Queries ALL 6 providers concurrently first to discover every provider that has lyrics for this track,
     * then selects the best provider strictly by priority (preferring word-synced providers in priority order,
     * then line-synced providers in priority order). Caches both positive and negative results so failed lookups
     * on non-music YouTube videos never loop.
     */
    private fun inquireAllProvidersAndSelectBest(
        key: String,
        title: String,
        artist: String,
        album: String,
        durationSec: Int,
        forceRefresh: Boolean
    ) {
        activeFetchJob?.cancel()
        if (forceRefresh) {
            completedInquiryKeys.remove(key)
            memoryCache.remove(key)
            multiProviderCache.remove(key)
        }

        activeFetchJob = scope.launch {
            _lyricsState.value = LiveLyricsResult(
                trackKey = key,
                title = title,
                artist = artist,
                isLoading = true
            )

            // 1. Enquire from ALL providers in parallel
            val inquiryResults: List<Pair<LyricsProviderSource, List<SyncedLyricLine>?>> = coroutineScope {
                PROVIDER_PRIORITY_ORDER.map { provider ->
                    async(Dispatchers.IO) {
                        val fetched = runCatching {
                            when (provider) {
                                LyricsProviderSource.PAXSENIX -> fetchFromPaxsenix(title, artist, durationSec)
                                LyricsProviderSource.BETTER_LYRICS -> fetchFromBetterLyrics(title, artist, album, durationSec)
                                LyricsProviderSource.LYRICS_PLUS -> fetchFromLyricsPlus(title, artist, album, durationSec)
                                LyricsProviderSource.LRCLIB -> fetchFromLrcLib(title, artist, album, durationSec)
                                LyricsProviderSource.KUGOU -> fetchFromKuGou(title, artist, durationSec)
                                LyricsProviderSource.ZEMER -> fetchFromZemer(title, artist, durationSec)
                            }
                        }.getOrNull()
                        val finalized = if (!fetched.isNullOrEmpty()) finalizeLineEndTimes(fetched) else null
                        provider to finalized
                    }
                }.awaitAll()
            }

            // 2. Filter valid non-empty results from all providers
            val validMap = LinkedHashMap<LyricsProviderSource, List<SyncedLyricLine>>()
            for (provider in PROVIDER_PRIORITY_ORDER) {
                val lines = inquiryResults.firstOrNull { it.first == provider }?.second
                if (!lines.isNullOrEmpty()) {
                    validMap[provider] = lines
                }
            }

            completedInquiryKeys[key] = System.currentTimeMillis()

            if (validMap.isNotEmpty()) {
                multiProviderCache[key] = validMap
                val availableOrdered = validMap.keys.toList()

                // 3. Select winner by strict priority:
                //    First check priority order for word-by-word synced lyrics;
                //    otherwise pick the highest-priority provider with line-synced lyrics.
                val bestWordSyncedProvider = PROVIDER_PRIORITY_ORDER.firstOrNull { p ->
                    val l = validMap[p]
                    l != null && l.any { line -> line.words.size > 1 }
                }
                val winningProvider = bestWordSyncedProvider ?: availableOrdered.first()
                val winningLines = validMap[winningProvider]!!
                val hasWords = winningLines.any { it.words.size > 1 }

                val finalResult = LiveLyricsResult(
                    trackKey = key,
                    title = title,
                    artist = artist,
                    provider = winningProvider,
                    availableProviders = availableOrdered,
                    isLoading = false,
                    isWordSynced = hasWords,
                    lines = winningLines
                )
                memoryCache.put(key, finalResult)
                _lyricsState.value = finalResult
            } else {
                // Negative cache: remember that this track/video has no lyrics so we NEVER loop!
                val emptyResult = LiveLyricsResult(
                    trackKey = key,
                    title = title,
                    artist = artist,
                    provider = null,
                    availableProviders = emptyList(),
                    isLoading = false,
                    isWordSynced = false,
                    lines = emptyList(),
                    errorMessage = "No synced lyrics found across providers"
                )
                memoryCache.put(key, emptyResult)
                _lyricsState.value = emptyResult
            }
        }
    }

    // ------------------------------------------------------------------------
    // 1. PAXSENIX (https://lyrics.paxsenix.org)
    // ------------------------------------------------------------------------
    private fun fetchFromPaxsenix(title: String, artist: String, durationSec: Int): List<SyncedLyricLine>? {
        val q = urlEncode("$title $artist".trim())
        val searchUrls = listOf(
            "https://lyrics.paxsenix.org/apple-music/search?q=$q",
            "https://lyrics.paxsenix.org/search?q=$q"
        )

        for (sUrl in searchUrls) {
            val rawSearch = httpGet(sUrl, timeoutMs = 3600) ?: continue
            val songId = extractPaxsenixSongId(rawSearch, title, artist, durationSec) ?: continue

            val lyricUrls = listOf(
                "https://lyrics.paxsenix.org/apple-music/lyrics?id=$songId&ttml=true&v=2",
                "https://lyrics.paxsenix.org/apple-music/lyrics?id=$songId"
            )
            for (lUrl in lyricUrls) {
                val payload = httpGet(lUrl, timeoutMs = 3800) ?: continue
                val parsed = parseFlexibleLyricsPayload(payload)
                if (parsed.isNotEmpty()) return parsed
            }
        }
        return null
    }

    private fun extractPaxsenixSongId(
        rawJson: String,
        targetTitle: String,
        targetArtist: String,
        targetDurationSec: Int
    ): String? {
        return runCatching<String?> {
            val trimmed = rawJson.trim()
            val arr = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("results") ?: obj.optJSONArray("data") ?: obj.optJSONArray("songs")
            } ?: return@runCatching null

            val normTitle = targetTitle.lowercase().trim()
            var bestId: String? = null
            var bestDiff = Int.MAX_VALUE
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id").ifBlank { item.optString("songId") }.ifBlank { item.optString("trackId") }
                if (id.isBlank()) continue
                val itemName = item.optString("name", item.optString("title", "")).lowercase()
                if (itemName.isNotBlank() && normTitle.length >= 4 && !itemName.contains(normTitle.take(4)) && !normTitle.contains(itemName.take(4))) {
                    continue
                }
                val durMs = item.optLong("durationInMillis", item.optLong("duration", 0L))
                val durSec = if (durMs > 1000L) (durMs / 1000L).toInt() else durMs.toInt()
                if (targetDurationSec > 0 && durSec > 0 && abs(durSec - targetDurationSec) > 25) {
                    continue
                }
                if (targetDurationSec <= 0 || durSec <= 0) {
                    return@runCatching id
                }
                val diff = abs(durSec - targetDurationSec)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestId = id
                }
            }
            bestId
        }.getOrNull()
    }

    // ------------------------------------------------------------------------
    // 2. BETTER LYRICS (https://lyrics-api.boidu.dev)
    // ------------------------------------------------------------------------
    private fun fetchFromBetterLyrics(title: String, artist: String, album: String, durationSec: Int): List<SyncedLyricLine>? {
        val t = urlEncode(title)
        val a = urlEncode(artist)
        val al = urlEncode(album)
        val durParam = if (durationSec > 0) "&d=$durationSec" else ""
        val endpoints = listOf(
            "https://lyrics-api.boidu.dev/getLyrics?s=$t&a=$a&al=$al$durParam",
            "https://lyrics-api.boidu.dev/getLyrics?title=$t&artist=$a",
            "https://unison.boidu.dev/getLyrics?s=$t&a=$a$durParam"
        )
        for (url in endpoints) {
            val raw = httpGet(url, timeoutMs = 3600) ?: continue
            val parsed = parseFlexibleLyricsPayload(raw)
            if (parsed.isNotEmpty()) return parsed
        }
        return null
    }

    // ------------------------------------------------------------------------
    // 3. LYRICSPLUS (Multi-Backend Word-Sync Aggregator)
    // ------------------------------------------------------------------------
    private fun fetchFromLyricsPlus(title: String, artist: String, album: String, durationSec: Int): List<SyncedLyricLine>? {
        val t = urlEncode(title)
        val a = urlEncode(artist)
        val al = urlEncode(album)
        val dur = if (durationSec > 0) "&duration=$durationSec" else ""
        val query = "/v2/lyrics/get?title=$t&artist=$a&album=$al$dur&source=apple,lyricsplus,musixmatch-word,musixmatch,spotify"
        val bases = listOf(
            "https://lyricsplus.prjktla.workers.dev",
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.atomix.one",
            "https://lyrics-plus-backend.vercel.app"
        )

        for (base in bases) {
            val raw = httpGet(base + query, timeoutMs = 3600) ?: continue
            val parsed = parseLyricsPlusJson(raw)
            if (parsed.isNotEmpty()) return parsed
        }
        return null
    }

    private fun parseLyricsPlusJson(raw: String): List<SyncedLyricLine> {
        return runCatching<List<SyncedLyricLine>> {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("lyrics") ?: return@runCatching emptyList()
            val out = ArrayList<SyncedLyricLine>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val startMs = item.optLong("time", -1L)
                val durMs = item.optLong("duration", 3000L).coerceAtLeast(300L)
                val text = item.optString("text").trim()
                if (startMs < 0L || text.isBlank() || text.startsWith("[")) continue

                val wordsList = ArrayList<SyncedLyricWord>()
                val syllabus = item.optJSONArray("syllabus") ?: item.optJSONArray("words")
                if (syllabus != null && syllabus.length() > 0) {
                    for (wIdx in 0 until syllabus.length()) {
                        val wObj = syllabus.optJSONObject(wIdx) ?: continue
                        val wStart = wObj.optLong("time", startMs)
                        val wDur = wObj.optLong("duration", 250L).coerceAtLeast(50L)
                        val wText = wObj.optString("text")
                        if (wText.isNotEmpty()) {
                            wordsList.add(
                                SyncedLyricWord(
                                    text = wText,
                                    startTimeMs = wStart,
                                    endTimeMs = wStart + wDur
                                )
                            )
                        }
                    }
                }
                out.add(
                    SyncedLyricLine(
                        startTimeMs = startMs,
                        endTimeMs = startMs + durMs,
                        text = text,
                        words = wordsList
                    )
                )
            }
            out.sortedBy { it.startTimeMs }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------------
    // 4. LRCLIB (https://lrclib.net)
    // ------------------------------------------------------------------------
    private fun fetchFromLrcLib(title: String, artist: String, album: String, durationSec: Int): List<SyncedLyricLine>? {
        val t = urlEncode(title)
        val a = urlEncode(artist)
        val al = urlEncode(album)
        val dur = if (durationSec > 0) "&duration=$durationSec" else ""

        val directUrl = "https://lrclib.net/api/get?track_name=$t&artist_name=$a&album_name=$al$dur"
        httpGet(directUrl, timeoutMs = 3500)?.let { raw ->
            val parsed = parseLrcLibResponse(raw, durationSec)
            if (parsed.isNotEmpty()) return parsed
        }

        val searchUrl = "https://lrclib.net/api/search?track_name=$t&artist_name=$a"
        httpGet(searchUrl, timeoutMs = 3600)?.let { raw ->
            val parsed = parseLrcLibResponse(raw, durationSec)
            if (parsed.isNotEmpty()) return parsed
        }
        return null
    }

    private fun parseLrcLibResponse(raw: String, targetDurationSec: Int): List<SyncedLyricLine> {
        return runCatching<List<SyncedLyricLine>> {
            val trimmed = raw.trim()
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                val synced = obj.optString("syncedLyrics")
                if (synced.isNotBlank() && synced != "null") {
                    return@runCatching parseLrcText(synced)
                }
            } else if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                var bestLrc: String? = null
                var bestDiff = Int.MAX_VALUE
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val synced = item.optString("syncedLyrics")
                    if (synced.isBlank() || synced == "null") continue
                    val dur = item.optDouble("duration", 0.0).toInt()
                    if (targetDurationSec > 0 && dur > 0 && abs(dur - targetDurationSec) > 25) continue
                    val diff = if (targetDurationSec > 0 && dur > 0) abs(dur - targetDurationSec) else i
                    if (diff < bestDiff) {
                        bestDiff = diff
                        bestLrc = synced
                    }
                }
                if (!bestLrc.isNullOrBlank()) {
                    return@runCatching parseLrcText(bestLrc)
                }
            }
            emptyList()
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------------
    // 5. KUGOU (https://mobileservice.kugou.com & https://lyrics.kugou.com)
    // ------------------------------------------------------------------------
    private fun fetchFromKuGou(title: String, artist: String, durationSec: Int): List<SyncedLyricLine>? {
        val keyword = urlEncode("$artist - $title".trim(' ', '-'))
        val searchUrl = "https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=6&showtype=0&keyword=$keyword"
        val searchRaw = httpGet(searchUrl, timeoutMs = 3500) ?: return null

        val candidateHashAndDur = runCatching<Pair<String, Int>?> {
            val root = JSONObject(searchRaw)
            val data = root.optJSONObject("data") ?: return@runCatching null
            val info = data.optJSONArray("info") ?: return@runCatching null
            val normTitle = title.lowercase().trim()
            var bestHash: String? = null
            var bestDurMs = durationSec * 1000
            var bestDiff = Int.MAX_VALUE
            for (i in 0 until info.length()) {
                val item = info.optJSONObject(i) ?: continue
                val songName = item.optString("songname", item.optString("filename", "")).lowercase()
                if (songName.isNotBlank() && normTitle.length >= 4 && !songName.contains(normTitle.take(4))) continue
                val hash = item.optString("hash")
                val dur = item.optInt("duration", 0)
                if (hash.isBlank()) continue
                val diff = if (durationSec > 0 && dur > 0) abs(dur - durationSec) else i
                if (diff < bestDiff && (durationSec <= 0 || diff <= 15)) {
                    bestDiff = diff
                    bestHash = hash
                    bestDurMs = if (dur > 0) dur * 1000 else bestDurMs
                }
            }
            if (bestHash != null) Pair(bestHash, bestDurMs) else null
        }.getOrNull() ?: return null

        val (hash, durMs) = candidateHashAndDur
        val lrcSearchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${urlEncode(title)}&duration=$durMs&hash=$hash"
        val lrcCandidatesRaw = httpGet(lrcSearchUrl, timeoutMs = 3500) ?: return null

        val idAndKey = runCatching<Pair<String, String>?> {
            val obj = JSONObject(lrcCandidatesRaw)
            val candidates = obj.optJSONArray("candidates") ?: return@runCatching null
            if (candidates.length() == 0) return@runCatching null
            val first = candidates.getJSONObject(0)
            val id = first.optString("id")
            val key = first.optString("accesskey")
            if (id.isNotBlank() && key.isNotBlank()) Pair(id, key) else null
        }.getOrNull() ?: return null

        val downloadUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=${idAndKey.first}&accesskey=${idAndKey.second}&fmt=lrc&charset=utf8"
        val downloadRaw = httpGet(downloadUrl, timeoutMs = 3500) ?: return null

        return runCatching<List<SyncedLyricLine>?> {
            val obj = JSONObject(downloadRaw)
            val base64Content = obj.optString("content")
            if (base64Content.isBlank()) return@runCatching null
            val decodedLrc = String(Base64.decode(base64Content, Base64.DEFAULT), Charsets.UTF_8)
            parseLrcText(decodedLrc).takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    // ------------------------------------------------------------------------
    // 6. ZEMER (Fallback Synced Lyrics Provider)
    // ------------------------------------------------------------------------
    private fun fetchFromZemer(title: String, artist: String, durationSec: Int): List<SyncedLyricLine>? {
        val q = urlEncode("$title $artist".trim())
        val urls = listOf(
            "https://zemer.co.il/api/lyrics?q=$q"
        )
        for (url in urls) {
            val raw = httpGet(url, timeoutMs = 3400) ?: continue
            val parsed = parseFlexibleLyricsPayload(raw).ifEmpty { parseLrcLibResponse(raw, durationSec) }
            if (parsed.isNotEmpty()) return parsed
        }
        return null
    }

    // ------------------------------------------------------------------------
    // UNIVERSAL PARSERS: TTML (Word-by-Word), JSON Syllabus, Enhanced & Standard LRC
    // ------------------------------------------------------------------------
    private fun parseFlexibleLyricsPayload(raw: String): List<SyncedLyricLine> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()

        if (trimmed.startsWith("<")) {
            val ttmlLines = parseTtmlXml(trimmed)
            if (ttmlLines.isNotEmpty()) return ttmlLines
        }

        if (trimmed.startsWith("{")) {
            return runCatching<List<SyncedLyricLine>> {
                val obj = JSONObject(trimmed)
                val ttml = obj.optString("ttml").ifBlank { obj.optString("ttmlLyrics") }
                if (ttml.isNotBlank() && ttml.trim().startsWith("<")) {
                    val parsedTtml = parseTtmlXml(ttml.trim())
                    if (parsedTtml.isNotEmpty()) return@runCatching parsedTtml
                }
                if (obj.has("lyrics") && obj.opt("lyrics") is JSONArray) {
                    val kpoe = parseLyricsPlusJson(trimmed)
                    if (kpoe.isNotEmpty()) return@runCatching kpoe
                }
                val lrcStr = obj.optString("syncedLyrics")
                    .ifBlank { obj.optString("lyrics") }
                    .ifBlank { obj.optString("lrc") }
                if (lrcStr.isNotBlank()) {
                    if (lrcStr.trim().startsWith("<")) {
                        return@runCatching parseTtmlXml(lrcStr.trim())
                    }
                    return@runCatching parseLrcText(lrcStr)
                }
                emptyList()
            }.getOrDefault(emptyList())
        }

        if (trimmed.startsWith("[")) {
            return runCatching<List<SyncedLyricLine>> {
                if (trimmed.contains("\"time\"") || trimmed.contains("\"startTimeMs\"")) {
                    val arr = JSONArray(trimmed)
                    val out = ArrayList<SyncedLyricLine>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val startMs = item.optLong("startTimeMs", item.optLong("time", -1L))
                        val durMs = item.optLong("durationMs", item.optLong("duration", 3000L))
                        val text = item.optString("words").ifBlank { item.optString("text") }.trim()
                        if (startMs >= 0L && text.isNotBlank()) {
                            out.add(SyncedLyricLine(startMs, startMs + durMs, text))
                        }
                    }
                    if (out.isNotEmpty()) return@runCatching out
                }
                parseLrcText(trimmed)
            }.getOrDefault(emptyList())
        }

        return parseLrcText(trimmed)
    }

    private fun parseTtmlXml(xml: String): List<SyncedLyricLine> {
        return runCatching<List<SyncedLyricLine>> {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            val lines = ArrayList<SyncedLyricLine>()
            var inP = false
            var pBeginMs = 0L
            var pEndMs = 0L
            val pTextBuilder = StringBuilder()
            var currentWords = ArrayList<SyncedLyricWord>()
            var sawInterSpanWhitespaceInP = false

            var inSpan = false
            var spanBeginMs = -1L
            var spanEndMs = -1L
            val spanTextBuilder = StringBuilder()

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase()
                        if (tag == "p") {
                            inP = true
                            pBeginMs = parseTtmlTimestampMs(parser.getAttributeValue(null, "begin"))
                            pEndMs = parseTtmlTimestampMs(parser.getAttributeValue(null, "end"))
                            pTextBuilder.setLength(0)
                            currentWords = ArrayList()
                            sawInterSpanWhitespaceInP = false
                        } else if (inP && tag == "span") {
                            val bAttr = parser.getAttributeValue(null, "begin")
                            val eAttr = parser.getAttributeValue(null, "end")
                            if (bAttr != null) {
                                inSpan = true
                                spanBeginMs = parseTtmlTimestampMs(bAttr)
                                spanEndMs = if (eAttr != null) parseTtmlTimestampMs(eAttr) else spanBeginMs + 350L
                                spanTextBuilder.setLength(0)
                            }
                        }
                    }

                    XmlPullParser.TEXT -> {
                        val txt = parser.text ?: ""
                        if (inSpan) {
                            spanTextBuilder.append(txt)
                        } else if (inP) {
                            if (txt.any { it.isWhitespace() }) {
                                sawInterSpanWhitespaceInP = true
                                if (currentWords.isNotEmpty()) {
                                    val lastIdx = currentWords.lastIndex
                                    val lastWord = currentWords[lastIdx]
                                    if (!lastWord.text.endsWith(" ")) {
                                        currentWords[lastIdx] = lastWord.copy(text = lastWord.text + " ")
                                    }
                                }
                                if (pTextBuilder.isNotEmpty() && !pTextBuilder.endsWith(" ")) {
                                    pTextBuilder.append(" ")
                                }
                            } else if (txt.isNotEmpty()) {
                                pTextBuilder.append(txt)
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.lowercase()
                        if (tag == "span" && inSpan) {
                            inSpan = false
                            val rawSpan = spanTextBuilder.toString()
                            if (rawSpan.isNotEmpty()) {
                                currentWords.add(
                                    SyncedLyricWord(
                                        text = rawSpan,
                                        startTimeMs = spanBeginMs,
                                        endTimeMs = spanEndMs.coerceAtLeast(spanBeginMs + 80L)
                                    )
                                )
                                pTextBuilder.append(rawSpan)
                            }
                        } else if (tag == "p" && inP) {
                            inP = false
                            val anySpanHasSpace = currentWords.any { it.text.contains(" ") }
                            val finalWords = if (!sawInterSpanWhitespaceInP && !anySpanHasSpace && currentWords.size > 1) {
                                currentWords.mapIndexed { idx, w ->
                                    if (idx < currentWords.lastIndex) w.copy(text = w.text + " ") else w
                                }
                            } else {
                                currentWords
                            }
                            val fullLine = if (finalWords.isNotEmpty()) {
                                finalWords.joinToString("") { it.text }.replace(Regex("\\s+"), " ").trim()
                            } else {
                                pTextBuilder.toString().replace(Regex("\\s+"), " ").trim()
                            }
                            if (fullLine.isNotBlank() && pBeginMs >= 0L) {
                                val effectiveEnd = if (pEndMs > pBeginMs) pEndMs else (finalWords.lastOrNull()?.endTimeMs ?: (pBeginMs + 3500L))
                                lines.add(
                                    SyncedLyricLine(
                                        startTimeMs = pBeginMs,
                                        endTimeMs = effectiveEnd,
                                        text = fullLine,
                                        words = finalWords
                                    )
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
            lines.sortedBy { it.startTimeMs }
        }.getOrDefault(emptyList())
    }

    private fun parseTtmlTimestampMs(raw: String?): Long {
        if (raw.isNullOrBlank()) return -1L
        val s = raw.trim()
        return runCatching<Long> {
            if (s.endsWith("ms")) {
                return@runCatching s.removeSuffix("ms").toDouble().toLong()
            }
            if (s.endsWith("s")) {
                return@runCatching (s.removeSuffix("s").toDouble() * 1000.0).toLong()
            }
            val parts = s.split(":")
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val sec = parts[2].toDouble()
                    ((h * 3600L + m * 60L) * 1000L) + (sec * 1000.0).toLong()
                }
                2 -> {
                    val m = parts[0].toLong()
                    val sec = parts[1].toDouble()
                    (m * 60L * 1000L) + (sec * 1000.0).toLong()
                }
                else -> (s.toDouble() * 1000.0).toLong()
            }
        }.getOrDefault(-1L)
    }

    private val LINE_TIME_REGEX = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val WORD_TIME_REGEX = Regex("<(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?>([^<]*)")

    private fun parseLrcText(lrc: String): List<SyncedLyricLine> {
        val out = ArrayList<SyncedLyricLine>()
        for (rawLine in lrc.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val matches = LINE_TIME_REGEX.findAll(line).toList()
            if (matches.isEmpty()) continue

            val contentAfterTags = line.substring(matches.last().range.last + 1).trim()
            if (contentAfterTags.isEmpty()) continue

            val words = ArrayList<SyncedLyricWord>()
            val wordMatches = WORD_TIME_REGEX.findAll(contentAfterTags).toList()
            val cleanText = if (wordMatches.isNotEmpty()) {
                val sb = StringBuilder()
                for (i in wordMatches.indices) {
                    val wm = wordMatches[i]
                    val wStart = parseLrcComponentsMs(wm.groupValues[1], wm.groupValues[2], wm.groupValues[3])
                    val wRaw = wm.groupValues[4]
                    if (wRaw.isNotEmpty()) {
                        val nextStart = if (i + 1 < wordMatches.size) {
                            parseLrcComponentsMs(
                                wordMatches[i + 1].groupValues[1],
                                wordMatches[i + 1].groupValues[2],
                                wordMatches[i + 1].groupValues[3]
                            )
                        } else wStart + 400L
                        words.add(SyncedLyricWord(wRaw, wStart, nextStart.coerceAtLeast(wStart + 80L)))
                        sb.append(wRaw)
                    }
                }
                sb.toString().trim()
            } else {
                contentAfterTags.replace(Regex("<[^>]+>"), "").trim()
            }

            if (cleanText.isBlank()) continue

            for (m in matches) {
                val startMs = parseLrcComponentsMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                out.add(
                    SyncedLyricLine(
                        startTimeMs = startMs,
                        endTimeMs = startMs + 4000L,
                        text = cleanText,
                        words = words
                    )
                )
            }
        }
        return out.sortedBy { it.startTimeMs }
    }

    private fun parseLrcComponentsMs(minStr: String, secStr: String, fracStr: String): Long {
        val min = minStr.toLongOrNull() ?: 0L
        val sec = secStr.toLongOrNull() ?: 0L
        val fracMs = when (fracStr.length) {
            1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
            2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
            3 -> fracStr.toLongOrNull() ?: 0L
            else -> 0L
        }
        return (min * 60_000L) + (sec * 1_000L) + fracMs
    }

    private fun finalizeLineEndTimes(lines: List<SyncedLyricLine>): List<SyncedLyricLine> {
        if (lines.isEmpty()) return emptyList()
        return lines.mapIndexed { idx, line ->
            val nextStart = lines.getOrNull(idx + 1)?.startTimeMs
            val computedEnd = if (nextStart != null && nextStart > line.startTimeMs) {
                nextStart
            } else {
                line.endTimeMs.coerceAtLeast(line.startTimeMs + 3500L)
            }
            // Bridge intra-line stretched word gaps (`151904.mp4` Item 4 Fix):
            // Ensures a stretched vocal syllable smoothly spans up to the next word in the line
            // and never has non-monotonic or zero-duration word timestamps.
            val smoothedWords = if (line.words.size > 1) {
                line.words.mapIndexed { wIdx, w ->
                    val nextW = line.words.getOrNull(wIdx + 1)
                    val bridgedEnd = if (nextW != null && nextW.startTimeMs > w.startTimeMs) {
                        val gap = nextW.startTimeMs - w.endTimeMs
                        if (gap in 1L..550L) nextW.startTimeMs else w.endTimeMs.coerceAtMost(nextW.startTimeMs)
                    } else {
                        w.endTimeMs
                    }
                    w.copy(endTimeMs = bridgedEnd.coerceAtLeast(w.startTimeMs + 90L))
                }
            } else {
                line.words
            }
            line.copy(endTimeMs = computedEnd, words = smoothedWords)
        }
    }

    private fun cleanTrackMetadata(raw: String): String {
        return raw
            .replace(Regex("\\s*\\((?:Official|Lyric|Audio|Video|Visualizer|HD|HQ|4K)[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[(?:Official|Lyric|Audio|Video|Visualizer|HD|HQ|4K)[^]]*]", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cleanArtistMetadata(raw: String): String {
        return raw
            .replace(" - Topic", "", ignoreCase = true)
            .replace("VEVO", "", ignoreCase = true)
            .trim()
    }

    private fun urlEncode(value: String): String {
        return runCatching { URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun httpGet(urlStr: String, timeoutMs: Int = 3600): String? {
        return runCatching<String?> {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "NikoLink/0.10 (Android; LiveLyricsEngine)")
                setRequestProperty("Accept", "application/json, text/plain, application/xml, */*")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@runCatching null
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val text = reader.use { it.readText() }
            conn.disconnect()
            text.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
