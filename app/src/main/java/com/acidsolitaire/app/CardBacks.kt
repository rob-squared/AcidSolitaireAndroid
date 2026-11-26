package com.acidsolitaire.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The ten built-in card back designs. They are drawn in code, so no image files are needed. */
enum class CardBackStyle(val title: String) {
    CLASSIC_BLUE("Classic Blue"),
    CRIMSON("Crimson"),
    EMERALD("Emerald"),
    VIOLET("Violet"),
    MIDNIGHT("Midnight"),
    SUNSET("Sunset"),
    OCEAN("Ocean"),
    GOLD("Black & Gold"),
    GRAPHITE("Graphite"),
    ACID("Acid"),
}

/** Which card back is in use: one of the ten designs, or a photo of the player's own. */
class CardBackStore(context: Context, private val prefs: SharedPreferences) {
    companion object {
        const val CUSTOM = "custom"
        private const val CHOICE = "cardBack.choice.v1"
    }

    private val directory = File(context.filesDir, "cardback").also { it.mkdirs() }
    private val file = File(directory, "custom.jpg")

    var choice by mutableStateOf(prefs.getString(CHOICE, null) ?: CardBackStyle.CLASSIC_BLUE.name)
        private set
    var customImage by mutableStateOf<ImageBitmap?>(null)
        private set

    init {
        if (file.exists()) {
            customImage = decodeScaled(ImageDecoder.createSource(file), 700)?.asImageBitmap()
        }
    }

    val style: CardBackStyle
        get() = CardBackStyle.entries.firstOrNull { it.name == choice } ?: CardBackStyle.CLASSIC_BLUE

    /** True when the player's own photo is the chosen back. */
    val usesCustom: Boolean get() = choice == CUSTOM && customImage != null

    fun choose(value: String) {
        choice = value
        prefs.edit { putString(CHOICE, value) }
    }

