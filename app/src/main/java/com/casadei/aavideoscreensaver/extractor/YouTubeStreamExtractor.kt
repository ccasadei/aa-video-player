package com.casadei.aavideoscreensaver.extractor

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class YouTubeSearchResult(
    val videoId: String,
    val title: String,
    val uploader: String,
    val durationOrBadge: String,
    val url: String
)

/**
 * Strategie di estrazione, in ordine di affidabilità verificata (24 ottobre 2026):
 * - NEWPIPE: NewPipeExtractor 0.26.5, fornisce HLS adattivo (fino a 1080p H.264) anche per i video normali,
 *   con URL scaricabili senza limitazioni; estrazione in circa 1,5-2,5 s.
 * - ANDROID: InnerTube ANDROID, solo HLS (dirette) o formato muxed (itag 18, 360p): riserva veloce.
 * - IOS: InnerTube IOS, solo HLS o muxed (per i VOD oggi non ne restituisce).
 * - ADAPTIVE: formati adattivi video+audio di InnerTube; senza poToken googlevideo risponde 403, ultima spiaggia.
 */
enum class YouTubeStrategy { ANDROID, NEWPIPE, IOS, ADAPTIVE }

data class ResolvedYouTubeStream(
    val videoId: String,
    val title: String,
    val uploader: String,
    val hlsUrl: String? = null,
    val videoUrl: String? = null,
    val audioUrl: String? = null,
    val resolutionLabel: String = "",
    val httpHeaders: Map<String, String> = emptyMap(),
    val durationMs: Long = 0L,
    val strategy: YouTubeStrategy
)

/**
 * Estrattore per URL YouTube:
 * - Strategia 1: InnerTube ANDROID (20.03.35) -> restituisce hlsManifestUrl per le dirette live e adaptive H.264 (itag 137/136/135) + M4A (itag 140) o muxed MP4 (itag 18) con URL non cifrati.
 * - Strategia 2: InnerTube IOS (20.03.02) -> fallback rapido per VOD H.264 + M4A.
 * - Strategia 3: NewPipeExtractor -> ulteriore fallback per video particolari.
 */
object YouTubeStreamExtractor {

    private const val TAG = "AAVideo"

    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val ANDROID_YT_VERSION = "20.03.35"
    private const val ANDROID_YT_UA =
        "com.google.android.youtube/$ANDROID_YT_VERSION (Linux; U; Android 14; IT) gzip"

    private const val IOS_YT_VERSION = "20.03.02"
    private const val IOS_YT_UA =
        "com.google.ios.youtube/$IOS_YT_VERSION (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)"

