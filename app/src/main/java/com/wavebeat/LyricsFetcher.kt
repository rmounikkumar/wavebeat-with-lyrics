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

    private const val USER_AGENT = "WaveBeat/1.0.9 (Android music player; personal use)"
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

            // 1. Search first — most reliable when the local metadata is messy.
            fun pickFromSearch(q: String): String? {
                val url = "https://lrclib.net/api/search?q=" + URLEncoder.encode(q, "UTF-8")
                return httpGet(url)?.let { body ->
                    val arr = JSONArray(body)
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
                    val hit = best?.optString("syncedLyrics")
                    if (isActuallySynced(hit)) return hit
                    // No timed record exists on LRCLIB: still show the best matching
                    // plain lyrics when the app has Online Lyrics enabled — better
                    // than "No lyrics found" for songs LRCLIB only stores untimed.
                    val plain = bestPlain?.optString("plainLyrics")
                    if (!plain.isNullOrBlank()) return plain
                    null
                }
            }

            pickFromSearch(searchText)?.let { return it }

            // Some downloaded titles carry junk that makes the strict query come
            // back empty (e.g. "My Stupid Heart (Ft. LAUV) - Walk off the Earth").
            // Retry with a relaxed query: first " - " segment, no parenthesized
            // text, no artist term — LRCLIB then finds the real track.
            val relaxed = cleanTitle
                .split(" - ").first()
                .replace(Regex("""\s*\([^)]*\)\s*"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim(' ', '-', '_', '.')
            if (relaxed.isNotBlank() && relaxed != searchText) {
                pickFromSearch(relaxed)?.let { return it }
            }

            // 2. Fall back to the exact-match endpoint (single record).
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
                    val obj = JSONObject(body)
                    val synced = obj.optString("syncedLyrics")
                    if (isActuallySynced(synced)) return synced
                    val plain = obj.optString("plainLyrics")
                    if (!plain.isNullOrBlank()) return plain
                }
            }

            null
        } catch (_: Exception) {
            null
        }
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