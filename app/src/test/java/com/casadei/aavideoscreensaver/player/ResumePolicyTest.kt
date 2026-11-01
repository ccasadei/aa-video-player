package com.casadei.aavideoscreensaver.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumePolicyTest {

    @Test
    fun `posizione oltre la durata reale riparte da zero`() {
        assertEquals(0L, ResumePolicy.sanitize(191_871L, 0L, 180_000L, isLive = false))
    }

    @Test
    fun `posizione negli ultimi 5 secondi riparte da zero`() {
        assertEquals(0L, ResumePolicy.sanitize(175_000L, 0L, 180_000L, isLive = false))
    }

    @Test
    fun `posizione appena prima della guardia finale viene mantenuta`() {
        assertEquals(174_999L, ResumePolicy.sanitize(174_999L, 0L, 180_000L, isLive = false))
    }

    @Test
    fun `durata salvata di un altro video azzera la ripresa`() {
        // Dati reali: YUNGBLUD (233.871 ms) con posizione/durata salvate di The Goonies
        assertEquals(0L, ResumePolicy.sanitize(191_871L, 6_838_016L, 233_871L, isLive = false))
    }

    @Test
    fun `durata salvata coerente mantiene la posizione`() {
        assertEquals(222_511L, ResumePolicy.sanitize(222_511L, 8_177_874L, 8_177_874L, isLive = false))
    }

    @Test
    fun `piccola differenza di durata entro 15 secondi mantiene la posizione`() {
        assertEquals(222_511L, ResumePolicy.sanitize(222_511L, 8_170_000L, 8_177_874L, isLive = false))
    }

    @Test
    fun `durata salvata sconosciuta non blocca una ripresa valida`() {
        assertEquals(64_243L, ResumePolicy.sanitize(64_243L, 0L, 233_871L, isLive = false))
    }

    @Test
    fun `durata reale ancora sconosciuta mantiene la posizione richiesta`() {
        assertEquals(222_511L, ResumePolicy.sanitize(222_511L, 8_177_874L, 0L, isLive = false))
    }

    @Test
    fun `una diretta non riprende mai da una posizione salvata`() {
        assertEquals(0L, ResumePolicy.sanitize(64_243L, 0L, 120_000L, isLive = true))
    }

    @Test
    fun `posizione entro i primi 5 secondi parte da zero`() {
        assertEquals(0L, ResumePolicy.sanitize(4_000L, 0L, 233_871L, isLive = false))
    }
}
