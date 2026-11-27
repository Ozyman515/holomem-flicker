package com.ozyman.holomemflicker

/**
 * Follows a match from screen readings: works out which move (or Undo) turned the coach's board
 * into what's on screen. It never recognises a move cold: it tries every legal move from the
 * known position and keeps the one whose result matches the screen (one misread tile allowed).
 * Same logic as the prototype that followed the user's match videos.
 *
 * A board is acted on only after it reads the same twice in a row (animations settle first).
 * An Undo is accepted only when the game's Undo counter went down.
 */
class PukaTracker {
    sealed class Result {
        object Nothing : Result()
        /** One or two moves (an animation can hide the first). */
        class Moves(val moves: List<Puka.Move>) : Result()
        /** Go back [steps] positions in the coach's history. */
        class Undo(val steps: Int) : Result()
        /** Several moves fit equally well: ask. */
        class Ambiguous(val options: List<Puka.Move>) : Result()
        /** Nothing explains the board (misread, or the coach is out of step). */
        object Lost : Result()
    }

    private var lastSeen: String? = null
    private var stable = 0
    var undos: Int? = null
    private var acted: String? = null
    /** The last steady board matched the coach's. */
    var inSync = true; private set

    fun reset() { lastSeen = null; stable = 0; undos = null; acted = null }

    companion object {
        const val TOL = 1

        fun owners(s: Puka.State): String = String(CharArray(s.n) { val p = s.b[it]; if (p == 0) '.' else if (Puka.own(p) == 0) 'B' else 'O' })

        fun diff(a: String, b: String) = a.indices.count { a[it] != b[it] }

        /** The reading in board order: owners and (where known) pieces. */
        fun fromReading(r: PukaReader.Reading, w: Int): Pair<String, IntArray>? {
            if (!r.normalView || r.tall != w) return null
            val s = Puka.empty(0, w)
            val own = CharArray(s.n) { '.' }
            val piece = IntArray(s.n)
            for ((y, row) in r.grid.withIndex()) for ((x, c) in row.withIndex()) {
                if (c.isEmpty()) continue
                val i = s.idx(x, y)
                own[i] = c[0]
                val t = when (c.getOrNull(1)) { 'L' -> Puka.LEADER; 'W' -> Puka.WHALE; 'D' -> Puka.DOLPHIN; 'T' -> Puka.TURTLE; 'G' -> Puka.GOLD; else -> 0 }
                if (t != 0) piece[i] = t or (if (c[0] == 'O') 8 else 0)
            }
            return String(own) to piece
        }

        private fun agree(s: Puka.State, piece: IntArray) = s.b.indices.count { piece[it] != 0 && s.b[it] == piece[it] }
    }

    /**
     * [state] = the coach's position, [history] = earlier positions (oldest first),
     * [side] = whose move it is in the coach (both sides are tried when [eitherSide]).
     */
    fun update(r: PukaReader.Reading, state: Puka.State, history: List<Puka.State>, eitherSide: Boolean = false): Result {
        val (seen, piece) = fromReading(r, state.w) ?: return Result.Nothing
        val prevUndos = undos
        r.undos?.let { if (undos == null) undos = it }
        if (seen == lastSeen) stable++ else { lastSeen = seen; stable = 1 }
        if (stable < 2) return Result.Nothing
        inSync = diff(owners(state), seen) <= TOL
        if (inSync) { acted = null; if (r.undos != null) undos = r.undos; return Result.Nothing }
        val key = seen + "|" + state.key()
        if (key == acted) return Result.Nothing // already answered for this board and position (e.g. waiting on a pick)

        // an Undo: only when the counter went down
        if (r.undos != null && prevUndos != null && r.undos < prevUndos) {
            for (k in 1..minOf(4, history.size)) {
                if (diff(owners(history[history.size - k]), seen) <= TOL) { undos = r.undos; acted = key; return Result.Undo(k) }
            }
        }

        class Cand(val score: Int, val moves: List<Puka.Move>)
        val cands = ArrayList<Cand>()
        val sides = if (eitherSide) listOf(state.turn, 1 - state.turn) else listOf(state.turn)
        for (side in sides) {
            val st = if (side == state.turn) state else state.copy().also { it.turn = side }
            for (m in Puka.moves(st)) {
                val n = Puka.apply(st, m)
                val d = diff(owners(n), seen)
                if (d <= TOL) cands.add(Cand(agree(n, piece) - 10 * d - (if (side != state.turn) 3 else 0), listOf(m)))
            }
        }
        if (cands.isEmpty()) {
            for (m in Puka.moves(state)) {
                val n = Puka.apply(state, m)
                if (Puka.terminal(n) != 0) continue
                for (m2 in Puka.moves(n)) {
                    val n2 = Puka.apply(n, m2)
                    val d = diff(owners(n2), seen)
                    if (d <= TOL) cands.add(Cand(agree(n2, piece) - 10 * d - 5, listOf(m, m2)))
                }
            }
        }
        acted = key
        if (cands.isEmpty()) return Result.Lost
        val best = cands.maxOf { it.score }
        val top = cands.filter { it.score == best }
        if (top.size == 1) return Result.Moves(top[0].moves)
        // ties: ask which first move it was; if two moves happened, the second is found on the next reading
        val firsts = top.map { it.moves[0] }.distinct()
        return if (firsts.size == 1) Result.Moves(listOf(firsts[0])) else Result.Ambiguous(firsts.take(4))
    }
}
