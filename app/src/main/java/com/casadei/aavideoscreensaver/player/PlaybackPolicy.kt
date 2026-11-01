package com.casadei.aavideoscreensaver.player

/**
 * Decisioni pure sul comportamento del player a fine contenuto.
 */
object PlaybackPolicy {

    enum class EndAction { NEXT, RESTART }

    private const val SUSPICIOUS_END_MS = 3_000L
    private const val LONG_CONTENT_MS = 30_000L

    /**
     * Il player usa REPEAT_MODE_OFF: la fine di ogni video arriva qui e si decide se passare al successivo
     * o ricominciare. Un video lungo che "finisce" pochi secondi dopo l'avvio indica una posizione di ripresa
     * errata: in quel caso si ricomincia invece di far avanzare la playlist.
     */
    fun onEnded(repeatAll: Boolean, playlistSize: Int, playedForMs: Long, durationMs: Long): EndAction {
        val suspiciousEnd = durationMs > LONG_CONTENT_MS && playedForMs < SUSPICIOUS_END_MS
        return if (repeatAll && playlistSize > 1 && !suspiciousEnd) EndAction.NEXT else EndAction.RESTART
    }
}
