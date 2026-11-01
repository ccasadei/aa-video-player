package com.casadei.aavideoscreensaver.extractor

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.regex.Pattern

/**
 * Sniffer WebView Headless (invisibile in background) per estrarre flussi .m3u8 / VixCloud
 * da pagine protette da Cloudflare o offuscamento JS (es. Streamed.pk / embed.st e VixSrc / StreamingCommunity)
 * direttamente quando l'utente seleziona un contenuto dallo schermo di Android Auto, senza dover toccare il telefono.
 */
object HeadlessStreamSniffer {

    private const val TAG = "AAVideo"
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val BLOCKED_AD_DOMAINS = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "adservice.google",
        "popads.net",
        "popcash.net",
        "propellerads",
        "adsterra",
        "exoclick",
        "juicyads",
        "clickadu",
        "hilltopads",
        "trafficjunky",
        "rapidadexchange.com",
        "aclib",
        "adcash",
        "llvpn.com",
        "optimserve.agency",
        "histats.com",
        "t48s7z.js",
        "/ad.html"
    )

    private data class PreCleanedPage(
        val effectiveUrl: String,
        val html: String
    )

    data class SniffedStream(
        val streamUrl: String,
        val headers: Map<String, String>,
        val pageTitle: String
    )

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniffStream(
        context: Context,
        targetUrl: String,
        referer: String,
        timeoutMs: Long = 16_000L
    ): Result<SniffedStream> = withContext(Dispatchers.Main) {
        val startMs = System.currentTimeMillis()
        Log.d(TAG, "HeadlessStreamSniffer.sniffStream start: url=$targetUrl timeoutMs=$timeoutMs")
        val deferred = CompletableDeferred<SniffedStream>()
        val mainHandler = Handler(Looper.getMainLooper())
        val appCtx = context.applicationContext

        var webView: WebView? = null
        var pollRunnable: Runnable? = null

        fun cleanup() {
            pollRunnable?.let { mainHandler.removeCallbacks(it) }
            pollRunnable = null
            webView?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.clearHistory()
                    wv.removeAllViews()
                    wv.destroy()
                }
            }
            webView = null
        }

        try {
            val wv = WebView(appCtx)
            webView = wv

            // Simula un viewport HD 1280x720 offscreen così i player (Clappr / JWPlayer / Video.js) inizializzano subito
            wv.visibility = View.INVISIBLE
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, 1280, 720)

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(wv, true)

            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = DESKTOP_USER_AGENT
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            fun completeWithUrl(
                rawStreamUrl: String,
                reqHeaders: Map<String, String> = emptyMap(),
                customReferer: String? = null
            ) {
                if (deferred.isCompleted) return
                val normalized = normalizeStreamUrl(rawStreamUrl)
                if (normalized.isBlank()) return

                val currentPageUrl = runCatching { wv.url }
                    .getOrNull()
                    ?.takeIf { it.startsWith("http") }
                    ?: targetUrl
                val effectiveRef = customReferer
                    ?: reqHeaders["Referer"]
                    ?: reqHeaders["referer"]
                    ?: currentPageUrl
                val originUri = runCatching { Uri.parse(effectiveRef) }.getOrNull()
                val origin = if (originUri?.scheme != null && originUri.host != null) {
                    "${originUri.scheme}://${originUri.host}"
                } else {
                    reqHeaders["Origin"] ?: reqHeaders["origin"] ?: ""
                }

                val cookie = runCatching {
                    CookieManager.getInstance().getCookie(normalized)
                        ?: CookieManager.getInstance().getCookie(effectiveRef)
                }.getOrNull()

                val headers = mutableMapOf(
                    "User-Agent" to DESKTOP_USER_AGENT,
                    "Referer" to effectiveRef,
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site"
                )
                if (origin.isNotBlank()) {
                    headers["Origin"] = origin
                }
                if (!cookie.isNullOrBlank()) {
                    headers["Cookie"] = cookie
                }
                for ((k, v) in reqHeaders) {
                    if (k.equals("Referer", ignoreCase = true) ||
                        k.equals("Origin", ignoreCase = true) ||
                        k.equals("Authorization", ignoreCase = true) ||
                        k.equals("Sec-Fetch-Dest", ignoreCase = true) ||
                        k.equals("Sec-Fetch-Mode", ignoreCase = true) ||
                        k.equals("Sec-Fetch-Site", ignoreCase = true)
                    ) {
                        headers[k] = v
                    }
                }

                val title = runCatching { wv.title }.getOrNull().orEmpty()
                val elapsed = System.currentTimeMillis() - startMs
                Log.i(TAG, "HeadlessStreamSniffer resolved in ${elapsed}ms: $normalized (ref=$effectiveRef)")
                deferred.complete(
                    SniffedStream(
                        streamUrl = normalized,
                        headers = headers,
                        pageTitle = title
                    )
                )
            }

            wv.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onStreamDetected(url: String?, pageOrigin: String?) {
                        if (url.isNullOrBlank()) return
                        val resolvedUrl = if (url.startsWith("//")) {
                            "https:$url"
                        } else if (url.startsWith("/") && !pageOrigin.isNullOrBlank()) {
                            "${pageOrigin.trimEnd('/')}$url"
                        } else {
                            url
                        }
                        if (isMediaStreamUrl(resolvedUrl)) {
                            mainHandler.post {
                                val ref = if (!pageOrigin.isNullOrBlank() && pageOrigin.startsWith("http")) {
                                    "${pageOrigin.trimEnd('/')}/"
                                } else {
                                    targetUrl
                                }
                                completeWithUrl(resolvedUrl, customReferer = ref)
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onVixScriptDetected(scriptText: String?, pageUrl: String?) {
                        if (scriptText.isNullOrBlank()) return
                        val parsed = parseVixCloudScript(scriptText)
                        if (parsed != null) {
                            mainHandler.post {
                                completeWithUrl(parsed, customReferer = pageUrl ?: targetUrl)
                            }
                        }
                    }
                },
                "AAVideoBridge"
            )

            // Blocca popup pubblicitari
            wv.webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean = false
            }

            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (isAdUrl(url)) return true
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    if (isAdUrl(reqUrl)) {
                        return WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }

                    if (isMediaStreamUrl(reqUrl)) {
                        val reqHeaders = request.requestHeaders ?: emptyMap()
                        mainHandler.post {
                            completeWithUrl(reqUrl, reqHeaders = reqHeaders)
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(JS_INTERCEPTOR_HOOK, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(JS_INTERCEPTOR_HOOK, null)
                    view?.evaluateJavascript(JS_SCAN_AND_AUTOPLAY, null)
                }
            }

            // Polling periodico ogni 650ms per cliccare play / scansionare script VixCloud o Clappr/JWPlayer
            pollRunnable = object : Runnable {
                override fun run() {
                    if (deferred.isCompleted) return
                    webView?.evaluateJavascript(JS_SCAN_AND_AUTOPLAY, null)
                    mainHandler.postDelayed(this, 650L)
                }
            }
            mainHandler.postDelayed(pollRunnable!!, 700L)

            val preCleaned = withContext(Dispatchers.IO) {
                preCleanHeavyAdScriptsIfNeeded(targetUrl, referer)
            }
            if (preCleaned != null) {
                wv.loadDataWithBaseURL(
                    preCleaned.effectiveUrl,
                    preCleaned.html,
                    "text/html",
                    "UTF-8",
                    preCleaned.effectiveUrl
                )
            } else {
                val initialHeaders = mapOf(
                    "Referer" to referer,
                    "User-Agent" to DESKTOP_USER_AGENT
                )
                wv.loadUrl(targetUrl, initialHeaders)
            }

            val result = withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }

            if (result != null) {
                Result.success(result)
            } else {
                val elapsed = System.currentTimeMillis() - startMs
                Log.w(TAG, "HeadlessStreamSniffer timeout after ${elapsed}ms for $targetUrl")
                Result.failure(IllegalStateException("Timeout estrazione stream ($targetUrl)"))
            }
        } catch (e: CancellationException) {
            // Cambio video / schermata chiusa: non è un errore della sorgente, va propagato al chiamante
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "HeadlessStreamSniffer error for $targetUrl", t)
            Result.failure(t)
        } finally {
            cleanup()
        }
    }

    /**
     * Alcune pagine embed (es. embed.st / embedme.top / rockyembed.st) includono ~630KB di script AdCash inline
     * (`;function _0x...` e `aclib.runPop`) o iframe multipli annidati (`rockyembed.st` -> `atob("https://embed.st/embed/ingest/...")`)
     * che saturano il main thread della WebView causando timeout.
     * Pre-scarichiamo l'HTML seguendo eventuali iframe intermedi e rimuovendo i blocchi pubblicitari pesanti.
     */
    private fun preCleanHeavyAdScriptsIfNeeded(targetUrl: String, referer: String): PreCleanedPage? {
        val lower = targetUrl.lowercase()
        if (!lower.contains("embed.st/") &&
            !lower.contains("embedme.top/") &&
            !lower.contains("rockyembed.")
        ) {
            return null
        }
        return runCatching {
            var currentUrl = targetUrl
            var currentReferer = referer
            var rawHtml = ""

            for (hop in 0 until 3) {
                val req = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Referer", currentReferer)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .get()
                    .build()
                val fetched = YouTubeStreamExtractor.okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    resp.body?.string()
                } ?: return@runCatching null

                rawHtml = fetched
                // Se la pagina ha già il player bundle-jw.js, siamo arrivati al frame finale
                if (rawHtml.contains("bundle-jw.js", ignoreCase = true)) {
                    break
                }

                // Controlla se la pagina crea un iframe tramite atob("aHR0cHM6...")
                val atobMatch = Regex("""atob\(\s*["']([A-Za-z0-9+/=]{20,})["']\s*\)""").find(rawHtml)
                val decodedAtobUrl = atobMatch?.groupValues?.getOrNull(1)?.let { b64 ->
                    runCatching {
                        String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
                    }.getOrNull()
                }?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

                if (decodedAtobUrl != null) {
                    Log.d(TAG, "Unwrapping atob iframe hop $hop: $currentUrl -> $decodedAtobUrl")
                    currentReferer = currentUrl
                    currentUrl = decodedAtobUrl
                    continue
                }

                // Controlla se la pagina contiene un <iframe src="https://..."> verso l'embed reale
                val iframeMatch = Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                    .findAll(rawHtml)
                    .map { it.groupValues[1] }
                    .firstOrNull { !isAdUrl(it) }

                if (iframeMatch != null) {
                    Log.d(TAG, "Unwrapping nested iframe hop $hop: $currentUrl -> $iframeMatch")
                    currentReferer = currentUrl
                    currentUrl = iframeMatch
                    continue
                }

                break
            }

            // Rimuove la parte offuscata AdCash (;function _0x...) lasciando intatte le variabili window[...] iniziali
            val cleaned = rawHtml
                .replace(Regex(";function\\s+_0x[0-9a-fA-F]+\\([^)]*\\)\\{[\\s\\S]*?</script>"), ";</script>")
                .replace(Regex("<script[^>]*>\\s*aclib\\.runPop[\\s\\S]*?</script>"), "")
                .replace("/ad.html", "about:blank")

            val hookTag = "<script>$JS_INTERCEPTOR_HOOK</script>"
            val finalHtml = if (cleaned.contains("<head>", ignoreCase = true)) {
                cleaned.replaceFirst(Regex("(?i)<head>"), "<head>$hookTag")
            } else {
                "$hookTag$cleaned"
            }
            PreCleanedPage(effectiveUrl = currentUrl, html = finalHtml)
        }.getOrNull()
    }

    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (isMediaStreamUrl(lower)) return false
        return BLOCKED_AD_DOMAINS.any { lower.contains(it) }
    }

    private fun isMediaStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains(".ts") || lower.contains(".m4s") || lower.contains(".vtt")) return false
        return lower.contains(".m3u8") ||
            (lower.contains("vixcloud.") && lower.contains("/playlist/")) ||
            (lower.contains("vixsrc.") && lower.contains("/playlist/"))
    }

    /**
     * Normalizza gli URL VixCloud / HLS:
     * - Aggiunge `.m3u8` prima di `?` sui link `vixcloud.co/playlist/<id>?token=...` (richiesto da ExoPlayer).
     * - Forza `&h=1` (1080p Full HD) sui flussi VixCloud.
     */
    fun normalizeStreamUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.contains("vixcloud.", ignoreCase = true) && url.contains("/playlist/", ignoreCase = true)) {
            val qIdx = url.indexOf('?')
            if (qIdx > 0) {
                val basePath = url.substring(0, qIdx)
                val query = url.substring(qIdx + 1)
                val fixedPath = if (basePath.endsWith(".m3u8", ignoreCase = true)) basePath else "$basePath.m3u8"
                url = "$fixedPath?$query"
            } else if (!url.endsWith(".m3u8", ignoreCase = true)) {
                url = "$url.m3u8"
            }
            if (!url.contains("h=1")) {
                url = if (url.contains("?")) "$url&h=1" else "$url?h=1"
            }
        }
        return url
    }

    fun parseVixCloudScript(scriptContent: String): String? {
        val tokenM = Pattern.compile("['\"]token['\"]\\s*:\\s*['\"]([^'\"]+)['\"]").matcher(scriptContent)
        val expiresM = Pattern.compile("['\"]expires['\"]\\s*:\\s*['\"]([^'\"]+)['\"]").matcher(scriptContent)
        val urlM = Pattern.compile("url\\s*:\\s*['\"](https?://[^'\"]+/playlist/[^'\"]+)['\"]").matcher(scriptContent)

        if (tokenM.find() && expiresM.find() && urlM.find()) {
            val token = tokenM.group(1) ?: return null
            val expires = expiresM.group(1) ?: return null
            val serverUrl = urlM.group(1) ?: return null
            val sep = if (serverUrl.contains("?")) "&" else "?"
            val raw = "$serverUrl${sep}token=$token&expires=$expires&h=1"
            return normalizeStreamUrl(raw)
        }
        return null
    }

    private const val JS_INTERCEPTOR_HOOK = """
        (function() {
            if (window.__aaVideoHooked) return;
            window.__aaVideoHooked = true;
            window.open = function() { return null; };
            try {
                const origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function(input, init) {
                        try {
                            const u = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                            if (u && (u.indexOf('.m3u8') !== -1 || u.indexOf('/playlist/') !== -1)) {
                                window.AAVideoBridge.onStreamDetected(u, window.location.origin);
                            }
                        } catch (e) {}
                        return origFetch.apply(this, arguments);
                    };
                }
                const origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    try {
                        if (url && typeof url === 'string' && (url.indexOf('.m3u8') !== -1 || url.indexOf('/playlist/') !== -1)) {
                            window.AAVideoBridge.onStreamDetected(url, window.location.origin);
                        }
                    } catch (e) {}
                    return origOpen.apply(this, arguments);
                };
            } catch (e) {}
        })();
    """

    private const val JS_SCAN_AND_AUTOPLAY = """
        (function() {
            try {
                // 1. Controlla window.masterPlaylist (VixCloud / VixSrc)
                if (window.masterPlaylist && window.masterPlaylist.url && window.masterPlaylist.params) {
                    var p = window.masterPlaylist.params;
                    var u = window.masterPlaylist.url;
                    var sep = u.indexOf('?') !== -1 ? '&' : '?';
                    var full = u + sep + 'token=' + (p.token || '') + '&expires=' + (p.expires || '') + '&h=1';
                    window.AAVideoBridge.onStreamDetected(full, window.location.origin);
                    return;
                }
                // 2. Scansiona tutti gli script per 'token', 'expires', '/playlist/'
                var scripts = document.querySelectorAll('script');
                for (var i = 0; i < scripts.length; i++) {
                    var t = scripts[i].textContent || '';
                    if (t.indexOf('/playlist/') !== -1 && t.indexOf('token') !== -1) {
                        window.AAVideoBridge.onVixScriptDetected(t, window.location.href);
                        return;
                    }
                }
                // 3. Controlla se la pagina ha un iframe verso /embed/ o vixcloud e naviga se necessario
                var iframes = document.querySelectorAll('iframe');
                for (var j = 0; j < iframes.length; j++) {
                    var src = iframes[j].src || iframes[j].getAttribute('src') || '';
                    if (src && (src.indexOf('vixcloud') !== -1 || src.indexOf('vixsrc') !== -1 || src.indexOf('/iframe/') !== -1 || src.indexOf('/embed/') !== -1)) {
                        try {
                            var idoc = iframes[j].contentDocument || (iframes[j].contentWindow && iframes[j].contentWindow.document);
                            if (idoc && idoc.documentElement) {
                                var ihtml = idoc.documentElement.innerHTML || '';
                                if (ihtml.indexOf('/playlist/') !== -1 && ihtml.indexOf('token') !== -1) {
                                    window.AAVideoBridge.onVixScriptDetected(ihtml, src);
                                    return;
                                }
                            }
                        } catch (e) {}
                    }
                }
                // 4. Controlla elemento <video> o player Clappr / JWPlayer
                var vids = document.querySelectorAll('video');
                for (var k = 0; k < vids.length; k++) {
                    var vsrc = vids[k].currentSrc || vids[k].src || '';
                    if (vsrc && vsrc.indexOf('blob:') !== 0 && vsrc.indexOf('http') === 0) {
                        window.AAVideoBridge.onStreamDetected(vsrc, window.location.origin);
                    }
                    vids[k].muted = true;
                    var playPromise = vids[k].play();
                    if (playPromise && playPromise.catch) playPromise.catch(function(){});
                }
                // 5. Simula click su poster/pulsanti play (Clappr, JWPlayer, VideoJS)
                var selectors = ['.player-poster', '.vjs-big-play-button', '.jw-display-icon-container', '#player', 'button[title="Play"]'];
                for (var s = 0; s < selectors.length; s++) {
                    var el = document.querySelector(selectors[s]);
                    if (el && typeof el.click === 'function') {
                        el.click();
                    }
                }
            } catch (e) {}
        })();
    """
}
