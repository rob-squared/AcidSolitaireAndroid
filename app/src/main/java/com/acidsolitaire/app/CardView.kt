package com.acidsolitaire.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** A playing card, drawn at exactly [width] by [width] / 0.68. */
@Composable
fun CardView(card: Card, width: Dp, modifier: Modifier = Modifier) {
    val height = width / 0.68f
    val radius = if (width < 56.dp) 5.dp else 7.dp
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .size(width, height)
            .shadow(1.dp, shape)
            .background(if (card.faceUp) Color.White else Color(0.18f, 0.18f, 0.18f), shape)
            .border(BorderStroke(1.dp, Color.Black.copy(alpha = 0.45f)), shape)
    ) {
        if (card.faceUp) {
            CardFace(card, width)
        } else {
            CardBackFace(if (width < 56.dp) 3.dp else 5.dp)
        }
    }
}

/**
 * The value is sized from the card's width so it is as large as the overlap allows: the strip of a
 * covered card is about 0.46 of the width tall. Sizes ignore the system font scale, so the overlap
 * maths always holds.
 */
@Composable
private fun CardFace(card: Card, width: Dp) {
    val density = LocalDensity.current
    val rankDp = max(16f, width.value * 0.40f)
    val rankSize = with(density) { rankDp.dp.toSp() }
    val suitSmall = with(density) { (rankDp * 0.6f).dp.toSp() }
    val suitBig = with(density) { (width.value * 0.5f).dp.toSp() }
    val color = if (card.suit.isRed) Color(0.9f, 0.1f, 0.1f) else Color.Black
    Column(Modifier.fillMaxSize().padding(width * 0.06f)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                card.label,
                color = color,
                fontSize = rankSize,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.SansSerif,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                lineHeight = rankSize,
            )
            Text(card.suit.symbol, color = color, fontSize = suitSmall, maxLines = 1, softWrap = false, lineHeight = suitSmall)
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Text(card.suit.symbol, color = color, fontSize = suitBig, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
        }
    }
}
