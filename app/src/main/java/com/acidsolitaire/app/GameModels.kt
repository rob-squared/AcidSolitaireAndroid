package com.acidsolitaire.app

import kotlin.random.Random

enum class GameKind(val title: String, val key: String, val subtitle: String) {
    KLONDIKE("Solitaire", "klondike", "Deal One / Deal Three"),
    FREECELL("Freecell", "freecell", "Classic Freecell"),
    SPIDER("Spider", "spider", "One / Two / Four Suit"),
}

/** Declared in the same order as the foundations, left to right. */
enum class Suit(val symbol: String, val isRed: Boolean) {
    CLUBS("♣", false),
    DIAMONDS("♦", true),
    HEARTS("♥", true),
    SPADES("♠", false),
}

data class Card(val id: Int, val rank: Int, val suit: Suit, val faceUp: Boolean = false) {
    val label: String
        get() = when (rank) {
            1 -> "A"
            11 -> "J"
            12 -> "Q"
            13 -> "K"
            else -> rank.toString()
        }
}

sealed interface Pile {
    data class Tableau(val index: Int) : Pile
    data class Foundation(val index: Int) : Pile
    data class Cell(val index: Int) : Pile
    data object Waste : Pile

    val displayName: String
        get() = when (this) {
            is Tableau -> "column ${index + 1}"
            is Foundation -> "${Suit.entries[index].name.lowercase()} foundation"
            is Cell -> "free cell ${index + 1}"
            Waste -> "waste"
        }
}

data class Source(val pile: Pile, val row: Int)

sealed interface AutoStep {
    data class Move(val source: Source, val destination: Pile) : AutoStep
    data object Deal : AutoStep
}

