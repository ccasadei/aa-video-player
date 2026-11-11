package com.casadei.aavideoscreensaver.player

import android.content.Context
import com.casadei.aavideoscreensaver.model.ScreensaverItem
import com.casadei.aavideoscreensaver.model.SourceType
import com.casadei.aavideoscreensaver.model.TapActionMode
import com.casadei.aavideoscreensaver.model.VideoScaleMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Gestisce il salvataggio persistente della playlist salvaschermo (inclusi gli header HTTP per StreamingCommunity/VixCloud),
 * delle posizioni di ripresa automatica (resume) per ciascun video e delle preferenze (scala, mute, tap).
 */
object ScreensaverRepository {

    private const val PREFS_NAME = "aa_video_screensaver_prefs"
    private const val KEY_PLAYLIST = "playlist_json_v1"
    private const val KEY_PLAYBACK_POSITIONS = "playback_positions_json_v1"
    private const val KEY_LAST_INDEX = "last_played_index"
    private const val KEY_IS_MUTED = "is_muted"
    private const val KEY_SCALE_MODE = "scale_mode"
    private const val KEY_SCALE_MODE_ORIGINAL_MIGRATED = "scale_mode_original_migrated"
    private const val KEY_DEMO_STREAMS_REMOVED = "demo_streams_removed"
    private const val KEY_REPEAT_ALL = "repeat_all"
    private const val KEY_TAP_MODE = "tap_action_mode"
    private const val KEY_LAST_SC_DOMAIN = "last_sc_domain"

    data class SavedProgress(
        val positionMs: Long,
        val durationMs: Long,
        val updatedAtMs: Long
    )

