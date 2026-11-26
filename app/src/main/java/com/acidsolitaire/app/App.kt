package com.acidsolitaire.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
fun AcidTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}

fun backgroundResource(key: String): Int = when (key) {
    "freecell" -> R.drawable.bg_freecell
    "spider" -> R.drawable.bg_spider
    else -> R.drawable.bg_klondike
}

fun logoResource(kind: GameKind): Int = when (kind) {
    GameKind.KLONDIKE -> R.drawable.logo_klondike
    GameKind.FREECELL -> R.drawable.logo_freecell
    GameKind.SPIDER -> R.drawable.logo_spider
}

@Composable
fun AcidApp(game: GameStore) {
    BackHandler(enabled = !game.showMenu) { game.setMenuVisible(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { game.startClock() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { game.stopClock() }

    CompositionLocalProvider(LocalCardBacks provides game.cardBacks) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (game.showMenu) FeltTableBackground() else BackgroundView(game.backgrounds, game.selectedGame)
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (game.showMenu) MainMenu(game) else GameBoard(game)
            }
            game.celebration?.let { CelebrationView(it, game) }
        }
        if (game.showSettings) SettingsDialog(game)
        if (game.showBackgrounds) BackgroundsDialog(game)
        if (game.showStats) StatsDialog(game)
        if (game.showHelp) HelpDialog(game.selectedGame) { game.showHelp = false }
        ImportDialogs(game)
    }
}

/** Asks before replacing statistics with an imported file, and reports files that can't be read. */
@Composable
private fun ImportDialogs(game: GameStore) {
    val pending = game.pendingImport
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { game.pendingImport = null },
            title = { Text("Replace your statistics?") },
            text = { Text("Your current statistics will be replaced by the ones in the file.") },
            confirmButton = {
                TextButton(onClick = { game.importStats(pending); game.pendingImport = null }) { Text("Replace Statistics") }
            },
            dismissButton = { TextButton(onClick = { game.pendingImport = null }) { Text("Cancel") } },
        )
    }
    val error = game.importError
    if (error != null) {
        AlertDialog(
            onDismissRequest = { game.importError = null },
            title = { Text("Couldn’t import") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = { game.importError = null }) { Text("OK") } },
        )
    }
}

// region Backgrounds

/** The game backdrop: the chosen photo, or the built-in artwork when no photos have been added. */
@Composable
fun BackgroundView(store: BackgroundStore, kind: GameKind) {
    val id = store.currentId(kind)
    Crossfade(targetState = id, animationSpec = tween(900), label = "background") { shown ->
        val image by produceState<ImageBitmap?>(null, shown) {
            if (store.isPhoto(shown) || store.isAsset(shown)) {
                val loaded = store.loadImage(shown)
                // An album photo that can't be loaded is dropped and replaced.
                if (loaded == null && store.isAsset(shown)) store.dropUnavailableAsset(shown)
                value = loaded
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val key = store.builtInKey(shown)
            val loaded = image
            if (key != null) {
                Image(painterResource(backgroundResource(key)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else if (loaded != null) {
                Image(loaded, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.12f)))
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.12f), Color.Transparent, Color.Black.copy(alpha = 0.28f)))
                )
            )
        }
    }
}

private class Scattered(val card: Card, val x: Float, val y: Float, val angle: Float, val scale: Float)

private fun scatterCards(): List<Scattered> {
    val random = Random(2026)
    return List(36) { index ->
        Scattered(
            card = Card(
                id = index,
                rank = random.nextInt(1, 14),
                suit = Suit.entries[random.nextInt(Suit.entries.size)],
                faceUp = random.nextInt(5) != 0,
            ),
            x = -0.03f + random.nextFloat() * 1.06f,
            y = -0.03f + random.nextFloat() * 1.06f,
            angle = random.nextFloat() * 360f - 180f,
            scale = 0.85f + random.nextFloat() * 0.3f,
        )
    }
}

/** The home screen backdrop: a red felt card table with playing cards strewn across it. */
@Composable
fun FeltTableBackground() {
    val items = remember { scatterCards() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val base = minOf(110.dp, maxOf(60.dp, minOf(maxWidth, maxHeight) * 0.2f))
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0.74f, 0.12f, 0.14f), Color(0.55f, 0.07f, 0.09f), Color(0.26f, 0.02f, 0.04f)),
                    center = Offset(size.width / 2, size.height / 2),
                    radius = max(size.width, size.height) * 0.75f,
                )
            )
            // Felt grain: thousands of tiny light and dark flecks.
            val random = Random(77)
            repeat(4000) { index ->
                val tint = if (index % 2 == 0) Color.Black else Color.White
                drawRect(
                    tint.copy(alpha = 0.07f),
                    Offset(random.nextFloat() * size.width, random.nextFloat() * size.height),
                    Size(1.5f, 1.5f),
                )
            }
        }
        for (item in items) {
            val cardWidth = base * item.scale
            val cardHeight = cardWidth / 0.68f
            CardView(
                item.card, cardWidth,
                Modifier
                    .offset {
                        IntOffset(
                            (item.x * widthPx - cardWidth.toPx() / 2).roundToInt(),
                            (item.y * heightPx - cardHeight.toPx() / 2).roundToInt(),
                        )
                    }
                    .rotate(item.angle),
            )
        }
        // A soft darkening keeps the menu buttons and title easy to read.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.1f), Color.Black.copy(alpha = 0.4f)))
            )
        )
    }
}

// endregion

@Composable
fun MainMenu(game: GameStore) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "AcidSolitaire Collection", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { game.showSettings = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            for (kind in GameKind.entries) {
                val shape = RoundedCornerShape(14.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f), shape)
                        .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
                        .clickable { game.open(kind) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Image(painterResource(logoResource(kind)), null, Modifier.size(42.dp))
                    Column(Modifier.weight(1f)) {
                        Text(kind.title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text(kind.subtitle, color = Color.White, fontSize = 14.sp)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White)
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}
