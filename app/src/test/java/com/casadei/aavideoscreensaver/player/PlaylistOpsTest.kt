package com.casadei.aavideoscreensaver.player

import com.casadei.aavideoscreensaver.model.ScreensaverItem
import com.casadei.aavideoscreensaver.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistOpsTest {

    private fun item(id: String) = ScreensaverItem(
        id = id,
        title = "Titolo $id",
        uriOrUrl = "https://example.com/$id.m3u8",
        sourceType = SourceType.DIRECT_STREAM,
        addedAtMs = 0L
    )

    private val abcd = listOf(item("a"), item("b"), item("c"), item("d"))

    @Test
    fun `rimuovere un elemento prima di quello attivo sposta indietro l'indice corrente`() {
        val r = PlaylistOps.removeById(abcd, currentIndex = 2, itemId = "a")!!
        assertEquals(listOf("b", "c", "d"), r.playlist.map { it.id })
        assertEquals(1, r.currentIndex)
        assertFalse(r.removedActive)
    }

    @Test
    fun `rimuovere un elemento dopo quello attivo lascia invariato l'indice corrente`() {
        val r = PlaylistOps.removeById(abcd, currentIndex = 1, itemId = "d")!!
        assertEquals(listOf("a", "b", "c"), r.playlist.map { it.id })
        assertEquals(1, r.currentIndex)
        assertFalse(r.removedActive)
    }

    @Test
    fun `rimuovere l'elemento attivo seleziona quello che ne prende il posto`() {
        val r = PlaylistOps.removeById(abcd, currentIndex = 1, itemId = "b")!!
        assertEquals(listOf("a", "c", "d"), r.playlist.map { it.id })
        assertEquals(1, r.currentIndex)
        assertTrue(r.removedActive)
        assertEquals("Titolo b", r.removedTitle)
    }

    @Test
    fun `rimuovere l'ultimo elemento attivo riporta l'indice dentro la lista`() {
        val r = PlaylistOps.removeById(abcd, currentIndex = 3, itemId = "d")!!
        assertEquals(2, r.currentIndex)
        assertTrue(r.removedActive)
    }

    @Test
    fun `id gia' rimosso non cancella un altro elemento`() {
        // Doppio tocco su una lista non ancora aggiornata: il secondo tocco non deve colpire la riga successiva
        val first = PlaylistOps.removeById(abcd, currentIndex = 0, itemId = "b")!!
        assertNull(PlaylistOps.removeById(first.playlist, first.currentIndex, itemId = "b"))
    }

    @Test
    fun `rimuovere l'unico elemento lascia una lista vuota con indice zero`() {
        val r = PlaylistOps.removeById(listOf(item("a")), currentIndex = 0, itemId = "a")!!
        assertTrue(r.playlist.isEmpty())
        assertEquals(0, r.currentIndex)
    }

    @Test
    fun `gli stream demo precaricati vengono tolti, gli altri elementi restano in ordine`() {
        val demo1 = item("demo_nasa_iss").copy(sourceType = SourceType.DEMO_STREAM)
        val demo2 = item("demo_tears_widescreen").copy(sourceType = SourceType.DEMO_STREAM)
        val film = item("f1").copy(sourceType = SourceType.STREAMING_COMMUNITY)
        val yt = item("y1").copy(sourceType = SourceType.YOUTUBE)
        assertEquals(listOf("f1", "y1"), PlaylistOps.withoutDemoItems(listOf(demo1, film, demo2, yt)).map { it.id })
    }

    @Test
    fun `playlist composta solo da demo diventa vuota`() {
        val demo = item("demo_redbull_tv").copy(sourceType = SourceType.DEMO_STREAM)
        assertTrue(PlaylistOps.withoutDemoItems(listOf(demo)).isEmpty())
    }
}
