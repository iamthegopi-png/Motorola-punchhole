package com.edge60pro.punchholecompanion

import android.app.Notification
import android.content.ComponentName
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlin.math.abs

class CompanionNotificationService : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private val activeControllers = mutableMapOf<String, MediaController>()
    private var lastPlayedTrackId: String? = null
    private var lastNotificationTimeMs = 0L

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        registerMediaControllers(controllers)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, CompanionNotificationService::class.java)

        runCatching {
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, component)
            registerMediaControllers(mediaSessionManager?.getActiveSessions(component))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null || sbn.isOngoing) return

        val notification = sbn.notification ?: return
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary || notification.category == Notification.CATEGORY_TRANSPORT ||
            notification.category == Notification.CATEGORY_SERVICE
        ) return

        val now = System.currentTimeMillis()
        if (now - lastNotificationTimeMs < 4000L) return
        lastNotificationTimeMs = now

        val accentColor = if (notification.color != Notification.COLOR_DEFAULT && notification.color != 0) {
            notification.color or -0x1000000
        } else {
            Color.parseColor("#00E5FF")
        }

        CompanionEventBus.lastNotificationIntent = notification.contentIntent
        CompanionEventBus.emit(
            CompanionTriggerEvent.NotificationAlert(
                packageName = sbn.packageName,
                accentColor = accentColor,
                contentIntent = notification.contentIntent
            )
        )
    }

    private fun registerMediaControllers(controllers: List<MediaController>?) {
        activeControllers.values.forEach { it.unregisterCallback(mediaCallback) }
        activeControllers.clear()

        controllers?.forEach { controller ->
            activeControllers[controller.packageName] = controller
            controller.registerCallback(mediaCallback)
            evaluateControllerState(controller)
        }
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            activeControllers.values.forEach { evaluateControllerState(it) }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            activeControllers.values.forEach { evaluateControllerState(it) }
        }
    }

    private fun evaluateControllerState(controller: MediaController) {
        val state = controller.playbackState?.state
        val metadata = controller.metadata

        if (state == PlaybackState.STATE_PLAYING && metadata != null) {
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val trackKey = "$title|$artist"

            val derivedBpm = 105f + (abs(trackKey.hashCode()) % 38)
            CompanionEventBus.isMusicCurrentlyPlaying = true
            CompanionEventBus.activeTrackBpm = derivedBpm

            if (trackKey != lastPlayedTrackId) {
                lastPlayedTrackId = trackKey
                CompanionEventBus.emit(
                    CompanionTriggerEvent.MusicBeatStart(
                        trackTitle = title,
                        artist = artist,
                        estimatedBpm = derivedBpm,
                        accentColor = Color.parseColor("#B388FF")
                    )
                )
            }
        } else if (state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_STOPPED) {
            val anyPlaying = activeControllers.values.any {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            }
            if (!anyPlaying && CompanionEventBus.isMusicCurrentlyPlaying) {
                CompanionEventBus.isMusicCurrentlyPlaying = false
                lastPlayedTrackId = null
                CompanionEventBus.emit(CompanionTriggerEvent.MusicStopped)
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        runCatching { mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener) }
        activeControllers.values.forEach { it.unregisterCallback(mediaCallback) }
        activeControllers.clear()
    }
}
