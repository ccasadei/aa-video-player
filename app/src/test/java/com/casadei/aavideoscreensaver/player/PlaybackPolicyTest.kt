package com.casadei.aavideoscreensaver.player

import com.casadei.aavideoscreensaver.player.PlaybackPolicy.EndAction
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPolicyTest {

    @Test
    fun `loop playlist con piu' elementi passa al successivo a fine video`() {
        assertEquals(EndAction.NEXT, PlaybackPolicy.onEnded(true, 3, playedForMs = 233_000L, durationMs = 233_871L))
    }

    @Test
    fun `loop playlist con un solo elemento ricomincia lo stesso video`() {
        assertEquals(EndAction.RESTART, PlaybackPolicy.onEnded(true, 1, playedForMs = 233_000L, durationMs = 233_871L))
    }

    @Test
    fun `loop singolo ricomincia lo stesso video anche con playlist lunga`() {
        assertEquals(EndAction.RESTART, PlaybackPolicy.onEnded(false, 3, playedForMs = 233_000L, durationMs = 233_871L))
    }

    @Test
    fun `fine immediata dopo l'avvio di un video lungo non salta al successivo`() {
        // Sintomo segnalato: posizione di ripresa errata -> il video finisce subito e la playlist avanza
        assertEquals(EndAction.RESTART, PlaybackPolicy.onEnded(true, 3, playedForMs = 800L, durationMs = 233_871L))
    }

    @Test
    fun `clip breve finita in pochi secondi avanza normalmente`() {
        assertEquals(EndAction.NEXT, PlaybackPolicy.onEnded(true, 3, playedForMs = 2_500L, durationMs = 2_600L))
    }
}
