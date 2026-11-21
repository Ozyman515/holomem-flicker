package com.ozyman.holomemflicker

/**
 * The in-game quick menu (Select+Y): switch mode, overlay, look or open the editor
 * without touching the screen. The engine owns the highlight and runs the choices;
 * the overlay only draws it. Rows: one per mode, then Overlay, Look, Edit layout, Close.
 * D-pad up/down moves (wraps), A chooses, B or Select+Y closes.
 */
object QuickMenu {
    const val KEY_A = 0x130
    const val KEY_B = 0x131
    /** d-pad sent as buttons (some controllers); most send the hat axis instead */
    const val KEY_DPAD_UP = 0x220
    const val KEY_DPAD_DOWN = 0x221

    const val EXTRAS = 4 // Overlay, Look, Edit, Close

    sealed class Item {
        data class SetMode(val index: Int) : Item()
        object Overlay : Item()
        object Look : Item()
        object Edit : Item()
        object Close : Item()
    }

    fun count(modes: Int): Int = modes + EXTRAS

    fun item(i: Int, modes: Int): Item = when {
        i < modes -> Item.SetMode(i)
        i == modes -> Item.Overlay
        i == modes + 1 -> Item.Look
        i == modes + 2 -> Item.Edit
        else -> Item.Close
    }

    fun move(i: Int, dir: Int, count: Int): Int = if (count <= 0) 0 else ((i + dir) % count + count) % count

    /** One drawn row. [hint] is the small right-hand text (state or what happens). */
    data class Row(val label: String, val hint: String, val current: Boolean, val gapBefore: Boolean)

    fun rows(modeNames: List<String>, modeIdx: Int, overlayOn: Boolean, lookName: String): List<Row> {
        val out = ArrayList<Row>()
        modeNames.forEachIndexed { i, n -> out.add(Row(n, if (i == modeIdx) "current" else "", i == modeIdx, false)) }
        out.add(Row("Overlay", if (overlayOn) "on" else "off", false, true))
        out.add(Row("Look", lookName, false, false))
        out.add(Row("Edit layout", "", false, false))
        out.add(Row("Close", "", false, false))
        return out
    }
}
