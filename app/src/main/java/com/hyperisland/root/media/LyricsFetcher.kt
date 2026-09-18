package com.hyperisland.root.media

import com.hyperisland.root.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-source open lyrics fetcher with fallback chain.
 *
 * Sources (in order):
 *  1. LRCLIB        — free, no key, synced LRC preferred (https://lrclib.net)
 *  2. LrcShare      — free, no key, search → song detail LRC (https://api.lrcshare.com)
 *  3. lyrics.ovh    — free, no key, plain text only (https://api.lyrics.ovh)
 *  4. LRC.cx        — public aggregator proxy (https://api.lrc.cx)
 *
 * Results are cached in-memory by (title|artist) for the process lifetime.
 * When synced LRC is available, [lineAt] returns the line active at [positionMs].
 */
object LyricsFetcher {

    private const val USER_AGENT = "HyperIslandRoot/1.3 (Android; open-source lyrics client)"
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 8_000

    data class TimedLine(val startMs: Long, val text: String)

    data class LyricsResult(
        val lines: List<TimedLine>,
        val plain: String?,
        val source: String,
        val isSynced: Boolean
    ) {
        fun lineAt(positionMs: Long): String? {
            if (lines.isEmpty()) {
                return plain?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            }
            // Last line whose startMs <= position
            var best: String? = null
            for (line in lines) {
                if (line.startMs <= positionMs) best = line.text
                else break
            }
            return best
        }
    }

    private val cache = ConcurrentHashMap<String, LyricsResult?>()
    private val inflight = ConcurrentHashMap<String, Mutex>()

    private fun cacheKey(title: String, artist: String): String =
        "${title.trim().lowercase()}|${artist.trim().lowercase()}"

    /**
     * Fetch lyrics for [title]/[artist]. Optional [durationSec] improves LRCLIB matching.
     * Returns null when every source fails or the track is instrumental / unknown.
     */
    suspend fun fetch(
        title: String,
        artist: String,
        durationSec: Int = 0
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val key = cacheKey(title, artist)
        if (cache.containsKey(key)) return@withContext cache[key]

        val mutex = inflight.getOrPut(key) { Mutex() }
        mutex.withLock {
            if (cache.containsKey(key)) return@withContext cache[key]

            val result = trySources(title, artist, durationSec)
            cache[key] = result
            inflight.remove(key)
            result
        }
    }

    /** Non-suspending lookup of a previously cached result (for position ticks). */
    fun cached(title: String, artist: String): LyricsResult? =
        cache[cacheKey(title, artist)]

    fun clearCache() {
        cache.clear()
    }

    // ── Source chain ─────────────────────────────────────────────────────

    private fun trySources(title: String, artist: String, durationSec: Int): LyricsResult? {
        val cleanTitle = sanitize(title)
        val cleanArtist = sanitize(artist)
        if (cleanTitle.isBlank()) return null

        // 1) LRCLIB — best synced coverage
        tryLrclib(cleanTitle, cleanArtist, durationSec)?.let { return it }

        // 2) LrcShare — search then detail
        tryLrcShare(cleanTitle, cleanArtist)?.let { return it }

        // 3) lyrics.ovh — plain text
        tryLyricsOvh(cleanTitle, cleanArtist)?.let { return it }

        // 4) LRC.cx aggregator
        tryLrcCx(cleanTitle, cleanArtist)?.let { return it }

        CrashLogger.d("LyricsFetcher: no lyrics for \"$cleanTitle\" – \"$cleanArtist\"")
        return null
    }

    // ── LRCLIB ───────────────────────────────────────────────────────────

    private fun tryLrclib(title: String, artist: String, durationSec: Int): LyricsResult? {
        return try {
            val params = buildString {
                append("track_name=").append(enc(title))
                append("&artist_name=").append(enc(artist))
                if (durationSec > 0) append("&duration=").append(durationSec)
            }
            val body = httpGet("https://lrclib.net/api/get?$params")
                ?: return tryLrclibSearch(title, artist)

            parseLrclibJson(body, "lrclib")
        } catch (t: Throwable) {
            CrashLogger.d("LyricsFetcher LRCLIB get failed: ${t.message}")
            tryLrclibSearch(title, artist)
        }
    }

    private fun tryLrclibSearch(title: String, artist: String): LyricsResult? {
        return try {
            val q = enc("$title $artist")
            val body = httpGet("https://lrclib.net/api/search?q=$q") ?: return null
            val arr = JSONArray(body)
            if (arr.length() == 0) return null
            // Prefer first non-instrumental with synced lyrics
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optBoolean("instrumental", false)) continue
                val parsed = parseLrclibJson(obj.toString(), "lrclib-search")
                if (parsed != null) return parsed
            }
            null
        } catch (t: Throwable) {
            CrashLogger.d("LyricsFetcher LRCLIB search failed: ${t.message}")
            null
        }
    }

    private fun parseLrclibJson(body: String, source: String): LyricsResult? {
        val obj = JSONObject(body)
        if (obj.optBoolean("instrumental", false)) return null
        val synced = obj.optString("syncedLyrics", "").takeIf { it.isNotBlank() }
        val plain = obj.optString("plainLyrics", "").takeIf { it.isNotBlank() }
        if (synced == null && plain == null) return null
        val lines = if (synced != null) parseLrc(synced) else emptyList()
        return LyricsResult(
            lines = lines,
            plain = plain ?: lines.joinToString("\n") { it.text },
            source = source,
            isSynced = lines.isNotEmpty()
        )
    }

    // ── LrcShare ─────────────────────────────────────────────────────────

    private fun tryLrcShare(title: String, artist: String): LyricsResult? {
        return try {
            val url = "https://api.lrcshare.com/v1/search?type=song" +
                "&title=${enc(title)}&artist=${enc(artist)}&limit=3"
            val body = httpGet(url) ?: return null
            val root = JSONObject(body)
            if (root.optInt("code", 0) != 200) return null
            val items = root.optJSONObject("data")?.optJSONArray("items") ?: return null
            if (items.length() == 0) return null

            val songId = items.getJSONObject(0).optString("id", "")
            if (songId.isBlank()) return null

            val detailBody = httpGet("https://api.lrcshare.com/v1/song/$songId") ?: return null
            val detail = JSONObject(detailBody)
            if (detail.optInt("code", 0) != 200) return null
            val data = detail.optJSONObject("data") ?: return null

            val lrc = data.optString("lrc", "").takeIf { it.isNotBlank() }
                ?: data.optJSONArray("lyric_versions")
                    ?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val v = arr.getJSONObject(i)
                            val text = v.optString("lrc", "")
                            if (text.isNotBlank()) return@let text
                        }
                        null
                    }

            if (lrc.isNullOrBlank()) return null
            val lines = parseLrc(lrc)
            LyricsResult(
                lines = lines,
                plain = if (lines.isEmpty()) stripLrcTags(lrc) else lines.joinToString("\n") { it.text },
                source = "lrcshare",
                isSynced = lines.isNotEmpty()
            )
        } catch (t: Throwable) {
            CrashLogger.d("LyricsFetcher LrcShare failed: ${t.message}")
            null
        }
    }

    // ── lyrics.ovh ───────────────────────────────────────────────────────

    private fun tryLyricsOvh(title: String, artist: String): LyricsResult? {
        return try {
            val pathArtist = enc(artist.ifBlank { " " })
            val pathTitle = enc(title)
            val body = httpGet("https://api.lyrics.ovh/v1/$pathArtist/$pathTitle") ?: return null
            val lyrics = JSONObject(body).optString("lyrics", "").trim()
            if (lyrics.isBlank()) return null
            // Plain only — split into pseudo-lines without timestamps
            val plainLines = lyrics.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()
            if (plainLines.isEmpty()) return null
            LyricsResult(
                lines = emptyList(),
                plain = plainLines.joinToString("\n"),
                source = "lyrics.ovh",
                isSynced = false
            )
        } catch (t: Throwable) {
            CrashLogger.d("LyricsFetcher lyrics.ovh failed: ${t.message}")
            null
        }
    }

    // ── LRC.cx ───────────────────────────────────────────────────────────

    private fun tryLrcCx(title: String, artist: String): LyricsResult? {
        return try {
            // Public aggregator used by several FOSS players
            val url = "https://api.lrc.cx/lyrics?title=${enc(title)}&artist=${enc(artist)}"
            val body = httpGet(url) ?: return null
            val trimmed = body.trim()
            if (trimmed.isBlank() || trimmed.startsWith("{") && trimmed.contains("\"error\"")) {
                return null
            }
            // Response may be raw LRC text or JSON with a lyrics field
            val lrcText = if (trimmed.startsWith("{")) {
                JSONObject(trimmed).optString("lyrics", "")
                    .ifBlank { JSONObject(trimmed).optString("lrc", "") }
            } else trimmed

            if (lrcText.isBlank()) return null
            val lines = parseLrc(lrcText)
            LyricsResult(
                lines = lines,
                plain = if (lines.isEmpty()) stripLrcTags(lrcText) else lines.joinToString("\n") { it.text },
                source = "lrc.cx",
                isSynced = lines.isNotEmpty()
            )
        } catch (t: Throwable) {
            CrashLogger.d("LyricsFetcher LRC.cx failed: ${t.message}")
            null
        }
    }

    // ── LRC parser ───────────────────────────────────────────────────────

    private val LRC_LINE = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]\s*(.*)""")

    fun parseLrc(lrc: String): List<TimedLine> {
        val out = ArrayList<TimedLine>()
        for (raw in lrc.lineSequence()) {
            val m = LRC_LINE.find(raw.trim()) ?: continue
            val min = m.groupValues[1].toLongOrNull() ?: continue
            val sec = m.groupValues[2].toLongOrNull() ?: continue
            val frac = m.groupValues[3]
            val ms = when {
                frac.isEmpty() -> 0L
                frac.length == 1 -> frac.toLong() * 100
                frac.length == 2 -> frac.toLong() * 10
                else -> frac.take(3).toLong()
            }
            val text = m.groupValues[4].trim()
            if (text.isBlank()) continue
            // Skip metadata tags that slipped through ([ti:], [ar:], …)
            if (text.startsWith("[") || text.matches(Regex("""^[a-zA-Z]{2,}:.*"""))) continue
            out.add(TimedLine(min * 60_000 + sec * 1_000 + ms, text))
        }
        out.sortBy { it.startMs }
        return out
    }

    private fun stripLrcTags(lrc: String): String =
        lrc.lineSequence()
            .map { LRC_LINE.find(it.trim())?.groupValues?.get(4)?.trim() ?: it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("[") }
            .joinToString("\n")

    // ── HTTP helper ──────────────────────────────────────────────────────

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                CrashLogger.d("LyricsFetcher HTTP $code for $url")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            CrashLogger.d("LyricsFetcher network error: ${t.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun enc(s: String): String =
        URLEncoder.encode(s, Charsets.UTF_8.name()).replace("+", "%20")

    private fun sanitize(s: String): String =
        s.replace(Regex("""\s*\(.*?\)\s*"""), " ")
            .replace(Regex("""\s*\[.*?]\s*"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
}
