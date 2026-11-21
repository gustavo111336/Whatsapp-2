package com.example.ui.components

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.SubcomposeAsyncImage
import com.example.data.remote.FreeMediaStorageManager
import com.example.ui.theme.Zap2ExpressiveShapes
import com.example.ui.theme.ZapOnlineGreen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun isVideoMediaUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.lowercase()
    if (lower.startsWith("data:video/")) return true
    val extParam = runCatching { Uri.parse(lower).getQueryParameter("ext") }.getOrNull()
    if (extParam in setOf("mp4", "webm", "m4v", "mov")) return true
    val cleanPath = runCatching { Uri.parse(lower).path ?: lower }.getOrDefault(lower)
    return cleanPath.endsWith(".mp4") ||
        cleanPath.endsWith(".webm") ||
        cleanPath.endsWith(".m4v") ||
        cleanPath.endsWith(".mov") ||
        lower.contains(".mp4?") ||
        lower.contains(".webm?") ||
        lower.contains("video%2f") ||
        lower.contains("avatar_video") ||
        lower.contains("banner_video")
}

@Composable
fun Zap2ProfileBanner(
    bannerUrl: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    overlayContent: @Composable BoxScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val cleanUrl = bannerUrl?.trim().orEmpty()
    val needsManaged = FreeMediaStorageManager.requiresManagedResolution(cleanUrl)
    val resolvedModel by produceState<Any?>(
        initialValue = if (needsManaged) null else cleanUrl.takeIf { it.isNotEmpty() },
        key1 = cleanUrl
    ) {
        value = if (cleanUrl.isEmpty()) {
            null
        } else if (needsManaged) {
            FreeMediaStorageManager.resolveMediaModel(context, cleanUrl)
        } else {
            cleanUrl
        }
    }

    var videoPlaybackFailed by remember(cleanUrl) { mutableStateOf(false) }

    val fallbackGradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer
        )
    )

    val boxModifier = if (onClick != null) {
        modifier
            .background(fallbackGradient)
            .clickable(onClick = onClick)
    } else {
        modifier.background(fallbackGradient)
    }

    Box(modifier = boxModifier) {
        val activeModel = resolvedModel
        val videoSourceString = when (activeModel) {
            is File -> Uri.fromFile(activeModel).toString()
            is String -> activeModel
            else -> ""
        }
        val isVideoFile = (activeModel as? File)?.extension?.lowercase() in setOf("mp4", "webm", "m4v", "mov")
        val showAsVideo = cleanUrl.isNotEmpty() &&
            (isVideoMediaUrl(cleanUrl) || isVideoFile) &&
            videoSourceString.isNotEmpty() &&
            !videoPlaybackFailed

        when {
            showAsVideo -> {
                LoopingMutedVideoAvatar(
                    videoUrl = videoSourceString,
                    onError = { videoPlaybackFailed = true },
                    fallbackContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(fallbackGradient)
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            activeModel != null -> {
                SubcomposeAsyncImage(
                    model = activeModel,
                    contentDescription = "Banner do perfil",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(fallbackGradient)
                        )
                    },
                    error = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(fallbackGradient)
                        )
                    }
                )
            }
        }

        overlayContent()
    }
}

