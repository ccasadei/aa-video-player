package com.casadei.aavideoscreensaver.extractor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Le risposte InnerTube qui sotto riproducono la struttura osservata il 24-10-2026:
 * - ANDROID (VOD): formats = [itag 18], adaptiveFormats con 137/140 (gli adattivi rispondono 403 senza poToken)
 * - IOS (VOD): nessun format muxed, solo adaptiveFormats
 * - ANDROID (live): hlsManifestUrl
 */
class YouTubeSelectionTest {

    private val ua = "test-ua"

    private val androidVod = JSONObject(
        """
        {"playabilityStatus":{"status":"OK"},
         "videoDetails":{"videoId":"rPio1UpPKnY","title":"YUNGBLUD - Lowlife","author":"YUNGBLUD","lengthSeconds":"233","isLive":false},
         "streamingData":{
           "formats":[{"itag":18,"url":"https://gv/itag18","mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"","height":360,"qualityLabel":"360p"}],
           "adaptiveFormats":[
             {"itag":137,"url":"https://gv/itag137","mimeType":"video/mp4; codecs=\"avc1.640028\"","height":1080,"fps":30,"bitrate":4000000,"qualityLabel":"1080p"},
             {"itag":140,"url":"https://gv/itag140","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130000}
           ]}}
        """.trimIndent()
    )

    private val iosVodAdaptiveOnly = JSONObject(
        """
        {"playabilityStatus":{"status":"OK"},
         "videoDetails":{"videoId":"rPio1UpPKnY","title":"YUNGBLUD - Lowlife","author":"YUNGBLUD","lengthSeconds":"233","isLive":false},
         "streamingData":{
           "adaptiveFormats":[
             {"itag":137,"url":"https://gv/ios137","mimeType":"video/mp4; codecs=\"avc1.640028\"","height":1080,"fps":30,"bitrate":4000000,"qualityLabel":"1080p"},
             {"itag":140,"url":"https://gv/ios140","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130000}
           ]}}
        """.trimIndent()
    )

    private val androidLive = JSONObject(
        """
        {"playabilityStatus":{"status":"OK"},
         "videoDetails":{"videoId":"rFZHOHl-L8A","title":"lofi radio","author":"Lofi","lengthSeconds":"0","isLive":true},
         "streamingData":{"hlsManifestUrl":"https://manifest/hls_variant/live.m3u8"}}
        """.trimIndent()
    )

    @Test
    fun `strategia ANDROID su VOD usa il formato muxed itag 18`() {
        val r = YouTubeStreamExtractor.pickFromPlayerResponse(androidVod, "rPio1UpPKnY", YouTubeStrategy.ANDROID, ua, allowAdaptive = false)
        assertEquals("https://gv/itag18", r.videoUrl)
        assertNull(r.audioUrl)
        assertEquals(233_000L, r.durationMs)
        assertEquals(YouTubeStrategy.ANDROID, r.strategy)
    }

    @Test
    fun `risposta con soli formati adattivi non e' accettata quando gli adattivi non sono ammessi`() {
        try {
            YouTubeStreamExtractor.pickFromPlayerResponse(iosVodAdaptiveOnly, "rPio1UpPKnY", YouTubeStrategy.IOS, ua, allowAdaptive = false)
            fail("Gli URL adattivi senza poToken danno HTTP 403: la strategia deve fallire e passare alla successiva")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("IOS"))
        }
    }

    @Test
    fun `strategia ADAPTIVE preferisce gli adattivi anche se esiste il muxed gia' rifiutato`() {
        val r = YouTubeStreamExtractor.pickFromPlayerResponse(androidVod, "rPio1UpPKnY", YouTubeStrategy.ADAPTIVE, ua, allowAdaptive = true)
        assertEquals("https://gv/itag137", r.videoUrl)
        assertEquals("https://gv/itag140", r.audioUrl)
    }

    @Test
    fun `diretta usa l'HLS`() {
        val r = YouTubeStreamExtractor.pickFromPlayerResponse(androidLive, "rFZHOHl-L8A", YouTubeStrategy.ANDROID, ua, allowAdaptive = false)
        assertEquals("https://manifest/hls_variant/live.m3u8", r.hlsUrl)
    }

    @Test
    fun `video non riproducibile riporta il motivo`() {
        val unplayable = JSONObject("""{"playabilityStatus":{"status":"UNPLAYABLE","reason":"Registrazione non disponibile"}}""")
        try {
            YouTubeStreamExtractor.pickFromPlayerResponse(unplayable, "x", YouTubeStrategy.ANDROID, ua, allowAdaptive = false)
            fail("Atteso errore")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Registrazione non disponibile"))
        }
    }

    @Test
    fun `senza esclusioni si parte da NewPipe, che oggi fornisce HLS adattivo anche per i video normali`() {
        // Misura del 24-10: NewPipe 0.26.5 -> HLS fino a 1080p scaricabile; ANDROID -> solo 360p muxed
        assertEquals(YouTubeStrategy.NEWPIPE, YouTubeStreamExtractor.strategyOrder(emptySet()).first())
    }

    @Test
    fun `dopo un 403 su NewPipe si passa al client ANDROID`() {
        val order = YouTubeStreamExtractor.strategyOrder(setOf(YouTubeStrategy.NEWPIPE))
        assertFalse(order.contains(YouTubeStrategy.NEWPIPE))
        assertEquals(YouTubeStrategy.ANDROID, order.first())
    }

    @Test
    fun `se tutte le strategie sono state escluse si ricomincia dalla lista completa`() {
        val order = YouTubeStreamExtractor.strategyOrder(YouTubeStrategy.values().toSet())
        assertEquals(YouTubeStrategy.NEWPIPE, order.first())
        assertEquals(4, order.size)
    }
}
