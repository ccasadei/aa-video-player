package com.casadei.aavideoscreensaver.player

import androidx.media3.ui.AspectRatioFrameLayout
import com.casadei.aavideoscreensaver.model.VideoScaleMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ScalePolicyTest {

    @Test
    fun `il pulsante scala passa ciclicamente per le tre modalita'`() {
        assertEquals(VideoScaleMode.ZOOM_CROP, VideoScaleMode.ORIGINAL.next())
        assertEquals(VideoScaleMode.STRETCH, VideoScaleMode.ZOOM_CROP.next())
        assertEquals(VideoScaleMode.ORIGINAL, VideoScaleMode.STRETCH.next())
    }

    @Test
    fun `il vecchio valore FIT salvato diventa Originale, che era l'intenzione dell'utente`() {
        // FIT era etichettato "proporzioni originali" ma su Surface diretta stirava l'immagine
        assertEquals(VideoScaleMode.ORIGINAL, VideoScaleMode.fromStoredName("FIT"))
    }

    @Test
    fun `valori salvati validi vengono riletti`() {
        assertEquals(VideoScaleMode.STRETCH, VideoScaleMode.fromStoredName("STRETCH"))
        assertEquals(VideoScaleMode.ORIGINAL, VideoScaleMode.fromStoredName("ORIGINAL"))
    }

    @Test
    fun `valore assente o sconosciuto usa Originale come predefinita`() {
        assertEquals(VideoScaleMode.ORIGINAL, VideoScaleMode.fromStoredName(null))
        assertEquals(VideoScaleMode.ORIGINAL, VideoScaleMode.fromStoredName("boh"))
    }

    @Test
    fun `uno Zoom salvato esplicitamente viene rispettato`() {
        assertEquals(VideoScaleMode.ZOOM_CROP, VideoScaleMode.fromStoredName("ZOOM_CROP"))
    }

    @Test
    fun `lo stato iniziale del player usa Originale`() {
        assertEquals(VideoScaleMode.ORIGINAL, com.casadei.aavideoscreensaver.model.PlayerUiState().scaleMode)
    }

    @Test
    fun `ogni modalita' usa il ridimensionamento AspectRatioFrameLayout corrispondente`() {
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, ScalePolicy.resizeMode(VideoScaleMode.ORIGINAL))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, ScalePolicy.resizeMode(VideoScaleMode.ZOOM_CROP))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, ScalePolicy.resizeMode(VideoScaleMode.STRETCH))
    }
}
