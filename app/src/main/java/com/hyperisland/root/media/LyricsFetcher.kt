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
 *  2. SyncLRC       — free, no key, synced/plain (https://api.synclrc.dev)
 *  3. NetEase       — free unofficial, strong Asia coverage (music.163.com)
 *  4. LrcShare      — free, no key, search → song detail LRC (https://api.lrcshare.com)
 *  5. lyrics.ovh    — free, no key, plain text only (https://api.lyrics.ovh)
 *  6. LRC.cx        — public aggregator proxy (https://api.lrc.cx)
 *  7. Genius        — plain text; needs optional client access token (docs.genius.com)
 *
 * Results are cached in-memory by (title|artist) for the process lifetime.
 * When synced LRC is available, [lineAt] returns the line active at [positionMs].
 */
object LyricsFetcher {

    private const val USER_AGENT = "HyperIsland/1.4.0 (Android; open-source lyrics client)"
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

    /**
     * Optional Genius Client Access Token (free at https://genius.com/api-clients).
     * Leave null to skip Genius. Plain text only.
     */
    @Volatile
    var geniusAccessToken: String? = null

    private fun cacheKey(title: String, artist: String): String =
        "${title.trim().lowercase()}|${artist.trim().lowercase()}"

    /**
     * Fetch lyrics for [title]/[artist]. Optional [durationSec] improves matching.
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

    // ── Source chain ─────────────────────────────────────────────────────

    private fun trySources(title: String, artist: String, durationSec: Int): LyricsResult? {
        val cleanTitle = sanitize(title)
        val cleanArtist = sanitize(artist)
        if (cleanTitle.isBlank()) return null

        // 1) LRCLIB — always first
        tryLrclib(cleanTitle, cleanArtist, durationSec)?.let { return it }

        // 2) SyncLRC
        trySyncLrc(cleanTitle, cleanArtist, durationSec)?.let { return it }

        // 3) NetEase (Asia-strong)
        tryNetease(cleanTitle, cleanArtist)?.let { return it }

        // 4) LrcShare
        tryLrcShare(cleanTitle, cleanArtist)?.let { return it }

        // 5) lyrics.ovh
        tryLyricsOvh(cleanTitle, cleanArtist)?.let { return it }

        // 6) LRC.cx
        tryLrcCx(cleanTitle, cleanArtist)?.let { return it }

        // 7) Genius (optional token)
        tryGenius(cleanTitle, cleanArtist)?.let { return it }

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

    // ── SyncLRC ──────────────────────────────────────────────────────────

    private fun trySyncLrc(title: String, artist: String, durationSec: Int): LyricsResult? {
        return try {
            val params = buildString {
                append("track=").append(enc(title))
                append("&artist=").append(enc(artist))
                append("&type=synced")
                if (durationSec > 0) append("&duration=").append(durationSec)
            }
            val body = httpGet("https://api.synclrc.dev/lyrics?$params")
                ?: return trySyncLrcSearch(title, artist)
            val obj = JSONObject(body)
            if (obj.optBoolean("instrumental", false)) return null
            val synced = obj.optString("synced", "").takeIf { it.isNotBlank() }
                ?: obj.optString("karaoke", "").takeIf { it.isNotBlank() }
            val plain = obj.optString("plain", "").takeIf { it.isNotBlank() }
            if (synced == null && plain == null) return trySyncLrcSearch(title, artist)
            val lines = if (synced != null) parseLrc(synced) else emptyList()
            LyricsResult(
                lines = lines,
                plain = plain ?: lines.joinToString("\n") { it.text },
                source = "synclrc",
                isSynced = lines.isNotEmpty()
            )
        } catch (t: Throwable) {
            CrashLogger.d("LyricsFetcher SyncLRC failed: ${t.message}")
            trySyncLrcSearch(title, artist)
        }
    }

    private fun trySyncLrcSearch(title: String, artist: String): LyricsResult? {
        return try {
            val q = enc("$title $artist")
            val body = httpGet("https://api.synclrc.dev/search?q=$q&limit=5") ?: return null
            val arr = JSONObject(body).optJSONArray("results") ?: return null
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                if (item.optBoolean("instrumental", false)) continue
                val lyrics = item.optJSONObject("lyrics")
                val synced = lyrics?.optString("synced", "")?.takeIf { it.isNotBlank() }
                    ?: item.optString("synced", "").takeIf { it.isNotBlank() }
                val plain = lyrics?.optString("plain", "")?.takeIf { it.isNotBlank() }
                    ?: item.optString("plain", "").takeIf { it.isNotBlank() }
                if (synced.isNullOrBlank() && plain.isNullOrBlank()) continue
                val lines = if (!synced.isNullOrBlank()) parseLrc(synced) else emptyList()
                return LyricsResult(
                    lines = lines,
                    plain = plain ?: lines.joinToString("\n") { it.text },
                    source = "synclrc-search",
                    isSynced = lines.isNotEmpty()
                )
            }
            null
        } catch (t: Throwable) {
            CrashLogger.d("LyricsFetcher SyncLRC search failed: ${t.message}")
            null
        }
    }

    // ── NetEase Cloud Music ──────────────────────────────────────────────

    private fun tryNetease(title: String, artist: String): LyricsResult? {
        return try {
            val q = enc("$title $artist")
            val searchBody = httpGet(
                "https://music.163.com/api/cloudsearch/pc?s=$q&type=1&limit=5&offset=0",
                extraHeaders = mapOf(
                    "Referer" to "https://music.163.com/",
                    "Cookie" to "os=pc"
                )
            ) ?: return null
            val songs = JSONObject(searchBody)
                .optJSONObject("result")
                ?.optJSONArray("songs")
                ?: return null
            if (songs.length() == 0) return null

            var bestId = -1L
            val titleLower = title.lowercase()
            for (i in 0 until songs.length()) {
                val song = songs.getJSONObject(i)
                val name = song.optString("name", "")
                if (name.equals(title, ignoreCase = true) ||
                    name.lowercase().contains(titleLower.take(12.coerceAtMost(titleLower.length)))
                ) {
                    bestId = song.optLong("id", -1L)
                    if (bestId > 0) break
                }
            }
            if (bestId <= 0) bestId = songs.getJSONObject(0).optLong("id", -1L)
            if (bestId <= 0) return null

            val lyricBody = httpGet(
                "https://music.163.com/api/song/lyric?id=$bestId&lv=-1&tv=-1&kv=-1&os=pc",
                extraHeaders = mapOf(
                    "Referer" to "https://music.163.com/",
                    "Cookie" to "os=pc"
                )
            ) ?: return null
            val root = JSONObject(lyricBody)
            val lrcText = root.optJSONObject("lrc")?.optString("lyric", "")?.takeIf { it.isNotBlank() }
                ?: return null
            if (lrcText.contains("纯音乐") && lrcText.length < 80) return null
            val lines = parseLrc(lrcText)
            LyricsResult(
                lines = lines,
                plain = if (lines.isEmpty()) stripLrcTags(lrcText) else lines.joinToString("\n") { it.text },
                source = "netease",
                isSynced = lines.isNotEmpty()
            )
        } catch (t: Throwable) {
            CrashLogger.d("LyricsFetcher NetEase failed: ${t.message}")
            null
        }
    }

    // ── Genius (optional token) ──────────────────────────────────────────

    private fun tryGenius(title: String, artist: String): LyricsResult? {
        val token = geniusAccessToken?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val q = enc("$title $artist")
            val searchBody = httpGet(
                "https://api.genius.com/search?q=$q",
                extraHeaders = mapOf("Authorization" to "Bearer $token")
            ) ?: return null
            val hits = JSONObject(searchBody)
                .optJSONObject("response")
                ?.optJSONArray("hits")
                ?: return null
            if (hits.length() == 0) return null

            var path: String? = null
            for (i in 0 until hits.length()) {
                val hit = hits.getJSONObject(i)
                if (hit.optString("type") != "song") continue
                val result = hit.optJSONObject("result") ?: continue
                path = result.optString("path", "").takeIf { it.isNotBlank() }
                    ?: result.optString("url", "").takeIf { it.isNotBlank() }
                if (path != null) break
            }
            if (path.isNullOrBlank()) return null

            val pageUrl = if (path.startsWith("http")) path else "https://genius.com$path"
            val html = httpGet(
                pageUrl,
                extraHeaders = mapOf("Accept" to "text/html")
            ) ?: return null

            val plain = extractGeniusLyrics(html) ?: return null
            if (plain.length < 20) return null
            LyricsResult(
                lines = emptyList(),
                plain = plain,
                source = "genius",
                isSynced = false
            )
        } catch (t: Throwable) {
            CrashLogger.d("LyricsFetcher Genius failed: ${t.message}")
            null
        }
    }

    private fun extractGeniusLyrics(html: String): String? {
        val container = Regex(
            "data-lyrics-container=\"true\"[^>]*>([\\s\\S]*?)</div>",
            RegexOption.IGNORE_CASE
        )
        val chunks = container.findAll(html).map { it.groupValues[1] }.toList()
        if (chunks.isEmpty()) return null
        val raw = chunks.joinToString("\n")
        return raw
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?[^>]+>"), "")
            .replace(
                Regex(
                    "\\[(Verse|Chorus|Bridge|Intro|Outro|Hook|Refrain)[^\\]]*]",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("&#x27;|&apos;"), "'")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .takeIf { it.length > 20 }
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
            val url = "https://api.lyrics.ovh/v1/${enc(artist)}/${enc(title)}"
            val body = httpGet(url) ?: return null
            val plain = JSONObject(body).optString("lyrics", "").takeIf { it.isNotBlank() }
                ?: return null
            LyricsResult(
                lines = emptyList(),
                plain = plain.trim(),
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
            val url = "https://api.lrc.cx/lyrics?title=${enc(title)}&artist=${enc(artist)}"
            val body = httpGet(url) ?: return null
            val trimmed = body.trim()
            if (trimmed.isBlank() || trimmed.startsWith("{") && trimmed.contains("\"error\"")) {
                return null
            }
            val lrcText = if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                obj.optString("lrc", "").takeIf { it.isNotBlank() }
                    ?: obj.optString("lyrics", "").takeIf { it.isNotBlank() }
                    ?: return null
            } else {
                trimmed
            }
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
            if (text.startsWith("[")) continue
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

    private fun httpGet(
        url: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
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
