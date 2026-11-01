package com.casadei.aavideoscreensaver.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.casadei.aavideoscreensaver.extractor.StreamedPkClient
import com.casadei.aavideoscreensaver.extractor.StreamingCommunityCatalogClient
import com.casadei.aavideoscreensaver.extractor.YouTubeStreamExtractor
import com.casadei.aavideoscreensaver.model.PlayerUiState
import com.casadei.aavideoscreensaver.model.SourceType
import com.casadei.aavideoscreensaver.player.ScreensaverRepository
import com.casadei.aavideoscreensaver.player.VideoPlayerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONException

/*
 * Struttura dei menu e quota template di Android Auto
 * ---------------------------------------------------
 * L'host consente al massimo 5 template per task e il quinto deve essere Navigation/Pane/Message/...:
 * oltre, mostra un errore e chiude l'app. Un ListTemplate conta come "refresh" (gratuito) solo se titolo,
 * numero di righe e titoli delle righe restano uguali; un SearchTemplate invece considera refresh qualsiasi
 * cambio di contenuto.
 *
 * Profondità massima dopo la riorganizzazione:
 *   1 VideoSurfaceScreen (NavigationTemplate, azzera la quota)
 *   2 CarBrowseHubScreen (ListTemplate statico)
 *   3 Browser del provider (SearchTemplate: sottomenu, pagine, ricerca, modalità elimina = refresh)
 *   4 Solo: episodi di una serie (SearchTemplate) e sorgenti di un evento Streamed.pk (ListTemplate senza pagine)
 * Scegliere un contenuto avvia la riproduzione e torna alla radice (popToRoot).
 */

internal object CarListLimits {
    fun maxRows(carContext: CarContext): Int {
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6)
        return limit.coerceAtLeast(6)
    }
}

