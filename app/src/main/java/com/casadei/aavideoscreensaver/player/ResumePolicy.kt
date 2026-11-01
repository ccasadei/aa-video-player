package com.casadei.aavideoscreensaver.player

import kotlin.math.abs

/**
 * Regole pure (senza dipendenze Android) per decidere se una posizione di ripresa salvata è ancora valida.
 */
object ResumePolicy {

    const val MIN_RESUME_MS = 5_000L
    private const val END_GUARD_MS = 5_000L
    private const val DURATION_MISMATCH_MS = 15_000L

    /**
     * Restituisce la posizione da cui ripartire (0 = dall'inizio / punto live).
     * [actualDurationMs] <= 0 significa durata reale non ancora nota: in quel caso la posizione non è verificabile.
     */
    fun sanitize(resumeMs: Long, savedDurationMs: Long, actualDurationMs: Long, isLive: Boolean): Long {
        if (isLive || resumeMs <= MIN_RESUME_MS) return 0L
        if (actualDurationMs <= 0L) return resumeMs
        if (resumeMs >= actualDurationMs - END_GUARD_MS) return 0L
        if (savedDurationMs > 0L && abs(savedDurationMs - actualDurationMs) > DURATION_MISMATCH_MS) return 0L
        return resumeMs
    }
}