/** One game in progress. Cards are immutable, so [copy] only needs to copy the pile lists. */
class Board(
    val kind: GameKind,
    val seed: Int,
    var tableau: MutableList<MutableList<Card>>,
    var stock: MutableList<Card> = mutableListOf(),
    var waste: MutableList<Card> = mutableListOf(),
    var foundations: MutableList<MutableList<Card>> = MutableList(4) { mutableListOf() },
    var cells: MutableList<MutableList<Card>> = MutableList(4) { mutableListOf() },
    var completedRuns: Int = 0,
    var moves: Int = 0,
) {
    companion object {
        fun create(kind: GameKind, seed: Int, spiderSuits: Int = 1): Board {
            val sizes = when (kind) {
                GameKind.KLONDIKE -> (1..7).toList()
                GameKind.FREECELL -> listOf(7, 7, 7, 7, 6, 6, 6, 6)
                GameKind.SPIDER -> listOf(6, 6, 6, 6, 5, 5, 5, 5, 5, 5)
            }
            val suits = when {
                kind == GameKind.SPIDER && spiderSuits == 1 -> listOf(Suit.SPADES)
                kind == GameKind.SPIDER && spiderSuits == 2 -> listOf(Suit.HEARTS, Suit.SPADES)
                else -> Suit.entries.toList()
            }
            val total = if (kind == GameKind.SPIDER) 104 else 52
            val deck = MutableList(total) { index ->
                Card(id = index, rank = index % 13 + 1, suit = suits[index / 13 % suits.size])
            }
            deck.shuffle(Random(seed.toLong()))
            val tableau = sizes.map { count ->
                MutableList(count) { row ->
                    val card = deck.removeAt(deck.lastIndex)
                    card.copy(faceUp = kind == GameKind.FREECELL || row == count - 1)
                }
            }.toMutableList()
            return Board(kind, seed, tableau, stock = deck)
        }
    }

    fun copy(): Board = Board(
        kind, seed,
        tableau.map { it.toMutableList() }.toMutableList(),
        stock.toMutableList(),
        waste.toMutableList(),
        foundations.map { it.toMutableList() }.toMutableList(),
        cells.map { it.toMutableList() }.toMutableList(),
        completedRuns, moves,
    )

    val won: Boolean
        get() = if (kind == GameKind.SPIDER) completedRuns == 8 else foundations.all { it.size == 13 }

    operator fun get(pile: Pile): MutableList<Card> = when (pile) {
        is Pile.Tableau -> tableau[pile.index]
        is Pile.Foundation -> foundations[pile.index]
        is Pile.Cell -> cells[pile.index]
        Pile.Waste -> waste
    }

    fun canSelect(source: Source): Boolean {
        val pile = this[source.pile]
        if (source.row !in pile.indices || !pile[source.row].faceUp) return false
        return when (source.pile) {
            is Pile.Foundation -> kind != GameKind.SPIDER && source.row == pile.size - 1
            is Pile.Cell, Pile.Waste -> source.row == pile.size - 1
            is Pile.Tableau -> (source.row until pile.size - 1).all { i ->
                val upper = pile[i]
                val lower = pile[i + 1]
                lower.faceUp && upper.rank == lower.rank + 1 &&
                    (if (kind == GameKind.SPIDER) upper.suit == lower.suit else upper.suit.isRed != lower.suit.isRed)
            }
        }
    }

    fun canMove(source: Source, destination: Pile): Boolean {
        if (source.pile == destination || !canSelect(source)) return false
        val pile = this[source.pile]
        val run = pile.subList(source.row, pile.size)
        val first = run[0]
        val top = this[destination].lastOrNull()
        return when (destination) {
            Pile.Waste -> false
            is Pile.Cell -> kind == GameKind.FREECELL && top == null && run.size == 1
            is Pile.Foundation ->
                kind != GameKind.SPIDER && run.size == 1 &&
                    first.suit == Suit.entries[destination.index] && first.rank == (top?.rank ?: 0) + 1
            is Pile.Tableau -> {
                val capacityOk = if (kind == GameKind.FREECELL) {
                    val spareColumns = tableau.indices.count { it != destination.index && tableau[it].isEmpty() }
                    val capacity = (1 + cells.count { it.isEmpty() }) * (1 shl spareColumns)
                    run.size <= capacity
                } else {
                    true
                }
                if (!capacityOk) {
                    false
                } else if (top == null) {
                    kind != GameKind.KLONDIKE || first.rank == 13
                } else {
                    top.rank == first.rank + 1 && (kind == GameKind.SPIDER || first.suit.isRed != top.suit.isRed)
                }
            }
        }
    }

    fun move(source: Source, destination: Pile): Boolean {
        if (!canMove(source, destination)) return false
        val from = this[source.pile]
        val run = ArrayList(from.subList(source.row, from.size))
        from.subList(source.row, from.size).clear()
        this[destination].addAll(run)
        (source.pile as? Pile.Tableau)?.let { settle(it.index) }
        (destination as? Pile.Tableau)?.let { settle(it.index) }
        moves += 1
        return true
    }

    /** Turns up the bottom card and, in Spider, removes a finished king-to-ace run. */
    private fun settle(col: Int) {
        val column = tableau[col]
        while (column.isNotEmpty()) {
            column[column.lastIndex] = column.last().copy(faceUp = true)
            if (kind != GameKind.SPIDER || column.size < 13) return
            val tail = column.subList(column.size - 13, column.size)
            val suit = tail[0].suit
            val complete = tail.withIndex().all { (offset, card) ->
                card.faceUp && card.rank == 13 - offset && card.suit == suit
            }
            if (!complete) return
            tail.clear()
            completedRuns += 1
        }
    }

    val canDeal: Boolean
        get() = when (kind) {
            GameKind.KLONDIKE -> stock.isNotEmpty() || waste.isNotEmpty()
            GameKind.SPIDER -> stock.size >= tableau.size && tableau.none { it.isEmpty() }
            GameKind.FREECELL -> false
        }

    fun deal(count: Int): Boolean {
        if (!canDeal) return false
        if (kind == GameKind.SPIDER) {
            for (col in tableau.indices) {
                val card = stock.removeAt(stock.lastIndex)
                tableau[col].add(card.copy(faceUp = true))
                settle(col)
            }
        } else if (stock.isEmpty()) {
            stock = waste.reversed().map { it.copy(faceUp = false) }.toMutableList()
            waste.clear()
        } else {
            repeat(minOf(count, stock.size)) {
                val card = stock.removeAt(stock.lastIndex)
                waste.add(card.copy(faceUp = true))
            }
        }
        moves += 1
        return true
    }

    /** The first legal move, ignoring whether it is worthwhile. See [usefulHint] for that. */
    fun hint(): Pair<Source, Pile>? {
        val columns: List<Pile> = tableau.indices.map { Pile.Tableau(it) }
        val sources = mutableListOf<Pile>()
        val destinations = mutableListOf<Pile>()
        if (kind != GameKind.SPIDER) {
            destinations += foundations.indices.map { Pile.Foundation(it) }
            sources += Pile.Waste
        }
        sources += columns
        destinations += columns
        if (kind == GameKind.FREECELL) {
            sources += cells.indices.map { Pile.Cell(it) }
            destinations += cells.indices.map { Pile.Cell(it) }
        }
        for (dest in destinations) {
            for (pile in sources) {
                for (row in this[pile].indices) {
                    // Moving a whole pile into an equivalent empty slot makes no progress.
                    if (this[dest].isEmpty() && row == 0) {
                        if (dest is Pile.Tableau && pile is Pile.Tableau) continue
                        if (dest is Pile.Cell && pile is Pile.Cell) continue
                    }
                    val source = Source(pile, row)
                    if (canMove(source, dest)) return source to dest
                }
            }
        }
        return null
    }

    /**
     * Where tapping [source] should send it, or null when it has no legal move. Preference:
     * foundation, then a column with cards (same suit first in Spider), then an empty column,
     * then a free cell.
     */
    fun bestDestination(source: Source): Pile? {
        if (!canSelect(source)) return null
        var fromWholeColumn = false
        when (source.pile) {
            is Pile.Foundation -> return null
            is Pile.Tableau -> fromWholeColumn = source.row == 0
            else -> {}
        }
        val card = this[source.pile][source.row]
        val candidates = mutableListOf<Pile>()
        if (kind != GameKind.SPIDER) candidates += foundations.indices.map { Pile.Foundation(it) }
        val occupied = tableau.indices.filter { tableau[it].isNotEmpty() }
        if (kind == GameKind.SPIDER) {
            candidates += occupied.filter { tableau[it].last().suit == card.suit }.map { Pile.Tableau(it) }
            candidates += occupied.filter { tableau[it].last().suit != card.suit }.map { Pile.Tableau(it) }
        } else {
            candidates += occupied.map { Pile.Tableau(it) }
        }
        // Moving a whole column into an empty column makes no progress.
        if (!fromWholeColumn) {
            candidates += tableau.indices.filter { tableau[it].isEmpty() }.map { Pile.Tableau(it) }
        }
        if (kind == GameKind.FREECELL && source.pile !is Pile.Cell) {
            candidates += cells.indices.map { Pile.Cell(it) }
        }
        return candidates.firstOrNull { canMove(source, it) }
    }

    /** The next card (waste, cell or column bottom) that can go to a foundation. */
    fun nextFoundationMove(): Pair<Source, Pile>? {
        val piles = mutableListOf<Pile>(Pile.Waste)
        piles += cells.indices.map { Pile.Cell(it) }
        piles += tableau.indices.map { Pile.Tableau(it) }
        for (pile in piles) {
            val cards = this[pile]
            if (cards.isEmpty()) continue
            val source = Source(pile, cards.size - 1)
            for (index in foundations.indices) {
                if (canMove(source, Pile.Foundation(index))) return source to Pile.Foundation(index)
            }
        }
        return null
    }

    /**
     * When no card is face down and the rest of the game is just sending cards to the
     * foundations, returns the steps that finish it. Returns null otherwise.
     */
    fun autoCompletePlan(draw: Int): List<AutoStep>? {
        if (kind == GameKind.SPIDER || won || tableau.any { col -> col.any { !it.faceUp } }) return null
        val sim = copy()
        val steps = mutableListOf<AutoStep>()
        var idleDeals = 0
        while (!sim.won) {
            val next = sim.nextFoundationMove()
            if (next != null) {
                sim.move(next.first, next.second)
                steps += AutoStep.Move(next.first, next.second)
                idleDeals = 0
            } else if (sim.kind == GameKind.KLONDIKE && sim.canDeal &&
                idleDeals < 2 * (sim.stock.size + sim.waste.size) + 2
            ) {
                sim.deal(draw)
                steps += AutoStep.Deal
                idleDeals += 1
            } else {
                return null
            }
            if (steps.size > 600) return null
        }
        return steps
    }
}

