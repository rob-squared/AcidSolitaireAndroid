package com.acidsolitaire.app

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

// region Help

private class Topic(val title: String, val body: String)

private fun topicsFor(kind: GameKind): List<Topic> {
    val goal = Topic(
        "Goal",
        "Move all 52 cards onto the four foundations at the top left, one for each suit, building each from Ace up to King.",
    )
    val game = when (kind) {
        GameKind.KLONDIKE -> listOf(
            goal,
            Topic("The layout", "Seven columns are dealt with 1, 2, 3 and so on up to 7 cards. Only the bottom card of each column is face up. The rest of the deck is the stock at the top right, and the cards you draw from it go onto the waste beside it."),
            Topic("Building columns", "Place a card on a column's bottom card if it is one rank lower and the opposite color, such as a red 6 on a black 7. You can move a whole run of cards that are already built this way. When you move cards away, the face-down card underneath is turned over."),
            Topic("Empty columns", "Only a King, or a run that starts with a King, can be moved into an empty column."),
            Topic("The stock", "Tap the stock to draw cards onto the waste: one at a time or three at a time, as chosen in Settings. The top waste card can be played onto a column or a foundation. When the stock runs out, tap it to turn the waste over and go through it again."),
            Topic("Foundations", "Start each foundation with its Ace, then add the same suit in order: 2, 3, 4 and so on to the King. You can also drag a foundation card back onto a column if it helps."),
        )
        GameKind.FREECELL -> listOf(
            goal,
            Topic("The layout", "All 52 cards are dealt face up into eight columns. The four free cells at the top right each hold one card, and they are your breathing room."),
            Topic("Building columns", "Place a card on a column's bottom card if it is one rank lower and the opposite color, such as a red 6 on a black 7. Any card can go into an empty column."),
            Topic("Free cells", "Park a card in an empty free cell to get it out of the way, and bring it back out when it can be played. Keep cells free as long as you can."),
            Topic("Moving several cards", "You can move a run of built cards in one go, as long as it is no longer than the number of empty free cells plus one, doubled for each empty column. For example, with two empty cells and no empty columns you can move three cards; with one empty column as well, six."),
            Topic("Foundations", "Start each foundation with its Ace, then add the same suit in order. Plan ahead before you fill your cells, because a full set of cells can leave you stuck."),
        )
        GameKind.SPIDER -> listOf(
            Topic("Goal", "Clear all eight columns of cards by building eight complete runs of one suit from King down to Ace. Each finished run is removed from the table."),
            Topic("The layout", "Two decks (104 cards) are used. Ten columns are dealt, and only the bottom card of each is face up. The rest wait in the stock as five rows of ten cards."),
            Topic("Suits", "You choose one, two or four suits in Settings for new deals. One suit is the easiest and four suits is the hardest."),
            Topic("Building columns", "Place a card on a column's bottom card if it is one rank lower, whatever its suit. A black 5 can go on a red 6. Only a run of the same suit in descending order can be moved together, so building in a single suit matters."),
            Topic("Empty columns", "Any card or same-suit run can be moved into an empty column."),
            Topic("Dealing from the stock", "Tap the stock to deal one new card face up onto every column. Every column must have at least one card before you can deal, so fill any empty column first."),
            Topic("Completing a run", "When a King through Ace of one suit are in order in a column, they leave the table automatically. Complete eight runs to win."),
        )
    }
    val controls = listOf(
        Topic("Controls", "Tap a card and it moves to the best place it can go. To choose where it goes, touch and hold a card, then drag it onto a column, a foundation or a free cell. A legal spot is outlined in orange."),
        Topic("Help while you play", "The lightbulb shows a useful move. The back arrow undoes your last move. A banner warns you when only one useful move is left, and when none are."),
    )
    return game + controls
}

/** How-to-play rules for one game only. */
@Composable
fun HelpDialog(kind: GameKind, onDismiss: () -> Unit) {
    FullScreenDialog(onDismiss) {
        ScreenFrame("How to Play ${kind.title}", onDismiss) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                for (topic in topicsFor(kind)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(topic.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(topic.body)
                    }
                }
            }
        }
    }
}

// endregion

// region Stats

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

/** Statistics for every game, plus backup: export through the share sheet, import from a file. */
@Composable
fun StatsDialog(game: GameStore) {
    val context = LocalContext.current
    val close = { game.showStats = false }
    var confirmReset by remember { mutableStateOf(false) }
    val stats = game.stats
    val all = GameKind.entries.map { stats[it] ?: GameStats() }
    val played = all.sumOf { it.played }
    val won = all.sumOf { it.wins }
    val totalTime = all.sumOf { it.totalTime }
    val fastest = all.mapNotNull { it.bestTime }.minOrNull()

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) game.importFrom(uri)
    }

    FullScreenDialog(close) {
        ScreenFrame("Statistics", close) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                SectionHeader("All games")
                StatLine("Games played", played.toString())
                StatLine("Games won", won.toString())
                StatLine("Fastest game", fastest?.let { formatDuration(it) } ?: "—")
                StatLine("Total time played", formatDuration(totalTime))

                for (kind in GameKind.entries) {
                    val item = stats[kind] ?: GameStats()
                    SectionHeader(kind.title)
                    StatLine("Games played", item.played.toString())
                    StatLine("Won", item.wins.toString())
                    StatLine("Lost", item.losses.toString())
                    StatLine("Win rate", item.winRate?.let { "${Math.round(it * 100)}%" } ?: "—")
                    StatLine("Fastest game", item.bestTime?.let { formatDuration(it) } ?: "—")
                    StatLine("Total time", formatDuration(item.totalTime))
                }

                SectionHeader("Backup")
                Text(
                    "Export your statistics as an XML file and share it anywhere, or import a file you exported before. Importing replaces your current statistics.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp),
                )
                Button(
                    onClick = {
                        val file = File(File(context.cacheDir, "exports").apply { mkdirs() }, StatsArchive.FILE_NAME)
                        file.writeText(StatsArchive.xml(game.stats), Charsets.UTF_8)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/xml"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "AcidSolitaire statistics")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Export statistics"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Export Statistics…") }
                OutlinedButton(
                    onClick = { importPicker.launch(arrayOf("text/xml", "application/xml", "text/plain", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Import Statistics…") }

                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                TextButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reset Statistics", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all statistics?") },
            text = { Text("This can’t be undone.") },
            confirmButton = { TextButton(onClick = { game.resetStats(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

// endregion
