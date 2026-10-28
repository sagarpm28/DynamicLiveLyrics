package com.nikolink.dynamiclyrics.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader as AndroidShader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nikolink.dynamiclyrics.lyrics.NikoLiveLyricsEngine
import com.nikolink.dynamiclyrics.media.DynamicLyricsNotificationListenerService
import com.nikolink.dynamiclyrics.media.NikoMediaSessionStateHolder
import com.nikolink.dynamiclyrics.widget.AccentColorSource
import com.nikolink.dynamiclyrics.widget.DynamicLyricsAppWidgetProvider
import com.nikolink.dynamiclyrics.widget.DynamicLyricsCanvasRenderer
import com.nikolink.dynamiclyrics.widget.DynamicLyricsCompactWidgetProvider
import com.nikolink.dynamiclyrics.widget.DynamicLyricsNotificationRenderer
import com.nikolink.dynamiclyrics.widget.DynamicLyricsSplitWidgetProvider
import com.nikolink.dynamiclyrics.widget.DynamicLyricsTabletXlWidgetProvider
import com.nikolink.dynamiclyrics.widget.DynamicLyricsVerticalWidgetProvider
import com.nikolink.dynamiclyrics.widget.DynamicLyricsWidgetService
import com.nikolink.dynamiclyrics.widget.DynamicWidgetSettings
import com.nikolink.dynamiclyrics.widget.NkbWidgetAnimationTimeline
import com.nikolink.dynamiclyrics.widget.VisualEngineMode
import com.nikolink.dynamiclyrics.widget.WidgetFontFamily
import com.nikolink.dynamiclyrics.widget.WidgetLayoutMode
import com.nikolink.dynamiclyrics.widget.WidgetThemeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DynamicWidgetSettings.ensureLoaded(this)
        DynamicLyricsWidgetService.ensureRunning(this)

        setContent {
            val isDark = DynamicWidgetSettings.isDarkEffective(this@MainActivity)
            val isMaterialYou = DynamicWidgetSettings.visualEngineMode == VisualEngineMode.MATERIAL_YOU_MONET
            val usePhoneSystemColors = isMaterialYou || DynamicWidgetSettings.accentColorSource == AccentColorSource.PHONE_SYSTEM
            val colorScheme = resolveStudioColorScheme(this@MainActivity, isDark, usePhoneSystemColors)

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DynamicLyricsStudioRoot(context = this@MainActivity)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DynamicLyricsNotificationListenerService.requestRefresh(this)
        DynamicLyricsAppWidgetProvider.requestImmediateUpdate(this, forceFullLayout = true)
    }
}

@Composable
private fun resolveStudioColorScheme(
    context: Context,
    isDark: Boolean,
    usePhoneSystemColors: Boolean
): ColorScheme {
    if (usePhoneSystemColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    return if (isDark) {
        darkColorScheme(
            primary = Color(0xFFD8B888),
            onPrimary = Color(0xFF14120E),
            primaryContainer = Color(0xFF2B251C),
            secondary = Color(0xFF38BDF8),
            secondaryContainer = Color(0xFF1E293B),
            background = if (DynamicWidgetSettings.themeStyle == WidgetThemeStyle.AMOLED_GLASS) Color(0xFF030509) else Color(0xFF0B0E17),
            surface = Color(0xFF141926),
            surfaceVariant = Color(0xFF1B2234)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF8C6B3F),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFF5EBD9),
            secondary = Color(0xFF0284C7),
            secondaryContainer = Color(0xFFE2E8F0),
            background = Color(0xFFF6F5F2),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEDEAE2)
        )
    }
}

