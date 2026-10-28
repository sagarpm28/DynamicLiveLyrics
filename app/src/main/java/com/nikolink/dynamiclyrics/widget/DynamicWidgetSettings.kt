package com.nikolink.dynamiclyrics.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

enum class VisualEngineMode(val id: String, val label: String, val subtitle: String) {
    HYPEROS_FROSTED_BLUR(
        "hyperos_blur",
        "HyperOS Blur",
        "Blurs the song's album cover in the background with a frosted glass look"
    ),
    MATERIAL_YOU_MONET(
        "material_you",
        "Material You (Solid)",
        "Clean solid colors that match your phone's Android 12+ theme without blur"
    )
}

enum class AccentColorSource(val id: String, val label: String, val subtitle: String) {
    ALBUM_ART(
        "album_art",
        "From Album Art",
        "Picks the accent color from whatever song is currently playing"
    ),
    PHONE_SYSTEM(
        "phone_system",
        "Phone System Color",
        "Uses only your phone's own Material You system accent color"
    )
}

enum class WidgetThemeStyle(val id: String, val label: String) {
    DARK_HYPEROS("dark", "Dark"),
    LIGHT_HYPEROS("light", "Light"),
    AMOLED_GLASS("amoled", "AMOLED Black"),
    AUTO_SYSTEM("auto", "Auto (System)");

    companion object {
        val HYPEROS_LIGHT = LIGHT_HYPEROS
    }
}

typealias WidgetThemeMode = WidgetThemeStyle

enum class WidgetLayoutMode(val id: String, val label: String, val description: String) {
    AUTO_ADAPTIVE(
        "auto",
        "Auto Fit",
        "Automatically switches layout based on how you resize the widget"
    ),
    CLASSIC_STACKED(
        "classic_stacked",
        "Classic Stacked",
        "Album art on top, lyrics in the middle, wavy bar and 6 buttons at the bottom"
    ),
    SPLIT_SIDE_BY_SIDE(
        "split_side",
        "Side-by-Side Split",
        "Album art and 6 buttons on the left side, wide lyrics box on the right side"
    ),
    VERTICAL_TOWER(
        "vertical_tower",
        "Vertical Tall",
        "Made for tall narrow widgets (2×4 / 2×5) with wrapped multi-line lyrics"
    ),
    COMPACT_STRIP(
        "compact_strip",
        "Compact Bar",
        "Wide horizontal bar (4×1 / 4×2) with album art, 6 buttons, and 2-line lyrics"
    )
}

enum class WidgetFontFamily(val id: String, val label: String, val systemFamilyName: String?) {
    DEFAULT_SANS("default_sans", "Default", null),
    ROUNDED_SANS("rounded_sans", "Medium Sans", "sans-serif-medium"),
    SERIF_EDITORIAL("serif", "Serif", "serif"),
    MONOSPACE_TECH("monospace", "Monospace", "monospace"),
    CONDENSED_SANS("condensed", "Condensed", "sans-serif-condensed"),
    CURSIVE_SCRIPT("cursive", "Script", "cursive"),
    CUSTOM_FILE("custom_file", "Custom Font File", null)
}

object DynamicWidgetSettings {
    const val WIDGET_FPS = 30
    const val FRAME_INTERVAL_MS = 33L

    var visualEngineMode by mutableStateOf(VisualEngineMode.HYPEROS_FROSTED_BLUR)
    var accentColorSource by mutableStateOf(AccentColorSource.ALBUM_ART)
    var themeStyle by mutableStateOf(WidgetThemeStyle.DARK_HYPEROS)
    var layoutMode by mutableStateOf(WidgetLayoutMode.AUTO_ADAPTIVE)

    // Silent Live Lyrics Notification Toggle
    var notificationLyricsEnabled by mutableStateOf(true)

    // Typography & Custom Font Controls
    var fontFamily by mutableStateOf(WidgetFontFamily.DEFAULT_SANS)
    var isLyricsBold by mutableStateOf(true)
    var isLyricsItalic by mutableStateOf(false)
    var customFontFileName by mutableStateOf("")

    var useAlbumArtAccent: Boolean
        get() = accentColorSource == AccentColorSource.ALBUM_ART
        set(value) {
            accentColorSource = if (value) AccentColorSource.ALBUM_ART else AccentColorSource.PHONE_SYSTEM
        }

    // HyperOS Blur & Glass Sliders (Active in HYPEROS_FROSTED_BLUR mode)
    var glassOpacity by mutableFloatStateOf(0.88f)
    var blurIntensity by mutableFloatStateOf(1.05f)
    var surfaceTintLevel by mutableFloatStateOf(0.18f)
    var rimBorderIntensity by mutableFloatStateOf(1.0f)
    var innerSheenIntensity by mutableFloatStateOf(1.0f)

