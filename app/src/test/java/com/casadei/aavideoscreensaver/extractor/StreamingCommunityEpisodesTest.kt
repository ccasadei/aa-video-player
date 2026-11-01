package com.casadei.aavideoscreensaver.extractor

import com.casadei.aavideoscreensaver.extractor.StreamingCommunityCatalogClient.EpisodeEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingCommunityEpisodesTest {

    private fun eps(season: Int, count: Int) = (1..count).map { EpisodeEntry(season, it, "Episodio $it") }

    @Test
    fun `conteggi per stagione letti dallo script del player`() {
        val js = """const SHOW = {"title":"Silo","seasons":[{"n":1,"count":10},{"n":2,"count":10},{"n":3,"count":1}]};"""
        assertEquals(mapOf(1 to 10, 2 to 10, 3 to 1), StreamingCommunityExtractor.parseSeasonCounts(js))
    }

    @Test
    fun `episodi oltre il conteggio disponibile vengono nascosti`() {
        // Log 24-10 20:37: Silo S03E02 elencato su AA ma "non ancora disponibile"
        val filtered = StreamingCommunityCatalogClient.filterAvailableEpisodes(
            mapOf(2 to eps(2, 10), 3 to eps(3, 10)),
            mapOf(2 to 10, 3 to 1)
        )
        assertEquals(10, filtered[2]!!.size)
        assertEquals(listOf(1), filtered[3]!!.map { it.episode })
    }

    @Test
    fun `stagione senza conteggio noto resta com'era`() {
        val filtered = StreamingCommunityCatalogClient.filterAvailableEpisodes(mapOf(1 to eps(1, 8)), emptyMap())
        assertEquals(8, filtered[1]!!.size)
    }

    @Test
    fun `stagione con zero episodi disponibili sparisce dall'elenco`() {
        val filtered = StreamingCommunityCatalogClient.filterAvailableEpisodes(
            mapOf(1 to eps(1, 8), 4 to eps(4, 6)),
            mapOf(1 to 8, 4 to 0)
        )
        assertEquals(setOf(1), filtered.keys)
    }
}
