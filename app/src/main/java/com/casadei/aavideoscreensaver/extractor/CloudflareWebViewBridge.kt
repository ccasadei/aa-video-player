package com.casadei.aavideoscreensaver.extractor

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
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
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridge Chromium (WebView headless) per superare i blocchi Cloudflare (HTTP 403/503 su TLS Java di OkHttp)
 * sia durante la navigazione del catalogo StreamingCommunity/StreamingUnity (`.nexus`, `.dog`, ecc.)
 * sia durante l'estrazione e riproduzione dei flussi VixCloud (`vixcloud.cc/embed/...`, `/playlist/...`, `/storage/enc.key`).
 */
object CloudflareWebViewBridge {

    private const val TAG = "AAVideo"
    private const val DEFAULT_FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val BLOCKED_SUBRESOURCE_KEYWORDS = listOf(
        "spbgc.com",
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
        "aclib",
        "adcash",
        "histats.com",
        "analytics.vixcloud.",
        "/gtag.js",
        "fonts.googleapis.com",
        "fonts.gstatic.com"
    )

    private val BLOCKED_EXTENSIONS = listOf(
        ".woff2", ".woff", ".ttf", ".otf",
        ".webp", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico",
        ".css"
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedCleanUserAgent: String? = null

    // WebView mantenuta viva sull'origine https://vixcloud.cc dopo l'apertura dell'embed per eseguire fetch() same-origin
    private var activeEmbedWebView: WebView? = null
    private var activeEmbedOrigin: String = ""

    data class WebFetchResponse(
        val status: Int,
        val contentType: String,
        val bytes: ByteArray,
        val error: String? = null
    )

    private val fetchIdGen = AtomicLong(1L)
    private val pendingFetches = ConcurrentHashMap<Long, Pair<CountDownLatch, AtomicResult>>()

    private class AtomicResult {
        @Volatile
        var response: WebFetchResponse? = null
    }

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    private fun resolveAppContext(): Context? {
        appContext?.let { return it }
        return runCatching {
            val clazz = Class.forName("android.app.ActivityThread")
            val app = clazz.getMethod("currentApplication").invoke(null) as? Application
            app?.applicationContext?.also { appContext = it }
        }.getOrNull()
    }

    /**
     * Restituisce lo User-Agent reale del Chromium di sistema rimuovendo i marker WebView (`; wv` e `Version/X.Y`),
     * così gli header Client Hints (`Sec-CH-UA`, `Sec-CH-UA-Platform: "Android"`) combaciano con lo User-Agent.
     */
    fun getCleanUserAgent(context: Context? = null): String {
        cachedCleanUserAgent?.let { return it }
        val ctx = context?.applicationContext ?: resolveAppContext()
        if (ctx != null) {
            val raw = runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrNull().orEmpty()
            if (raw.isNotBlank()) {
                val cleaned = raw
                    .replace("; wv)", ")")
                    .replace(Regex("""Version/\d+\.\d+\s*"""), "")
                    .trim()
                cachedCleanUserAgent = cleaned
                return cleaned
            }
        }
        return DEFAULT_FALLBACK_UA
    }

    private val fastOkHttpClient by lazy {
        YouTubeStreamExtractor.okHttpClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Scarica l'HTML di [url] provando prima OkHttp (con cookie e UA del browser) e passando automaticamente
     * alla WebView Chromium se Cloudflare risponde HTTP 403/503 o challenge.
     */
    suspend fun fetchHtmlWithFallback(
        url: String,
        referer: String = "",
        timeoutMs: Long = 12_000L
    ): String = withContext(Dispatchers.IO) {
        val ua = getCleanUserAgent()
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()

        val okHttpAttempt = runCatching {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8")
                .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
                .get()
            if (referer.isNotBlank()) {
                reqBuilder.header("Referer", referer)
            }
            if (!cookie.isNullOrBlank()) {
                reqBuilder.header("Cookie", cookie)
            }
            fastOkHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val cfMitigated = resp.header("cf-mitigated").orEmpty()
                    throw IllegalStateException("HTTP ${resp.code} (cf=$cfMitigated) su $url")
                }
                val body = resp.body?.string().orEmpty()
                if (isCloudflareChallengeHtml(body)) {
                    throw IllegalStateException("Cloudflare challenge su $url")
                }
                body
            }
        }

        if (okHttpAttempt.isSuccess) {
            return@withContext okHttpAttempt.getOrThrow()
        }

        val errMsg = okHttpAttempt.exceptionOrNull()?.message ?: "errore OkHttp"
        Log.i(TAG, "fetchHtmlWithFallback: $errMsg -> provo WebView per $url")
        fetchHtmlViaWebView(url, referer, timeoutMs)
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchHtmlViaWebView(
        url: String,
        referer: String = "",
        timeoutMs: Long = 12_000L
    ): String = withContext(Dispatchers.Main) {
        val ctx = resolveAppContext()
            ?: throw IllegalStateException("Context non disponibile per WebView ($url)")

        val deferred = CompletableDeferred<String>()
        val ua = getCleanUserAgent(ctx)
        val isVixEmbed = (url.contains("vixcloud.", ignoreCase = true) ||
            url.contains("vixsrc.", ignoreCase = true)) &&
            url.contains("/embed/", ignoreCase = true)

        var webView: WebView? = null
        var pollRunnable: Runnable? = null
        var transferredToActiveEmbed = false

        fun cleanup() {
            pollRunnable?.let { mainHandler.removeCallbacks(it) }
            pollRunnable = null
            if (!transferredToActiveEmbed) {
                webView?.let { wv ->
                    runCatching {
                        wv.stopLoading()
                        wv.loadUrl("about:blank")
                        wv.removeAllViews()
                        wv.destroy()
                    }
                }
            }
            webView = null
        }

        try {
            val wv = createConfiguredWebView(ctx, ua, attachFetchBridge = isVixEmbed)
            webView = wv

            fun inspectCurrentDom() {
                if (deferred.isCompleted) return
                val currentWv = webView ?: return
                currentWv.evaluateJavascript(
                    """
                    (function() {
                        try {
                            return JSON.stringify({
                                title: document.title || '',
                                url: window.location.href || '',
                                readyState: document.readyState || '',
                                html: document.documentElement ? document.documentElement.outerHTML : ''
                            });
                        } catch (e) {
                            return '';
                        }
                    })();
                    """.trimIndent()
                ) { rawResult ->
                    if (deferred.isCompleted || rawResult.isNullOrBlank() || rawResult == "null") return@evaluateJavascript
                    runCatching {
                        val jsonStr = JSONObject("{\"v\":$rawResult}").optString("v", "")
                        if (jsonStr.isBlank()) return@runCatching
                        val obj = JSONObject(jsonStr)
                        val title = obj.optString("title", "")
                        val pageUrl = obj.optString("url", "")
                        val readyState = obj.optString("readyState", "")
                        val html = obj.optString("html", "")

                        if (pageUrl == "about:blank" || html.length < 60) return@runCatching

                        // Se siamo ancora sulla challenge di Cloudflare, attendiamo il prossimo ciclo
                        if (isCloudflareChallengeHtml(html, title)) {
                            Log.d(TAG, "WebView attendendo risoluzione challenge Cloudflare su $pageUrl (title='$title')")
                            return@runCatching
                        }

                        // Se la pagina ha restituito un errore 403 esplicito
                        if (title == "403" ||
                            title.contains("403 Forbidden", ignoreCase = true) ||
                            title.contains("Access Denied", ignoreCase = true) ||
                            (html.length < 900 && (html.contains(">403<") || html.contains("Access Denied")))
                        ) {
                            Log.w(TAG, "WebView ha ricevuto 403 su $url (title='$title', len=${html.length})")
                            deferred.completeExceptionally(IllegalStateException("HTTP 403 su $url"))
                            return@runCatching
                        }

                        val hasExpectedContent = html.contains("data-page") ||
                            html.contains("<iframe", ignoreCase = true) ||
                            html.contains("masterPlaylist") ||
                            html.contains("btn sc") ||
                            (readyState == "complete" && html.length > 200)

                        if (hasExpectedContent) {
                            Log.i(TAG, "WebView fetch completato: url=$pageUrl title='$title' len=${html.length}")
                            if (isVixEmbed && html.contains("masterPlaylist")) {
                                promoteToActiveEmbedWebView(currentWv, pageUrl)
                                transferredToActiveEmbed = true
                            }
                            CookieManager.getInstance().flush()
                            deferred.complete(html)
                        }
                    }
                }
            }

            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    if (!request.isForMainFrame && shouldBlockSubresource(reqUrl)) {
                        return WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    inspectCurrentDom()
                }
            }

            pollRunnable = object : Runnable {
                override fun run() {
                    if (deferred.isCompleted) return
                    inspectCurrentDom()
                    mainHandler.postDelayed(this, 450L)
                }
            }
            mainHandler.postDelayed(pollRunnable!!, 500L)

            val headers = mutableMapOf<String, String>()
            if (referer.isNotBlank()) {
                headers["Referer"] = referer
            }
            if (headers.isEmpty()) {
                wv.loadUrl(url)
            } else {
                wv.loadUrl(url, headers)
            }

            val result = withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }
            result ?: throw IllegalStateException("Timeout WebView ($url)")
        } catch (e: CancellationException) {
            throw e
        } finally {
            cleanup()
        }
    }

    /**
     * Esegue `fetch(url)` direttamente dentro la WebView Chromium posizionata sull'origine `https://vixcloud.cc`,
     * così le playlist `.m3u8` e la chiave `/storage/enc.key` vengono scaricate con lo stesso identico stack TLS/cookie
     * del browser quando OkHttp riceve HTTP 403.
     */
    fun fetchBytesViaVixCloudWebViewBlocking(
        url: String,
        referer: String = "https://vixcloud.cc/",
        timeoutMs: Long = 9_000L
    ): WebFetchResponse? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val ctx = resolveAppContext() ?: return null

        val reqId = fetchIdGen.getAndIncrement()
        val latch = CountDownLatch(1)
        val holder = AtomicResult()
        pendingFetches[reqId] = latch to holder

        val targetUri = runCatching { Uri.parse(url) }.getOrNull()
        val targetOrigin = if (targetUri?.scheme != null && targetUri.host != null) {
            "${targetUri.scheme}://${targetUri.host}"
        } else {
            "https://vixcloud.cc"
        }

        val escapedUrl = url.replace("\\", "\\\\").replace("'", "\\'")
        val jsFetchCall = """
            (function() {
                fetch('$escapedUrl', {
                    method: 'GET',
                    credentials: 'include'
                }).then(function(resp) {
                    var status = resp.status || 0;
                    var ct = (resp.headers && resp.headers.get('content-type')) || 'application/octet-stream';
                    if (!resp.ok) {
                        window.AAFetchBridge.onFetchDone($reqId, status, ct, '', 'HTTP ' + status);
                        return;
                    }
                    return resp.arrayBuffer().then(function(buf) {
                        var bytes = new Uint8Array(buf);
                        var binary = '';
                        var chunkSize = 8192;
                        for (var i = 0; i < bytes.length; i += chunkSize) {
                            binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize));
                        }
                        var b64 = btoa(binary);
                        window.AAFetchBridge.onFetchDone($reqId, status, ct, b64, '');
                    });
                }).catch(function(err) {
                    window.AAFetchBridge.onFetchDone($reqId, 0, '', '', String(err));
                });
            })();
        """.trimIndent()

        mainHandler.post {
            runCatching {
                val ua = getCleanUserAgent(ctx)
                var wv = activeEmbedWebView
                if (wv == null) {
                    wv = createConfiguredWebView(ctx, ua, attachFetchBridge = true)
                    activeEmbedWebView = wv
                }
                if (!activeEmbedOrigin.equals(targetOrigin, ignoreCase = true)) {
                    activeEmbedOrigin = targetOrigin
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                            super.onPageFinished(view, loadedUrl)
                            view?.evaluateJavascript(jsFetchCall, null)
                        }
                    }
                    wv.loadDataWithBaseURL(
                        "$targetOrigin/",
                        "<!DOCTYPE html><html><head></head><body></body></html>",
                        "text/html",
                        "UTF-8",
                        null
                    )
                } else {
                    wv.evaluateJavascript(jsFetchCall, null)
                }
            }.onFailure { err ->
                holder.response = WebFetchResponse(0, "", ByteArray(0), err.message)
                latch.countDown()
            }
        }

        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            holder.response
        } catch (_: InterruptedException) {
            null
        } finally {
            pendingFetches.remove(reqId)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(
        ctx: Context,
        userAgent: String,
        attachFetchBridge: Boolean
    ): WebView {
        val wv = WebView(ctx)
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
            userAgentString = userAgent
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean = false
        }

        if (attachFetchBridge) {
            wv.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onFetchDone(
                        reqId: Long,
                        status: Int,
                        contentType: String?,
                        base64Data: String?,
                        error: String?
                    ) {
                        val entry = pendingFetches[reqId] ?: return
                        val bytes = if (!base64Data.isNullOrBlank()) {
                            runCatching { Base64.decode(base64Data, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
                        } else {
                            ByteArray(0)
                        }
                        entry.second.response = WebFetchResponse(
                            status = status,
                            contentType = contentType.orEmpty().ifBlank { "application/vnd.apple.mpegurl" },
                            bytes = bytes,
                            error = error?.takeIf { it.isNotBlank() }
                        )
                        entry.first.countDown()
                    }
                },
                "AAFetchBridge"
            )
        }

        return wv
    }

    private fun promoteToActiveEmbedWebView(wv: WebView, pageUrl: String) {
        val old = activeEmbedWebView
        if (old != null && old !== wv) {
            runCatching {
                old.stopLoading()
                old.loadUrl("about:blank")
                old.removeAllViews()
                old.destroy()
            }
        }
        activeEmbedWebView = wv
        val parsed = runCatching { Uri.parse(pageUrl) }.getOrNull()
        activeEmbedOrigin = if (parsed?.scheme != null && parsed.host != null) {
            "${parsed.scheme}://${parsed.host}"
        } else {
            "https://vixcloud.cc"
        }
    }

    private fun isCloudflareChallengeHtml(html: String, title: String = ""): Boolean {
        if (html.contains("data-page") || html.contains("masterPlaylist") || html.contains("<iframe")) {
            return false
        }
        val lowerTitle = title.lowercase()
        if (lowerTitle.contains("just a moment") ||
            lowerTitle.contains("un momento") ||
            lowerTitle.contains("attention required")
        ) {
            return true
        }
        return html.contains("cf-browser-verification") ||
            (html.contains("/cdn-cgi/challenge-platform/") && html.length < 15_000)
    }

    private fun shouldBlockSubresource(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("/cdn-cgi/")) return false
        if (BLOCKED_SUBRESOURCE_KEYWORDS.any { lower.contains(it) }) return true
        val pathPart = lower.substringBefore('?')
        if (BLOCKED_EXTENSIONS.any { pathPart.endsWith(it) }) return true
        // Blocca i pesanti bundle Vue/Inertia e JWPlayer quando ci serve solo l'HTML SSR iniziale
        if (pathPart.contains("/build/assets/") && pathPart.endsWith(".js")) return true
        if (pathPart.contains("/jwplayer") && pathPart.endsWith(".js")) return true
        return false
    }
}
