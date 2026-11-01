package com.casadei.aavideoscreensaver.player

import androidx.media3.ui.AspectRatioFrameLayout
import com.casadei.aavideoscreensaver.model.VideoScaleMode

/**
 * Come rendere ciascuna modalità di scala sulla superficie di Android Auto (funzioni pure).
 */
object ScalePolicy {

    /** Modalità di AspectRatioFrameLayout che contiene la SurfaceView del player sul VirtualDisplay dell'auto. */
    fun resizeMode(mode: VideoScaleMode): Int = when (mode) {
        VideoScaleMode.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoScaleMode.ZOOM_CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoScaleMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
}
