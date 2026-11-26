package com.acidsolitaire.app

import android.annotation.SuppressLint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/** Sizes shared by every part of the board, so drag-and-drop can work out where things are. */
class BoardLayout(val width: Dp, val gap: Dp, val columns: Int) {
    val height: Dp get() = width / 0.68f

    /** The distance from the top of one overlapped card to the next in a column. */
    val step: Dp get() = maxOf(22.dp, width * 0.46f)
    val pitch: Dp get() = width + gap
    val rowWidth: Dp get() = width * columns + gap * (columns - 1)
}

/** A card or run being dragged with a finger. [origin] is where its first card sat, in root pixels. */
data class DragState(val source: Source, val origin: Offset, val translation: Offset, val target: Pile?)

/** Everything the board screen tracks while you play: selection, hint, message and dragging. */
class BoardController(val game: GameStore) {
    var selected by mutableStateOf<Source?>(null)
    var suggested by mutableStateOf<Pile?>(null)
    var message by mutableStateOf("")
    var drag by mutableStateOf<DragState?>(null)

    var layout = BoardLayout(60.dp, 3.dp, 7)
    var density = Density(1f)
    var boardCoords: LayoutCoordinates? = null
    var topCoords: LayoutCoordinates? = null
    var contentCoords: LayoutCoordinates? = null

    private fun LayoutCoordinates?.root(): Offset =
        if (this != null && isAttached) positionInRoot() else Offset.Zero

    fun boardOrigin(): Offset = boardCoords.root()

    fun clear() {
        selected = null
        suggested = null
        message = ""
    }

    // region Moves

    /**
     * Tapping a card performs its move: to a foundation when possible, otherwise onto a column, an
     * empty column or a free cell.
     */
    fun tap(source: Source) {
        clear()
        game.userTouched()
        val board = game.board
        if (!board.canSelect(source)) return
        if (source.pile is Pile.Foundation) {
            message = "Touch and drag a foundation card to move it back onto a column."
            return
        }
        val card = board[source.pile][source.row]
        val destination = board.bestDestination(source)
        if (destination == null) {
            message = "No move available for ${card.label}${card.suit.symbol}."
            return
        }
        game.move(source, destination)
    }

    fun deal() {
        clear()
        game.userTouched()
        if (!game.deal()) {
            message = if (game.board.stock.isEmpty()) "No cards left to deal." else "Fill each empty column before dealing."
        }
    }

    fun hint() {
        clear()
        val board = game.board
        val useful = board.usefulHint()
        if (useful != null) {
            show(useful.first, useful.second)
        } else if (board.canDeal) {
            message = if (board.stock.isEmpty()) "Tap the stock to recycle the waste." else "Tap the stock to deal."
        } else {
            val any = board.hint()
            if (any != null) show(any.first, any.second) else message = "No move found. Try Undo or start a new deal."
        }
    }

    private fun show(source: Source, destination: Pile) {
        selected = source
        suggested = destination
        val card = game.board[source.pile][source.row]
        message = "Move ${card.label}${card.suit.symbol} from ${source.pile.displayName} to ${destination.displayName}."
    }

    // endregion

    // region Dragging

    fun isLifted(source: Source): Boolean {
        val d = drag ?: return false
        return d.source.pile == source.pile && source.row >= d.source.row
    }

    /** The card lifts as soon as it is held, which also pauses scrolling. */
    fun lift(source: Source, origin: Offset) {
        game.userTouched()
        if (!game.board.canSelect(source)) return
        if (drag == null) clear()
        drag = DragState(source, origin, Offset.Zero, null)
    }

    fun dragTo(source: Source, origin: Offset, translation: Offset, local: Offset) {
        if (!game.board.canSelect(source)) return
        val target = dropTarget(origin + local)?.takeIf { game.board.canMove(source, it) }
        drag = DragState(source, origin, translation, target)
    }

    fun drop(source: Source, origin: Offset, translation: Offset, local: Offset) {
        val dragged = translation.getDistance() > with(density) { 6.dp.toPx() }
        drag = null
        // Held and released without moving: same as a tap.
        if (!dragged || !game.board.canSelect(source)) {
            tap(source)
            return
        }
        val target = dropTarget(origin + local)
        if (target == null || !game.board.canMove(source, target)) {
            message = "That card can’t go there."
            return
        }
        game.move(source, target)
    }

    fun cancelDrag() {
        drag = null
    }

