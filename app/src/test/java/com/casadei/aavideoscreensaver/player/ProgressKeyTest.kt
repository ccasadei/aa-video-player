package com.casadei.aavideoscreensaver.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressKeyTest {

    private fun key(url: String) = ScreensaverRepository.normalizeProgressKey(url)

    @Test
    fun `cdn hls generico con path numerico non collide con un film StreamingCommunity`() {
        assertEquals(
            "https://cdn.example.com/hls/1/playlist.m3u8",
            key("https://cdn.example.com/hls/1/playlist.m3u8")
        )
    }

    @Test
    fun `embed streamed non viene scambiato per un video YouTube`() {
        assertEquals(
            "https://embed.st/embed/alpha-tennis-1/1",
            key("https://embed.st/embed/alpha-tennis-1/1")
        )
    }

    @Test
    fun `parametro v= su sito non YouTube non diventa una chiave YouTube`() {
        assertEquals(
            "https://example.com/play?dev=abcdefghijk",
            key("https://example.com/play?dev=abcdefghijk")
        )
    }

    @Test
    fun `episodio vidxgo e flusso d2b dello stesso episodio condividono la chiave`() {
        assertEquals("sc_tv_2531336_s1_e1", key("https://v.vidxgo.co/2531336/1/1?se=0&info=0"))
        assertEquals(
            "sc_tv_2531336_s1_e1",
            key("https://cdn.v1.media-250.d2b.you/hls/tv/2531336/1/1/master.m3u8?t=794d&e=1792850523128&b=2887")
        )
    }

    @Test
    fun `film vidxgo e flusso d2b dello stesso film condividono la chiave`() {
        assertEquals("sc_movie_133093", key("https://v.vidxgo.co/0133093?se=0&info=0"))
        assertEquals("sc_movie_133093", key("https://cdn.v1.media-12.d2b.you/hls/0133093/master.m3u8?t=a&e=2"))
        assertEquals("sc_movie_133093", key("https://cdn.v1.media-12.d2b.you/hls/movie/0133093/master.m3u8?t=a&e=2"))
    }

    @Test
    fun `varianti di URL YouTube dello stesso video condividono la chiave gia' salvata`() {
        assertEquals("yt_rpio1uppkny", key("https://www.youtube.com/watch?v=rPio1UpPKnY"))
        assertEquals("yt_rpio1uppkny", key("https://youtu.be/rPio1UpPKnY"))
    }

    @Test
    fun `hls con token in query usa l'URL senza query`() {
        assertEquals(
            "https://lb4.strmd.st/secure/abc/hotel/stream/x/1/playlist.m3u8",
            key("https://lb4.strmd.st/secure/abc/hotel/stream/x/1/playlist.m3u8?token=zzz")
        )
    }

    @Test
    fun `watch URL di StreamingCommunity mantiene la stessa chiave anche se cambia dominio`() {
        assertEquals(
            "sc_watch_12264_e90021",
            key("https://streamingcommunityz.nexus/it/watch/12264?e=90021")
        )
        assertEquals(
            "sc_watch_12264_e90021",
            key("https://streamingcommunity.foo/it/watch/12264?e=90021")
        )
        assertEquals(
            "sc_watch_12264_e90021",
            key("https://streamingunity.dog/it/watch/12264?e=90021")
        )
        assertEquals(
            "sc_watch_66558",
            key("https://streamingcommunityz.nexus/it/watch/66558")
        )
    }
}