    @Volatile
    private var initialized = false

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(StreamingCommunityExtractor.VidxgoTokenRefreshInterceptor)
            .build()
    }

    private object OkHttpNewPipeDownloader : Downloader() {
        override fun execute(request: NpRequest): NpResponse {
            val method = request.httpMethod()
            val url = request.url()
            val headers = request.headers()
            val dataToSend = request.dataToSend()

            val body = if (dataToSend != null) {
                dataToSend.toRequestBody(null)
            } else if (method.equals("POST", ignoreCase = true)) {
                ByteArray(0).toRequestBody(null)
            } else {
                null
            }

            val reqBuilder = Request.Builder()
                .url(url)
                .method(method, body)
                .header("User-Agent", WEB_USER_AGENT)

            for ((key, values) in headers) {
                reqBuilder.removeHeader(key)
                for (v in values) {
                    reqBuilder.addHeader(key, v)
                }
            }

            okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (response.code == 429) {
                    throw ReCaptchaException("Rate limit 429", url)
                }
                val responseBody = response.body?.string() ?: ""
                val responseHeaders = response.headers.toMultimap()
                val latestUrl = response.request.url.toString()
                return NpResponse(
                    response.code,
                    response.message,
                    responseHeaders,
                    responseBody,
                    latestUrl
                )
            }
        }
    }

    @Synchronized
    fun ensureInitialized() {
        if (!initialized) {
            NewPipe.init(
                OkHttpNewPipeDownloader,
                Localization("it", "IT"),
                ContentCountry("IT")
            )
            initialized = true
        }
    }

    /**
     * Estrae il primo URL HTTP/HTTPS contenuto in un testo condiviso (es. da Intent.ACTION_SEND).
     */
    fun extractUrlFromSharedText(rawText: String): String? {
        val trimmed = rawText.trim()
        val matcher = Pattern.compile("(https?://[^\\s\"'<>]+)", Pattern.CASE_INSENSITIVE).matcher(trimmed)
        if (matcher.find()) {
            return matcher.group(1)?.trim()
        }
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("content://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true)
        ) {
            trimmed
        } else {
            null
        }
    }

    /**
     * Riconosce se un URL appartiene a YouTube.
     */
    fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com/") ||
            lower.contains("youtu.be/") ||
            lower.contains("youtube-nocookie.com/")
    }

    /**
     * Estrae il video ID di 11 caratteri da qualsiasi variante di link YouTube.
     */
    fun extractVideoId(url: String): String? {
        val patterns = listOf(
            "youtu\\.be/([a-zA-Z0-9_-]{11})",
            "[?&]v=([a-zA-Z0-9_-]{11})",
            "youtube\\.com/shorts/([a-zA-Z0-9_-]{11})",
            "youtube\\.com/live/([a-zA-Z0-9_-]{11})",
            "youtube\\.com/embed/([a-zA-Z0-9_-]{11})",
            "youtube\\.com/v/([a-zA-Z0-9_-]{11})"
        )
        for (regex in patterns) {
            val m = Pattern.compile(regex).matcher(url)
            if (m.find()) {
                return m.group(1)
            }
        }
        return null
    }

    private val ALL_STRATEGIES = listOf(
        YouTubeStrategy.NEWPIPE,
        YouTubeStrategy.ANDROID,
        YouTubeStrategy.IOS,
        YouTubeStrategy.ADAPTIVE
    )

    /** Ordine delle strategie da provare, escludendo quelle che hanno già prodotto URL rifiutati (HTTP 403). */
    fun strategyOrder(excluded: Set<YouTubeStrategy>): List<YouTubeStrategy> {
        return ALL_STRATEGIES.filter { it !in excluded }.ifEmpty { ALL_STRATEGIES }
    }

    /**
     * Risolve un URL YouTube in un flusso riproducibile da ExoPlayer provando le strategie in ordine.
     * [excluded] contiene le strategie i cui URL sono stati rifiutati durante la riproduzione (HTTP 403):
     * ripetere la stessa strategia porterebbe allo stesso errore.
     */
    suspend fun resolveStream(
        rawUrl: String,
        excluded: Set<YouTubeStrategy> = emptySet()
    ): Result<ResolvedYouTubeStream> = withContext(Dispatchers.IO) {
        val cleanUrl = extractUrlFromSharedText(rawUrl) ?: rawUrl.trim()
        val videoId = extractVideoId(cleanUrl)
        val canonicalUrl = if (videoId != null) {
            "https://www.youtube.com/watch?v=$videoId"
        } else {
            cleanUrl
        }

        var androidResponse: JSONObject? = null
        var lastError: Throwable? = null

        for (strategy in strategyOrder(excluded)) {
            // Le chiamate sono bloccanti: dopo un cambio video non ha senso provare le strategie successive
            ensureActive()
            val attempt = runCatching {
                when (strategy) {
                    YouTubeStrategy.ANDROID, YouTubeStrategy.ADAPTIVE -> {
                        val id = videoId ?: throw IllegalStateException("ID video YouTube non riconosciuto")
                        val json = androidResponse ?: fetchInnerTubePlayer(
                            videoId = id,
                            clientName = "ANDROID",
                            clientVersion = ANDROID_YT_VERSION,
                            userAgent = ANDROID_YT_UA,
                            extraClientFields = mapOf("androidSdkVersion" to 34)
                        ).also { androidResponse = it }
                        pickFromPlayerResponse(
                            json = json,
                            videoId = id,
                            strategy = strategy,
                            userAgent = ANDROID_YT_UA,
                            allowAdaptive = strategy == YouTubeStrategy.ADAPTIVE
                        )
                    }
                    YouTubeStrategy.IOS -> {
                        val id = videoId ?: throw IllegalStateException("ID video YouTube non riconosciuto")
                        val json = fetchInnerTubePlayer(
                            videoId = id,
                            clientName = "IOS",
                            clientVersion = IOS_YT_VERSION,
                            userAgent = IOS_YT_UA,
                            extraClientFields = mapOf(
                                "deviceMake" to "Apple",
                                "deviceModel" to "iPhone16,2",
                                "osName" to "iPhone",
                                "osVersion" to "18.2.1.22C161"
                            )
                        )
                        pickFromPlayerResponse(json, id, strategy, IOS_YT_UA, allowAdaptive = false)
                    }
                    YouTubeStrategy.NEWPIPE -> {
                        ensureInitialized()
                        val info = StreamInfo.getInfo(ServiceList.YouTube, canonicalUrl)
                        selectBestStreamFromNewPipe(info, videoId ?: info.id ?: "")
                    }
                }
            }
            val resolved = attempt.getOrNull()
            if (resolved != null) {
                Log.i(
                    TAG,
                    "YouTube risolto con $strategy per videoId=$videoId: ${resolved.resolutionLabel} " +
                        "(hls=${resolved.hlsUrl != null}, audioSeparato=${resolved.audioUrl != null})"
                )
                return@withContext Result.success(resolved)
            }
            lastError = attempt.exceptionOrNull()
            Log.w(TAG, "YouTube $strategy fallito per $videoId: ${lastError?.message}")
        }

        Result.failure(lastError ?: IllegalStateException("Impossibile estrarre lo stream YouTube per $videoId"))
    }

    private fun fetchInnerTubePlayer(
        videoId: String,
        clientName: String,
        clientVersion: String,
        userAgent: String,
        extraClientFields: Map<String, Any>
    ): JSONObject {
        val clientObj = JSONObject().apply {
            put("clientName", clientName)
            put("clientVersion", clientVersion)
            put("hl", "it")
            put("gl", "IT")
            for ((k, v) in extraClientFields) {
                put(k, v)
            }
        }
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", JSONObject().apply {
                put("client", clientObj)
            })
        }

        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} da InnerTube ($clientName)")
            }
            val bodyStr = resp.body?.string() ?: throw IllegalStateException("Risposta vuota da InnerTube ($clientName)")
            return JSONObject(bodyStr)
        }
    }

    /**
     * Sceglie il flusso riproducibile da una risposta InnerTube /player (funzione pura, senza rete).
     * Con [allowAdaptive] = false accetta solo HLS o formati muxed: i formati adattivi senza poToken
     * vengono rifiutati da googlevideo con HTTP 403.
     */
    fun pickFromPlayerResponse(
        json: JSONObject,
        videoId: String,
        strategy: YouTubeStrategy,
        userAgent: String,
        allowAdaptive: Boolean
    ): ResolvedYouTubeStream {
        val clientName = strategy.name
        val headersForPlayback = mapOf("User-Agent" to userAgent)

        val playability = json.optJSONObject("playabilityStatus")
        val status = playability?.optString("status") ?: ""
        if (status != "OK" && status.isNotBlank()) {
            val reason = playability?.optString("reason")?.takeIf { it.isNotBlank() } ?: status
            throw IllegalStateException("Video non riproducibile ($clientName: $reason)")
        }

        val details = json.optJSONObject("videoDetails")
        val title = details?.optString("title")?.takeIf { it.isNotBlank() } ?: "YouTube ($videoId)"
        val author = details?.optString("author")?.takeIf { it.isNotBlank() } ?: "YouTube"
        val lengthSec = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
        val durationMs = if (lengthSec > 0L) lengthSec * 1000L else 0L
        val isLive = details?.optBoolean("isLive", false) == true ||
            details?.optBoolean("isLiveContent", false) == true

        val streamingData = json.optJSONObject("streamingData")
            ?: throw IllegalStateException("streamingData non presente per $videoId ($clientName)")

        // 1. HLS: indispensabile per le dirette
        val hlsManifestUrl = streamingData.optString("hlsManifestUrl").takeIf { it.isNotBlank() }
        if (hlsManifestUrl != null) {
            return ResolvedYouTubeStream(
                videoId = videoId,
                title = title,
                uploader = author,
                hlsUrl = hlsManifestUrl,
                resolutionLabel = if (isLive) "🔴 HLS Live" else "HLS HD",
                httpHeaders = headersForPlayback,
                durationMs = durationMs,
                strategy = strategy
            )
        }

        if (isLive) {
            throw IllegalStateException("Live stream senza hlsManifestUrl su $clientName")
        }

        // 2. Formati muxed (video+audio, es. itag 18): accettano Range: bytes=0- senza poToken.
        //    La strategia ADAPTIVE li salta: si usa solo dopo che proprio questi URL sono stati rifiutati.
        val formats = streamingData.optJSONArray("formats")
        if (strategy != YouTubeStrategy.ADAPTIVE && formats != null && formats.length() > 0) {
            var bestUrl: String? = null
            var bestHeight = -1
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val url = f.optString("url").takeIf { it.isNotBlank() } ?: continue
                val height = f.optInt("height", 360)
                if (height >= bestHeight) {
                    bestHeight = height
                    bestUrl = url
                }
            }
            if (bestUrl != null) {
                return ResolvedYouTubeStream(
                    videoId = videoId,
                    title = title,
                    uploader = author,
                    videoUrl = bestUrl,
                    resolutionLabel = "${bestHeight}p MP4",
                    httpHeaders = headersForPlayback,
                    durationMs = durationMs,
                    strategy = strategy
                )
            }
        }

        // 3. Formati adattivi (H.264 + audio M4A): solo se ammessi, senza poToken googlevideo risponde 403
        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
        if (allowAdaptive && adaptiveFormats != null && adaptiveFormats.length() > 0) {
            val pickedAdaptive = pickBestAdaptiveFromInnerTube(adaptiveFormats)
            if (pickedAdaptive != null) {
                return ResolvedYouTubeStream(
                    videoId = videoId,
                    title = title,
                    uploader = author,
                    videoUrl = pickedAdaptive.first,
                    audioUrl = pickedAdaptive.second,
                    resolutionLabel = pickedAdaptive.third,
                    httpHeaders = headersForPlayback,
                    durationMs = durationMs,
                    strategy = strategy
                )
            }
        }

        throw IllegalStateException("Nessun formato diretto disponibile per $videoId ($clientName)")
    }

    /**
     * Seleziona da `adaptiveFormats` di InnerTube il miglior stream video H.264 (`avc1`, max 1080p)
     * e il miglior stream audio AAC (`mp4a`, es. itag 140) per `MergingMediaSource`.
     */
    private fun pickBestAdaptiveFromInnerTube(adaptiveFormats: JSONArray): Triple<String, String?, String>? {
        data class AdaptiveCandidate(
            val url: String,
            val height: Int,
            val fps: Int,
            val isAvc1: Boolean,
            val isMp4: Boolean,
            val bitrate: Int,
            val label: String
        )

        val videoCandidates = mutableListOf<AdaptiveCandidate>()
        var bestAudioUrl: String? = null
        var bestAudioScore = -1

        for (i in 0 until adaptiveFormats.length()) {
            val f = adaptiveFormats.optJSONObject(i) ?: continue
            val url = f.optString("url").takeIf { it.isNotBlank() } ?: continue
            val mime = f.optString("mimeType").lowercase()
            val bitrate = f.optInt("bitrate", 0)

            if (mime.startsWith("video/")) {
                val height = f.optInt("height", 0)
                if (height !in 144..1080) continue
                val isAvc1 = mime.contains("avc1")
                val isVp9 = mime.contains("vp09") || mime.contains("vp9")
                // Evitiamo AV1 (av01) per garantire decodifica hardware fluida senza cali di frame
                if (!isAvc1 && !isVp9) continue
                val isMp4 = mime.contains("video/mp4")
                val fps = f.optInt("fps", 30)
                val qLabel = f.optString("qualityLabel").ifBlank { "${height}p" }
                videoCandidates.add(
                    AdaptiveCandidate(
                        url = url,
                        height = height,
                        fps = fps,
                        isAvc1 = isAvc1,
                        isMp4 = isMp4,
                        bitrate = bitrate,
                        label = qLabel
                    )
                )
            } else if (mime.startsWith("audio/")) {
                val isM4a = mime.contains("audio/mp4") || mime.contains("mp4a")
                val itag = f.optInt("itag", 0)
                // Preferiamo itag 140 (AAC LC 128kbps) rispetto a HE-AAC o Opus
                val score = bitrate + (if (isM4a) 500_000 else 0) + (if (itag == 140) 200_000 else 0)
                if (score > bestAudioScore) {
                    bestAudioScore = score
                    bestAudioUrl = url
                }
            }
        }

        val bestVideo = videoCandidates.sortedWith(
            compareByDescending<AdaptiveCandidate> { it.isAvc1 }
                .thenByDescending { it.height }
                .thenByDescending { it.fps }
                .thenByDescending { it.isMp4 }
                .thenByDescending { it.bitrate }
        ).firstOrNull() ?: return null

        return Triple(bestVideo.url, bestAudioUrl, bestVideo.label)
    }

    private fun selectBestStreamFromNewPipe(info: StreamInfo, videoId: String): ResolvedYouTubeStream {
        val title = info.name?.takeIf { it.isNotBlank() } ?: "YouTube Video ($videoId)"
        val uploader = info.uploaderName?.takeIf { it.isNotBlank() } ?: "YouTube"
        val isLive = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        val durationMs = if (!isLive && info.duration > 0L) info.duration * 1000L else 0L

        // Se è una diretta o dispone di manifesto HLS, usiamo direttamente l'HLS (supporta 60fps e audio nativo)
        val hls = info.hlsUrl?.takeIf { it.isNotBlank() }
        if (hls != null) {
            return ResolvedYouTubeStream(
                videoId = videoId,
                title = title,
                uploader = uploader,
                hlsUrl = hls,
                resolutionLabel = if (isLive) "🔴 HLS Live" else "HLS adattivo",
                httpHeaders = mapOf("User-Agent" to WEB_USER_AGENT),
                durationMs = durationMs,
                strategy = YouTubeStrategy.NEWPIPE
            )
        }

        // Cerchiamo prima uno stream adattivo 1080p / 720p60 / 720p (MP4 preferito) + miglior audio M4A/WebM
        val bestAdaptiveVideo = pickBestVideoStream(info.videoOnlyStreams ?: emptyList(), maxHeight = 1080)
        val bestAudio = (info.audioStreams ?: emptyList())
            .filter { it.content.isNotBlank() }
            .maxByOrNull { (it.averageBitrate.coerceAtLeast(0)) + if (it.format?.suffix == "m4a") 50 else 0 }

        val bestMuxedVideo = pickBestVideoStream(info.videoStreams ?: emptyList(), maxHeight = 1080)

        // Se abbiamo uno stream muxed >= 720p, è immediato e contiene già video+audio sincronizzati
        val muxedHeight = parseHeight(bestMuxedVideo?.getResolution())
        if (bestMuxedVideo != null && muxedHeight >= 720) {
            return ResolvedYouTubeStream(
                videoId = videoId,
                title = title,
                uploader = uploader,
                videoUrl = bestMuxedVideo.content,
                audioUrl = null,
                resolutionLabel = bestMuxedVideo.getResolution().ifBlank { "HD" },
                httpHeaders = mapOf("User-Agent" to WEB_USER_AGENT),
                durationMs = durationMs,
                strategy = YouTubeStrategy.NEWPIPE
            )
        }

        // Altrimenti se abbiamo un adaptive HD (es. 1080p60 / 720p60) lo abbiniamo alla traccia audio per MergingMediaSource
        if (bestAdaptiveVideo != null) {
            return ResolvedYouTubeStream(
                videoId = videoId,
                title = title,
                uploader = uploader,
                videoUrl = bestAdaptiveVideo.content,
                audioUrl = bestAudio?.content,
                resolutionLabel = bestAdaptiveVideo.getResolution().ifBlank { "HD" },
                httpHeaders = mapOf("User-Agent" to WEB_USER_AGENT),
                durationMs = durationMs,
                strategy = YouTubeStrategy.NEWPIPE
            )
        }

        // Fallback su qualsiasi stream muxed disponibile (es. 360p/480p)
        if (bestMuxedVideo != null) {
            return ResolvedYouTubeStream(
                videoId = videoId,
                title = title,
                uploader = uploader,
                videoUrl = bestMuxedVideo.content,
                audioUrl = null,
                resolutionLabel = bestMuxedVideo.getResolution().ifBlank { "SD" },
                httpHeaders = mapOf("User-Agent" to WEB_USER_AGENT),
                durationMs = durationMs,
                strategy = YouTubeStrategy.NEWPIPE
            )
        }

        throw IllegalStateException("Nessuno stream video compatibile trovato per $title")
    }

    private fun pickBestVideoStream(streams: List<VideoStream>, maxHeight: Int): VideoStream? {
        return streams
            .filter { it.content.isNotBlank() }
            .sortedWith(
                compareByDescending<VideoStream> {
                    val h = parseHeight(it.getResolution())
                    if (h in 1..maxHeight) h else 0
                }.thenByDescending {
                    it.fps
                }.thenByDescending {
                    if (it.format?.suffix.equals("mp4", ignoreCase = true)) 1 else 0
                }
            )
            .firstOrNull()
    }

    private fun parseHeight(resolution: String?): Int {
        if (resolution.isNullOrBlank()) return 0
        val m = Pattern.compile("(\\d{3,4})p").matcher(resolution)
        return if (m.find()) m.group(1)?.toIntOrNull() ?: 0 else 0
    }

    /**
     * Ricerca video e live su YouTube direttamente da Android Auto.
     * Usa prima l'API InnerTube WEB (molto veloce) e come fallback NewPipeExtractor.
     */
    suspend fun searchVideos(query: String): Result<List<YouTubeSearchResult>> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext Result.success(emptyList())

        // 1. Tentativo veloce tramite InnerTube Search
        val innerTubeRes = runCatching {
            searchViaInnerTube(cleanQuery)
        }
        if (innerTubeRes.isSuccess && innerTubeRes.getOrNull().orEmpty().isNotEmpty()) {
            return@withContext innerTubeRes
        }

        // 2. Fallback tramite NewPipeExtractor
        return@withContext runCatching {
            ensureInitialized()
            val extractor = ServiceList.YouTube.getSearchExtractor(cleanQuery)
            extractor.fetchPage()
            val items = extractor.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { item ->
                    val url = item.url ?: return@mapNotNull null
                    val vid = extractVideoId(url) ?: return@mapNotNull null
                    val durationSec = item.duration
                    val durLabel = if (durationSec <= 0L) {
                        "🔴 LIVE"
                    } else {
                        val mins = durationSec / 60
                        val secs = durationSec % 60
                        String.format("%d:%02d", mins, secs)
                    }
                    YouTubeSearchResult(
                        videoId = vid,
                        title = item.name ?: "Video YouTube",
                        uploader = item.uploaderName ?: "YouTube",
                        durationOrBadge = durLabel,
                        url = "https://www.youtube.com/watch?v=$vid"
                    )
                }
            items
        }
    }

    private fun searchViaInnerTube(query: String): List<YouTubeSearchResult> {
        val payload = JSONObject().apply {
            put("query", query)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20250201.00.00")
                    put("hl", "it")
                    put("gl", "IT")
                })
            })
        }

        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
            .header("User-Agent", WEB_USER_AGENT)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        okHttpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: return emptyList()
            val root = JSONObject(bodyStr)
            val results = mutableListOf<YouTubeSearchResult>()
            collectVideoRenderers(root, results)
            return results.distinctBy { it.videoId }.take(30)
        }
    }

    private fun collectVideoRenderers(node: Any?, out: MutableList<YouTubeSearchResult>) {
        if (out.size >= 35) return
        when (node) {
            is JSONObject -> {
                val vr = node.optJSONObject("videoRenderer")
                if (vr != null) {
                    val videoId = vr.optString("videoId").trim()
                    if (videoId.isNotEmpty()) {
                        val title = extractRunsText(vr.optJSONObject("title")).ifBlank { "YouTube ($videoId)" }
                        val owner = extractRunsText(vr.optJSONObject("ownerText"))
                            .ifBlank { extractRunsText(vr.optJSONObject("longBylineText")) }
                            .ifBlank { "YouTube" }
                        val lengthText = vr.optJSONObject("lengthText")?.optString("simpleText")
                            ?: extractRunsText(vr.optJSONObject("lengthText"))
                        val durBadge = if (lengthText.isBlank()) "🔴 LIVE" else lengthText
                        out.add(
                            YouTubeSearchResult(
                                videoId = videoId,
                                title = title,
                                uploader = owner,
                                durationOrBadge = durBadge,
                                url = "https://www.youtube.com/watch?v=$videoId"
                            )
                        )
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    collectVideoRenderers(node.opt(k), out)
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until node.length()) {
                    collectVideoRenderers(node.opt(i), out)
                }
            }
        }
    }

    private fun extractRunsText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        val runs = obj.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text") ?: "")
        }
        return sb.toString().trim()
    }
}
