package com.acidsolitaire.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

private val sparkColors = listOf(
    Color(0xFFFF4D6D), Color(0xFFFFC107), Color(0xFF4DD0E1), Color(0xFF9CCC65), Color(0xFFBA68C8), Color(0xFFFF8A65),
)

/** The win overlay: a firework burst, the result, a fun fact and what to do next. */
@Composable
fun CelebrationView(celebration: Celebration, game: GameStore) {
    val transition = rememberInfiniteTransition(label = "fireworks")
    val t by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            // Swallow touches so the board underneath can't be played while the overlay is up.
            .clickable(interactionSource = MutableInteractionSource(), indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val bursts = listOf(Offset(0.25f, 0.28f), Offset(0.75f, 0.22f), Offset(0.5f, 0.12f))
            bursts.forEachIndexed { b, origin ->
                val phase = (t + b * 0.31f) % 1f
                val radius = size.minDimension * 0.28f * phase
                for (i in 0 until 18) {
                    val angle = i * (2 * Math.PI / 18)
                    val center = Offset(
                        size.width * origin.x + (cos(angle) * radius).toFloat(),
                        size.height * origin.y + (sin(angle) * radius).toFloat() + phase * 30f,
                    )
                    drawCircle(sparkColors[(i + b) % sparkColors.size].copy(alpha = 1f - phase), 5f, center)
                }
            }
        }
        // The card is black, so text inside it defaults to white.
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(
                Modifier.padding(24.dp).background(Color.Black, RoundedCornerShape(20.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("You Won!", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "${celebration.kind.title} in ${celebration.moves} moves · ${formatDuration(celebration.seconds)}",
                    textAlign = TextAlign.Center,
                )
                if (celebration.isRecord) {
                    Text("New fastest game!", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Text(
                    celebration.fact, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { game.dismissCelebration(true) }) { Text("New Deal") }
                    OutlinedButton(onClick = { game.dismissCelebration(false) }) { Text("Menu") }
                }
            }
        }
    }
}