@Composable
fun DynamicLyricsStudioRoot(context: Context) {
    val scope = rememberCoroutineScope()
    val nowPlaying by NikoMediaSessionStateHolder.nowPlaying.collectAsState()
    val lyricsState by NikoLiveLyricsEngine.lyricsState.collectAsState()

    var hasNotificationAccess by remember {
        mutableStateOf(DynamicLyricsNotificationListenerService.isNotificationAccessGranted(context))
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        DynamicLyricsWidgetService.wakeUpNow(context)
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                runCatching { notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }

    val fullScreenNkbAnim = remember { Animatable(0f) }

    fun runWithFullScreenNkbTransition(mutation: () -> Unit) {
        scope.launch {
            fullScreenNkbAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 230, easing = FastOutSlowInEasing)
            )
            mutation()
            DynamicWidgetSettings.save(context)
            NkbWidgetAnimationTimeline.triggerStageNkbBloom()
            DynamicLyricsAppWidgetProvider.requestImmediateUpdate(context, forceFullLayout = true)
            delay(45L)
            fullScreenNkbAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
            )
        }
    }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val importedName = copyCustomFontFromUri(context, uri)
                if (importedName != null) {
                    DynamicWidgetSettings.customFontFileName = importedName
                    DynamicWidgetSettings.fontFamily = WidgetFontFamily.CUSTOM_FILE
                    DynamicWidgetSettings.invalidateCustomFontCache()
                    DynamicWidgetSettings.save(context)
                    NkbWidgetAnimationTimeline.triggerStageNkbBloom()
                    DynamicLyricsAppWidgetProvider.requestImmediateUpdate(context, forceFullLayout = true)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            hasNotificationAccess = DynamicLyricsNotificationListenerService.isNotificationAccessGranted(context)
            delay(1200L)
        }
    }

    val isDark = DynamicWidgetSettings.isDarkEffective(context)
    val isMaterialYou = DynamicWidgetSettings.visualEngineMode == VisualEngineMode.MATERIAL_YOU_MONET
    val scheme = MaterialTheme.colorScheme

    val phoneSystemAccentColor = remember(isDark) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val resId = if (isDark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
            runCatching { Color(context.getColor(resId)) }.getOrDefault(scheme.primary)
        } else {
            scheme.primary
        }
    }

    val targetAccent = if (DynamicWidgetSettings.accentColorSource == AccentColorSource.ALBUM_ART &&
        nowPlaying.dominantAccentArgb != null
    ) {
        Color(nowPlaying.dominantAccentArgb!! or (0xFF shl 24))
    } else {
        phoneSystemAccentColor
    }
    val accentColor by animateColorAsState(targetAccent, tween(380), label = "studioAccent")

    val nkbBlurProgress = fullScreenNkbAnim.value
    val isNkbTransitionActive = nkbBlurProgress > 0.005f
    val nkbModifier = if (isNkbTransitionActive) {
        Modifier.graphicsLayer {
            val r = (nkbBlurProgress * 34f * DynamicWidgetSettings.nkbIntensity.coerceAtLeast(0.4f)).coerceIn(0.5f, 48f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                renderEffect = AndroidRenderEffect.createBlurEffect(r, r, AndroidShader.TileMode.CLAMP)
                    .asComposeRenderEffect()
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(nkbModifier)
    ) {
        if (isMaterialYou) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scheme.background)
            )
        } else {
            val bgTop = if (isDark) Color(0xFF0B0F1C) else Color(0xFFF5F3EE)
            val bgBot = if (isDark) Color(0xFF060810) else Color(0xFFEAE6DC)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                bgTop,
                                accentColor.copy(alpha = if (isDark) 0.14f else 0.10f),
                                bgBot
                            )
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StudioHeaderBar(
                isDark = isDark,
                isMaterialYou = isMaterialYou,
                accentColor = accentColor,
                onSyncNow = {
                    NkbWidgetAnimationTimeline.triggerStageNkbBloom()
                    DynamicLyricsNotificationListenerService.requestRefresh(context)
                    DynamicLyricsAppWidgetProvider.requestImmediateUpdate(context, forceFullLayout = true)
                }
            )

            SetupAndWidgetPresetsCard(
                context = context,
                hasNotificationAccess = hasNotificationAccess,
                isDark = isDark,
                isMaterialYou = isMaterialYou,
                accentColor = accentColor
            )

            LiveWidgetRendererPreviewSection(
                context = context,
                nowPlaying = nowPlaying,
                lyricsState = lyricsState,
                isDark = isDark,
                accentColor = accentColor
            )

            NotificationLyricsCard(
                context = context,
                nowPlaying = nowPlaying,
                lyricsState = lyricsState,
                isDark = isDark,
                isMaterialYou = isMaterialYou,
                accentColor = accentColor,
                onRequestNotifPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            runCatching { notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                        }
                    }
                }
            )

            VisualEngineAndThemeCard(
                context = context,
                isDark = isDark,
                isMaterialYou = isMaterialYou,
                accentColor = accentColor,
                onSelectEngine = { newEngine ->
                    if (DynamicWidgetSettings.visualEngineMode != newEngine) {
                        runWithFullScreenNkbTransition {
                            DynamicWidgetSettings.visualEngineMode = newEngine
                        }
                    }
                },
                onSelectTheme = { newTheme ->
                    if (DynamicWidgetSettings.themeStyle != newTheme) {
                        runWithFullScreenNkbTransition {
                            DynamicWidgetSettings.themeStyle = newTheme
                        }
                    }
                }
            )

            TypographyAndCustomFontCard(
                context = context,
                isDark = isDark,
                isMaterialYou = isMaterialYou,
                accentColor = accentColor,
                onPickCustomFont = {
                    try {
                        fontPickerLauncher.launch(arrayOf("*/*"))
                    } catch (_: Throwable) {}
                }
            )

            WidgetLayoutArrangementCard(
                context = context,
                isDark = isDark,
                isMaterialYou = isMaterialYou,
                accentColor = accentColor
            )

            VisualPhysicsAndNkbSlidersCard(
                context = context,
                isDark = isDark,
                isMaterialYou = isMaterialYou,
                accentColor = accentColor
            )

            LyricsProvidersCard(
                context = context,
                isDark = isDark,
                isMaterialYou = isMaterialYou,
                accentColor = accentColor
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        if (isNkbTransitionActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        accentColor.copy(alpha = (nkbBlurProgress * 0.22f).coerceIn(0f, 0.35f))
                    )
            )
        }
    }
}

private suspend fun copyCustomFontFromUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
        var displayName = "CustomFont.ttf"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0 && cursor.moveToFirst()) {
                displayName = cursor.getString(nameIdx) ?: displayName
            }
        }
        val destFile = DynamicWidgetSettings.getCustomFontFile(context)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        if (destFile.exists() && destFile.length() > 64L) displayName else null
    } catch (_: Throwable) {
        null
    }
}

