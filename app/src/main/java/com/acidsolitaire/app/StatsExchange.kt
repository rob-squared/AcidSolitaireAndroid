package com.acidsolitaire.app

import java.io.ByteArrayInputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Reads and writes play statistics as a small XML file, so they can be backed up, shared or moved
 * to another device. Only statistics are stored, never the deals in progress. The format is the
 * same one the iPhone app uses, so a file from either app opens in the other.
 *
 *     <AcidSolitaireStats version="1" exported="2026-11-21T11:00:00Z">
 *       <Game name="Solitaire" wins="3" losses="1" bestTime="152.0" totalTime="940.5"/>
 *       ...
 *     </AcidSolitaireStats>
 */
object StatsArchive {
    class ImportException(message: String) : Exception(message)

    const val ROOT = "AcidSolitaireStats"
    const val FILE_NAME = "AcidSolitaire-Stats.xml"

    fun xml(stats: Map<GameKind, GameStats>, exported: Instant = Instant.now()): String {
        val lines = mutableListOf("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        lines += "<$ROOT version=\"1\" exported=\"$exported\">"
        for (kind in GameKind.entries) {
            val item = stats[kind] ?: GameStats()
            val best = item.bestTime?.let { " bestTime=\"$it\"" } ?: ""
            lines += "  <Game name=\"${escape(kind.title)}\" wins=\"${item.wins}\" losses=\"${item.losses}\"$best totalTime=\"${item.totalTime}\"/>"
        }
        lines += "</$ROOT>"
        return lines.joinToString("\n") + "\n"
    }

    /**
     * Statistics for every game found in the file. Games the app doesn't know are skipped; a file
     * that isn't an AcidSolitaire statistics file, or holds impossible numbers, is rejected.
     */
    fun parse(bytes: ByteArray): Map<GameKind, GameStats> {
        val factory = DocumentBuilderFactory.newInstance()
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (e: Exception) {
            // Not every XML parser knows this switch; the others don't load external entities anyway.
        }
        factory.isExpandEntityReferences = false
        val root = try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
        } catch (e: Exception) {
            throw ImportException("That file isn’t an AcidSolitaire statistics file.")
        }
        if (root == null || root.tagName != ROOT) {
            throw ImportException("That file isn’t an AcidSolitaire statistics file.")
        }
        val bad = ImportException("That file has statistics that can’t be read.")
        val result = mutableMapOf<GameKind, GameStats>()
        val nodes = root.getElementsByTagName("Game")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            val kind = GameKind.entries.firstOrNull { it.title == element.getAttribute("name") } ?: continue
            val wins = element.getAttribute("wins").ifEmpty { "0" }.toIntOrNull()
            val losses = element.getAttribute("losses").ifEmpty { "0" }.toIntOrNull()
            val total = element.getAttribute("totalTime").ifEmpty { "0" }.toDoubleOrNull()
            if (wins == null || wins < 0 || losses == null || losses < 0) throw bad
            if (total == null || !total.isFinite() || total < 0) throw bad
            var best: Double? = null
            if (element.hasAttribute("bestTime")) {
                best = element.getAttribute("bestTime").toDoubleOrNull()
                if (best == null || !best.isFinite() || best < 0) throw bad
            }
            result[kind] = GameStats(wins, losses, best, total)
        }
        if (result.isEmpty()) throw ImportException("That file doesn’t contain any game statistics.")
        return result
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}
