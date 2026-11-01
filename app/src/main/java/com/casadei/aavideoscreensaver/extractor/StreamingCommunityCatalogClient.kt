package com.casadei.aavideoscreensaver.extractor

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Client per sfogliare e cercare Film e Serie TV del catalogo StreamingCommunity ITA
 * (`streaming-community.biz` + `v.vidxgo.co` + `v3-cinemeta.strem.io`)
 * direttamente dallo schermo di Android Auto senza dover usare il telefono.
 */
object StreamingCommunityCatalogClient {

    private const val SC_BIZ_BASE = "https://streaming-community.biz"
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
        val scPagePath: String? = null
    ) {
        val badge: String
            get() = if (isSeries) "📺 SERIE TV" else "🎬 FILM"
    }

    data class EpisodeEntry(
        val season: Int,
        val episode: Int,
        val title: String,
        val releasedYear: String = ""
    ) {
        val code: String
            get() = "S%02dE%02d".format(season, episode)
    }

    data class SeriesDetails(
        val imdbId: String,
        val tmdbId: Int,
        val name: String,
        val seasons: Map<Int, List<EpisodeEntry>>
    )

    suspend fun fetchPopularMovies(): Result<List<CatalogEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val scMovies = runCatching {
                scrapeStreamingCommunityBiz("$SC_BIZ_BASE/film/", defaultIsSeries = false)
            }.getOrDefault(emptyList())

            if (scMovies.isNotEmpty()) {
                return@runCatching scMovies
            }

            val cinemetaMovies = runCatching {
                val json = getText("$CINEMETA_BASE/catalog/movie/top.json")
                parseCinemetaMetas(JSONObject(json).optJSONArray("metas"), isSeries = false)
            }.getOrDefault(emptyList())

            mergeUniqueEntries(scMovies, cinemetaMovies)
        }
    }

    suspend fun fetchPopularSeries(): Result<List<CatalogEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val scSeries = runCatching {
                scrapeStreamingCommunityBiz("$SC_BIZ_BASE/series/", defaultIsSeries = true)
            }.getOrDefault(emptyList())

            if (scSeries.isNotEmpty()) {
                return@runCatching scSeries
            }

            val cinemetaSeries = runCatching {
                val json = getText("$CINEMETA_BASE/catalog/series/top.json")
                parseCinemetaMetas(JSONObject(json).optJSONArray("metas"), isSeries = true)
            }.getOrDefault(emptyList())

            mergeUniqueEntries(scSeries, cinemetaSeries)
        }
    }

    suspend fun searchCatalog(query: String): Result<List<CatalogEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = query.trim()
            if (clean.isBlank()) return@runCatching fetchPopularMovies().getOrDefault(emptyList())
            val encoded = Uri.encode(clean)

            // 1. Ricerca diretta nell'archivio italiano di StreamingCommunity
            val scResults = runCatching {
                scrapeStreamingCommunityBiz("$SC_BIZ_BASE/archivio/?search=$encoded", defaultIsSeries = null)
            }.getOrDefault(emptyList())

            if (scResults.size >= 4) {
                return@runCatching scResults
            }

            // 2. Fallback/Integrazione con Cinemeta solo se la ricerca diretta ha pochi risultati
            val movies = runCatching {
                val mJson = getText("$CINEMETA_BASE/catalog/movie/top/search=$encoded.json")
                parseCinemetaMetas(JSONObject(mJson).optJSONArray("metas"), isSeries = false)
            }.getOrDefault(emptyList())

            val series = runCatching {
                val sJson = getText("$CINEMETA_BASE/catalog/series/top/search=$encoded.json")
                parseCinemetaMetas(JSONObject(sJson).optJSONArray("metas"), isSeries = true)
            }.getOrDefault(emptyList())

            val combinedCinemeta = mutableListOf<CatalogEntry>()
            val maxLen = maxOf(movies.size, series.size)
            for (i in 0 until maxLen) {
                if (i < movies.size) combinedCinemeta.add(movies[i])
                if (i < series.size) combinedCinemeta.add(series[i])
            }

            mergeUniqueEntries(scResults, combinedCinemeta)
        }
    }

    /**
     * Costruisce l'URL di riproduzione immediata per un Film su `v.vidxgo.co` (1080p ITA, senza Cloudflare).
     */
    suspend fun resolveMovieVixUrl(entry: CatalogEntry): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val numericImdb = entry.imdbId.removePrefix("tt").trim()
            if (numericImdb.isNotBlank() && numericImdb.all { it.isDigit() }) {
                return@runCatching "$VIDXGO_BASE/$numericImdb?se=0&info=0"
            }
            if (!entry.scPagePath.isNullOrBlank()) {
                return@runCatching "$SC_BIZ_BASE${entry.scPagePath}"
            }
            throw IllegalStateException("ID IMDb non valido per ${entry.title}")
        }
    }

    /**
     * Scarica stagioni ed episodi di una Serie TV per permetterne la scelta direttamente su Android Auto.
     * Filtra automaticamente le stagioni/puntate future non ancora uscite (es. "TBA") verificando le stagioni
     * realmente presenti su `v.vidxgo.co`.
     */
    suspend fun fetchSeriesDetails(entry: CatalogEntry): Result<SeriesDetails> = withContext(Dispatchers.IO) {
        runCatching {
            val numericImdbStr = entry.imdbId.removePrefix("tt").trim()
            val numericImdbInt = numericImdbStr.toIntOrNull() ?: (entry.tmdbId ?: 0)
            if (numericImdbInt <= 0) {
                throw IllegalStateException("ID IMDb non disponibile per ${entry.title}")
            }

            // 1. Verifica quali stagioni esistono effettivamente sul player v.vidxgo.co (evita stagioni future TBA)
            val vidxgoSeasonCounts = runCatching {
                StreamingCommunityExtractor.extractSeasonCountsFromVidxgo(numericImdbStr)
            }.getOrDefault(emptyMap())
            val availableVidxgoSeasons = vidxgoSeasonCounts.filterValues { it > 0 }.keys

            val seasonsMap = sortedMapOf<Int, MutableList<EpisodeEntry>>()

            // 2. Carica struttura stagioni/episodi da Cinemeta filtrando episodi "TBA" o stagioni non presenti su vidxgo
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
            // Episodi elencati (Cinemeta / seasons.php) ma non ancora pubblicati sul player
            val availableSeasons = filterAvailableEpisodes(seasonsMap, vidxgoSeasonCounts)

            if (availableSeasons.isEmpty()) {
                // Niente episodi "inventati": porterebbero solo a errori 404 sul player
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
     * Toglie gli episodi oltre il numero dichiarato disponibile dal player vidxgo per ciascuna stagione
     * (`"seasons":[{"n":3,"count":1}]`). Stagioni senza conteggio noto restano invariate.
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

    private fun scrapeStreamingCommunityBiz(
        url: String,
        defaultIsSeries: Boolean?
    ): List<CatalogEntry> {
        val html = getText(url, referer = "$SC_BIZ_BASE/")
        val doc = Jsoup.parse(html, SC_BIZ_BASE)
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
            if (!seenImdb.add(imdbId)) continue

            val isSeries = when {
                defaultIsSeries != null -> defaultIsSeries
                kind == "tv" || kind == "series" -> true
                kind == "movie" || kind == "film" -> false
                else -> duration.isBlank()
            }
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

    private fun mergeUniqueEntries(
        primary: List<CatalogEntry>,
        secondary: List<CatalogEntry>
    ): List<CatalogEntry> {
        val seen = HashSet<String>()
        val merged = mutableListOf<CatalogEntry>()
        for (item in primary) {
            if (seen.add(item.imdbId)) merged.add(item)
        }
        for (item in secondary) {
            if (seen.add(item.imdbId)) merged.add(item)
        }
        return merged
    }

    private fun getText(url: String, referer: String? = null): String {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/json,*/*;q=0.8")
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
