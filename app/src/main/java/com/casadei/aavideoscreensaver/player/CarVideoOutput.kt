package com.casadei.aavideoscreensaver.player

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.casadei.aavideoscreensaver.model.VideoScaleMode

/**
 * Uscita video su Android Auto tramite un VirtualDisplay privato disegnato sulla superficie dell'auto.
 *
 * Sul VirtualDisplay viene mostrata una Presentation con una SurfaceView dentro un AspectRatioFrameLayout:
 * - le proporzioni (Originale / Zoom / Stira) le gestisce il layout, senza effetti GPU nel player
 *   (la catena di effetti di Media3 1.4.1 si bloccava al cambio di contenuto);
 * - quando Android Auto distrugge o sostituisce la superficie (es. passando da "Sfoglia") cambia solo il
 *   target del VirtualDisplay: la Surface su cui scrive il decoder resta sempre valida.
 *
 * Tutti i metodi vanno chiamati sul main thread.
 */
@OptIn(UnstableApi::class)
class CarVideoOutput(private val context: Context) {

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var aspectLayout: AspectRatioFrameLayout? = null
    private var surfaceView: SurfaceView? = null
    private var displayWidth = 0
    private var displayHeight = 0
    private var displayDpi = 0

    /** true se il video passa dal VirtualDisplay (altrimenti si usa la superficie diretta come fallback). */
    val isActive: Boolean
        get() = virtualDisplay != null && surfaceView != null

    /**
     * Collega o sostituisce la superficie dell'auto. Restituisce false se il VirtualDisplay non è disponibile:
     * in quel caso il chiamante deve passare la superficie direttamente al player.
     */
    fun attachCarSurface(
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int,
        player: ExoPlayer,
        mode: VideoScaleMode,
        videoAspectRatio: Float
    ): Boolean {
        val safeDpi = if (dpi > 0) dpi else DEFAULT_DPI
        val existing = virtualDisplay
        if (existing != null && isActive) {
            if (width != displayWidth || height != displayHeight || safeDpi != displayDpi) {
                existing.resize(width, height, safeDpi)
            }
            existing.surface = surface
            rememberSize(width, height, safeDpi)
            return true
        }

        return try {
            val dm = context.getSystemService(DisplayManager::class.java)
            val display = dm.createVirtualDisplay(
                "AAVideoCarOutput",
                width,
                height,
                safeDpi,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            ) ?: return false
            virtualDisplay = display
            rememberSize(width, height, safeDpi)
            showPresentation(display, mode, videoAspectRatio)
            surfaceView?.let { player.setVideoSurfaceView(it) }
            Log.i(TAG, "Uscita video su VirtualDisplay ${width}x$height@${safeDpi}dpi")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "VirtualDisplay/Presentation non disponibile, uso la superficie diretta", t)
            releaseDisplayOnly()
            false
        }
    }

    /** Superficie dell'auto distrutta (o schermata nascosta): il player continua a scrivere sulla SurfaceView. */
    fun detachCarSurface() {
        virtualDisplay?.surface = null
    }

    /** Ricollega il player alla SurfaceView (es. dopo un clearVideoSurface nel recupero di un errore). */
    fun rebindPlayer(player: ExoPlayer) {
        surfaceView?.let { player.setVideoSurfaceView(it) }
    }

    fun setScaleMode(mode: VideoScaleMode) {
        aspectLayout?.resizeMode = ScalePolicy.resizeMode(mode)
    }

    fun setVideoAspectRatio(ratio: Float) {
        if (ratio > 0f) aspectLayout?.setAspectRatio(ratio)
    }

    /** Fine sessione Android Auto: stacca il player e libera Presentation e VirtualDisplay. */
    fun release(player: ExoPlayer?) {
        surfaceView?.let { player?.clearVideoSurfaceView(it) }
        releaseDisplayOnly()
    }

    private fun showPresentation(display: VirtualDisplay, mode: VideoScaleMode, videoAspectRatio: Float) {
        val p = Presentation(context, display.display)
        val root = FrameLayout(p.context).apply { setBackgroundColor(Color.BLACK) }
        val layout = AspectRatioFrameLayout(p.context).apply {
            resizeMode = ScalePolicy.resizeMode(mode)
            if (videoAspectRatio > 0f) setAspectRatio(videoAspectRatio)
        }
        val sv = SurfaceView(p.context)
        layout.addView(
            sv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            layout,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        p.setContentView(root)
        p.show()
        presentation = p
        aspectLayout = layout
        surfaceView = sv
    }

    private fun rememberSize(width: Int, height: Int, dpi: Int) {
        displayWidth = width
        displayHeight = height
        displayDpi = dpi
    }

    private fun releaseDisplayOnly() {
        runCatching { presentation?.dismiss() }
        runCatching { virtualDisplay?.release() }
        presentation = null
        aspectLayout = null
        surfaceView = null
        virtualDisplay = null
    }

    private companion object {
        const val TAG = "AAVideo"
        const val DEFAULT_DPI = 160
    }
}
