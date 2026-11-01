package com.casadei.aavideoscreensaver.car

import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.casadei.aavideoscreensaver.R
import com.casadei.aavideoscreensaver.model.PlayerUiState
import com.casadei.aavideoscreensaver.player.VideoPlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Schermata Full-Screen su Android Auto (Zero Header, 30/60 fps):
 *
 * - Collega ExoPlayer direttamente a SurfaceContainer.surface in onSurfaceAvailable.
 * - Mantiene il SurfaceCallback registrato durante la navigazione nel menu "Sfoglia" per evitare
 *   errori "Broken pipe (-32)" sul decoder hardware al ritorno alla schermata video.
 * - Mostra una barra di avanzamento a scomparsa (5s) con ripresa automatica (Resume) e pulsanti ⏪ -30s / ⏩ +30s.
 * - Mantiene visibile un avviso chiaro a schermo durante il caricamento/estrazione o in caso di errore
 *   (evitando lo schermo nero muto) con pulsante rapido Riprova.
 */
class VideoSurfaceScreen(carContext: CarContext) : Screen(carContext) {

    private var stateCollectJob: Job? = null
    private var overlayHideJob: Job? = null
    private var scrollAccumX = 0f
    private var lastTrackSwitchMs = 0L
    private var lastSurfaceContainer: SurfaceContainer? = null
    private var lastShownErrorToast: String? = null

    private var lastTemplateSignature: String = ""

    private fun switchNextWithDebounce() {
        val now = System.currentTimeMillis()
        if (now - lastTrackSwitchMs < 380L) return
        lastTrackSwitchMs = now
        scrollAccumX = 0f
        VideoPlayerController.nextVideo()
    }

    private fun switchPrevWithDebounce() {
        val now = System.currentTimeMillis()
        if (now - lastTrackSwitchMs < 380L) return
        lastTrackSwitchMs = now
        scrollAccumX = 0f
        VideoPlayerController.previousVideo()
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            lastSurfaceContainer = container
            VideoPlayerController.attachCarSurface(
                context = carContext,
                surface = container.surface,
                width = container.width,
                height = container.height,
                dpi = container.dpi
            )
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            VideoPlayerController.updateCarSurfaceSize(
                width = visibleArea.width(),
                height = visibleArea.height()
            )
        }

        override fun onStableAreaChanged(stableArea: Rect) {
            // Nessun ridisegno manuale richiesto: ExoPlayer renderizza a 30/60 fps direttamente sul Surface
        }

        override fun onClick(x: Float, y: Float) {
            VideoPlayerController.onCarSurfaceSingleTap()
        }

        override fun onFling(velocityX: Float, velocityY: Float) {
            if (abs(velocityX) > abs(velocityY) && abs(velocityX) > 180f) {
                if (velocityX < 0f) {
                    // Swipe verso sinistra -> video successivo
                    switchNextWithDebounce()
                } else {
                    // Swipe verso destra -> video precedente
                    switchPrevWithDebounce()
                }
            }
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            if (abs(distanceX) > abs(distanceY)) {
                scrollAccumX += distanceX
                if (scrollAccumX > 90f) {
                    switchNextWithDebounce()
                } else if (scrollAccumX < -90f) {
                    switchPrevWithDebounce()
                }
            }
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            // Android Auto gestisce una sola superficie: quella memorizzata non va più riagganciata in onStart
            // (anche se l'oggetto Surface ricevuto qui è un'istanza Java diversa). Riagganciare una superficie
            // morta fa fallire MediaCodec.setOutputSurface (log 24-10: "No such device -19").
            lastSurfaceContainer = null
            VideoPlayerController.detachCarSurface(container.surface)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                VideoPlayerController.ensureInitialized(carContext)
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
            }

            override fun onStart(owner: LifecycleOwner) {
                VideoPlayerController.ensureInitialized(carContext)
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)

                // Se il Surface è già disponibile e valido quando si torna da "Sfoglia", riaggancialo subito
                lastSurfaceContainer?.let { container ->
                    val s = container.surface
                    if (s != null && s.isValid) {
                        VideoPlayerController.attachCarSurface(
                            context = carContext,
                            surface = s,
                            width = container.width,
                            height = container.height,
                            dpi = container.dpi
                        )
                    }
                }

