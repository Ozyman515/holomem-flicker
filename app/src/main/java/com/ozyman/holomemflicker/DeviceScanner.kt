package com.ozyman.holomemflicker

/** One /dev/input/eventN node, parsed from `getevent -p`. */
class DeviceInfo(val path: String, val name: String) {
    val keys = HashSet<Int>()
    /** abs code -> [value, min, max] */
    val abs = HashMap<Int, IntArray>()

    override fun toString(): String =
        "$path  \"$name\"  keys=${keys.size}  abs=" +
            abs.keys.sorted().joinToString(" ") { val a = abs[it]!!; "%02x[%d..%d]=%d".format(it, a[1], a[2], a[0]) }
}

object DeviceScanner {
    fun scan(): List<DeviceInfo> {
        val p = ProcessBuilder("getevent", "-p").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return parse(text)
    }

    private val addRe = Regex("""^add device \d+: (\S+)""")
    private val nameRe = Regex("""^\s*name:\s*"(.*)"""")
    private val headRe = Regex("""^\s+([A-Z]+) \(([0-9a-fA-F]{4})\):(.*)$""")
    private val absRe = Regex("""([0-9a-fA-F]{4})\s*:\s*value (-?\d+), min (-?\d+), max (-?\d+)""")
    private val hexRe = Regex("""\b([0-9a-fA-F]{4})\b""")

    fun parse(text: String): List<DeviceInfo> {
        val out = ArrayList<DeviceInfo>()
        var pendingPath: String? = null
        var cur: DeviceInfo? = null
        var section = ""
        for (line in text.lines()) {
            val add = addRe.find(line)
            if (add != null) {
                pendingPath = add.groupValues[1]
                cur = null
                section = ""
                continue
            }
            if (cur == null) {
                val nm = nameRe.find(line)
                val path = pendingPath
                if (nm != null && path != null) {
                    cur = DeviceInfo(path, nm.groupValues[1])
                    out.add(cur)
                }
                continue
            }
            val c = cur
            if (line.trim().startsWith("input props")) {
                section = ""
                continue
            }
            val h = headRe.find(line)
            val body = if (h != null) {
                section = h.groupValues[1]
                h.groupValues[3]
            } else {
                if (!line.startsWith(" ")) section = ""
                line
            }
            when (section) {
                "KEY" -> hexRe.findAll(body).forEach { c.keys.add(it.groupValues[1].toInt(16)) }
                "ABS" -> absRe.find(body)?.let {
                    c.abs[it.groupValues[1].toInt(16)] = intArrayOf(
                        it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[4].toInt()
                    )
                }
            }
        }
        return out
    }

    /** Pick the game controller: prefer Razer/Kishi, else anything with L1 + a left stick. */
    fun pickController(devs: List<DeviceInfo>, forcedPath: String): DeviceInfo? {
        if (forcedPath.isNotEmpty()) devs.firstOrNull { it.path == forcedPath }?.let { return it }
        return devs.firstOrNull {
            (it.name.contains("kishi", true) || it.name.contains("razer", true)) &&
                it.abs.containsKey(0) && it.abs.containsKey(1)
        } ?: devs.firstOrNull {
            it.keys.contains(0x136) && it.abs.containsKey(0) && it.abs.containsKey(1)
        }
    }
}