@Composable
fun Zap2Avatar(
    name: String,
    photoUrl: String?,
    isGroup: Boolean = false,
    online: Boolean = false,
    size: Dp = 52.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val initials = remember(name) {
        name.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifEmpty { "Z2" }
    }

    val cleanUrl = photoUrl?.trim().orEmpty()
    val needsManaged = FreeMediaStorageManager.requiresManagedResolution(cleanUrl)
    val resolvedModel by produceState<Any?>(
        initialValue = if (needsManaged) null else cleanUrl.takeIf { it.isNotEmpty() },
        key1 = cleanUrl
    ) {
        value = if (cleanUrl.isEmpty()) {
            null
        } else if (needsManaged) {
            FreeMediaStorageManager.resolveMediaModel(context, cleanUrl)
        } else {
            cleanUrl
        }
    }

    var videoPlaybackFailed by remember(cleanUrl) { mutableStateOf(false) }

    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(Zap2ExpressiveShapes.SquircleAvatar)
                .background(
                    if (isGroup) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.primaryContainer
                ),
            contentAlignment = Alignment.Center
        ) {
            val activeModel = resolvedModel
            val videoSourceString = when (activeModel) {
                is File -> Uri.fromFile(activeModel).toString()
                is String -> activeModel
                else -> ""
            }
            when {
                cleanUrl.isNotEmpty() && isVideoMediaUrl(cleanUrl) && videoSourceString.isNotEmpty() && !videoPlaybackFailed -> {
                    LoopingMutedVideoAvatar(
                        videoUrl = videoSourceString,
                        onError = { videoPlaybackFailed = true },
                        fallbackContent = {
                            AvatarFallbackVisual(
                                name = name,
                                initials = initials,
                                isGroup = isGroup,
                                size = size
                            )
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                activeModel != null -> {
                    SubcomposeAsyncImage(
                        model = activeModel,
                        contentDescription = "Avatar de $name",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        loading = {
                            AvatarFallbackVisual(
                                name = name,
                                initials = initials,
                                isGroup = isGroup,
                                size = size
                            )
                        },
                        error = {
                            AvatarFallbackVisual(
                                name = name,
                                initials = initials,
                                isGroup = isGroup,
                                size = size
                            )
                        }
                    )
                }
                else -> {
                    AvatarFallbackVisual(
                        name = name,
                        initials = initials,
                        isGroup = isGroup,
                        size = size
                    )
                }
            }
        }

        if (online && !isGroup) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(ZapOnlineGreen)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}

@Composable
private fun AvatarFallbackVisual(
    name: String,
    initials: String,
    isGroup: Boolean,
    size: Dp
) {
    if (isGroup) {
        Icon(
            imageVector = Icons.Rounded.Group,
            contentDescription = "Grupo $name",
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(size * 0.52f)
        )
    } else {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
fun LoopingMutedVideoAvatar(
    videoUrl: String,
    onError: () -> Unit,
    fallbackContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    var firstFrameReady by remember(videoUrl) { mutableStateOf(false) }
    val mediaPlayerHolder = remember(videoUrl) { arrayOfNulls<MediaPlayer>(1) }

    DisposableEffect(videoUrl) {
        onDispose {
            runCatching {
                mediaPlayerHolder[0]?.stop()
            }
            runCatching {
                mediaPlayerHolder[0]?.release()
            }
            mediaPlayerHolder[0] = null
        }
    }

    Box(modifier = modifier) {
        if (!firstFrameReady) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                fallbackContent()
            }
        }
        key(videoUrl) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                    val textureView = this
                    isOpaque = false
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var surfaceRef: Surface? = null

                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            val surface = Surface(surfaceTexture)
                            surfaceRef = surface
                            runCatching {
                                val mp = MediaPlayer().apply {
                                    setDataSource(ctx, Uri.parse(videoUrl))
                                    setSurface(surface)
                                    isLooping = true
                                    setVolume(0f, 0f)
                                    setOnVideoSizeChangedListener { _, vw, vh ->
                                        if (vw > 0 && vh > 0 && width > 0 && height > 0) {
                                            applyCenterCropTransform(textureView, width, height, vw, vh)
                                        }
                                    }
                                    setOnPreparedListener { player ->
                                        player.isLooping = true
                                        player.setVolume(0f, 0f)
                                        player.start()
                                        firstFrameReady = true
                                    }
                                    setOnInfoListener { _, what, _ ->
                                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                                            firstFrameReady = true
                                        }
                                        false
                                    }
                                    setOnErrorListener { _, _, _ ->
                                        onError()
                                        true
                                    }
                                    prepareAsync()
                                }
                                mediaPlayerHolder[0] = mp
                            }.onFailure {
                                onError()
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            val mp = mediaPlayerHolder[0] ?: return
                            val vw = mp.videoWidth
                            val vh = mp.videoHeight
                            if (vw > 0 && vh > 0 && width > 0 && height > 0) {
                                applyCenterCropTransform(textureView, width, height, vw, vh)
                            }
                        }

                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                            runCatching { mediaPlayerHolder[0]?.stop() }
                            runCatching { mediaPlayerHolder[0]?.release() }
                            mediaPlayerHolder[0] = null
                            surfaceRef?.release()
                            surfaceRef = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                    }
                }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun applyCenterCropTransform(
    textureView: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    videoWidth: Int,
    videoHeight: Int
) {
    val sx = viewWidth.toFloat() / videoWidth.toFloat()
    val sy = viewHeight.toFloat() / videoHeight.toFloat()
    val maxScale = maxOf(sx, sy)
    val scaledW = maxScale / sx
    val scaledH = maxScale / sy
    val matrix = Matrix().apply {
        setScale(scaledW, scaledH, viewWidth / 2f, viewHeight / 2f)
    }
    textureView.setTransform(matrix)
}

@Composable
fun TypingDotsAnimated(
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "typing_dots")
    val dot1 by transition.animateFloat(
        initialValue = 0f,
        targetValue = -4f,
        animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
        label = "d1"
    )
    val dot2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = -4f,
        animationSpec = infiniteRepeatable(tween(420, delayMillis = 140), RepeatMode.Reverse),
        label = "d2"
    )
    val dot3 by transition.animateFloat(
        initialValue = 0f,
        targetValue = -4f,
        animationSpec = infiniteRepeatable(tween(420, delayMillis = 280), RepeatMode.Reverse),
        label = "d3"
    )

    Row(
        modifier = modifier.padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(dot1, dot2, dot3).forEach { yOffset ->
            Box(
                modifier = Modifier
                    .offset(y = yOffset.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

fun formatFileSize(bytes: Long?): String {
    val size = (bytes ?: 0L).coerceAtLeast(0L)
    return when {
        size < 1024L -> "$size B"
        size < 1024L * 1024L -> "${(size / 1024.0).let { String.format(Locale.getDefault(), "%.1f", it) }} KB"
        size < 1024L * 1024L * 1024L -> "${(size / (1024.0 * 1024.0)).let { String.format(Locale.getDefault(), "%.1f", it) }} MB"
        else -> "${(size / (1024.0 * 1024.0 * 1024.0)).let { String.format(Locale.getDefault(), "%.2f", it) }} GB"
    }
}

fun formatClockTime(date: Date?): String {
    if (date == null) return ""
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
}

fun formatRelativeDateTime(date: Date?): String {
    if (date == null) return ""
    val now = System.currentTimeMillis()
    val diff = now - date.time
    return if (diff < 86_400_000L) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
    } else {
        SimpleDateFormat("dd/MM", Locale.getDefault()).format(date)
    }
}
