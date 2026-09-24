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

    private const val USER_AGENT = "WaveBeat/1.0.6 (Android music player; personal use)"
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
     * Returns synced LRC text for the given song, or null when unavailable.
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
            val searchText = listOf(cleanTitle, cleanArtist)
                .filter { !it.isNullOrBlank() }
                .joinToString(" ")
            if (searchText.isBlank()) return null
            val durationSec = if (durationMs > 0L) durationMs / 1000.0 else 0.0

            // 1. Search first — most reliable when the local metadata is messy.
            val searchUrl = "https://lrclib.net/api/search?q=" +
                    URLEncoder.encode(searchText, "UTF-8")
            httpGet(searchUrl)?.let { body ->
                val arr = JSONArray(body)
                var best: JSONObject? = null
                var bestDiff = Double.MAX_VALUE
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    if (item.optBoolean("instrumental")) continue
                    val synced = item.optString("syncedLyrics")
                    if (!isActuallySynced(synced)) continue
                    val itemDur = item.optDouble("duration", 0.0)
                    val diff = if (durationSec > 0.0 && itemDur > 0.0) {
                        Math.abs(itemDur - durationSec)
                    } else {
                        0.0
                    }
                    if (diff < bestDiff) {
                        bestDiff = diff
                        best = item
                    }
                }
                val hit = best?.optString("syncedLyrics")
                if (isActuallySynced(hit)) return hit
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