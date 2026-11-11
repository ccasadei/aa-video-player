package com.casadei.aavideoscreensaver.extractor

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Client per sfogliare e cercare Film e Serie TV del catalogo StreamingCommunity ITA
 * direttamente dallo schermo di Android Auto senza dover usare il telefono.
 *
 * Fonte primaria: le URL attive di StreamingCommunity / StreamingUnity lette dinamicamente da
 * `https://www.streaming-community.day/` (es. `https://streamingcommunityz.nexus` e `https://streamingunity.dog`,
 * basate su Inertia `data-page` e player VixCloud), provate in sequenza con fallback automatico a WebView
 * se Cloudflare blocca OkHttp.
 */
object StreamingCommunityCatalogClient {

    private const val TAG = "AAVideo"
    private const val VIDXGO_BASE = "https://v.vidxgo.co"
    private const val CINEMETA_BASE = "https://v3-cinemeta.strem.io"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    data class CatalogEntry(
        val imdbId: String,
        val title: String,
        val isSeries: Boolean,
        val year: String,
        val tmdbId: Int? = null,
        val scTitleId: Int? = null,
        val scSlug: String? = null,
        val scPagePath: String? = null
    ) {
        val badge: String
            get() = if (isSeries) "📺 SERIE TV" else "🎬 FILM"
    }

    data class EpisodeEntry(
        val season: Int,
        val episode: Int,
        val title: String,
        val releasedYear: String = "",
        val scEpisodeId: Int? = null
    ) {
        val code: String
            get() = "S%02dE%02d".format(season, episode)
    }

    data class SeriesDetails(
        val imdbId: String,
        val tmdbId: Int,
        val name: String,
        val seasons: Map<Int, List<EpisodeEntry>>,
        val scTitleId: Int? = null
    )

    /**
     * Restituisce i film popolari provando in ordine le URL lette da `https://www.streaming-community.day/`.
     */
    suspend fun fetchPopularMovies(): Result<List<CatalogEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val candidates = StreamingCommunityUrlResolver.getCandidateBaseUrls()
            for (baseUrl in candidates) {
                val scMovies = runCatching {
                    val html = CloudflareWebViewBridge.fetchHtmlWithFallback("$baseUrl/it/movies", referer = "$baseUrl/")
                    parseInertiaCatalogHtml(html, defaultIsSeries = false)
                }.onFailure { err ->
                    Log.w(TAG, "Catalogo film fallito su $baseUrl: ${err.message}")
                }.getOrDefault(emptyList())

                if (scMovies.isNotEmpty()) {
                    StreamingCommunityUrlResolver.markUrlWorking(baseUrl)
                    return@runCatching scMovies
                }
            }

            val vidxgoFrontend = StreamingCommunityUrlResolver.getCurrentVidxgoRefererBaseUrl()
            val toysMovies = runCatching {
                scrapeStreamingCommunityToys("$vidxgoFrontend/film/", vidxgoFrontend, defaultIsSeries = false)
            }.getOrDefault(emptyList())

            if (toysMovies.isNotEmpty()) {
                return@runCatching toysMovies
            }