    /** The pile under a point (in root pixels), or null. */
    private fun dropTarget(point: Offset): Pile? {
        val top = topCoords.root()
        val topHeight = topCoords?.size?.height?.toFloat() ?: 0f
        val content = contentCoords.root()
        val gap = with(density) { layout.gap.toPx() }
        val pitch = with(density) { layout.pitch.toPx() }
        val kind = game.selectedGame
        if (point.y < top.y + topHeight + gap) {
            if (kind == GameKind.SPIDER) return null
            val slot = floor((point.x - top.x + gap / 2) / pitch).toInt()
            return when {
                slot in 0..3 -> Pile.Foundation(slot)
                slot in 4..7 && kind == GameKind.FREECELL -> Pile.Cell(slot - 4)
                else -> null
            }
        }
        val column = floor((point.x - content.x + gap / 2) / pitch).toInt()
        return if (column in 0 until layout.columns) Pile.Tableau(column) else null
    }

    /** Where a pile's top-left corner is, in root pixels. */
    fun originOf(pile: Pile, row: Int): Offset {
        val top = topCoords.root()
        val content = contentCoords.root()
        val pitch = with(density) { layout.pitch.toPx() }
        val step = with(density) { layout.step.toPx() }
        return when (pile) {
            is Pile.Tableau -> Offset(content.x + pile.index * pitch, content.y + row * step)
            is Pile.Foundation -> Offset(top.x + pile.index * pitch, top.y)
            is Pile.Cell -> Offset(top.x + (4 + pile.index) * pitch, top.y)
            Pile.Waste -> Offset(top.x + 5 * pitch, top.y)
        }
    }

    // endregion
}

/**
 * Touch and hold briefly, then drag. A quick swipe on a column still scrolls the board. Without a
 * hold, lifting the finger is a tap. [holdMillis] is how long a card must be held before it lifts.
 */
private fun Modifier.cardGestures(
    holdMillis: Long,
    canDrag: () -> Boolean,
    onTouch: () -> Unit,
    onTap: () -> Unit,
    onLift: () -> Unit,
    onDrag: (translation: Offset, local: Offset) -> Unit,
    onDrop: (translation: Offset, local: Offset) -> Unit,
    onCancel: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onTouch()
        val slop = viewConfiguration.touchSlop
        val draggable = canDrag()
        var outcome = 0 // 1 = finger lifted, 2 = gave up (a scroll took over, or it moved too far)
        val finished = withTimeoutOrNull(if (draggable) holdMillis else 10_000L) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || change.isConsumed) {
                    outcome = 2
                    return@withTimeoutOrNull
                }
                if (!change.pressed) {
                    change.consume()
                    outcome = 1
                    return@withTimeoutOrNull
                }
                if ((change.position - down.position).getDistance() > slop) {
                    outcome = 2
                    return@withTimeoutOrNull
                }
            }
        }
        if (finished != null) {
            // Finished before the hold time ran out.
            if (outcome == 1) onTap()
            return@awaitEachGesture
        }
        if (!draggable) return@awaitEachGesture

        onLift()
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                onCancel()
                return@awaitEachGesture
            }
            val translation = change.position - down.position
            change.consume()
            if (!change.pressed) {
                onDrop(translation, change.position)
                return@awaitEachGesture
            }
            onDrag(translation, change.position)
        }
    }
}

private val Orange = Color(0xFFFF9500)

// maxWidth/maxHeight are used below; lint misreports the scope as unused.
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun GameBoard(game: GameStore) {
    val c = remember(game) { BoardController(game) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var confirmNew by remember { mutableStateOf(false) }
    var showNoMoves by remember { mutableStateOf(false) }
    var lastStatus by remember { mutableStateOf(game.moveStatus) }
    val board = game.board

    // Warn when the useful moves run low.
    LaunchedEffect(game.moveStatus) {
        val status = game.moveStatus
        if (status != lastStatus) {
            lastStatus = status
            if (status != MoveStatus.PLENTY) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (status == MoveStatus.NONE) showNoMoves = true
            }
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned { c.boardCoords = it }
    ) {
        val count = board.tableau.size
        val gap = if (maxWidth < 500.dp) 3.dp else 6.dp
        val width = minOf((maxWidth - 12.dp - gap * (count - 1)) / count, minOf(88.dp, maxOf(38.dp, maxHeight * 0.13f)))
        val layout = BoardLayout(width, gap, count)
        c.layout = layout
        c.density = density

        Column(
            Modifier.fillMaxSize().padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Toolbar(game) { confirmNew = true }
            // Foundations sit top left and the stock top right.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Row(
                    Modifier.width(layout.rowWidth).onGloballyPositioned { c.topCoords = it },
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    TopRow(c, layout)
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState(), enabled = c.drag == null)
                    .padding(vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier.width(layout.rowWidth).onGloballyPositioned { c.contentCoords = it },
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.Top,
                ) {
                    for (col in board.tableau.indices) TableauColumn(c, col, layout)
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                MoveBanner(game.moveStatus)
                if (c.message.isNotEmpty()) {
                    Text(
                        c.message, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            BottomBar(game, c)
        }

        FloatingCards(c, layout)
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text("Start a new deal?") },
            text = {
                Text(
                    if (game.moves > 0) "This will replace your current ${game.selectedGame.title} deal and count it as a loss."
                    else "This will replace your current ${game.selectedGame.title} deal."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmNew = false; c.clear(); game.newGame() }) { Text("New Deal") }
            },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Cancel") } },
        )
    }
    if (showNoMoves) {
        AlertDialog(
            onDismissRequest = { showNoMoves = false },
            title = { Text("No moves left") },
            text = { Text("I can’t find any useful moves left in this deal. You can undo a few moves or start a new deal.") },
            confirmButton = {
                TextButton(onClick = { showNoMoves = false; c.clear(); game.newGame() }) { Text("New Deal") }
            },
            dismissButton = {
                Row {
                    if (game.history.isNotEmpty()) {
                        TextButton(onClick = { showNoMoves = false; c.clear(); game.undo() }) { Text("Undo") }
                    }
                    TextButton(onClick = { showNoMoves = false }) { Text("Keep Looking") }
                }
            },
        )
    }
}

