package com.casadei.aavideoscreensaver.phone

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.casadei.aavideoscreensaver.extractor.YouTubeStreamExtractor
import com.casadei.aavideoscreensaver.model.PlayerUiState
import com.casadei.aavideoscreensaver.model.VideoScaleMode
import com.casadei.aavideoscreensaver.player.ScreensaverRepository
import com.casadei.aavideoscreensaver.player.VideoPlayerController
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Activity Principale su Smartphone (AA Video Screensaver):
 *
 * - Tema scuro automotive coordinato con il display 10.25" widescreen (100% Full-Screen senza Google Maps).
 * - Aggiunta file video locali (.mp4, .mkv, .webm) tramite ACTION_OPEN_DOCUMENT con takePersistableUriPermission.
 * - Integrazione StreamingCommunity (sia link diretti sia tramite Browser Integrato con AdBlock e cattura automatica VixCloud 1080p).
 * - Inserimento o ricezione via "Condividi -> AA Video Screensaver" (Intent.ACTION_SEND) di stream .m3u8/.mp4 e link YouTube.
 * - Controllo immediato su Android Auto: tap sulla playlist per riprodurre live, toggle Mute/Audio e scala FIT vs ZOOM/CROP.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var aaConnectionBadge: TextView
    private lateinit var nowPlayingTitleView: TextView
    private lateinit var nowPlayingStatusView: TextView
    private lateinit var progressContainer: LinearLayout
    private lateinit var progressSeekBar: SeekBar
    private lateinit var progressTimeLabel: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var btnMuteToggle: Button
    private lateinit var btnScaleToggle: Button
    private lateinit var btnRepeatToggle: Button
    private lateinit var btnTapModeToggle: Button
    private lateinit var customTitleInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var playlistContainer: LinearLayout

    private var isUserDraggingSeek: Boolean = false
    private var lastRenderedPlaylistHash: Int = 0

    private val pickLocalVideoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val selectedUris = mutableListOf<Uri>()

            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i)?.uri?.let { selectedUris.add(it) }
                }
            }
            if (selectedUris.isEmpty()) {
                data.data?.let { selectedUris.add(it) }
            }

            if (selectedUris.isNotEmpty()) {
                for (uri in selectedUris) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                }
                VideoPlayerController.addLocalVideoUris(
                    context = this,
                    uris = selectedUris,
                    playImmediately = true
                )
                Toast.makeText(
                    this,
                    "Aggiunti ${selectedUris.size} video locali alla playlist",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VideoPlayerController.ensureInitialized(this)

        buildDarkUi()
        observePlayerState()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                if (!sharedText.isNullOrBlank()) {
                    val cleanUrl = YouTubeStreamExtractor.extractUrlFromSharedText(sharedText) ?: sharedText.trim()
                    urlInput.setText(cleanUrl)
                    if (!sharedSubject.isNullOrBlank() && customTitleInput.text.isNullOrBlank()) {
                        customTitleInput.setText(sharedSubject)
                    }
                    VideoPlayerController.addStreamOrYouTubeUrl(
                        context = this,
                        rawInput = cleanUrl,
                        customTitle = sharedSubject?.takeIf { it.isNotBlank() },
                        playImmediately = true
                    )
                    Toast.makeText(
                        this,
                        "Link condiviso inviato in riproduzione su Android Auto!",
                        Toast.LENGTH_LONG
                    ).show()
                    intent.action = Intent.ACTION_MAIN
                }
            }
            Intent.ACTION_VIEW -> {
                val dataUri = intent.data
                if (dataUri != null) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            dataUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                    VideoPlayerController.addLocalVideoUris(
                        context = this,
                        uris = listOf(dataUri),
                        playImmediately = true
                    )
                    intent.action = Intent.ACTION_MAIN
                }
            }
        }
    }

    private fun buildDarkUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#090C12"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 44, 36, 56)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(root)

        // Header
        val titleView = TextView(this).apply {
            text = "AA Video"
            textSize = 23f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38E0B2"))
        }
        root.addView(titleView)

        val subHeaderView = TextView(this).apply {
            text = "100% Full-Screen 10.25\" (Senza Google Maps) • Made with ❤️ by Cristiano Casadei"
            textSize = 12.5f
            setTextColor(Color.parseColor("#8B9BB4"))
            setPadding(0, 4, 0, 18)
        }
        root.addView(subHeaderView)

        // 1. Card Stato Live & Controlli Rapidi Riproduzione
        val nowPlayingCard = createCardContainer("#121824", "#223049")
        root.addView(nowPlayingCard, marginLayoutParams(bottom = 24))

        aaConnectionBadge = TextView(this).apply {
            text = "⚪ Android Auto: In attesa di connessione allo schermo auto"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#93C5FD"))
            setPadding(0, 0, 0, 8)
        }
        nowPlayingCard.addView(aaConnectionBadge)

        val fullScreenTip = TextView(this).apply {
            text = "💡 Sostituisce Google Maps: toccando l'icona di AA Video nella barra di Android Auto si espande al 100% dello schermo 10.25\" chiudendo Maps."
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 10)
        }
        nowPlayingCard.addView(fullScreenTip)

        nowPlayingTitleView = TextView(this).apply {
            text = "Nessun video selezionato"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 2, 0, 6)
        }
        nowPlayingCard.addView(nowPlayingTitleView)

        nowPlayingStatusView = TextView(this).apply {
            text = "Stato: Pronto"
            textSize = 13.5f
            setTextColor(Color.parseColor("#A5B4FC"))
            setPadding(0, 0, 0, 12)
        }
        nowPlayingCard.addView(nowPlayingStatusView)

        // Barra di Avanzamento e Controlli Seek (-30s / +30s)
        progressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 14)
        }

        progressTimeLabel = TextView(this).apply {
            text = "⏱️ 0:00 / 0:00 (Ripresa automatica attiva)"
            textSize = 12.5f
            setTextColor(Color.parseColor("#38E0B2"))
            setPadding(4, 0, 4, 6)
        }
        progressContainer.addView(progressTimeLabel)

        progressSeekBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = VideoPlayerController.uiState.value.durationMs
                        if (dur > 0L) {
                            val targetMs = (dur * progress) / 1000L
                            progressTimeLabel.text = "⏱️ Vai a: ${PlayerUiState.formatTimeMs(targetMs)} / ${PlayerUiState.formatTimeMs(dur)}"
                        }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isUserDraggingSeek = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isUserDraggingSeek = false
                    val dur = VideoPlayerController.uiState.value.durationMs
                    val prog = seekBar?.progress ?: 0
                    if (dur > 0L) {
                        val targetMs = (dur * prog) / 1000L
                        VideoPlayerController.seekToMs(targetMs)
                    }
                }
            })
        }
        progressContainer.addView(progressSeekBar, marginLayoutParams(bottom = 8))

        val seekButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        val btnRewind30 = createStyledButton("⏪ -30s", "#1E293B") {
            VideoPlayerController.seekByMs(-30_000L)
        }
        val btnRetryStream = createStyledButton("🔄 Riprova", "#334155") {
            VideoPlayerController.retryCurrentVideo()
        }
        val btnForward30 = createStyledButton("⏩ +30s", "#1E293B") {
            VideoPlayerController.seekByMs(30_000L)
        }
        seekButtonsRow.addView(btnRewind30, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10 })
        seekButtonsRow.addView(btnRetryStream, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10 })
        seekButtonsRow.addView(btnForward30, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        progressContainer.addView(seekButtonsRow)

        nowPlayingCard.addView(progressContainer)

        val transportRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        val btnPrev = createStyledButton("⏮️ Prec.", "#1E293B") {
            VideoPlayerController.previousVideo()
        }
        btnPlayPause = createStyledButton("⏯️ Play / Pausa", "#2563EB") {
            VideoPlayerController.togglePlayPause()
        }
        val btnNext = createStyledButton("⏭️ Succ.", "#1E293B") {
            VideoPlayerController.nextVideo()
        }

        transportRow.addView(btnPrev, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10 })
        transportRow.addView(btnPlayPause, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10 })
        transportRow.addView(btnNext, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nowPlayingCard.addView(transportRow)

        // 2. Card Impostazioni Audio & Scala Video 10.25"
        val settingsCard = createCardContainer("#121824", "#1F2937")
        root.addView(settingsCard, marginLayoutParams(bottom = 24))

        val settingsTitle = createSectionTitle("🎛️ Audio Intelligente & Formato 10.25\" Widescreen")
        settingsCard.addView(settingsTitle)

        btnMuteToggle = createStyledButton("🔇 Salvaschermo Silenzioso (Spotify/Radio liberi)", "#0F766E") {
            VideoPlayerController.toggleMute(this)
        }
        settingsCard.addView(btnMuteToggle, marginLayoutParams(top = 12, bottom = 10))

        btnScaleToggle = createStyledButton("📐 Scala Video", "#1D4ED8") {
            VideoPlayerController.toggleScaleMode(this)
        }
        settingsCard.addView(btnScaleToggle, marginLayoutParams(bottom = 10))

        val secondaryTogglesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        btnRepeatToggle = createStyledButton("🔁 Loop: Singolo", "#1E293B") {
            VideoPlayerController.toggleRepeatMode(this)
        }
        btnTapModeToggle = createStyledButton("👆 Tap: Pausa+Info", "#1E293B") {
            VideoPlayerController.toggleTapActionMode(this)
        }
        secondaryTogglesRow.addView(btnRepeatToggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10 })
        secondaryTogglesRow.addView(btnTapModeToggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        settingsCard.addView(secondaryTogglesRow)

        // 3. Card Aggiunta Sorgenti (StreamingCommunity, Streamed.pk, File Locali, Stream .m3u8/.mp4, YouTube)
        val addSourcesCard = createCardContainer("#121824", "#1F2937")
        root.addView(addSourcesCard, marginLayoutParams(bottom = 24))

        addSourcesCard.addView(createSectionTitle("➕ Sorgenti: Video Locali, URL e Cataloghi in Auto"))

        val inCarNotice = TextView(this).apply {
            text = "🚗 Guida Sicura: Su Android Auto tocca il pulsante 'Sfoglia' in alto a destra per scegliere direttamente dallo schermo dell'auto i video di YouTube, i Film/Serie TV di StreamingCommunity e le dirette sportive di Streamed.pk (anche con ricerca vocale 🎤) senza toccare il telefono!"
            textSize = 12.5f
            setTextColor(Color.parseColor("#38E0B2"))
            background = createRoundedBackground("#0F292A", "#14B8A6", 14f)
            setPadding(24, 18, 24, 18)
        }
        addSourcesCard.addView(inCarNotice, marginLayoutParams(top = 10, bottom = 12))

        val btnOpenStreamingCommunity = createStyledButton(
            "🍿 Apri Browser StreamingCommunity (AdBlock + Cattura Stream 1080p)",
            "#7C3AED"
        ) {
            val prefill = urlInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            StreamingCommunityBrowserDialog.show(this, prefill)
        }
        addSourcesCard.addView(btnOpenStreamingCommunity, marginLayoutParams(top = 4, bottom = 10))

        val btnPickLocal = createStyledButton(
            "📂 Scegli File Video dal Telefono (.mp4, .mkv, .webm)",
            "#0284C7"
        ) {
            openLocalVideoPicker()
        }
        addSourcesCard.addView(btnPickLocal, marginLayoutParams(bottom = 18))

        val urlHelpText = TextView(this).apply {
            text = "Oppure incolla un URL diretto (.m3u8 HLS, .mp4), un link YouTube o un link StreamingCommunity (anche da 'Condividi -> AA Video Screensaver'):"
            textSize = 12.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 10)
        }
        addSourcesCard.addView(urlHelpText)

        customTitleInput = EditText(this).apply {
            hint = "Nome personalizzato (es. Film / Camino 4K - opzionale)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            background = createRoundedBackground("#1B2434", "#334155", 16f)
            setPadding(28, 22, 28, 22)
        }
        addSourcesCard.addView(customTitleInput, marginLayoutParams(bottom = 10))

        val urlInputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        urlInput = EditText(this).apply {
            hint = "https://... (YouTube, StreamingCommunity, .m3u8, .mp4)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            background = createRoundedBackground("#1B2434", "#334155", 16f)
            setPadding(28, 22, 28, 22)
        }
        val btnPaste = createStyledButton("📋 Incolla", "#334155") {
            pasteFromClipboard()
        }

        urlInputRow.addView(urlInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10 })
        urlInputRow.addView(btnPaste, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addSourcesCard.addView(urlInputRow, marginLayoutParams(bottom = 12))

        val btnAddUrl = createStyledButton(
            "🚀 Salva in Playlist e Riproduci su Android Auto",
            "#10B981"
        ) {
            val rawUrl = urlInput.text?.toString()?.trim() ?: ""
            val customName = customTitleInput.text?.toString()?.trim()
            if (rawUrl.isBlank()) {
                Toast.makeText(this, "Inserisci o incolla prima un URL o link YouTube/StreamingCommunity", Toast.LENGTH_SHORT).show()
            } else {
                VideoPlayerController.addStreamOrYouTubeUrl(
                    context = this,
                    rawInput = rawUrl,
                    customTitle = customName,
                    playImmediately = true
                )
                urlInput.setText("")
                customTitleInput.setText("")
                Toast.makeText(this, "Stream aggiunto e avviato!", Toast.LENGTH_SHORT).show()
            }
        }
        addSourcesCard.addView(btnAddUrl)

        // 4. Card Playlist Salvaschermo (Tocca per riprodurre subito su Android Auto)
        val playlistCard = createCardContainer("#121824", "#1F2937")
        root.addView(playlistCard, marginLayoutParams(bottom = 24))

        playlistCard.addView(createSectionTitle("🎬 Playlist Salvaschermo (Tocca per mandare Live in Auto)"))

        val gestureHint = TextView(this).apply {
            text = "Su Android Auto: Swipe Destra/Sinistra per cambiare video • Tap singolo per Pausa/Titolo 3s"
            textSize = 12f
            setTextColor(Color.parseColor("#38E0B2"))
            setPadding(0, 6, 0, 14)
        }
        playlistCard.addView(gestureHint)

        playlistContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        playlistCard.addView(playlistContainer)

        setContentView(scroll)
    }

    private fun observePlayerState() {
        lifecycleScope.launch {
            VideoPlayerController.uiState.collectLatest { state ->
                updateUiFromState(state)
            }
        }
    }

    private fun updateUiFromState(state: PlayerUiState) {
        if (state.carSurfaceConnected) {
            val dim = if (state.carSurfaceWidth > 0 && state.carSurfaceHeight > 0) {
                " (${state.carSurfaceWidth}×${state.carSurfaceHeight})"
            } else {
                ""
            }
            aaConnectionBadge.text = "🟢 Display Auto Connesso Full-Screen$dim (Maps sostituito)"
            aaConnectionBadge.setTextColor(Color.parseColor("#38E0B2"))
        } else {
            aaConnectionBadge.text = "⚪ In attesa di apertura su Android Auto (Player pronto)"
            aaConnectionBadge.setTextColor(Color.parseColor("#93C5FD"))
        }

        val currentItem = state.currentItem
        val displayTitle = state.resolvedTitle.ifBlank { currentItem?.title ?: "Nessun video" }
        nowPlayingTitleView.text = displayTitle

        val resInfo = if (state.videoWidth > 0 && state.videoHeight > 0) {
            " • Risoluzione ${state.videoWidth}×${state.videoHeight}"
        } else {
            ""
        }
        nowPlayingStatusView.text = "${state.statusMessage}$resInfo"
        if (state.errorMessage != null) {
            nowPlayingStatusView.setTextColor(Color.parseColor("#FCA5A5"))
        } else if (state.isBuffering) {
            nowPlayingStatusView.setTextColor(Color.parseColor("#FDE047"))
        } else {
            nowPlayingStatusView.setTextColor(Color.parseColor("#A5B4FC"))
        }

        if (state.isSeekable) {
            progressSeekBar.isEnabled = true
            if (!isUserDraggingSeek && state.durationMs > 0L) {
                val permille = ((state.currentPositionMs * 1000L) / state.durationMs).toInt().coerceIn(0, 1000)
                progressSeekBar.progress = permille
                val pct = ((state.currentPositionMs * 100L) / state.durationMs).toInt().coerceIn(0, 100)
                progressTimeLabel.text = "⏱️ ${PlayerUiState.formatTimeMs(state.currentPositionMs)} / ${PlayerUiState.formatTimeMs(state.durationMs)} ($pct%) • Ripresa automatica attiva"
            }
        } else {
            progressSeekBar.isEnabled = false
            progressSeekBar.progress = 0
            progressTimeLabel.text = "📡 Flusso in diretta (LIVE) • Usa 🔄 Riprova se il segnale cade"
        }

        btnPlayPause.text = when {
            state.errorMessage != null -> "🔄 Riprova"
            state.isPlaying -> "⏸️ Pausa"
            else -> "▶️ Riproduci"
        }

        if (state.isMuted) {
            btnMuteToggle.text = "🔇 Modalità: Salvaschermo Silenzioso (Spotify/Radio liberi)"
            btnMuteToggle.background = createRoundedBackground("#0F766E", "#14B8A6", 18f)
        } else {
            btnMuteToggle.text = "🔊 Modalità: Audio Video ATTIVO sull'impianto Auto"
            btnMuteToggle.background = createRoundedBackground("#B45309", "#F59E0B", 18f)
        }

        btnScaleToggle.text = "📐 Scala Video: ${state.scaleMode.label}"
        btnScaleToggle.background = when (state.scaleMode) {
            VideoScaleMode.ORIGINAL -> createRoundedBackground("#0F766E", "#14B8A6", 18f)
            VideoScaleMode.ZOOM_CROP -> createRoundedBackground("#1D4ED8", "#60A5FA", 18f)
            VideoScaleMode.STRETCH -> createRoundedBackground("#334155", "#64748B", 18f)
        }

        btnRepeatToggle.text = if (state.repeatAll) {
            "🔁 Loop: Playlist (${state.playlist.size})"
        } else {
            "🔁 Loop: Singolo Video"
        }

        btnTapModeToggle.text = state.tapActionMode.label

        // La posizione salvata cambia ogni 5 s durante la riproduzione: per non ricostruire tutta la lista
        // così spesso si considera solo il minuto di ripresa
        val playlistHash = state.playlist.map {
            listOf(it.id, it.title, it.uriOrUrl, it.sourceType, it.lastPositionMs / 60_000L, it.durationMs / 60_000L)
        }.hashCode() * 31 + state.currentIndex * 7 + (if (state.isPlaying) 1 else 0)
        if (playlistHash != lastRenderedPlaylistHash) {
            lastRenderedPlaylistHash = playlistHash
            renderPlaylistRows(state)
        }
    }

    private fun renderPlaylistRows(state: PlayerUiState) {
        playlistContainer.removeAllViews()

        state.playlist.forEachIndexed { index, item ->
            val isSelected = (index == state.currentIndex)
            val bgHex = if (isSelected) "#17253B" else "#161E2E"
            val borderHex = if (isSelected) "#38E0B2" else "#263248"

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = createRoundedBackground(bgHex, borderHex, 18f)
                setPadding(26, 22, 20, 22)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    VideoPlayerController.playItemById(this@MainActivity, item.id)
                }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            val badgePrefix = if (isSelected) {
                if (state.isPlaying) "▶️ LIVE • " else "⏸️ ATTIVO • "
            } else {
                "${index + 1}. "
            }

            val itemTitleView = TextView(this).apply {
                text = "$badgePrefix${item.title}"
                textSize = 15f
                setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSelected) Color.parseColor("#38E0B2") else Color.WHITE)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            textCol.addView(itemTitleView)

            val savedProg = ScreensaverRepository.loadSavedProgress(this, item.uriOrUrl)
            val posMs = item.lastPositionMs.takeIf { it > 5_000L } ?: (savedProg?.positionMs ?: 0L)
            val durMs = item.durationMs.takeIf { it > 0L } ?: (savedProg?.durationMs ?: 0L)
            val resumeInfo = if (posMs > 5_000L && durMs > 0L) {
                "⏱️ Riprendi da ${PlayerUiState.formatTimeMs(posMs)} / ${PlayerUiState.formatTimeMs(durMs)} • "
            } else {
                ""
            }

            val itemSubView = TextView(this).apply {
                text = "$resumeInfo[${item.sourceType.badge}] ${item.uriOrUrl}"
                textSize = 11.5f
                setTextColor(Color.parseColor("#94A3B8"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(0, 4, 0, 0)
            }
            textCol.addView(itemSubView)

            val btnDelete = Button(this).apply {
                text = "✕"
                textSize = 13f
                setTextColor(Color.parseColor("#FCA5A5"))
                background = createRoundedBackground("#2A161D", "#7F1D1D", 14f)
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(24, 14, 24, 14)
                setOnClickListener {
                    VideoPlayerController.removeItemById(this@MainActivity, item.id)
                }
            }

            row.addView(
                textCol,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 14 }
            )
            row.addView(
                btnDelete,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )

            playlistContainer.addView(row, marginLayoutParams(bottom = 12))
        }
    }

    private fun openLocalVideoPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("video/mp4", "video/x-matroska", "video/webm", "video/*")
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        pickLocalVideoLauncher.launch(intent)
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (!text.isNullOrBlank()) {
            val extracted = YouTubeStreamExtractor.extractUrlFromSharedText(text) ?: text
            urlInput.setText(extracted)
            Toast.makeText(this, "URL incollato dagli appunti", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Appunti vuoti", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createCardContainer(bgColorHex: String, strokeColorHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedBackground(bgColorHex, strokeColorHex, 24f)
            setPadding(32, 28, 32, 28)
        }
    }

    private fun createSectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
        }
    }

    private fun createStyledButton(label: String, bgHex: String, onClick: (View) -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = createRoundedBackground(bgHex, "#334155", 18f)
            setPadding(26, 22, 26, 22)
            setOnClickListener(onClick)
        }
    }

    private fun createRoundedBackground(fillHex: String, strokeHex: String, cornerRadiusPx: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(Color.parseColor(fillHex))
            setStroke(2, Color.parseColor(strokeHex))
        }
    }

    private fun marginLayoutParams(
        top: Int = 0,
        bottom: Int = 0
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = top
            bottomMargin = bottom
        }
    }
}