/** Esegue un caricamento trasformando gli errori in Result, senza mai inghiottire la cancellazione. */
private suspend fun <T> loadSafely(block: suspend () -> Result<T>): Result<T> {
    return try {
        val res = block()
        val err = res.exceptionOrNull()
        if (err is CancellationException) throw err
        res
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
}

private fun friendlyError(t: Throwable, fallback: String): String = when (t) {
    is JSONException -> "Risposta non valida dal servizio (possibile blocco temporaneo). Riprova più tardi."
    else -> t.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
}

/**
 * Schermata di navigazione basata su SearchTemplate: ogni sottomenu, pagina o risultato di ricerca è un refresh
 * della stessa schermata e non consuma la quota di template di Android Auto.
 */
abstract class CarBrowserScreen(
    carContext: CarContext,
    private val searchHint: String
) : Screen(carContext) {

    protected class BrowserRow(
        val title: String,
        val text: String,
        val browsable: Boolean = false,
        val onClick: () -> Unit
    )

    private class BrowserView(
        val label: String,
        val emptyMessage: String,
        val isSearch: Boolean,
        val loader: suspend () -> Result<List<BrowserRow>>
    ) {
        var cachedRows: List<BrowserRow>? = null
        var cachedMessage: String = emptyMessage
    }

    private val viewStack = ArrayDeque<BrowserView>()
    private var rows: List<BrowserRow> = emptyList()
    private var emptyMessage: String = ""
    private var isLoading = false
    private var currentPage = 0
    private var searchDraft = ""
    private var loadJob: Job? = null
    private var started = false

    /** Contenuto iniziale della schermata. */
    protected abstract suspend fun loadRoot(): Result<List<BrowserRow>>

    protected abstract val rootEmptyMessage: String

    /** Ricerca testuale o vocale inviata dall'utente. */
    protected abstract fun onSearch(query: String)

    /** true se il contenuto può cambiare mentre si è in una sottovista (es. playlist): al ritorno si ricarica. */
    protected open val reloadOnBack: Boolean = false

    /** Da chiamare nel costruttore delle sottoclassi, dopo l'inizializzazione dei loro campi. */
    protected fun start() {
        if (started) return
        started = true
        viewStack.clear()
        viewStack.addLast(BrowserView("Inizio", rootEmptyMessage, isSearch = false) { loadRoot() })
        display(viewStack.last(), forceReload = true)
    }

    protected fun showView(
        label: String,
        emptyMessage: String,
        isSearch: Boolean = false,
        loader: suspend () -> Result<List<BrowserRow>>
    ) {
        // Ricerche successive sostituiscono la precedente invece di accumularsi nello stack
        if (isSearch && viewStack.size > 1 && viewStack.last().isSearch) {
            viewStack.removeLast()
        }
        val view = BrowserView(label, emptyMessage, isSearch, loader)
        viewStack.addLast(view)
        display(view, forceReload = true)
    }

    /** Ricarica la vista corrente (es. playlist cambiata) restando sulla stessa pagina. */
    protected fun refreshCurrentView() {
        viewStack.lastOrNull()?.let { display(it, forceReload = true, keepPage = true) }
    }

    /**
     * Push protetto dal doppio tocco: se l'utente tocca due volte prima che l'host aggiorni la lista, il secondo
     * push aggiungerebbe un template in più e potrebbe superare la quota di 5 (l'host chiuderebbe l'app).
     */
    protected fun pushIfOnTop(screen: Screen) {
        if (screenManager.top === this) screenManager.push(screen)
    }

    protected fun isAtRoot(): Boolean = viewStack.size <= 1

    private fun goBack() {
        if (viewStack.size <= 1) return
        viewStack.removeLast()
        display(viewStack.last(), forceReload = reloadOnBack)
    }

    private fun display(view: BrowserView, forceReload: Boolean, keepPage: Boolean = false) {
        loadJob?.cancel()
        if (!keepPage) currentPage = 0
        val cached = view.cachedRows
        if (!forceReload && cached != null) {
            rows = cached
            emptyMessage = view.cachedMessage
            isLoading = false
            invalidate()
            return
        }
        isLoading = true
        invalidate()
        loadJob = lifecycleScope.launch {
            val res = loadSafely { view.loader() }
            ensureActive()
            val loadedRows = res.getOrDefault(emptyList())
            val message = res.exceptionOrNull()?.let { friendlyError(it, view.emptyMessage) } ?: view.emptyMessage
            view.cachedRows = loadedRows
            view.cachedMessage = message
            if (viewStack.lastOrNull() === view) {
                rows = loadedRows
                emptyMessage = message
                isLoading = false
                invalidate()
            }
        }
    }

    private fun toRow(r: BrowserRow): Row {
        val builder = Row.Builder()
            .setTitle(r.title)
            .setOnClickListener { r.onClick() }
        if (r.text.isNotBlank()) builder.addText(r.text)
        if (r.browsable) builder.setBrowsable(true)
        return builder.build()
    }

    private fun buildItemList(): ItemList {
        val builder = ItemList.Builder().setNoItemsMessage(emptyMessage.ifBlank { "Nessun elemento" })

        val leading = mutableListOf<Row>()
        val query = searchDraft.trim()
        if (query.length >= 2) {
            leading += toRow(BrowserRow("🔍 Cerca \"$query\"", "Tocca qui o premi Invio per cercare") {
                searchDraft = ""
                onSearch(query)
            })
        }
        if (viewStack.size > 1) {
            val previous = viewStack[viewStack.size - 2].label
            leading += toRow(BrowserRow("⬅️ Indietro", "Torna a: $previous") { goBack() })
        }
        leading.forEach { builder.addItem(it) }

        if (rows.isEmpty()) {
            if (leading.isNotEmpty()) {
                // Con righe di servizio presenti il messaggio "nessun elemento" non verrebbe mostrato dall'host
                builder.addItem(toRow(BrowserRow(emptyMessage.ifBlank { "Nessun elemento" }, "") {}))
            }
            return builder.build()
        }

        val capacity = (CarListLimits.maxRows(carContext) - leading.size).coerceAtLeast(3)
        if (rows.size <= capacity) {
            rows.forEach { builder.addItem(toRow(it)) }
            return builder.build()
        }

        // Paginazione: nel SearchTemplate cambiare pagina è un refresh, non consuma quota
        val pageSize = (capacity - 2).coerceAtLeast(1)
        val totalPages = (rows.size + pageSize - 1) / pageSize
        val page = currentPage.coerceIn(0, totalPages - 1)
        val startIdx = page * pageSize
        val endIdx = (startIdx + pageSize).coerceAtMost(rows.size)
        if (page > 0) {
            builder.addItem(toRow(BrowserRow("⬅️ Pagina precedente (${page}/$totalPages)", "") {
                currentPage = page - 1
                invalidate()
            }))
        }
        for (i in startIdx until endIdx) builder.addItem(toRow(rows[i]))
        if (page < totalPages - 1) {
            builder.addItem(toRow(BrowserRow("➡️ Pagina successiva (${page + 2}/$totalPages)", "${rows.size - endIdx} rimanenti") {
                currentPage = page + 1
                invalidate()
            }))
        }
        return builder.build()
    }

    override fun onGetTemplate(): Template {
        val builder = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                searchDraft = searchText
                invalidate()
            }

            override fun onSearchSubmitted(searchText: String) {
                val q = searchText.trim().ifBlank { searchDraft.trim() }
                if (q.isNotBlank()) {
                    searchDraft = ""
                    onSearch(q)
                }
            }
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint(searchHint)
            .setShowKeyboardByDefault(false)

        if (isLoading) {
            return builder.setLoading(true).build()
        }
        return builder.setItemList(buildItemList()).build()
    }

    protected fun playAndReturn(title: String, uriOrUrl: String, sourceType: SourceType) {
        CarToast.makeText(carContext, "Avvio: $title", CarToast.LENGTH_SHORT).show()
        VideoPlayerController.playOrAddCatalogItem(
            context = carContext,
            title = title,
            uriOrUrl = uriOrUrl,
            sourceType = sourceType
        )
        screenManager.popToRoot()
    }
}

