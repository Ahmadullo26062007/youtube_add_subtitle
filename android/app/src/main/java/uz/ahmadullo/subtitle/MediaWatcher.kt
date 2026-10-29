package uz.ahmadullo.subtitle

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Faqat MediaSession'larga kirish huquqi uchun kerak: Android
 * getActiveSessions() ni yoqilgan NotificationListener'i bor ilovaga beradi.
 */
class MediaListener : NotificationListenerService()

/**
 * YouTube ilovasining MediaSession'ini kuzatadi: video nomi, holat,
 * pozitsiya va tezlik.
 */
class MediaWatcher(
    context: Context,
    private val onChange: () -> Unit
) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(app, MediaListener::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var controller: MediaController? = null

    var title: String = ""
        private set

    var state: Int = PlaybackState.STATE_NONE
        private set

    private var position = 0L
    private var updateTime = 0L
    private var speed = 1f

    /** Ruxsat berilmagan bo'lsa false */
    var hasAccess = true
        private set

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(s: PlaybackState?) {
            readState(s)
            onChange()
        }

        override fun onMetadataChanged(m: MediaMetadata?) {
            readMetadata(m)
            onChange()
        }

        override fun onSessionDestroyed() {
            attach(null)
            refresh()
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { refresh() }

    fun start() {
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            hasAccess = true
        } catch (e: SecurityException) {
            hasAccess = false
        }
        refresh()
    }

    fun stop() {
        try {
            manager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
            // e'tibor bermaymiz
        }
        attach(null)
    }

    fun refresh() {
        val sessions = try {
            manager.getActiveSessions(component).also { hasAccess = true }
        } catch (e: SecurityException) {
            hasAccess = false
            emptyList()
        }
        val found = sessions.firstOrNull { it.packageName in YOUTUBE_PACKAGES }

        if (found?.sessionToken != controller?.sessionToken) attach(found)
        onChange()
    }

    private fun attach(next: MediaController?) {
        controller?.unregisterCallback(callback)
        controller = next

        if (next == null) {
            title = ""
            state = PlaybackState.STATE_NONE
            return
        }

        next.registerCallback(callback, handler)
        readMetadata(next.metadata)
        readState(next.playbackState)
    }

    private fun readMetadata(m: MediaMetadata?) {
        title = SubtitleStore.normalize(m?.getString(MediaMetadata.METADATA_KEY_TITLE))
    }

    private fun readState(s: PlaybackState?) {
        if (s == null) {
            state = PlaybackState.STATE_NONE
            return
        }
        state = s.state
        position = s.position
        updateTime = s.lastPositionUpdateTime
        speed = s.playbackSpeed
        Log.d(TAG, "state=${s.state} pos=${s.position} speed=${s.playbackSpeed}")
    }

    val isPlaying get() = state == PlaybackState.STATE_PLAYING

    /** Joriy pozitsiya, sekundda (oxirgi yangilanishdan beri o'tgan vaqt qo'shiladi). */
    fun positionSeconds(): Double {
        var ms = position.toDouble()
        if (isPlaying && updateTime > 0) {
            ms += (SystemClock.elapsedRealtime() - updateTime) * speed.toDouble()
        }
        return ms / 1000.0
    }

    companion object {
        const val TAG = "SubOverlay"

        val YOUTUBE_PACKAGES = setOf(
            "com.google.android.youtube",
            "app.revanced.android.youtube",
            "app.rvx.android.youtube"
        )

        fun findYoutube(context: Context): MediaController? {
            val manager = context.getSystemService(MediaSessionManager::class.java)
            return findYoutube(manager, ComponentName(context, MediaListener::class.java))
        }

        private fun findYoutube(manager: MediaSessionManager, component: ComponentName): MediaController? =
            try {
                manager.getActiveSessions(component)
                    .firstOrNull { it.packageName in YOUTUBE_PACKAGES }
            } catch (e: SecurityException) {
                null
            }
    }
}
