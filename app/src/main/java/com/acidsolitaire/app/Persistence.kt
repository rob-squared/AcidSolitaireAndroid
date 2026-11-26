package com.acidsolitaire.app

import org.json.JSONArray
import org.json.JSONObject

/** Saves boards and statistics as small JSON documents in SharedPreferences. */

private fun Card.toJson() = JSONArray().put(id).put(rank).put(suit.ordinal).put(if (faceUp) 1 else 0)

private fun cardFrom(a: JSONArray) = Card(a.getInt(0), a.getInt(1), Suit.entries[a.getInt(2)], a.getInt(3) == 1)

private fun List<Card>.toJson() = JSONArray().also { array -> forEach { array.put(it.toJson()) } }

private fun cardsFrom(a: JSONArray): MutableList<Card> =
    MutableList(a.length()) { cardFrom(a.getJSONArray(it)) }

private fun List<List<Card>>.toJsonPiles() = JSONArray().also { array -> forEach { array.put(it.toJson()) } }

private fun pilesFrom(a: JSONArray): MutableList<MutableList<Card>> =
    MutableList(a.length()) { cardsFrom(a.getJSONArray(it)) }

fun Board.toJson(): JSONObject = JSONObject()
    .put("kind", kind.name)
    .put("seed", seed)
    .put("tableau", tableau.toJsonPiles())
    .put("stock", stock.toJson())
    .put("waste", waste.toJson())
    .put("foundations", foundations.toJsonPiles())
    .put("cells", cells.toJsonPiles())
    .put("runs", completedRuns)
    .put("moves", moves)

fun boardFromJson(o: JSONObject): Board = Board(
    kind = GameKind.valueOf(o.getString("kind")),
    seed = o.getInt("seed"),
    tableau = pilesFrom(o.getJSONArray("tableau")),
    stock = cardsFrom(o.getJSONArray("stock")),
    waste = cardsFrom(o.getJSONArray("waste")),
    foundations = pilesFrom(o.getJSONArray("foundations")),
    cells = pilesFrom(o.getJSONArray("cells")),
    completedRuns = o.getInt("runs"),
    moves = o.getInt("moves"),
)

fun boardsToJson(boards: Map<GameKind, Board>): String {
    val o = JSONObject()
    boards.forEach { (kind, board) -> o.put(kind.name, board.toJson()) }
    return o.toString()
}

fun boardsFromJson(text: String?): MutableMap<GameKind, Board> {
    val result = mutableMapOf<GameKind, Board>()
    if (text == null) return result
    try {
        val o = JSONObject(text)
        for (kind in GameKind.entries) {
            if (o.has(kind.name)) result[kind] = boardFromJson(o.getJSONObject(kind.name))
        }
    } catch (e: Exception) {
        // A damaged save is dropped rather than crashing the app.
    }
    return result
}

fun statsToJson(stats: Map<GameKind, GameStats>): String {
    val o = JSONObject()
    stats.forEach { (kind, s) ->
        val item = JSONObject().put("wins", s.wins).put("losses", s.losses).put("total", s.totalTime)
        if (s.bestTime != null) item.put("best", s.bestTime)
        o.put(kind.name, item)
    }
    return o.toString()
}

fun statsFromJson(text: String?): MutableMap<GameKind, GameStats> {
    val result = mutableMapOf<GameKind, GameStats>()
    if (text == null) return result
    try {
        val o = JSONObject(text)
        for (kind in GameKind.entries) {
            val item = o.optJSONObject(kind.name) ?: continue
            result[kind] = GameStats(
                wins = item.optInt("wins"),
                losses = item.optInt("losses"),
                bestTime = if (item.has("best")) item.getDouble("best") else null,
                totalTime = item.optDouble("total", 0.0),
            )
        }
    } catch (e: Exception) {
        // A damaged save is dropped rather than crashing the app.
    }
    return result
}

fun elapsedToJson(map: Map<GameKind, Double>): String {
    val o = JSONObject()
    map.forEach { (kind, seconds) -> o.put(kind.name, seconds) }
    return o.toString()
}

fun elapsedFromJson(text: String?): MutableMap<GameKind, Double> {
    val result = mutableMapOf<GameKind, Double>()
    if (text == null) return result
    try {
        val o = JSONObject(text)
        for (kind in GameKind.entries) if (o.has(kind.name)) result[kind] = o.getDouble(kind.name)
    } catch (e: Exception) {
        // A damaged save is dropped rather than crashing the app.
    }
    return result
}