    // Universal NKB, Wave & Typography Sliders (Active in BOTH HyperOS Blur & Material You modes)
    var nkbIntensity by mutableFloatStateOf(1.15f)
    var bouncePhysics by mutableFloatStateOf(1.05f)
    var waveAmplitude by mutableFloatStateOf(1.0f)
    var fontScale by mutableFloatStateOf(1.0f)
    var lyricsOffsetMs by mutableLongStateOf(0L)

    var themeMode: WidgetThemeStyle
        get() = themeStyle
        set(value) { themeStyle = value }

    var glassAlpha: Float
        get() = glassOpacity
        set(value) { glassOpacity = value }

    var useDynamicAccent: Boolean
        get() = useAlbumArtAccent
        set(value) { useAlbumArtAccent = value }

    private var loaded = false
    private var cachedCustomTypeface: Typeface? = null
    private var cachedCustomFontTimestamp: Long = -1L

    fun getCustomFontFile(context: Context): File = File(context.filesDir, "custom_widget_font.ttf")

    fun invalidateCustomFontCache() {
        cachedCustomTypeface = null
        cachedCustomFontTimestamp = -1L
    }

    fun resolveWidgetTypeface(context: Context?, forceBold: Boolean = isLyricsBold, forceItalic: Boolean = isLyricsItalic): Typeface {
        val styleInt = when {
            forceBold && forceItalic -> Typeface.BOLD_ITALIC
            forceBold -> Typeface.BOLD
            forceItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }

        if (fontFamily == WidgetFontFamily.CUSTOM_FILE && context != null) {
            val file = getCustomFontFile(context)
            if (file.exists() && file.length() > 0L) {
                val mod = file.lastModified()
                if (cachedCustomTypeface == null || cachedCustomFontTimestamp != mod) {
                    cachedCustomTypeface = runCatching { Typeface.createFromFile(file) }.getOrNull()
                    cachedCustomFontTimestamp = mod
                }
                val base = cachedCustomTypeface
                if (base != null) {
                    return Typeface.create(base, styleInt)
                }
            }
        }

        val sysName = fontFamily.systemFamilyName
        return if (sysName != null) {
            Typeface.create(sysName, styleInt)
        } else {
            Typeface.create(Typeface.DEFAULT, styleInt)
        }
    }

