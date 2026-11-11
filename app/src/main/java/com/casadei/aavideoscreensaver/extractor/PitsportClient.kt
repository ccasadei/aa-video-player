package com.casadei.aavideoscreensaver.extractor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Client ed Estrattore per https://pitsport.st/ (Live Sport: calcio, F1, MotoGP, NBA, NFL, WWE, tennis...).
 *
 * - Interroga le API JSON pubbliche di pitsport.st (`/api/v1`).
 * - Risolve gli embed dinamici (es. `embdlol.st/embed/...`) in flussi .m3u8 compatibili con ExoPlayer
 *   usando HeadlessStreamSniffer in background senza toccare il telefono.
 */
object PitsportClient {

    private const val BASE_URL = "https://pitsport.st"
    private const val API_BASE = "$BASE_URL/api/v1"
    private const val RESOLVE_TIMEOUT_MS = 30_000L
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val API_CALL_TIMEOUT_SEC = 8L

    private const val TAG = "AAVideo"

    data class LiveProgram(
        val id: String,
        val title: String,
        val category: String,
        val categoryEmoji: String,
        val homeTeam: String?,
        val awayTeam: String?,
        val poster: String?,
        val startsAt: Long,
        val endsAt: Long,
        val live: Boolean,
        val streams: List<StreamOption>,
        val hasVideo: Boolean = streams.isNotEmpty()
    ) {
        val formattedTime: String
            get() {
                val startMs = if (startsAt > 10_000_000_000L) startsAt else startsAt * 1000L
                if (live) {
                    return if (startsAt > 0L) {
                        val timeStr = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(startMs))
                        "🔴 LIVE ($timeStr)"
                    } else {
                        "🔴 LIVE"
                    }
                }
                if (startsAt > 0L) {
                    val nowMs = System.currentTimeMillis()
                    val diffHours = (startMs - nowMs) / 3_600_000L
                    val fmt = if (diffHours > 20L) "dd/MM HH:mm" else "HH:mm"
                    val timeStr = SimpleDateFormat(fmt, Locale.ITALY).format(Date(startMs))
                    return "⏳ Inizia alle $timeStr"
                }
                return "📡"
            }
    }

    data class PitsportCompetition(
        val id: String,
        val name: String,
        val emoji: String
    )

    data class StreamOption(
        val id: String,
        val name: String,
        val embedUrl: String,
        val quality: String = "HD",
        val language: String = "LIVE"
    ) {
        val label: String
            get() = "📡 $name [$quality • $language]"
    }

    data class ResolvedPitsportStream(
        val streamUrl: String,
        val title: String,
        val headers: Map<String, String>,
        val effectiveEmbedUrl: String? = null
    )

    fun isPitsportUrl(url: String): Boolean {
        val lower = url.lowercase().trim()
        return lower.contains("pitsport.st") ||
            lower.contains("pitsport.live") ||
            lower.contains("pitsport.xyz") ||
            lower.contains("embdlol.st/embed/")
    }

    suspend fun fetchLiveNow(): Result<List<LiveProgram>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = getJson("$API_BASE/live-now")
            parseProgramsResponse(json, includeUpcoming = true)
        }
    }

    /**
     * Legge sia live che upcoming dall'endpoint live-now.
     * Quando non ci sono eventi in diretta, mostra comunque quelli in programma.
     */
    suspend fun fetchLiveOrUpcoming(): Result<List<LiveProgram>> = fetchLiveNow()

    suspend fun fetchHome(): Result<List<LiveProgram>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = getJson("$API_BASE/home")
            val homeList = parseProgramsResponse(json, includeUpcoming = true)
            if (homeList.isNotEmpty()) {
                homeList
            } else {
                parseProgramsResponse(getJson("$API_BASE/live-now"), includeUpcoming = true)
            }
        }
    }

    suspend fun fetchCompetitions(): Result<List<PitsportCompetition>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = getJson("$API_BASE/competitions")
            parseCompetitionsResponse(json)
        }
    }

    suspend fun fetchProgramsByCompetition(competitionId: String): Result<List<LiveProgram>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = getJson("$API_BASE/competitions/$competitionId")
            parseProgramsResponse(json, includeUpcoming = true)
        }
    }

    suspend fun fetchProgramById(programId: String): Result<LiveProgram?> = withContext(Dispatchers.IO) {
        runCatching {
            val playJson = runCatching { getJson("$API_BASE/programs/$programId/play") }.getOrNull()
            val json = if (!playJson.isNullOrBlank()) playJson else getJson("$API_BASE/programs/$programId")
            parseProgramDetailResponse(json, fallbackId = programId)
        }
    }

    internal fun parseProgramDetailResponse(json: String, fallbackId: String = ""): LiveProgram? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val data = obj.optJSONObject("data") ?: obj
        val programObj = data.optJSONObject("program") ?: data

        val id = programObj.optString("programId").ifBlank {
            programObj.optString("_id").ifBlank {
                programObj.optString("id", fallbackId)
            }
        }
        if (id.isBlank()) return null

        val title = extractProgramTitle(programObj)
        val category = extractProgramCategory(programObj)
        val homeTeam = programObj.optString("home_team", "").takeIf { it.isNotBlank() }
        val awayTeam = programObj.optString("away_team", "").takeIf { it.isNotBlank() }
        val poster = programObj.optString("background").ifBlank { programObj.optString("poster", "") }.takeIf { it.isNotBlank() }
        val startsAt = programObj.optLong("sessionStart", programObj.optLong("starts_at", programObj.optLong("start", 0L)))
        val endsAt = programObj.optLong("ends_at", 0L)
        val liveState = programObj.optString("liveState", "")
        val live = liveState.equals("live", ignoreCase = true) || programObj.optBoolean("live", false)

        val streams = mutableListOf<StreamOption>()
        val seenUrls = mutableSetOf<String>()

        fun addVideoOption(v: JSONObject, index: Int) {
            val allowed = v.optBoolean("allowed", true)
            if (!allowed) return
            val embedUrl = v.optString("embedUrl").ifBlank {
                v.optString("url").ifBlank { v.optString("iframe", "") }
            }.trim()
            if (embedUrl.isNotBlank() && seenUrls.add(embedUrl)) {
                val optId = v.optString("embedId").ifBlank {
                    v.optString("_id").ifBlank { v.optString("id", "s$index") }
                }
                val optName = v.optString("label").ifBlank {
                    v.optString("name", "Stream ${index + 1}")
                }
                val quality = if (v.optBoolean("hd", true)) "HD" else "SD"
                val lang = v.optString("language", "LIVE").ifBlank { "LIVE" }
                streams.add(
                    StreamOption(
                        id = optId,
                        name = optName,
                        embedUrl = embedUrl,
                        quality = quality,
                        language = lang
                    )
                )
            }
        }

        val videosArr = data.optJSONArray("videos") ?: programObj.optJSONArray("videos")
        if (videosArr != null) {
            for (i in 0 until videosArr.length()) {
                val v = videosArr.optJSONObject(i) ?: continue
                addVideoOption(v, i)
            }
        }
        val videoObj = data.optJSONObject("video") ?: programObj.optJSONObject("video")
        if (videoObj != null) {
            addVideoOption(videoObj, streams.size)
        }

        val hasVideo = data.optBoolean("hasVideo", programObj.optBoolean("hasVideo", streams.isNotEmpty()))

        return LiveProgram(
            id = id,
            title = title,
            category = category,
            categoryEmoji = sportEmoji(category),
            homeTeam = homeTeam,
            awayTeam = awayTeam,
            poster = poster,
            startsAt = startsAt,
            endsAt = endsAt,
            live = live,
            streams = streams,
            hasVideo = hasVideo || streams.isNotEmpty()
        )
    }

    internal fun parseCompetitionsResponse(json: String): List<PitsportCompetition> {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arr = obj.optJSONArray("data")
            ?: obj.optJSONObject("data")?.optJSONArray("competitions")
            ?: JSONArray()
        val list = mutableListOf<PitsportCompetition>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optString("competitionId").ifBlank { c.optString("id") }
            val name = c.optString("name").ifBlank { c.optString("text") }
            val slug = c.optString("slug", name)
            if (id.isNotBlank() && name.isNotBlank()) {
                list.add(
                    PitsportCompetition(
                        id = id,
                        name = name,
                        emoji = sportEmoji(slug.ifBlank { name })
                    )
                )
            }
        }
        return list
    }

    internal fun parseProgramsResponse(json: String, includeUpcoming: Boolean = true): List<LiveProgram> {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val data = obj.optJSONObject("data") ?: obj

        val rawItems = mutableListOf<JSONObject>()
        val liveArr = data.optJSONArray("live")
        if (liveArr != null) {
            for (i in 0 until liveArr.length()) {
                liveArr.optJSONObject(i)?.let { rawItems.add(it) }
            }
        }
        if (includeUpcoming) {
            val upcomingArr = data.optJSONArray("upcoming")
            if (upcomingArr != null) {
                for (i in 0 until upcomingArr.length()) {
                    upcomingArr.optJSONObject(i)?.let { rawItems.add(it) }
                }
            }
        }
        val legacyArr = data.optJSONArray("programs") ?: data.optJSONArray("events") ?: data.optJSONArray("data")
        if (legacyArr != null) {
            for (i in 0 until legacyArr.length()) {
                legacyArr.optJSONObject(i)?.let { rawItems.add(it) }
            }
        }

        val seenIds = mutableSetOf<String>()
        val parsed = mutableListOf<LiveProgram>()
        for (prog in rawItems) {
            val detail = parseProgramDetailResponse(prog.toString()) ?: continue
            if (seenIds.add(detail.id)) {
                parsed.add(detail)
            }
        }

        return parsed.sortedWith(
            compareByDescending<LiveProgram> { it.live }
                .thenByDescending { it.hasVideo }
                .thenBy { if (it.startsAt > 0L) it.startsAt else Long.MAX_VALUE }
        )
    }

    private fun extractProgramTitle(prog: JSONObject): String {
        val titleText = prog.optString("titleText", "").trim()
        if (titleText.isNotBlank()) return titleText

        val titleArr = prog.optJSONArray("title")
        if (titleArr != null && titleArr.length() > 0) {
            val parts = mutableListOf<String>()
            for (j in 0 until titleArr.length()) {
                val t = titleArr.optJSONObject(j)?.optString("text", "")?.trim().orEmpty()
                if (t.isNotBlank()) parts.add(t)
            }
            if (parts.isNotEmpty()) return parts.joinToString(" vs ")
        }

        val rawTitle = prog.optString("title", "").trim()
        if (rawTitle.isNotBlank() && !rawTitle.startsWith("[")) return rawTitle
        return "Programma"
    }

    private fun extractProgramCategory(prog: JSONObject): String {
        val footer = prog.optString("footer", "").trim()
        if (footer.isNotBlank()) return footer
        val cardFooter = prog.optString("cardFooter", "").trim()
        if (cardFooter.isNotBlank()) return cardFooter
        val compName = prog.optJSONObject("competition")?.optString("name", "")?.trim().orEmpty()
        if (compName.isNotBlank()) return compName
        return prog.optString("category", "").trim()
    }

    suspend fun searchPrograms(query: String): Result<List<LiveProgram>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanQ = query.trim().lowercase(Locale.ITALIAN)
            val live = fetchLiveNow().getOrDefault(emptyList())
            val home = fetchHome().getOrDefault(emptyList())

            val merged = LinkedHashMap<String, LiveProgram>()
            for (p in live + home) {
                if (p.id !in merged) merged[p.id] = p
            }

            if (cleanQ.isBlank()) {
                merged.values.toList()
            } else {
                merged.values.filter { program ->
                    program.title.lowercase(Locale.ITALIAN).contains(cleanQ) ||
                        program.category.lowercase(Locale.ITALIAN).contains(cleanQ) ||
                        program.categoryEmoji.contains(cleanQ)
                }
            }
        }
    }

    suspend fun resolveStream(
        context: Context,
        urlOrSpec: String,
        fallbackTitle: String = "⚽ Pitsport Live"
    ): Result<ResolvedPitsportStream> = withContext(Dispatchers.IO) {
        try {
            Result.success(withTimeout(RESOLVE_TIMEOUT_MS) { resolveInternal(context, urlOrSpec.trim(), fallbackTitle) })
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Nessuna sorgente di Pitsport ha risposto entro ${RESOLVE_TIMEOUT_MS / 1000} secondi"))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun resolveInternal(context: Context, clean: String, fallbackTitle: String): ResolvedPitsportStream {
        // 1. Se è già un .m3u8 diretto
        if (clean.contains(".m3u8", ignoreCase = true)) {
            return ResolvedPitsportStream(
                streamUrl = clean,
                title = fallbackTitle,
                headers = mapOf(
                    "Referer" to "$BASE_URL/",
                    "Origin" to BASE_URL,
                    "User-Agent" to USER_AGENT
                )
            )
        }

        // 2. Se è un embed URL, sniffalo direttamente
        if (clean.contains("embdlol.st/embed/", ignoreCase = true) ||
            (clean.contains("/embed/", ignoreCase = true) && !clean.contains("/programs/", ignoreCase = true))
        ) {
            val sniffResult = HeadlessStreamSniffer.sniffStream(
                context = context,
                targetUrl = clean,
                referer = "$BASE_URL/",
                timeoutMs = 15_000L
            )
            val sniffed = sniffResult.getOrNull()
            if (sniffed != null && isM3u8Reachable(sniffed.streamUrl, sniffed.headers)) {
                return ResolvedPitsportStream(
                    streamUrl = HeadlessStreamSniffer.normalizeStreamUrl(sniffed.streamUrl),
                    title = fallbackTitle,
                    headers = sniffed.headers,
                    effectiveEmbedUrl = clean
                )
            }
            throw IllegalStateException("Flusso Pitsport non disponibile (${sniffResult.exceptionOrNull()?.message})")
        }

        // 3. Se è un ID di programma o un URL API, risolvi tramite /programs/{id}/play
        val programId = extractProgramId(clean)
        if (programId != null) {
            val program = runCatching { fetchProgramById(programId) }.getOrNull()?.getOrNull()
            if (program != null) {
                if (program.streams.isNotEmpty()) {
                    val firstStream = program.streams[0]
                    return resolveInternal(context, firstStream.embedUrl, "⚽ ${program.title}")
                }
                if (!program.live && program.startsAt > 0L) {
                    throw IllegalStateException("${program.title}: ${program.formattedTime}")
                }
            }
        }

        // 4. Fallback: sniff diretto
        val sniffResult = HeadlessStreamSniffer.sniffStream(
            context = context,
            targetUrl = clean,
            referer = "$BASE_URL/",
            timeoutMs = 15_000L
        )
        val sniffed = sniffResult.getOrNull()
        if (sniffed != null && isM3u8Reachable(sniffed.streamUrl, sniffed.headers)) {
            return ResolvedPitsportStream(
                streamUrl = HeadlessStreamSniffer.normalizeStreamUrl(sniffed.streamUrl),
                title = fallbackTitle,
                headers = sniffed.headers,
                effectiveEmbedUrl = clean
            )
        }

        throw IllegalStateException("Impossibile risolvere il flusso Pitsport: $clean (${sniffResult.exceptionOrNull()?.message})")
    }

    private fun extractProgramId(url: String): String? {
        val segments = url.trim('/').split('/')
        for (i in segments.indices) {
            if (i > 0 && segments[i - 1] == "programs" && segments[i].isNotBlank()) {
                return segments[i]
            }
        }
        if (url.isNotBlank() && !url.contains("/")) {
            return url
        }
        return null
    }

    private fun isM3u8Reachable(m3u8Url: String, headers: Map<String, String>): Boolean {
        return runCatching {
            val reqBuilder = Request.Builder().url(m3u8Url).get()
            for ((k, v) in headers) {
                reqBuilder.header(k, v)
            }
            YouTubeStreamExtractor.okHttpClient.newBuilder()
                .callTimeout(API_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build()
                .newCall(reqBuilder.build())
                .execute()
                .use { resp ->
                    val bodyPreview = resp.peekBody(512).string()
                    resp.isSuccessful && bodyPreview.trimStart().startsWith("#EXTM3U")
                }
        }.getOrDefault(false)
    }

    private fun getJson(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$BASE_URL/")
            .header("Accept", "application/json")
            .get()
            .build()
        val call = YouTubeStreamExtractor.okHttpClient.newBuilder()
            .callTimeout(API_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
            .newCall(req)
        call.execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} da Pitsport API")
            }
            return resp.body?.string() ?: "{}"
        }
    }

    private fun sportEmoji(category: String): String {
        val c = category.lowercase(Locale.ITALIAN)
        return when {
            c.contains("football") && !c.contains("american") || c.contains("calcio") || c.contains("soccer") || c.contains("league") || c.contains("serie") -> "⚽"
            c.contains("formula") || c.contains("f1") || c.contains("f2") || c.contains("f3") || c.contains("motorsport") || c.contains("motogp") || c.contains("motori") || c.contains("nascar") || c.contains("indycar") || c.contains("wec") || c.contains("wrc") -> "🏎️"
            c.contains("basketball") || c.contains("nba") || c.contains("basket") -> "🏀"
            c.contains("tennis") -> "🎾"
            c.contains("mma") || c.contains("ufc") || c.contains("fighting") || c.contains("combattimento") -> "🥊"
            c.contains("american") || c.contains("nfl") -> "🏈"
            c.contains("wwe") || c.contains("aew") || c.contains("wrestling") -> "🤼"
            c.contains("baseball") || c.contains("mlb") -> "⚾"
            c.contains("rugby") -> "🏉"
            c.contains("golf") -> "⛳"
            c.contains("cycling") || c.contains("ciclismo") -> "🚴"
            c.contains("hockey") || c.contains("nhl") -> "🏒"
            c.contains("cricket") -> "🏏"
            c.contains("boxing") || c.contains("boxe") -> "🥊"
            else -> "📡"
        }
    }
}
