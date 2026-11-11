package com.casadei.aavideoscreensaver.extractor

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Risolve dinamicamente gli URL correnti di StreamingCommunity leggendo
 * https://www.streaming-community.day/ che elenca i due bottoni attivi:
 *   - "StreamingCommunity" (`a.btn.sc`) → es. https://streamingcommunityz.nexus
 *   - "StreamingUnity"     (`a.btn.su`) → es. https://streamingunity.dog
 *
 * Restituisce entrambi gli URL estratti da `.day` in [getCandidateBaseUrls] così il catalogo
 * e il player possono provare automaticamente la prima URL e, se bloccata o non raggiungibile,
 * passare subito alla seconda URL indicata da `.day`.
 */
object StreamingCommunityUrlResolver {

    private const val URL_CHECKER = "https://www.streaming-community.day"
    private const val VIDXGO_FRONTEND_CHECKER = "https://streaming-community.toys"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val CACHE_TTL_MS = 5 * 60_000L // 5 minuti

    private val FALLBACK_DOMAINS = listOf(
        "https://streamingcommunityz.nexus",
        "https://streamingunity.dog"
    )

    private const val FALLBACK_VIDXGO_REFERER = "https://streaming-community.toys"

    private val fastHttpClient by lazy {
        YouTubeStreamExtractor.okHttpClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    private val cachedUrls = AtomicReference<CachedUrls?>(null)
    private val preferredBaseUrl = AtomicReference<String?>(null)
    private val cachedVidxgoReferer = AtomicReference<CachedUrl?>(null)

    private data class CachedUrls(
        val urls: List<String>,
        val fetchedAtMs: Long
    )

    private data class CachedUrl(
        val url: String,
        val fetchedAtMs: Long
    )

    fun getCachedOrDefaultBaseUrl(): String =
        preferredBaseUrl.get()
            ?: cachedUrls.get()?.urls?.firstOrNull()
            ?: FALLBACK_DOMAINS.first()

    fun getCachedOrDefaultVidxgoRefererBaseUrl(): String =
        cachedVidxgoReferer.get()?.url ?: FALLBACK_VIDXGO_REFERER

    /**
     * Registra quale delle URL lette da `streaming-community.day` ha risposto con successo,
     * così le richieste successive provano per prima la URL funzionante.
     */
    fun markUrlWorking(url: String) {
        val clean = url.trim().removeSuffix("/")
        if (clean.startsWith("http")) {
            preferredBaseUrl.set(clean)
        }
    }

    /**
     * Restituisce tutte le URL attive lette dinamicamente da `https://www.streaming-community.day/`
     * (es. `["https://streamingcommunityz.nexus", "https://streamingunity.dog"]`).
     * Se una delle due è già stata verificata funzionante ([markUrlWorking]), viene messa in testa.
     */
    suspend fun getCandidateBaseUrls(): List<String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cachedUrls.get()
        val baseList = if (cached != null && (now - cached.fetchedAtMs) < CACHE_TTL_MS && cached.urls.isNotEmpty()) {
            cached.urls
        } else {
            val fetched = runCatching { fetchUrlsFromChecker() }.getOrDefault(emptyList())
            if (fetched.isNotEmpty()) {
                cachedUrls.set(CachedUrls(fetched, now))
                Log.i("AAVideo", "StreamingCommunity URL aggiornate da streaming-community.day: $fetched")
                fetched
            } else {
                Log.w("AAVideo", "StreamingCommunity: fetch da streaming-community.day fallito, uso fallback: $FALLBACK_DOMAINS")
                FALLBACK_DOMAINS
            }
        }

        val pref = preferredBaseUrl.get()
        if (pref != null && pref in baseList && baseList.firstOrNull() != pref) {
            listOf(pref) + baseList.filterNot { it == pref }
        } else {
            baseList
        }
    }

    /**
     * Restituisce l'URL primaria corrente di StreamingCommunity (da streaming-community.day).
     */
    suspend fun getCurrentBaseUrl(): String =
        getCandidateBaseUrls().firstOrNull() ?: FALLBACK_DOMAINS.first()

    /**
     * Restituisce il dominio frontend attivo accettato da `v.vidxgo.co` nell'header `Referer`.
     */
    suspend fun getCurrentVidxgoRefererBaseUrl(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cachedVidxgoReferer.get()
        if (cached != null && (now - cached.fetchedAtMs) < CACHE_TTL_MS) {
            return@withContext cached.url
        }

        val resolved = runCatching {
            val req = Request.Builder()
                .url(VIDXGO_FRONTEND_CHECKER)
                .header("User-Agent", USER_AGENT)
                .head()
                .build()
            fastHttpClient.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url
                "${finalUrl.scheme}://${finalUrl.host}"
            }
        }.getOrNull()

        val target = resolved ?: FALLBACK_VIDXGO_REFERER
        cachedVidxgoReferer.set(CachedUrl(target, now))
        target
    }

    /**
     * Scarica `https://www.streaming-community.day/` ed estrae tutti i bottoni (`a.btn.sc`, `a.btn.su`, `a.btn`).
     */
    private suspend fun fetchUrlsFromChecker(): List<String> {
        val html = runCatching {
            val req = Request.Builder()
                .url(URL_CHECKER)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,*/*;q=0.8")
                .get()
                .build()
            fastHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.string()
            }
        }.getOrNull() ?: runCatching {
            CloudflareWebViewBridge.fetchHtmlViaWebView(URL_CHECKER, timeoutMs = 8_000L)
        }.getOrNull() ?: return emptyList()

        return parseCandidateUrlsFromCheckerHtml(html)
    }

    internal fun parseCandidateUrlsFromCheckerHtml(html: String): List<String> {
        val doc = Jsoup.parse(html, URL_CHECKER)
        val result = mutableListOf<String>()
        val seen = HashSet<String>()

        val elements = doc.select("a.btn.sc[href], a.btn.su[href], a.btn[href]")
        for (el in elements) {
            val href = el.attr("href").trim().removeSuffix("/")
            if (!href.startsWith("http://") && !href.startsWith("https://")) continue
            val lower = href.lowercase()
            if (lower.contains("telegram.") ||
                lower.contains("wa.me") ||
                lower.contains("facebook.") ||
                lower.contains("twitter.") ||
                lower.contains("x.com") ||
                lower.contains("streaming-community.day")
            ) {
                continue
            }
            if (seen.add(lower)) {
                result.add(href)
            }
        }
        return result
    }

    /**
     * Invalida la cache (utile per forzare un refresh manuale).
     */
    fun invalidateCache() {
        cachedUrls.set(null)
        preferredBaseUrl.set(null)
        cachedVidxgoReferer.set(null)
    }
}
