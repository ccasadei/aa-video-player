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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Client ed Estrattore per https://ppv.st/ (Sport, MMA, Wrestling, F1, MotoGP, Basket, NFL, Canali 24/7 e VOD).
 *
 * - Interroga le API JSON pubbliche di ppv.st (`api.ppv.st/api/streams`, `api.ppv.st/api/vods`).
 * - Risolve gli embed dinamici (es. `taifood-blog.asia/embed/...`) in flussi .m3u8 compatibili con ExoPlayer
 *   usando HeadlessStreamSniffer in background senza toccare il telefono.
 */
object PpvClient {

    private const val API_BASE = "https://api.ppv.st/api"
    private const val PPV_BASE = "https://ppv.st"
    private const val RESOLVE_TIMEOUT_MS = 30_000L
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val API_CALL_TIMEOUT_SEC = 8L

    private const val TAG = "AAVideo"

    private val streamFallbackEmbeds = ConcurrentHashMap<String, List<String>>()

    data class PpvStream(
        val id: String,
        val name: String,
        val category: String,
        val categoryLabel: String,
        val startsAt: Long,
        val endsAt: Long,
        val alwaysLive: Boolean,
        val viewers: Int,
        val embedUrl: String,
        val substreams: List<SubstreamOption>,
        val sourceTag: String = "",
        val isLiveNow: Boolean = alwaysLive,
        val isVod: Boolean = false
    ) {
        val formattedStatus: String
            get() {
                if (isVod) return "📺 VOD"
                if (alwaysLive) return "🔴 24/7 LIVE"
                val startMs = if (startsAt > 10_000_000_000L) startsAt else startsAt * 1000L
                if (isLiveNow) {
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
                    return "⏳ $timeStr"
                }
                return "📡"
            }
    }

    data class SubstreamOption(
        val id: String,
        val name: String,
        val embedUrl: String
    )

    data class PpvCategory(
        val id: String,
        val name: String
    )

    data class ResolvedPpvStream(
        val streamUrl: String,
        val title: String,
        val headers: Map<String, String>,
        val effectiveEmbedUrl: String? = null
    )

    fun isPpvUrl(url: String): Boolean {
        val lower = url.lowercase().trim()
        return lower.contains("ppv.st") ||
            lower.contains("ppv.tj") ||
            lower.contains("ppv.to") ||
            lower.contains("ppvs.pk") ||
            lower.contains("vod.television.st") ||
            lower.contains("taifood-blog.asia/embed/")
    }

