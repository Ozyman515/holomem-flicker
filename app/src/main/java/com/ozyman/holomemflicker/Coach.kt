package com.ozyman.holomemflicker

import org.json.JSONArray
import org.json.JSONObject

/**
 * The in-game Puka Puka Coach (Select+Start), driven only by the controller.
 *
 * Loop: on your turn it thinks, highlights the best move and asks "Play best move?" (A = yes).
 * On their turn it lists their most likely replies; the D-pad walks the list and each one is
 * highlighted on the mini board so it can be matched against the game, A confirms.
 * "Other…" holds everything else: a different move entered by hand, Undo, Draw, New match,
 * panel settings. When the match ends, "Undo the last move" takes the result back.
 *
 * No Android here (testable on the JVM): searches run through [runAsync], results come back
 * through [post], and [changed] asks the overlay to redraw.
 */
class Coach(
    private val runAsync: (() -> Unit) -> Unit,
    private val post: (() -> Unit) -> Unit,
    private val changed: () -> Unit,
    val settings: Settings = Settings(),
    private val thinkMs: Long = 1500,
) {
    enum class Phase { START, MY_TURN, THEIR_TURN, OTHER, MANUAL, SETTINGS, MOVE_PANEL, OVER, SCREEN_TEST, PICK }

    /** Highlight colours: the best move for you is red, a picked move of theirs is violet. */
    enum class Role { BEST, THEIRS, MANUAL }

    class Item(val label: String, val hint: String = "", val run: () -> Unit)

    /** Panel look, saved by the overlay. Opacity is a percent of the overlay's own limit. */
    class Settings(var scale: Int = 100, var opacity: Int = 100, var x: Float = 0.015f, var y: Float = 0.16f, var follow: Boolean = true) {
        fun toJson() = JSONObject().put("scale", scale).put("opacity", opacity).put("x", x.toDouble()).put("y", y.toDouble()).put("follow", follow)
        companion object {
            fun from(o: JSONObject?) = Settings().apply {
                if (o == null) return@apply
                scale = o.optInt("scale", 100).coerceIn(MIN_SCALE, MAX_SCALE)
                opacity = o.optInt("opacity", 100).coerceIn(MIN_OPACITY, 100)
                x = o.optDouble("x", 0.015).toFloat().coerceIn(0f, 1f)
                y = o.optDouble("y", 0.16).toFloat().coerceIn(0f, 1f)
                follow = o.optBoolean("follow", true)
            }
        }
    }

    var state: Puka.State = Puka.start(); private set
    /** Positions before each move of this match (for Undo and the repetition rule). */
    private val history = ArrayList<Puka.State>()
    private val played = ArrayList<Puka.Move>()
    var phase = Phase.START; private set
    private var back = Phase.START // where Other…/Settings return to
    var sel = 0; private set
    var thinking = false; private set
    var best: Puka.Result? = null; private set
    var replies: List<Puka.Move> = emptyList(); private set
    var drawn = false; private set
    var note = ""; private set
    private var gen = 0

    // hand entry: a cursor over the board (screen columns 0..3) and the two benches (-1 = yours, 4 = theirs)
    var cx = 0; private set
    var cy = 0; private set
    var picked: Puka.Move? = null; private set // from-square or bench piece chosen, waiting for the target

    /** Last move played (drawn as a faint marker). */
    val last: Puka.Move? get() = played.lastOrNull()?.takeIf { it.to >= 0 }

    var onSettings: (() -> Unit)? = null
    /** Opens the touch board editor (the overlay's Fix board window). */
    var onFix: (() -> Unit)? = null
    /** Screen reader test on/off (the overlay runs the capture loop). */
    var onScreenTest: ((Boolean) -> Unit)? = null
    /** Shares the screen reader test log. */
    var onShareLog: (() -> Unit)? = null

    // ---- screen reader test: the last reading, shown on the mini board ----
    var scan: Puka.State? = null; private set
    var scanUnknown: Set<Int> = emptySet(); private set
    var scanText = "Reading the screen…"; private set
    private var scanHead = "Screen reader test"

    /** A result from the overlay's capture loop ([j] = the helper's readScreen JSON). */
    fun showScan(j: JSONObject) {
        if (phase != Phase.SCREEN_TEST) return
        if (!j.optBoolean("ok")) { scanHead = "Capture failed"; scanText = j.optString("error"); changed(); return }
        val r = j.getJSONObject("reading")
        val cap = j.optLong("capMs"); val conv = j.optLong("convMs"); val rd = j.optLong("readMs")
        val timing = "grab ${cap + conv} ms · read ${rd} ms"
        if (!r.optBoolean("normal")) {
            scanHead = "Rotate back to read the board"
            scanText = "Not the normal view (%.2f) · %s".format(r.optDouble("score"), timing)
            scan = null; changed(); return
        }
        val tall = r.getInt("tall")
        val s = Puka.empty(if (r.optInt("myTurn") == 0) 1 else 0, tall)
        val unk = HashSet<Int>()
        val g = r.getJSONArray("grid")
        for (y in 0 until g.length()) { val row = g.getJSONArray(y); for (x in 0 until row.length()) {
            val c = row.getString(x); if (c.isEmpty()) continue
            val i = s.idx(x, y)
            val t = when (c.getOrNull(1)) { 'L' -> Puka.LEADER; 'W' -> Puka.WHALE; 'D' -> Puka.DOLPHIN; 'T' -> Puka.TURTLE; 'G' -> Puka.GOLD; else -> 0 }
            if (t == 0) { unk.add(i); s.b[i] = Puka.TURTLE or (if (c[0] == 'O') 8 else 0) } else s.b[i] = t or (if (c[0] == 'O') 8 else 0)
        } }
        val bn = r.getJSONArray("bench")
        for (side in 0..1) { val a = bn.getJSONArray(side); for (k in 0 until a.length()) {
            val t = when (a.getString(k)) { "W" -> Puka.WHALE; "D" -> Puka.DOLPHIN; else -> Puka.TURTLE }; s.h[side * 5 + t]++ } }
        scan = s; scanUnknown = unk
        val undos = r.optInt("undos", -1); val mt = r.optInt("myTurn", -1)
        scanHead = "Read: $tall-tall" + (if (unk.isNotEmpty()) " · ${unk.size} unsure" else "")
        scanText = timing + " · undos " + (if (undos < 0) "?" else "$undos") + " · " + (when (mt) { 1 -> "your turn"; 0 -> "their turn"; else -> "turn ?" })
        changed()
    }
    var onSave: (() -> Unit)? = null

    // ---------------------------------------------------------------- what the panel shows

    val prompt: String
        get() = when (phase) {
            Phase.START -> "New match: who moves first?"
            Phase.MY_TURN -> if (thinking) "Thinking…" else if (best == null) "No legal move" else "Play best move?"
            Phase.THEIR_TURN -> if (thinking) "Thinking…" else "Their move?"
            Phase.OTHER -> "Other…"
            Phase.MANUAL -> if (picked == null) (if (state.turn == 0) "Your move: pick a piece" else "Their move: pick a piece") else "Pick the square it moves to"
            Phase.SETTINGS -> "Panel settings"
            Phase.MOVE_PANEL -> "Move the panel with the D-pad"
            Phase.SCREEN_TEST -> scanHead
            Phase.PICK -> if (state.turn == 0) "Which move did you make?" else "Which move did they make?"
            Phase.OVER -> when {
                drawn -> "Draw"
                Puka.winner(state) == 0 -> "You win!"
                else -> "They win"
            }
        }

    val detail: String
        get() = when (phase) {
            Phase.MY_TURN -> (if (seen.isNotEmpty()) "$seen · " else "") + (best?.let { b ->
                verdict(b.score, 0) + " · " + Puka.shortText(state, b.move)
            } ?: "")
            Phase.THEIR_TURN -> if (seen.isNotEmpty()) seen else if (thinking || replies.isEmpty()) "" else if (settings.follow) "Watching the screen for their move · or pick it here" else "Most likely first · A picks · Other… for anything else"
            Phase.MANUAL -> "A selects · B goes back"
            Phase.MOVE_PANEL -> "A or B when done"
            Phase.SCREEN_TEST -> scanText
            Phase.PICK -> "A few moves fit what's on screen · A picks"
            Phase.OVER -> if (drawn) "Match ended in a draw" else "A wrong entry? Undo the last move puts the board back"
            else -> note
        }

    val items: List<Item>
        get() = when (phase) {
            Phase.START -> listOf(
                Item("Normal start: I go first") { newMatch(0) },
                Item("Normal start: they go first") { newMatch(1) },
                Item("Stage's own setup", "set up in Fix board") { onFix?.invoke() },
                Item("Panel settings") { goTo(Phase.SETTINGS) },
                Item("Screen reader test") { startScreenTest() },
            )
            Phase.MY_TURN -> buildList {
                best?.let { b -> add(Item("Yes, play it", "A") { play(b.move) }) }
                add(Item("Other…") { openOther() })
            }
            Phase.THEIR_TURN -> buildList {
                for (m in replies) add(Item(Puka.shortText(state, m)) { play(m) })
                add(Item("Other…") { openOther() })
            }
            Phase.OTHER -> buildList {
                add(Item(if (state.turn == 0) "I played a different move" else "Enter their move by hand") { openManual() })
                if (history.isNotEmpty()) add(Item("Undo the last move") { undo() })
                add(Item(if (state.turn == 0) "It's actually their turn" else "It's actually my turn") { switchTurn() })
                add(Item("Match ended in a draw") { drawn = true; goTo(Phase.OVER) })
                add(Item("New match") { goTo(Phase.START) })
                add(Item("Panel settings") { goTo(Phase.SETTINGS) })
                add(Item("Screen reader test") { startScreenTest() })
                add(Item("Back") { goTo(back) })
            }
            Phase.SETTINGS -> listOf(
                Item("Size", "${settings.scale}%  ◀ ▶") {},
                Item("See-through", "${100 - settings.opacity}%  ◀ ▶") {},
                Item("Move the panel") { goTo(Phase.MOVE_PANEL) },
                Item("Follow the game by itself", if (settings.follow) "on  ◀ ▶" else "off  ◀ ▶") { settings.follow = !settings.follow; onSettings?.invoke() },
                Item("Back") { goTo(if (back == Phase.START) Phase.START else Phase.OTHER) },
            )
            Phase.OVER -> buildList {
                if (history.isNotEmpty()) add(Item("Undo the last move") { drawn = false; undo() })
                else if (drawn) add(Item("Take back the draw") { drawn = false; goTo(turnPhase()) })
                add(Item("New match: I go first") { newMatch(0) })
                add(Item("New match: they go first") { newMatch(1) })
            }
            Phase.MANUAL, Phase.MOVE_PANEL -> emptyList()
            Phase.PICK -> buildList {
                for (m in pickOptions) add(Item(Puka.shortText(state, m)) { play(m) })
                add(Item("Other…") { openOther() })
            }
            Phase.SCREEN_TEST -> buildList {
                val s = scan
                if (s != null && scanUnknown.isEmpty() && Puka.leaderPos(s, 0) >= 0 && Puka.leaderPos(s, 1) >= 0)
                    add(Item("Start a match from this board") { stopScreenTest(); startFrom(s) })
                add(Item("Share test log") { onShareLog?.invoke() })
                add(Item("Stop test") { stopScreenTest(); goTo(back) })
            }
        }

    /** The move to highlight on the mini board right now, and how. */
    val highlight: Pair<Puka.Move, Role>?
        get() = when (phase) {
            Phase.MY_TURN -> if (thinking) null else best?.let { it.move to Role.BEST }
            Phase.THEIR_TURN -> if (thinking) null else replies.getOrNull(sel)?.let { it to Role.THEIRS }
            Phase.PICK -> pickOptions.getOrNull(sel)?.let { it to Role.THEIRS }
            else -> null
        }

    /** In hand entry: the squares the picked piece may go to. */
    val targets: List<Int>
        get() = picked?.let { p -> Puka.moves(state).filter { it.from == p.from && it.drop == p.drop }.map { it.to } } ?: emptyList()

    private fun verdict(score: Double, forSide: Int): String {
        val s = if (state.turn == forSide) score else -score
        return when {
            s > Puka.MATE - 100 -> "You win in ${Math.ceil((Puka.MATE - s) / 2).toInt()}"
            s < -(Puka.MATE - 100) -> "They can force a win"
            s > 1.5 -> "You're ahead"
            s < -1.5 -> "You're behind"
            else -> "About even"
        }
    }

    // ---------------------------------------------------------------- controller

    fun onKey(code: Int) {
        when (phase) {
            Phase.MANUAL -> manualKey(code)
            Phase.MOVE_PANEL -> when (code) {
                KEY_LEFT -> nudge(-0.02f, 0f)
                KEY_RIGHT -> nudge(0.02f, 0f)
                KEY_UP -> nudge(0f, -0.03f)
                KEY_DOWN -> nudge(0f, 0.03f)
                KEY_A, KEY_B -> goTo(Phase.SETTINGS, 2)
            }
            else -> listKey(code)
        }
        changed()
    }

    private fun listKey(code: Int) {
        val its = items
        when (code) {
            KEY_UP -> if (its.isNotEmpty()) sel = (sel - 1 + its.size) % its.size
            KEY_DOWN -> if (its.isNotEmpty()) sel = (sel + 1) % its.size
            KEY_LEFT, KEY_RIGHT -> if (phase == Phase.SETTINGS) adjust(if (code == KEY_LEFT) -1 else 1)
            KEY_A -> its.getOrNull(sel)?.run?.invoke()
            KEY_B -> when (phase) {
                Phase.MY_TURN, Phase.THEIR_TURN, Phase.PICK -> openOther()
                Phase.OTHER -> goTo(back)
                Phase.SCREEN_TEST -> { stopScreenTest(); goTo(back) }
                Phase.SETTINGS -> goTo(if (back == Phase.START) Phase.START else Phase.OTHER)
                else -> {}
            }
        }
    }

    private fun adjust(dir: Int) {
        when (sel) {
            0 -> settings.scale = (settings.scale + dir * 10).coerceIn(MIN_SCALE, MAX_SCALE)
            1 -> settings.opacity = (settings.opacity - dir * 10).coerceIn(MIN_OPACITY, 100)
            3 -> settings.follow = !settings.follow
            else -> return
        }
        onSettings?.invoke()
    }

    private fun nudge(dx: Float, dy: Float) {
        settings.x = (settings.x + dx).coerceIn(0f, 1f)
        settings.y = (settings.y + dy).coerceIn(0f, 1f)
        onSettings?.invoke()
    }

    private fun manualKey(code: Int) {
        when (code) {
            KEY_LEFT -> cx = (cx - 1).coerceAtLeast(-1)
            KEY_RIGHT -> cx = (cx + 1).coerceAtMost(4)
            KEY_UP -> cy = (cy - 1 + state.w) % state.w
            KEY_DOWN -> cy = (cy + 1) % state.w
            KEY_B -> if (picked != null) picked = null else goTo(Phase.OTHER)
            KEY_A -> manualPick()
        }
    }

    /** Bench rows top to bottom: Dolphin, Whale, Turtle. */
    private fun benchType(y: Int) = Puka.DOLPHIN + y

    private fun manualPick() {
        val side = state.turn
        val p = picked
        if (p == null) {
            if (cx == -1 || cx == 4) {
                val benchSide = if (cx == -1) 0 else 1
                val t = benchType(cy)
                if (cy <= 2 && benchSide == side && state.hand(side, t) > 0) picked = Puka.Move(-1, -1, t)
                else note = "Nothing to drop there"
                return
            }
            val i = state.idx(cx, cy)
            val q = state.b[i]
            if (q != 0 && Puka.own(q) == side && Puka.moves(state).any { it.from == i && it.drop == 0 }) picked = Puka.Move(i, -1, 0)
            else note = if (side == 0) "Pick one of your pieces" else "Pick one of their pieces"
            return
        }
        if (cx == -1 || cx == 4) return
        val to = state.idx(cx, cy)
        val m = Puka.moves(state).firstOrNull { it.from == p.from && it.drop == p.drop && it.to == to }
        if (m != null) { picked = null; play(m) } else note = "It can't go there"
    }

    // ---------------------------------------------------------------- game flow

    fun newMatch(first: Int) {
        tracker.reset(); seen = ""
        history.clear(); played.clear()
        state = Puka.start().also { it.turn = first }
        drawn = false
        goTo(turnPhase())
    }

    // ---- following the game from screen readings ----
    private val tracker = PukaTracker()
    private var pickOptions: List<Puka.Move> = emptyList()
    /** Last thing seen on screen, shown under the prompt. */
    var seen = ""; private set
    /** True when the overlay should keep reading the screen. */
    val wantsReadings get() = phase == Phase.SCREEN_TEST || (settings.follow && (phase == Phase.MY_TURN || phase == Phase.THEIR_TURN || phase == Phase.PICK))

    fun onReading(r: PukaReader.Reading) {
        if (!settings.follow || (phase != Phase.MY_TURN && phase != Phase.THEIR_TURN && phase != Phase.PICK)) return
        if (!r.normalView) { if (seen != "Rotate back to follow the game") { seen = "Rotate back to follow the game"; changed() }; return }
        when (val res = tracker.update(r, state, history)) {
            is PukaTracker.Result.Nothing -> if (tracker.inSync && seen.startsWith("Can't")) seen = ""
            is PukaTracker.Result.Moves -> {
                val txt = ArrayList<String>()
                for (m in res.moves) { txt.add((if (state.turn == 0) "you: " else "they: ") + Puka.shortText(state, m)); playAuto(m) }
                seen = "Saw " + txt.joinToString(", ")
            }
            is PukaTracker.Result.Undo -> {
                repeat(res.steps) { if (history.isNotEmpty()) { state = history.removeAt(history.size - 1); played.removeAt(played.size - 1) } }
                drawn = false
                goTo(turnPhase())
                seen = "Saw an Undo (undos left: ${tracker.undos ?: "?"})"
            }
            is PukaTracker.Result.Ambiguous -> { pickOptions = res.options; goTo(Phase.PICK); seen = "" }
            is PukaTracker.Result.Lost -> seen = "Can't match the screen to the board: Fix board, or enter the move in Other…"
        }
        changed()
    }

    /** A move seen on screen (not entered with the controller). */
    private fun playAuto(m: Puka.Move) {
        history.add(state); played.add(m)
        state = Puka.apply(state, m)
        goTo(if (Puka.terminal(state) != 0) Phase.OVER else turnPhase())
    }

    fun play(m: Puka.Move) {
        seen = ""
        history.add(state)
        played.add(m)
        state = Puka.apply(state, m)
        note = ""
        goTo(if (Puka.terminal(state) != 0) Phase.OVER else turnPhase())
    }

    fun undo() {
        if (history.isEmpty()) return
        state = history.removeAt(history.size - 1)
        played.removeAt(played.size - 1)
        drawn = false
        goTo(turnPhase())
    }

    /** The coach has the wrong side to move (e.g. a board read when the turn wasn't clear): flip it. */
    fun switchTurn() {
        history.add(state); played.add(Puka.Move(-1, -1, 0))
        state = state.copy().also { it.turn = 1 - it.turn }
        goTo(turnPhase())
    }

    private fun startScreenTest() {
        scan = null; scanUnknown = emptySet(); scanHead = "Screen reader test"; scanText = "Reading the screen…"
        goTo(Phase.SCREEN_TEST)
        onScreenTest?.invoke(true)
    }

    private fun stopScreenTest() { onScreenTest?.invoke(false) }

    /** A new match from a stage's own starting board (any size), set up in Fix board. */
    fun startFrom(s: Puka.State) {
        tracker.reset(); seen = ""
        history.clear(); played.clear()
        state = s.copy()
        drawn = false
        goTo(if (Puka.terminal(state) != 0) Phase.OVER else turnPhase())
    }

    /** The board was fixed by hand ("Fix board"): keep the old one so Undo can bring it back. */
    fun setPosition(s: Puka.State) {
        if (s == state) { goTo(turnPhase()); return }
        history.add(state)
        played.add(Puka.Move(-1, -1, 0)) // placeholder: an edit, not a move
        state = s.copy()
        drawn = false
        goTo(if (Puka.terminal(state) != 0) Phase.OVER else turnPhase())
    }

    private fun turnPhase() = if (state.turn == 0) Phase.MY_TURN else Phase.THEIR_TURN

    private fun openOther() {
        if (phase != Phase.OTHER && phase != Phase.SETTINGS) back = phase
        goTo(Phase.OTHER)
    }

    private fun openManual() {
        picked = null
        // start the cursor on the side to move's Leader
        val lp = Puka.leaderPos(state, state.turn)
        if (lp >= 0) { cx = state.sx(lp); cy = state.sy(lp) }
        goTo(Phase.MANUAL)
    }

    private fun goTo(p: Phase, select: Int = 0) {
        if (p == Phase.MY_TURN || p == Phase.THEIR_TURN || p == Phase.START) back = p
        phase = p
        sel = select
        if (p != Phase.OTHER && p != Phase.MANUAL) note = ""
        if (p == Phase.MY_TURN || p == Phase.THEIR_TURN) think()
        onSave?.invoke()
    }

    private var analysedKey = -1L
    private var thinkingKey = -1L

    private fun think() {
        val k = state.key()
        if (thinking && thinkingKey == k) return
        if (!thinking && analysedKey == k && (best != null || replies.isNotEmpty())) return
        thinkingKey = k
        val g = ++gen
        val s = state.copy()
        val seen = history.map { it.key() }.toSet()
        thinking = true
        best = null
        replies = emptyList()
        runAsync {
            val r: Puka.Result?
            val rs: List<Puka.Move>
            if (s.turn == 0) { r = Puka.search(s, thinkMs, seen = seen); rs = emptyList() }
            else { r = null; rs = Puka.rank(s, thinkMs, seen = seen).take(4).map { it.first } }
            post {
                if (g != gen) return@post
                thinking = false
                best = r
                replies = rs
                analysedKey = s.key()
                changed()
            }
        }
    }

    // ---------------------------------------------------------------- saving

    fun toJson(): JSONObject {
        fun st(s: Puka.State) = JSONObject().put("b", JSONArray(s.b.toList())).put("h", JSONArray(s.h.toList())).put("t", s.turn).put("w", s.w)
        return JSONObject()
            .put("state", st(state))
            .put("history", JSONArray(history.map { st(it) }))
            .put("played", JSONArray(played.map { JSONArray(listOf(it.from, it.to, it.drop)) }))
            .put("phase", (if (phase == Phase.OTHER || phase == Phase.SETTINGS || phase == Phase.MANUAL || phase == Phase.MOVE_PANEL || phase == Phase.SCREEN_TEST || phase == Phase.PICK) back else phase).name)
            .put("drawn", drawn)
    }

    fun load(o: JSONObject?) {
        if (o == null) return
        try {
            fun st(j: JSONObject): Puka.State {
                val b = j.getJSONArray("b"); val h = j.getJSONArray("h")
                return Puka.State(IntArray(b.length()) { b.getInt(it) }, IntArray(10) { h.getInt(it) }, j.getInt("t"), j.optInt("w", 3))
            }
            val s = st(o.getJSONObject("state"))
            val hs = o.getJSONArray("history").let { a -> (0 until a.length()).map { st(a.getJSONObject(it)) } }
            val ps = o.getJSONArray("played").let { a -> (0 until a.length()).map { val m = a.getJSONArray(it); Puka.Move(m.getInt(0), m.getInt(1), m.getInt(2)) } }
            if (hs.size != ps.size) return
            state = s; history.clear(); history.addAll(hs); played.clear(); played.addAll(ps)
            drawn = o.optBoolean("drawn")
            val p = try { Phase.valueOf(o.optString("phase")) } catch (_: Throwable) { Phase.START }
            goTo(if (p == Phase.MY_TURN || p == Phase.THEIR_TURN) turnPhase() else p)
        } catch (_: Throwable) {
        }
    }

    /** Opening the panel again: ask again if the last search was dropped while it was closed. */
    fun resume() {
        if ((phase == Phase.MY_TURN || phase == Phase.THEIR_TURN) && !thinking && best == null && replies.isEmpty()) think()
    }

    companion object {
        const val KEY_A = 0x130
        const val KEY_B = 0x131
        const val KEY_UP = 0x220
        const val KEY_DOWN = 0x221
        const val KEY_LEFT = 0x222
        const val KEY_RIGHT = 0x223
        const val MIN_SCALE = 60
        const val MAX_SCALE = 160
        const val MIN_OPACITY = 30
    }
}