private val barButtonColors
    @Composable get() = ButtonDefaults.filledTonalButtonColors(
        containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White,
    )

@Composable
private fun Toolbar(game: GameStore, onNew: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { game.setMenuVisible(true) }, colors = barButtonColors) {
            Icon(Icons.Filled.Menu, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  Menu")
        }
        Text(
            game.selectedGame.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
        )
        FilledTonalButton(onClick = onNew, colors = barButtonColors) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  New")
        }
    }
}

@Composable
private fun BottomBar(game: GameStore, c: BoardController) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1.seconds)
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    val iconColors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White,
        disabledContainerColor = Color.Black.copy(alpha = 0.25f), disabledContentColor = Color.White.copy(alpha = 0.35f),
    )
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Moves ${game.moves}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Schedule, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Text(
                " " + formatDuration(game.elapsed(now)), color = Color.White, fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        FilledTonalIconButton(onClick = { game.showHelp = true }, colors = iconColors) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Help")
        }
        FilledTonalIconButton(
            onClick = { c.clear(); game.undo() }, enabled = game.history.isNotEmpty(), colors = iconColors,
        ) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
        }
        FilledTonalIconButton(onClick = { c.hint() }, colors = iconColors) {
            Icon(Icons.Filled.Lightbulb, contentDescription = "Hint")
        }
        Box {
            FilledTonalIconButton(onClick = { menuOpen = true }, colors = iconColors) {
                Icon(Icons.Filled.Settings, contentDescription = "Options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Background Photo") }, onClick = {
                    menuOpen = false
                    game.showBackgrounds = true
                })
                DropdownMenuItem(text = { Text("Win/Loss Stats") }, onClick = {
                    menuOpen = false
                    game.showStats = true
                })
            }
        }
    }
}

/** The warning shown when only one useful move is left, or none. */
@Composable
private fun MoveBanner(status: MoveStatus) {
    when (status) {
        MoveStatus.PLENTY -> {}
        MoveStatus.LAST -> Banner("Only 1 useful move left", Icons.Filled.Warning, Color(0xFFFF9500))
        MoveStatus.NONE -> Banner("No moves left. Undo or start a new deal.", Icons.Filled.Block, Color(0xFFE5262B))
    }
}