    suspend fun fetchLiveStreams(): Result<List<PpvStream>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = getJson("$API_BASE/streams")
            val liveOnly = parseStreamsResponse(json, onlyLiveNow = true)
            if (liveOnly.isNotEmpty()) {
                liveOnly
            } else {
                parseStreamsResponse(json, onlyLiveNow = false)
            }
        }
    }

    suspend fun fetchUpcomingStreams(): Result<List<PpvStream>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = getJson("$API_BASE/streams")
            parseStreamsResponse(json, onlyLiveNow = false).filter { !it.alwaysLive }
        }
    }

    suspend fun fetchVodStreams(): Result<List<PpvStream>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = getJson("$API_BASE/vods")
            parseVodsResponse(json)
        }
    }

    suspend fun fetchCategories(): Result<List<PpvCategory>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = getJson("$API_BASE/streams")
            parseCategoriesResponse(json)
        }
    }

    suspend fun fetchStreamsByCategory(categoryId: String): Result<List<PpvStream>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = getJson("$API_BASE/streams")
            val fromStreams = parseStreamsResponse(json, onlyLiveNow = false, categoryFilter = categoryId)
            if (fromStreams.isNotEmpty()) {
                fromStreams
            } else {
                val vodJson = runCatching { getJson("$API_BASE/vods") }.getOrDefault("{}")
                parseVodsResponse(vodJson, categoryFilter = categoryId)
            }
        }
    }

    suspend fun searchStreams(query: String): Result<List<PpvStream>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanQ = query.trim().lowercase(Locale.ITALIAN)
            val allStreams = runCatching {
                parseStreamsResponse(getJson("$API_BASE/streams"), onlyLiveNow = false)
            }.getOrDefault(emptyList())
            val allVods = runCatching {
                parseVodsResponse(getJson("$API_BASE/vods"))
            }.getOrDefault(emptyList())

            (allStreams + allVods).filter { stream ->
                stream.name.lowercase(Locale.ITALIAN).contains(cleanQ) ||
                    stream.category.lowercase(Locale.ITALIAN).contains(cleanQ) ||
                    stream.categoryLabel.lowercase(Locale.ITALIAN).contains(cleanQ) ||
                    stream.sourceTag.lowercase(Locale.ITALIAN).contains(cleanQ)
            }
        }
    }

    suspend fun fetchStreamPlayUrl(stream: PpvStream): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            registerFallbackEmbeds(stream)
            if (stream.isVod || stream.embedUrl.contains("/api/vods/", ignoreCase = true)) {
                return@runCatching fetchVodPlayById(stream.id)
            }
            if (stream.embedUrl.startsWith("http://", ignoreCase = true) ||
                stream.embedUrl.startsWith("https://", ignoreCase = true)
            ) {
                return@runCatching stream.embedUrl
            }
            fetchStreamPlayById(stream.id)
        }
    }

    private fun registerFallbackEmbeds(stream: PpvStream) {
        val allEmbeds = buildList {
            if (stream.embedUrl.isNotBlank()) add(stream.embedUrl)
            stream.substreams.forEach { sub ->
                if (sub.embedUrl.isNotBlank() && sub.embedUrl !in this) {
                    add(sub.embedUrl)
                }
            }
        }
        if (allEmbeds.size > 1) {
            for (emb in allEmbeds) {
                streamFallbackEmbeds[emb] = allEmbeds
            }
        }
    }

    suspend fun resolveStream(
        context: Context,
        urlOrSpec: String,
        fallbackTitle: String = "📺 PPV Live"
    ): Result<ResolvedPpvStream> = withContext(Dispatchers.IO) {
        try {
            Result.success(withTimeout(RESOLVE_TIMEOUT_MS) { resolveInternal(context, urlOrSpec.trim(), fallbackTitle) })
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Nessuna sorgente di PPV ha risposto entro ${RESOLVE_TIMEOUT_MS / 1000} secondi"))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun resolveInternal(context: Context, clean: String, fallbackTitle: String): ResolvedPpvStream {
        // 1. Se è un URL API VOD (/api/vods/{id}), estrae il link .m3u8 diretto
        if (clean.contains("/api/vods/", ignoreCase = true)) {
            val vodId = clean.substringAfterLast("/").substringBefore("?").trim()
            if (vodId.isNotBlank()) {
                val m3u8Url = fetchVodPlayById(vodId)
                return resolveInternal(context, m3u8Url, fallbackTitle)
            }
        }

        // 2. Se è già un .m3u8 diretto
        if (clean.contains(".m3u8", ignoreCase = true)) {
            return ResolvedPpvStream(
                streamUrl = clean,
                title = fallbackTitle,
                headers = mapOf(
                    "Referer" to "$PPV_BASE/",
                    "Origin" to PPV_BASE,
                    "User-Agent" to USER_AGENT
                )
            )
        }

        // 3. Se è un embed URL (es. taifood-blog.asia/embed/...), sniffalo con eventuali substream di fallback
        if (clean.contains("taifood-blog.asia/embed/", ignoreCase = true) ||
            (clean.contains("/embed/", ignoreCase = true) && clean.startsWith("http", ignoreCase = true))
        ) {
            val candidates = buildList {
                add(clean)
                streamFallbackEmbeds[clean]?.forEach { alt ->
                    if (alt !in this) add(alt)
                }
            }.take(3)

            var lastErr: Throwable? = null
            for ((idx, candidateUrl) in candidates.withIndex()) {
                Log.d(TAG, "PPV Sniffing (${idx + 1}/${candidates.size}): $candidateUrl")
                val sniffResult = HeadlessStreamSniffer.sniffStream(
                    context = context,
                    targetUrl = candidateUrl,
                    referer = "$PPV_BASE/",
                    timeoutMs = 14_000L
                )
                val sniffed = sniffResult.getOrNull()
                if (sniffed != null && isM3u8Reachable(sniffed.streamUrl, sniffed.headers)) {
                    return ResolvedPpvStream(
                        streamUrl = HeadlessStreamSniffer.normalizeStreamUrl(sniffed.streamUrl),
                        title = fallbackTitle,
                        headers = sniffed.headers,
                        effectiveEmbedUrl = candidateUrl
                    )
                }
                lastErr = sniffResult.exceptionOrNull()
            }
            throw IllegalStateException("Flusso PPV non disponibile (${lastErr?.message ?: "nessuna risposta"})")
        }

        // 4. Se è un ID o un URL API /api/streams/{id}, risolvi tramite /play
        val streamId = extractStreamId(clean)
        if (streamId != null) {
            val playUrl = runCatching { fetchStreamPlayById(streamId) }.getOrNull()
            if (playUrl != null && playUrl != clean) {
                return resolveInternal(context, playUrl, fallbackTitle)
            }
        }

        // 5. Fallback: sniff diretto
        val sniffResult = HeadlessStreamSniffer.sniffStream(
            context = context,
            targetUrl = clean,
            referer = "$PPV_BASE/",
            timeoutMs = 15_000L
        )
        val sniffed = sniffResult.getOrNull()
        if (sniffed != null && isM3u8Reachable(sniffed.streamUrl, sniffed.headers)) {
            return ResolvedPpvStream(
                streamUrl = HeadlessStreamSniffer.normalizeStreamUrl(sniffed.streamUrl),
                title = fallbackTitle,
                headers = sniffed.headers,
                effectiveEmbedUrl = clean
            )
        }

        throw IllegalStateException("Impossibile risolvere il flusso PPV: $clean (${sniffResult.exceptionOrNull()?.message})")
    }

    private fun extractStreamId(url: String): String? {
        val segments = url.trim('/').split('/')
        for (i in segments.indices) {
            if (i > 0 && segments[i - 1] == "streams" && segments[i].isNotBlank()) {
                return segments[i]
            }
        }
        if (url.isNotBlank() && !url.contains("/")) {
            return url
        }
        return null
    }

    internal fun parsePlayUrlFromJson(json: String): String? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val direct = obj.optString("embedUrl").ifBlank {
            obj.optString("url").ifBlank { obj.optString("iframe") }
        }
        if (direct.isNotBlank()) return direct

        val dataObj = obj.optJSONObject("data") ?: return null
        val sourceObj = dataObj.optJSONObject("source")
        if (sourceObj != null) {
            val srcData = sourceObj.optString("data")
            if (srcData.isNotBlank()) return srcData
        }
        val sourcesArr = dataObj.optJSONArray("sources")
        if (sourcesArr != null) {
            for (i in 0 until sourcesArr.length()) {
                val s = sourcesArr.optJSONObject(i) ?: continue
                val d = s.optString("data").ifBlank { s.optString("url").ifBlank { s.optString("iframe") } }
                if (d.isNotBlank()) return d
            }
        }
        return null
    }

    private fun fetchStreamPlayById(streamId: String): String {
        val json = getJson("$API_BASE/streams/$streamId/play")
        return parsePlayUrlFromJson(json)
            ?: throw IllegalStateException("Nessun URL di riproduzione trovato per lo stream $streamId")
    }

    private fun fetchVodPlayById(vodId: String): String {
        val json = getJson("$API_BASE/vods/$vodId")
        return parsePlayUrlFromJson(json)
            ?: throw IllegalStateException("Nessun flusso VOD trovato per l'evento $vodId")
    }

    internal fun parseCategoriesResponse(json: String): List<PpvCategory> {
        val trimmed = json.trim()
        val categoriesArr = if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            root.optJSONArray("streams") ?: root.optJSONArray("categories") ?: JSONArray()
        } else if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONArray()
        }

        val list = mutableListOf<PpvCategory>()
        val seenIds = mutableSetOf<String>()
        for (i in 0 until categoriesArr.length()) {
            val obj = categoriesArr.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val rawName = obj.optString("category").ifBlank { obj.optString("name") }
            if (id.isNotBlank() && rawName.isNotBlank() && seenIds.add(id)) {
                list.add(PpvCategory(id = id, name = translatePpvCategory(rawName)))
            }
        }
        return list
    }

    internal fun parseStreamsResponse(
        json: String,
        onlyLiveNow: Boolean = false,
        categoryFilter: String? = null
    ): List<PpvStream> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()

        var nowSec = System.currentTimeMillis() / 1000L
        val streamItems = mutableListOf<Pair<JSONObject, String>>() // (streamObj, fallbackCategory)

        if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            val apiTs = root.optLong("timestamp", 0L)
            if (apiTs > 1_000_000_000L) {
                nowSec = apiTs
            }
            val outerArr = root.optJSONArray("streams") ?: JSONArray()
            for (i in 0 until outerArr.length()) {
                val item = outerArr.optJSONObject(i) ?: continue
                val nestedStreams = item.optJSONArray("streams")
                if (nestedStreams != null) {
                    val catId = item.optString("id")
                    val catName = item.optString("category")
                    if (categoryFilter != null &&
                        !catId.equals(categoryFilter, ignoreCase = true) &&
                        !catName.equals(categoryFilter, ignoreCase = true)
                    ) {
                        continue
                    }
                    for (j in 0 until nestedStreams.length()) {
                        val sObj = nestedStreams.optJSONObject(j) ?: continue
                        streamItems.add(sObj to catName)
                    }
                } else {
                    streamItems.add(item to "")
                }
            }
        } else if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val nestedStreams = item.optJSONArray("streams")
                if (nestedStreams != null) {
                    val catName = item.optString("category")
                    for (j in 0 until nestedStreams.length()) {
                        val sObj = nestedStreams.optJSONObject(j) ?: continue
                        streamItems.add(sObj to catName)
                    }
                } else {
                    streamItems.add(item to "")
                }
            }
        }

        val parsed = mutableListOf<PpvStream>()
        for ((obj, fallbackCat) in streamItems) {
            val id = obj.optString("id")
            val name = obj.optString("name", obj.optString("title", "")).trim()
            val category = obj.optString("category_name").ifBlank {
                obj.optString("category").ifBlank { fallbackCat }
            }
            if (categoryFilter != null &&
                !category.equals(categoryFilter, ignoreCase = true) &&
                obj.optString("category_id") != categoryFilter &&
                fallbackCat.isBlank()
            ) {
                continue
            }

            val startsAt = obj.optLong("starts_at", obj.optLong("start", 0L))
            val endsAt = obj.optLong("ends_at", 0L)
            val alwaysLiveVal = obj.optInt("always_live", 0) == 1 || obj.optBoolean("always_live", false)
            val viewers = obj.optString("viewers", "0").toIntOrNull() ?: obj.optInt("viewers", 0)
            val iframe = obj.optString("iframe", obj.optString("embedUrl", "")).trim()
            val sourceTag = obj.optString("source_tag", obj.optString("tag", "")).trim()
            val substreamsArr = obj.optJSONArray("substreams")

            val isScheduledLive = startsAt > 1_000_000_000L &&
                nowSec >= (startsAt - 900L) &&
                (endsAt <= 0L || nowSec <= endsAt + 1800L)
            val isLiveNow = alwaysLiveVal || isScheduledLive

            if (onlyLiveNow && !isLiveNow) continue

            val substreams = mutableListOf<SubstreamOption>()
            if (substreamsArr != null) {
                for (j in 0 until substreamsArr.length()) {
                    val sObj = substreamsArr.optJSONObject(j) ?: continue
                    val subId = sObj.optString("id", "sub-$j")
                    val subLabel = sObj.optString("source_tag").ifBlank {
                        sObj.optString("name", "Fonte ${j + 1}")
                    }
                    val subIframe = sObj.optString("iframe", sObj.optString("embedUrl", sObj.optString("url", ""))).trim()
                    if (subIframe.isNotBlank() && subIframe != iframe) {
                        substreams.add(SubstreamOption(id = subId, name = subLabel, embedUrl = subIframe))
                    }
                }
            }

            if (id.isNotBlank() && name.isNotBlank() && iframe.isNotBlank()) {
                val stream = PpvStream(
                    id = id,
                    name = name,
                    category = category,
                    categoryLabel = translatePpvCategory(category),
                    startsAt = startsAt,
                    endsAt = endsAt,
                    alwaysLive = alwaysLiveVal,
                    viewers = viewers,
                    embedUrl = iframe,
                    substreams = substreams,
                    sourceTag = sourceTag,
                    isLiveNow = isLiveNow,
                    isVod = false
                )
                registerFallbackEmbeds(stream)
                parsed.add(stream)
            }
        }

        return parsed.sortedWith(
            compareByDescending<PpvStream> { it.isLiveNow && !it.alwaysLive }
                .thenByDescending { it.alwaysLive }
                .thenByDescending { if (it.isLiveNow) it.viewers else 0 }
                .thenBy { if (!it.isLiveNow && it.startsAt > 0L) it.startsAt else Long.MAX_VALUE }
        )
    }

    internal fun parseVodsResponse(json: String, categoryFilter: String? = null): List<PpvStream> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()

        val vodGroups = if (trimmed.startsWith("{")) {
            JSONObject(trimmed).optJSONArray("vods") ?: JSONArray()
        } else if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONArray()
        }

        val list = mutableListOf<PpvStream>()
        for (i in 0 until vodGroups.length()) {
            val group = vodGroups.optJSONObject(i) ?: continue
            val nestedVods = group.optJSONArray("vods")
            if (nestedVods != null) {
                val catId = group.optString("id")
                val catName = group.optString("category")
                if (categoryFilter != null &&
                    !catId.equals(categoryFilter, ignoreCase = true) &&
                    !catName.equals(categoryFilter, ignoreCase = true)
                ) {
                    continue
                }
                for (j in 0 until nestedVods.length()) {
                    val vObj = nestedVods.optJSONObject(j) ?: continue
                    val vod = parseSingleVodItem(vObj, catName)
                    if (vod != null) list.add(vod)
                }
            } else {
                val vod = parseSingleVodItem(group, group.optString("category"))
                if (vod != null) list.add(vod)
            }
        }
        return list.sortedByDescending { it.startsAt }
    }

    private fun parseSingleVodItem(vObj: JSONObject, categoryName: String): PpvStream? {
        val id = vObj.optString("id")
        val name = vObj.optString("name", vObj.optString("title", "")).trim()
        if (id.isBlank() || name.isBlank()) return null

        val eventDate = vObj.optLong("event_date", vObj.optLong("added", 0L))
        val views = vObj.optString("views", "0").toIntOrNull() ?: vObj.optInt("views", 0)
        val iframeOrApi = vObj.optString("iframe").ifBlank { "$API_BASE/vods/$id" }

        return PpvStream(
            id = id,
            name = name,
            category = categoryName,
            categoryLabel = translatePpvCategory(categoryName),
            startsAt = eventDate,
            endsAt = 0L,
            alwaysLive = false,
            viewers = views,
            embedUrl = iframeOrApi,
            substreams = emptyList(),
            sourceTag = "VOD",
            isLiveNow = false,
            isVod = true
        )
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
            .header("Referer", "$PPV_BASE/")
            .header("Accept", "application/json")
            .get()
            .build()
        val call = YouTubeStreamExtractor.okHttpClient.newBuilder()
            .callTimeout(API_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
            .newCall(req)
        call.execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} da PPV API")
            }
            return resp.body?.string() ?: "{}"
        }
    }

    internal fun translatePpvCategory(name: String): String = when (name.trim().lowercase(Locale.ITALIAN)) {
        "mma" -> "🥊 MMA (UFC)"
        "boxing" -> "🥊 Boxe"
        "wrestling" -> "🤼 Wrestling (WWE, AEW)"
        "motorsports", "motorsport", "f1", "motogp" -> "🏎️ Motori (F1, MotoGP)"
        "tennis" -> "🎾 Tennis (ATP, WTA)"
        "american football", "american-football" -> "🏈 Football Americano (NFL)"
        "australian football" -> "🏉 Football Australiano"
        "football", "soccer", "calcio" -> "⚽ Calcio"
        "basketball" -> "🏀 Basket (NBA)"
        "ice hockey", "hockey" -> "🏒 Hockey su ghiaccio (NHL)"
        "cricket" -> "🏏 Cricket"
        "golf" -> "⛳ Golf"
        "rugby" -> "🏉 Rugby"
        "cycling" -> "🚴 Ciclismo"
        "baseball" -> "⚾ Baseball (MLB)"
        "24/7 streams" -> "📺 Canali 24/7"
        "arm wrestling" -> "💪 Braccio di ferro"
        "concerts" -> "🎸 Concerti"
        "documentaries" -> "🎬 Documentari"
        else -> name
    }
}