/**
 * Hub principale (livello 2). Righe statiche: un titolo che cambia (es. con il numero di elementi della playlist)
 * al ritorno da un sottomenu verrebbe contato come nuovo template.
 */
class CarBrowseHubScreen(carContext: CarContext) : Screen(carContext) {

    // Protezione dal doppio tocco (vedi CarBrowserScreen.pushIfOnTop)
    private fun pushIfOnTop(screen: Screen) {
        if (screenManager.top === this) screenManager.push(screen)
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("📂 Playlist salvata")
                    .addText("Video locali, flussi salvati e contenuti recenti")
                    .setBrowsable(true)
                    .setOnClickListener { pushIfOnTop(PlaylistBrowserScreen(carContext)) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("▶️ YouTube")
                    .addText("Cerca con voce 🎤 o tastiera, categorie rapide")
                    .setBrowsable(true)
                    .setOnClickListener { pushIfOnTop(YouTubeBrowserScreen(carContext)) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("🍿 StreamingCommunity")
                    .addText("Film e Serie TV in italiano, ricerca vocale 🎤")
                    .setBrowsable(true)
                    .setOnClickListener { pushIfOnTop(StreamingCommunityBrowserScreen(carContext)) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("⚽ Streamed.pk")
                    .addText("Dirette sportive ed eventi live")
                    .setBrowsable(true)
                    .setOnClickListener { pushIfOnTop(StreamedPkBrowserScreen(carContext)) }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setSingleList(list)
            .setTitle("AA Video — Scegli sorgente")
            .setHeaderAction(Action.BACK)
            .build()
    }
}

// ============================================================================
// YOUTUBE (livello 3)
// ============================================================================

class YouTubeBrowserScreen(carContext: CarContext) :
    CarBrowserScreen(carContext, "Cerca video o canale su YouTube...") {

    private data class QuickCategory(val title: String, val subtitle: String, val query: String)

    private val quickCategories = listOf(
        QuickCategory("🔴 Notizie & TV italiana live", "Sky TG24, RaiNews24, TGCOM24 e dirette news", "tg diretta live italia"),
        QuickCategory("🎵 Lofi & Chill 24/7 live", "Musica rilassante e visual per la guida notturna", "lofi hip hop radio beats to relax study live"),
        QuickCategory("🏔️ 4K Nature & Scenic Drive", "Paesaggi, strade panoramiche e natura 60fps", "4k scenic drive nature Relaxation film 60fps"),
        QuickCategory("🌍 NASA & Spazio ISS live", "La Terra vista dallo spazio in diretta", "NASA live earth from space ISS")
    )

    override val rootEmptyMessage = "Usa il microfono 🎤 o la tastiera per cercare"

    init {
        start()
    }

    override suspend fun loadRoot(): Result<List<BrowserRow>> = Result.success(
        quickCategories.map { cat ->
            BrowserRow(cat.title, cat.subtitle, browsable = true) {
                showView(cat.title, "Nessun video trovato") { searchRows(cat.query) }
            }
        }
    )

    override fun onSearch(query: String) {
        showView("🔍 $query", "Nessun video YouTube trovato per '$query'", isSearch = true) { searchRows(query) }
    }

    private suspend fun searchRows(query: String): Result<List<BrowserRow>> =
        YouTubeStreamExtractor.searchVideos(query).map { list ->
            list.map { vid ->
                BrowserRow("▶️ ${vid.title}", "${vid.uploader} • ${vid.durationOrBadge}") {
                    playAndReturn("▶️ ${vid.title}", vid.url, SourceType.YOUTUBE)
                }
            }
        }
}

// ============================================================================
// STREAMINGCOMMUNITY (livello 3, serie -> livello 4)
// ============================================================================

class StreamingCommunityBrowserScreen(carContext: CarContext) :
    CarBrowserScreen(carContext, "Cerca film o serie TV...") {

    override val rootEmptyMessage = "Usa il microfono 🎤 o la tastiera per cercare"

    init {
        start()
    }

    override suspend fun loadRoot(): Result<List<BrowserRow>> = Result.success(
        listOf(
            BrowserRow("🎬 Film popolari", "I film più visti in italiano", browsable = true) {
                showView("Film popolari", "Nessun film disponibile") {
                    catalogRows(StreamingCommunityCatalogClient.fetchPopularMovies())
                }
            },
            BrowserRow("📺 Serie TV popolari", "Scelta di stagione ed episodio", browsable = true) {
                showView("Serie TV popolari", "Nessuna serie disponibile") {
                    catalogRows(StreamingCommunityCatalogClient.fetchPopularSeries())
                }
            }
        )
    )

    override fun onSearch(query: String) {
        showView("🔍 $query", "Nessun titolo trovato per '$query'", isSearch = true) {
            catalogRows(StreamingCommunityCatalogClient.searchCatalog(query))
        }
    }

    private fun catalogRows(
        res: Result<List<StreamingCommunityCatalogClient.CatalogEntry>>
    ): Result<List<BrowserRow>> = res.map { entries ->
        entries.map { media ->
            val yearBadge = if (media.year.isNotBlank()) " (${media.year})" else ""
            if (media.isSeries) {
                BrowserRow("📺 ${media.title}$yearBadge", "Serie TV • Scegli stagione ed episodio", browsable = true) {
                    pushIfOnTop(StreamingCommunitySeriesScreen(carContext, media))
                }
            } else {
                BrowserRow("🎬 ${media.title}$yearBadge", "Film • Tocca per avviare") {
                    launchMovie(media, yearBadge)
                }
            }
        }
    }

    private fun launchMovie(media: StreamingCommunityCatalogClient.CatalogEntry, yearBadge: String) {
        lifecycleScope.launch {
            StreamingCommunityCatalogClient.resolveMovieVixUrl(media)
                .onSuccess { url -> playAndReturn("🍿 ${media.title}$yearBadge", url, SourceType.STREAMING_COMMUNITY) }
                .onFailure { err ->
                    if (err is CancellationException) throw err
                    CarToast.makeText(carContext, err.localizedMessage ?: "Film non disponibile", CarToast.LENGTH_LONG).show()
                }
        }
    }
}

/** Stagioni ed episodi nella stessa schermata (livello 4): passare da stagione a episodi è un refresh. */
class StreamingCommunitySeriesScreen(
    carContext: CarContext,
    private val series: StreamingCommunityCatalogClient.CatalogEntry
) : CarBrowserScreen(carContext, "Cerca episodio (es. 2x05 o titolo)...") {

    private var details: StreamingCommunityCatalogClient.SeriesDetails? = null

    override val rootEmptyMessage = "Nessuna stagione disponibile per ${series.title}"

    init {
        start()
    }

    override suspend fun loadRoot(): Result<List<BrowserRow>> =
        StreamingCommunityCatalogClient.fetchSeriesDetails(series).map { d ->
            details = d
            d.seasons.keys.sorted().map { season ->
                val eps = d.seasons[season].orEmpty()
                BrowserRow("📂 Stagione $season", "${eps.size} episodi", browsable = true) {
                    showView("Stagione $season", "Nessun episodio in questa stagione") {
                        Result.success(episodeRows(d, eps))
                    }
                }
            }
        }

    override fun onSearch(query: String) {
        val d = details
        if (d == null) {
            CarToast.makeText(carContext, "Attendi il caricamento degli episodi", CarToast.LENGTH_SHORT).show()
            return
        }
        val q = query.trim().lowercase()
        val codeMatch = Regex("""^s?(\d{1,2})\s*[xe]\s*(\d{1,3})$""").find(q)
        val matches = d.seasons.values.flatten().filter { ep ->
            if (codeMatch != null) {
                ep.season == codeMatch.groupValues[1].toInt() && ep.episode == codeMatch.groupValues[2].toInt()
            } else {
                ep.title.lowercase().contains(q) || ep.code.lowercase().contains(q)
            }
        }
        showView("🔍 $query", "Nessun episodio trovato per '$query'", isSearch = true) {
            Result.success(episodeRows(d, matches))
        }
    }

    private fun episodeRows(
        d: StreamingCommunityCatalogClient.SeriesDetails,
        episodes: List<StreamingCommunityCatalogClient.EpisodeEntry>
    ): List<BrowserRow> {
        val progressMap = ScreensaverRepository.loadAllSavedProgress(carContext)
        return episodes.map { ep ->
            val url = StreamingCommunityCatalogClient.buildSeriesEpisodeVixUrl(d.imdbId, ep.season, ep.episode)
            val prog = progressMap[ScreensaverRepository.normalizeProgressKey(url)]
            val hint = if (prog != null && prog.positionMs > 5_000L && prog.durationMs > 0L) {
                "⏱️ Riprendi da ${PlayerUiState.formatTimeMs(prog.positionMs)} / ${PlayerUiState.formatTimeMs(prog.durationMs)}"
            } else {
                "Tocca per riprodurre"
            }
            BrowserRow("▶️ ${ep.code} — ${ep.title}", hint) {
                playAndReturn("🍿 ${d.name} ${ep.code} - ${ep.title}", url, SourceType.STREAMING_COMMUNITY)
            }
        }
    }
}

// ============================================================================
// STREAMED.PK (livello 3, sorgenti -> livello 4)
// ============================================================================

class StreamedPkBrowserScreen(carContext: CarContext) :
    CarBrowserScreen(carContext, "Cerca squadra, evento o sport...") {

    override val rootEmptyMessage = "Usa il microfono 🎤 o la tastiera per cercare"

    init {
        start()
    }

    override suspend fun loadRoot(): Result<List<BrowserRow>> = Result.success(
        listOf(
            BrowserRow("🔴 In diretta ora", "Eventi live in questo momento", browsable = true) {
                showView("In diretta ora", "Nessun evento live in questo momento") {
                    matchRows(StreamedPkClient.fetchLiveMatches())
                }
            },
            BrowserRow("🔥 Live popolari", "Gli eventi più seguiti", browsable = true) {
                showView("Live popolari", "Nessun evento popolare live") {
                    matchRows(StreamedPkClient.fetchPopularLiveMatches())
                }
            },
            BrowserRow("📅 Eventi di oggi", "Calendario completo di oggi", browsable = true) {
                showView("Eventi di oggi", "Nessun evento oggi") {
                    matchRows(StreamedPkClient.fetchAllTodayMatches())
                }
            },
            BrowserRow("🏆 Per sport", "Calcio, motori, tennis, basket...", browsable = true) {
                showView("Per sport", "Nessuna categoria disponibile") { categoryRows() }
            }
        )
    )

    override fun onSearch(query: String) {
        showView("🔍 $query", "Nessun evento trovato per '$query'", isSearch = true) {
            matchRows(StreamedPkClient.searchMatches(query))
        }
    }

    private suspend fun categoryRows(): Result<List<BrowserRow>> =
        StreamedPkClient.fetchSportsCategories().map { cats ->
            cats.map { cat ->
                BrowserRow("${cat.emoji} ${cat.name}", "Eventi live e in programma", browsable = true) {
                    showView(cat.name, "Nessun evento per ${cat.name}") {
                        matchRows(StreamedPkClient.fetchMatchesBySport(cat.id))
                    }
                }
            }
        }

    private fun matchRows(res: Result<List<StreamedPkClient.StreamedMatch>>): Result<List<BrowserRow>> =
        res.map { matches ->
            matches.map { match ->
                val pop = if (match.popular) "🔥 " else ""
                BrowserRow(
                    "${match.categoryEmoji} $pop${match.title}",
                    "${match.formattedTime} • ${match.sources.size} sorgenti",
                    browsable = true
                ) {
                    pushIfOnTop(StreamedPkStreamOptionsCarScreen(carContext, match))
                }
            }
        }
}

/**
 * Sorgenti di un evento (livello 4). ListTemplate senza paginazione: il passaggio caricamento -> lista è un
 * refresh; una pagina successiva sarebbe un quinto template non ammesso. Le sorgenti sono ordinate per qualità
 * e il resolver prova comunque le alternative dello stesso evento.
 */
class StreamedPkStreamOptionsCarScreen(
    carContext: CarContext,
    private val match: StreamedPkClient.StreamedMatch
) : Screen(carContext) {

    private var isLoading = true
    private var streams: List<StreamedPkClient.StreamOption> = emptyList()
    private var errorMsg: String? = null

    init {
        lifecycleScope.launch {
            val res = loadSafely { StreamedPkClient.fetchStreamsForMatch(match) }
            streams = res.getOrDefault(emptyList())
            errorMsg = res.exceptionOrNull()?.let { friendlyError(it, "Errore caricamento sorgenti") }
            isLoading = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val builder = ListTemplate.Builder()
            .setTitle("📡 ${match.title}")
            .setHeaderAction(Action.BACK)
        if (isLoading) return builder.setLoading(true).build()

        val list = ItemList.Builder()
            .setNoItemsMessage(errorMsg ?: "Nessun flusso attivo per questo evento al momento")
        streams.take(CarListLimits.maxRows(carContext)).forEach { opt ->
            list.addItem(
                Row.Builder()
                    .setTitle(opt.label)
                    .addText("Tocca per avviare la diretta")
                    .setOnClickListener {
                        val title = "⚽ ${match.title} (${opt.source.uppercase()} #${opt.streamNo})"
                        CarToast.makeText(carContext, "Avvio diretta: ${match.title}", CarToast.LENGTH_SHORT).show()
                        VideoPlayerController.playOrAddCatalogItem(
                            context = carContext,
                            title = title,
                            uriOrUrl = opt.embedUrl,
                            sourceType = SourceType.STREAMED_PK
                        )
                        screenManager.popToRoot()
                    }
                    .build()
            )
        }
        return builder.setSingleList(list.build()).build()
    }
}

// ============================================================================
// PLAYLIST (livello 3)
// ============================================================================

/** Playlist su SearchTemplate: modalità elimina e rimozioni sono refresh (in un ListTemplate consumerebbero quota). */
class PlaylistBrowserScreen(carContext: CarContext) :
    CarBrowserScreen(carContext, "Cerca nella playlist...") {

    private var isDeleteMode = false

    override val rootEmptyMessage = "La playlist è vuota. Aggiungi video dal telefono o scegli dal catalogo."

    override val reloadOnBack = true

    init {
        start()
        // Aggiorna solo quando cambiano elementi o elemento attivo, non a ogni secondo di riproduzione
        lifecycleScope.launch {
            VideoPlayerController.uiState
                .map { s -> s.playlist.map { it.id to it.title } to s.currentIndex }
                .distinctUntilChanged()
                .drop(1)
                .collect { refreshCurrentView() }
        }
    }

    override suspend fun loadRoot(): Result<List<BrowserRow>> {
        val toggle = BrowserRow(
            if (isDeleteMode) "✅ Fine eliminazione" else "🗑️ Elimina elementi",
            if (isDeleteMode) "Tocca un elemento per rimuoverlo" else "Attiva la modalità rimozione"
        ) {
            isDeleteMode = !isDeleteMode
            refreshCurrentView()
        }
        return Result.success(listOf(toggle) + itemRows(VideoPlayerController.uiState.value, null))
    }

    override fun onSearch(query: String) {
        showView("🔍 $query", "Nessun elemento trovato per '$query'", isSearch = true) {
            Result.success(itemRows(VideoPlayerController.uiState.value, query.trim().lowercase()))
        }
    }

    private fun itemRows(state: PlayerUiState, filter: String?): List<BrowserRow> {
        val currentId = state.currentItem?.id
        return state.playlist
            .filter { filter == null || it.title.lowercase().contains(filter) }
            .map { item ->
                if (isDeleteMode) {
                    BrowserRow("🗑️ Rimuovi: ${item.title}", "[${item.sourceType.badge}]") {
                        VideoPlayerController.removeItemById(carContext, item.id)
                        CarToast.makeText(carContext, "🗑️ Rimosso: ${item.title}", CarToast.LENGTH_SHORT).show()
                    }
                } else {
                    val active = if (item.id == currentId) "🟢 IN RIPRODUZIONE • " else ""
                    val resume = if (item.lastPositionMs > 5_000L && item.durationMs > 0L) {
                        "⏱️ ${PlayerUiState.formatTimeMs(item.lastPositionMs)}/${PlayerUiState.formatTimeMs(item.durationMs)} • "
                    } else {
                        ""
                    }
                    BrowserRow(item.title, "$active$resume[${item.sourceType.badge}]") {
                        VideoPlayerController.playItemById(carContext, item.id)
                        screenManager.popToRoot()
                    }
                }
            }
    }
}