@Composable
private fun Banner(text: String, icon: ImageVector, color: Color) {
    Row(
        Modifier
            .background(color.copy(alpha = 0.92f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text("  $text", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// region Piles

@Composable
private fun RowScope.TopRow(c: BoardController, layout: BoardLayout) {
    val game = c.game
    when (game.selectedGame) {
        GameKind.KLONDIKE -> {
            Foundations(c, layout)
            Spacer(Modifier.width(layout.width))
            Slot(c, Pile.Waste, layout, "⋯")
            StockButton(c, layout)
        }
        GameKind.FREECELL -> {
            Foundations(c, layout)
            for (cell in game.board.cells.indices) Slot(c, Pile.Cell(cell), layout, "◇")
        }
        GameKind.SPIDER -> {
            Column(Modifier.weight(1f)) {
                Text("${game.board.completedRuns} / 8 runs", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("${game.board.stock.size / 10} deals left", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            StockButton(c, layout)
        }
    }
}

@Composable
private fun Foundations(c: BoardController, layout: BoardLayout) {
    for (index in Suit.entries.indices) Slot(c, Pile.Foundation(index), layout, Suit.entries[index].symbol)
}

@Composable
private fun StockButton(c: BoardController, layout: BoardLayout) {
    val game = c.game
    val stock = game.board.stock
    val label = if (stock.isEmpty() && game.selectedGame == GameKind.KLONDIKE) "Recycle waste" else "Deal from stock"
    Box(
        Modifier
            .size(layout.width, layout.height)
            .clickable(role = Role.Button) { c.deal() }
            .semantics { contentDescription = "$label, ${stock.size} cards" },
    ) {
        val card = stock.lastOrNull()
        if (card != null) {
            CardView(card, layout.width)
        } else {
            EmptySlot(if (game.selectedGame == GameKind.KLONDIKE) "↻" else "–", false, layout)
        }
    }
}

@Composable
private fun TableauColumn(c: BoardController, col: Int, layout: BoardLayout) {
    val cards = c.game.board.tableau[col]
    Box(Modifier.size(layout.width, layout.height + layout.step * max(0, cards.size - 1))) {
        if (cards.isEmpty()) {
            Slot(c, Pile.Tableau(col), layout, "–")
        } else {
            cards.forEachIndexed { row, card ->
                key(card.id) {
                    CardSlot(c, card, Source(Pile.Tableau(col), row), layout, Modifier.offset(y = layout.step * row))
                }
            }
        }
    }
}

@Composable
private fun Slot(c: BoardController, pile: Pile, layout: BoardLayout, symbol: String) {
    val cards = c.game.board[pile]
    val card = cards.lastOrNull()
    if (card != null) {
        CardSlot(c, card, Source(pile, cards.size - 1), layout)
    } else {
        EmptySlot(symbol, c.suggested == pile, layout, Modifier.semantics { contentDescription = "Empty ${pile.displayName}" })
    }
}

@Composable
private fun EmptySlot(symbol: String, highlighted: Boolean, layout: BoardLayout, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier
            .size(layout.width, layout.height)
            .background(Color.Black.copy(alpha = 0.25f), shape)
            .border(BorderStroke(if (highlighted) 3.dp else 1.dp, if (highlighted) Orange else Color.White.copy(alpha = 0.55f)), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = Color.White.copy(alpha = 0.8f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** A card you can tap (it moves itself) or touch and drag to a destination of your choice. */
@Composable
private fun CardSlot(c: BoardController, card: Card, source: Source, layout: BoardLayout, modifier: Modifier = Modifier) {
    val game = c.game
    val currentSource by rememberUpdatedState(source)
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val highlighted = c.selected == source || (c.suggested == source.pile && card == game.board[source.pile].lastOrNull())
    val inColumn = source.pile is Pile.Tableau
    val description =
        "${if (card.faceUp) "${card.label} of ${card.suit.name.lowercase()}" else "Face down"}, ${source.pile.displayName}"

    fun origin(): Offset = coords[0]?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero

    Box(
        modifier
            .size(layout.width, layout.height)
            .alpha(if (c.isLifted(source)) 0f else 1f)
            .onGloballyPositioned { coords[0] = it }
            .cardGestures(
                holdMillis = if (inColumn) 110L else 10L,
                canDrag = { game.board.canSelect(currentSource) },
                onTouch = { game.userTouched() },
                onTap = { c.tap(currentSource) },
                onLift = { c.lift(currentSource, origin()) },
                onDrag = { translation, local -> c.dragTo(currentSource, c.drag?.origin ?: origin(), translation, local) },
                onDrop = { translation, local -> c.drop(currentSource, c.drag?.origin ?: origin(), translation, local) },
                onCancel = { c.cancelDrag() },
            )
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    c.tap(currentSource)
                    true
                }
            }
    ) {
        CardView(card, layout.width)
        if (highlighted) {
            Box(Modifier.size(layout.width, layout.height).border(3.dp, Orange, RoundedCornerShape(5.dp)))
        }
    }
}

// endregion

/** The cards in flight, drawn above everything so they are never clipped by the scroll view. */
@Composable
private fun FloatingCards(c: BoardController, layout: BoardLayout) {
    val drag = c.drag ?: return
    val board = c.game.board
    val pile = board[drag.source.pile]
    if (pile.size <= drag.source.row) return
    val run = pile.subList(drag.source.row, pile.size).toList()
    val boardOrigin = c.boardOrigin()

    drag.target?.let { target ->
        val o = c.originOf(target, max(0, board[target].size - 1))
        Box(
            Modifier
                .zIndex(1f)
                .offset { IntOffset((o.x - boardOrigin.x).roundToInt(), (o.y - boardOrigin.y).roundToInt()) }
                .size(layout.width, layout.height)
                .border(3.dp, Orange, RoundedCornerShape(5.dp))
        )
    }
    Box(
        Modifier
            .zIndex(2f)
            .offset {
                val p = drag.origin + drag.translation - boardOrigin
                IntOffset(p.x.roundToInt(), p.y.roundToInt())
            }
            .size(layout.width, layout.height + layout.step * (run.size - 1))
    ) {
        run.forEachIndexed { index, card ->
            CardView(card, layout.width, Modifier.offset(y = layout.step * index))
        }
    }
}
