package com.casadei.aavideoscreensaver.extractor

import android.content.Context
import android.net.Uri
import android.text.Html
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Estrattore per StreamingCommunity (streaming-community.biz / v.vidxgo.co / d2b.you e VixCloud / VixSrc).
 *
 * Supporta:
 * 1. Player `v.vidxgo.co/{imdb}` e `v.vidxgo.co/{imdb}/{s}/{e}` (decodifica XOR istantanea + headers CORS Sec-Fetch-*)
 * 2. Flussi `.m3u8` `d2b.you/hls/...` anche già salvati in playlist con token scaduto (rigenera automaticamente il token!)
 * 3. Pagine `streaming-community.biz/titles/...` (estrae l'iframe `v.vidxgo.co` da `/watching`)
 * 4. URL VixSrc / VixCloud (`vixsrc.to`, `vixcloud.co`) con fallback automatico a `HeadlessStreamSniffer`.
 */
object StreamingCommunityExtractor {

    private const val TAG = "AAVideo"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val SC_BIZ_BASE = "https://streaming-community.biz"
    private const val VIDXGO_BASE = "https://v.vidxgo.co"

    data class ResolvedVixStream(
        val title: String,
        val streamUrl: String,
        val headers: Map<String, String>,
        val resolutionLabel: String = "1080p FHD"
    )

    private data class CachedVidxgoToken(
        val token: String,
        val expireMs: Long,
        val refreshedAtMs: Long = System.currentTimeMillis()
    )

    private val tokenCache = ConcurrentHashMap<String, CachedVidxgoToken>()
    private val tokenRefreshLock = Any()

    /**
     * Interceptor OkHttp che intercetta tutte le richieste di ExoPlayer verso i CDN `*.d2b.you/hls/...`:
     * 1. Applica sempre gli header obbligatori (`Referer: https://v.vidxgo.co/`, `Origin: https://v.vidxgo.co`, `Sec-Fetch-Mode: cors`).
     * 2. Controlla il parametro di scadenza `e=<timestamp_ms>` (su VidxGo i token durano solo ~160 secondi!).
     * 3. Quando mancano meno di 40 secondi alla scadenza (o se è già scaduto), richiede in modo trasparente
     *    un nuovo token fresco a `v.vidxgo.co` e riscrive `t=` ed `e=` su tutti i segmenti `.ts` e manifest `.m3u8`
     *    (esattamente come fa `installUrlSigner()` via JS nel browser).
     * 4. Se il CDN risponde comunque HTTP 403/410, forza il refresh del token e ripete subito la richiesta.
     */
    object VidxgoTokenRefreshInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalReq = chain.request()
            val url = originalReq.url
            if (!url.host.contains("d2b.you", ignoreCase = true)) {
                return chain.proceed(originalReq)
            }

            val mediaInfo = extractD2bMediaKeyAndEmbedUrl(url.encodedPath)
            val now = System.currentTimeMillis()

            var targetUrl = url
            if (mediaInfo != null) {
                val (mediaKey, embedUrl) = mediaInfo
                val urlExpire = url.queryParameter("e")?.toLongOrNull() ?: 0L
                var cached = tokenCache[mediaKey]

                val bestExpire = maxOf(urlExpire, cached?.expireMs ?: 0L)
                // Se il token scade tra meno di 40 secondi (o è già scaduto), rigeneralo preventivamente
                if (bestExpire in 1L..(now + 40_000L)) {
                    cached = refreshTokenFromVidxgo(mediaKey, embedUrl, force = false)
                }

                val activeToken = cached ?: tokenCache[mediaKey]
                if (activeToken != null && activeToken.expireMs >= urlExpire && activeToken.token.isNotBlank()) {
                    targetUrl = url.newBuilder()
                        .setQueryParameter("t", activeToken.token)
                        .setQueryParameter("e", activeToken.expireMs.toString())
                        .build()
                }
            }

            val reqBuilder = originalReq.newBuilder().url(targetUrl)
            for ((k, v) in buildVidxgoCdnHeaders()) {
                reqBuilder.header(k, v)
            }

            val response = chain.proceed(reqBuilder.build())
            if ((response.code == 403 || response.code == 410) && mediaInfo != null) {
                val (mediaKey, embedUrl) = mediaInfo
                Log.w(TAG, "Ricevuto HTTP ${response.code} su segmento d2b.you ($mediaKey), refresh immediato token in corso...")
                val refreshed = refreshTokenFromVidxgo(mediaKey, embedUrl, force = true)
                if (refreshed != null) {
                    response.close()
                    val retryUrl = url.newBuilder()
                        .setQueryParameter("t", refreshed.token)
                        .setQueryParameter("e", refreshed.expireMs.toString())
                        .build()
                    val retryBuilder = originalReq.newBuilder().url(retryUrl)
                    for ((k, v) in buildVidxgoCdnHeaders()) {
                        retryBuilder.header(k, v)
                    }
                    return chain.proceed(retryBuilder.build())
                }
            }

            return response
        }
    }

    private fun extractD2bMediaKeyAndEmbedUrl(encodedPath: String): Pair<String, String>? {
        val tvMatcher = Pattern.compile("/hls/tv/(\\d+)/(\\d+)/(\\d+)/").matcher(encodedPath)
        if (tvMatcher.find()) {
            val imdb = tvMatcher.group(1)!!
            val s = tvMatcher.group(2)!!
            val e = tvMatcher.group(3)!!
            return ("tv/$imdb/$s/$e") to "$VIDXGO_BASE/$imdb/$s/$e?se=0&info=0"
        }
        val movieMatcher = Pattern.compile("/hls/(?:movie/)?(\\d+)/").matcher(encodedPath)
        if (movieMatcher.find()) {
            val imdb = movieMatcher.group(1)!!
            return ("movie/$imdb") to "$VIDXGO_BASE/$imdb?se=0&info=0"
        }
        return null
    }

    private fun cacheTokenFromStreamUrl(streamUrl: String) {
        val uri = runCatching { Uri.parse(streamUrl) }.getOrNull() ?: return
        val path = uri.path ?: return
        val mediaInfo = extractD2bMediaKeyAndEmbedUrl(path) ?: return
        val t = uri.getQueryParameter("t") ?: return
        val e = uri.getQueryParameter("e")?.toLongOrNull() ?: return
        if (t.isNotBlank() && e > 0L) {
            tokenCache[mediaInfo.first] = CachedVidxgoToken(token = t, expireMs = e)
        }
    }

    private fun refreshTokenFromVidxgo(mediaKey: String, embedUrl: String, force: Boolean): CachedVidxgoToken? {
        synchronized(tokenRefreshLock) {
            val now = System.currentTimeMillis()
            val existing = tokenCache[mediaKey]
            if (!force && existing != null && existing.expireMs > now + 45_000L) {
                return existing
            }
            if (existing != null && now - existing.refreshedAtMs < 3_000L) {
                return existing
            }
            return runCatching {
                val resolved = resolveFromVidxgoEmbed(embedUrl, "🍿 StreamingCommunity")
                cacheTokenFromStreamUrl(resolved.streamUrl)
                val updated = tokenCache[mediaKey]
                if (updated != null) {
                    val ttlSec = ((updated.expireMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
                    Log.i(TAG, "🔄 Token VidxGo aggiornato in background per $mediaKey (valido per altri ${ttlSec}s)")
                }
                updated
            }.onFailure { err ->
                Log.w(TAG, "Refresh token VidxGo fallito per $mediaKey: ${err.message}")
            }.getOrNull()
        }
    }

    fun isStreamingCommunityOrVixUrl(url: String): Boolean {
        val lower = url.lowercase().trim()
        return lower.contains("streamingcommunity") ||
            lower.contains("streaming-community") ||
            lower.contains("vidxgo.") ||
            lower.contains("d2b.you") ||
            lower.contains("vixcloud.") ||
            lower.contains("vixsrc.") ||
            (lower.contains("/watch/") && lower.contains("?e="))
    }

    /**
     * Header HTTP obbligatori per i CDN `d2b.you` usati da `v.vidxgo.co` / `streaming-community.biz`.
     * Senza `Sec-Fetch-Mode: cors` e `Origin: https://v.vidxgo.co`, nginx restituisce HTTP 403 (`ERROR_CODE_IO_BAD_HTTP_STATUS`).
     */
    fun buildVidxgoCdnHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$VIDXGO_BASE/",
        "Origin" to VIDXGO_BASE,
        "Accept" to "*/*",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site"
    )

    suspend fun resolveStream(
        context: Context,
        rawUrl: String,
        fallbackTitle: String = "🍿 StreamingCommunity"
    ): Result<ResolvedVixStream> = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim()
        val lowerUrl = cleanUrl.lowercase()

        // 1. Se è un URL CDN d2b.you (es. salvato in playlist dal Browser Sniffer):
        //    Rigeneriamo SEMPRE un token fresco tramite v.vidxgo.co se possibile, oppure applichiamo gli header Sec-Fetch-Mode: cors!
        if (lowerUrl.contains("d2b.you")) {
            val refreshed = runCatching { refreshD2bUrlViaVidxgo(cleanUrl, fallbackTitle) }.getOrNull()
            if (refreshed != null) {
                return@withContext Result.success(refreshed)
            }
            // Se il token nell'URL è ancora valido (appena catturato), restituiamolo con gli header obbligatori vidxgo
            return@withContext Result.success(
                ResolvedVixStream(
                    title = fallbackTitle,
                    streamUrl = cleanUrl,
                    headers = buildVidxgoCdnHeaders(),
                    resolutionLabel = "1080p FHD"
                )
            )
        }

        // 2. Se è un URL player v.vidxgo.co diretto (es. dal catalogo Android Auto o da iframe)
        if (lowerUrl.contains("vidxgo.co")) {
            val vidxResult = runCatching { resolveFromVidxgoEmbed(cleanUrl, fallbackTitle) }
            if (vidxResult.isSuccess) {
                return@withContext vidxResult
            }
            // Nessun ripiego su un altro episodio: riprodurre S01E01 al posto dell'episodio richiesto
            // salverebbe la posizione sotto la chiave sbagliata e confonderebbe la playlist.
            val epMatcher = Pattern.compile("v\\.vidxgo\\.co/(\\d+)/(\\d+)/(\\d+)").matcher(cleanUrl)
            val err = if (epMatcher.find()) {
                IllegalStateException(
                    "Episodio S${epMatcher.group(2)}E${epMatcher.group(3)} non ancora disponibile su StreamingCommunity",
                    vidxResult.exceptionOrNull()
                )
            } else {
                vidxResult.exceptionOrNull()
                    ?: IllegalStateException("Titolo non ancora disponibile su StreamingCommunity")
            }
            Log.w(TAG, "Impossibile risolvere v.vidxgo.co ($cleanUrl): ${err.message} (causa: ${err.cause?.message})")
            return@withContext Result.failure(err)
        }

        // 3. Se è una pagina di streaming-community.biz (es. /titles/27037-terminal-list-streaming)
        if (lowerUrl.contains("streaming-community.")) {
            val scBizResult = runCatching { resolveFromStreamingCommunityBizPage(cleanUrl, fallbackTitle) }
            if (scBizResult.isSuccess) {
                return@withContext scBizResult
            }
        }

        val parsedUri = Uri.parse(cleanUrl)
        val baseOrigin = if (parsedUri.scheme != null && parsedUri.host != null) {
            "${parsedUri.scheme}://${parsedUri.host}"
        } else {
            "https://vixsrc.to"
        }

        // 4. Se è già un flusso .m3u8 o playlist VixCloud diretta
        if (lowerUrl.contains(".m3u8") ||
            (lowerUrl.contains("vixcloud.") && lowerUrl.contains("/playlist/"))
        ) {
            val normalized = HeadlessStreamSniffer.normalizeStreamUrl(cleanUrl)
            val effectiveOrigin = if (lowerUrl.contains("vixcloud.") || lowerUrl.contains("vixsrc.")) {
                "https://vixsrc.to"
            } else {
                baseOrigin
            }
            return@withContext Result.success(
                ResolvedVixStream(
                    title = fallbackTitle,
                    streamUrl = normalized,
                    headers = mapOf(
                        "Referer" to "$effectiveOrigin/",
                        "Origin" to effectiveOrigin,
                        "User-Agent" to USER_AGENT,
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "cross-site"
                    )
                )
            )
        }

        // 5. Tentativo rapido via HTTP diretto (VixSrc / VixCloud / StreamingCommunity standard)
        val directAttempt = runCatching {
            resolveDirectHttp(cleanUrl, baseOrigin, fallbackTitle)
        }
        if (directAttempt.isSuccess) {
            return@withContext directAttempt
        }

        // 6. Fallback automatico con HeadlessStreamSniffer
        val sniffResult = HeadlessStreamSniffer.sniffStream(
            context = context,
            targetUrl = cleanUrl,
            referer = "$baseOrigin/",
            timeoutMs = 16_000L
        )
        sniffResult.map { sniffed ->
            val finalHeaders = if (sniffed.streamUrl.contains("d2b.you", ignoreCase = true)) {
                buildVidxgoCdnHeaders()
            } else {
                sniffed.headers
            }
            ResolvedVixStream(
                title = fallbackTitle,
                streamUrl = HeadlessStreamSniffer.normalizeStreamUrl(sniffed.streamUrl),
                headers = finalHeaders,
                resolutionLabel = "1080p FHD"
            )
        }
    }

    /**
     * Se l'utente ha in playlist un link `https://cdn.v1.media-67.d2b.you/hls/tv/11743610/1/1/index-v1-a1.m3u8?t=...`,
     * estrae `11743610/1/1` (o `/hls/6263850/`) e richiede un nuovo token fresco a `v.vidxgo.co`.
     */
    private fun refreshD2bUrlViaVidxgo(d2bUrl: String, fallbackTitle: String): ResolvedVixStream? {
        val tvMatcher = Pattern.compile("/hls/tv/(\\d+)/(\\d+)/(\\d+)/").matcher(d2bUrl)
        if (tvMatcher.find()) {
            val imdb = tvMatcher.group(1)
            val s = tvMatcher.group(2)
            val e = tvMatcher.group(3)
            val vidxUrl = "$VIDXGO_BASE/$imdb/$s/$e?se=0&info=0"
            return resolveFromVidxgoEmbed(vidxUrl, fallbackTitle)
        }

        val movieMatcher = Pattern.compile("/hls/(\\d+)/").matcher(d2bUrl)
        if (movieMatcher.find()) {
            val imdb = movieMatcher.group(1)
            val vidxUrl = "$VIDXGO_BASE/$imdb?se=0&info=0"
            return resolveFromVidxgoEmbed(vidxUrl, fallbackTitle)
        }
        return null
    }

    /**
     * Risolve una pagina `https://streaming-community.biz/titles/...` estraendo l'iframe `v.vidxgo.co`.
     */
    private fun resolveFromStreamingCommunityBizPage(pageUrl: String, fallbackTitle: String): ResolvedVixStream {
        val watchingUrl = if (pageUrl.contains("/watching")) {
            pageUrl
        } else {
            pageUrl.trimEnd('/') + "/watching"
        }
        val html = fetchHtml(watchingUrl, referer = "$SC_BIZ_BASE/")
        val vidxMatcher = Pattern.compile("(https://v\\.vidxgo\\.co/[^'\"\\s<>\\\\]+)").matcher(html)
        if (vidxMatcher.find()) {
            val rawEmbed = vidxMatcher.group(1)!!
                .replace("&amp;", "&")
                .replace("\\u0026", "&")
            return resolveFromVidxgoEmbed(rawEmbed, fallbackTitle)
        }

        // Fallback: cerca l'ID IMDb nella pagina principale del titolo
        val mainHtml = if (watchingUrl != pageUrl) fetchHtml(pageUrl, referer = "$SC_BIZ_BASE/") else html
        val imdbMatcher = Pattern.compile("v\\.vidxgo\\.co/(\\d+)").matcher(mainHtml)
        if (imdbMatcher.find()) {
            val imdb = imdbMatcher.group(1)!!
            val embedUrl = if (pageUrl.contains("serie", ignoreCase = true)) {
                "$VIDXGO_BASE/$imdb/1/1?se=0&info=0"
            } else {
                "$VIDXGO_BASE/$imdb/1/1?se=0&info=0"
            }
            return runCatching { resolveFromVidxgoEmbed(embedUrl, fallbackTitle) }
                .getOrElse { resolveFromVidxgoEmbed("$VIDXGO_BASE/$imdb?se=0&info=0", fallbackTitle) }
        }

        throw IllegalStateException("Iframe v.vidxgo.co non trovato in $pageUrl")
    }

    /**
     * Scarica l'embed `https://v.vidxgo.co/...` con `Sec-Fetch-Dest: iframe` e decodifica gli script XOR
     * `var k='...',d=atob('...')` per estrarre `let currentSrc = "https://cdn...d2b.you/hls/.../master.m3u8?t=..."`.
     */
    fun resolveFromVidxgoEmbed(embedUrl: String, fallbackTitle: String): ResolvedVixStream {
        val cleanEmbed = embedUrl
            .replace("&amp;", "&")
            .replace("content=trailer&", "")
            .replace("content=trailer", "")

        val req = Request.Builder()
            .url(cleanEmbed)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Referer", "$SC_BIZ_BASE/")
            .header("Sec-Fetch-Dest", "iframe")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "cross-site")
            .get()
            .build()

        val html = YouTubeStreamExtractor.okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} su v.vidxgo.co ($cleanEmbed)")
            }
            resp.body?.string() ?: ""
        }

        val docTitle = Jsoup.parse(html).title().trim()
        val resolvedTitle = if (docTitle.isNotBlank() && docTitle != "403" && !docTitle.equals("Player", ignoreCase = true)) {
            if (docTitle.startsWith("🍿")) docTitle else "🍿 $docTitle"
        } else {
            fallbackTitle
        }

        // Cerca gli script offuscati con XOR: var k='20274346',d=atob('...');
        val xorPattern = Pattern.compile("var\\s+k=['\"]([^'\"]+)['\"]\\s*,\\s*d=atob\\(['\"]([^'\"]+)['\"]\\)")
        val matcher = xorPattern.matcher(html)
        val srcPattern = Pattern.compile("let\\s+currentSrc\\s*=\\s*['\"]([^'\"]+)['\"]")

        while (matcher.find()) {
            val key = matcher.group(1) ?: continue
            val b64 = matcher.group(2) ?: continue
            val decodedBytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: continue
            val outBytes = ByteArray(decodedBytes.size)
            val keyLen = key.length
            for (i in decodedBytes.indices) {
                outBytes[i] = (decodedBytes[i].toInt() xor key[i % keyLen].code).toByte()
            }
            val decodedJs = String(outBytes, Charsets.UTF_8)
            val srcMatcher = srcPattern.matcher(decodedJs)
            if (srcMatcher.find()) {
                val rawSrc = srcMatcher.group(1)!!.replace("\\/", "/")
                if (rawSrc.startsWith("http")) {
                    cacheTokenFromStreamUrl(rawSrc)
                    return ResolvedVixStream(
                        title = resolvedTitle,
                        streamUrl = rawSrc,
                        headers = buildVidxgoCdnHeaders(),
                        resolutionLabel = "1080p FHD"
                    )
                }
            }
        }

        // Fallback se c'è un .m3u8 in chiaro nell'HTML
        val directM3u8 = Pattern.compile("(https?://[^'\"\\s\\\\]+\\.m3u8[^'\"\\s\\\\]*)").matcher(html)
        if (directM3u8.find()) {
            val url = directM3u8.group(1)!!.replace("\\/", "/")
            cacheTokenFromStreamUrl(url)
            return ResolvedVixStream(
                title = resolvedTitle,
                streamUrl = url,
                headers = buildVidxgoCdnHeaders(),
                resolutionLabel = "1080p FHD"
            )
        }

        throw IllegalStateException("Episodio o film non ancora disponibile su StreamingCommunity ($cleanEmbed)")
    }

    /** Conteggio episodi per stagione dichiarato nello script del player (`{"n":1,"count":10}`). */
    internal fun parseSeasonCounts(decodedJs: String): Map<Int, Int> {
        if (!decodedJs.contains("\"seasons\"")) return emptyMap()
        val counts = linkedMapOf<Int, Int>()
        val m = Pattern.compile("\\{\\s*\"n\"\\s*:\\s*(\\d+)\\s*,\\s*\"count\"\\s*:\\s*(\\d+)\\s*\\}").matcher(decodedJs)
        while (m.find()) {
            val season = m.group(1)?.toIntOrNull() ?: continue
            val count = m.group(2)?.toIntOrNull() ?: continue
            if (season > 0) counts[season] = count
        }
        return counts
    }

    /**
     * Estrae dal player `v.vidxgo.co` il numero di episodi disponibili per ciascuna stagione,
     * decodificando la variabile `const SHOW = {...,"seasons":[{"n":1,"count":10},...]}` nello script XOR.
     */
    fun extractSeasonCountsFromVidxgo(numericImdb: String): Map<Int, Int> {
        val embedUrl = "$VIDXGO_BASE/$numericImdb/1/1?se=0&info=0"
        val req = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Referer", "$SC_BIZ_BASE/")
            .header("Sec-Fetch-Dest", "iframe")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "cross-site")
            .get()
            .build()

        val html = YouTubeStreamExtractor.okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyMap()
            resp.body?.string() ?: ""
        }

        val xorPattern = Pattern.compile("var\\s+k=['\"]([^'\"]+)['\"]\\s*,\\s*d=atob\\(['\"]([^'\"]+)['\"]\\)")
        val matcher = xorPattern.matcher(html)
        while (matcher.find()) {
            val key = matcher.group(1) ?: continue
            val b64 = matcher.group(2) ?: continue
            val decodedBytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: continue
            val outBytes = ByteArray(decodedBytes.size)
            val keyLen = key.length
            for (i in decodedBytes.indices) {
                outBytes[i] = (decodedBytes[i].toInt() xor key[i % keyLen].code).toByte()
            }
            val counts = parseSeasonCounts(String(outBytes, Charsets.UTF_8))
            if (counts.isNotEmpty()) return counts
        }
        return emptyMap()
    }

    private fun resolveDirectHttp(
        cleanUrl: String,
        baseOrigin: String,
        fallbackTitle: String
    ): ResolvedVixStream {
        val parsedUri = Uri.parse(cleanUrl)

        if (cleanUrl.contains("vixsrc.", ignoreCase = true) &&
            (cleanUrl.contains("/movie/") || cleanUrl.contains("/tv/"))
        ) {
            val pathPart = parsedUri.path ?: ""
            val apiUrl = "$baseOrigin/api$pathPart"
            val apiJson = fetchHtml(apiUrl, referer = "$baseOrigin/")
            if (apiJson.trim().startsWith("{")) {
                val src = JSONObject(apiJson).optString("src", "").trimStart('/')
                if (src.isNotBlank()) {
                    val embedUrl = "$baseOrigin/$src"
                    return extractFromVixCloudEmbed(embedUrl, "$baseOrigin/", fallbackTitle)
                }
            }
            return extractFromVixCloudEmbed(cleanUrl, "$baseOrigin/", fallbackTitle)
        }

        if (cleanUrl.contains("vixcloud.", ignoreCase = true) || cleanUrl.contains("vixsrc.", ignoreCase = true)) {
            return extractFromVixCloudEmbed(cleanUrl, cleanUrl, fallbackTitle)
        }

        val titlesMatcher = Pattern.compile("/titles/(\\d+)").matcher(cleanUrl)
        val watchMatcher = Pattern.compile("/watch/(\\d+)").matcher(cleanUrl)
        val episodeParam = parsedUri.getQueryParameter("e")

        val firstPageHtml = fetchHtml(cleanUrl, referer = "$baseOrigin/")
        val doc = Jsoup.parse(firstPageHtml, cleanUrl)
        var pageTitle = doc.title()
            .replace("StreamingCommunity", "", ignoreCase = true)
            .replace(" - ", " ")
            .trim()
            .ifBlank { fallbackTitle }

        val appDiv = doc.selectFirst("#app[data-page]")
        if (appDiv != null) {
            val dataPageRaw = appDiv.attr("data-page")
            val decodedJson = Html.fromHtml(dataPageRaw, Html.FROM_HTML_MODE_LEGACY).toString()
            val rootObj = runCatching { JSONObject(decodedJson) }.getOrNull()
            val props = rootObj?.optJSONObject("props")
            if (props != null) {
                val titleObj = props.optJSONObject("title")
                val name = titleObj?.optString("name")
                if (!name.isNullOrBlank()) {
                    pageTitle = "🍿 $name"
                }

                val embedUrl = props.optString("embed_url").takeIf { it.isNotBlank() }
                if (embedUrl != null) {
                    val fullEmbed = if (embedUrl.startsWith("http")) embedUrl else "$baseOrigin$embedUrl"
                    val resolvedEmbed = resolveIframeToEmbedUrl(fullEmbed, cleanUrl)
                    return extractFromVixCloudEmbed(resolvedEmbed, cleanUrl, pageTitle)
                }
            }
        }

        val iframeEl = doc.selectFirst("iframe[src]")
        if (iframeEl != null) {
            val iframeSrc = iframeEl.absUrl("src").ifBlank { iframeEl.attr("src") }
            if (iframeSrc.contains("vidxgo.co", ignoreCase = true)) {
                return resolveFromVidxgoEmbed(iframeSrc, pageTitle)
            }
            val resolvedEmbed = resolveIframeToEmbedUrl(iframeSrc, cleanUrl)
            return extractFromVixCloudEmbed(resolvedEmbed, cleanUrl, pageTitle)
        }

        val mediaId = when {
            watchMatcher.find() -> watchMatcher.group(1)
            titlesMatcher.find() -> titlesMatcher.group(1)
            else -> null
        }

        if (mediaId != null) {
            val langPrefix = if (cleanUrl.contains("/it/")) "/it" else ""
            val epQuery = if (!episodeParam.isNullOrBlank()) "?e=$episodeParam&next_episode=1" else ""
            val watchOrIframeUrl = "$baseOrigin$langPrefix/iframe/$mediaId$epQuery"
            val resolvedEmbed = resolveIframeToEmbedUrl(watchOrIframeUrl, cleanUrl)
            return extractFromVixCloudEmbed(resolvedEmbed, cleanUrl, pageTitle)
        }

        throw IllegalStateException("Embed VixCloud non trovato direttamente")
    }

    private fun resolveIframeToEmbedUrl(iframeUrl: String, referer: String): String {
        if (iframeUrl.contains("vixcloud.", ignoreCase = true) ||
            iframeUrl.contains("vixsrc.", ignoreCase = true) ||
            iframeUrl.contains("vidxgo.", ignoreCase = true)
        ) {
            return iframeUrl
        }
        val html = fetchHtml(iframeUrl, referer)
        val doc = Jsoup.parse(html, iframeUrl)
        val innerIframe = doc.selectFirst("iframe[src]")
        if (innerIframe != null) {
            val src = innerIframe.absUrl("src").ifBlank { innerIframe.attr("src") }
            if (src.isNotBlank()) return src
        }
        return iframeUrl
    }

    private fun extractFromVixCloudEmbed(
        embedUrl: String,
        refererUrl: String,
        fallbackTitle: String
    ): ResolvedVixStream {
        if (embedUrl.contains("vidxgo.co", ignoreCase = true)) {
            return resolveFromVidxgoEmbed(embedUrl, fallbackTitle)
        }
        val html = fetchHtml(embedUrl, referer = refererUrl)
        val embedUri = Uri.parse(embedUrl)
        val embedOrigin = "${embedUri.scheme ?: "https"}://${embedUri.host ?: "vixsrc.to"}"
        val headers = mapOf(
            "Referer" to "$embedOrigin/",
            "Origin" to embedOrigin,
            "User-Agent" to USER_AGENT,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site"
        )

        val parsedFromScript = HeadlessStreamSniffer.parseVixCloudScript(html)
        if (parsedFromScript != null) {
            return ResolvedVixStream(
                title = fallbackTitle,
                streamUrl = parsedFromScript,
                headers = headers,
                resolutionLabel = "1080p FHD"
            )
        }

        val m3u8Matcher = Pattern.compile("(https?://[^'\"\\s]+\\.m3u8[^'\"\\s]*)").matcher(html)
        if (m3u8Matcher.find()) {
            val directM3u8 = m3u8Matcher.group(1)!!
            return ResolvedVixStream(
                title = fallbackTitle,
                streamUrl = HeadlessStreamSniffer.normalizeStreamUrl(directM3u8),
                headers = headers,
                resolutionLabel = "HLS"
            )
        }

        throw IllegalStateException("Token VixCloud non trovato nell'HTML diretto")
    }

    private fun fetchHtml(url: String, referer: String): String {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8")
            .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
        if (referer.isNotBlank()) {
            reqBuilder.header("Referer", referer)
        }

        YouTubeStreamExtractor.okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} su $url")
            }
            return resp.body?.string() ?: ""
        }
    }
}