@Composable
private fun StudioCardContainer(
    isDark: Boolean,
    isMaterialYou: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    if (isMaterialYou) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = scheme.surfaceVariant.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.26f)),
            tonalElevation = 4.dp
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    } else {
        val cardBg = if (isDark) {
            Color(0xFF131826).copy(alpha = DynamicWidgetSettings.glassOpacity.coerceIn(0.68f, 0.95f))
        } else {
            Color(0xFFFFFFFF).copy(alpha = DynamicWidgetSettings.glassOpacity.coerceIn(0.78f, 0.96f))
        }
        val borderTop = if (isDark) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.85f)
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            cardBg,
                            accentColor.copy(alpha = if (isDark) 0.14f else 0.10f)
                        )
                    )
                )
                .border(
                    1.1.dp,
                    Brush.linearGradient(listOf(borderTop, accentColor.copy(alpha = 0.35f))),
                    shape
                )
                .padding(16.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun NkbBounceActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color,
    contentColor: Color,
    content: @Composable () -> Unit
) {
    var tapCount by remember { mutableIntStateOf(0) }
    val anim = remember { Animatable(1f) }

    LaunchedEffect(tapCount) {
        if (tapCount > 0) {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(380, easing = LinearEasing))
        }
    }

    val t = anim.value
    val isAnimating = t < 0.995f
    val scale = if (isAnimating) {
        NikoFastGaussianBlur.computeNkbBounceScale(t, DynamicWidgetSettings.bouncePhysics)
    } else 1f
    val bloom = if (isAnimating) {
        NikoFastGaussianBlur.computeNkbBloomFactor(t, DynamicWidgetSettings.nkbIntensity)
    } else 0f

    val animMod = if (isAnimating) {
        Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && bloom > 0.03f) {
                val r = (bloom * 12f).coerceIn(0.5f, 18f)
                renderEffect = AndroidRenderEffect.createBlurEffect(r, r, AndroidShader.TileMode.CLAMP)
                    .asComposeRenderEffect()
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(animMod)
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                tapCount++
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun StudioHeaderBar(
    isDark: Boolean,
    isMaterialYou: Boolean,
    accentColor: Color,
    onSyncNow: () -> Unit
) {
    val textPrimary = if (isDark) Color.White else Color(0xFF14120E)
    val textSecondary = textPrimary.copy(alpha = 0.68f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = "Dynamic Lyrics Widget",
                color = textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isMaterialYou) {
                    "v1.0.0 • Material You • 30 FPS Live Lyrics"
                } else {
                    "v1.0.0 • HyperOS Blur • 30 FPS Live Lyrics"
                },
                color = textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        NkbBounceActionButton(
            onClick = onSyncNow,
            containerColor = accentColor.copy(alpha = 0.22f),
            contentColor = accentColor
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Refresh Widget",
                tint = accentColor,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = "Refresh",
                color = textPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupAndWidgetPresetsCard(
    context: Context,
    hasNotificationAccess: Boolean,
    isDark: Boolean,
    isMaterialYou: Boolean,
    accentColor: Color
) {
    val textPrimary = if (isDark) Color.White else Color(0xFF14120E)
    val textSecondary = textPrimary.copy(alpha = 0.70f)

    StudioCardContainer(isDark = isDark, isMaterialYou = isMaterialYou, accentColor = accentColor) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (hasNotificationAccess) Color(0xFF10B981).copy(alpha = 0.20f)
                            else Color(0xFFF59E0B).copy(alpha = 0.20f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (hasNotificationAccess) Icons.Filled.CheckCircle else Icons.Filled.NotificationsActive,
                        contentDescription = null,
                        tint = if (hasNotificationAccess) Color(0xFF10B981) else Color(0xFFF59E0B),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (hasNotificationAccess) "Music Access Connected"
                        else "Allow Notification Access",
                        color = textPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (hasNotificationAccess)
                            "Connected to your music player (30 FPS live lyrics & NKB buttons active)."
                        else
                            "Turn on Notification Access so the widget can see what song is playing and control your music.",
                        color = textSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            if (!hasNotificationAccess) {
                NkbBounceActionButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        } catch (_: Throwable) {}
                    },
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = Color(0xFFF59E0B),
                    contentColor = Color(0xFF090D16)
                ) {
                    Icon(Icons.Filled.NotificationsActive, null, tint = Color(0xFF090D16), modifier = Modifier.size(18.dp))
                    Text("Allow Notification Access", color = Color(0xFF090D16), fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp)
                }
            }

            Text(
                text = "Quick Add Widget to Home Screen:",
                color = textSecondary,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val presets = listOf(
                    "Classic (4×3)" to DynamicLyricsAppWidgetProvider::class.java,
                    "Split Left/Right (4×2)" to DynamicLyricsSplitWidgetProvider::class.java,
                    "Vertical Tall (2×4)" to DynamicLyricsVerticalWidgetProvider::class.java,
                    "Compact Bar (4×1)" to DynamicLyricsCompactWidgetProvider::class.java,
                    "Tablet XL (6×4)" to DynamicLyricsTabletXlWidgetProvider::class.java
                )
                presets.forEach { (label, providerCls) ->
                    NkbBounceActionButton(
                        onClick = { requestPinSpecificWidget(context, providerCls) },
                        containerColor = accentColor.copy(alpha = 0.18f),
                        contentColor = textPrimary
                    ) {
                        Icon(Icons.Filled.AddToHomeScreen, null, tint = accentColor, modifier = Modifier.size(15.dp))
                        Text(label, color = textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationLyricsCard(
    context: Context,
    nowPlaying: com.nikolink.dynamiclyrics.media.NowPlayingMediaState,
    lyricsState: com.nikolink.dynamiclyrics.lyrics.LiveLyricsResult,
    isDark: Boolean,
    isMaterialYou: Boolean,
    accentColor: Color,
    onRequestNotifPermission: () -> Unit
) {
    val textPrimary = if (isDark) Color.White else Color(0xFF14120E)
    val textSecondary = textPrimary.copy(alpha = 0.68f)
    val enabled = DynamicWidgetSettings.notificationLyricsEnabled

    var notifPreviewTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(
        enabled,
        nowPlaying.isPlaying,
        DynamicWidgetSettings.fontFamily,
        DynamicWidgetSettings.isLyricsBold,
        DynamicWidgetSettings.isLyricsItalic,
        DynamicWidgetSettings.customFontFileName,
        DynamicWidgetSettings.fontScale
    ) {
        while (enabled) {
            notifPreviewTick = SystemClock.elapsedRealtime()
            delay(33L)
        }
    }

    StudioCardContainer(isDark = isDark, isMaterialYou = isMaterialYou, accentColor = accentColor) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (enabled) Icons.Filled.Notifications else Icons.Filled.NotificationsOff,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Live Lyrics in Notification",
                            color = textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Silent compact 2-line notification with blurred top/bottom edges so you can read lyrics while using other apps (never pops up over screen).",
                            color = textSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (enabled) accentColor else textPrimary.copy(alpha = 0.10f))
                        .clickable {
                            val next = !enabled
                            DynamicWidgetSettings.notificationLyricsEnabled = next
                            DynamicWidgetSettings.save(context)
                            if (next) {
                                onRequestNotifPermission()
                            }
                            DynamicLyricsWidgetService.wakeUpNow(context)
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (enabled) "ON" else "OFF",
                        color = if (enabled) Color(0xFF080D18) else textPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            if (enabled) {
                val notifBitmap = remember(
                    notifPreviewTick,
                    nowPlaying,
                    lyricsState,
                    DynamicWidgetSettings.accentColorSource,
                    DynamicWidgetSettings.fontFamily,
                    DynamicWidgetSettings.isLyricsBold,
                    DynamicWidgetSettings.isLyricsItalic,
                    DynamicWidgetSettings.customFontFileName,
                    DynamicWidgetSettings.fontScale
                ) {
                    val posMs = DynamicLyricsNotificationListenerService.currentInterpolatedPositionMs()
                    DynamicLyricsNotificationRenderer.renderNotificationBitmap(
                        context = context,
                        nowPlaying = nowPlaying,
                        lyrics = lyricsState,
                        positionMs = posMs
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(textPrimary.copy(alpha = 0.04f)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = notifBitmap.asImageBitmap(),
                        contentDescription = "Notification Lyrics Preview",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveWidgetRendererPreviewSection(
    context: Context,
    nowPlaying: com.nikolink.dynamiclyrics.media.NowPlayingMediaState,
    lyricsState: com.nikolink.dynamiclyrics.lyrics.LiveLyricsResult,
    isDark: Boolean,
    accentColor: Color
) {
    val textPrimary = if (isDark) Color.White else Color(0xFF14120E)
    var frameTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(
        nowPlaying.isPlaying,
        DynamicWidgetSettings.visualEngineMode,
        DynamicWidgetSettings.accentColorSource,
        DynamicWidgetSettings.themeStyle,
        DynamicWidgetSettings.layoutMode,
        DynamicWidgetSettings.fontFamily,
        DynamicWidgetSettings.isLyricsBold,
        DynamicWidgetSettings.isLyricsItalic,
        DynamicWidgetSettings.customFontFileName,
        DynamicWidgetSettings.waveAmplitude,
        DynamicWidgetSettings.blurIntensity,
        DynamicWidgetSettings.glassOpacity,
        DynamicWidgetSettings.fontScale
    ) {
        while (true) {
            frameTick = SystemClock.elapsedRealtime()
            delay(DynamicWidgetSettings.FRAME_INTERVAL_MS)
        }
    }

    val previewLayout = when (DynamicWidgetSettings.layoutMode) {
        WidgetLayoutMode.AUTO_ADAPTIVE -> WidgetLayoutMode.CLASSIC_STACKED
        else -> DynamicWidgetSettings.layoutMode
    }

    val (previewW, previewH, previewAspect) = when (previewLayout) {
        WidgetLayoutMode.SPLIT_SIDE_BY_SIDE -> Triple(760, 380, 760f / 380f)
        WidgetLayoutMode.VERTICAL_TOWER -> Triple(420, 680, 420f / 680f)
        WidgetLayoutMode.COMPACT_STRIP -> Triple(760, 250, 760f / 250f)
        else -> Triple(640, 520, 640f / 520f)
    }

    val renderedBitmap = remember(
        frameTick,
        nowPlaying,
        lyricsState,
        previewLayout,
        DynamicWidgetSettings.visualEngineMode,
        DynamicWidgetSettings.accentColorSource,
        DynamicWidgetSettings.themeStyle,
        DynamicWidgetSettings.fontFamily,
        DynamicWidgetSettings.isLyricsBold,
        DynamicWidgetSettings.isLyricsItalic,
        DynamicWidgetSettings.customFontFileName,
        DynamicWidgetSettings.glassOpacity,
        DynamicWidgetSettings.blurIntensity,
        DynamicWidgetSettings.surfaceTintLevel,
        DynamicWidgetSettings.fontScale
    ) {
        val posMs = DynamicLyricsNotificationListenerService.currentInterpolatedPositionMs()
        DynamicLyricsCanvasRenderer.renderWidgetBitmap(
            context = context,
            state = nowPlaying,
            lyrics = lyricsState,
            isLoadingLyrics = lyricsState.isLoading,
            preferredProvider = NikoLiveLyricsEngine.preferredProvider,
            currentPosMs = posMs,
            widthPx = previewW,
            heightPx = previewH,
            layoutOverride = previewLayout
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "LIVE PREVIEW (${previewLayout.label.uppercase()} • 30 FPS)",
                color = textPrimary.copy(alpha = 0.68f),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Tap buttons to test NKB",
                color = accentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(previewAspect.coerceIn(0.60f, 3.0f)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = renderedBitmap.asImageBitmap(),
                contentDescription = "Live Widget Preview",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(previewLayout) {
                        detectTapGestures { offset ->
                            val nx = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val ny = (offset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            handlePreviewTap6Buttons(context, previewLayout, nx, ny)
                        }
                    }
            )
        }
    }
}

private fun handlePreviewTap6Buttons(
    context: Context,
    layout: WidgetLayoutMode,
    nx: Float,
    ny: Float
) {
    val state = DynamicLyricsNotificationListenerService.mediaState
    when (layout) {
        WidgetLayoutMode.SPLIT_SIDE_BY_SIDE -> {
            if (nx <= 0.42f) {
                if (ny >= 0.74f) {
                    val btnIdx = ((nx / 0.42f) * 6f).toInt().coerceIn(0, 5)
                    triggerTransportActionByIndex6(context, btnIdx)
                }
            } else if (ny <= 0.15f) {
                NkbWidgetAnimationTimeline.triggerButtonNkb(NkbWidgetAnimationTimeline.WidgetButtonId.PROVIDER)
                NkbWidgetAnimationTimeline.triggerStageNkbBloom()
                NikoLiveLyricsEngine.cycleProvider(state.title, state.artist, state.album, state.durationMs)
            }
        }
        WidgetLayoutMode.COMPACT_STRIP -> {
            if (ny <= 0.32f && nx >= 0.44f) {
                val btnIdx = (((nx - 0.44f) / 0.56f) * 6f).toInt().coerceIn(0, 5)
                triggerTransportActionByIndex6(context, btnIdx)
            }
        }
        else -> {
            if (ny <= 0.09f) {
                NkbWidgetAnimationTimeline.triggerButtonNkb(NkbWidgetAnimationTimeline.WidgetButtonId.PROVIDER)
                NkbWidgetAnimationTimeline.triggerStageNkbBloom()
                NikoLiveLyricsEngine.cycleProvider(state.title, state.artist, state.album, state.durationMs)
            } else if (ny >= 0.84f) {
                val btnIdx = (nx * 6f).toInt().coerceIn(0, 5)
                triggerTransportActionByIndex6(context, btnIdx)
            }
        }
    }
    DynamicLyricsWidgetService.wakeUpNow(context)
}

private fun triggerTransportActionByIndex6(context: Context, index: Int) {
    val cur = DynamicLyricsNotificationListenerService.currentInterpolatedPositionMs()
    val state = DynamicLyricsNotificationListenerService.mediaState
    val maxDur = if (state.durationMs > 0L) state.durationMs else Long.MAX_VALUE
    when (index) {
        0 -> {
            NkbWidgetAnimationTimeline.triggerButtonNkb(NkbWidgetAnimationTimeline.WidgetButtonId.PREV)
            NkbWidgetAnimationTimeline.triggerStageNkbBloom()
            DynamicLyricsNotificationListenerService.skipToPrevious(context)
        }
        1 -> {
            NkbWidgetAnimationTimeline.triggerButtonNkb(NkbWidgetAnimationTimeline.WidgetButtonId.SEEK_BACK)
            DynamicLyricsNotificationListenerService.seekTo((cur - 5_000L).coerceAtLeast(0L))
        }
        2 -> {
            NkbWidgetAnimationTimeline.triggerButtonNkb(NkbWidgetAnimationTimeline.WidgetButtonId.PLAY_PAUSE)
            DynamicLyricsNotificationListenerService.togglePlayPause(context)
        }
        3 -> {
            NkbWidgetAnimationTimeline.triggerButtonNkb(NkbWidgetAnimationTimeline.WidgetButtonId.SEEK_FWD)
            DynamicLyricsNotificationListenerService.seekTo((cur + 5_000L).coerceAtMost(maxDur))
        }
        4 -> {
            NkbWidgetAnimationTimeline.triggerButtonNkb(NkbWidgetAnimationTimeline.WidgetButtonId.NEXT)
            NkbWidgetAnimationTimeline.triggerStageNkbBloom()
            DynamicLyricsNotificationListenerService.skipToNext(context)
        }
        5 -> {
            NkbWidgetAnimationTimeline.triggerButtonNkb(NkbWidgetAnimationTimeline.WidgetButtonId.LIKE)
            DynamicLyricsNotificationListenerService.toggleLikeCurrentTrack(context)
        }
    }
}

@Composable
private fun VisualEngineAndThemeCard(
    context: Context,
    isDark: Boolean,
    isMaterialYou: Boolean,
    accentColor: Color,
    onSelectEngine: (VisualEngineMode) -> Unit,
    onSelectTheme: (WidgetThemeStyle) -> Unit
) {
    val textPrimary = if (isDark) Color.White else Color(0xFF14120E)
    val textSecondary = textPrimary.copy(alpha = 0.68f)

    StudioCardContainer(isDark = isDark, isMaterialYou = isMaterialYou, accentColor = accentColor) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isMaterialYou) Icons.Filled.Palette else Icons.Filled.BlurOn,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Widget Look & Colors",
                    color = textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VisualEngineMode.entries.forEach { mode ->
                    val selected = DynamicWidgetSettings.visualEngineMode == mode
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (selected) accentColor.copy(alpha = 0.22f)
                                else textPrimary.copy(alpha = 0.06f)
                            )
                            .border(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) accentColor else textPrimary.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(18.dp)
                            )
                            .clickable { onSelectEngine(mode) }
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = mode.label,
                            color = if (selected) accentColor else textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = mode.subtitle,
                            color = textSecondary,
                            fontSize = 11.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Text(
                text = "Accent Color:",
                color = textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AccentColorSource.entries.forEach { src ->
                    val selected = DynamicWidgetSettings.accentColorSource == src
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (selected) accentColor.copy(alpha = 0.20f)
                                else textPrimary.copy(alpha = 0.06f)
                            )
                            .border(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) accentColor else textPrimary.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                DynamicWidgetSettings.accentColorSource = src
                                DynamicWidgetSettings.save(context)
                                NkbWidgetAnimationTimeline.triggerStageNkbBloom()
                                DynamicLyricsAppWidgetProvider.requestImmediateUpdate(context)
                            }
                            .padding(11.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = src.label,
                            color = if (selected) accentColor else textPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = src.subtitle,
                            color = textSecondary,
                            fontSize = 10.5.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Text(
                text = "Theme Mode:",
                color = textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WidgetThemeStyle.entries.forEach { style ->
                    val selected = DynamicWidgetSettings.themeStyle == style
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) accentColor else textPrimary.copy(alpha = 0.08f)
                            )
                            .clickable { onSelectTheme(style) }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = style.label,
                            color = if (selected) Color(0xFF080D18) else textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypographyAndCustomFontCard(
    context: Context,
    isDark: Boolean,
    isMaterialYou: Boolean,
    accentColor: Color,
    onPickCustomFont: () -> Unit
) {
    val textPrimary = if (isDark) Color.White else Color(0xFF14120E)
    val textSecondary = textPrimary.copy(alpha = 0.68f)

    StudioCardContainer(isDark = isDark, isMaterialYou = isMaterialYou, accentColor = accentColor) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.FontDownload, null, tint = accentColor, modifier = Modifier.size(20.dp))
                Text(
                    text = "Text Style & Custom Font",
                    color = textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val isBold = DynamicWidgetSettings.isLyricsBold
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isBold) accentColor.copy(alpha = 0.22f) else textPrimary.copy(alpha = 0.06f))
                        .border(1.dp, if (isBold) accentColor else textPrimary.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                        .clickable {
                            DynamicWidgetSettings.isLyricsBold = !isBold
                            DynamicWidgetSettings.save(context)
                            DynamicLyricsAppWidgetProvider.requestImmediateUpdate(context)
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isBold) "B  Bold Text: ON" else "B  Bold Text: OFF",
                        color = if (isBold) accentColor else textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                val isItalic = DynamicWidgetSettings.isLyricsItalic
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isItalic) accentColor.copy(alpha = 0.22f) else textPrimary.copy(alpha = 0.06f))
                        .border(1.dp, if (isItalic) accentColor else textPrimary.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                        .clickable {
                            DynamicWidgetSettings.isLyricsItalic = !isItalic
                            DynamicWidgetSettings.save(context)
                            DynamicLyricsAppWidgetProvider.requestImmediateUpdate(context)
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isItalic) "I  Italic: ON" else "I  Italic: OFF",
                        color = if (isItalic) accentColor else textPrimary,
                        fontSize = 13.sp,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = "Font Style (Applies to both Widget & Notification Lyrics):",
                color = textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WidgetFontFamily.entries.forEach { fam ->
                    val selected = DynamicWidgetSettings.fontFamily == fam
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) accentColor else textPrimary.copy(alpha = 0.08f))
                            .clickable {
                                if (fam == WidgetFontFamily.CUSTOM_FILE &&
                                    !DynamicWidgetSettings.getCustomFontFile(context).exists()
                                ) {
                                    onPickCustomFont()
                                } else {
                                    DynamicWidgetSettings.fontFamily = fam
                                    DynamicWidgetSettings.save(context)
                                    NkbWidgetAnimationTimeline.triggerStageNkbBloom()
                                    DynamicLyricsAppWidgetProvider.requestImmediateUpdate(context)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = fam.label,
                            color = if (selected) Color(0xFF080D18) else textPrimary,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                NkbBounceActionButton(
                    onClick = onPickCustomFont,
                    modifier = Modifier.weight(1f),
                    containerColor = accentColor.copy(alpha = 0.18f),
                    contentColor = textPrimary
                ) {
                    Icon(Icons.Filled.UploadFile, null, tint = accentColor, modifier = Modifier.size(17.dp))
                    Text(
                        text = if (DynamicWidgetSettings.customFontFileName.isNotBlank()) {
                            "Loaded Font: ${DynamicWidgetSettings.customFontFileName} (Tap to Change)"
                        } else {
                            "Pick Your Own Font File (.ttf / .otf)"
                        },
                        color = textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetLayoutArrangementCard(
    context: Context,
    isDark: Boolean,
    isMaterialYou: Boolean,
    accentColor: Color
) {
    val textPrimary = if (isDark) Color.White else Color(0xFF14120E)
    val textSecondary = textPrimary.copy(alpha = 0.68f)

    StudioCardContainer(isDark = isDark, isMaterialYou = isMaterialYou, accentColor = accentColor) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ViewColumn,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Widget Layout Style",
                    color = textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            WidgetLayoutMode.entries.forEach { mode ->
                val selected = DynamicWidgetSettings.layoutMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (selected) accentColor.copy(alpha = 0.18f)
                            else textPrimary.copy(alpha = 0.05f)
                        )
                        .border(
                            1.dp,
                            if (selected) accentColor else textPrimary.copy(alpha = 0.10f),
                            RoundedCornerShape(16.dp)
                        )
                        .clickable {
                            DynamicWidgetSettings.layoutMode = mode
                            DynamicWidgetSettings.save(context)
                            NkbWidgetAnimationTimeline.triggerStageNkbBloom()
                            DynamicLyricsAppWidgetProvider.requestImmediateUpdate(context, forceFullLayout = true)
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = mode.label,
                            color = if (selected) accentColor else textPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = mode.description,
                            color = textSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VisualPhysicsAndNkbSlidersCard(
    context: Context,
    isDark: Boolean,
    isMaterialYou: Boolean,
    accentColor: Color
) {
    val textPrimary = if (isDark) Color.White else Color(0xFF14120E)
    val textSecondary = textPrimary.copy(alpha = 0.68f)

    StudioCardContainer(isDark = isDark, isMaterialYou = isMaterialYou, accentColor = accentColor) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Tune, null, tint = accentColor, modifier = Modifier.size(20.dp))
                    Text(
                        text = if (isMaterialYou) "NKB, Waves & Text Size"
                        else "Blur, NKB & Wave Settings",
                        color = textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(textPrimary.copy(alpha = 0.08f))
                        .clickable {
                            DynamicWidgetSettings.resetToDefaults(context)
                            NkbWidgetAnimationTimeline.triggerStageNkbBloom()
                            DynamicLyricsAppWidgetProvider.requestImmediateUpdate(context, forceFullLayout = true)
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Filled.RestartAlt, null, tint = textPrimary, modifier = Modifier.size(14.dp))
                        Text("Reset", color = textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (!isMaterialYou) {
                LabeledSliderRow(
                    title = "Background Blur",
                    subtitle = "How blurry the album art looks behind the widget",
                    valueText = "${(DynamicWidgetSettings.blurIntensity * 100).roundToInt()}%",
                    value = DynamicWidgetSettings.blurIntensity,
                    range = 0.0f..2.0f,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    accentColor = accentColor,
                    onValueChange = {
                        DynamicWidgetSettings.blurIntensity = it
                        DynamicWidgetSettings.save(context)
                        DynamicLyricsAppWidgetProvider.requestImmediateUpdate(context)
                    }
                )

                LabeledSliderRow(
                    title = "Glass Opacity",
                    subtitle = "Make the widget background more see-through or more solid",
                    valueText = "${(DynamicWidgetSettings.glassOpacity * 100).roundToInt()}%",
                    value = DynamicWidgetSettings.glassOpacity,
                    range = 0.40f..0.98f,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    accentColor = accentColor,
                    onValueChange = {
                        DynamicWidgetSettings.glassOpacity = it
                        DynamicWidgetSettings.save(context)
                        DynamicLyricsAppWidgetProvider.requestImmediateUpdate(context)
                    }
                )

                LabeledSliderRow(
                    title = "Glass Border Glow",
                    subtitle = "Brightness of the glass border and top shine",
                    valueText = "${(DynamicWidgetSettings.rimBorderIntensity * 100).roundToInt()}%",
                    value = DynamicWidgetSettings.rimBorderIntensity,
                    range = 0.0f..2.0f,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    accentColor = accentColor,
                    onValueChange = {
                        DynamicWidgetSettings.rimBorderIntensity = it
                        DynamicWidgetSettings.innerSheenIntensity = it
                        DynamicWidgetSettings.save(context)
                        DynamicLyricsAppWidgetProvider.requestImmediateUpdate(context)
                    }
                )
            }

            LabeledSliderRow(
                title = "Background Color Tint",
                subtitle = "How much accent color mixes into the widget card",
                valueText = "${(DynamicWidgetSettings.surfaceTintLevel * 100).roundToInt()}%",
                value = DynamicWidgetSettings.surfaceTintLevel,
                range = 0.0f..0.75f,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                accentColor = accentColor,
                onValueChange = {
                    DynamicWidgetSettings.surfaceTintLevel = it
                    DynamicWidgetSettings.save(context)
                    DynamicLyricsAppWidgetProvider.requestImmediateUpdate(context)
                }
            )

            LabeledSliderRow(
                title = "NKB Blur Effect",
                subtitle = "Strength of the NKB blur flash when you tap buttons or change songs",
                valueText = "${(DynamicWidgetSettings.nkbIntensity * 100).roundToInt()}%",
                value = DynamicWidgetSettings.nkbIntensity,
                range = 0.0f..2.0f,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                accentColor = accentColor,
                onValueChange = {
                    DynamicWidgetSettings.nkbIntensity = it
                    DynamicWidgetSettings.save(context)
                }
            )

            LabeledSliderRow(
                title = "Button Bounce",
                subtitle = "How much the navigation buttons bounce when tapped",
                valueText = "${(DynamicWidgetSettings.bouncePhysics * 100).roundToInt()}%",
                value = DynamicWidgetSettings.bouncePhysics,
                range = 0.0f..2.0f,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                accentColor = accentColor,
                onValueChange = {
                    DynamicWidgetSettings.bouncePhysics = it
                    DynamicWidgetSettings.save(context)
                }
            )

            LabeledSliderRow(
                title = "Wave Height",
                subtitle = "Height of the waves on the progress bar and album ring",
                valueText = "${(DynamicWidgetSettings.waveAmplitude * 100).roundToInt()}%",
                value = DynamicWidgetSettings.waveAmplitude,
                range = 0.0f..2.0f,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                accentColor = accentColor,
                onValueChange = {
                    DynamicWidgetSettings.waveAmplitude = it
                    DynamicWidgetSettings.save(context)
                    DynamicLyricsAppWidgetProvider.requestImmediateUpdate(context)
                }
            )

            LabeledSliderRow(
                title = "Lyrics Text Size",
                subtitle = "Make the lyrics text bigger or smaller inside the widget and notification",
                valueText = "${(DynamicWidgetSettings.fontScale * 100).roundToInt()}%",
                value = DynamicWidgetSettings.fontScale,
                range = 0.75f..1.35f,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                accentColor = accentColor,
                onValueChange = {
                    DynamicWidgetSettings.fontScale = it
                    DynamicWidgetSettings.save(context)
                    DynamicLyricsAppWidgetProvider.requestImmediateUpdate(context)
                }
            )

            LabeledSliderRow(
                title = "Lyrics Timing Offset",
                subtitle = "Fix lyrics if they show up a little too early (-) or too late (+)",
                valueText = "${if (DynamicWidgetSettings.lyricsOffsetMs >= 0) "+" else ""}${DynamicWidgetSettings.lyricsOffsetMs} ms",
                value = DynamicWidgetSettings.lyricsOffsetMs.toFloat(),
                range = -2000f..2000f,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                accentColor = accentColor,
                onValueChange = {
                    DynamicWidgetSettings.lyricsOffsetMs = ((it / 50f).roundToInt() * 50).toLong()
                    DynamicWidgetSettings.save(context)
                    DynamicLyricsAppWidgetProvider.requestImmediateUpdate(context)
                }
            )
        }
    }
}

@Composable
private fun LabeledSliderRow(
    title: String,
    subtitle: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    textPrimary: Color,
    textSecondary: Color,
    accentColor: Color,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = valueText,
                color = accentColor,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }
        Text(subtitle, color = textSecondary, fontSize = 11.sp)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = textPrimary,
                activeTrackColor = accentColor,
                inactiveTrackColor = textPrimary.copy(alpha = 0.18f)
            )
        )
    }
}

@Composable
private fun LyricsProvidersCard(
    context: Context,
    isDark: Boolean,
    isMaterialYou: Boolean,
    accentColor: Color
) {
    val textPrimary = if (isDark) Color.White else Color(0xFF14120E)
    val activeProvider = NikoLiveLyricsEngine.preferredProvider
    val mediaState = DynamicLyricsNotificationListenerService.mediaState
    val currentLyrics = NikoLiveLyricsEngine.currentLyrics

    StudioCardContainer(isDark = isDark, isMaterialYou = isMaterialYou, accentColor = accentColor) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = accentColor, modifier = Modifier.size(18.dp))
                    Text(
                        text = "Lyrics Source",
                        color = textPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = currentLyrics?.provider?.displayName ?: "Ready",
                    color = accentColor,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NikoLiveLyricsEngine.LyricsProviderOption.entries.forEach { option ->
                    val selected = option == activeProvider
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (selected) accentColor else textPrimary.copy(alpha = 0.08f)
                            )
                            .clickable {
                                NkbWidgetAnimationTimeline.triggerStageNkbBloom()
                                NikoLiveLyricsEngine.selectProvider(
                                    provider = option,
                                    title = mediaState.title,
                                    artist = mediaState.artist,
                                    album = mediaState.album,
                                    durationMs = mediaState.durationMs
                                )
                                DynamicLyricsAppWidgetProvider.requestImmediateUpdate(context)
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = option.label,
                            color = if (selected) Color(0xFF080D18) else textPrimary,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

private fun requestPinSpecificWidget(context: Context, providerCls: Class<*>) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = AppWidgetManager.getInstance(context)
            val provider = ComponentName(context, providerCls)
            if (mgr.isRequestPinAppWidgetSupported) {
                mgr.requestPinAppWidget(provider, null, null)
            }
        }
    } catch (_: Throwable) {}
}
