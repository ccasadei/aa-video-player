package com.casadei.aavideoscreensaver.phone

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.casadei.aavideoscreensaver.player.ScreensaverRepository
import com.casadei.aavideoscreensaver.player.VideoPlayerController
import java.io.ByteArrayInputStream

/**
 * Mini-Browser integrato con Ad-Blocker e Sniffer automatico per StreamingCommunity / VixCloud.
 *
 * - Blocca i popup pubblicitari quando si preme Play sul player di StreamingCommunity.
 * - Intercetta automaticamente le richieste di rete verso `vixcloud.co/playlist/...`, `.m3u8` e `.mp4`
 *   catturando anche `Referer`, `Origin` e `Cookie` necessari per la CDN.
 * - Appena rileva il flusso video, mostra un pulsante luminoso per inviarlo istantaneamente
 *   in riproduzione Full-Screen su Android Auto.
 */
object StreamingCommunityBrowserDialog {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val BLOCKED_AD_KEYWORDS = listOf(
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
        "outbrain.com",
        "taboola.com",
        "yandex.ru/ads",
        "bet365",
        "1xbet"
    )

    @SuppressLint("SetJavaScriptEnabled")
    fun show(context: Context, initialQueryOrUrl: String? = null) {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#090C12"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top Navigation Bar
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#121824"))
            setPadding(18, 18, 18, 18)
        }

        lateinit var webView: WebView
        lateinit var urlBar: EditText
        lateinit var capturedBanner: LinearLayout
        lateinit var capturedInfoText: TextView
        lateinit var btnSendToCar: Button

        var latestCapturedUrl: String? = null
        var latestCapturedHeaders: Map<String, String> = emptyMap()
        var latestPageTitle: String = "🍿 StreamingCommunity"

        val btnBack = createCompactButton(context, "◀", "#1E293B") {
            if (webView.canGoBack()) webView.goBack()
        }

        urlBar = EditText(context).apply {
            hint = "Cerca su StreamingCommunity o incolla URL..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13.5f
            setSingleLine(true)
            background = GradientDrawable().apply {
                cornerRadius = 14f
                setColor(Color.parseColor("#1B2434"))
                setStroke(2, Color.parseColor("#334155"))
            }
            setPadding(22, 16, 22, 16)
        }

        val btnGo = createCompactButton(context, "Vai", "#2563EB") {
            val raw = urlBar.text?.toString()?.trim() ?: ""
            if (raw.isNotBlank()) {
                val target = normalizeInputToUrl(raw)
                webView.loadUrl(target)
            }
        }

        val btnClose = createCompactButton(context, "✕", "#7F1D1D") {
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
            dialog.dismiss()
        }

