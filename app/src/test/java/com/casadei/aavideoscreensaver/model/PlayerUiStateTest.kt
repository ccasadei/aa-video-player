package com.casadei.aavideoscreensaver.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUiStateTest {

    @Test
    fun `diretta con finestra live di 60 secondi non e' seekable`() {
        assertFalse(PlayerUiState(durationMs = 60_000L, isLive = true).isSeekable)
    }

    @Test
    fun `video on demand di 60 secondi e' seekable`() {
        assertTrue(PlayerUiState(durationMs = 60_000L, isLive = false).isSeekable)
    }
}
