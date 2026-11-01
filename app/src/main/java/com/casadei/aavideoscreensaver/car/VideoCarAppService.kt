package com.casadei.aavideoscreensaver.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.casadei.aavideoscreensaver.player.VideoPlayerController

/**
 * CarAppService registrato nella categoria NAVIGATION (con ACTION_NAVIGATE) per prendere
 * il posto di Google Maps come app di navigazione attiva e occupare il 100% del display 10.25"
 * widescreen su Android Auto Coolwalk (senza Google Maps aperto a lato).
 */
class VideoCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session = VideoCarSession()
}

class VideoCarSession : Session() {

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                // Auto spenta / cavo scollegato: il player non deve continuare a riprodurre sul telefono
                VideoPlayerController.onCarSessionEnded()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        VideoPlayerController.ensureInitialized(carContext)
        return VideoSurfaceScreen(carContext)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        VideoPlayerController.triggerOverlayFor3Seconds()
    }
}
