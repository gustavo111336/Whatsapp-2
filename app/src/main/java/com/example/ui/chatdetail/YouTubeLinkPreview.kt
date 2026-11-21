package com.example.ui.chatdetail

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.Zap2Application
import com.example.data.model.ChatMessage
import com.example.data.remote.FreeMediaStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class YouTubeLinkInfo(
    val videoId: String,
    val canonicalUrl: String,
    val originalUrl: String,
    val thumbnailUrl: String
)

data class YouTubeOEmbedMeta(
    val title: String,
    val authorName: String
)

private val URL_REGEX = Regex("""https?://[^\s<>()]+""")
private val YOUTUBE_ID_REGEX = Regex(
    """(?:youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|live/|embed/)|youtu\.be/)([A-Za-z0-9_-]{11})"""
)
private val oEmbedCache = ConcurrentHashMap<String, YouTubeOEmbedMeta>()

fun buildMessageAnnotatedText(text: String, linkColor: Color): AnnotatedString {
    return buildAnnotatedString {
        append(text)
        URL_REGEX.findAll(text).forEach { match ->
            val rawUrl = match.value.trimEnd('.', ',', ')', ']', ';', '!', '?')
            val endIndex = match.range.first + rawUrl.length
            addLink(
                url = LinkAnnotation.Url(
                    url = rawUrl,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium
                        )
                    )
                ),
                start = match.range.first,
                end = endIndex
            )
        }
    }
}

fun extractFirstYouTubeInfo(text: String): YouTubeLinkInfo? {
    val urlMatch = URL_REGEX.findAll(text)
        .map { it.value.trimEnd('.', ',', ')', ']', ';', '!', '?') }
        .firstOrNull { YOUTUBE_ID_REGEX.containsMatchIn(it) }
        ?: return null
    val videoId = YOUTUBE_ID_REGEX.find(urlMatch)?.groupValues?.getOrNull(1) ?: return null
    return YouTubeLinkInfo(
        videoId = videoId,
        canonicalUrl = "https://www.youtube.com/watch?v=$videoId",
        originalUrl = urlMatch,
        thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
    )
}

@Composable
fun YouTubePreviewCard(
    info: YouTubeLinkInfo,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var meta by remember(info.videoId) { mutableStateOf(oEmbedCache[info.videoId]) }

    LaunchedEffect(info.videoId) {
        if (meta == null) {
            val fetched = withContext(Dispatchers.IO) { fetchYouTubeOEmbed(info.videoId) }
            if (fetched != null) {
                oEmbedCache[info.videoId] = fetched
                meta = fetched
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenUrl(info.originalUrl) }
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(145.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(Color.Black)
            ) {
                AsyncImage(
                    model = info.thumbnailUrl,
                    contentDescription = "Miniatura do vídeo do YouTube",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xCCFF0000),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(width = 52.dp, height = 36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Abrir no YouTube",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = meta?.title ?: "Vídeo do YouTube",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = meta?.authorName?.let { "$it • YouTube" } ?: info.canonicalUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF1A73E8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun VideoMessageBubbleContent(
    message: ChatMessage,
    onPlayVideo: (File) -> Unit
) {
    val context = LocalContext.current
    val resolvedVideoFile by produceState<File?>(
        initialValue = null,
        key1 = message.id,
        key2 = message.localMediaPath,
        key3 = message.mediaUrl
    ) {
        value = withContext(Dispatchers.IO) {
            resolveVideoFile(context, message)
        }
    }

    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = resolvedVideoFile?.absolutePath) {
        val file = resolvedVideoFile ?: return@produceState
        value = withContext(Dispatchers.IO) {
            extractVideoFrame(file)
        }
    }

    val seconds = ((message.mediaDurationMs ?: 0L) / 1000L).coerceAtLeast(1L)
    val isFetchingRemote = resolvedVideoFile == null && !message.mediaUrl.isNullOrBlank()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .clickable(enabled = resolvedVideoFile != null) {
                resolvedVideoFile?.let(onPlayVideo)
            }
    ) {
        val thumbBmp = thumbnail
        if (thumbBmp != null) {
            Image(
                bitmap = thumbBmp.asImageBitmap(),
                contentDescription = "Miniatura do vídeo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.Center)
                .size(54.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isFetchingRemote) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.PlayCircle,
                        contentDescription = "Reproduzir vídeo",
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color.Black.copy(alpha = 0.65f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
        ) {
            Text(
                text = "🎬 ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
fun VideoPlayerDialog(
    videoFile: File,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Black,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Fechar vídeo",
                            tint = Color.White
                        )
                    }
                }
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            val controller = MediaController(ctx)
                            controller.setAnchorView(this)
                            setMediaController(controller)
                            setVideoURI(Uri.fromFile(videoFile))
                            setOnPreparedListener { mp ->
                                mp.isLooping = false
                                start()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 460.dp)
                )
            }
        }
    }
}

private suspend fun resolveVideoFile(context: Context, message: ChatMessage): File? {
    val local = message.localMediaPath?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
    if (local != null) return local
    val url = message.mediaUrl ?: return null
    if (url.startsWith("data:video/")) {
        return runCatching {
            val b64 = url.substringAfter(",", "")
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val cached = File(context.cacheDir, "play_vid_${message.id}.mp4")
            if (!cached.exists() || cached.length() != bytes.size.toLong()) {
                cached.writeBytes(bytes)
            }
            cached
        }.getOrNull()
    }
    val manager = (context.applicationContext as? Zap2Application)?.container?.freeStorageManager
    if (manager != null && (FreeMediaStorageManager.isChunkUri(url) || url.startsWith("http://") || url.startsWith("https://"))) {
        return manager.resolveToFile(
            url = url,
            preferredFileName = message.fileName ?: "play_vid_${message.id}.mp4",
            expectedSizeBytes = message.fileSizeBytes
        ).getOrNull()
    }
    return null
}

private fun extractVideoFrame(file: File): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun fetchYouTubeOEmbed(videoId: String): YouTubeOEmbedMeta? {
    return try {
        val url = URL("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3500
            readTimeout = 3500
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val title = json.optString("title", "").trim()
                val author = json.optString("author_name", "").trim()
                if (title.isNotEmpty()) YouTubeOEmbedMeta(title, author) else null
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
