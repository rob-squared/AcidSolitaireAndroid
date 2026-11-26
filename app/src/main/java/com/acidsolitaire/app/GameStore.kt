package com.acidsolitaire.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the win screen shows. */
data class Celebration(
    val id: Long,
    val kind: GameKind,
    val moves: Int,
    val seconds: Double,
    val isRecord: Boolean,
    val fact: String,
)

/**
 * Holds every game, the statistics, the play clock and the settings. Everything the screens show
 * is Compose state, so they redraw when it changes.
 */
class GameStore(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("acidsolitaire", Context.MODE_PRIVATE)

    val backgrounds = BackgroundStore(application, prefs)
    val cardBacks = CardBackStore(application, prefs)
    private val sound = SoundPlayer(prefs, viewModelScope)

    private val saved: MutableMap<GameKind, Board> = boardsFromJson(prefs.getString("boards.v1", null))
    private val elapsedBase: MutableMap<GameKind, Double> = elapsedFromJson(prefs.getString("elapsed.v1", null))
    private var clockStart: Long? = null

    /** The clock for a new deal waits for the first touch of a card or the deal button. */
    private var clockTouched = false

    var board by mutableStateOf(saved[GameKind.KLONDIKE] ?: Board.create(GameKind.KLONDIKE, randomSeed()))
        private set
    var showMenu by mutableStateOf(true)
        private set
    var showSettings by mutableStateOf(false)
    var showStats by mutableStateOf(false)
    var showBackgrounds by mutableStateOf(false)
    var showHelp by mutableStateOf(false)
    var history by mutableStateOf<List<Board>>(emptyList())
        private set
    var isAutoCompleting by mutableStateOf(false)
        private set
    var celebration by mutableStateOf<Celebration?>(null)
        private set
    var stats by mutableStateOf<Map<GameKind, GameStats>>(statsFromJson(prefs.getString("stats.v1", null)))
        private set
    var moveStatus by mutableStateOf(MoveStatus.PLENTY)
        private set

    /** Statistics read from a file, waiting for the player to confirm replacing their own. */
    var pendingImport by mutableStateOf<Map<GameKind, GameStats>?>(null)
    var importError by mutableStateOf<String?>(null)

    var dealStyle by mutableIntStateOf(prefs.getInt("dealStyle", 1))
        private set
    var spiderSuits by mutableIntStateOf(prefs.getInt("spiderSuits", 1))
        private set
    var soundVolume by mutableFloatStateOf(prefs.getFloat(SoundPlayer.VOLUME_KEY, 1f))
        private set

    val selectedGame: GameKind get() = board.kind
    val moves: Int get() = board.moves

    private fun randomSeed() = Random.nextInt(1, 1_000_000)

    // region Settings

    fun updateDealStyle(value: Int) {
        dealStyle = value
        prefs.edit().putInt("dealStyle", value).apply()
        refreshMoveStatus()
    }

    fun updateSpiderSuits(value: Int) {
        spiderSuits = value
        prefs.edit().putInt("spiderSuits", value).apply()
    }

    fun updateSoundVolume(value: Float) {
        soundVolume = value
        prefs.edit().putFloat(SoundPlayer.VOLUME_KEY, value).apply()
    }

    /** Plays a sample so a new volume can be heard. */
    fun previewSound() = sound.chime()

    // endregion

    // region Games

    fun setMenuVisible(show: Boolean) {
        showMenu = show
        if (show) stopClock() else startClock()
    }

    fun open(kind: GameKind) {
        val previous = saved[kind]
        if (previous != null && !previous.won) {
            if (board.kind != kind) history = emptyList()
            stopClock()
            clockTouched = false
            board = previous
            setMenuVisible(false)
            scheduleAutoComplete()
            refreshMoveStatus()
        } else {
            newGame(kind)
        }
    }

    fun newGame(kind: GameKind? = null) {
        stopClock()
        if (kind == null || kind == board.kind) recordLossIfNeeded()
        val target = kind ?: selectedGame
        history = emptyList()
        clockTouched = false
        elapsedBase[target] = 0.0
        saveElapsed()
        board = Board.create(target, randomSeed(), spiderSuits)
        save()
        setMenuVisible(false)
        refreshMoveStatus()
    }

    /** Applies [action] to a copy of the board; keeps the result only if the action returns true. */
    fun play(action: (Board) -> Boolean): Boolean {
        val next = board.copy()
        if (!action(next)) return false
        history = history.takeLast(99) + board
        board = next
        save()
        startClock()
        checkpointClock()
        if (board.won) finishGame() else scheduleAutoComplete()
        refreshMoveStatus()
        return true
    }

    /**
     * Moves a card or run. The chime plays when the move involves the columns at the bottom or the
     * foundations at the top left, but not for free cells, the waste or the stock.
     */
    fun move(source: Source, destination: Pile): Boolean {
        if (!board.canMove(source, destination)) return false
        if (chimes(source.pile) || chimes(destination)) sound.chime()
        return play { it.move(source, destination) }
    }

    fun deal(): Boolean {
        val count = dealStyle
        return play { it.deal(count) }
    }

    private fun chimes(pile: Pile) = pile is Pile.Tableau || pile is Pile.Foundation

    fun undo() {
        if (isAutoCompleting || history.isEmpty()) return
        board = history.last()
        history = history.dropLast(1)
        save()
        refreshMoveStatus()
    }

    // endregion

    // region Move warnings

    /** Recomputes whether plenty of useful moves remain, only one, or none. */
    private fun refreshMoveStatus() {
        if (board.won || isAutoCompleting || board.autoCompletePlan(dealStyle) != null) {
            moveStatus = MoveStatus.PLENTY
            return
        }
        // A Spider deal is always another option, so it never counts as running out of moves.
        if (board.kind == GameKind.SPIDER && board.canDeal) {
            moveStatus = MoveStatus.PLENTY
            return
        }
        moveStatus = when (board.usefulMoveCount(dealStyle)) {
            0 -> MoveStatus.NONE
            1 -> MoveStatus.LAST
            else -> MoveStatus.PLENTY
        }
    }

    // endregion

    // region Win, loss and statistics

    private fun recordLossIfNeeded() {
        if (board.moves == 0 || board.won) return
        val current = stats[board.kind] ?: GameStats()
        stats = stats + (board.kind to current.copy(losses = current.losses + 1))
        saveStats()
    }

    private fun finishGame() {
        stopClock()
        history = emptyList()
        val kind = board.kind
        val seconds = elapsedBase[kind] ?: 0.0
        moveStatus = MoveStatus.PLENTY
        val record = stats[kind] ?: GameStats()
        val isRecord = record.bestTime?.let { seconds < it } ?: true
        stats = stats + (kind to record.copy(wins = record.wins + 1, bestTime = if (isRecord) seconds else record.bestTime))
        saveStats()
        backgrounds.advanceAfterWin(kind)
        val result = Celebration(System.nanoTime(), kind, board.moves, seconds, isRecord, FunFacts.random())
        viewModelScope.launch {
            delay(900)
            celebration = result
            sound.fanfare()
        }
    }

    fun dismissCelebration(startNewDeal: Boolean) {
        celebration = null
        if (startNewDeal) newGame() else setMenuVisible(true)
    }

    fun resetStats() {
        stats = emptyMap()
        saveStats()
    }

    /** Replaces every statistic with ones read from an exported file. */
    fun importStats(imported: Map<GameKind, GameStats>) {
        stats = imported
        saveStats()
    }

    /** Reads an exported statistics file. The player confirms before anything is replaced. */
    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val input = resolver.openInputStream(uri)
                        ?: throw StatsArchive.ImportException("That file couldn’t be opened.")
                    val bytes = input.use { stream ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            if (out.size() > 1_000_000) {
                                throw StatsArchive.ImportException("That file is too large to be a statistics file.")
                            }
                        }
                        out.toByteArray()
                    }
                    StatsArchive.parse(bytes)
                }
            }
            result.onSuccess { pendingImport = it }
                .onFailure { importError = it.message ?: "That file couldn’t be read." }
        }
    }

    // endregion

    // region Auto-complete

    private fun scheduleAutoComplete() {
        if (isAutoCompleting) return
        val plan = board.autoCompletePlan(dealStyle) ?: return
        if (plan.isEmpty()) return
        isAutoCompleting = true
        viewModelScope.launch {
            delay(500)
            for (step in plan) {
                when (step) {
                    is AutoStep.Move -> move(step.source, step.destination)
                    AutoStep.Deal -> deal()
                }
                delay(190)
            }
            isAutoCompleting = false
        }
    }

    // endregion

    // region Play clock

    /** Seconds spent on the current deal. The clock runs only while a game is on screen. */
    fun elapsed(nowMillis: Long = System.currentTimeMillis()): Double {
        val running = clockStart?.let { maxOf(0L, nowMillis - it) / 1000.0 } ?: 0.0
        return (elapsedBase[board.kind] ?: 0.0) + running
    }

    /** Called when the player first touches a card or the deal button on a new deal. */
    fun userTouched() {
        clockTouched = true
        startClock()
    }

    private val clockArmed: Boolean
        get() = clockTouched || board.moves > 0 || (elapsedBase[board.kind] ?: 0.0) > 0.0

    fun startClock() {
        if (clockStart != null || showMenu || board.won || !clockArmed) return
        clockStart = System.currentTimeMillis()
    }

    fun stopClock() {
        val start = clockStart ?: return
        val seconds = maxOf(0L, System.currentTimeMillis() - start) / 1000.0
        val kind = board.kind
        elapsedBase[kind] = (elapsedBase[kind] ?: 0.0) + seconds
        val current = stats[kind] ?: GameStats()
        stats = stats + (kind to current.copy(totalTime = current.totalTime + seconds))
        clockStart = null
        saveElapsed()
        saveStats()
    }

    private fun checkpointClock() {
        if (clockStart == null) return
        stopClock()
        startClock()
    }

    // endregion

    // region Persistence

    private fun save() {
        saved[selectedGame] = board
        prefs.edit().putString("boards.v1", boardsToJson(saved)).apply()
    }

    private fun saveStats() {
        prefs.edit().putString("stats.v1", statsToJson(stats)).apply()
    }

    private fun saveElapsed() {
        prefs.edit().putString("elapsed.v1", elapsedToJson(elapsedBase)).apply()
    }

    // endregion

    override fun onCleared() {
        sound.release()
        super.onCleared()
    }
}
