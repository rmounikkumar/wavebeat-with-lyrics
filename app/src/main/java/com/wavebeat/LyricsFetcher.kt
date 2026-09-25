package com.wavebeat

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches synchronized lyrics (LRC) from the public LRCLIB API
 * (https://lrclib.net/api) as a last-resort fallback for songs that have
 * neither a sibling .lrc file nor lyrics embedded in the audio file.
 *
 * Runs on the caller's (background) thread. Never throws: any failure
 * (offline, restricted song, no match, malformed response) returns null so
 * the app simply falls back to "No lyrics found in this track".
 */
object LyricsFetcher {

    private const val USER_AGENT = "WaveBeat/1.0.13 (Android music player; personal use)"
    private const val TIMEOUT_MS = 8000

    /**
     * True when the text actually contains at least one timed line
     * ("[mm:ss.xx] ..."). LRCLIB sometimes stores plain, untimed text in
     * the syncedLyrics field; those records would be shown unsynchronized,
     * so they are treated as "no synced lyrics available".
     */
    private val timedLineRegex = Regex("""\[\d{1,2}:\d{2}([.:]\d{1,3})?\]""")

    private fun isActuallySynced(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return timedLineRegex.containsMatchIn(text)
    }

    /**
     * Returns lyrics for the given song, or null when unavailable.
     *
     * Synced (timed) lyrics are preferred. When LRCLIB only stores plain,
     * untimed lyrics for the best-matching record, those are returned instead
     * (the app shows them as static text) so the song still gets lyrics.
     *
     * @param title      song title from the media store (may contain download junk)
     * @param artist     song artist (may be "<unknown>" or blank)
     * @param durationMs song duration in milliseconds (used to prefer the right version)
     */
    fun resolveSongLyrics(title: String, artist: String, durationMs: Long): String? {
        return try {
            val cleanTitle = cleanQuery(title)
            val cleanArtist = cleanQuery(artist)
                .takeUnless { it.isBlank() || it.equals("<unknown>", ignoreCase = true) }
                // YouTube auto-generated channels are named "Artist - Topic";
                // the word "Topic" pollutes the search and returns zero hits.
                ?.replace(Regex("""(?i)\s*[-–]\s*topic\s*$"""), "")
                ?.trim()
            val searchText = listOf(cleanTitle, cleanArtist)
                .filter { !it.isNullOrBlank() }
                .joinToString(" ")
            if (searchText.isBlank()) return null
            val durationSec = if (durationMs > 0L) durationMs / 1000.0 else 0.0
            val titleTokens = titleTokens(cleanTitle)

            // Segments of "Artist - Song" style titles, used for the relaxed
            // retries below ("My Stupid Heart (Ft. LAUV) - Walk off the Earth").
            val segments = cleanTitle.split(" - ").map { seg ->
                seg.replace(Regex("""\s*\([^)]*\)\s*"""), " ")
                    .trim(' ', '-', '_', '.')
            }.filter { it.isNotBlank() && it != searchText }
            val firstSegment = segments.firstOrNull()
            val lastSegment = if (segments.size > 1) segments.last() else null
            val firstAllowed = if (firstSegment != null) {
                val a = titleTokens - titleTokens(firstSegment)
                if (a.isEmpty()) titleTokens else a
            } else {
                emptySet()
            }
            val slug = slugQuery(title)

            // Every query is evaluated; the best (rank, key) is kept, so an exact
            // duration record found by a later query beats an approximate one
            // found earlier. A rank-0 pick (exact duration, complete synced) is
            // the ideal outcome and stops the search right away.
            var candidates: MutableList<Pick>? = null
            fun tryGold(p: Pick?): String? {
                if (p == null) return null
                if (p.rank == 0) return pickText(p)
                val list = candidates ?: mutableListOf<Pick>().also { candidates = it }
                list.add(p)
                return null
            }

            // 1. Strict query (clean title + artist).
            tryGold(pickFromSearch(searchText, titleTokens, durationSec, false, false))?.let { return it }

            // 2. Relaxed retry on the first segment, only accepting records that
            //    still match a word from the rest of the title. Without this an
            //    artist-only query ("OneRepublic") can return the artist's *other*
            //    songs ("Counting Stars").
            if (firstSegment != null) {
                tryGold(pickFromSearch(firstSegment, firstAllowed, durationSec, true, false))?.let { return it }
            }

            // 3. Relaxed retry on the last segment ("Artist - Song" titles need
            //    the song half; VEVO-style artist suffixes poison the strict query).
            if (lastSegment != null) {
                tryGold(pickFromSearch(lastSegment, titleTokens, durationSec, true, false))?.let { return it }
            }

            // 4. Title only, without the artist term ("OneRepublic - If I Lose
            //    Myself" when the artist is "OneRepublicVEVO").
            if (cleanTitle.isNotBlank()) {
                tryGold(pickFromSearch(cleanTitle, titleTokens, durationSec, false, false))?.let { return it }
            }

            // 5. Fall back to the exact-match endpoint (single record).
            if (cleanTitle.isNotBlank()) {
                var getUrl = "https://lrclib.net/api/get?track_name=" +
                        URLEncoder.encode(cleanTitle, "UTF-8")
                if (!cleanArtist.isNullOrBlank()) {
                    getUrl += "&artist_name=" + URLEncoder.encode(cleanArtist, "UTF-8")
                }
                if (durationSec > 0.0) {
                    getUrl += "&duration=" + durationSec
                }
                httpGet(getUrl)?.let { body ->
                    try {
                        val obj = JSONObject(body)
                        tryGold(pickBest(listOf(obj), titleTokens, durationSec, false, false))?.let { return it }
                    } catch (_: Exception) {
                        // Malformed single-record response: ignore.
                    }
                }
            }

            // 6. Junk slug titles ("...-128-ytshorts.savetube.me"): rebuild a
            //    natural-language query. Only exact-duration records are accepted
            //    here, so a different-length version is never mis-tagged.
            if (slug != null) {
                tryGold(pickFromSearch(slug, titleTokens, durationSec, false, true))?.let { return it }
            }

            // Best of everything found above (strongest rank wins).
            candidates?.let { list ->
                var best: Pick? = null
                for (p in list) {
                    if (best == null || pickLess(p, best)) best = p
                }
                best?.let { return pickText(it) }
            }

            // Last resort: pre-fix behaviour on the strict query only, so a song
            // that previously resolved (even to an odd record) is unchanged.
            pickLenient(searchText, durationSec)?.let { return pickText(it) }

            null
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------
    // Candidate ranking
    // ---------------------------------------------------------------------
    //
    // A record from LRCLIB is judged by:
    //   - duration match     (exact <= 1.5s, strong <= tolerance, weak <= 10%)
    //   - coverage           last timed line vs the record's own duration
    //   - title-token match  a word of the local title appears in the record
    //
    // rank 0: exact-duration synced, covers the song            -> synced
    // rank 2: exact-duration plain (outranks a slightly-off synced version)
    // rank 3: strong-duration synced, covers the song           -> synced
    // rank 4: strong-duration synced but stops early            -> its plain
    // rank 5: weak-duration synced, covers the song             -> synced
    // rank 6: weak-duration synced but stops early              -> its plain
    // rank 7: any other plain                                   -> plain

    private const val COVERAGE_GAP_LIMIT_S = 50.0
    private const val EXACT_DIFF_S = 1.5

    private val timeTagRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]""")

    private val stopWords = setOf(
        "the", "and", "with", "feat", "ft", "you", "your", "my", "me", "of", "to", "in", "on",
        "for", "is", "it", "not", "a",
        "official", "lyrics", "lyric", "video", "audio", "music", "visualizer", "kbps", "remix",
        "edit", "version", "live", "cover", "remaster", "remastered", "explicit", "vevo", "topic",
        "mv", "hq", "hd", "tiktok", "performance", "soundtrack", "full"
    )

    private val junkTerms = listOf(
        "official", "lyrics", "lyric", "video", "audio", "8d", "3d", "remix", "live", "cover",
        "vevo", "kbps", "hq", "hd", "visualizer", "deadpool", "soundtrack", "_"
    )

    private val slugSuffixJunk = setOf(
        "128", "320", "official", "lyrics", "lyric", "video", "audio", "cover", "remix",
        "visualizer", "vevo", "kbps", "hd", "hq", "mv", "music", "full", "live", "performance"
    )

    private class Pick(val rank: Int, val key: List<Double>, val item: JSONObject)

    /** Informative (>=3 chars, non-stopword) lowercase tokens of a text. */
    private fun titleTokens(text: String): Set<String> =
        text.lowercase()
            .split(Regex("""[^a-z0-9]+"""))
            .filter { it.length >= 3 && it !in stopWords }
            .toSet()

    /** Last [mm:ss.xx] timestamp found in LRC text (milliseconds), or null. */
    private fun lastTimestampMs(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        var last: Long? = null
        for (m in timeTagRegex.findAll(text)) {
            val minutes = m.groupValues[1].toLongOrNull() ?: 0L
            val seconds = m.groupValues[2].toLongOrNull() ?: 0L
            var fraction = m.groupValues[3]
            if (fraction.isEmpty()) fraction = "0"
            while (fraction.length < 3) fraction += "0"
            val ms = minutes * 60000L + seconds * 1000L + (fraction.take(3).toLongOrNull() ?: 0L)
            if (last == null || ms > last) last = ms
        }
        return last
    }

    private fun durationTolerance(durationSec: Double): Double =
        if (durationSec > 0.0) maxOf(5.0, minOf(10.0, durationSec * 0.02)) else 0.0

    /** Weak-duration records are only trusted when not too far off the local file. */
    private fun weakDiffLimit(durationSec: Double): Double =
        if (durationSec > 0.0) maxOf(15.0, durationSec * 0.10) else 15.0

    private fun isJunkName(trackName: String): Boolean {
        val n = trackName.lowercase()
        return junkTerms.any { n.contains(it) }
    }

    /** Element-wise comparison; true when a is strictly better than b. */
    private fun betterKey(a: List<Double>, b: List<Double>): Boolean {
        for (i in 0 until minOf(a.size, b.size)) {
            if (a[i] < b[i]) return true
            if (a[i] > b[i]) return false
        }
        return false
    }

    private fun pickLess(a: Pick, b: Pick): Boolean =
        if (a.rank != b.rank) a.rank < b.rank else betterKey(a.key, b.key)

    /**
     * Evaluates candidate records and returns the best usable one (or null).
     *
     * @param requireToken when true (relaxed queries) a title-token match is
     *                     mandatory: a merely duration-matching record of a
     *                     *different* song must not win.
     * @param strongOnly   when true (slug queries) only duration-matching
     *                     records are considered.
     */
    private fun pickBest(
        items: List<JSONObject>,
        allowedTokens: Set<String>,
        durationSec: Double,
        requireToken: Boolean,
        strongOnly: Boolean
    ): Pick? {
        val t = durationTolerance(durationSec)
        val wl = weakDiffLimit(durationSec)
        val buckets = mutableMapOf<Int, Pair<List<Double>, JSONObject>>()
        for (item in items) {
            if (item.optBoolean("instrumental")) continue
            val itemDur = item.optDouble("duration", 0.0)
            val diff = if (durationSec > 0.0 && itemDur > 0.0) {
                Math.abs(itemDur - durationSec)
            } else {
                Double.MAX_VALUE
            }
            val strong = itemDur > 0.0 && diff <= t
            val exact = itemDur > 0.0 && durationSec > 0.0 && diff <= EXACT_DIFF_S
            if (strongOnly && !strong) continue
            val name = item.optString("trackName").lowercase()
            val tokenMatch = allowedTokens.any { name.contains(it) }
            if (requireToken) {
                if (!tokenMatch) continue
            } else {
                if (!(strong || tokenMatch)) continue
            }

            val synced = item.optString("syncedLyrics")
            if (isActuallySynced(synced)) {
                val lastMs = lastTimestampMs(synced) ?: continue
                val gap = if (itemDur > 0.0) itemDur - lastMs / 1000.0 else Double.MAX_VALUE
                val lines = synced.lines().count { it.isNotBlank() }
                val junk = if (isJunkName(item.optString("trackName"))) 1.0 else 0.0
                val covOk = gap <= COVERAGE_GAP_LIMIT_S
                val rank: Int
                val key: List<Double>
                when {
                    exact && covOk -> { rank = 0; key = listOf(0.0, gap, junk, -lines.toDouble()) }
                    strong && covOk -> { rank = 3; key = listOf(0.0, gap, junk, -lines.toDouble()) }
                    strong -> { rank = 4; key = listOf(0.0, gap, junk, -lines.toDouble()) }
                    covOk && diff <= wl -> { rank = 5; key = listOf(diff, gap, junk, -lines.toDouble()) }
                    diff <= wl -> { rank = 6; key = listOf(diff, gap, junk, -lines.toDouble()) }
                    else -> continue
                }
                val cur = buckets[rank]
                if (cur == null || betterKey(key, cur.first)) buckets[rank] = key to item
            } else {
                val plain = item.optString("plainLyrics")
                if (plain.isNullOrBlank()) continue
                val junk = if (isJunkName(item.optString("trackName"))) 1.0 else 0.0
                val rank = if (exact) 2 else 7
                val key = listOf(diff, 0.0, junk, 0.0)
                val cur = buckets[rank]
                if (cur == null || betterKey(key, cur.first)) buckets[rank] = key to item
            }
        }
        if (buckets.isEmpty()) return null
        val minRank = buckets.keys.minOrNull() ?: return null
        val (key, item) = buckets[minRank] ?: return null
        return Pick(minRank, key, item)
    }

    private fun pickFromSearch(
        q: String,
        allowedTokens: Set<String>,
        durationSec: Double,
        requireToken: Boolean,
        strongOnly: Boolean
    ): Pick? {
        val url = "https://lrclib.net/api/search?q=" + URLEncoder.encode(q, "UTF-8")
        val body = httpGet(url) ?: return null
        val arr = try {
            JSONArray(body)
        } catch (_: Exception) {
            return null
        }
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        if (items.isEmpty()) return null
        return pickBest(items, allowedTokens, durationSec, requireToken, strongOnly)
    }

    /** Pre-fix behaviour: best duration match, gates disabled (last resort). */
    private fun pickLenient(q: String, durationSec: Double): Pick? {
        val url = "https://lrclib.net/api/search?q=" + URLEncoder.encode(q, "UTF-8")
        val body = httpGet(url) ?: return null
        val arr = try {
            JSONArray(body)
        } catch (_: Exception) {
            return null
        }
        var best: JSONObject? = null
        var bestDiff = Double.MAX_VALUE
        var bestPlain: JSONObject? = null
        var bestPlainDiff = Double.MAX_VALUE
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optBoolean("instrumental")) continue
            val itemDur = item.optDouble("duration", 0.0)
            val diff = if (durationSec > 0.0 && itemDur > 0.0) {
                Math.abs(itemDur - durationSec)
            } else {
                0.0
            }
            val synced = item.optString("syncedLyrics")
            if (isActuallySynced(synced)) {
                if (diff < bestDiff) {
                    bestDiff = diff
                    best = item
                }
            } else if (!item.optString("plainLyrics").isNullOrBlank()) {
                if (diff < bestPlainDiff) {
                    bestPlainDiff = diff
                    bestPlain = item
                }
            }
        }
        if (best != null) return Pick(-1, listOf(0.0), best)
        if (bestPlain != null) return Pick(99, listOf(0.0), bestPlain)
        return null
    }

    /**
     * Junk slug titles from download sites carry no usable metadata:
     *   "coldplay-hymn-for-the-weekend-lyrics-128-ytshorts.savetube.me"
     * Rebuild a natural-language query from the slug parts.
     */
    private fun slugQuery(title: String): String? {
        val t = title.trim()
        val low = t.lowercase()
        val slugLike = low.contains("savetube") || low.contains("ytshorts") ||
            (Regex("""^[a-z0-9_\-]+$""").matches(low) && low.count { it == '-' } >= 2 && low.length >= 6)
        if (!slugLike) return null
        var s = low.replace(".savetube.me", "").replace("ytshorts", "").replace("-128", "")
        var parts = s.split('-', '_').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        while (parts.isNotEmpty() && parts.last() in slugSuffixJunk) {
            parts = parts.dropLast(1)
        }
        if (parts.size < 2) return null
        return parts.joinToString(" ")
    }

    /** Lyric text of a chosen record, or null when unusable. */
    private fun pickText(p: Pick): String? {
        val synced = p.item.optString("syncedLyrics")
        if (p.rank == -1 || p.rank == 0 || p.rank == 3 || p.rank == 5) {
            if (isActuallySynced(synced)) return synced
        }
        val plain = p.item.optString("plainLyrics")
        if (!plain.isNullOrBlank()) return plain
        return null
    }

    /**
     * Strips common download-junk tags from titles/artists so the search
     * query matches the clean track on LRCLIB:
     *   "Shape of You (Official Music Video) [xYz] - 8D Audio (320 kbps)"
     *   -> "Shape of You"
     */
    internal fun cleanQuery(input: String): String {
        var t = input.trim()
        // Remove bracketed annotations: [Official Video], [lyrics], etc.
        t = t.replace(Regex("""\s*\[[^\]]*\][\s-]*"""), " ")
        // Remove parenthesized annotations that contain known tag words,
        // while keeping meaningful ones such as "(feat. X)".
        t = t.replace(
            Regex(
                """\s*\([^)]*?(?i:official|lyrics?|audio|video|visuali[sz]er|kbps|hq|hd|8d|3d|remaster|explicit|mix|edit|version|remix|sped|slowed|tiktok|vevo|live|performance|cover|full)[^)]*?\)\s*"""
            ),
            " "
        )
        // Collapse repeated separators and whitespace.
        t = t.replace(Regex("""\s+"""), " ")
        return t.trim(' ', '-', '_', '.')
    }

    private fun httpGet(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.use { ins ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val read = ins.read(chunk)
                    if (read == -1) break
                    buffer.write(chunk, 0, read)
                }
                buffer.toString("UTF-8")
            }
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}