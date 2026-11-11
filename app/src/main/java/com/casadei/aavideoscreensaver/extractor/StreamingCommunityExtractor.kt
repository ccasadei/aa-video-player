package com.casadei.aavideoscreensaver.extractor

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Estrattore per StreamingCommunity (`streamingcommunityz.nexus` / VixCloud `vixcloud.cc`,
 * `streaming-community.toys` / `v.vidxgo.co` / `d2b.you` e VixSrc).
 *
 * Supporta:
 * 1. Pagine e watch URL di StreamingCommunity ufficiale (`streamingcommunityz.nexus/it/watch/{id}`, `/it/titles/{id}-{slug}`)
 *    estraendo direttamente il master playlist FHD da `vixcloud.cc/embed/...`.
 * 2. Player `v.vidxgo.co/{imdb}` e `v.vidxgo.co/{imdb}/{s}/{e}` (decodifica XOR istantanea + Referer `streaming-community.toys`).
 * 3. Flussi `.m3u8` `d2b.you/hls/...` anche già salvati in playlist con token scaduto (rigenera automaticamente il token).
 * 4. Pagine `streaming-community.biz` / `streaming-community.toys/titles/...` (estrae l'iframe `v.vidxgo.co` da `/watching`).
 * 5. URL VixSrc / VixCloud (`vixcloud.cc`, `vixcloud.co`, `vixsrc.to`) con fallback automatico a `HeadlessStreamSniffer`.
 */
object StreamingCommunityExtractor {

    private const val TAG = "AAVideo"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val DEFAULT_VIDXGO_REFERER = "https://streaming-community.toys"
    private const val VIDXGO_BASE = "https://v.vidxgo.co"

    /**
     * Dominio frontend corrente per `v.vidxgo.co` (es. `https://streaming-community.toys`),
     * usato da [VidxgoTokenRefreshInterceptor] per il refresh dei token VidxGo.
     */
    @Volatile
    private var currentVidxgoReferer: String = DEFAULT_VIDXGO_REFERER

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
     *    un nuovo token fresco a `v.vidxgo.co` e riscrive `t=` ed `e=` su tutti i segmenti `.ts` e manifest `.m3u8`.
     * 4. Se il CDN risponde comunque HTTP 403/410, forza il refresh del token e ripete subito la richiesta.
     */
    object VidxgoTokenRefreshInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalReq = chain.request()
            val url = originalReq.url

            // Gestione flussi VixCloud / VixSrc (usati da streamingcommunityz.nexus e streamingunity.dog):
            // aggiunge UA/Cookie della WebView e, se Cloudflare blocca OkHttp con 403/503 su playlist o enc.key,
            // esegue il fetch same-origin dentro la WebView Chromium.
            if (url.host.contains("vixcloud.", ignoreCase = true) ||
                url.host.contains("vixsrc.", ignoreCase = true)
            ) {
                val origin = "${url.scheme}://${url.host}"
                val ua = CloudflareWebViewBridge.getCleanUserAgent()
                val cookie = runCatching { CookieManager.getInstance().getCookie(url.toString()) }.getOrNull()
                val vixReqBuilder = originalReq.newBuilder()
                    .header("User-Agent", ua)
                    .header("Referer", "$origin/")
                    .header("Origin", origin)
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                if (!cookie.isNullOrBlank()) {
                    vixReqBuilder.header("Cookie", cookie)
                }
                var resp = chain.proceed(vixReqBuilder.build())
                // Se la playlist aveva h=1 (es. salvata da una sessione precedente) ma il titolo non ha 1080p,
                // VixCloud risponde 403: riprova subito rimuovendo h=1.
                if (resp.code == 403 && url.queryParameter("h") != null) {
                    resp.close()
                    val urlWithoutH = url.newBuilder().removeAllQueryParameters("h").build()
                    Log.i(TAG, "Riprovo playlist VixCloud senza h=1: $urlWithoutH")
                    resp = chain.proceed(vixReqBuilder.url(urlWithoutH).build())
                }
                if ((resp.code == 403 || resp.code == 503) && Looper.myLooper() != Looper.getMainLooper()) {
                    Log.w(TAG, "Ricevuto HTTP ${resp.code} da OkHttp su VixCloud ($url), passo a fetch WebView...")
                    val webResp = CloudflareWebViewBridge.fetchBytesViaVixCloudWebViewBlocking(
                        url = url.toString(),
                        referer = "$origin/"
                    )
                    if (webResp != null && webResp.status in 200..299 && webResp.bytes.isNotEmpty()) {
                        resp.close()
                        Log.i(TAG, "VixCloud fetch via WebView riuscito (${webResp.bytes.size} byte) per $url")
                        val mediaType = webResp.contentType.toMediaTypeOrNull()
                        return Response.Builder()
                            .request(originalReq)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Content-Type", webResp.contentType)
                            .body(webResp.bytes.toResponseBody(mediaType))
                            .build()
                    }
                }
                return resp
            }

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
                if (bestExpire in 1L..(now + 40_000L)) {
                    cached = refreshTokenFromVidxgo(mediaKey, embedUrl, force = false, currentVidxgoReferer)
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
                val refreshed = refreshTokenFromVidxgo(mediaKey, embedUrl, force = true, currentVidxgoReferer)
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

    private fun refreshTokenFromVidxgo(
        mediaKey: String,
        embedUrl: String,
        force: Boolean,
        vidxgoReferer: String
    ): CachedVidxgoToken? {
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
                val resolved = kotlinx.coroutines.runBlocking {
                    resolveFromVidxgoEmbed(embedUrl, "🍿 StreamingCommunity", vidxgoReferer)
                }
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
            lower.contains("streamingunity") ||
            lower.contains("streaming-community") ||
            lower.contains("vidxgo.") ||
            lower.contains("d2b.you") ||
            lower.contains("vixcloud.") ||
            lower.contains("vixsrc.") ||
            (lower.contains("/watch/") && lower.contains("?e="))
    }

    /**
     * Se un link in playlist punta a un dominio `streamingcommunity*` o `streamingunity*`,
     * sostituisce il dominio con quello candidato risolto da `streaming-community.day`.
     */
    internal fun rewriteStreamingCommunityDomain(url: String, currentBaseUrl: String): String {
        val cleanBase = currentBaseUrl.trim().trimEnd('/')
        if (!cleanBase.startsWith("http")) return url.trim()
        val m = Regex("""^(https?://[^/?#]+)(.*)$""", RegexOption.IGNORE_CASE).find(url.trim()) ?: return url.trim()
        val hostPart = m.groupValues[1].lowercase()
        val pathAndQuery = m.groupValues[2]
        return if (hostPart.contains("streamingcommunity") || hostPart.contains("streamingunity")) {
            "$cleanBase$pathAndQuery"
        } else {
            url.trim()
        }
    }

    /**
     * Header HTTP obbligatori per i CDN `d2b.you` usati da `v.vidxgo.co`.
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
        CloudflareWebViewBridge.init(context)
        val candidateBases = StreamingCommunityUrlResolver.getCandidateBaseUrls()
        val baseUrl = candidateBases.firstOrNull() ?: StreamingCommunityUrlResolver.getCurrentBaseUrl()
        val vidxgoReferer = StreamingCommunityUrlResolver.getCurrentVidxgoRefererBaseUrl()
        currentVidxgoReferer = vidxgoReferer

        val cleanUrl = rewriteStreamingCommunityDomain(rawUrl, baseUrl)
        val lowerUrl = cleanUrl.lowercase()

        // 1. Se è un URL CDN d2b.you (es. salvato in playlist):
        //    Rigeneriamo SEMPRE un token fresco tramite v.vidxgo.co (con Referer streaming-community.toys)
        if (lowerUrl.contains("d2b.you")) {
            val refreshed = runCatching { refreshD2bUrlViaVidxgo(cleanUrl, fallbackTitle, vidxgoReferer) }.getOrNull()
            if (refreshed != null) {
                return@withContext Result.success(refreshed)
            }
            return@withContext Result.success(
                ResolvedVixStream(
                    title = fallbackTitle,
                    streamUrl = cleanUrl,
                    headers = buildVidxgoCdnHeaders(),
                    resolutionLabel = "1080p FHD"
                )
            )
        }

        // 2. Se è un URL player v.vidxgo.co diretto (richiede Referer streaming-community.toys, NON streamingcommunityz.nexus!)
        if (lowerUrl.contains("vidxgo.co")) {
            val vidxResult = runCatching { resolveFromVidxgoEmbed(context, cleanUrl, fallbackTitle, vidxgoReferer) }
            if (vidxResult.isSuccess) {
                return@withContext vidxResult
            }
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

        // 3. Se è una pagina di streaming-community.biz / streaming-community.toys (es. /titles/27037-terminal-list-streaming)
        if (lowerUrl.contains("streaming-community.")) {
            val scBizResult = runCatching { resolveFromStreamingCommunityBizPage(cleanUrl, fallbackTitle, vidxgoReferer) }
            if (scBizResult.isSuccess) {
                return@withContext scBizResult
            }
        }

        val parsedUri = Uri.parse(cleanUrl)
        val baseOrigin = if (parsedUri.scheme != null && parsedUri.host != null) {
            "${parsedUri.scheme}://${parsedUri.host}"
        } else {
            baseUrl
        }

        // 4. Se è già un flusso .m3u8 o playlist VixCloud diretta
        if (lowerUrl.contains(".m3u8") ||
            (lowerUrl.contains("vixcloud.") && lowerUrl.contains("/playlist/"))
        ) {
            val normalized = HeadlessStreamSniffer.normalizeStreamUrl(cleanUrl)
            val effectiveOrigin = when {
                lowerUrl.contains("vixsrc.") -> "https://vixsrc.to"
                parsedUri.scheme != null && parsedUri.host != null -> "${parsedUri.scheme}://${parsedUri.host}"
                else -> "https://vixcloud.cc"
            }
            return@withContext Result.success(
                ResolvedVixStream(
                    title = fallbackTitle,
                    streamUrl = normalized,
                    headers = mapOf(
                        "Referer" to "$effectiveOrigin/",
                        "Origin" to effectiveOrigin,
                        "User-Agent" to CloudflareWebViewBridge.getCleanUserAgent(context),
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "cross-site"
                    )
                )
            )
        }

        // 5. Tentativo sui domini candidati prelevati da streaming-community.day (.nexus, .dog, ecc.)
        val isScDomain = rawUrl.lowercase().let {
            it.contains("streamingcommunity") || it.contains("streamingunity")
        }
        if (isScDomain) {
            for (candidateBase in candidateBases) {
                val candidateUrl = rewriteStreamingCommunityDomain(rawUrl, candidateBase)
                val cUri = Uri.parse(candidateUrl)
                val candidateOrigin = if (cUri.scheme != null && cUri.host != null) {
                    "${cUri.scheme}://${cUri.host}"
                } else {
                    candidateBase
                }
                val directAttempt = runCatching {
                    resolveDirectHttp(candidateUrl, candidateOrigin, fallbackTitle, vidxgoReferer)
                }
                if (directAttempt.isSuccess) {
                    val resolved = directAttempt.getOrThrow()
                    StreamingCommunityUrlResolver.markUrlWorking(candidateBase)
                    Log.i(TAG, "StreamingCommunity risolto via $candidateBase: ${resolved.streamUrl.take(90)}")
                    return@withContext directAttempt
                } else {
                    Log.w(TAG, "resolveDirectHttp fallito su $candidateBase ($candidateUrl): ${directAttempt.exceptionOrNull()?.message}")
                }
            }
        } else {
            val directAttempt = runCatching {
                resolveDirectHttp(cleanUrl, baseOrigin, fallbackTitle, vidxgoReferer)
            }
            if (directAttempt.isSuccess) {
                val resolved = directAttempt.getOrThrow()
                Log.i(TAG, "StreamingCommunity risolto via HTTP diretto: ${resolved.streamUrl.take(90)}")
                return@withContext directAttempt
            } else {
                Log.w(TAG, "resolveDirectHttp fallito per $cleanUrl: ${directAttempt.exceptionOrNull()?.message}")
            }
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

    private suspend fun refreshD2bUrlViaVidxgo(
        d2bUrl: String,
        fallbackTitle: String,
        vidxgoReferer: String
    ): ResolvedVixStream? {
        val tvMatcher = Pattern.compile("/hls/tv/(\\d+)/(\\d+)/(\\d+)/").matcher(d2bUrl)
        if (tvMatcher.find()) {
            val imdb = tvMatcher.group(1)
            val s = tvMatcher.group(2)
            val e = tvMatcher.group(3)
            val vidxUrl = "$VIDXGO_BASE/$imdb/$s/$e?se=0&info=0"
            return resolveFromVidxgoEmbed(vidxUrl, fallbackTitle, vidxgoReferer)
        }

        val movieMatcher = Pattern.compile("/hls/(\\d+)/").matcher(d2bUrl)
        if (movieMatcher.find()) {
            val imdb = movieMatcher.group(1)
            val vidxUrl = "$VIDXGO_BASE/$imdb?se=0&info=0"
            return resolveFromVidxgoEmbed(vidxUrl, fallbackTitle, vidxgoReferer)
        }
        return null
    }

    /**
     * Risolve una pagina `https://streaming-community.toys/titles/...` estraendo l'iframe `v.vidxgo.co`.
     */
    suspend fun resolveFromStreamingCommunityBizPage(
        pageUrl: String,
        fallbackTitle: String,
        vidxgoReferer: String
    ): ResolvedVixStream {
        val rewrittenPageUrl = pageUrl.replace("https://streaming-community.biz", vidxgoReferer)
        val watchingUrl = if (rewrittenPageUrl.contains("/watching")) {
            rewrittenPageUrl
        } else {
            rewrittenPageUrl.trimEnd('/') + "/watching"
        }
        val html = fetchHtml(watchingUrl, referer = "$vidxgoReferer/")
        val vidxMatcher = Pattern.compile("(https://v\\.vidxgo\\.co/[^'\"\\s<>\\\\]+)").matcher(html)
        if (vidxMatcher.find()) {
            val rawEmbed = vidxMatcher.group(1)!!
                .replace("&amp;", "&")
                .replace("\\u0026", "&")
            return resolveFromVidxgoEmbed(rawEmbed, fallbackTitle, vidxgoReferer)
        }

        val mainHtml = if (watchingUrl != rewrittenPageUrl) fetchHtml(rewrittenPageUrl, referer = "$vidxgoReferer/") else html
        val imdbMatcher = Pattern.compile("v\\.vidxgo\\.co/(\\d+)").matcher(mainHtml)
        if (imdbMatcher.find()) {
            val imdb = imdbMatcher.group(1)!!
            val embedUrl = "$VIDXGO_BASE/$imdb/1/1?se=0&info=0"
            return runCatching { resolveFromVidxgoEmbed(embedUrl, fallbackTitle, vidxgoReferer) }
                .getOrElse { resolveFromVidxgoEmbed("$VIDXGO_BASE/$imdb?se=0&info=0", fallbackTitle, vidxgoReferer) }
        }

        throw IllegalStateException("Iframe v.vidxgo.co non trovato in $pageUrl")
    }

    /**
     * Scarica l'embed `https://v.vidxgo.co/...` con `Referer: https://streaming-community.toys/`
     * e decodifica gli script XOR `var k='...',d=atob('...')` per estrarre il master `.m3u8`.
     */
    suspend fun resolveFromVidxgoEmbed(
        context: Context?,
        embedUrl: String,
        fallbackTitle: String,
        vidxgoReferer: String
    ): ResolvedVixStream {
        val cleanEmbed = embedUrl
            .replace("&amp;", "&")
            .replace("content=trailer&", "")
            .replace("content=trailer", "")

        val okHttpResult = runCatching {
            resolveFromVidxgoEmbedViaHttp(cleanEmbed, fallbackTitle, vidxgoReferer)
        }
        if (okHttpResult.isSuccess) {
            return okHttpResult.getOrThrow()
        }

        Log.w(TAG, "OkHttp bloccato per v.vidxgo.co ($cleanEmbed), provo con WebView...")

        val appCtx = context?.applicationContext
        if (appCtx == null) {
            val httpErr = okHttpResult.exceptionOrNull()
            throw httpErr ?: IllegalStateException("Impossibile risolvere v.vidxgo.co ($cleanEmbed)")
        }

        try {
            return resolveFromVidxgoEmbedViaWebView(appCtx, cleanEmbed, fallbackTitle, vidxgoReferer)
        } catch (e: Exception) {
            Log.w(TAG, "WebView fallita per v.vidxgo.co ($cleanEmbed): ${e.message}")
            val httpErr = okHttpResult.exceptionOrNull()
            throw httpErr ?: e
        }
    }

    suspend fun resolveFromVidxgoEmbed(
        embedUrl: String,
        fallbackTitle: String,
        vidxgoReferer: String
    ): ResolvedVixStream = resolveFromVidxgoEmbed(null, embedUrl, fallbackTitle, vidxgoReferer)

    private fun resolveFromVidxgoEmbedViaHttp(
        cleanEmbed: String,
        fallbackTitle: String,
        vidxgoReferer: String
    ): ResolvedVixStream {
        val req = Request.Builder()
            .url(cleanEmbed)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Referer", "${vidxgoReferer.trimEnd('/')}/")
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

        return extractM3u8FromHtml(html, resolvedTitle, fallbackTitle)
    }

    private suspend fun resolveFromVidxgoEmbedViaWebView(
        context: Context,
        embedUrl: String,
        fallbackTitle: String,
        vidxgoReferer: String
    ): ResolvedVixStream = withContext(Dispatchers.Main) {
        val startMs = System.currentTimeMillis()
        Log.i(TAG, "WebView resolve v.vidxgo.co: $embedUrl")

        val deferred = CompletableDeferred<ResolvedVixStream>()
        val appCtx = context.applicationContext

        val wv = android.webkit.WebView(appCtx)
        wv.visibility = android.view.View.INVISIBLE
        wv.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1280, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(720, android.view.View.MeasureSpec.EXACTLY)
        )
        wv.layout(0, 0, 1280, 720)

        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, true)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = USER_AGENT
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }

        var resolvedTitle = fallbackTitle

        wv.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                if (deferred.isCompleted) return
                val title = view?.title?.trim()
                if (title != null && title.isNotBlank() && title != "403" && !title.equals("Player", ignoreCase = true)) {
                    resolvedTitle = if (title.startsWith("🍿")) title else "🍿 $title"
                }

                val js = """
                    (function() {
                        try {
                            var scripts = document.getElementsByTagName('script');
                            for (var i = 0; i < scripts.length; i++) {
                                var src = scripts[i].textContent || scripts[i].innerText;
                                if (!src) continue;
                                var m = src.match(/var\s+k=['"]([^'"]+)['"]\s*,\s*d=atob\(['"]([^'"]+)['"]\)/);
                                if (m) {
                                    var key = m[1];
                                    var b64 = m[2];
                                    var decoded = atob(b64);
                                    var out = '';
                                    for (var j = 0; j < decoded.length; j++) {
                                        out += String.fromCharCode(decoded.charCodeAt(j) ^ key.charCodeAt(j % key.length));
                                    }
                                    var srcMatch = out.match(/let\s+currentSrc\s*=\s*['"]([^'"]+)['"]/);
                                    if (srcMatch) {
                                        return JSON.stringify({type: 'currentSrc', url: srcMatch[1]});
                                    }
                                    var m3u8Match = out.match(/https?:\/\/[^'"\s]+\.m3u8[^'"\s]*/);
                                    if (m3u8Match) {
                                        return JSON.stringify({type: 'm3u8', url: m3u8Match[0]});
                                    }
                                }
                            }
                            var htmlMatch = document.body ? document.body.innerHTML.match(/(https?:\/\/[^'"\s]+\.m3u8[^'"\s]*)/) : null;
                            if (htmlMatch) {
                                return JSON.stringify({type: 'm3u8', url: htmlMatch[1]});
                            }
                            return JSON.stringify({type: 'none'});
                        } catch(e) {
                            return JSON.stringify({type: 'error', error: e.message});
                        }
                    })();
                """.trimIndent()

                wv.evaluateJavascript(js) { result ->
                    if (deferred.isCompleted) return@evaluateJavascript
                    try {
                        val unquoted = if (result != null && result.startsWith("\"") && result.endsWith("\"")) {
                            org.json.JSONTokener(result).nextValue().toString()
                        } else {
                            result ?: "{}"
                        }
                        val json = JSONObject(unquoted)
                        val type = json.optString("type", "none")
                        val streamUrl = json.optString("url", "").replace("\\/", "/")
                        when {
                            type in listOf("currentSrc", "m3u8") && streamUrl.startsWith("http") -> {
                                cacheTokenFromStreamUrl(streamUrl)
                                deferred.complete(
                                    ResolvedVixStream(
                                        title = resolvedTitle,
                                        streamUrl = streamUrl,
                                        headers = buildVidxgoCdnHeaders(),
                                        resolutionLabel = "1080p FHD"
                                    )
                                )
                            }
                            else -> {
                                deferred.completeExceptionally(
                                    IllegalStateException("Nessun flusso trovato in v.vidxgo.co ($embedUrl)")
                                )
                            }
                        }
                    } catch (e: Exception) {
                        deferred.completeExceptionally(
                            IllegalStateException("Parsing WebView result failed: $result (${e.message})")
                        )
                    }
                }
            }

            override fun onReceivedError(
                view: android.webkit.WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true && !deferred.isCompleted) {
                    val errMsg = "WebView load error: ${error?.description} (code: ${error?.errorCode})"
                    deferred.completeExceptionally(IllegalStateException(errMsg))
                }
            }
        }

        wv.loadUrl(
            embedUrl,
            mapOf(
                "Referer" to "${vidxgoReferer.trimEnd('/')}/",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site"
            )
        )

        try {
            val result = withTimeoutOrNull(20_000L) { deferred.await() }
                ?: throw IllegalStateException("WebView timeout dopo 20s per v.vidxgo.co ($embedUrl)")
            val elapsed = System.currentTimeMillis() - startMs
            Log.i(TAG, "WebView resolve OK in ${elapsed}ms: ${result.streamUrl.take(80)}")
            result
        } finally {
            runCatching {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.removeAllViews()
                wv.destroy()
            }
        }
    }

    private fun extractM3u8FromHtml(
        html: String,
        resolvedTitle: String,
        fallbackTitle: String
    ): ResolvedVixStream {
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

        throw IllegalStateException("Episodio o film non ancora disponibile su StreamingCommunity")
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
    fun extractSeasonCountsFromVidxgo(
        numericImdb: String,
        vidxgoReferer: String = DEFAULT_VIDXGO_REFERER
    ): Map<Int, Int> {
        val embedUrl = "$VIDXGO_BASE/$numericImdb/1/1?se=0&info=0"
        val req = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Referer", "${vidxgoReferer.trimEnd('/')}/")
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

    private suspend fun resolveDirectHttp(
        cleanUrl: String,
        baseOrigin: String,
        fallbackTitle: String,
        vidxgoReferer: String
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
                    return extractFromVixCloudEmbed(embedUrl, "$baseOrigin/", fallbackTitle, vidxgoReferer)
                }
            }
            return extractFromVixCloudEmbed(cleanUrl, "$baseOrigin/", fallbackTitle, vidxgoReferer)
        }

        if (cleanUrl.contains("vixcloud.", ignoreCase = true) || cleanUrl.contains("vixsrc.", ignoreCase = true)) {
            return extractFromVixCloudEmbed(cleanUrl, cleanUrl, fallbackTitle, vidxgoReferer)
        }

        val titlesMatcher = Pattern.compile("/titles/(\\d+)").matcher(cleanUrl)
        val watchMatcher = Pattern.compile("/watch/(\\d+)").matcher(cleanUrl)
        val episodeParam = parsedUri.getQueryParameter("e")
            ?: parsedUri.getQueryParameter("episode_id")

        // Percorso rapido per /watch/{id} (es. https://streamingcommunityz.nexus/it/watch/66558 o /watch/12264?e=90021):
        // interroga direttamente l'endpoint leggero /it/iframe/{id} (~1.7 KB) evitando il download della pagina intera (~318 KB)
        if (watchMatcher.find()) {
            val mediaId = watchMatcher.group(1)!!
            val langPrefix = if (cleanUrl.contains("/it/")) "/it" else ""
            val epQuery = if (!episodeParam.isNullOrBlank()) "?episode_id=$episodeParam&next_episode=1" else ""
            val directIframeUrl = "$baseOrigin$langPrefix/iframe/$mediaId$epQuery"
            val fastAttempt = runCatching {
                val resolvedEmbed = resolveIframeToEmbedUrl(directIframeUrl, cleanUrl)
                extractFromVixCloudEmbed(resolvedEmbed, "$baseOrigin/", fallbackTitle, vidxgoReferer)
            }
            if (fastAttempt.isSuccess) {
                return fastAttempt.getOrThrow()
            }
        }

        val firstPageHtml = fetchHtml(cleanUrl, referer = "$baseOrigin/")
        val doc = Jsoup.parse(firstPageHtml, cleanUrl)
        var pageTitle = doc.title()
            .replace("StreamingCommunity", "", ignoreCase = true)
            .replace(" - ", " ")
            .trim()
            .ifBlank { fallbackTitle }

        var fallbackEpisodeId: String? = episodeParam
        val appDiv = doc.selectFirst("#app[data-page]")
        if (appDiv != null) {
            // Jsoup decodifica già le entità HTML (&quot; -> ") in attr("data-page")
            val decodedJson = appDiv.attr("data-page")
            val rootObj = runCatching { JSONObject(decodedJson) }.getOrNull()
            val props = rootObj?.optJSONObject("props")
            if (props != null) {
                val titleObj = props.optJSONObject("title")
                val name = titleObj?.optString("name")
                if (!name.isNullOrBlank()) {
                    pageTitle = "🍿 $name"
                }

                // Se è una pagina /titles/{id}-{slug} di una serie TV condivisa senza episodio specifico,
                // prende il primo episodio della stagione caricata
                if (fallbackEpisodeId.isNullOrBlank()) {
                    val firstEp = props.optJSONObject("loadedSeason")
                        ?.optJSONArray("episodes")
                        ?.optJSONObject(0)
                    val epId = firstEp?.optInt("id", 0) ?: 0
                    if (epId > 0) {
                        fallbackEpisodeId = epId.toString()
                    }
                }

                val embedUrl = props.optString("embedUrl").ifBlank { props.optString("embed_url") }.takeIf { it.isNotBlank() }
                if (embedUrl != null) {
                    val fullEmbed = if (embedUrl.startsWith("http")) embedUrl else "$baseOrigin$embedUrl"
                    val resolvedEmbed = resolveIframeToEmbedUrl(fullEmbed, cleanUrl)
                    return extractFromVixCloudEmbed(resolvedEmbed, "$baseOrigin/", pageTitle, vidxgoReferer)
                }
            }
        }

        val iframeEl = doc.selectFirst("iframe[src]")
        if (iframeEl != null) {
            val iframeSrc = iframeEl.absUrl("src").ifBlank { iframeEl.attr("src") }
            if (iframeSrc.contains("vidxgo.co", ignoreCase = true)) {
                return resolveFromVidxgoEmbed(iframeSrc, pageTitle, vidxgoReferer)
            }
            val resolvedEmbed = resolveIframeToEmbedUrl(iframeSrc, cleanUrl)
            return extractFromVixCloudEmbed(resolvedEmbed, "$baseOrigin/", pageTitle, vidxgoReferer)
        }

        watchMatcher.reset()
        titlesMatcher.reset()
        val mediaId = when {
            watchMatcher.find() -> watchMatcher.group(1)
            titlesMatcher.find() -> titlesMatcher.group(1)
            else -> null
        }

        if (mediaId != null) {
            val langPrefix = if (cleanUrl.contains("/it/")) "/it" else ""
            val epQuery = if (!fallbackEpisodeId.isNullOrBlank()) "?episode_id=$fallbackEpisodeId&next_episode=1" else ""
            val watchOrIframeUrl = "$baseOrigin$langPrefix/iframe/$mediaId$epQuery"
            val resolvedEmbed = resolveIframeToEmbedUrl(watchOrIframeUrl, cleanUrl)
            return extractFromVixCloudEmbed(resolvedEmbed, "$baseOrigin/", pageTitle, vidxgoReferer)
        }

        throw IllegalStateException("Embed VixCloud non trovato direttamente")
    }

    private suspend fun resolveIframeToEmbedUrl(iframeUrl: String, referer: String): String {
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

    private suspend fun extractFromVixCloudEmbed(
        embedUrl: String,
        refererUrl: String,
        fallbackTitle: String,
        vidxgoReferer: String
    ): ResolvedVixStream {
        if (embedUrl.contains("vidxgo.co", ignoreCase = true)) {
            return resolveFromVidxgoEmbed(embedUrl, fallbackTitle, vidxgoReferer)
        }
        val html = fetchHtml(embedUrl, referer = refererUrl)
        val embedUri = Uri.parse(embedUrl)
        val embedOrigin = "${embedUri.scheme ?: "https"}://${embedUri.host ?: "vixcloud.cc"}"
        val cleanUa = CloudflareWebViewBridge.getCleanUserAgent()
        val headers = mapOf(
            "Referer" to "$embedOrigin/",
            "Origin" to embedOrigin,
            "User-Agent" to cleanUa,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site"
        )

        val effectiveTitle = resolveVixCloudTitle(embedUri, fallbackTitle)

        val parsedFromScript = HeadlessStreamSniffer.parseVixCloudScript(html)
        if (parsedFromScript != null) {
            return ResolvedVixStream(
                title = effectiveTitle,
                streamUrl = parsedFromScript,
                headers = headers,
                resolutionLabel = "1080p FHD"
            )
        }

        val m3u8Matcher = Pattern.compile("(https?://[^'\"\\s]+\\.m3u8[^'\"\\s]*)").matcher(html)
        if (m3u8Matcher.find()) {
            val directM3u8 = m3u8Matcher.group(1)!!
            return ResolvedVixStream(
                title = effectiveTitle,
                streamUrl = HeadlessStreamSniffer.normalizeStreamUrl(directM3u8),
                headers = headers,
                resolutionLabel = "HLS"
            )
        }

        throw IllegalStateException("Token VixCloud non trovato nell'HTML diretto")
    }

    private fun resolveVixCloudTitle(embedUri: Uri, fallbackTitle: String): String {
        if (fallbackTitle.isNotBlank() && !fallbackTitle.startsWith("🍿 StreamingCommunity")) {
            return fallbackTitle
        }
        return runCatching {
            val tParam = embedUri.getQueryParameter("t")
            if (tParam.isNullOrBlank()) return@runCatching fallbackTitle
            val decodedTitle = String(Base64.decode(tParam, Base64.DEFAULT), Charsets.UTF_8).trim()
            if (decodedTitle.isBlank()) return@runCatching fallbackTitle
            val dParam = embedUri.getQueryParameter("d")
            val epSuffix = if (!dParam.isNullOrBlank()) {
                val decodedD = String(Base64.decode(dParam, Base64.DEFAULT), Charsets.UTF_8).trim()
                if (decodedD.startsWith("{")) {
                    val dJson = JSONObject(decodedD)
                    val epNum = dJson.optInt("number", 0)
                    val epName = dJson.optString("name", "").trim()
                    when {
                        epNum > 0 && epName.isNotBlank() -> " - E$epNum · $epName"
                        epNum > 0 -> " - E$epNum"
                        else -> ""
                    }
                } else if (decodedD.isNotBlank()) {
                    " - $decodedD"
                } else {
                    ""
                }
            } else {
                ""
            }
            "🍿 $decodedTitle$epSuffix"
        }.getOrDefault(fallbackTitle)
    }

    private suspend fun fetchHtml(url: String, referer: String): String {
        return CloudflareWebViewBridge.fetchHtmlWithFallback(url = url, referer = referer)
    }
}
