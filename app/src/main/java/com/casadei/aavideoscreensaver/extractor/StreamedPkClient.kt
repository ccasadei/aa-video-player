package com.casadei.aavideoscreensaver.extractor

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Client ed Estrattore per https://streamed.pk/ (Live Sport & Eventi che cambiano nel tempo).
 *
 * - Interroga le API JSON pubbliche di Streamed.pk (/api/matches/live, /api/matches/all-today,
 *   /api/sports, /api/matches/{sport}, /api/stream/{source}/{id}).
 * - Risolve gli embed dinamici (es. https://embed.st/embed/...) in flussi .m3u8 compatibili con ExoPlayer
 *   usando HeadlessStreamSniffer in background senza toccare il telefono.
 */
object StreamedPkClient {

    private const val BASE_URL = "https://streamed.pk"
    private const val RESOLVE_TIMEOUT_MS = 30_000L
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // Mappa da singolo embedUrl a tutti gli embedUrl alternativi dello stesso evento (anche su source diverse)
    private val matchFallbackEmbeds = ConcurrentHashMap<String, List<String>>()

    // Calendario di oggi appena scaricato dal catalogo: evita di richiederlo di nuovo all'avvio della diretta
    // (l'API di streamed.pk a volte risponde in molti secondi o con errori TLS)
    @Volatile
    private var todayMatchesCache: Pair<Long, List<StreamedMatch>>? = null
    // Ultimo elenco delle dirette: un evento presente qui è già iniziato, inutile controllare il calendario
    @Volatile
    private var liveMatchesCache: Pair<Long, List<StreamedMatch>>? = null
    private const val TODAY_CACHE_MS = 5 * 60_000L
    private const val API_CALL_TIMEOUT_SEC = 6L

    data class StreamSourceRef(
        val source: String,
        val id: String
    )

    data class StreamedMatch(
        val id: String,
        val title: String,
        val category: String,
        val dateMs: Long,
        val popular: Boolean,
        val sources: List<StreamSourceRef>,
        val isConfirmedLive: Boolean = false
    ) {
        val categoryEmoji: String
            get() = sportEmoji(category)

        val formattedTime: String
            get() {
                if (dateMs <= 0L) return "🔴 24/7 LIVE"
                val now = System.currentTimeMillis()
                val diffMin = (now - dateMs) / 60_000L
                val timeStr = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(dateMs))
                return when {
                    isConfirmedLive || diffMin in -15..165 -> "🔴 LIVE ($timeStr)"
                    diffMin < -15 -> "⏳ Inizia alle $timeStr"
                    else -> "🕒 $timeStr"
                }
            }
    }

    data class SportCategory(
        val id: String,
        val name: String
    ) {
        val emoji: String
            get() = sportEmoji(id)
    }

    data class StreamOption(
        val id: String,
        val streamNo: Int,
        val language: String,
        val hd: Boolean,
        val embedUrl: String,
        val source: String,
        val viewers: Int
    ) {
        val label: String
            get() {
                val hdBadge = if (hd) "HD" else "SD"
                val lang = language.ifBlank { "Main" }
                val viewersBadge = if (viewers > 0) " • 👥 $viewers" else ""
                return "📡 Stream #$streamNo [$hdBadge • $lang • ${source.uppercase()}]$viewersBadge"
            }
    }

    data class ResolvedStreamedPk(
        val streamUrl: String,
        val title: String,
        val headers: Map<String, String>,
        val effectiveEmbedUrl: String? = null
    )

    fun isStreamedPkUrl(url: String): Boolean {
        val lower = url.lowercase().trim()
        return lower.startsWith("streamedpk://") ||
            lower.contains("streamed.pk") ||
            lower.contains("streamed.su") ||
            lower.contains("embed.st/embed/") ||
            lower.contains("embedme.top/embed/")
    }

    suspend fun fetchLiveMatches(): Result<List<StreamedMatch>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = getJson("$BASE_URL/api/matches/live")
            parseMatchesArray(JSONArray(json), markAllConfirmedLive = true)
                .also { liveMatchesCache = System.currentTimeMillis() to it }
        }
    }

    suspend fun fetchPopularLiveMatches(): Result<List<StreamedMatch>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = getJson("$BASE_URL/api/matches/live/popular")
            parseMatchesArray(JSONArray(json), markAllConfirmedLive = true)
        }
    }

    suspend fun fetchAllTodayMatches(): Result<List<StreamedMatch>> = withContext(Dispatchers.IO) {
        runCatching {
            val liveIds = runCatching {
                parseMatchesArray(JSONArray(getJson("$BASE_URL/api/matches/live")), markAllConfirmedLive = true)
                    .map { it.id }
                    .toSet()
            }.getOrDefault(emptySet())
            val today = parseMatchesArray(JSONArray(getJson("$BASE_URL/api/matches/all-today")))
            todayMatchesCache = System.currentTimeMillis() to today
            filterAndSortMatches(today, liveIds)
        }
    }

    suspend fun fetchMatchesBySport(sportId: String): Result<List<StreamedMatch>> = withContext(Dispatchers.IO) {
        runCatching {
            val liveIds = runCatching {
                parseMatchesArray(JSONArray(getJson("$BASE_URL/api/matches/live")), markAllConfirmedLive = true)
                    .map { it.id }
                    .toSet()
            }.getOrDefault(emptySet())
            val json = getJson("$BASE_URL/api/matches/$sportId")
            filterAndSortMatches(parseMatchesArray(JSONArray(json)), liveIds)
        }
    }

    private fun filterAndSortMatches(raw: List<StreamedMatch>, liveIds: Set<String>): List<StreamedMatch> {
        val now = System.currentTimeMillis()
        return raw.map { m ->
            m.copy(isConfirmedLive = m.isConfirmedLive || m.id in liveIds)
        }.filter { m ->
            if (m.isConfirmedLive || m.dateMs <= 0L) return@filter true
            val diffMin = (now - m.dateMs) / 60_000L
            // Esclude gli eventi già terminati da oltre 2h45m non più presenti in /api/matches/live
            diffMin <= 165L
        }.sortedWith(
            compareByDescending<StreamedMatch> { it.isConfirmedLive }
                .thenByDescending {
                    val diffMin = (now - it.dateMs) / 60_000L
                    it.dateMs > 0L && diffMin in -15..165
                }
                .thenByDescending { it.dateMs <= 0L }
                .thenBy { if (it.dateMs > 0L) it.dateMs else Long.MAX_VALUE }
        )
    }

    suspend fun fetchSportsCategories(): Result<List<SportCategory>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = getJson("$BASE_URL/api/sports")
            val arr = JSONArray(json)
            val list = mutableListOf<SportCategory>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val name = obj.optString("name")
                if (id.isNotBlank() && name.isNotBlank()) {
                    list.add(SportCategory(id = id, name = translateSportName(id, name)))
                }
            }
            list
        }
    }

    suspend fun searchMatches(query: String): Result<List<StreamedMatch>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanQ = query.trim().lowercase()
            val live = runCatching {
                parseMatchesArray(JSONArray(getJson("$BASE_URL/api/matches/live")), markAllConfirmedLive = true)
            }.getOrDefault(emptyList())
            val liveIds = live.map { it.id }.toSet()
            val today = runCatching { parseMatchesArray(JSONArray(getJson("$BASE_URL/api/matches/all-today"))) }
                .onSuccess { todayMatchesCache = System.currentTimeMillis() to it }
                .getOrDefault(emptyList())

            val merged = LinkedHashMap<String, StreamedMatch>()
            for (m in live + today) {
                if (m.sources.isNotEmpty() && m.id !in merged) {
                    merged[m.id] = m.copy(isConfirmedLive = m.isConfirmedLive || m.id in liveIds)
                }
            }

            val filtered = if (cleanQ.isBlank()) {
                merged.values.toList()
            } else {
                merged.values.filter { m ->
                    m.title.lowercase().contains(cleanQ) ||
                        m.category.lowercase().contains(cleanQ) ||
                        translateSportName(m.category, m.category).lowercase().contains(cleanQ)
                }
            }
            filterAndSortMatches(filtered, liveIds)
        }
    }

    suspend fun fetchStreamsForMatch(match: StreamedMatch): Result<List<StreamOption>> = withContext(Dispatchers.IO) {
        runCatching {
            val allStreams = mutableListOf<StreamOption>()
            for (src in match.sources) {
                val streams = runCatching { fetchStreamsForSource(src.source, src.id) }.getOrDefault(emptyList())
                allStreams.addAll(streams)
            }
            val sorted = allStreams.sortedWith(
                compareByDescending<StreamOption> { it.hd }
                    .thenByDescending { it.viewers }
            )
            val allEmbeds = sorted.map { it.embedUrl }.filter { it.isNotBlank() }.distinct()
            if (allEmbeds.size > 1) {
                for (emb in allEmbeds) {
                    matchFallbackEmbeds[emb] = allEmbeds
                }
            }
            sorted
        }
    }

    suspend fun fetchStreamsForSource(source: String, id: String): List<StreamOption> = withContext(Dispatchers.IO) {
        val json = getJson("$BASE_URL/api/stream/$source/$id")
        val arr = JSONArray(json)
        val result = mutableListOf<StreamOption>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val embedUrl = obj.optString("embedUrl")
            if (embedUrl.isBlank()) continue
            result.add(
                StreamOption(
                    id = obj.optString("id", id),
                    streamNo = obj.optInt("streamNo", i + 1),
                    language = obj.optString("language", "Main"),
                    hd = obj.optBoolean("hd", true),
                    embedUrl = embedUrl,
                    source = obj.optString("source", source),
                    viewers = obj.optInt("viewers", 0)
                )
            )
        }
        result
    }

    /**
     * Risolve un URL di Streamed.pk (`embed.st/embed/...`, `streamedpk://match/{source}/{id}`, o `streamed.pk/watch/...`)
     * in un flusso HLS `.m3u8` + headers HTTP per ExoPlayer.
     *
     * - Prova al massimo 3 sorgenti dello stesso evento, con un tempo complessivo di [RESOLVE_TIMEOUT_MS].
     * - Se nessuna risponde, restituisce un errore chiaro (non ancora iniziato / terminato): non passa mai
     *   a un evento diverso da quello scelto.
     * - La cancellazione (cambio video) viene sempre propagata, non trattata come errore della sorgente.
     */
    suspend fun resolveStream(
        context: Context,
        urlOrSpec: String,
        fallbackTitle: String = "⚽ Streamed.pk Live"
    ): Result<ResolvedStreamedPk> = withContext(Dispatchers.IO) {
        try {
            Result.success(withTimeout(RESOLVE_TIMEOUT_MS) { resolveInternal(context, urlOrSpec.trim(), fallbackTitle) })
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Nessuna sorgente di Streamed.pk ha risposto entro ${RESOLVE_TIMEOUT_MS / 1000} secondi"))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun resolveInternal(context: Context, clean: String, fallbackTitle: String): ResolvedStreamedPk {
        // 1. Se è già un .m3u8 diretto
        if (clean.contains(".m3u8", ignoreCase = true)) {
            return ResolvedStreamedPk(
                streamUrl = clean,
                title = fallbackTitle,
                headers = mapOf(
                    "Referer" to "https://embed.st/",
                    "Origin" to "https://embed.st",
                    "User-Agent" to USER_AGENT
                )
            )
        }

        // 2. Determina l'embedUrl (o lista di embedUrl di fallback dello stesso evento) da sniffare
        val candidateEmbedUrls = mutableListOf<String>()
        var requestedSourceId: String? = null

        if (clean.startsWith("streamedpk://match/", ignoreCase = true)) {
            val segs = Uri.parse(clean).pathSegments
            if (segs.size >= 2) {
                requestedSourceId = segs[1]
                candidateEmbedUrls.addAll(fetchStreamsForSource(segs[0], segs[1]).map { it.embedUrl })
            }
        } else if (clean.contains("embed.st/embed/", ignoreCase = true) ||
            clean.contains("embedme.top/embed/", ignoreCase = true)
        ) {
            candidateEmbedUrls.add(clean)
            matchFallbackEmbeds[clean]?.forEach { alt ->
                if (alt !in candidateEmbedUrls) candidateEmbedUrls.add(alt)
            }
            // Se il singolo streamNo è offline, carica anche gli altri stream dello stesso evento
            val segs = Uri.parse(clean).pathSegments // ["embed", "{source}", "{id}", "{streamNo}"]
            if (segs.size >= 3 && segs[0].equals("embed", ignoreCase = true)) {
                requestedSourceId = segs[2]
                // Se la schermata delle sorgenti le ha appena scaricate, le alternative sono già note
                val altStreams = if (matchFallbackEmbeds[clean] != null) emptyList() else try {
                    fetchStreamsForSource(segs[1], segs[2])
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("AAVideo", "Streamed.pk: sorgenti alternative non disponibili (${t.message})")
                    emptyList()
                }
                for (alt in altStreams) {
                    if (alt.embedUrl.isNotBlank() && alt.embedUrl !in candidateEmbedUrls) {
                        candidateEmbedUrls.add(alt.embedUrl)
                    }
                }
            }
        } else if (clean.contains("streamed.pk/api/stream/", ignoreCase = true)) {
            val arr = JSONArray(getJson(clean))
            for (i in 0 until arr.length()) {
                val emb = arr.optJSONObject(i)?.optString("embedUrl").orEmpty()
                if (emb.isNotBlank()) candidateEmbedUrls.add(emb)
            }
        } else {
            candidateEmbedUrls.add(clean)
        }

        if (candidateEmbedUrls.isEmpty()) {
            throw IllegalStateException("Nessuna sorgente attiva trovata su Streamed.pk per questo evento")
        }

        // Evento non ancora iniziato: inutile attendere gli sniff (andrebbero tutti in timeout)
        val now = System.currentTimeMillis()
        val knownLive = liveMatchesCache?.takeIf { now - it.first < TODAY_CACHE_MS }?.second.orEmpty()
            .any { m -> m.sources.any { it.id.equals(requestedSourceId, ignoreCase = true) } }
        val cachedToday = todayMatchesCache?.takeIf { now - it.first < TODAY_CACHE_MS }?.second
        val todayMatches = if (knownLive) emptyList() else cachedToday ?: try {
            parseMatchesArray(JSONArray(getJson("$BASE_URL/api/matches/all-today")))
                .also { todayMatchesCache = System.currentTimeMillis() to it }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("AAVideo", "Streamed.pk: calendario non disponibile (${t.message})")
            emptyList()
        }
        if (scheduledLater(requestedSourceId, todayMatches, System.currentTimeMillis()) != null) {
            throw IllegalStateException(
                unavailableReason(requestedSourceId, todayMatches, System.currentTimeMillis(), fallbackTitle)
            )
        }

        var lastError: Throwable? = null
        for (embedUrl in candidateEmbedUrls.take(3)) {
            val sniffResult = HeadlessStreamSniffer.sniffStream(
                context = context,
                targetUrl = embedUrl,
                referer = "$BASE_URL/",
                timeoutMs = 11_000L
            )
            val sniffed = sniffResult.getOrNull()
            if (sniffed == null) {
                lastError = sniffResult.exceptionOrNull()
                continue
            }
            if (isM3u8Reachable(sniffed.streamUrl, sniffed.headers)) {
                return ResolvedStreamedPk(
                    streamUrl = sniffed.streamUrl,
                    title = fallbackTitle,
                    headers = sniffed.headers,
                    effectiveEmbedUrl = embedUrl
                )
            }
            Log.w("AAVideo", "Streamed.pk m3u8 offline (${sniffed.streamUrl}), provo sorgente successiva...")
        }

        // 3. Nessuna sorgente dell'evento risponde: spiega perché, senza cambiare evento
        val reason = unavailableReason(requestedSourceId, todayMatches, System.currentTimeMillis(), fallbackTitle)
        Log.w("AAVideo", "Streamed.pk non disponibile: $reason (ultimo errore: ${lastError?.message})")
        throw IllegalStateException(reason, lastError)
    }

    /** Evento richiesto che inizierà tra più di 10 minuti (inutile cercare il flusso), altrimenti null. */
    internal fun scheduledLater(
        requestedSourceId: String?,
        todayMatches: List<StreamedMatch>,
        nowMs: Long
    ): StreamedMatch? {
        val srcId = requestedSourceId?.takeIf { it.isNotBlank() } ?: return null
        val match = todayMatches.firstOrNull { m -> m.sources.any { it.id.equals(srcId, ignoreCase = true) } }
        return match?.takeIf { it.dateMs > nowMs + 10 * 60_000L }
    }

    /**
     * Messaggio da mostrare quando nessuna sorgente dell'evento richiesto risponde. Non si passa mai a un
     * evento diverso: l'utente ha scelto quell'evento e la playlist non deve cambiare contenuto da sola.
     */
    internal fun unavailableReason(
        requestedSourceId: String?,
        todayMatches: List<StreamedMatch>,
        nowMs: Long,
        fallbackTitle: String
    ): String {
        val title = fallbackTitle.removePrefix("⚽").trim().ifBlank { "L'evento" }
        val scheduled = scheduledLater(requestedSourceId, todayMatches, nowMs)
        if (scheduled != null) {
            val timeStr = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(scheduled.dateMs))
            return "⏳ '${scheduled.title}' inizierà alle $timeStr (non ancora in diretta)"
        }
        return "'$title' è terminato o al momento non disponibile su Streamed.pk. Scegli un altro evento da Sfoglia."
    }

    private fun isM3u8Reachable(m3u8Url: String, headers: Map<String, String>): Boolean {
        return runCatching {
            val reqBuilder = Request.Builder().url(m3u8Url).get()
            for ((k, v) in headers) {
                reqBuilder.header(k, v)
            }
            YouTubeStreamExtractor.okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val bodyPreview = resp.peekBody(512).string()
                Log.i(
                    "AAVideo",
                    "isM3u8Reachable code=${resp.code} type=${resp.header("Content-Type")} url=$m3u8Url body=${bodyPreview.take(220).replace("\n", "\\n")}"
                )
                resp.isSuccessful && bodyPreview.trimStart().startsWith("#EXTM3U")
            }
        }.getOrDefault(false)
    }

    private fun parseMatchesArray(arr: JSONArray, markAllConfirmedLive: Boolean = false): List<StreamedMatch> {
        val list = mutableListOf<StreamedMatch>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val title = obj.optString("title")
            val category = obj.optString("category", "other")
            val dateMs = obj.optLong("date", 0L)
            val popular = obj.optBoolean("popular", false)

            val sourcesArr = obj.optJSONArray("sources")
            val sources = mutableListOf<StreamSourceRef>()
            if (sourcesArr != null) {
                for (j in 0 until sourcesArr.length()) {
                    val sObj = sourcesArr.optJSONObject(j) ?: continue
                    val srcName = sObj.optString("source")
                    val srcId = sObj.optString("id")
                    if (srcName.isNotBlank() && srcId.isNotBlank()) {
                        sources.add(StreamSourceRef(source = srcName, id = srcId))
                    }
                }
            }

            if (id.isNotBlank() && title.isNotBlank() && sources.isNotEmpty()) {
                list.add(
                    StreamedMatch(
                        id = id,
                        title = title,
                        category = category,
                        dateMs = dateMs,
                        popular = popular,
                        sources = sources,
                        isConfirmedLive = markAllConfirmedLive
                    )
                )
            }
        }
        return list
    }

    private fun getJson(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$BASE_URL/")
            .header("Accept", "application/json")
            .get()
            .build()
        // Tetto complessivo per chiamata: un'API lenta non deve consumare il tempo massimo di avvio della diretta
        val call = YouTubeStreamExtractor.okHttpClient.newCall(req)
        call.timeout().timeout(API_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        call.execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} da Streamed.pk")
            }
            return resp.body?.string() ?: "[]"
        }
    }

    private fun sportEmoji(category: String): String = when (category.lowercase()) {
        "football" -> "⚽"
        "motor-sports", "motorsport" -> "🏎️"
        "basketball" -> "🏀"
        "tennis" -> "🎾"
        "fight" -> "🥊"
        "hockey" -> "🏒"
        "baseball" -> "⚾"
        "american-football" -> "🏈"
        "rugby" -> "🏉"
        "golf" -> "⛳"
        "cricket" -> "🏏"
        "darts" -> "🎯"
        "billiards" -> "🎱"
        else -> "📡"
    }

    private fun translateSportName(id: String, defaultName: String): String = when (id.lowercase()) {
        "football" -> "Calcio (Football)"
        "motor-sports" -> "Motori (F1, MotoGP, Rally)"
        "basketball" -> "Basket (NBA, EuroLeague)"
        "tennis" -> "Tennis (ATP, WTA)"
        "fight" -> "Combattimento (UFC, Boxe)"
        "hockey" -> "Hockey"
        "american-football" -> "Football Americano (NFL)"
        "baseball" -> "Baseball (MLB)"
        "rugby" -> "Rugby"
        "golf" -> "Golf"
        else -> defaultName
    }
}