    fun isDarkEffective(context: Context): Boolean {
        return when (themeStyle) {
            WidgetThemeStyle.DARK_HYPEROS, WidgetThemeStyle.AMOLED_GLASS -> true
            WidgetThemeStyle.LIGHT_HYPEROS -> false
            WidgetThemeStyle.AUTO_SYSTEM -> {
                val nightFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightFlags == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    fun resolveEffectiveLayout(widthDp: Int, heightDp: Int, providerDefault: WidgetLayoutMode? = null): WidgetLayoutMode {
        if (layoutMode != WidgetLayoutMode.AUTO_ADAPTIVE) {
            return layoutMode
        }
        val aspect = widthDp.toFloat().coerceAtLeast(1f) / heightDp.toFloat().coerceAtLeast(1f)
        return when {
            heightDp <= 135 && aspect >= 1.75f -> WidgetLayoutMode.COMPACT_STRIP
            aspect >= 1.48f && heightDp in 136..295 -> WidgetLayoutMode.SPLIT_SIDE_BY_SIDE
            aspect <= 0.78f -> WidgetLayoutMode.VERTICAL_TOWER
            providerDefault != null && providerDefault != WidgetLayoutMode.AUTO_ADAPTIVE -> providerDefault
            else -> WidgetLayoutMode.CLASSIC_STACKED
        }
    }

    fun ensureLoaded(context: Context) {
        if (!loaded) {
            loaded = true
            load(context)
        }
    }

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("dynamic_lyrics_prefs", Context.MODE_PRIVATE)
        val engineName = prefs.getString("visual_engine_mode", VisualEngineMode.HYPEROS_FROSTED_BLUR.name)
            ?: VisualEngineMode.HYPEROS_FROSTED_BLUR.name
        visualEngineMode = runCatching { VisualEngineMode.valueOf(engineName) }
            .getOrDefault(VisualEngineMode.HYPEROS_FROSTED_BLUR)

        val accentSrcName = prefs.getString("accent_color_source", null)
        accentColorSource = if (accentSrcName != null) {
            runCatching { AccentColorSource.valueOf(accentSrcName) }.getOrDefault(AccentColorSource.ALBUM_ART)
        } else {
            if (prefs.getBoolean("use_album_accent", true)) AccentColorSource.ALBUM_ART else AccentColorSource.PHONE_SYSTEM
        }

        val styleName = prefs.getString("theme_style", WidgetThemeStyle.DARK_HYPEROS.name)
            ?: WidgetThemeStyle.DARK_HYPEROS.name
        themeStyle = runCatching { WidgetThemeStyle.valueOf(styleName) }
            .getOrDefault(WidgetThemeStyle.DARK_HYPEROS)

        val layoutName = prefs.getString("layout_mode", WidgetLayoutMode.AUTO_ADAPTIVE.name)
            ?: WidgetLayoutMode.AUTO_ADAPTIVE.name
        layoutMode = runCatching { WidgetLayoutMode.valueOf(layoutName) }
            .getOrDefault(WidgetLayoutMode.AUTO_ADAPTIVE)

        notificationLyricsEnabled = prefs.getBoolean("notification_lyrics_enabled", true)

        val fontFamName = prefs.getString("font_family", WidgetFontFamily.DEFAULT_SANS.name)
            ?: WidgetFontFamily.DEFAULT_SANS.name
        fontFamily = runCatching { WidgetFontFamily.valueOf(fontFamName) }
            .getOrDefault(WidgetFontFamily.DEFAULT_SANS)
        isLyricsBold = prefs.getBoolean("is_lyrics_bold", true)
        isLyricsItalic = prefs.getBoolean("is_lyrics_italic", false)
        customFontFileName = prefs.getString("custom_font_file_name", "") ?: ""

        glassOpacity = prefs.getFloat("glass_opacity", 0.88f).coerceIn(0.35f, 1.0f)
        blurIntensity = prefs.getFloat("blur_intensity", 1.05f).coerceIn(0.0f, 2.0f)
        surfaceTintLevel = prefs.getFloat("surface_tint_level", 0.18f).coerceIn(0.0f, 0.85f)
        rimBorderIntensity = prefs.getFloat("rim_border_intensity", 1.0f).coerceIn(0.0f, 2.0f)
        innerSheenIntensity = prefs.getFloat("inner_sheen_intensity", 1.0f).coerceIn(0.0f, 2.0f)
        nkbIntensity = prefs.getFloat("nkb_intensity", 1.15f).coerceIn(0.0f, 2.0f)
        bouncePhysics = prefs.getFloat("bounce_physics", 1.05f).coerceIn(0.0f, 2.0f)
        waveAmplitude = prefs.getFloat("wave_amplitude", 1.0f).coerceIn(0.0f, 2.0f)
        fontScale = prefs.getFloat("font_scale", 1.0f).coerceIn(0.75f, 1.40f)
        lyricsOffsetMs = prefs.getLong("lyrics_offset_ms", 0L).coerceIn(-3000L, 3000L)
    }

    fun save(context: Context) {
        context.getSharedPreferences("dynamic_lyrics_prefs", Context.MODE_PRIVATE).edit()
            .putString("visual_engine_mode", visualEngineMode.name)
            .putString("accent_color_source", accentColorSource.name)
            .putBoolean("use_album_accent", useAlbumArtAccent)
            .putString("theme_style", themeStyle.name)
            .putString("layout_mode", layoutMode.name)
            .putBoolean("notification_lyrics_enabled", notificationLyricsEnabled)
            .putString("font_family", fontFamily.name)
            .putBoolean("is_lyrics_bold", isLyricsBold)
            .putBoolean("is_lyrics_italic", isLyricsItalic)
            .putString("custom_font_file_name", customFontFileName)
            .putFloat("glass_opacity", glassOpacity)
            .putFloat("blur_intensity", blurIntensity)
            .putFloat("surface_tint_level", surfaceTintLevel)
            .putFloat("rim_border_intensity", rimBorderIntensity)
            .putFloat("inner_sheen_intensity", innerSheenIntensity)
            .putFloat("nkb_intensity", nkbIntensity)
            .putFloat("bounce_physics", bouncePhysics)
            .putFloat("wave_amplitude", waveAmplitude)
            .putFloat("font_scale", fontScale)
            .putLong("lyrics_offset_ms", lyricsOffsetMs)
            .apply()
    }

    fun resetToDefaults(context: Context) {
        visualEngineMode = VisualEngineMode.HYPEROS_FROSTED_BLUR
        accentColorSource = AccentColorSource.ALBUM_ART
        themeStyle = WidgetThemeStyle.DARK_HYPEROS
        layoutMode = WidgetLayoutMode.AUTO_ADAPTIVE
        notificationLyricsEnabled = true
        fontFamily = WidgetFontFamily.DEFAULT_SANS
        isLyricsBold = true
        isLyricsItalic = false
        glassOpacity = 0.88f
        blurIntensity = 1.05f
        surfaceTintLevel = 0.18f
        rimBorderIntensity = 1.0f
        innerSheenIntensity = 1.0f
        nkbIntensity = 1.15f
        bouncePhysics = 1.05f
        waveAmplitude = 1.0f
        fontScale = 1.0f
        lyricsOffsetMs = 0L
        save(context)
    }
}
