package com.casadei.aavideoscreensaver.player

import androidx.media3.common.PlaybackException
import com.casadei.aavideoscreensaver.extractor.YouTubeStrategy
import com.casadei.aavideoscreensaver.model.SourceType

/**
 * Decide come reagire a un errore di ExoPlayer (funzione pura, testabile senza Android).
 */
object ErrorRecoveryPolicy {

    enum class Action { RESYNC_LIVE, RETRY_DECODER, SURFACE_LOST, RETRY_NETWORK, RERESOLVE, SHOW_ERROR }

    /** Tentativi già fatti per l'elemento corrente (azzerati quando il player torna in STATE_READY). */
    data class Attempts(
        val decoderRetries: Int = 0,
        val httpRetries: Int = 0,
        val liveResyncs: Int = 0,
        val networkRetries: Int = 0
    )

    private const val MAX_DECODER_RETRIES = 2
    private const val MAX_LIVE_RESYNCS = 3
    private const val MAX_NETWORK_RETRIES = 3

    /** YouTube ha più strategie di estrazione da provare; per gli altri provider bastano 2 rigenerazioni. */
    fun maxHttpRetries(sourceType: SourceType?): Int =
        if (sourceType == SourceType.YOUTUBE) YouTubeStrategy.values().size - 1 else 2

    /**
     * Un 403 prima che il video parta significa che la strategia produce URL non accettati (es. adattivi senza
     * poToken): va esclusa. Dopo STATE_READY invece l'URL era valido ed è scaduto (expire) o è cambiato l'IP
     * (Wi-Fi -> rete mobile): la stessa strategia, rieseguita, fornisce un URL nuovo e funzionante.
     */
    fun shouldExcludeYouTubeStrategy(httpCode: Int?, reachedReady: Boolean): Boolean {
        return httpCode == 403 && !reachedReady
    }

    fun decide(
        errorCode: Int,
        httpCode: Int?,
        sourceType: SourceType?,
        attempts: Attempts,
        surfaceError: Boolean = false
    ): Action {
        val isStreamed = sourceType == SourceType.STREAMED_PK ||
            sourceType == SourceType.PPV ||
            sourceType == SourceType.PITSPORT
        val canReresolve = attempts.httpRetries < maxHttpRetries(sourceType)
        if (surfaceError) {
            // Superficie dell'auto distrutta o sostituita: non è un problema del contenuto
            return if (attempts.decoderRetries < MAX_DECODER_RETRIES) Action.SURFACE_LOST else Action.SHOW_ERROR
        }
        return when (errorCode) {
            // La diretta è andata oltre la posizione di riproduzione (stallo / buffering lungo)
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                if (attempts.liveResyncs < MAX_LIVE_RESYNCS) Action.RESYNC_LIVE else Action.SHOW_ERROR

            // Decoder hardware MediaTek su una Surface transitoria durante il cambio schermata AA
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                if (attempts.decoderRetries < MAX_DECODER_RETRIES) Action.RETRY_DECODER else Action.SHOW_ERROR

            // Copertura assente per qualche secondo (gallerie, zone senza segnale)
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                if (attempts.networkRetries < MAX_NETWORK_RETRIES) Action.RETRY_NETWORK else Action.SHOW_ERROR

            // Token/link scaduto. Il 404 è un contenuto mancante, tranne nelle dirette Streamed dove il link ruota
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                if ((httpCode != 404 || isStreamed) && canReresolve) Action.RERESOLVE else Action.SHOW_ERROR

            // Streamed risponde a volte "Not found" con HTTP 200 al posto della playlist
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                if (isStreamed && canReresolve) Action.RERESOLVE else Action.SHOW_ERROR

            else -> Action.SHOW_ERROR
        }
    }
}
