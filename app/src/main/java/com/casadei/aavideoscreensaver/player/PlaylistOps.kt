package com.casadei.aavideoscreensaver.player

import com.casadei.aavideoscreensaver.model.ScreensaverItem
import com.casadei.aavideoscreensaver.model.SourceType

/**
 * Operazioni pure sulla playlist, indirizzate per id (mai per indice: l'indice mostrato in una lista
 * può essere già vecchio quando l'utente tocca la riga).
 */
object PlaylistOps {

    data class Removal(
        val playlist: List<ScreensaverItem>,
        val currentIndex: Int,
        val removedActive: Boolean,
        val removedTitle: String
    )

    /** Restituisce null se l'elemento non esiste più (es. doppio tocco su una lista non ancora aggiornata). */
    fun removeById(playlist: List<ScreensaverItem>, currentIndex: Int, itemId: String): Removal? {
        val index = playlist.indexOfFirst { it.id == itemId }
        if (index < 0) return null
        val remaining = playlist.toMutableList().apply { removeAt(index) }
        val lastIndex = (remaining.size - 1).coerceAtLeast(0)
        val newIndex = when {
            index < currentIndex -> currentIndex - 1
            else -> currentIndex
        }.coerceIn(0, lastIndex)
        return Removal(
            playlist = remaining,
            currentIndex = newIndex,
            removedActive = index == currentIndex,
            removedTitle = playlist[index].title
        )
    }

    /** Elementi demo precaricati dalle versioni precedenti (stream di prova non più voluti). */
    fun withoutDemoItems(playlist: List<ScreensaverItem>): List<ScreensaverItem> =
        playlist.filterNot { it.sourceType == SourceType.DEMO_STREAM || it.id.startsWith("demo_") }
}
