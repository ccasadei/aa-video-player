package com.casadei.aavideoscreensaver.extractor

import com.casadei.aavideoscreensaver.extractor.StreamedPkClient.StreamSourceRef
import com.casadei.aavideoscreensaver.extractor.StreamedPkClient.StreamedMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamedPkUnavailableTest {

    private val now = 1_792_850_000_000L

    private fun match(id: String, title: String, sourceId: String, startMs: Long) = StreamedMatch(
        id = id,
        title = title,
        category = "football",
        dateMs = startMs,
        popular = false,
        sources = listOf(StreamSourceRef(source = "hotel", id = sourceId))
    )

    private val rally = match("m1", "Rally Six Cities SS4", "rally-ss4", now - 3 * 3_600_000L)
    private val rayo = match("m2", "Rayo Vallecano vs Elche", "rayo-elche", now - 10 * 60_000L)

    @Test
    fun `evento che inizia piu' tardi lo segnala come non ancora iniziato`() {
        val later = match("m3", "Inter vs Milan", "inter-milan", now + 2 * 3_600_000L)
        val msg = StreamedPkClient.unavailableReason("inter-milan", listOf(rally, rayo, later), now, "⚽ Inter vs Milan")
        assertTrue(msg, msg.contains("Inter vs Milan") && msg.contains("inizierà"))
    }

    @Test
    fun `evento terminato non viene sostituito da un'altra diretta`() {
        // Caso dai log del 24-10 14:03: scelto il Rally, riprodotto "Rayo Vallecano vs Elche"
        val msg = StreamedPkClient.unavailableReason("rally-ss4", listOf(rally, rayo), now, "⚽ Rally Six Cities SS4")
        assertTrue(msg, msg.contains("Rally Six Cities SS4"))
        assertTrue(msg, msg.contains("terminat") || msg.contains("non disponibil"))
        assertFalse(msg, msg.contains("Rayo"))
    }

    @Test
    fun `evento sconosciuto produce comunque un messaggio comprensibile`() {
        val msg = StreamedPkClient.unavailableReason(null, emptyList(), now, "⚽ Evento")
        assertTrue(msg, msg.isNotBlank())
    }

    @Test
    fun `evento che parte tra due ore viene riconosciuto prima di cercare il flusso`() {
        val later = match("m3", "Inter vs Milan", "inter-milan", now + 2 * 3_600_000L)
        assertEquals("m3", StreamedPkClient.scheduledLater("inter-milan", listOf(rally, later), now)?.id)
    }

    @Test
    fun `evento gia' iniziato non blocca la ricerca del flusso`() {
        assertNull(StreamedPkClient.scheduledLater("rayo-elche", listOf(rally, rayo), now))
    }

    @Test
    fun `evento che parte entro 10 minuti si prova comunque a sintonizzare`() {
        val soon = match("m4", "Pre-gara", "pre-gara", now + 5 * 60_000L)
        assertNull(StreamedPkClient.scheduledLater("pre-gara", listOf(soon), now))
    }
}