/** Wins and losses for one game. A deal is a loss when it is replaced after at least one move. */
data class GameStats(
    val wins: Int = 0,
    val losses: Int = 0,
    val bestTime: Double? = null,
    /** Every second the game spent on screen with the clock running, won or not. */
    val totalTime: Double = 0.0,
) {
    val played: Int get() = wins + losses
    val winRate: Double? get() = if (played == 0) null else wins.toDouble() / played
}

fun formatDuration(seconds: Double): String {
    val total = maxOf(0, Math.round(seconds).toInt())
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}

/** How many useful moves the player has left. */
enum class MoveStatus { PLENTY, LAST, NONE }

// region Useful moves

/**
 * Counting "useful" moves. A legal move is not always a useful one: shuffling a king between two
 * empty columns, or lifting a card off the card it already builds on, changes nothing. The warnings
 * for "only one move left" and "no moves left" count only moves that make progress.
 */
private fun Board.linked(upper: Card, lower: Card): Boolean =
    upper.rank == lower.rank + 1 &&
        (if (kind == GameKind.SPIDER) upper.suit == lower.suit else upper.suit.isRed != lower.suit.isRed)

/** Every legal move that makes progress. Symmetric destinations count once; foundation sources never. */
fun Board.usefulMoves(): List<Pair<Source, Pile>> {
    val firstEmptyColumn = tableau.indices.firstOrNull { tableau[it].isEmpty() }
    val firstEmptyCell = cells.indices.firstOrNull { cells[it].isEmpty() }

    val sources = mutableListOf<Source>()
    if (kind == GameKind.KLONDIKE && waste.isNotEmpty()) sources += Source(Pile.Waste, waste.size - 1)
    for (i in cells.indices) if (cells[i].isNotEmpty()) sources += Source(Pile.Cell(i), 0)
    for (c in tableau.indices) {
        for (r in tableau[c].indices) {
            val s = Source(Pile.Tableau(c), r)
            if (canSelect(s)) sources += s
        }
    }

    val destinations = mutableListOf<Pile>()
    if (kind != GameKind.SPIDER) destinations += foundations.indices.map { Pile.Foundation(it) }
    destinations += tableau.indices.filter { tableau[it].isNotEmpty() || it == firstEmptyColumn }
        .map { Pile.Tableau(it) }
    if (kind == GameKind.FREECELL && firstEmptyCell != null) destinations += Pile.Cell(firstEmptyCell)

    val result = mutableListOf<Pair<Source, Pile>>()
    for (s in sources) {
        for (d in destinations) {
            if (canMove(s, d) && isUseful(s, d)) result += s to d
        }
    }
    return result
}