            val json = getText("$CINEMETA_BASE/catalog/movie/top.json")
            parseCinemetaMetas(JSONObject(json).optJSONArray("metas"), isSeries = false)
        }
    }

    /**
     * Restituisce le serie TV popolari provando in ordine le URL lette da `https://www.streaming-community.day/`.
     */
    suspend fun fetchPopularSeries(): Result<List<CatalogEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val candidates = StreamingCommunityUrlResolver.getCandidateBaseUrls()
            for (baseUrl in candidates) {
                val scSeries = runCatching {
                    val html = CloudflareWebViewBridge.fetchHtmlWithFallback("$baseUrl/it/tv-shows", referer = "$baseUrl/")
                    parseInertiaCatalogHtml(html, defaultIsSeries = true)
                }.onFailure { err ->
                    Log.w(TAG, "Catalogo serie TV fallito su $baseUrl: ${err.message}")
                }.getOrDefault(emptyList())

                if (scSeries.isNotEmpty()) {
                    StreamingCommunityUrlResolver.markUrlWorking(baseUrl)
                    return@runCatching scSeries
                }
            }

            val vidxgoFrontend = StreamingCommunityUrlResolver.getCurrentVidxgoRefererBaseUrl()
            val toysSeries = runCatching {
                scrapeStreamingCommunityToys("$vidxgoFrontend/series/", vidxgoFrontend, defaultIsSeries = true)
            }.getOrDefault(emptyList())

            if (toysSeries.isNotEmpty()) {
                return@runCatching toysSeries
            }

            val json = getText("$CINEMETA_BASE/catalog/series/top.json")
            parseCinemetaMetas(JSONObject(json).optJSONArray("metas"), isSeries = true)
        }
    }

    /**
     * Cerca nel catalogo provando in ordine le URL lette da `https://www.streaming-community.day/`.
     */
    suspend fun searchCatalog(query: String): Result<List<CatalogEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = query.trim()
            if (clean.isBlank()) return@runCatching fetchPopularMovies().getOrDefault(emptyList())
            val encoded = URLEncoder.encode(clean, "UTF-8")

            val candidates = StreamingCommunityUrlResolver.getCandidateBaseUrls()
            for (baseUrl in candidates) {
                val scResults = runCatching {
                    val html = CloudflareWebViewBridge.fetchHtmlWithFallback("$baseUrl/it/search?q=$encoded", referer = "$baseUrl/")
                    parseInertiaCatalogHtml(html, defaultIsSeries = null)
                }.onFailure { err ->
                    Log.w(TAG, "Ricerca '$clean' fallita su $baseUrl: ${err.message}")
                }.getOrDefault(emptyList())

                if (scResults.isNotEmpty()) {
                    StreamingCommunityUrlResolver.markUrlWorking(baseUrl)
                    return@runCatching scResults
                }
            }

            val vidxgoFrontend = StreamingCommunityUrlResolver.getCurrentVidxgoRefererBaseUrl()
            val toysResults = runCatching {
                scrapeStreamingCommunityToys("$vidxgoFrontend/archivio/?search=$encoded", vidxgoFrontend, defaultIsSeries = null)
            }.getOrDefault(emptyList())

            if (toysResults.isNotEmpty()) {
                return@runCatching toysResults
            }

            val combined = mutableListOf<CatalogEntry>()
            val seen = mutableSetOf<String>()

            val movies = runCatching {
                val mJson = getText("$CINEMETA_BASE/catalog/movie/top/search=$encoded.json")
                parseCinemetaMetas(JSONObject(mJson).optJSONArray("metas"), isSeries = false)
            }.getOrDefault(emptyList())

            val series = runCatching {
                val sJson = getText("$CINEMETA_BASE/catalog/series/top/search=$encoded.json")
                parseCinemetaMetas(JSONObject(sJson).optJSONArray("metas"), isSeries = true)
            }.getOrDefault(emptyList())

            val maxLen = maxOf(movies.size, series.size)
            for (i in 0 until maxLen) {
                if (i < movies.size && seen.add(movies[i].title.lowercase())) combined.add(movies[i])
                if (i < series.size && seen.add(series[i].title.lowercase())) combined.add(series[i])
            }
            combined
        }
    }

    /**
     * Costruisce l'URL di riproduzione per un Film selezionato dal catalogo:
     * - Se proviene dal catalogo ufficiale StreamingCommunity (`scTitleId`), restituisce `$baseUrl/it/watch/$scTitleId`.
     * - Altrimenti restituisce l'embed `https://v.vidxgo.co/$numericImdb?se=0&info=0`.
     */
    suspend fun resolveMovieVixUrl(entry: CatalogEntry): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val scId = entry.scTitleId ?: entry.imdbId.removePrefix("sc:").toIntOrNull()
            if (scId != null && scId > 0) {
                val baseUrl = StreamingCommunityUrlResolver.getCurrentBaseUrl()
                return@runCatching "$baseUrl/it/watch/$scId"
            }
            val numericImdb = entry.imdbId.removePrefix("tt").trim()
            if (numericImdb.isNotBlank() && numericImdb.all { it.isDigit() }) {
                return@runCatching "$VIDXGO_BASE/$numericImdb?se=0&info=0"
            }
            if (!entry.scPagePath.isNullOrBlank()) {
                val baseUrl = StreamingCommunityUrlResolver.getCurrentBaseUrl()
                return@runCatching "$baseUrl${entry.scPagePath}"
            }
            throw IllegalStateException("ID titolo non valido per ${entry.title}")
        }
    }

    /**
     * Scarica stagioni ed episodi di una Serie TV per permetterne la scelta direttamente su Android Auto.
     */
    suspend fun fetchSeriesDetails(entry: CatalogEntry): Result<SeriesDetails> = withContext(Dispatchers.IO) {
        runCatching {
            val scId = entry.scTitleId ?: entry.imdbId.removePrefix("sc:").toIntOrNull()
            if (scId != null && scId > 0) {
                val candidates = StreamingCommunityUrlResolver.getCandidateBaseUrls()
                for (baseUrl in candidates) {
                    val scDetails = runCatching {
                        fetchInertiaSeriesDetails(entry, scId, baseUrl)
                    }.onFailure { err ->
                        Log.w(TAG, "Dettagli serie '$scId' falliti su $baseUrl: ${err.message}")
                    }.getOrNull()
                    if (scDetails != null && scDetails.seasons.isNotEmpty()) {
                        StreamingCommunityUrlResolver.markUrlWorking(baseUrl)
                        return@runCatching scDetails
                    }
                }
            }

            val numericImdbStr = entry.imdbId.removePrefix("tt").trim()
            val numericImdbInt = numericImdbStr.toIntOrNull() ?: (entry.tmdbId ?: 0)
            if (numericImdbInt <= 0) {
                throw IllegalStateException("ID serie non disponibile per ${entry.title}")
            }

            // 1. Verifica quali stagioni esistono sul player v.vidxgo.co (evita stagioni future TBA)
            val vidxgoReferer = StreamingCommunityUrlResolver.getCurrentVidxgoRefererBaseUrl()
            val vidxgoSeasonCounts = runCatching {
                StreamingCommunityExtractor.extractSeasonCountsFromVidxgo(numericImdbStr, vidxgoReferer)
            }.getOrDefault(emptyMap())
            val availableVidxgoSeasons = vidxgoSeasonCounts.filterValues { it > 0 }.keys

            val seasonsMap = sortedMapOf<Int, MutableList<EpisodeEntry>>()

            // 2. Carica struttura stagioni/episodi da Cinemeta filtrando episodi "TBA"
            runCatching {
                val metaJson = getText("$CINEMETA_BASE/meta/series/${entry.imdbId}.json")
                val metaObj = JSONObject(metaJson).optJSONObject("meta")
                val videosArr = metaObj?.optJSONArray("videos") ?: JSONArray()

                for (i in 0 until videosArr.length()) {
                    val v = videosArr.optJSONObject(i) ?: continue
                    val season = v.optInt("season", 0)
                    val ep = v.optInt("episode", v.optInt("number", 0))
                    if (season <= 0 || ep <= 0) continue
                    if (availableVidxgoSeasons.isNotEmpty() && season !in availableVidxgoSeasons) continue

                    val epTitle = v.optString("name", v.optString("title", "Episodio $ep")).trim()
                    if (epTitle.equals("TBA", ignoreCase = true) || epTitle.equals("TBD", ignoreCase = true)) {
                        continue
                    }
                    val rel = v.optString("released", "").take(4)
                    val list = seasonsMap.getOrPut(season) { mutableListOf() }
                    list.add(
                        EpisodeEntry(
                            season = season,
                            episode = ep,
                            title = epTitle.ifBlank { "Episodio $ep" },
                            releasedYear = rel
                        )
                    )
                }
            }

            // 3. Arricchisce con i titoli italiani ufficiali da v.vidxgo.co/seasons.php
            val targetSeasons = when {
                availableVidxgoSeasons.isNotEmpty() -> availableVidxgoSeasons.sorted()
                seasonsMap.isNotEmpty() -> seasonsMap.keys.take(4)
                else -> listOf(1, 2, 3)
            }
            for (sNum in targetSeasons) {
                runCatching {
                    val sJson = getText(
                        url = "$VIDXGO_BASE/seasons.php?imdb=$numericImdbStr&season=$sNum",
                        referer = "$VIDXGO_BASE/$numericImdbStr/1/1?se=0&info=0"
                    )
                    val root = JSONObject(sJson)
                    if (root.optInt("ok", 0) == 1) {
                        val epsArr = root.optJSONArray("episodes")
                        if (epsArr != null && epsArr.length() > 0) {
                            val itaList = mutableListOf<EpisodeEntry>()
                            for (i in 0 until epsArr.length()) {
                                val epObj = epsArr.optJSONObject(i) ?: continue
                                val num = epObj.optInt("number", i + 1)
                                val name = epObj.optString("name", "Episodio $num")
                                itaList.add(
                                    EpisodeEntry(
                                        season = sNum,
                                        episode = num,
                                        title = name
                                    )
                                )
                            }
                            if (itaList.isNotEmpty()) {
                                seasonsMap[sNum] = itaList
                            }
                        }
                    }
                }
            }

            for ((_, epList) in seasonsMap) {
                epList.sortBy { it.episode }
            }
            val availableSeasons = filterAvailableEpisodes(seasonsMap, vidxgoSeasonCounts)

            if (availableSeasons.isEmpty()) {
                throw IllegalStateException("Stagioni ed episodi di ${entry.title} non disponibili su StreamingCommunity")
            }

            SeriesDetails(
                imdbId = entry.imdbId,
                tmdbId = numericImdbInt,
                name = entry.title,
                seasons = availableSeasons
            )
        }
    }

    /**
     * Scarica stagioni ed episodi direttamente dalla pagina Inertia `/it/titles/{id}-{slug}` di StreamingCommunity.
     */
    private suspend fun fetchInertiaSeriesDetails(
        entry: CatalogEntry,
        scId: Int,
        baseUrl: String
    ): SeriesDetails = coroutineScope {
        val slugPart = if (!entry.scSlug.isNullOrBlank()) "$scId-${entry.scSlug}" else scId.toString()
        val titlePath = entry.scPagePath?.takeIf { it.startsWith("/") } ?: "/it/titles/$slugPart"
        val mainHtml = CloudflareWebViewBridge.fetchHtmlWithFallback("$baseUrl$titlePath", referer = "$baseUrl/")
        val props = extractInertiaProps(mainHtml)
            ?: throw IllegalStateException("Pagina serie non valida per ${entry.title}")

        val titleObj = props.optJSONObject("title")
        val realSlug = titleObj?.optString("slug")?.takeIf { it.isNotBlank() } ?: entry.scSlug
        val effectiveBasePath = if (!realSlug.isNullOrBlank()) "/it/titles/$scId-$realSlug" else titlePath

        val seasonsArr = titleObj?.optJSONArray("seasons") ?: JSONArray()
        val seasonNumbers = mutableListOf<Int>()
        for (i in 0 until seasonsArr.length()) {
            val sObj = seasonsArr.optJSONObject(i) ?: continue
            val sNum = sObj.optInt("number", 0)
            val epCount = sObj.optInt("episodes_count", 1)
            if (sNum > 0 && epCount > 0) {
                seasonNumbers.add(sNum)
            }
        }

        val seasonsMap = sortedMapOf<Int, List<EpisodeEntry>>()
        val loadedSeason = props.optJSONObject("loadedSeason")
        val loadedSeasonNum = loadedSeason?.optInt("number", 0) ?: 0
        if (loadedSeasonNum > 0) {
            val eps = parseInertiaEpisodes(loadedSeasonNum, loadedSeason?.optJSONArray("episodes"))
            if (eps.isNotEmpty()) {
                seasonsMap[loadedSeasonNum] = eps
            }
        }

        val remainingSeasons = seasonNumbers.filter { it != loadedSeasonNum }
        val fetchedOtherSeasons = remainingSeasons.map { sNum ->
            async(Dispatchers.IO) {
                runCatching {
                    val sHtml = CloudflareWebViewBridge.fetchHtmlWithFallback(
                        "$baseUrl$effectiveBasePath/season-$sNum",
                        referer = "$baseUrl$effectiveBasePath"
                    )
                    val sProps = extractInertiaProps(sHtml)
                    val sLoaded = sProps?.optJSONObject("loadedSeason")
                    val eps = parseInertiaEpisodes(sNum, sLoaded?.optJSONArray("episodes"))
                    sNum to eps
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()

        for ((sNum, eps) in fetchedOtherSeasons) {
            if (eps.isNotEmpty()) {
                seasonsMap[sNum] = eps
            }
        }

        if (seasonsMap.isEmpty()) {
            throw IllegalStateException("Stagioni ed episodi di ${entry.title} non disponibili su StreamingCommunity")
        }

        SeriesDetails(
            imdbId = entry.imdbId,
            tmdbId = scId,
            name = entry.title,
            seasons = seasonsMap,
            scTitleId = scId
        )
    }

    private fun parseInertiaEpisodes(seasonNumber: Int, episodesArr: JSONArray?): List<EpisodeEntry> {
        if (episodesArr == null) return emptyList()
        val list = mutableListOf<EpisodeEntry>()
        for (i in 0 until episodesArr.length()) {
            val epObj = episodesArr.optJSONObject(i) ?: continue
            val epId = epObj.optInt("id", 0).takeIf { it > 0 }
            val epNum = epObj.optInt("number", i + 1)
            if (epNum <= 0) continue
            val rawName = epObj.optString("name", "").trim()
            val cleanName = if (rawName.isNotBlank() && rawName != "null") rawName else "Episodio $epNum"
            list.add(
                EpisodeEntry(
                    season = seasonNumber,
                    episode = epNum,
                    title = cleanName,
                    scEpisodeId = epId
                )
            )
        }
        return list.sortedBy { it.episode }
    }

    /**
     * Estrae i titoli dal JSON Inertia `data-page` di StreamingCommunity (`sliders` o `titles`).
     */
    internal fun parseInertiaCatalogHtml(html: String, defaultIsSeries: Boolean?): List<CatalogEntry> {
        val props = extractInertiaProps(html) ?: return emptyList()
        val results = mutableListOf<CatalogEntry>()
        val seenIds = HashSet<Int>()

        fun addTitleObj(obj: JSONObject?) {
            if (obj == null) return
            val id = obj.optInt("id", 0)
            if (id <= 0 || !seenIds.add(id)) return
            val name = obj.optString("name", "").trim()
            if (name.isBlank()) return
            val slug = obj.optString("slug", "").trim()
            val type = obj.optString("type", "").lowercase()
            val isSeries = when {
                type == "tv" || type == "series" -> true
                type == "movie" || type == "film" -> false
                defaultIsSeries != null -> defaultIsSeries
                else -> obj.optInt("seasons_count", 0) > 0
            }
            val dateStr = obj.optString("last_air_date", "")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: obj.optString("release_date", "").takeIf { it != "null" }.orEmpty()
            val year = dateStr.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty()
            val pagePath = if (slug.isNotBlank()) "/it/titles/$id-$slug" else "/it/titles/$id"

            results.add(
                CatalogEntry(
                    imdbId = "sc:$id",
                    title = name,
                    isSeries = isSeries,
                    year = year,
                    tmdbId = id,
                    scTitleId = id,
                    scSlug = slug.ifBlank { null },
                    scPagePath = pagePath
                )
            )
        }

        val sliders = props.optJSONArray("sliders")
        if (sliders != null) {
            for (s in 0 until sliders.length()) {
                val slider = sliders.optJSONObject(s) ?: continue
                val titles = slider.optJSONArray("titles") ?: continue
                for (t in 0 until titles.length()) {
                    addTitleObj(titles.optJSONObject(t))
                }
            }
        }

        val titlesNode = props.opt("titles")
        when (titlesNode) {
            is JSONArray -> {
                for (i in 0 until titlesNode.length()) {
                    addTitleObj(titlesNode.optJSONObject(i))
                }
            }
            is JSONObject -> {
                val dataArr = titlesNode.optJSONArray("data")
                if (dataArr != null) {
                    for (i in 0 until dataArr.length()) {
                        addTitleObj(dataArr.optJSONObject(i))
                    }
                }
            }
        }

        return results
    }

    internal fun extractInertiaProps(html: String): JSONObject? {
        val doc = Jsoup.parse(html)
        val appDiv = doc.selectFirst("#app[data-page]") ?: return null
        val dataPage = appDiv.attr("data-page")
        if (dataPage.isBlank()) return null
        return runCatching {
            JSONObject(dataPage).optJSONObject("props")
        }.getOrNull()
    }

    /**
     * Estrae Film e Serie TV dal catalogo HTML di `streaming-community.toys`.
     */
    internal fun scrapeStreamingCommunityToys(
        url: String,
        baseUrl: String,
        defaultIsSeries: Boolean?
    ): List<CatalogEntry> {
        val html = getText(url, referer = "$baseUrl/")
        val doc = Jsoup.parse(html, baseUrl)
        val elements = doc.select("a.tile-image[data-title]")
        val results = mutableListOf<CatalogEntry>()
        val seenImdb = HashSet<String>()

        for (el in elements) {
            val title = el.attr("data-title").trim()
            val imdbId = el.attr("data-imdbid").trim()
            val year = el.attr("data-year").trim()
            val duration = el.attr("data-duration").trim()
            val kind = el.attr("data-kind").trim().lowercase()
            val href = el.attr("href").trim()

            if (title.isBlank() || !imdbId.startsWith("tt")) continue

            val isSeries = when {
                defaultIsSeries != null -> defaultIsSeries
                kind == "tv" || kind == "series" -> true
                kind == "movie" || kind == "film" -> false
                else -> duration.isBlank()
            }
            // Esclude i film non ancora usciti (durata vuota)
            if (!isSeries && duration.isBlank()) continue
            if (!seenImdb.add(imdbId)) continue

            val numericImdb = imdbId.removePrefix("tt").toIntOrNull()
            results.add(
                CatalogEntry(
                    imdbId = imdbId,
                    title = title,
                    isSeries = isSeries,
                    year = year,
                    tmdbId = numericImdb,
                    scPagePath = href.takeIf { it.startsWith("/") }
                )
            )
        }
        return results
    }

    /**
     * Toglie gli episodi oltre il numero dichiarato disponibile dal player vidxgo per ciascuna stagione.
     * Stagioni senza conteggio noto restano invariate.
     */
    internal fun filterAvailableEpisodes(
        seasons: Map<Int, List<EpisodeEntry>>,
        availableCounts: Map<Int, Int>
    ): Map<Int, List<EpisodeEntry>> {
        val result = sortedMapOf<Int, List<EpisodeEntry>>()
        for ((season, episodes) in seasons) {
            val count = availableCounts[season]
            val visible = if (count == null) episodes else episodes.filter { it.episode <= count }
            if (visible.isNotEmpty()) result[season] = visible
        }
        return result
    }

    /**
     * Costruisce l'URL episodio:
     * - Per le serie del catalogo ufficiale StreamingCommunity (`scTitleId` + `scEpisodeId`):
     *   `$baseUrl/it/watch/$scTitleId?e=$scEpisodeId`
     * - Per le serie IMDb / `v.vidxgo.co`:
     *   `https://v.vidxgo.co/{imdbNumeric}/{season}/{episode}?se=0&info=0`
     */
    fun buildSeriesEpisodeUrl(details: SeriesDetails, episode: EpisodeEntry): String {
        val scId = details.scTitleId ?: details.imdbId.removePrefix("sc:").toIntOrNull()
        val epId = episode.scEpisodeId
        if (scId != null && scId > 0 && epId != null && epId > 0) {
            val baseUrl = StreamingCommunityUrlResolver.getCachedOrDefaultBaseUrl()
            return "$baseUrl/it/watch/$scId?e=$epId"
        }
        return buildSeriesEpisodeVixUrl(details.imdbId, episode.season, episode.episode)
    }

    /**
     * Costruisce l'URL episodio `https://v.vidxgo.co/{imdbNumeric}/{season}/{episode}?se=0&info=0`
     * preservando eventuali zeri iniziali dell'ID IMDb (es. tt0903747 -> 0903747).
     */
    fun buildSeriesEpisodeVixUrl(imdbId: String, season: Int, episode: Int): String {
        val numeric = imdbId.removePrefix("tt").trim()
        return "$VIDXGO_BASE/$numeric/$season/$episode?se=0&info=0"
    }

    fun buildSeriesEpisodeVixUrl(tmdbId: Int, season: Int, episode: Int): String {
        return "$VIDXGO_BASE/$tmdbId/$season/$episode?se=0&info=0"
    }

    private fun parseCinemetaMetas(arr: JSONArray?, isSeries: Boolean): List<CatalogEntry> {
        if (arr == null) return emptyList()
        val list = mutableListOf<CatalogEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val imdbId = obj.optString("imdb_id", obj.optString("id", ""))
            val name = obj.optString("name", "")
            val year = obj.optString("releaseInfo", "")
            if (imdbId.startsWith("tt") && name.isNotBlank()) {
                val numericImdb = imdbId.removePrefix("tt").toIntOrNull()
                list.add(
                    CatalogEntry(
                        imdbId = imdbId,
                        title = name,
                        isSeries = isSeries,
                        year = year,
                        tmdbId = numericImdb
                    )
                )
            }
        }
        return list
    }

    private fun getText(url: String, referer: String? = null): String {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8")
            .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
            .get()
        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", referer)
        }
        YouTubeStreamExtractor.okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} su $url")
            }
            return resp.body?.string() ?: ""
        }
    }
}
