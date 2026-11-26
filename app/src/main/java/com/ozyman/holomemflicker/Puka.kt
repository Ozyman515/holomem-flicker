package com.ozyman.holomemflicker

/**
 * Puka Puka Fight rules and best-move search (the game is Dobutsu shogi with holomems).
 * A straight port of the engine in assets/puka-coach.html, so the overlay coach and the
 * Coach screen pick the same moves (PukaTest checks this against the page's engine).
 *
 * Board: 3 columns x 4 rows, index r*3+c. Row 0 = their back row, row 3 = your back row.
 * Side 0 = you (moves toward row 0), side 1 = them.
 * Cell: 0 empty, type 1..5 for you, type|8 for them.
 * Types: 1 Leader, 2 Dolphin, 3 Whale, 4 Turtle, 5 Gold Turtle.
 * Hands (benches): h[side*5 + type] for type 2..4.
 */
object Puka {
    const val LEADER = 1
    const val DOLPHIN = 2
    const val WHALE = 3
    const val TURTLE = 4
    const val GOLD = 5

    val NAMES = mapOf(LEADER to "Leader", DOLPHIN to "Dolphin", WHALE to "Whale", TURTLE to "Turtle", GOLD to "Gold Turtle")

    /** Steps per type as (dr, dc) for side 0; side 1 flips dr. */
    val DIRS: Map<Int, Array<IntArray>> = mapOf(
        LEADER to arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 0), intArrayOf(-1, 1), intArrayOf(0, -1), intArrayOf(0, 1), intArrayOf(1, -1), intArrayOf(1, 0), intArrayOf(1, 1)),
        DOLPHIN to arrayOf(intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1)),
        WHALE to arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1)),
        TURTLE to arrayOf(intArrayOf(-1, 0)),
        GOLD to arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 0), intArrayOf(-1, 1), intArrayOf(0, -1), intArrayOf(0, 1), intArrayOf(1, 0)),
    )
    private val VAL = doubleArrayOf(0.0, 0.0, 5.0, 5.0, 2.0, 6.0)
    private val HANDVAL = doubleArrayOf(0.0, 0.0, 6.0, 6.0, 2.5)
    const val MATE = 10000.0

    fun own(p: Int): Int = if (p == 0) -1 else if (p and 8 != 0) 1 else 0
    fun typ(p: Int): Int = p and 7

    class State(val b: IntArray, val h: IntArray, var turn: Int) {
        fun copy() = State(b.copyOf(), h.copyOf(), turn)
        fun hand(side: Int, t: Int) = h[side * 5 + t]
        /** Unique number for the position (board, benches, side to move). */
        fun key(): Long {
            var k = 0L
            for (p in b) k = k * 16 + p
            for (side in 0..1) for (t in 2..4) k = k * 3 + h[side * 5 + t].coerceIn(0, 2)
            return k * 2 + turn
        }
        override fun equals(other: Any?) = other is State && b.contentEquals(other.b) && h.contentEquals(other.h) && turn == other.turn
        override fun hashCode() = key().hashCode()
    }

    /** from = -1 for a drop; drop = the type dropped (0 for a board move). */
    data class Move(val from: Int, val to: Int, val drop: Int)

    fun start(): State {
        val b = IntArray(12)
        b[0] = DOLPHIN or 8; b[1] = LEADER or 8; b[2] = WHALE or 8; b[4] = TURTLE or 8
        b[7] = TURTLE; b[9] = WHALE; b[10] = LEADER; b[11] = DOLPHIN
        return State(b, IntArray(10), 0)
    }

    fun empty(turn: Int = 0) = State(IntArray(12), IntArray(10), turn)

    fun moves(s: State): List<Move> {
        val side = s.turn
        val out = ArrayList<Move>()
        val fwd = if (side == 0) 1 else -1
        for (i in 0 until 12) {
            val p = s.b[i]
            if (p == 0 || own(p) != side) continue
            val t = typ(p)
            val r = i / 3
            val c = i % 3
            for (d in DIRS.getValue(t)) {
                val nr = r + d[0] * fwd
                val nc = c + d[1]
                if (nr < 0 || nr > 3 || nc < 0 || nc > 2) continue
                val j = nr * 3 + nc
                val q = s.b[j]
                if (q != 0 && own(q) == side) continue
                out.add(Move(i, j, 0))
            }
        }
        for (t in 2..4) {
            if (s.h[side * 5 + t] == 0) continue
            for (j in 0 until 12) if (s.b[j] == 0) out.add(Move(-1, j, t))
        }
        return out
    }

    /** The new position; also tells what was knocked off (1 = the Leader). */
    fun apply(s: State, m: Move): State {
        val n = s.copy()
        val side = s.turn
        if (m.drop != 0) {
            n.b[m.to] = m.drop or (if (side == 1) 8 else 0)
            n.h[side * 5 + m.drop]--
        } else {
            val p = n.b[m.from]
            val q = n.b[m.to]
            if (q != 0) {
                val cap = typ(q)
                if (cap != LEADER) n.h[side * 5 + (if (cap == GOLD) TURTLE else cap)]++
            }
            var np = p
            val r = m.to / 3
            if (typ(p) == TURTLE && ((side == 0 && r == 0) || (side == 1 && r == 3))) np = GOLD or (if (side == 1) 8 else 0)
            n.b[m.from] = 0
            n.b[m.to] = np
        }
        n.turn = 1 - side
        return n
    }

    fun captured(s: State, m: Move): Int = if (m.drop != 0) 0 else typ(s.b[m.to])

    fun leaderPos(s: State, side: Int): Int {
        for (i in 0 until 12) {
            val p = s.b[i]
            if (p != 0 && typ(p) == LEADER && own(p) == side) return i
        }
        return -1
    }

    /** For the side to move: 1 = has won, -1 = has lost, 0 = game goes on. */
    fun terminal(s: State): Int {
        val me = s.turn
        if (leaderPos(s, me) < 0) return -1
        if (leaderPos(s, 1 - me) < 0) return 1
        val r = leaderPos(s, me) / 3
        if ((me == 0 && r == 0) || (me == 1 && r == 3)) return 1 // my Leader survived a turn on their back row (a Try)
        return 0
    }

    /** Who has won: 0 = you, 1 = them, -1 = nobody yet. */
    fun winner(s: State): Int = when (terminal(s)) {
        1 -> s.turn
        -1 -> 1 - s.turn
        else -> -1
    }

    fun evaluate(s: State): Double {
        val me = s.turn
        var v = 0.0
        for (i in 0 until 12) {
            val p = s.b[i]
            if (p == 0) continue
            val t = typ(p)
            val sg = if (own(p) == me) 1 else -1
            if (t != LEADER) v += sg * VAL[t]
            else {
                val r = i / 3
                val adv = if (own(p) == 0) 3 - r else r
                v += sg * adv * 0.6
            }
        }
        for (t in 2..4) v += (s.h[me * 5 + t] - s.h[(1 - me) * 5 + t]) * HANDVAL[t]
        return v
    }

    class Result(val move: Move, val score: Double, val depth: Int, val nodes: Int)

    private class Entry(val d: Int, val v: Double, val f: Int, val m: Move?)

    private fun order(s: State, ms: List<Move>): MutableList<Move> =
        ms.map { m ->
            val q = s.b[m.to]
            var sc = 0.0
            if (q != 0) sc = if (typ(q) == LEADER) 1000.0 else 10 * VAL[typ(q)]
            if (m.drop != 0) sc -= 1
            sc to m
        }.sortedByDescending { it.first }.map { it.second }.toMutableList()

    private fun front(ms: MutableList<Move>, m: Move?) {
        if (m == null) return
        val i = ms.indexOf(m)
        if (i > 0) ms.add(0, ms.removeAt(i))
    }

    /**
     * Best move by iterative deepening, like the Coach page: stops after [maxMs] (or after
     * [maxDepth] for tests). [seen] = positions already played this match: going back to one
     * counts as a draw, so the coach avoids repeating when ahead.
     */
    fun search(s: State, maxMs: Long, maxDepth: Int = 40, seen: Set<Long> = emptySet(), clock: () -> Long = System::currentTimeMillis): Result? {
        val t0 = clock()
        val tt = HashMap<Long, Entry>()
        var nodes = 0
        var stop = false
        val path = HashSet<Long>()

        fun neg(s: State, d: Int, alpha: Double, b: Double, ply: Int): Double {
            var a = alpha
            nodes++
            if ((nodes and 2047) == 0 && clock() - t0 > maxMs) stop = true
            val term = terminal(s)
            if (term != 0) return term * (MATE - ply)
            val k = s.key()
            if (path.contains(k) || seen.contains(k)) return 0.0
            if (d <= 0) return evaluate(s)
            val e = tt[k]
            var best = Double.NEGATIVE_INFINITY
            var bm: Move? = null
            if (e != null && e.d >= d) {
                if (e.f == 0) return e.v
                if (e.f == 1 && e.v >= b) return e.v
                if (e.f == -1 && e.v <= a) return e.v
            }
            val ms = order(s, moves(s))
            front(ms, e?.m)
            if (ms.isEmpty()) return -(MATE - ply)
            val a0 = a
            path.add(k)
            for (m in ms) {
                val v = -neg(apply(s, m), d - 1, -b, -a, ply + 1)
                if (stop) break
                if (v > best) { best = v; bm = m }
                if (v > a) a = v
                if (a >= b) break
            }
            path.remove(k)
            if (!stop) tt[k] = Entry(d, best, if (best <= a0) -1 else if (best >= b) 1 else 0, bm)
            return best
        }

        var result: Result? = null
        for (d in 1..maxDepth) {
            path.clear()
            var a = Double.NEGATIVE_INFINITY
            var best = Double.NEGATIVE_INFINITY
            var bm: Move? = null
            val ms = order(s, moves(s))
            front(ms, tt[s.key()]?.m)
            path.add(s.key())
            for (m in ms) {
                val v = -neg(apply(s, m), d - 1, Double.NEGATIVE_INFINITY, -a, 1)
                if (stop) break
                if (v > best) { best = v; bm = m }
                if (v > a) a = v
            }
            val chosen = bm
            if (stop || chosen == null) break
            tt[s.key()] = Entry(d, best, 0, chosen)
            result = Result(chosen, best, d, nodes)
            if (Math.abs(best) > MATE - 100) break
            if (clock() - t0 > maxMs * 0.45) break
        }
        return result?.let { Result(it.move, it.score, it.depth, nodes) }
    }

    /**
     * Every legal move with an exact score for the side to move (best first), from the last
     * depth that finished in time. Used to list the opponent's most likely replies.
     */
    fun rank(s: State, maxMs: Long, maxDepth: Int = 40, seen: Set<Long> = emptySet(), clock: () -> Long = System::currentTimeMillis): List<Pair<Move, Double>> {
        val t0 = clock()
        val all = moves(s)
        if (all.isEmpty()) return emptyList()
        var ranked: List<Pair<Move, Double>> = order(s, all).map { it to 0.0 }
        var stop = false
        var nodes = 0
        val path = HashSet<Long>()
        val tt = HashMap<Long, Entry>()
        fun neg(s: State, d: Int, alpha: Double, b: Double, ply: Int): Double {
            var a = alpha
            nodes++
            if ((nodes and 2047) == 0 && clock() - t0 > maxMs) stop = true
            val term = terminal(s)
            if (term != 0) return term * (MATE - ply)
            val k = s.key()
            if (path.contains(k) || seen.contains(k)) return 0.0
            if (d <= 0) return evaluate(s)
            val e = tt[k]
            if (e != null && e.d >= d) {
                if (e.f == 0) return e.v
                if (e.f == 1 && e.v >= b) return e.v
                if (e.f == -1 && e.v <= a) return e.v
            }
            val ms = order(s, moves(s))
            front(ms, e?.m)
            if (ms.isEmpty()) return -(MATE - ply)
            var best = Double.NEGATIVE_INFINITY
            var bm: Move? = null
            val a0 = a
            path.add(k)
            for (m in ms) {
                val v = -neg(apply(s, m), d - 1, -b, -a, ply + 1)
                if (stop) break
                if (v > best) { best = v; bm = m }
                if (v > a) a = v
                if (a >= b) break
            }
            path.remove(k)
            if (!stop) tt[k] = Entry(d, best, if (best <= a0) -1 else if (best >= b) 1 else 0, bm)
            return best
        }
        for (d in 1..maxDepth) {
            val out = ArrayList<Pair<Move, Double>>()
            path.clear()
            path.add(s.key())
            for ((m, _) in ranked) {
                val v = -neg(apply(s, m), d - 1, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 1)
                if (stop) break
                out.add(m to v)
            }
            if (stop) break
            ranked = out.sortedByDescending { it.second }
            if (ranked.all { Math.abs(it.second) > MATE - 100 }) break
            if (clock() - t0 > maxMs * 0.45) break
        }
        return ranked
    }

    /** "Dolphin c2 → c3" style text; squares are named like the game screen (column 1 = your side). */
    fun moveText(s: State, m: Move): String {
        if (m.drop != 0) return "Drop ${NAMES[m.drop]} on ${square(m.to)}"
        val p = s.b[m.from]
        val q = s.b[m.to]
        return "${NAMES[typ(p)]} ${square(m.from)} → ${square(m.to)}" + (if (q != 0) " (takes ${NAMES[typ(q)]})" else "")
    }

    /** Screen column (0 = your back row, on the left) and row (0 = top) of a board index. */
    fun screenX(i: Int) = 3 - i / 3
    fun screenY(i: Int) = i % 3
    fun index(x: Int, y: Int) = (3 - x) * 3 + y

    fun square(i: Int) = "column ${screenX(i) + 1} " + arrayOf("top", "middle", "bottom")[screenY(i)]

    /** Short square name for the overlay: column 1..4 from your side + T/M/B (top, middle, bottom), e.g. "3M". */
    fun short(i: Int) = "${screenX(i) + 1}" + "TMB"[screenY(i)]

    /** Short move text for the overlay list, e.g. "Leader 4B → 3M · takes Gold Turtle". */
    fun shortText(s: State, m: Move): String {
        if (m.drop != 0) return "Drop ${NAMES[m.drop]} → ${short(m.to)}"
        val q = s.b[m.to]
        return "${NAMES[typ(s.b[m.from])]} ${short(m.from)} → ${short(m.to)}" + (if (q != 0) " · takes ${NAMES[typ(q)]}" else "")
    }
}
