package com.example.ui.chatdetail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.util.UUID
import kotlin.math.min

enum class StickerCropShape(val label: String) {
    ROUNDED("Quadrado"),
    CIRCLE("Círculo"),
    TRANSPARENT("Livre")
}

private data class DoodleStroke(
    val points: List<Offset>,
    val colorArgb: Int
)

@Composable
fun StickerCreatorDialog(
    onDismiss: () -> Unit,
    onSaveOnly: (File, String) -> Unit,
    onSendSticker: (File, Boolean, String) -> Unit
) {
    val context = LocalContext.current
    var baseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var bgColor by remember { mutableStateOf(Color(0xFF00A884)) }
    var shape by remember { mutableStateOf(StickerCropShape.ROUNDED) }
    var whiteBorder by remember { mutableStateOf(true) }
    var caption by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("Minha Fig") }
    var textColor by remember { mutableStateOf(Color.White) }
    var brushColor by remember { mutableStateOf(Color(0xFFFFEB3B)) }
    var saveToCollection by remember { mutableStateOf(true) }

    val strokes = remember { mutableStateListOf<DoodleStroke>() }
    var activePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize(240, 240)) }

    val bgPalette = listOf(
        Color.Transparent,
        Color(0xFF00A884),
        Color(0xFF1E88E5),
        Color(0xFF8E24AA),
        Color(0xFFE53935),
        Color(0xFFFB8C00),
        Color(0xFF1F2937)
    )
    val inkPalette = listOf(
        Color.White,
        Color(0xFFFFEB3B),
        Color(0xFF111827),
        Color(0xFFFF4081),
        Color(0xFF00E5FF)
    )

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            baseBitmap = decodeScaledStickerSource(context, uri, 512)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Criar Fig (Sticker)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Fechar")
                    }
                }

                // Live Preview & Drawing Canvas
                val previewShape = when (shape) {
                    StickerCropShape.CIRCLE -> CircleShape
                    StickerCropShape.ROUNDED -> RoundedCornerShape(36.dp)
                    StickerCropShape.TRANSPARENT -> RoundedCornerShape(12.dp)
                }
                Box(
                    modifier = Modifier
                        .size(230.dp)
                        .align(Alignment.CenterHorizontally)
                        .clip(previewShape)
                        .background(
                            if (bgColor == Color.Transparent) {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            } else {
                                bgColor
                            }
                        )
                        .then(
                            if (whiteBorder) Modifier.border(5.dp, Color.White, previewShape) else Modifier
                        )
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(brushColor) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    activePoints = listOf(start)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    activePoints = activePoints + change.position
                                },
                                onDragEnd = {
                                    if (activePoints.size >= 2) {
                                        strokes.add(DoodleStroke(activePoints, brushColor.toArgb()))
                                    }
                                    activePoints = emptyList()
                                },
                                onDragCancel = {
                                    activePoints = emptyList()
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = baseBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Prévia da fig",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (strokes.isEmpty() && caption.isBlank()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Brush,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = "Escolha uma foto ou desenhe/escreva aqui",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.9f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                        strokes.forEach { stroke ->
                            if (stroke.points.size >= 2) {
                                val path = ComposePath().apply {
                                    moveTo(stroke.points.first().x, stroke.points.first().y)
                                    stroke.points.drop(1).forEach { pt -> lineTo(pt.x, pt.y) }
                                }
                                drawPath(
                                    path = path,
                                    color = Color(stroke.colorArgb),
                                    style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                            }
                        }
                        if (activePoints.size >= 2) {
                            val path = ComposePath().apply {
                                moveTo(activePoints.first().x, activePoints.first().y)
                                activePoints.drop(1).forEach { pt -> lineTo(pt.x, pt.y) }
                            }
                            drawPath(
                                path = path,
                                color = brushColor,
                                style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }

                    if (caption.isNotBlank()) {
                        Text(
                            text = caption,
                            color = textColor,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 12.dp, vertical = 14.dp)
                                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                // Image & clear controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("sticker_pick_image_button")
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Foto")
                    }
                    OutlinedButton(
                        onClick = { strokes.clear() },
                        enabled = strokes.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Limpar traço")
                    }
                }

                // Shape & border chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StickerCropShape.entries.forEach { candidate ->
                        FilterChip(
                            selected = shape == candidate,
                            onClick = { shape = candidate },
                            label = { Text(candidate.label) }
                        )
                    }
                    FilterChip(
                        selected = whiteBorder,
                        onClick = { whiteBorder = !whiteBorder },
                        label = { Text("Borda") }
                    )
                }

                // Background palette
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Fundo da Fig", style = MaterialTheme.typography.labelMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        bgPalette.forEach { c ->
                            val selected = bgColor == c
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(if (c == Color.Transparent) MaterialTheme.colorScheme.surfaceContainerHighest else c)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        shape = CircleShape
                                    )
                                    .clickable { bgColor = c }
                            )
                        }
                    }
                }

                // Ink/Text palette
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Cor do Pincel e Texto", style = MaterialTheme.typography.labelMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        inkPalette.forEach { c ->
                            val selected = brushColor == c
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        brushColor = c
                                        textColor = c
                                    }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it.take(48) },
                    label = { Text("Texto da fig (opcional)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sticker_caption_input")
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(36) },
                    label = { Text("Nome em Minhas Figs") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { saveToCollection = !saveToCollection }
                ) {
                    Checkbox(
                        checked = saveToCollection,
                        onCheckedChange = { saveToCollection = it }
                    )
                    Text("Salvar em Minhas Figs ao enviar", style = MaterialTheme.typography.bodyMedium)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val file = renderStickerPngFile(
                                context = context,
                                baseBitmap = baseBitmap,
                                bgColorArgb = if (bgColor == Color.Transparent) AndroidColor.TRANSPARENT else bgColor.toArgb(),
                                shape = shape,
                                whiteBorder = whiteBorder,
                                caption = caption,
                                textColorArgb = textColor.toArgb(),
                                strokes = strokes.toList(),
                                sourceCanvasSize = canvasSize
                            )
                            onSaveOnly(file, title)
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("sticker_save_only_button")
                    ) {
                        Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Só Salvar")
                    }
                    Button(
                        onClick = {
                            val file = renderStickerPngFile(
                                context = context,
                                baseBitmap = baseBitmap,
                                bgColorArgb = if (bgColor == Color.Transparent) AndroidColor.TRANSPARENT else bgColor.toArgb(),
                                shape = shape,
                                whiteBorder = whiteBorder,
                                caption = caption,
                                textColorArgb = textColor.toArgb(),
                                strokes = strokes.toList(),
                                sourceCanvasSize = canvasSize
                            )
                            onSendSticker(file, saveToCollection, title)
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("sticker_send_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Enviar Fig")
                    }
                }
            }
        }
    }
}