    /** Crops a photo to card proportions, saves it and makes it the card back. */
    suspend fun setCustom(context: Context, uri: Uri): Boolean {
        val cropped = withContext(Dispatchers.IO) {
            val source = decodeScaled(ImageDecoder.createSource(context.contentResolver, uri), 1400)
                ?: return@withContext null
            val targetW = 340
            val targetH = 500
            val scale = max(targetW.toFloat() / source.width, targetH.toFloat() / source.height)
            val scaledW = max(targetW, (source.width * scale).roundToInt())
            val scaledH = max(targetH, (source.height * scale).roundToInt())
            val scaled = source.scale(scaledW, scaledH)
            val result = Bitmap.createBitmap(scaled, (scaledW - targetW) / 2, (scaledH - targetH) / 2, targetW, targetH)
            runCatching {
                FileOutputStream(file).use { result.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                result
            }.getOrNull()
        } ?: return false
        customImage = cropped.asImageBitmap()
        choose(CUSTOM)
        return true
    }

    fun removeCustom() {
        file.delete()
        customImage = null
        if (choice == CUSTOM) choose(CardBackStyle.CLASSIC_BLUE.name)
    }
}

val LocalCardBacks = staticCompositionLocalOf<CardBackStore> { error("CardBackStore not provided") }

/** The face-down side of every card, using the chosen design or the player's own photo. */
@Composable
fun CardBackFace(radius: Dp, modifier: Modifier = Modifier) {
    val store = LocalCardBacks.current
    val custom = store.customImage
    Box(modifier.fillMaxSize().padding(2.5.dp).clip(RoundedCornerShape(radius))) {
        if (store.usesCustom && custom != null) {
            Image(custom, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            CardBackArt(store.style, Modifier.fillMaxSize())
        }
    }
}

/** One of the ten designs, drawn on a Canvas so it stays sharp at any size. */
@Composable
fun CardBackArt(style: CardBackStyle, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawBack(style) }
}

private fun rgb(r: Double, g: Double, b: Double) = Color(r.toFloat(), g.toFloat(), b.toFloat())

private fun DrawScope.drawBack(style: CardBackStyle) {
    val w = size.width
    val gold = rgb(0.93, 0.76, 0.30)
    val line = max(1f, w * 0.012f)
    fun diagonal(from: Color, to: Color) = drawRect(
        Brush.linearGradient(listOf(from, to), start = Offset.Zero, end = Offset(size.width, size.height))
    )
    fun vertical(colors: List<Color>) = drawRect(Brush.verticalGradient(colors))

    when (style) {
        CardBackStyle.CLASSIC_BLUE -> {
            diagonal(rgb(0.07, 0.14, 0.50), rgb(0.13, 0.27, 0.72))
            lattice(w * 0.16f, Color.White.copy(alpha = 0.28f), line)
            diamond(gold, 0.46f)
        }
        CardBackStyle.CRIMSON -> {
            diagonal(rgb(0.55, 0.04, 0.10), rgb(0.82, 0.12, 0.16))
            dots(w * 0.13f, w * 0.02f, Color.White.copy(alpha = 0.45f))
            medallion(gold, Color.White, w * 0.2f, line)
        }
        CardBackStyle.EMERALD -> {
            diagonal(rgb(0.02, 0.42, 0.26), rgb(0.0, 0.22, 0.15))
            stripes(w * 0.1f, Color.White.copy(alpha = 0.2f), w * 0.03f, vertical = true)
            medallion(rgb(0.9, 1.0, 0.9), gold, w * 0.2f, line)
        }
        CardBackStyle.VIOLET -> {
            diagonal(rgb(0.32, 0.12, 0.58), rgb(0.52, 0.27, 0.82))
            lattice(w * 0.22f, Color.White.copy(alpha = 0.32f), line * 1.5f)
            diamond(rgb(1.0, 0.9, 1.0), 0.4f)
        }
        CardBackStyle.MIDNIGHT -> {
            diagonal(rgb(0.02, 0.03, 0.14), rgb(0.20, 0.12, 0.40))
            stars()
            medallion(rgb(1.0, 0.95, 0.7), Color.White.copy(alpha = 0.6f), w * 0.16f, line)
        }
        CardBackStyle.SUNSET -> {
            vertical(listOf(rgb(1.0, 0.78, 0.25), rgb(1.0, 0.42, 0.32), rgb(0.55, 0.18, 0.55)))
            stripes(size.height * 0.07f, Color.White.copy(alpha = 0.22f), line * 1.5f, vertical = false)
            medallion(rgb(1.0, 0.95, 0.8), Color.White.copy(alpha = 0.7f), w * 0.22f, line)
        }
        CardBackStyle.OCEAN -> {
            vertical(listOf(rgb(0.0, 0.62, 0.82), rgb(0.0, 0.20, 0.45)))
            waves(w * 0.2f, Color.White.copy(alpha = 0.4f), line * 1.5f)
        }
        CardBackStyle.GOLD -> {
            drawRect(rgb(0.07, 0.07, 0.08))
            lattice(w * 0.14f, gold.copy(alpha = 0.85f), line)
            diamond(rgb(0.07, 0.07, 0.08), 0.4f, outline = gold, lineWidth = line * 1.5f)
        }
        CardBackStyle.GRAPHITE -> {
            drawRect(rgb(0.2, 0.21, 0.23))
            checker(w * 0.1f, rgb(0.31, 0.32, 0.35))
            medallion(rgb(0.75, 0.77, 0.8), Color.White.copy(alpha = 0.6f), w * 0.17f, line)
        }
        CardBackStyle.ACID -> {
            drawRect(Color.Black)
            rings(listOf(rgb(0.7, 1.0, 0.0), rgb(1.0, 0.0, 0.6), Color.Black), 10)
        }
    }
    // A thin keyline inside the edge ties every design together.
    drawRoundRect(
        Color.White.copy(alpha = 0.55f),
        topLeft = Offset(w * 0.07f, w * 0.07f),
        size = Size(size.width - w * 0.14f, size.height - w * 0.14f),
        cornerRadius = CornerRadius(w * 0.05f),
        style = Stroke(line),
    )
}

private fun DrawScope.lattice(spacing: Float, color: Color, lineWidth: Float) {
    val path = Path()
    var x = -size.height
    while (x < size.width) {
        path.moveTo(x, 0f)
        path.lineTo(x + size.height, size.height)
        path.moveTo(x + size.height, 0f)
        path.lineTo(x, size.height)
        x += spacing
    }
    drawPath(path, color, style = Stroke(lineWidth))
}

private fun DrawScope.dots(spacing: Float, radius: Float, color: Color) {
    var y = spacing / 2
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) spacing / 2 else spacing
        while (x < size.width) {
            drawCircle(color, radius, Offset(x, y))
            x += spacing
        }
        y += spacing * 0.8f
        row += 1
    }
}

