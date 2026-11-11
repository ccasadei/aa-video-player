package com.casadei.aavideoscreensaver.player

import android.content.Context
import android.media.MediaCodec
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.casadei.aavideoscreensaver.extractor.CloudflareWebViewBridge
import com.casadei.aavideoscreensaver.extractor.PitsportClient
import com.casadei.aavideoscreensaver.extractor.PpvClient
import com.casadei.aavideoscreensaver.extractor.StreamingCommunityExtractor
import com.casadei.aavideoscreensaver.extractor.YouTubeStrategy
import com.casadei.aavideoscreensaver.extractor.YouTubeStreamExtractor
import com.casadei.aavideoscreensaver.model.PlayerUiState
import com.casadei.aavideoscreensaver.model.ScreensaverItem
import com.casadei.aavideoscreensaver.model.SourceType
import com.casadei.aavideoscreensaver.model.TapActionMode
import com.casadei.aavideoscreensaver.model.VideoScaleMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Controller centralizzato per ExoPlayer (Media3) condiviso tra Smartphone e Android Auto.
 *
 * - Collega direttamente ExoPlayer a SurfaceContainer.surface di Android Auto a 30/60 fps.
 * - Gestisce l'auto-recovery del decoder hardware se la superficie di Android Auto viene ricreata.
 * - Salva e ripristina automaticamente la posizione di riproduzione (Resume) per ogni video non-live.
 * - Espone stato dettagliato di avanzamento, buffering ed errori visibili sia su AA che su telefono.
 */
@OptIn(UnstableApi::class)
object VideoPlayerController {

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private const val OVERLAY_DURATION_MS = 5000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var exoPlayer: ExoPlayer? = null
    private var activeCarSurface: Surface? = null
    private var resolveJob: Job? = null
    private var activeLoadedItemId: String? = null
    private var decoderRetryCount: Int = 0
    private var httpTokenRetryCount: Int = 0
    private var liveResyncCount: Int = 0
    private var networkRetryCount: Int = 0
    private var lastProgressSaveAtMs: Long = 0L

    // Incrementato a ogni nuovo caricamento: i callback ritardati di un caricamento precedente vengono scartati
    private var loadGeneration: Long = 0L
    private var pendingSameItemRetry: Runnable? = null
    private var itemStartedAtMs: Long = 0L

    // Uscita video su Android Auto via VirtualDisplay + Presentation (proporzioni gestite dal layout)
    private var carOutput: CarVideoOutput? = null
    // Dopo un fallimento del VirtualDisplay si resta sulla superficie diretta senza riprovare a ogni aggancio
    private var carOutputUnavailable: Boolean = false
    private var lastVideoAspectRatio: Float = 0f

    // Strategie YouTube i cui URL sono stati rifiutati (HTTP 403) per l'elemento corrente
    private val youtubeExcludedStrategies = mutableSetOf<YouTubeStrategy>()
    private var currentYouTubeStrategy: YouTubeStrategy? = null
    // true quando l'elemento caricato ha raggiunto STATE_READY almeno una volta (gli URL erano validi)
    private var currentItemReachedReady: Boolean = false