                stateCollectJob = lifecycleScope.launch {
                    VideoPlayerController.uiState.collectLatest { state ->
                        val now = System.currentTimeMillis()
                        val overlayActive = state.overlayVisibleUntilMs > now
                        val persistentBanner = state.isBuffering || state.errorMessage != null || state.playlist.isEmpty()

                        // Se c'è un nuovo errore, mostra anche un CarToast immediato
                        val err = state.errorMessage
                        if (err != null && err != lastShownErrorToast) {
                            lastShownErrorToast = err
                            runCatching {
                                CarToast.makeText(carContext, "⚠️ $err", CarToast.LENGTH_LONG).show()
                            }
                        } else if (err == null) {
                            lastShownErrorToast = null
                        }

                        // Quando l'overlay è visibile, aggiorna la firma ogni secondo per far scorrere la barra di avanzamento
                        val posSecBucket = if (overlayActive || persistentBanner) {
                            state.currentPositionMs / 1000L
                        } else {
                            -1L
                        }
                        val durSecBucket = state.durationMs / 1000L

                        val sig = buildString {
                            append(state.currentIndex).append('|')
                            append(state.playlist.isEmpty()).append('|')
                            append(state.resolvedTitle).append('|')
                            append(state.isMuted).append('|')
                            append(state.scaleMode).append('|')
                            append(state.isPlaying).append('|')
                            append(state.isBuffering).append('|')
                            append(state.errorMessage ?: "").append('|')
                            append( if ( persistentBanner ) state.statusMessage else "" ).append('|')
                            append(overlayActive).append('|')
                            append(posSecBucket).append('|')
                            append(durSecBucket)
                        }

                        if (sig != lastTemplateSignature) {
                            lastTemplateSignature = sig
                            invalidate()
                        }

                        if (overlayActive && !persistentBanner) {
                            val remainingMs = (state.overlayVisibleUntilMs - now).coerceAtLeast(50L)
                            overlayHideJob?.cancel()
                            overlayHideJob = lifecycleScope.launch {
                                delay(remainingMs + 50L)
                                lastTemplateSignature = ""
                                invalidate()
                            }
                        }
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                stateCollectJob?.cancel()
                overlayHideJob?.cancel()
                // Scollega il Surface da ExoPlayer quando si apre il menu Sfoglia (ListTemplate)
                // così il decoder non va in Broken pipe (-32), ma lascia registrato surfaceCallback
                VideoPlayerController.detachCarSurface(null)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                runCatching {
                    carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val state = VideoPlayerController.uiState.value
        val now = System.currentTimeMillis()
        val hasError = state.errorMessage != null
        val playlistEmpty = state.playlist.isEmpty()
        val showOverlayCard = playlistEmpty || hasError || state.isBuffering || (state.overlayVisibleUntilMs > now)

        val browseIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_menu_browse)).build()
        val volumeIconRes = if (state.isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        val volumeIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, volumeIconRes)).build()
        val scaleIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_aspect_ratio)).build()
        val playPauseIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_play_pause)).build()
        val nextIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_skip_next)).build()
        val replay30Icon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_replay_30)).build()
        val forward30Icon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_forward_30)).build()
        val refreshIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_refresh)).build()

        // ActionStrip in alto (max 4 azioni): Sfoglia, Riprova (se in errore) o Audio/Mute, Scala FIT/ZOOM, Video Successivo
        val actionStripBuilder = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(browseIcon)
                    .setTitle("Sfoglia")
                    .setOnClickListener {
                        // Protezione dal doppio tocco: un secondo push consumerebbe quota template
                        if (screenManager.top === this) {
                            screenManager.push(CarBrowseHubScreen(carContext))
                        }
                    }
                    .build()
            )

        if (hasError) {
            actionStripBuilder.addAction(
                Action.Builder()
                    .setIcon(refreshIcon)
                    .setOnClickListener {
                        CarToast.makeText(carContext, "🔄 Nuovo tentativo in corso...", CarToast.LENGTH_SHORT).show()
                        VideoPlayerController.retryCurrentVideo()
                    }
                    .build()
            )
        } else {
            actionStripBuilder.addAction(
                Action.Builder()
                    .setIcon(volumeIcon)
                    .setOnClickListener {
                        VideoPlayerController.toggleMute(carContext)
                    }
                    .build()
            )
        }

        val actionStrip = actionStripBuilder
            .addAction(
                Action.Builder()
                    .setIcon(scaleIcon)
                    .setOnClickListener {
                        VideoPlayerController.toggleScaleMode(carContext)
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(nextIcon)
                    .setOnClickListener {
                        VideoPlayerController.nextVideo()
                    }
                    .build()
            )
            .build()

        // MapActionStrip laterale (max 4 icone): PAN, Seek -30s, Play/Pausa (o Riprova), Seek +30s
        val mapActionStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(
                Action.Builder()
                    .setIcon(replay30Icon)
                    .setOnClickListener {
                        if (state.isSeekable) {
                            VideoPlayerController.seekByMs(-30_000L)
                        } else {
                            CarToast.makeText(carContext, "Flusso in diretta (non riavvolgibile)", CarToast.LENGTH_SHORT).show()
                        }
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(if (hasError) refreshIcon else playPauseIcon)
                    .setOnClickListener {
                        if (hasError) {
                            VideoPlayerController.retryCurrentVideo()
                        } else {
                            VideoPlayerController.togglePlayPause()
                        }
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(forward30Icon)
                    .setOnClickListener {
                        if (state.isSeekable) {
                            VideoPlayerController.seekByMs(30_000L)
                        } else {
                            CarToast.makeText(carContext, "Flusso in diretta (non avanzabile)", CarToast.LENGTH_SHORT).show()
                        }
                    }
                    .build()
            )
            .build()

        val builder = NavigationTemplate.Builder()
            .setBackgroundColor(CarColor.SECONDARY)
            .setActionStrip(actionStrip)
            .setMapActionStrip(mapActionStrip)
            .setPanModeListener { _ ->
                // Mantiene attivi i gesti touch/swipe su SurfaceCallback a tutto schermo
            }

        if (showOverlayCard) {
            val currentItem = state.currentItem
            val rawTitle = state.resolvedTitle.ifBlank { currentItem?.title ?: "AA Video" }
            val audioBadge = if (state.isMuted) "🔇 Silenzioso" else "🔊 Audio ON"
            val sourceBadge = currentItem?.sourceType?.badge ?: "STREAM"
            val posBadge = if (state.playlist.isNotEmpty()) {
                "${state.currentIndex + 1}/${state.playlist.size}"
            } else {
                "0/0"
            }

            val messageInfo = when {
                playlistEmpty -> {
                    // Niente stream demo: con la playlist vuota si spiega come scegliere un contenuto
                    MessageInfo.Builder("📂 Playlist vuota")
                        .setText("Tocca lo schermo, poi Sfoglia")
                        .build()
                }
                hasError -> {
                    MessageInfo.Builder("⚠️ $rawTitle")
                        .setText("${state.errorMessage} • Tocca 🔄 in alto per riprovare o fai Swipe")
                        .build()
                }
                state.isBuffering -> {
                    val progressHint = if (state.currentPositionMs > 5_000L && state.durationMs > 0L) {
                        " • Ripresa da ${PlayerUiState.formatTimeMs(state.currentPositionMs)}"
                    } else {
                        ""
                    }
                    MessageInfo.Builder("⏳ $rawTitle")
                        .setText("${state.statusMessage}$progressHint")
                        .build()
                }
                else -> {
                    val playPrefix = if (state.isPlaying) "▶️" else "⏸️"
                    val subtitleText = if (state.isSeekable) {
                        val progressBar = PlayerUiState.buildTextProgressBar(
                            posMs = state.currentPositionMs,
                            durMs = state.durationMs,
                            segments = 10
                        )
                        "$progressBar • $audioBadge • $posBadge"
                    } else {
                        "[$sourceBadge LIVE] • ${state.scaleMode.shortLabel} • $audioBadge • $posBadge"
                    }
                    MessageInfo.Builder("$playPrefix $rawTitle")
                        .setText(subtitleText)
                        .build()
                }
            }
            builder.setNavigationInfo(messageInfo)
        }

        return builder.build()
    }
}