private fun decodeScaledStickerSource(context: Context, uri: Uri, targetSize: Int): Bitmap? {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val raw = BitmapFactory.decodeStream(stream) ?: return null
            val scale = min(targetSize.toFloat() / raw.width, targetSize.toFloat() / raw.height)
            val w = (raw.width * scale).toInt().coerceAtLeast(1)
            val h = (raw.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(raw, w, h, true).also {
                if (it !== raw) raw.recycle()
            }
        }
    }.getOrNull()
}

private fun renderStickerPngFile(
    context: Context,
    baseBitmap: Bitmap?,
    bgColorArgb: Int,
    shape: StickerCropShape,
    whiteBorder: Boolean,
    caption: String,
    textColorArgb: Int,
    strokes: List<DoodleStroke>,
    sourceCanvasSize: IntSize
): File {
    val size = 512
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())

    val contentBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val contentCanvas = Canvas(contentBitmap)

    if (bgColorArgb != AndroidColor.TRANSPARENT) {
        contentCanvas.drawColor(bgColorArgb)
    }

    if (baseBitmap != null) {
        val side = min(baseBitmap.width, baseBitmap.height)
        val left = (baseBitmap.width - side) / 2
        val top = (baseBitmap.height - side) / 2
        val srcRect = Rect(left, top, left + side, top + side)
        contentCanvas.drawBitmap(baseBitmap, srcRect, rect, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    // Scale doodle strokes from preview coordinates to 512x512
    val sx = if (sourceCanvasSize.width > 0) size.toFloat() / sourceCanvasSize.width else 1f
    val sy = if (sourceCanvasSize.height > 0) size.toFloat() / sourceCanvasSize.height else 1f
    strokes.forEach { stroke ->
        if (stroke.points.size >= 2) {
            val path = Path().apply {
                moveTo(stroke.points.first().x * sx, stroke.points.first().y * sy)
                stroke.points.drop(1).forEach { pt -> lineTo(pt.x * sx, pt.y * sy) }
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke.colorArgb
                style = Paint.Style.STROKE
                strokeWidth = 18f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            contentCanvas.drawPath(path, paint)
        }
    }

    if (caption.isNotBlank()) {
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(120, 0, 0, 0)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColorArgb
            textSize = 48f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val textBounds = Rect()
        textPaint.getTextBounds(caption, 0, caption.length, textBounds)
        val padX = 28f
        val padY = 18f
        val centerY = size - 56f
        val bgRect = RectF(
            (size / 2f) - (textBounds.width() / 2f) - padX,
            centerY - textBounds.height() - padY,
            (size / 2f) + (textBounds.width() / 2f) + padX,
            centerY + padY
        )
        contentCanvas.drawRoundRect(bgRect, 22f, 22f, boxPaint)
        contentCanvas.drawText(caption, size / 2f, centerY, textPaint)
    }

    // Apply shape mask
    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE }
    when (shape) {
        StickerCropShape.CIRCLE -> canvas.drawOval(rect, maskPaint)
        StickerCropShape.ROUNDED -> canvas.drawRoundRect(rect, 84f, 84f, maskPaint)
        StickerCropShape.TRANSPARENT -> canvas.drawRoundRect(rect, 28f, 28f, maskPaint)
    }
    layerPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(contentBitmap, 0f, 0f, layerPaint)
    contentBitmap.recycle()

    if (whiteBorder) {
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 14f
        }
        val borderRect = RectF(7f, 7f, size - 7f, size - 7f)
        when (shape) {
            StickerCropShape.CIRCLE -> canvas.drawOval(borderRect, borderPaint)
            StickerCropShape.ROUNDED -> canvas.drawRoundRect(borderRect, 80f, 80f, borderPaint)
            StickerCropShape.TRANSPARENT -> canvas.drawRoundRect(borderRect, 24f, 24f, borderPaint)
        }
    }

    val outFile = File(context.cacheDir, "created_fig_${UUID.randomUUID()}.png")
    outFile.outputStream().use { stream ->
        output.compress(Bitmap.CompressFormat.PNG, 100, stream)
    }
    output.recycle()
    return outFile
}
