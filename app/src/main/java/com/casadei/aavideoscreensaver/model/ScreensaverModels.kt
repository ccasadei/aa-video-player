package com.casadei.aavideoscreensaver.model

enum class SourceType(val label: String, val badge: String) {
    DEMO_STREAM("Stream Demo", "DEMO HLS"),
    LOCAL_FILE("File Locale", "LOCALE"),
    DIRECT_STREAM("Stream Rete", "STREAM"),
    YOUTUBE("YouTube", "YOUTUBE"),
    STREAMING_COMMUNITY("StreamingCommunity", "STREAMING COMMUNITY"),
    STREAMED_PK("Streamed.pk Live", "STREAMED.PK")
}

enum class VideoScaleMode(val label: String, val shortLabel: String) {
    ORIGINAL("Originale (proporzioni intatte, bande nere)", "ORIGINALE"),
    ZOOM_CROP("Zoom / Ritaglio (schermo pieno, taglia i bordi)", "ZOOM"),
    STRETCH("Adatta (schermo pieno, immagine stirata)", "STIRA");

    /** Modalità successiva per il pulsante "Scala" (ciclo Originale -> Zoom -> Stira). */
    fun next(): VideoScaleMode = values()[(ordinal + 1) % values().size]

    companion object {
        /**
         * Rilegge la modalità salvata. "FIT" delle versioni precedenti era presentata come "proporzioni originali"
         * ma su una Surface diretta stirava l'immagine: la si converte in ORIGINAL, che era l'intenzione dell'utente.
         */
        fun fromStoredName(name: String?): VideoScaleMode = when (name) {
            "FIT" -> ORIGINAL
            null -> ORIGINAL
            else -> values().firstOrNull { it.name == name } ?: ORIGINAL
        }
    }
}

enum class TapActionMode(val label: String) {
    OVERLAY_AND_PLAY_PAUSE("Tap: Play/Pausa + Overlay 5s"),
    OVERLAY_ONLY("Tap: Mostra Solo Overlay 5s")
}

data class ScreensaverItem(
    val id: String,
    val title: String,
    val uriOrUrl: String,
    val sourceType: SourceType,
    val addedAtMs: Long = System.currentTimeMillis(),
    val httpHeaders: Map<String, String> = emptyMap(),
    val lastPositionMs: Long = 0L,
    val durationMs: Long = 0L
)

data class PlayerUiState(
    val playlist: List<ScreensaverItem> = emptyList(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isMuted: Boolean = true,
    val scaleMode: VideoScaleMode = VideoScaleMode.ORIGINAL,
    val repeatAll: Boolean = false,
    val tapActionMode: TapActionMode = TapActionMode.OVERLAY_AND_PLAY_PAUSE,
    val statusMessage: String = "Pronto",
    val errorMessage: String? = null,
    val resolvedTitle: String = "",
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isLive: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val carSurfaceConnected: Boolean = false,
    val carSurfaceWidth: Int = 0,
    val carSurfaceHeight: Int = 0,
    val overlayVisibleUntilMs: Long = 0L
) {
    val currentItem: ScreensaverItem?
        get() = playlist.getOrNull(currentIndex)

    val isSeekable: Boolean
        get() = !isLive && durationMs > 30_000L

    companion object {
        fun formatTimeMs(ms: Long): String {
            if (ms <= 0L) return "0:00"
            val totalSec = (ms / 1000L).coerceAtLeast(0L)
            val hours = totalSec / 3600L
            val minutes = (totalSec % 3600L) / 60L
            val seconds = totalSec % 60L
            return if (hours > 0L) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }

        fun buildTextProgressBar(posMs: Long, durMs: Long, segments: Int = 10): String {
            if (durMs <= 0L) return ""
            val ratio = (posMs.toDouble() / durMs.toDouble()).coerceIn(0.0, 1.0)
            val pct = (ratio * 100.0).toInt()
            val filled = (ratio * segments).toInt().coerceIn(0, segments)
            val bar = buildString {
                for (i in 0 until segments) {
                    when {
                        i < filled -> append('━')
                        i == filled -> append('●')
                        else -> append('─')
                    }
                }
            }
            return "$bar ${formatTimeMs(posMs)} / ${formatTimeMs(durMs)} ($pct%)"
        }
    }
}