    /** Ripresa richiesta per l'elemento corrente, da verificare quando la durata reale diventa nota. */
    private data class PendingResume(
        val itemId: String,
        val requestedMs: Long,
        val savedDurationMs: Long
    )
    private var pendingResume: PendingResume? = null

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val mediaAudioAttrs = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    private val progressPollRunnable = object : Runnable {
        override fun run() {
            val player = exoPlayer
            if (player != null && activeLoadedItemId != null && resolveJob?.isActive != true) {
                val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                val pos = player.currentPosition.coerceAtLeast(0L)
                val live = player.isCurrentMediaItemLive
                val state = _uiState.value
                if (pos != state.currentPositionMs || dur != state.durationMs || live != state.isLive) {
                    _uiState.update {
                        it.copy(
                            currentPositionMs = pos,
                            durationMs = dur,
                            isLive = live
                        )
                    }
                }
                val now = System.currentTimeMillis()
                if (player.isPlaying && !live && dur > 30_000L && now - lastProgressSaveAtMs >= 5_000L) {
                    lastProgressSaveAtMs = now
                    saveCurrentItemProgressInternal(pos, dur)
                }
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    fun ensureInitialized(context: Context) {
        runOnMain {
            val ctx = context.applicationContext
            CloudflareWebViewBridge.init(ctx)
            if (appContext == null) {
                appContext = ctx
                val playlist = ScreensaverRepository.loadPlaylist(ctx)
                val lastIdx = ScreensaverRepository.loadLastIndex(ctx).coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
                val isMuted = ScreensaverRepository.loadIsMuted(ctx)
                val scaleMode = ScreensaverRepository.loadScaleMode(ctx)
                val repeatAll = ScreensaverRepository.loadRepeatAll(ctx)
                val tapMode = ScreensaverRepository.loadTapActionMode(ctx)
                val initialItem = playlist.getOrNull(lastIdx)

                _uiState.update {
                    it.copy(
                        playlist = playlist,
                        currentIndex = lastIdx,
                        isMuted = isMuted,
                        scaleMode = scaleMode,
                        repeatAll = repeatAll,
                        tapActionMode = tapMode,
                        resolvedTitle = initialItem?.title ?: "",
                        currentPositionMs = initialItem?.lastPositionMs ?: 0L,
                        durationMs = initialItem?.durationMs ?: 0L
                    )
                }
            }

            if (exoPlayer == null) {
                // Nessun avvio automatico qui: aprire l'app sul telefono non deve far partire il video (con audio).
                // La riproduzione parte quando Android Auto fornisce la superficie (attachCarSurface) o su azione utente.
                exoPlayer = createExoPlayer(ctx)
                mainHandler.removeCallbacks(progressPollRunnable)
                mainHandler.post(progressPollRunnable)
            }
        }
    }

    private fun createExoPlayer(context: Context): ExoPlayer {
        val dataSourceFactory = buildDataSourceFactory(context, emptyMap())

        // Alcune sorgenti (es. StreamingCommunity) offrono un solo flusso 1080p a ~3 Mbit/s: se la rete è appena
        // sotto quella soglia, ripartire con 2,5 s di buffer produceva una pausa ogni 10-20 s (log 24-10).
        // Dopo un'interruzione si attendono 8 s: pause meno frequenti, a parità di tempo totale di attesa.
        // Per le dirette DefaultLoadControl limita comunque la soglia a metà della distanza dal punto live.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 20_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 8_000
            )
            .build()

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()

        // Lo schermo dell'auto non supera il 1080p: varianti 1440p/2160p (spesso VP9) caricherebbero solo il decoder
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(1920, 1080)
            .build()

        val state = _uiState.value
        applyAudioSettingsToPlayer(player, state.isMuted)
        applyScaleSettingsToPlayer(player, state.scaleMode)
        // Il player contiene un solo elemento alla volta: con REPEAT_MODE_ONE/ALL non arriverebbe mai a STATE_ENDED
        // e la playlist non avanzerebbe. La ripetizione è gestita in onPlaybackStateChanged(STATE_ENDED).
        player.repeatMode = Player.REPEAT_MODE_OFF

        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                validatePendingResume(player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
                if (!isPlaying) {
                    val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                    if (dur > 30_000L) {
                        saveCurrentItemProgressInternal(player.currentPosition.coerceAtLeast(0L), dur)
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val buffering = playbackState == Player.STATE_BUFFERING
                val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: _uiState.value.durationMs
                val pos = player.currentPosition.coerceAtLeast(0L)

                if (playbackState == Player.STATE_READY) {
                    currentItemReachedReady = true
                    decoderRetryCount = 0
                    httpTokenRetryCount = 0
                    liveResyncCount = 0
                    networkRetryCount = 0
                }

                val statusLabel = when (playbackState) {
                    Player.STATE_BUFFERING -> "⏳ Buffering stream in corso..."
                    Player.STATE_READY -> "Riproduzione attiva (30/60 fps)"
                    Player.STATE_ENDED -> "Fine stream"
                    else -> _uiState.value.statusMessage
                }
                Log.i("AAVideo", "ExoPlayer state=$playbackState ($statusLabel) on idx=${_uiState.value.currentIndex}")
                _uiState.update {
                    it.copy(
                        isBuffering = buffering,
                        errorMessage = if (playbackState == Player.STATE_READY) null else it.errorMessage,
                        statusMessage = if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                            statusLabel
                        } else {
                            it.statusMessage
                        },
                        currentPositionMs = pos,
                        durationMs = dur,
                        isLive = player.isCurrentMediaItemLive
                    )
                }

                if (playbackState == Player.STATE_ENDED && activeLoadedItemId != null) {
                    if (dur > 30_000L) {
                        saveCurrentItemProgressInternal(0L, dur)
                    }
                    val playedForMs = System.currentTimeMillis() - itemStartedAtMs
                    val action = PlaybackPolicy.onEnded(
                        repeatAll = _uiState.value.repeatAll,
                        playlistSize = _uiState.value.playlist.size,
                        playedForMs = playedForMs,
                        durationMs = dur
                    )
                    Log.i("AAVideo", "Fine contenuto idx=${_uiState.value.currentIndex} dopo ${playedForMs}ms -> $action")
                    when (action) {
                        PlaybackPolicy.EndAction.NEXT -> nextVideo()
                        PlaybackPolicy.EndAction.RESTART -> {
                            player.seekTo(0)
                            player.play()
                        }
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // Diagnostica: varianti video disponibili (* = utilizzabili dalla selezione adattiva)
                val variants = tracks.groups
                    .filter { it.type == C.TRACK_TYPE_VIDEO }
                    .flatMap { g -> (0 until g.length).map { i -> g.getTrackFormat(i) to g.isTrackSelected(i) } }
                if (variants.isNotEmpty()) {
                    Log.i(
                        "AAVideo",
                        "Tracce video idx=${_uiState.value.currentIndex}: " + variants.joinToString { (f, selected) ->
                            "${f.width}x${f.height}@${if (f.bitrate > 0) f.bitrate / 1000 else "?"}k${if (selected) "*" else ""}"
                        }
                    )
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    Log.i("AAVideo", "Risoluzione video in uso: ${videoSize.width}x${videoSize.height}")
                    lastVideoAspectRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    carOutput?.setVideoAspectRatio(lastVideoAspectRatio)
                    _uiState.update {
                        it.copy(
                            videoWidth = videoSize.width,
                            videoHeight = videoSize.height
                        )
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("AAVideo", "ExoPlayer error (${error.errorCodeName}) on item idx=${_uiState.value.currentIndex}: ${error.message}", error)

                val httpCode = httpResponseCode(error)
                val sourceType = _uiState.value.currentItem?.sourceType
                // Superficie dell'auto non più utilizzabile (es. sostituita da Android Auto tornando da "Sfoglia"):
                // MediaCodec.setOutputSurface fallisce con CodecException, la catena di effetti con un errore GL
                val surfaceError = (error.errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK &&
                    error.cause is MediaCodec.CodecException) ||
                    error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
                val action = ErrorRecoveryPolicy.decide(
                    errorCode = error.errorCode,
                    httpCode = httpCode,
                    sourceType = sourceType,
                    attempts = ErrorRecoveryPolicy.Attempts(
                        decoderRetries = decoderRetryCount,
                        httpRetries = httpTokenRetryCount,
                        liveResyncs = liveResyncCount,
                        networkRetries = networkRetryCount
                    ),
                    surfaceError = surfaceError
                )
                Log.w("AAVideo", "Recupero errore ${error.errorCodeName} (HTTP $httpCode, $sourceType) -> $action")

                when (action) {
                    ErrorRecoveryPolicy.Action.RESYNC_LIVE -> {
                        // La finestra live è andata avanti durante uno stallo: si riparte dal punto live
                        liveResyncCount++
                        _uiState.update {
                            it.copy(isBuffering = true, errorMessage = null, statusMessage = "⏩ Riaggancio alla diretta...")
                        }
                        player.seekToDefaultPosition()
                        player.prepare()
                        player.play()
                        return
                    }

                    ErrorRecoveryPolicy.Action.RETRY_DECODER -> {
                        // Decoder hardware MediaTek fallito per una Surface transitoria durante il cambio schermata AA
                        decoderRetryCount++
                        _uiState.update {
                            it.copy(isBuffering = true, statusMessage = "⏳ Riconnessione superficie video Android Auto...")
                        }
                        player.clearVideoSurface()
                        scheduleSameItemRetry(player, delayMs = 300L, reattachSurface = true)
                        return
                    }

                    ErrorRecoveryPolicy.Action.SURFACE_LOST -> {
                        // La riproduzione continua senza video (audio e posizione intatti): la nuova superficie
                        // fornita da Android Auto verrà agganciata da attachCarSurface
                        decoderRetryCount++
                        player.clearVideoSurface()
                        val output = carOutput
                        if (output != null && output.isActive) {
                            // Con il VirtualDisplay la superficie del player è la SurfaceView: la si ricollega
                            output.rebindPlayer(player)
                        } else {
                            activeCarSurface = null
                        }
                        _uiState.update {
                            it.copy(
                                carSurfaceConnected = carOutput?.isActive == true && activeCarSurface != null,
                                isBuffering = true,
                                errorMessage = null,
                                statusMessage = "⏳ Riconnessione schermo Android Auto..."
                            )
                        }
                        player.prepare()
                        player.play()
                        return
                    }

                    ErrorRecoveryPolicy.Action.RETRY_NETWORK -> {
                        // Copertura persa per qualche secondo: si riprova lo stesso flusso dalla stessa posizione
                        networkRetryCount++
                        _uiState.update {
                            it.copy(
                                isBuffering = true,
                                errorMessage = null,
                                statusMessage = "📶 Rete assente, nuovo tentativo ($networkRetryCount)..."
                            )
                        }
                        scheduleSameItemRetry(player, delayMs = 3_000L * networkRetryCount, reattachSurface = false)
                        return
                    }

                    ErrorRecoveryPolicy.Action.RERESOLVE -> {
                        // Token/link scaduto: nuova estrazione e ripresa dalla stessa posizione
                        httpTokenRetryCount++
                        if (sourceType == SourceType.YOUTUBE &&
                            ErrorRecoveryPolicy.shouldExcludeYouTubeStrategy(httpCode, currentItemReachedReady)
                        ) {
                            // La strategia produce URL rifiutati: ripeterla darebbe lo stesso 403
                            currentYouTubeStrategy?.let { youtubeExcludedStrategies += it }
                        }
                        val savedPos = if (player.isCurrentMediaItemLive) {
                            0L
                        } else {
                            player.currentPosition.coerceAtLeast(_uiState.value.currentPositionMs).coerceAtLeast(0L)
                        }
                        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: _uiState.value.durationMs
                        if (dur > 30_000L && savedPos > 5_000L) {
                            saveCurrentItemProgressInternal(savedPos, dur)
                        }
                        Log.w(
                            "AAVideo",
                            "Nuova estrazione #$httpTokenRetryCount a pos=${savedPos}ms " +
                                "(strategie YouTube escluse: $youtubeExcludedStrategies)"
                        )
                        _uiState.update {
                            it.copy(
                                isBuffering = true,
                                errorMessage = null,
                                statusMessage = "🔄 Rinnovo link stream e ripresa da ${PlayerUiState.formatTimeMs(savedPos)}..."
                            )
                        }
                        loadAndPlayIndexInternal(_uiState.value.currentIndex, showOverlay = false, overrideResumeMs = savedPos)
                        return
                    }

                    ErrorRecoveryPolicy.Action.SHOW_ERROR -> Unit
                }

                val readableError = buildReadableErrorMessage(error)
                _uiState.update {
                    it.copy(
                        isBuffering = false,
                        errorMessage = readableError,
                        statusMessage = "⚠️ $readableError"
                    )
                }
                triggerOverlayFor5Seconds()
            }
        })

        activeCarSurface?.let { surface ->
            if (carOutput?.isActive == true) {
                carOutput?.rebindPlayer(player)
            } else if (surface.isValid) {
                player.setVideoSurface(surface)
            } else {
                activeCarSurface = null
            }
        }

        return player
    }

    /**
     * Ritenta lo stesso elemento dopo [delayMs] dalla stessa posizione. Il tentativo viene scartato se nel frattempo
     * è stato caricato un altro elemento: la posizione salvata non gli appartiene.
     */
    private fun scheduleSameItemRetry(player: ExoPlayer, delayMs: Long, reattachSurface: Boolean) {
        val savedPos = player.currentPosition.coerceAtLeast(0L)
        val retryGeneration = loadGeneration
        val retryItemId = activeLoadedItemId
        pendingSameItemRetry?.let { mainHandler.removeCallbacks(it) }
        val retry = Runnable {
            pendingSameItemRetry = null
            if (retryGeneration != loadGeneration || retryItemId == null || activeLoadedItemId != retryItemId) {
                Log.w("AAVideo", "Nuovo tentativo scartato: nel frattempo è stato caricato un altro elemento")
                return@Runnable
            }
            val p = exoPlayer ?: return@Runnable
            if (reattachSurface) {
                val output = carOutput
                val surf = activeCarSurface
                if (output != null && output.isActive) {
                    output.rebindPlayer(p)
                } else if (surf != null && surf.isValid) {
                    p.setVideoSurface(surf)
                } else {
                    activeCarSurface = null
                }
            }
            if (p.isCurrentMediaItemLive) {
                p.seekToDefaultPosition()
            } else if (savedPos > 0L) {
                p.seekTo(savedPos)
            }
            p.prepare()
            p.play()
        }
        pendingSameItemRetry = retry
        mainHandler.postDelayed(retry, delayMs)
    }

    private fun httpResponseCode(error: PlaybackException): Int? =
        (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode

    private fun buildReadableErrorMessage(error: PlaybackException): String {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                when (val code = httpResponseCode(error)) {
                    404 -> "Contenuto non disponibile sul server (HTTP 404). Tocca Sfoglia o Swipe per cambiare video."
                    401, 403 -> "Accesso allo stream rifiutato (HTTP $code). Tocca Riprova per rigenerare il link."
                    else -> "Errore server HTTP ${code ?: ""} (${error.errorCodeName}). Tocca Riprova o cambia sorgente."
                }
            }
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                "Timeout o connessione di rete assente. Verifica la rete e tocca Riprova."
            }
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> {
                "Stream non ancora attivo o formato non valido. Prova un'altra sorgente."
            }
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> {
                "Errore inizializzazione decoder video. Tocca Riprova per riavviare il flusso."
            }
            else -> {
                "Errore riproduzione (${error.errorCodeName}). Tocca Riprova o Swipe al prossimo video."
            }
        }
    }

    private fun buildDataSourceFactory(context: Context, headers: Map<String, String>): DefaultDataSource.Factory {
        val ua = headers["User-Agent"] ?: DEFAULT_UA
        val okHttpFactory = OkHttpDataSource.Factory(YouTubeStreamExtractor.okHttpClient)
            .setUserAgent(ua)
        if (headers.isNotEmpty()) {
            okHttpFactory.setDefaultRequestProperties(headers)
        }
        return DefaultDataSource.Factory(context, okHttpFactory)
    }

    /**
     * Collega direttamente il Surface di Android Auto (SurfaceContainer.surface) a ExoPlayer.
     */
    fun attachCarSurface(context: Context, surface: Surface?, width: Int, height: Int, dpi: Int = 0) {
        ensureInitialized(context)
        runOnMain {
            val player = exoPlayer
            val isValidSurface = surface != null && surface.isValid
            if (!isValidSurface) {
                player?.clearVideoSurface()
                activeCarSurface = null
                _uiState.update {
                    it.copy(carSurfaceConnected = false)
                }
                return@runOnMain
            }

            val previousSurface = activeCarSurface
            activeCarSurface = surface

            if (player != null && surface != null) {
                val mode = _uiState.value.scaleMode
                val output = if (carOutputUnavailable) null else carOutput ?: CarVideoOutput(context.applicationContext).also { carOutput = it }
                val viaDisplay = output?.attachCarSurface(surface, width, height, dpi, player, mode, lastVideoAspectRatio) == true
                if (!viaDisplay) {
                    // Fallback: superficie diretta al decoder ("Originale" non può aggiungere bande nere e stira)
                    if (output != null) {
                        carOutputUnavailable = true
                        carOutput = null
                    }
                    if (previousSurface != null && previousSurface !== surface) {
                        player.clearVideoSurface(previousSurface)
                    }
                    player.setVideoSurface(surface)
                }
                applyScaleSettingsToPlayer(player, mode)

                // Il media nel player è quello dell'elemento corrente? Dopo stop() ExoPlayer conserva media ed errore
                // precedenti: ripreparare senza questo controllo riproporrebbe un elemento rimosso o già sostituito.
                val playerHoldsCurrentItem = activeLoadedItemId != null &&
                    activeLoadedItemId == _uiState.value.currentItem?.id
                if (resolveJob?.isActive == true) {
                    // Estrazione in corso: il video partirà appena pronto
                } else if (player.playerError != null && playerHoldsCurrentItem) {
                    // Errore (tipicamente decoder) mentre la Surface veniva ricreata: si riprepara lo stesso flusso
                    decoderRetryCount = 0
                    player.prepare()
                    player.play()
                } else if (player.playbackState == Player.STATE_IDLE && _uiState.value.playlist.isNotEmpty()) {
                    loadAndPlayIndexInternal(_uiState.value.currentIndex, showOverlay = true)
                } else if (player.playbackState == Player.STATE_READY && !player.isPlaying) {
                    player.play()
                }
            }
            _uiState.update {
                it.copy(
                    carSurfaceConnected = true,
                    carSurfaceWidth = width,
                    carSurfaceHeight = height
                )
            }
            triggerOverlayFor5Seconds()
        }
    }

    fun updateCarSurfaceSize(width: Int, height: Int) {
        runOnMain {
            _uiState.update {
                it.copy(carSurfaceWidth = width, carSurfaceHeight = height)
            }
        }
    }

    fun detachCarSurface(surface: Surface?) {
        runOnMain {
            val player = exoPlayer
            if (player != null) {
                val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                if (dur > 30_000L) {
                    saveCurrentItemProgressInternal(player.currentPosition.coerceAtLeast(0L), dur)
                }
            }
            if (activeCarSurface === surface || surface == null) {
                val output = carOutput
                if (output != null && output.isActive) {
                    // Il player continua a scrivere sulla SurfaceView: si stacca solo il target del VirtualDisplay
                    output.detachCarSurface()
                } else if (surface != null) {
                    player?.clearVideoSurface(surface)
                } else {
                    player?.clearVideoSurface()
                }
                activeCarSurface = null
                _uiState.update {
                    it.copy(carSurfaceConnected = false)
                }
            }
        }
    }

    /**
     * Chiamato quando la sessione Android Auto termina (auto spenta / cavo scollegato): salva la posizione e mette
     * in pausa, così il telefono non continua a scaricare lo stream e a riprodurre l'audio in background.
     */
    fun onCarSessionEnded() {
        runOnMain {
            Log.i("AAVideo", "Sessione Android Auto terminata: salvataggio posizione e pausa")
            saveOutgoingPlayerProgress()
            // Un'estrazione ancora in corso farebbe partire il video dopo la disconnessione
            resolveJob?.cancel()
            pendingSameItemRetry?.let { mainHandler.removeCallbacks(it) }
            pendingSameItemRetry = null
            exoPlayer?.pause()
            carOutput?.release(exoPlayer)
            carOutput = null
            exoPlayer?.clearVideoSurface()
            activeCarSurface = null
            _uiState.update {
                it.copy(
                    carSurfaceConnected = false,
                    isBuffering = false,
                    statusMessage = "⏸️ In pausa (Android Auto disconnesso)"
                )
            }
        }
    }

    /**
     * Salva la posizione corrente dell'elemento attivo nella playlist e nelle SharedPreferences.
     * Controlla [activeLoadedItemId] per non sovrascrivere mai il progresso di un nuovo elemento
     * mentre è ancora in fase di estrazione asincrona.
     */
    private fun saveCurrentItemProgressInternal(positionMs: Long, durationMs: Long) {
        val ctx = appContext ?: return
        if (durationMs <= 30_000L) return
        // Nelle dirette la "durata" è la finestra live: la posizione non ha senso come punto di ripresa
        if (exoPlayer?.isCurrentMediaItemLive == true) return
        val loadedId = activeLoadedItemId ?: return
        val state = _uiState.value
        val idx = state.currentIndex
        val currentList = state.playlist.toMutableList()
        val item = currentList.getOrNull(idx) ?: return
        if (item.id != loadedId) return

        val effectivePos = if (positionMs < 5_000L || (durationMs > 0 && positionMs.toDouble() / durationMs.toDouble() > 0.96)) {
            0L
        } else {
            positionMs
        }

        ScreensaverRepository.savePlaybackProgress(ctx, item.uriOrUrl, effectivePos, durationMs)
        if (item.lastPositionMs != effectivePos || item.durationMs != durationMs) {
            currentList[idx] = item.copy(
                lastPositionMs = effectivePos,
                durationMs = durationMs
            )
            ScreensaverRepository.savePlaylist(ctx, currentList)
            _uiState.update {
                it.copy(
                    playlist = currentList,
                    currentPositionMs = positionMs,
                    durationMs = durationMs
                )
            }
        }
    }

    /**
     * Quando la timeline reale è nota, verifica che la posizione di ripresa richiesta sia coerente con la durata
     * effettiva (o che non si tratti di una diretta). Se non lo è, riparte dall'inizio / dal punto live e azzera
     * il progresso salvato, così un valore errato non viene riproposto alla volta successiva.
     */
    private fun validatePendingResume(player: ExoPlayer) {
        val pending = pendingResume ?: return
        if (activeLoadedItemId != pending.itemId) {
            pendingResume = null
            return
        }
        // La timeline segnaposto (prima del prepare) non dice nulla né su durata né su live: si aspetta quella reale
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return
        if (timeline.getWindow(player.currentMediaItemIndex, Timeline.Window()).isPlaceholder) return
        val isLive = player.isCurrentMediaItemLive
        val actualDur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        if (!isLive && actualDur <= 0L) return

        pendingResume = null
        val sanitized = ResumePolicy.sanitize(pending.requestedMs, pending.savedDurationMs, actualDur, isLive)
        if (sanitized == pending.requestedMs) return

        Log.w(
            "AAVideo",
            "Ripresa scartata per item=${pending.itemId}: richiesta=${pending.requestedMs}ms, " +
                "durata salvata=${pending.savedDurationMs}ms, durata reale=${actualDur}ms, live=$isLive"
        )
        if (isLive) {
            player.seekToDefaultPosition()
            // Posizione salvata da una versione precedente (finestra live scambiata per durata): la si elimina
            clearProgressOfItem(pending.itemId)
        } else {
            player.seekTo(sanitized)
            saveCurrentItemProgressInternal(sanitized, actualDur)
        }
        _uiState.update { it.copy(currentPositionMs = sanitized, isLive = isLive) }
    }

    private fun clearProgressOfItem(itemId: String) {
        val ctx = appContext ?: return
        val list = _uiState.value.playlist.toMutableList()
        val idx = list.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val item = list[idx]
        ScreensaverRepository.clearPlaybackProgress(ctx, item.uriOrUrl)
        if (item.lastPositionMs != 0L || item.durationMs != 0L) {
            list[idx] = item.copy(lastPositionMs = 0L, durationMs = 0L)
            ScreensaverRepository.savePlaylist(ctx, list)
            _uiState.update { it.copy(playlist = list) }
        }
    }

    private fun saveOutgoingPlayerProgress() {
        val player = exoPlayer ?: return
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        if (dur > 30_000L) {
            saveCurrentItemProgressInternal(player.currentPosition.coerceAtLeast(0L), dur)
        }
    }

    /**
     * Sposta la riproduzione avanti o indietro di [deltaMs] millisecondi (es. -30_000L o +30_000L).
     */
    fun seekByMs(deltaMs: Long) {
        runOnMain {
            val player = exoPlayer ?: return@runOnMain
            val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: return@runOnMain
            if (dur <= 0L) return@runOnMain
            val newPos = (player.currentPosition + deltaMs).coerceIn(0L, (dur - 1000L).coerceAtLeast(0L))
            player.seekTo(newPos)
            val sign = if (deltaMs >= 0) "+${deltaMs / 1000}s" else "${deltaMs / 1000}s"
            _uiState.update {
                it.copy(
                    currentPositionMs = newPos,
                    durationMs = dur,
                    statusMessage = "⏩ Seek $sign → ${PlayerUiState.formatTimeMs(newPos)} / ${PlayerUiState.formatTimeMs(dur)}"
                )
            }
            saveCurrentItemProgressInternal(newPos, dur)
            triggerOverlayFor5Seconds()
        }
    }

    /**
     * Sposta la riproduzione alla posizione esatta [positionMs] (es. dalla SeekBar su telefono).
     */
    fun seekToMs(positionMs: Long) {
        runOnMain {
            val player = exoPlayer ?: return@runOnMain
            val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: return@runOnMain
            val clamped = positionMs.coerceIn(0L, dur)
            player.seekTo(clamped)
            _uiState.update {
                it.copy(
                    currentPositionMs = clamped,
                    durationMs = dur,
                    statusMessage = "⏱️ Posizione: ${PlayerUiState.formatTimeMs(clamped)} / ${PlayerUiState.formatTimeMs(dur)}"
                )
            }
            saveCurrentItemProgressInternal(clamped, dur)
            triggerOverlayFor5Seconds()
        }
    }

    /**
     * Riprova il caricamento e l'avvio del video corrente (utile in caso di errore di rete o token scaduto).
     */
    fun retryCurrentVideo() {
        runOnMain {
            val list = _uiState.value.playlist
            if (list.isEmpty()) return@runOnMain
            decoderRetryCount = 0
            httpTokenRetryCount = 0
            liveResyncCount = 0
            networkRetryCount = 0
            loadAndPlayIndexInternal(_uiState.value.currentIndex, showOverlay = true)
        }
    }

    /**
     * Passa immediatamente in riproduzione l'elemento indicato. Si usa l'id e non l'indice: la lista mostrata
     * all'utente può essere già cambiata (elementi aggiunti/rimossi) quando tocca la riga.
     */
    fun playItemById(context: Context, itemId: String) {
        ensureInitialized(context)
        runOnMain {
            val index = _uiState.value.playlist.indexOfFirst { it.id == itemId }
            if (index < 0) return@runOnMain
            saveOutgoingPlayerProgress()
            loadAndPlayIndexInternal(index, showOverlay = true)
        }
    }

    fun nextVideo() {
        runOnMain {
            val list = _uiState.value.playlist
            if (list.isEmpty()) return@runOnMain
            saveOutgoingPlayerProgress()
            val nextIdx = (_uiState.value.currentIndex + 1) % list.size
            loadAndPlayIndexInternal(nextIdx, showOverlay = true)
        }
    }

    fun previousVideo() {
        runOnMain {
            val list = _uiState.value.playlist
            if (list.isEmpty()) return@runOnMain
            saveOutgoingPlayerProgress()
            val prevIdx = if (_uiState.value.currentIndex - 1 < 0) list.lastIndex else _uiState.value.currentIndex - 1
            loadAndPlayIndexInternal(prevIdx, showOverlay = true)
        }
    }

    fun togglePlayPause() {
        runOnMain {
            val player = exoPlayer ?: return@runOnMain
            if (_uiState.value.errorMessage != null) {
                retryCurrentVideo()
                return@runOnMain
            }
            if (player.isPlaying) {
                player.pause()
                _uiState.update { it.copy(statusMessage = "⏸️ In pausa") }
            } else {
                if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                    loadAndPlayIndexInternal(_uiState.value.currentIndex, showOverlay = true)
                } else {
                    player.play()
                    _uiState.update { it.copy(statusMessage = "▶️ Riproduzione attiva (30/60 fps)") }
                }
            }
            triggerOverlayFor5Seconds()
        }
    }

    fun onCarSurfaceSingleTap() {
        runOnMain {
            if (_uiState.value.tapActionMode == TapActionMode.OVERLAY_AND_PLAY_PAUSE) {
                togglePlayPause()
            } else {
                triggerOverlayFor5Seconds()
            }
        }
    }

    fun triggerOverlayFor3Seconds() {
        triggerOverlayFor5Seconds()
    }

    fun triggerOverlayFor5Seconds() {
        val until = System.currentTimeMillis() + OVERLAY_DURATION_MS
        _uiState.update { it.copy(overlayVisibleUntilMs = until) }
    }

    fun toggleMute(context: Context? = appContext) {
        setMuted(context, !_uiState.value.isMuted)
    }

    fun setMuted(context: Context? = appContext, muted: Boolean) {
        runOnMain {
            val ctx = context?.applicationContext ?: appContext
            ctx?.let { ScreensaverRepository.saveIsMuted(it, muted) }
            exoPlayer?.let { applyAudioSettingsToPlayer(it, muted) }
            _uiState.update {
                it.copy(
                    isMuted = muted,
                    statusMessage = if (muted) {
                        "🔇 Salvaschermo Silenzioso attivo (Spotify/Radio DAB liberi)"
                    } else {
                        "🔊 Audio Video attivo"
                    }
                )
            }
            triggerOverlayFor5Seconds()
        }
    }

    private fun applyAudioSettingsToPlayer(player: ExoPlayer, isMuted: Boolean) {
        if (isMuted) {
            player.setAudioAttributes(mediaAudioAttrs, /* handleAudioFocus = */ false)
            player.volume = 0f
        } else {
            player.setAudioAttributes(mediaAudioAttrs, /* handleAudioFocus = */ true)
            player.volume = 1f
        }
    }

    fun toggleScaleMode(context: Context? = appContext) {
        val next = _uiState.value.scaleMode.next()
        setScaleMode(context, next)
    }

    fun setScaleMode(context: Context? = appContext, mode: VideoScaleMode) {
        runOnMain {
            val ctx = context?.applicationContext ?: appContext
            ctx?.let { ScreensaverRepository.saveScaleMode(it, mode) }
            exoPlayer?.let { applyScaleSettingsToPlayer(it, mode) }
            _uiState.update {
                it.copy(
                    scaleMode = mode,
                    statusMessage = "📐 Scala video impostata su ${mode.shortLabel}"
                )
            }
            triggerOverlayFor5Seconds()
        }
    }

    /**
     * Applica la modalità di scala.
     * Con l'uscita su VirtualDisplay le proporzioni le gestisce l'AspectRatioFrameLayout: la SurfaceView ha già
     * la forma giusta e il decoder la riempie. Nel fallback a superficie diretta il decoder sa solo stirare
     * o ritagliare, quindi "Originale" si comporta come "Stira".
     */
    private fun applyScaleSettingsToPlayer(player: ExoPlayer, mode: VideoScaleMode) {
        val output = carOutput
        if (output != null && output.isActive) {
            player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            output.setScaleMode(mode)
        } else {
            player.videoScalingMode = if (mode == VideoScaleMode.ZOOM_CROP) {
                C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            } else {
                C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }
        }
    }

    fun toggleRepeatMode(context: Context? = appContext) {
        val nextRepeatAll = !_uiState.value.repeatAll
        runOnMain {
            val ctx = context?.applicationContext ?: appContext
            ctx?.let { ScreensaverRepository.saveRepeatAll(it, nextRepeatAll) }
            _uiState.update {
                it.copy(
                    repeatAll = nextRepeatAll,
                    statusMessage = if (nextRepeatAll) "🔁 Ripetizione: Intera Playlist" else "🔁 Ripetizione: Singolo Video Continuo"
                )
            }
        }
    }

    fun toggleTapActionMode(context: Context? = appContext) {
        val next = if (_uiState.value.tapActionMode == TapActionMode.OVERLAY_AND_PLAY_PAUSE) {
            TapActionMode.OVERLAY_ONLY
        } else {
            TapActionMode.OVERLAY_AND_PLAY_PAUSE
        }
        runOnMain {
            val ctx = context?.applicationContext ?: appContext
            ctx?.let { ScreensaverRepository.saveTapActionMode(it, next) }
            _uiState.update { it.copy(tapActionMode = next) }
        }
    }

    fun addLocalVideoUris(context: Context, uris: List<Uri>, playImmediately: Boolean = true) {
        ensureInitialized(context)
        runOnMain {
            if (uris.isEmpty()) return@runOnMain
            saveOutgoingPlayerProgress()
            val ctx = context.applicationContext
            val currentList = _uiState.value.playlist.toMutableList()
            var firstAddedIdx = -1

            for (uri in uris) {
                val uriStr = uri.toString()
                val existingIdx = currentList.indexOfFirst { it.uriOrUrl == uriStr }
                if (existingIdx >= 0) {
                    if (firstAddedIdx < 0) firstAddedIdx = existingIdx
                    continue
                }
                val fileName = queryDisplayName(ctx, uri) ?: "Video Locale (${uri.lastPathSegment ?: "MP4"})"
                val savedProg = ScreensaverRepository.loadSavedProgress(ctx, uriStr)
                val item = ScreensaverItem(
                    id = UUID.randomUUID().toString(),
                    title = "📁 $fileName",
                    uriOrUrl = uriStr,
                    sourceType = SourceType.LOCAL_FILE,
                    lastPositionMs = savedProg?.positionMs ?: 0L,
                    durationMs = savedProg?.durationMs ?: 0L
                )
                currentList.add(item)
                if (firstAddedIdx < 0) firstAddedIdx = currentList.lastIndex
            }

            ScreensaverRepository.savePlaylist(ctx, currentList)
            _uiState.update { it.copy(playlist = currentList) }

            if (playImmediately && firstAddedIdx >= 0) {
                loadAndPlayIndexInternal(firstAddedIdx, showOverlay = true)
            }
        }
    }

    /**
     * Aggiunge uno stream HLS/MP4 catturato dal Browser Sniffer integrato (es. StreamingCommunity / VixCloud)
     * salvando anche gli header HTTP (Referer / Origin / Cookie) necessari per la riproduzione.
     */
    fun addCapturedWebStream(
        context: Context,
        streamUrl: String,
        title: String,
        headers: Map<String, String>,
        playImmediately: Boolean = true
    ) {
        ensureInitialized(context)
        runOnMain {
            saveOutgoingPlayerProgress()
            val ctx = context.applicationContext
            val cleanTitle = if (title.startsWith("🍿")) title else "🍿 $title"
            val currentList = _uiState.value.playlist.toMutableList()
            val savedProg = ScreensaverRepository.loadSavedProgress(ctx, streamUrl)

            val existingIdx = currentList.indexOfFirst { it.uriOrUrl == streamUrl }
            val targetIdx = if (existingIdx >= 0) {
                currentList[existingIdx] = currentList[existingIdx].copy(
                    title = cleanTitle,
                    httpHeaders = headers
                )
                existingIdx
            } else {
                val newItem = ScreensaverItem(
                    id = UUID.randomUUID().toString(),
                    title = cleanTitle,
                    uriOrUrl = streamUrl,
                    sourceType = SourceType.STREAMING_COMMUNITY,
                    httpHeaders = headers,
                    lastPositionMs = savedProg?.positionMs ?: 0L,
                    durationMs = savedProg?.durationMs ?: 0L
                )
                currentList.add(newItem)
                currentList.lastIndex
            }

            ScreensaverRepository.savePlaylist(ctx, currentList)
            _uiState.update { it.copy(playlist = currentList) }

            if (playImmediately) {
                loadAndPlayIndexInternal(targetIdx, showOverlay = true)
            }
        }
    }

    /**
     * Avvia immediatamente un elemento selezionato dal catalogo di Android Auto
     * (Streamed.pk, StreamingCommunity, YouTube) aggiungendolo/aggiornandolo in playlist.
     */
    fun playOrAddCatalogItem(
        context: Context,
        title: String,
        uriOrUrl: String,
        sourceType: SourceType,
        headers: Map<String, String> = emptyMap()
    ) {
        ensureInitialized(context)
        runOnMain {
            saveOutgoingPlayerProgress()
            val ctx = context.applicationContext
            val cleanUrl = uriOrUrl.trim()
            if (cleanUrl.isBlank()) return@runOnMain

            val savedProg = ScreensaverRepository.loadSavedProgress(ctx, cleanUrl)
            val currentList = _uiState.value.playlist.toMutableList()
            val existingIdx = currentList.indexOfFirst { it.uriOrUrl.equals(cleanUrl, ignoreCase = true) }
            val targetIdx = if (existingIdx >= 0) {
                val old = currentList[existingIdx]
                currentList[existingIdx] = old.copy(
                    title = title,
                    sourceType = sourceType,
                    httpHeaders = if (headers.isNotEmpty()) headers else old.httpHeaders,
                    lastPositionMs = if (old.lastPositionMs > 0L) old.lastPositionMs else (savedProg?.positionMs ?: 0L),
                    durationMs = if (old.durationMs > 0L) old.durationMs else (savedProg?.durationMs ?: 0L)
                )
                existingIdx
            } else {
                val newItem = ScreensaverItem(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    uriOrUrl = cleanUrl,
                    sourceType = sourceType,
                    httpHeaders = headers,
                    lastPositionMs = savedProg?.positionMs ?: 0L,
                    durationMs = savedProg?.durationMs ?: 0L
                )
                currentList.add(newItem)
                currentList.lastIndex
            }

            ScreensaverRepository.savePlaylist(ctx, currentList)
            _uiState.update { it.copy(playlist = currentList) }
            loadAndPlayIndexInternal(targetIdx, showOverlay = true)
        }
    }

    /**
     * Aggiunge un URL di rete (.m3u8, .mp4, stream live), un link YouTube, StreamingCommunity o Streamed.pk alla playlist.
     */
    fun addStreamOrYouTubeUrl(
        context: Context,
        rawInput: String,
        customTitle: String? = null,
        playImmediately: Boolean = true
    ) {
        ensureInitialized(context)
        runOnMain {
            val extractedUrl = YouTubeStreamExtractor.extractUrlFromSharedText(rawInput) ?: rawInput.trim()
            if (extractedUrl.isBlank()) return@runOnMain

            saveOutgoingPlayerProgress()
            val ctx = context.applicationContext
            val isYt = YouTubeStreamExtractor.isYouTubeUrl(extractedUrl)
            val isSc = StreamingCommunityExtractor.isStreamingCommunityOrVixUrl(extractedUrl)
            val isPpv = PpvClient.isPpvUrl(extractedUrl)
            val isPitsport = PitsportClient.isPitsportUrl(extractedUrl)

            val sourceType = when {
                isYt -> SourceType.YOUTUBE
                isSc -> SourceType.STREAMING_COMMUNITY
                isPpv -> SourceType.PPV
                isPitsport -> SourceType.PITSPORT
                else -> SourceType.DIRECT_STREAM
            }

            val defaultTitle = when {
                !customTitle.isNullOrBlank() -> customTitle.trim()
                isYt -> {
                    val vid = YouTubeStreamExtractor.extractVideoId(extractedUrl) ?: "Stream"
                    "▶️ YouTube ($vid)"
                }
                isSc -> {
                    val seg = runCatching { Uri.parse(extractedUrl).lastPathSegment }.getOrNull() ?: "Film/Serie"
                    "🍿 StreamingCommunity ($seg)"
                }
                isPpv -> {
                    val seg = runCatching { Uri.parse(extractedUrl).lastPathSegment }.getOrNull() ?: "Live"
                    "📺 PPV ($seg)"
                }
                isPitsport -> {
                    val seg = runCatching { Uri.parse(extractedUrl).lastPathSegment }.getOrNull() ?: "Live"
                    "⚽ Pitsport ($seg)"
                }
                extractedUrl.contains(".m3u8", ignoreCase = true) -> {
                    val host = runCatching { Uri.parse(extractedUrl).host }.getOrNull() ?: "HLS"
                    "📡 Stream HLS ($host)"
                }
                else -> {
                    val seg = runCatching { Uri.parse(extractedUrl).lastPathSegment }.getOrNull() ?: "Stream"
                    "🌐 Stream ($seg)"
                }
            }

            val savedProg = ScreensaverRepository.loadSavedProgress(ctx, extractedUrl)
            val currentList = _uiState.value.playlist.toMutableList()
            val existingIdx = currentList.indexOfFirst { it.uriOrUrl.equals(extractedUrl, ignoreCase = true) }
            val targetIdx = if (existingIdx >= 0) {
                if (!customTitle.isNullOrBlank()) {
                    currentList[existingIdx] = currentList[existingIdx].copy(title = customTitle.trim())
                }
                existingIdx
            } else {
                val newItem = ScreensaverItem(
                    id = UUID.randomUUID().toString(),
                    title = defaultTitle,
                    uriOrUrl = extractedUrl,
                    sourceType = sourceType,
                    lastPositionMs = savedProg?.positionMs ?: 0L,
                    durationMs = savedProg?.durationMs ?: 0L
                )
                currentList.add(newItem)
                currentList.lastIndex
            }

            ScreensaverRepository.savePlaylist(ctx, currentList)
            _uiState.update { it.copy(playlist = currentList) }

            if (playImmediately) {
                loadAndPlayIndexInternal(targetIdx, showOverlay = true)
            }
        }
    }

    fun removeItemById(context: Context, itemId: String) {
        ensureInitialized(context)
        runOnMain {
            val ctx = context.applicationContext
            val state = _uiState.value
            val removal = PlaylistOps.removeById(state.playlist, state.currentIndex, itemId) ?: return@runOnMain
            val wasActiveAndRunning = removal.removedActive &&
                (exoPlayer?.isPlaying == true || resolveJob?.isActive == true || state.carSurfaceConnected)
            if (removal.removedActive) {
                saveOutgoingPlayerProgress()
            }

            val newList = removal.playlist
            val newIndex = removal.currentIndex.coerceIn(0, (newList.size - 1).coerceAtLeast(0))

            ScreensaverRepository.savePlaylist(ctx, newList)
            ScreensaverRepository.saveLastIndex(ctx, newIndex)

            _uiState.update {
                it.copy(
                    playlist = newList,
                    currentIndex = newIndex,
                    statusMessage = "🗑️ Rimosso dalla playlist: ${removal.removedTitle}"
                )
            }
            if (removal.removedActive) {
                if (wasActiveAndRunning && newList.isNotEmpty()) {
                    loadAndPlayIndexInternal(newIndex, showOverlay = true)
                } else {
                    // Nessuna riproduzione in corso (es. gestione playlist dal telefono) o playlist ora vuota:
                    // si ferma il player senza avviare altro
                    resolveJob?.cancel()
                    loadGeneration++
                    pendingResume = null
                    activeLoadedItemId = null
                    exoPlayer?.stop()
                    _uiState.update {
                        it.copy(
                            resolvedTitle = newList.getOrNull(newIndex)?.title ?: "",
                            isBuffering = false,
                            errorMessage = null
                        )
                    }
                }
            }
        }
    }

    private fun loadAndPlayIndexInternal(
        index: Int,
        showOverlay: Boolean,
        overrideResumeMs: Long? = null
    ) {
        val ctx = appContext ?: return
        val list = _uiState.value.playlist
        val item = list.getOrNull(index) ?: return
        val player = exoPlayer ?: return

        Log.i("AAVideo", "loadAndPlayIndexInternal[$index]: title='${item.title}', type=${item.sourceType}, url=${item.uriOrUrl}")
        resolveJob?.cancel()
        loadGeneration++
        pendingSameItemRetry?.let { mainHandler.removeCallbacks(it) }
        pendingSameItemRetry = null
        pendingResume = null
        activeLoadedItemId = null
        player.stop()
        decoderRetryCount = 0
        liveResyncCount = 0
        networkRetryCount = 0
        currentYouTubeStrategy = null
        currentItemReachedReady = false
        if (overrideResumeMs == null) {
            // Caricamento "nuovo" (non un recupero automatico dopo errore): si riparte da zero
            httpTokenRetryCount = 0
            youtubeExcludedStrategies.clear()
        }
        ScreensaverRepository.saveLastIndex(ctx, index)

        val savedProg = ScreensaverRepository.loadSavedProgress(ctx, item.uriOrUrl)
        val initialResumeMs = overrideResumeMs?.takeIf { it > 5_000L }
            ?: item.lastPositionMs.takeIf { it > 5_000L }
            ?: (savedProg?.positionMs ?: 0L)
        val initialDurMs = item.durationMs.takeIf { it > 0L } ?: (savedProg?.durationMs ?: 0L)

        _uiState.update {
            it.copy(
                currentIndex = index,
                resolvedTitle = item.title,
                isBuffering = true,
                errorMessage = null,
                currentPositionMs = initialResumeMs,
                durationMs = initialDurMs,
                isLive = false,
                statusMessage = "⏳ Caricamento: ${item.title}"
            )
        }
        if (showOverlay) {
            triggerOverlayFor5Seconds()
        }

        // Caso 1: YouTube
        if (item.sourceType == SourceType.YOUTUBE || YouTubeStreamExtractor.isYouTubeUrl(item.uriOrUrl)) {
            resolveJob = scope.launch {
                _uiState.update {
                    it.copy(
                        isBuffering = true,
                        errorMessage = null,
                        statusMessage = "⏳ Estrazione flusso YouTube per '${item.title}'..."
                    )
                }
                val result = YouTubeStreamExtractor.resolveStream(item.uriOrUrl, youtubeExcludedStrategies.toSet())
                result.onSuccess { resolved ->
                    currentYouTubeStrategy = resolved.strategy
                    val updatedTitle = if (item.title.startsWith("▶️ YouTube (")) {
                        "▶️ ${resolved.title}"
                    } else {
                        item.title
                    }

                    if (updatedTitle != item.title) {
                        val updatedList = _uiState.value.playlist.toMutableList()
                        val idxInList = updatedList.indexOfFirst { it.id == item.id }
                        if (idxInList >= 0) {
                            updatedList[idxInList] = updatedList[idxInList].copy(title = updatedTitle)
                            ScreensaverRepository.savePlaylist(ctx, updatedList)
                            _uiState.update { it.copy(playlist = updatedList) }
                        }
                    }

                    _uiState.update {
                        it.copy(
                            resolvedTitle = "$updatedTitle [${resolved.resolutionLabel}]",
                            errorMessage = null,
                            statusMessage = "Avvio YouTube ${resolved.resolutionLabel}: ${resolved.title}"
                        )
                    }
                    if (showOverlay) {
                        triggerOverlayFor5Seconds()
                    }

                    val mergedHeaders = resolved.httpHeaders + item.httpHeaders
                    val dataSourceFactory = buildDataSourceFactory(ctx, mergedHeaders)
                    val mediaSource: MediaSource = when {
                        !resolved.hlsUrl.isNullOrBlank() -> {
                            val mediaItem = MediaItem.Builder()
                                .setUri(resolved.hlsUrl)
                                .setMimeType(MimeTypes.APPLICATION_M3U8)
                                .build()
                            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                        }
                        !resolved.videoUrl.isNullOrBlank() && !resolved.audioUrl.isNullOrBlank() -> {
                            val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                                .createMediaSource(MediaItem.fromUri(resolved.videoUrl))
                            val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                                .createMediaSource(MediaItem.fromUri(resolved.audioUrl))
                            MergingMediaSource(videoSource, audioSource)
                        }
                        !resolved.videoUrl.isNullOrBlank() -> {
                            ProgressiveMediaSource.Factory(dataSourceFactory)
                                .createMediaSource(MediaItem.fromUri(resolved.videoUrl))
                        }
                        else -> throw IllegalStateException("URL YouTube risolto vuoto")
                    }

                    // InnerTube fornisce già la durata reale: la ripresa si verifica prima ancora di avviare il player
                    val effectiveResumeMs = ResumePolicy.sanitize(
                        resumeMs = initialResumeMs,
                        savedDurationMs = initialDurMs,
                        actualDurationMs = resolved.durationMs,
                        isLive = false
                    )

                    startMediaSourceOnPlayer(player, mediaSource, effectiveResumeMs, item.id, initialDurMs)
                }.onFailure { err ->
                    val errText = "Errore YouTube: ${err.localizedMessage ?: "Impossibile estrarre lo stream"}"
                    _uiState.update {
                        it.copy(
                            isBuffering = false,
                            errorMessage = errText,
                            statusMessage = "⚠️ $errText"
                        )
                    }
                    triggerOverlayFor5Seconds()
                }
            }
            return
        }

        val isDirectM3u8OrPlaylist = item.uriOrUrl.contains(".m3u8", ignoreCase = true) ||
            item.uriOrUrl.contains("/playlist/", ignoreCase = true)

        // Caso 2: Pagina o stream StreamingCommunity / VixCloud / VidxGo / d2b.you
        if (item.sourceType == SourceType.STREAMING_COMMUNITY ||
            StreamingCommunityExtractor.isStreamingCommunityOrVixUrl(item.uriOrUrl)
        ) {
            resolveJob = scope.launch {
                _uiState.update {
                    it.copy(
                        isBuffering = true,
                        errorMessage = null,
                        statusMessage = "⏳ Estrazione stream StreamingCommunity / VixCloud..."
                    )
                }
                val result = StreamingCommunityExtractor.resolveStream(ctx, item.uriOrUrl, item.title)
                result.onSuccess { resolved ->
                    // Il titolo della pagina vidxgo è generico (spesso senza stagione/episodio): lo si usa solo
                    // per sostituire il titolo provvisorio degli URL incollati a mano
                    val hasPlaceholderTitle = item.title.startsWith("🍿 StreamingCommunity (")
                    val updatedTitle = if (hasPlaceholderTitle && resolved.title.isNotBlank()) {
                        resolved.title
                    } else {
                        item.title
                    }

                    val updatedList = _uiState.value.playlist.toMutableList()
                    val idxInList = updatedList.indexOfFirst { it.id == item.id }
                    if (idxInList >= 0) {
                        updatedList[idxInList] = updatedList[idxInList].copy(
                            title = updatedTitle,
                            httpHeaders = resolved.headers
                        )
                        ScreensaverRepository.savePlaylist(ctx, updatedList)
                        _uiState.update { it.copy(playlist = updatedList) }
                    }

                    _uiState.update {
                        it.copy(
                            resolvedTitle = "$updatedTitle [${resolved.resolutionLabel}]",
                            errorMessage = null,
                            statusMessage = "Avvio StreamingCommunity (${resolved.resolutionLabel})"
                        )
                    }
                    if (showOverlay) {
                        triggerOverlayFor5Seconds()
                    }

                    val dataSourceFactory = buildDataSourceFactory(ctx, resolved.headers)
                    val mediaItem = MediaItem.Builder()
                        .setUri(resolved.streamUrl)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()
                    val mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                    startMediaSourceOnPlayer(player, mediaSource, initialResumeMs, item.id, initialDurMs)
                }.onFailure { err ->
                    val errText = err.localizedMessage ?: "Impossibile estrarre lo stream StreamingCommunity"
                    _uiState.update {
                        it.copy(
                            isBuffering = false,
                            errorMessage = errText,
                            statusMessage = "⚠️ $errText"
                        )
                    }
                    triggerOverlayFor5Seconds()
                }
            }
            return
        }

        // Caso 2B: Pagina / Embed PPV non ancora risolta in .m3u8 diretto (o .m3u8 privo di header Referer/Origin)
        if ((item.sourceType == SourceType.PPV || PpvClient.isPpvUrl(item.uriOrUrl)) &&
            (!isDirectM3u8OrPlaylist || item.httpHeaders.isEmpty())
        ) {
            resolveJob = scope.launch {
                _uiState.update {
                    it.copy(
                        isBuffering = true,
                        errorMessage = null,
                        statusMessage = "⏳ Estrazione live PPV: ${item.title}..."
                    )
                }
                val result = PpvClient.resolveStream(ctx, item.uriOrUrl, item.title)
                result.onSuccess { resolved ->
                    val updatedTitle = item.title

                    _uiState.update {
                        it.copy(
                            resolvedTitle = "$updatedTitle [HLS Live]",
                            errorMessage = null,
                            statusMessage = "Avvio Live PPV: $updatedTitle"
                        )
                    }
                    if (showOverlay) {
                        triggerOverlayFor5Seconds()
                    }

                    val dataSourceFactory = buildDataSourceFactory(ctx, resolved.headers)
                    val mediaItem = MediaItem.Builder()
                        .setUri(resolved.streamUrl)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()
                    val mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                    startMediaSourceOnPlayer(player, mediaSource, 0L, item.id, 0L)
                }.onFailure { err ->
                    val errText = "Errore PPV: ${err.localizedMessage ?: "Stream non attivo al momento"}"
                    _uiState.update {
                        it.copy(
                            isBuffering = false,
                            errorMessage = errText,
                            statusMessage = "⚠️ $errText"
                        )
                    }
                    triggerOverlayFor5Seconds()
                }
            }
            return
        }

        // Caso 2C: Pagina / Embed Pitsport non ancora risolta in .m3u8 diretto (o .m3u8 privo di header Referer/Origin)
        if ((item.sourceType == SourceType.PITSPORT || PitsportClient.isPitsportUrl(item.uriOrUrl)) &&
            (!isDirectM3u8OrPlaylist || item.httpHeaders.isEmpty())
        ) {
            resolveJob = scope.launch {
                _uiState.update {
                    it.copy(
                        isBuffering = true,
                        errorMessage = null,
                        statusMessage = "⏳ Estrazione live Pitsport: ${item.title}..."
                    )
                }
                val result = PitsportClient.resolveStream(ctx, item.uriOrUrl, item.title)
                result.onSuccess { resolved ->
                    val updatedTitle = item.title

                    _uiState.update {
                        it.copy(
                            resolvedTitle = "$updatedTitle [HLS Live]",
                            errorMessage = null,
                            statusMessage = "Avvio Live Pitsport: $updatedTitle"
                        )
                    }
                    if (showOverlay) {
                        triggerOverlayFor5Seconds()
                    }

                    val dataSourceFactory = buildDataSourceFactory(ctx, resolved.headers)
                    val mediaItem = MediaItem.Builder()
                        .setUri(resolved.streamUrl)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()
                    val mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                    startMediaSourceOnPlayer(player, mediaSource, 0L, item.id, 0L)
                }.onFailure { err ->
                    val errText = "Errore Pitsport: ${err.localizedMessage ?: "Stream non attivo al momento"}"
                    _uiState.update {
                        it.copy(
                            isBuffering = false,
                            errorMessage = errText,
                            statusMessage = "⚠️ $errText"
                        )
                    }
                    triggerOverlayFor5Seconds()
                }
            }
            return
        }

        // Caso 3: File locale (content:// / file://) o Stream diretto (.m3u8 / VixCloud playlist / .mp4 / .mkv / .webm)
        val uri = Uri.parse(item.uriOrUrl)
        val lowerUrl = item.uriOrUrl.lowercase()
        val isHls = lowerUrl.contains(".m3u8") || lowerUrl.contains("/hls/") || lowerUrl.contains("/playlist/")

        val dataSourceFactory = buildDataSourceFactory(ctx, item.httpHeaders)
        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        if (isHls) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        val mediaSource: MediaSource = if (isHls) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItemBuilder.build())
        } else {
            DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItemBuilder.build())
        }