private fun Board.isUseful(source: Source, destination: Pile): Boolean {
    if (destination is Pile.Foundation) return true
    val from = source.pile as? Pile.Tableau ?: return true // waste or cell onto a column
    val cards = tableau[from.index]
    if (destination is Pile.Cell) return cellMoveHelps(from.index, source.row)

    val intoEmptyColumn = destination is Pile.Tableau && tableau[destination.index].isEmpty()
    // Moving a whole column empties it, unless it only lands in another empty column.
    if (source.row == 0) return !intoEmptyColumn

    val card = cards[source.row]
    val above = cards[source.row - 1]
    if (!above.faceUp) return true // turning over a face-down card is always progress
    if (linked(above, card)) return false // lifting a card off the card it builds on undoes work
    if (kind == GameKind.SPIDER && !intoEmptyColumn && destination is Pile.Tableau) {
        return tableau[destination.index].lastOrNull()?.suit == card.suit
    }
    return true
}

/** A Freecell card parked in a cell only helps if the card it uncovers can then move on. */
private fun Board.cellMoveHelps(column: Int, row: Int): Boolean {
    if (row <= 0) return false
    val card = tableau[column][row]
    val above = tableau[column][row - 1]
    if (linked(above, card)) return false
    val after = copy()
    after.tableau[column].removeAt(after.tableau[column].lastIndex)
    val exposed = Source(Pile.Tableau(column), row - 1)
    if (foundations.indices.any { after.canMove(exposed, Pile.Foundation(it)) }) return true
    return tableau.indices.any { t ->
        t != column && tableau[t].isNotEmpty() && after.canMove(exposed, Pile.Tableau(t))
    }
}

/**
 * How many cards in the Klondike stock and waste can be played when they come to the top. This
 * runs through every card in the stock (and the waste, after it is turned over again), so each
 * playable card counts as its own move. The card already showing on the waste is not counted here
 * because [usefulMoves] already includes it.
 */
fun Board.playableStockCards(draw: Int): Int {
    if (kind != GameKind.KLONDIKE || !canDeal) return 0
    val sim = copy()
    val showing = waste.lastOrNull()?.id
    val playable = mutableSetOf<Int>()
    for (i in 0 until stock.size + waste.size + 2) {
        if (!sim.deal(draw)) break
        val top = sim.waste.lastOrNull() ?: continue
        if (top.id == showing) continue
        val source = Source(Pile.Waste, sim.waste.size - 1)
        val piles = sim.foundations.indices.map<Int, Pile> { Pile.Foundation(it) } +
            sim.tableau.indices.map<Int, Pile> { Pile.Tableau(it) }
        if (piles.any { sim.canMove(source, it) }) playable += top.id
    }
    return playable.size
}

/** True when dealing would bring up a playable card (Klondike), or when a Spider deal is allowed. */
fun Board.dealIsUseful(draw: Int): Boolean = when (kind) {
    GameKind.FREECELL -> false
    GameKind.SPIDER -> canDeal
    GameKind.KLONDIKE -> playableStockCards(draw) > 0
}

/**
 * Every move that can be made right now: each useful move among the cards in the columns, free
 * cells and waste, plus each stock card that could be played once it is turned up. A Spider deal
 * is not a card move, so it is not counted here.
 */
fun Board.usefulMoveCount(draw: Int): Int = usefulMoves().size + playableStockCards(draw)

/** A suggested move: a foundation move if there is one, otherwise the first useful move. */
fun Board.usefulHint(): Pair<Source, Pile>? {
    val moves = usefulMoves()
    return moves.firstOrNull { it.second is Pile.Foundation } ?: moves.firstOrNull()
}

// endregion