        topBar.addView(btnBack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = 10 })
        topBar.addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10 })
        topBar.addView(btnGo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = 10 })
        topBar.addView(btnClose, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(topBar)

        // Hint bar
        val hintBar = TextView(context).apply {
            text = "🛡️ AdBlock & Anti-Popup attivi: apri un film/serie e premi Play sul video. Lo stream .m3u8 verrà catturato automaticamente!"
            textSize = 11.5f
            setTextColor(Color.parseColor("#93C5FD"))
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(24, 12, 24, 12)
        }
        root.addView(hintBar)

        // Banner di cattura stream (visibile appena viene intercettato il flusso VixCloud / .m3u8)
        capturedBanner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#064E3B"))
            setPadding(24, 18, 24, 18)
            visibility = View.GONE
        }

        capturedInfoText = TextView(context).apply {
            text = "🎬 Stream Full-HD rilevato!"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#A7F3D0"))
            setPadding(0, 0, 0, 10)
        }
        capturedBanner.addView(capturedInfoText)

        btnSendToCar = Button(context).apply {
            text = "▶️ Invia SUBITO a Tutto Schermo su Android Auto e Salva"
            isAllCaps = false
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#052E16"))
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#34D399"))
            }
            setPadding(24, 18, 24, 18)
            setOnClickListener {
                val streamUrl = latestCapturedUrl
                if (!streamUrl.isNullOrBlank()) {
                    VideoPlayerController.addCapturedWebStream(
                        context = context,
                        streamUrl = streamUrl,
                        title = latestPageTitle,
                        headers = latestCapturedHeaders,
                        playImmediately = true
                    )
                    Toast.makeText(
                        context,
                        "Avviato su Android Auto: $latestPageTitle",
                        Toast.LENGTH_LONG
                    ).show()
                    runCatching {
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                        webView.destroy()
                    }
                    dialog.dismiss()
                }
            }
        }
        capturedBanner.addView(btnSendToCar)
        root.addView(capturedBanner)

        // WebView principale
        webView = WebView(context).apply {
            setBackgroundColor(Color.parseColor("#090C12"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = false
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                // Blocca tutte le finestre popup pubblicitarie aperte dai player di streaming
                return false
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrBlank() &&
                    !title.contains("about:blank", ignoreCase = true) &&
                    !title.contains("Google", ignoreCase = true)
                ) {
                    val clean = title
                        .replace("StreamingCommunity", "", ignoreCase = true)
                        .replace(" - ", " ")
                        .trim()
                    if (clean.isNotBlank()) {
                        latestPageTitle = "🍿 $clean"
                    }
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val lower = url.lowercase()

                if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                    return true
                }
                if (isBlockedAdUrl(lower)) {
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                urlBar.setText(url)
                val lowerUrl = url.lowercase()
                if (lowerUrl.contains("streamingcommunity.") || lowerUrl.contains("streaming-community.")) {
                    val uri = Uri.parse(url)
                    val domainRoot = "${uri.scheme ?: "https"}://${uri.host}"
                    ScreensaverRepository.saveLastStreamingCommunityUrl(context, domainRoot)
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                val lower = url.lowercase()

                if (isBlockedAdUrl(lower)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }

                // Rileva master playlist VixCloud / VixSrc / VidxGo (d2b.you) / HLS (.m3u8) / MP4
                if (isPlayableStreamUrl(lower)) {
                    val upgradedUrl = upgradeVixCloudToFhdIfApplicable(url)
                    val reqHeaders = mutableMapOf<String, String>()
                    if (lower.contains("d2b.you")) {
                        reqHeaders.putAll(
                            com.casadei.aavideoscreensaver.extractor.StreamingCommunityExtractor.buildVidxgoCdnHeaders()
                        )
                    } else {
                        reqHeaders["Sec-Fetch-Dest"] = "empty"
                        reqHeaders["Sec-Fetch-Mode"] = "cors"
                        reqHeaders["Sec-Fetch-Site"] = "cross-site"
                    }
                    for ((k, v) in request.requestHeaders) {
                        if (k.equals("Referer", ignoreCase = true) ||
                            k.equals("Origin", ignoreCase = true) ||
                            k.equals("User-Agent", ignoreCase = true) ||
                            k.equals("Sec-Fetch-Dest", ignoreCase = true) ||
                            k.equals("Sec-Fetch-Mode", ignoreCase = true) ||
                            k.equals("Sec-Fetch-Site", ignoreCase = true)
                        ) {
                            reqHeaders[k] = v
                        }
                    }
                    if (!reqHeaders.containsKey("Referer")) {
                        val hostUri = Uri.parse(url)
                        reqHeaders["Referer"] = if (lower.contains("d2b.you")) {
                            "https://v.vidxgo.co/"
                        } else {
                            "${hostUri.scheme ?: "https"}://${hostUri.host ?: "v.vidxgo.co"}/"
                        }
                    }
                    if (!reqHeaders.containsKey("Origin") && lower.contains("d2b.you")) {
                        reqHeaders["Origin"] = "https://v.vidxgo.co"
                    }
                    val cookie = CookieManager.getInstance().getCookie(url)
                    if (!cookie.isNullOrBlank()) {
                        reqHeaders["Cookie"] = cookie
                    }

                    mainHandler.post {
                        // Preferiamo la master playlist principale rispetto ai singoli sotto-variant .m3u8
                        val shouldUpdate = latestCapturedUrl == null ||
                            upgradedUrl.contains("/playlist/") ||
                            upgradedUrl.contains("master.m3u8") ||
                            (!latestCapturedUrl!!.contains("/playlist/") && !latestCapturedUrl!!.contains("master.m3u8"))

                        if (shouldUpdate) {
                            latestCapturedUrl = upgradedUrl
                            latestCapturedHeaders = reqHeaders
                            capturedInfoText.text = "🎬 Stream Catturato: $latestPageTitle"
                            capturedBanner.visibility = View.VISIBLE
                        }
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }
        }

        root.addView(
            webView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
        }
        dialog.show()

        val startUrl = if (!initialQueryOrUrl.isNullOrBlank()) {
            normalizeInputToUrl(initialQueryOrUrl)
        } else {
            ScreensaverRepository.loadLastStreamingCommunityUrl(context)
        }
        urlBar.setText(startUrl)
        webView.loadUrl(startUrl)
    }

    private fun isBlockedAdUrl(lowerUrl: String): Boolean {
        return BLOCKED_AD_KEYWORDS.any { lowerUrl.contains(it) }
    }

    private fun isPlayableStreamUrl(lowerUrl: String): Boolean {
        if (lowerUrl.endsWith(".ts") || lowerUrl.contains(".ts?") || lowerUrl.endsWith(".vtt")) {
            return false
        }
        return (lowerUrl.contains("vixcloud.") && lowerUrl.contains("/playlist/")) ||
            (lowerUrl.contains("vixsrc.") && lowerUrl.contains("/playlist/")) ||
            lowerUrl.contains(".m3u8") ||
            (lowerUrl.contains(".mp4") && !lowerUrl.contains("preview") && !lowerUrl.contains("trailer"))
    }

    private fun upgradeVixCloudToFhdIfApplicable(url: String): String {
        return if ((url.contains("vixcloud.") || url.contains("vixsrc.")) &&
            url.contains("/playlist/") &&
            !url.contains("h=1")
        ) {
            if (url.contains("?")) "$url&h=1" else "$url?h=1"
        } else {
            url
        }
    }

    private fun normalizeInputToUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        if (trimmed.contains(".") && !trimmed.contains(" ")) {
            return "https://$trimmed"
        }
        return "https://streaming-community.biz/archivio/?search=${Uri.encode(trimmed)}"
    }

    private fun createCompactButton(
        context: Context,
        label: String,
        bgHex: String,
        onClick: (View) -> Unit
    ): Button {
        return Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            background = GradientDrawable().apply {
                cornerRadius = 14f
                setColor(Color.parseColor(bgHex))
            }
            setPadding(24, 16, 24, 16)
            setOnClickListener(onClick)
        }
    }
}