        val startPos = if (item.sourceType == SourceType.DEMO_STREAM ||
            item.sourceType == SourceType.PPV ||
            item.sourceType == SourceType.PITSPORT
        ) {
            0L
        } else {
            initialResumeMs
        }
        startMediaSourceOnPlayer(player, mediaSource, startPos, item.id, initialDurMs)
    }

    private fun startMediaSourceOnPlayer(
        player: ExoPlayer,
        mediaSource: MediaSource,
        resumePositionMs: Long,
        itemId: String,
        savedDurationMs: Long
    ) {
        activeLoadedItemId = itemId
        pendingResume = if (resumePositionMs > ResumePolicy.MIN_RESUME_MS) {
            PendingResume(itemId, resumePositionMs, savedDurationMs)
        } else {
            null
        }
        // Con la superficie diretta (fallback) si verifica che sia ancora valida prima di prepare() per evitare
        // Broken pipe (-32). Con il VirtualDisplay il player scrive sempre sulla SurfaceView, che resta valida.
        val surf = if (carOutput?.isActive == true) null else activeCarSurface
        if (surf != null && !surf.isValid) {
            Log.w("AAVideo", "startMediaSourceOnPlayer: activeCarSurface non più valido, scollegamento preventivo")
            player.clearVideoSurface()
            activeCarSurface = null
            _uiState.update { it.copy(carSurfaceConnected = false) }
        } else if (surf != null && surf.isValid) {
            player.setVideoSurface(surf)
        }

        applyScaleSettingsToPlayer(player, _uiState.value.scaleMode)
        applyAudioSettingsToPlayer(player, _uiState.value.isMuted)
        itemStartedAtMs = System.currentTimeMillis()

        if (resumePositionMs > 5_000L) {
            player.setMediaSource(mediaSource, resumePositionMs)
            _uiState.update {
                it.copy(
                    currentPositionMs = resumePositionMs,
                    statusMessage = "▶️ Ripreso da ${PlayerUiState.formatTimeMs(resumePositionMs)}"
                )
            }
            triggerOverlayFor5Seconds()
        } else {
            player.setMediaSource(mediaSource)
        }

        player.prepare()
        player.play()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (col >= 0) cursor.getString(col) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
