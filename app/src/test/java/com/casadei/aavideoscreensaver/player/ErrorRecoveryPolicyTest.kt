package com.casadei.aavideoscreensaver.player

import androidx.media3.common.PlaybackException
import com.casadei.aavideoscreensaver.model.SourceType
import com.casadei.aavideoscreensaver.player.ErrorRecoveryPolicy.Action
import com.casadei.aavideoscreensaver.player.ErrorRecoveryPolicy.Attempts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorRecoveryPolicyTest {

    private fun decide(code: Int, http: Int? = null, type: SourceType? = SourceType.STREAMED_PK, attempts: Attempts = Attempts()) =
        ErrorRecoveryPolicy.decide(code, http, type, attempts)

    @Test
    fun `diretta rimasta indietro rispetto alla finestra live si riaggancia al punto live`() {
        // Log del 24-10 20:00:44, Streamed.pk GP USA Practice 3: ERROR_CODE_BEHIND_LIVE_WINDOW
        assertEquals(Action.RESYNC_LIVE, decide(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
    }

    @Test
    fun `riaggancio live che fallisce di continuo si ferma e mostra l'errore`() {
        assertEquals(Action.SHOW_ERROR, decide(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, attempts = Attempts(liveResyncs = 3)))
    }

    @Test
    fun `decoder non inizializzato viene ritentato due volte`() {
        assertEquals(Action.RETRY_DECODER, decide(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, attempts = Attempts(decoderRetries = 1)))
        assertEquals(Action.SHOW_ERROR, decide(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, attempts = Attempts(decoderRetries = 2)))
    }

    @Test
    fun `rete persa (galleria) viene ritentata prima di mostrare l'errore`() {
        assertEquals(Action.RETRY_NETWORK, decide(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, type = SourceType.YOUTUBE))
        assertEquals(Action.RETRY_NETWORK, decide(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, type = SourceType.YOUTUBE, attempts = Attempts(networkRetries = 2)))
        assertEquals(Action.SHOW_ERROR, decide(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, type = SourceType.YOUTUBE, attempts = Attempts(networkRetries = 3)))
    }

    @Test
    fun `token scaduto (403) rigenera il link`() {
        assertEquals(Action.RERESOLVE, decide(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403, SourceType.STREAMING_COMMUNITY))
    }

    @Test
    fun `404 su StreamingCommunity e' un contenuto mancante e non va ritentato`() {
        assertEquals(Action.SHOW_ERROR, decide(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404, SourceType.STREAMING_COMMUNITY))
    }

    @Test
    fun `404 su una diretta Streamed indica un link ruotato e va rigenerato`() {
        assertEquals(Action.RERESOLVE, decide(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404, SourceType.STREAMED_PK))
    }

    @Test
    fun `manifest non valido su Streamed rigenera il link, su altri provider no`() {
        assertEquals(Action.RERESOLVE, decide(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, type = SourceType.STREAMED_PK))
        assertEquals(Action.SHOW_ERROR, decide(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, type = SourceType.STREAMING_COMMUNITY))
    }

    @Test
    fun `YouTube ha un tentativo per ogni strategia alternativa`() {
        assertEquals(Action.RERESOLVE, decide(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403, SourceType.YOUTUBE, Attempts(httpRetries = 2)))
        assertEquals(Action.SHOW_ERROR, decide(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403, SourceType.YOUTUBE, Attempts(httpRetries = 3)))
    }

    @Test
    fun `gli altri provider si fermano dopo due rigenerazioni`() {
        assertEquals(Action.SHOW_ERROR, decide(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403, SourceType.STREAMING_COMMUNITY, Attempts(httpRetries = 2)))
    }

    @Test
    fun `403 prima che il video parta esclude la strategia YouTube che ha prodotto l'URL`() {
        assertTrue(ErrorRecoveryPolicy.shouldExcludeYouTubeStrategy(403, reachedReady = false))
    }

    @Test
    fun `403 a video gia' partito (URL scaduto o cambio IP) non esclude la strategia che funziona`() {
        assertFalse(ErrorRecoveryPolicy.shouldExcludeYouTubeStrategy(403, reachedReady = true))
    }

    @Test
    fun `errori diversi dal 403 non escludono strategie YouTube`() {
        assertFalse(ErrorRecoveryPolicy.shouldExcludeYouTubeStrategy(500, reachedReady = false))
    }

    @Test
    fun `superficie Android Auto morta non mostra errore e attende quella nuova`() {
        // Log 24-10 20:31:09 e 20:46:20: setOutputSurface -> CodecException (No such device -19)
        val action = ErrorRecoveryPolicy.decide(
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, null, SourceType.STREAMING_COMMUNITY, Attempts(), surfaceError = true
        )
        assertEquals(Action.SURFACE_LOST, action)
    }

    @Test
    fun `superficie che continua a fallire alla fine mostra l'errore`() {
        val action = ErrorRecoveryPolicy.decide(
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, null, SourceType.STREAMING_COMMUNITY,
            Attempts(decoderRetries = 2), surfaceError = true
        )
        assertEquals(Action.SHOW_ERROR, action)
    }

    @Test
    fun `errore runtime non legato alla superficie resta un errore`() {
        assertEquals(Action.SHOW_ERROR, decide(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, type = SourceType.STREAMING_COMMUNITY))
    }
}