    fun loadPlaylist(context: Context): List<ScreensaverItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PLAYLIST, null)
        if (raw.isNullOrBlank()) {
            return emptyList()
        }

        val savedMap = loadAllSavedProgress(context)

        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ScreensaverItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                val title = obj.optString("title").ifBlank { "Video #${i + 1}" }
                val uriOrUrl = obj.optString("uriOrUrl")
                if (uriOrUrl.isBlank()) continue
                val typeStr = obj.optString("sourceType", SourceType.DIRECT_STREAM.name)
                val sourceType = runCatching { SourceType.valueOf(typeStr) }.getOrDefault(SourceType.DIRECT_STREAM)
                val addedAt = obj.optLong("addedAtMs", System.currentTimeMillis())

                val headersMap = mutableMapOf<String, String>()
                val headersObj = obj.optJSONObject("httpHeaders")
                if (headersObj != null) {
                    val keys = headersObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = headersObj.optString(k)
                        if (k.isNotBlank() && v.isNotBlank()) {
                            headersMap[k] = v
                        }
                    }
                }

                val progressFromMap = savedMap[normalizeProgressKey(uriOrUrl)]
                val lastPos = obj.optLong("lastPositionMs", progressFromMap?.positionMs ?: 0L)
                val dur = obj.optLong("durationMs", progressFromMap?.durationMs ?: 0L)

                list.add(
                    ScreensaverItem(
                        id = id,
                        title = title,
                        uriOrUrl = uriOrUrl,
                        sourceType = sourceType,
                        addedAtMs = addedAt,
                        httpHeaders = headersMap,
                        lastPositionMs = lastPos,
                        durationMs = dur
                    )
                )
            }
            if (!prefs.getBoolean(KEY_DEMO_STREAMS_REMOVED, false)) {
                // Le versioni precedenti precaricavano 4 stream demo: non sono voluti, si tolgono una volta sola
                val cleaned = PlaylistOps.withoutDemoItems(list)
                savePlaylist(context, cleaned)
                prefs.edit().putBoolean(KEY_DEMO_STREAMS_REMOVED, true).apply()
                cleaned
            } else {
                list
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun savePlaylist(context: Context, items: List<ScreensaverItem>) {
        val arr = JSONArray()
        for (item in items) {
            val headersObj = JSONObject()
            for ((k, v) in item.httpHeaders) {
                headersObj.put(k, v)
            }
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("uriOrUrl", item.uriOrUrl)
                put("sourceType", item.sourceType.name)
                put("addedAtMs", item.addedAtMs)
                put("httpHeaders", headersObj)
                put("lastPositionMs", item.lastPositionMs)
                put("durationMs", item.durationMs)
            }
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLAYLIST, arr.toString())
            .apply()
    }

    fun normalizeProgressKey(uriOrUrl: String): String {
        val clean = uriOrUrl.trim().lowercase()
        val isScHost = clean.contains("vidxgo.") || clean.contains("d2b.you")
        if (isScHost) {
            // Normalizza stream Serie TV di VidxGo / d2b.you in una chiave unica indipendente dal token temporaneo
            Regex("""(?:v\.vidxgo\.co|/hls/tv)/0*(\d+)/(\d+)/(\d+)""").find(clean)?.let { m ->
                return "sc_tv_${m.groupValues[1]}_s${m.groupValues[2]}_e${m.groupValues[3]}"
            }
            // Normalizza stream Film di VidxGo / d2b.you (sia /hls/movie/0133093 che /hls/0133093)
            Regex("""(?:v\.vidxgo\.co|/hls/(?:movie/)?)/?0*(\d+)(?:\?|$|/)""").find(clean)?.let { m ->
                return "sc_movie_${m.groupValues[1]}"
            }
        }
        if ((clean.contains("streamingcommunity") || clean.contains("streamingunity")) && clean.contains("/watch/")) {
            Regex("""/watch/(\d+)(?:.*[?&](?:e|episode_id)=(\d+))?""").find(clean)?.let { m ->
                val titleId = m.groupValues[1]
                val epId = m.groupValues[2]
                return if (epId.isNotBlank()) "sc_watch_${titleId}_e$epId" else "sc_watch_$titleId"
            }
        }
        // Normalizza URL YouTube all'ID video (così URL diversi dello stesso video condividono il progresso)
        val isYouTubeHost = clean.contains("youtube.com/") || clean.contains("youtu.be/") ||
            clean.contains("youtube-nocookie.com/")
        if (isYouTubeHost) {
            Regex("""(?:[?&]v=|youtu\.be/|/embed/|/shorts/|/live/)([a-z0-9_-]{11})""").find(clean)?.let { m ->
                return "yt_${m.groupValues[1]}"
            }
        }
        // Per URL HLS con token temporanei in query string, rimuove la query string
        if (clean.contains(".m3u8?")) {
            return clean.substringBefore("?")
        }
        return clean
    }

    fun savePlaybackProgress(context: Context, uriOrUrl: String, positionMs: Long, durationMs: Long) {
        if (uriOrUrl.isBlank() || durationMs <= 30_000L) return
        val key = normalizeProgressKey(uriOrUrl)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PLAYBACK_POSITIONS, null)
        val root = runCatching { if (raw != null) JSONObject(raw) else JSONObject() }.getOrElse { JSONObject() }

        // Se siamo oltre il 96% del video o nei primi 5 secondi, azzera la posizione per ripartire da capo la prossima volta
        val effectivePos = if (positionMs < 5_000L || (durationMs > 0 && positionMs.toDouble() / durationMs.toDouble() > 0.96)) {
            0L
        } else {
            positionMs
        }

        val entry = JSONObject().apply {
            put("pos", effectivePos)
            put("dur", durationMs)
            put("ts", System.currentTimeMillis())
        }
        root.put(key, entry)

        prefs.edit().putString(KEY_PLAYBACK_POSITIONS, root.toString()).apply()
    }

    fun clearPlaybackProgress(context: Context, uriOrUrl: String) {
        if (uriOrUrl.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PLAYBACK_POSITIONS, null) ?: return
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (root.remove(normalizeProgressKey(uriOrUrl)) != null) {
            prefs.edit().putString(KEY_PLAYBACK_POSITIONS, root.toString()).apply()
        }
    }

    fun loadSavedProgress(context: Context, uriOrUrl: String): SavedProgress? {
        if (uriOrUrl.isBlank()) return null
        val key = normalizeProgressKey(uriOrUrl)
        return loadAllSavedProgress(context)[key]
    }

    fun loadAllSavedProgress(context: Context): Map<String, SavedProgress> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PLAYBACK_POSITIONS, null) ?: return emptyMap()
        return try {
            val root = JSONObject(raw)
            val map = mutableMapOf<String, SavedProgress>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val obj = root.optJSONObject(k) ?: continue
                val pos = obj.optLong("pos", 0L)
                val dur = obj.optLong("dur", 0L)
                val ts = obj.optLong("ts", 0L)
                if (dur > 0L) {
                    map[k] = SavedProgress(pos, dur, ts)
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun loadLastIndex(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_LAST_INDEX, 0)

    fun saveLastIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_INDEX, index)
            .apply()
    }

    fun loadIsMuted(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_IS_MUTED, true)

    fun saveIsMuted(context: Context, isMuted: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_MUTED, isMuted)
            .apply()
    }

    fun loadScaleMode(context: Context): VideoScaleMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SCALE_MODE_ORIGINAL_MIGRATED, false)) {
            // La vera modalità "Originale" esiste solo da questa versione ed è la predefinita richiesta:
            // la si attiva una volta anche per chi aveva salvato una modalità con la versione precedente.
            prefs.edit()
                .putString(KEY_SCALE_MODE, VideoScaleMode.ORIGINAL.name)
                .putBoolean(KEY_SCALE_MODE_ORIGINAL_MIGRATED, true)
                .apply()
            return VideoScaleMode.ORIGINAL
        }
        return VideoScaleMode.fromStoredName(prefs.getString(KEY_SCALE_MODE, null))
    }

    fun saveScaleMode(context: Context, mode: VideoScaleMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCALE_MODE, mode.name)
            .apply()
    }

    fun loadRepeatAll(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_REPEAT_ALL, false)

    fun saveRepeatAll(context: Context, repeatAll: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REPEAT_ALL, repeatAll)
            .apply()
    }

    fun loadTapActionMode(context: Context): TapActionMode {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TAP_MODE, TapActionMode.OVERLAY_AND_PLAY_PAUSE.name)
        return runCatching { TapActionMode.valueOf(name ?: "") }.getOrDefault(TapActionMode.OVERLAY_AND_PLAY_PAUSE)
    }

    fun saveTapActionMode(context: Context, mode: TapActionMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TAP_MODE, mode.name)
            .apply()
    }

    fun loadLastStreamingCommunityUrl(context: Context): String {
        val defaultDomain = com.casadei.aavideoscreensaver.extractor.StreamingCommunityUrlResolver.getCachedOrDefaultBaseUrl()
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SC_DOMAIN, defaultDomain)
            ?: defaultDomain
        return if (saved.contains("google.com/search", ignoreCase = true) ||
            saved.contains("streaming-community.biz", ignoreCase = true)
        ) {
            defaultDomain
        } else {
            saved
        }
    }

    fun saveLastStreamingCommunityUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SC_DOMAIN, url)
            .apply()
    }
}
