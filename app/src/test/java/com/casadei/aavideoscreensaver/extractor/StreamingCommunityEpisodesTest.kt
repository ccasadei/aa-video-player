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

    @Test
    fun `estrae catalogo film e serie da Inertia data-page di streamingcommunityz`() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html><body>
            <div id="app" data-page="{&quot;component&quot;:&quot;Titles/Index&quot;,&quot;props&quot;:{&quot;sliders&quot;:[{&quot;name&quot;:&quot;trending&quot;,&quot;titles&quot;:[{&quot;id&quot;:66558,&quot;slug&quot;:&quot;the-gospel-of-christmas&quot;,&quot;name&quot;:&quot;The Gospel of Christmas&quot;,&quot;type&quot;:&quot;movie&quot;,&quot;last_air_date&quot;:&quot;2026-11-01&quot;},{&quot;id&quot;:12264,&quot;slug&quot;:&quot;mobland&quot;,&quot;name&quot;:&quot;MobLand&quot;,&quot;type&quot;:&quot;tv&quot;,&quot;last_air_date&quot;:&quot;2025-03-30&quot;}]}]}}"></div>
            </body></html>
        """.trimIndent()

        val entries = StreamingCommunityCatalogClient.parseInertiaCatalogHtml(sampleHtml, defaultIsSeries = null)
        assertEquals(2, entries.size)
        assertEquals(66558, entries[0].scTitleId)
        assertEquals("the-gospel-of-christmas", entries[0].scSlug)
        assertEquals("The Gospel of Christmas", entries[0].title)
        assertEquals(false, entries[0].isSeries)
        assertEquals("2026", entries[0].year)

        assertEquals(12264, entries[1].scTitleId)
        assertEquals("mobland", entries[1].scSlug)
        assertEquals("MobLand", entries[1].title)
        assertEquals(true, entries[1].isSeries)
        assertEquals("2025", entries[1].year)
    }

    @Test
    fun `riscrive vecchio dominio streamingcommunity con il nuovo baseUrl`() {
        assertEquals(
            "https://streamingcommunityz.nexus/it/watch/12264?e=90021",
            StreamingCommunityExtractor.rewriteStreamingCommunityDomain(
                "https://streamingcommunity.foo/it/watch/12264?e=90021",
                "https://streamingcommunityz.nexus"
            )
        )
        assertEquals(
            "https://streamingunity.dog/it/watch/12264?e=90021",
            StreamingCommunityExtractor.rewriteStreamingCommunityDomain(
                "https://streamingcommunityz.nexus/it/watch/12264?e=90021",
                "https://streamingunity.dog"
            )
        )
    }

    @Test
    fun `estrae entrambi gli URL dai bottoni di streaming-community punto day`() {
        val dayHtml = """
            <!DOCTYPE html>
            <html><body>
            <div class="btns">
                <a class="btn sc" href="https://streamingcommunityz.nexus/" target="_blank">Accedi a StreamingCommunity</a>
                <a class="btn su" href="https://streamingunity.dog/" target="_blank">Accedi a StreamingUnity</a>
            </div>
            </body></html>
        """.trimIndent()
        assertEquals(
            listOf("https://streamingcommunityz.nexus", "https://streamingunity.dog"),
            StreamingCommunityUrlResolver.parseCandidateUrlsFromCheckerHtml(dayHtml)
        )
    }

    @Test
    fun `parseVixCloudScript aggiunge h=1 solo quando canPlayFHD e' true`() {
        val scriptHdOnly = """
            window.masterPlaylist = {
                params: {
                    'token': '1e0dd1a39255bff1ffb4779d698c4b5e',
                    'expires': '1799584750',
                    'asn': '',
                },
                url: 'https://vixcloud.cc/playlist/317006',
            }
            window.canPlayFHD = false
        """.trimIndent()
        assertEquals(
            "https://vixcloud.cc/playlist/317006?token=1e0dd1a39255bff1ffb4779d698c4b5e&expires=1799584750",
            HeadlessStreamSniffer.parseVixCloudScript(scriptHdOnly)
        )

        val scriptFhd = scriptHdOnly.replace("canPlayFHD = false", "canPlayFHD = true")
        assertEquals(
            "https://vixcloud.cc/playlist/317006?token=1e0dd1a39255bff1ffb4779d698c4b5e&expires=1799584750&h=1",
            HeadlessStreamSniffer.parseVixCloudScript(scriptFhd)
        )
    }
}
