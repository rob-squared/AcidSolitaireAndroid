package com.acidsolitaire.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RulesTest {
    private var nextId = 1000
    private fun up(rank: Int, suit: Suit) = Card(nextId++, rank, suit, faceUp = true)
    private fun down(rank: Int, suit: Suit) = Card(nextId++, rank, suit, faceUp = false)

    private fun emptyKlondike(): Board {
        val board = Board.create(GameKind.KLONDIKE, 1)
        board.tableau = MutableList(7) { mutableListOf() }
        board.stock = mutableListOf()
        board.waste = mutableListOf()
        board.foundations = MutableList(4) { mutableListOf() }
        return board
    }

    @Test
    fun foundationCardsCanBeDraggedBackInKlondikeAndFreecell() {
        for (kind in listOf(GameKind.KLONDIKE, GameKind.FREECELL)) {
            val board = Board.create(kind, 4)
            board.tableau = MutableList(board.tableau.size) { mutableListOf() }
            board.tableau[0] = mutableListOf(up(6, Suit.SPADES))
            board.foundations[0] = mutableListOf(up(1, Suit.CLUBS), up(2, Suit.CLUBS), up(3, Suit.CLUBS))
            board.foundations[3] = mutableListOf(up(5, Suit.HEARTS))
            val source = Source(Pile.Foundation(3), 0)
            assertTrue(board.canSelect(source))
            assertTrue(board.move(source, Pile.Tableau(0)))
            assertTrue(board.foundations[3].isEmpty())
        }
        val spider = Board.create(GameKind.SPIDER, 4)
        assertFalse(spider.canSelect(Source(Pile.Foundation(0), 0)))
    }

    @Test
    fun everyPlayableStockCardCountsAsItsOwnMove() {
        val board = emptyKlondike()
        board.tableau[0] = mutableListOf(up(9, Suit.SPADES))
        board.tableau[1] = mutableListOf(up(10, Suit.CLUBS))
        // Two of the three stock cards can be played onto a column; the 3 of diamonds cannot.
        board.stock = mutableListOf(up(3, Suit.DIAMONDS), up(9, Suit.DIAMONDS), up(8, Suit.HEARTS))
        assertEquals(2, board.playableStockCards(1))
        assertEquals(2, board.usefulMoveCount(1))

        // A column move that is available as well adds one more.
        board.tableau[2] = mutableListOf(up(1, Suit.CLUBS))
        assertEquals(3, board.usefulMoveCount(1))
    }

    @Test
    fun noPlayableCardsMeansNoMoves() {
        val board = emptyKlondike()
        board.tableau[0] = mutableListOf(up(9, Suit.SPADES))
        board.stock = mutableListOf(up(3, Suit.DIAMONDS))
        assertEquals(0, board.playableStockCards(1))
    }

    @Test
    fun statsArchiveRoundTrips() {
        val stats = mapOf(
            GameKind.KLONDIKE to GameStats(wins = 3, losses = 2, bestTime = 95.5, totalTime = 1234.0),
            GameKind.SPIDER to GameStats(wins = 0, losses = 1, bestTime = null, totalTime = 60.0),
        )
        val parsed = StatsArchive.parse(StatsArchive.xml(stats).toByteArray(Charsets.UTF_8))
        assertEquals(stats[GameKind.KLONDIKE], parsed[GameKind.KLONDIKE])
        assertEquals(stats[GameKind.SPIDER], parsed[GameKind.SPIDER])
        assertNull(parsed[GameKind.SPIDER]?.bestTime)
        assertEquals(0, parsed[GameKind.FREECELL]?.played ?: 0)
    }

    @Test
    fun statsArchiveRejectsOtherFilesAndImpossibleNumbers() {
        val notStats = "<html><body/></html>".toByteArray()
        val negative = "<${StatsArchive.ROOT} version=\"1\"><Game name=\"Solitaire\" wins=\"-1\" losses=\"0\" totalTime=\"0\"/></${StatsArchive.ROOT}>".toByteArray()
        for (bytes in listOf(notStats, "not xml".toByteArray(), negative)) {
            try {
                StatsArchive.parse(bytes)
                fail("should have been rejected")
            } catch (expected: StatsArchive.ImportException) {
            }
        }
    }
}
