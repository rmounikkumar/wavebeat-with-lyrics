package com.wavebeat

/**
 * A single timed lyric line from an LRC file.
 */
data class LrcLine(val timeMs: Long, val text: String)

/**
 * Parser for LRC (LyRiCs) files — the synchronized lyrics format produced by
 * LRC generators (e.g. the one used in the tutorial video).
 *
 * Format reference:
 *   [mm:ss.xx] line text
 *   [mm:ss.xx][mm:ss.xxx] repeated text   (multiple timestamps per line)
 */
object LrcParser {

    // Matches one or more [mm:ss.xx] time tags at the start (or anywhere) of a line.
    private val timeTagRegex = Regex("""\[(\d{1,2}):(\d{1,2}(?:[.:]\d{1,3})?)\]""")

    private fun parseTimestamp(mm: String, ss: String): Long {
        val minutes = mm.toLongOrNull() ?: 0L
        val splitIdx = ss.indexOf(':').let { if (it >= 0) it else ss.indexOf('.') }
        val seconds = if (splitIdx >= 0) {
            ss.substring(0, splitIdx).toLongOrNull() ?: 0L
        } else {
            ss.toLongOrNull() ?: 0L
        }
        val frac = if (splitIdx >= 0) ss.substring(splitIdx + 1) else ""
        val fracMs = if (frac.isNotEmpty()) {
            frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        } else {
            0L
        }
        return minutes * 60_000L + seconds * 1000L + fracMs
    }

    /**
     * Parses raw LRC text into a sorted list of timed lines.
     * Lines without any [mm:ss] tag are ignored. Empty lines are ignored.
     */
    fun parse(lrcText: String): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()
        lrcText.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val matches = timeTagRegex.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val text = line.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) return@forEach
            matches.forEach { m ->
                val ms = parseTimestamp(m.groupValues[1], m.groupValues[2])
                lines.add(LrcLine(ms, text))
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    /**
     * Returns the index of the line that is active at [positionMs],
     * or -1 when the position is before the first timed line.
     */
    fun getCurrentLineIndex(lines: List<LrcLine>, positionMs: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) idx = i else break
        }
        return idx
    }

    /** Renders timed lines back to plain multi-line text (one line per entry). */
    fun toPlainText(lines: List<LrcLine>): String =
        lines.joinToString("\n") { it.text }
}