private fun DrawScope.stripes(spacing: Float, color: Color, lineWidth: Float, vertical: Boolean) {
    val path = Path()
    var offset = spacing / 2
    val limit = if (vertical) size.width else size.height
    while (offset < limit) {
        if (vertical) {
            path.moveTo(offset, 0f)
            path.lineTo(offset, size.height)
        } else {
            path.moveTo(0f, offset)
            path.lineTo(size.width, offset)
        }
        offset += spacing
    }
    drawPath(path, color, style = Stroke(lineWidth))
}

private fun DrawScope.waves(spacing: Float, color: Color, lineWidth: Float) {
    val path = Path()
    val length = spacing * 1.6f
    val lift = spacing * 0.7f
    var y = spacing / 2
    while (y < size.height + spacing) {
        path.moveTo(0f, y)
        var x = 0f
        var up = true
        while (x < size.width) {
            path.quadraticTo(x + length / 2, if (up) y - lift else y + lift, x + length, y)
            x += length
            up = !up
        }
        y += spacing
    }
    drawPath(path, color, style = Stroke(lineWidth))
}

private fun DrawScope.stars() {
    val random = Random(42)
    repeat(45) {
        val x = random.nextFloat() * size.width
        val y = random.nextFloat() * size.height
        val r = (0.5f + random.nextFloat() * 1.1f) * max(0.6f, size.width / 60f)
        drawCircle(Color.White.copy(alpha = 0.85f), r, Offset(x, y))
    }
}

private fun DrawScope.checker(cell: Float, color: Color) {
    var row = 0
    var y = 0f
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            drawRect(color, Offset(x, y), Size(cell, cell))
            x += cell * 2
        }
        y += cell
        row += 1
    }
}

private fun DrawScope.rings(colors: List<Color>, count: Int) {
    val center = Offset(size.width / 2, size.height / 2)
    val longest = max(size.width, size.height) * 0.75f
    for (index in count downTo 1) {
        drawCircle(colors[index % colors.size], longest * index / count, center)
    }
}

private fun DrawScope.diamond(fill: Color, scale: Float, outline: Color = Color.White, lineWidth: Float = 1.5f) {
    val halfW = size.width * scale / 2
    val halfH = size.height * scale / 2
    val cx = size.width / 2
    val cy = size.height / 2
    val path = Path().apply {
        moveTo(cx, cy - halfH)
        lineTo(cx + halfW, cy)
        lineTo(cx, cy + halfH)
        lineTo(cx - halfW, cy)
        close()
    }
    drawPath(path, fill)
    drawPath(path, outline.copy(alpha = 0.9f), style = Stroke(lineWidth))
}

private fun DrawScope.medallion(fill: Color, ring: Color, radius: Float, lineWidth: Float) {
    val center = Offset(size.width / 2, size.height / 2)
    drawCircle(fill, radius, center)
    drawCircle(ring, radius, center, style = Stroke(lineWidth))
}
