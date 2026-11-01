package com.edge60pro.punchholecompanion

import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class CharacterSkin(
    val displayName: String,
    val ropeColor: Int,
    val bodyColor: Int,
    val accentColor: Int,
    val eyeColor: Int
) {
    SHADOW_NINJA(
        displayName = "Shadow Ninja",
        ropeColor = Color.parseColor("#E2C08D"),
        bodyColor = Color.parseColor("#1E222D"),
        accentColor = Color.parseColor("#FF5252"),
        eyeColor = Color.parseColor("#00E5FF")
    ),
    CYBER_SPIDER(
        displayName = "Cyber Spider",
        ropeColor = Color.parseColor("#E0F7FA"),
        bodyColor = Color.parseColor("#12131A"),
        accentColor = Color.parseColor("#FF007F"),
        eyeColor = Color.parseColor("#FF007F")
    ),
    ASTRO_CADET(
        displayName = "Astro Cadet",
        ropeColor = Color.parseColor("#CFD8DC"),
        bodyColor = Color.parseColor("#F5F7FA"),
        accentColor = Color.parseColor("#FFAB00"),
        eyeColor = Color.parseColor("#FFF8E1")
    ),
    NEON_KODAMA(
        displayName = "Neon Spirit",
        ropeColor = Color.parseColor("#69F0AE"),
        bodyColor = Color.parseColor("#00332C"),
        accentColor = Color.parseColor("#00E676"),
        eyeColor = Color.parseColor("#B9F6CA")
    )
}

data class CompanionConfig(
    val ropeLengthDp: Float = 170f,
    val ropeThicknessDp: Float = 3.2f,
    val intervalMinutes: Int = 5,
    val allowEdgeWalk: Boolean = true,
    val reactToNotifications: Boolean = true,
    val danceToMusic: Boolean = true,
    val xOffsetPx: Int = 0,
    val yOffsetPx: Int = 0,
    val skin: CharacterSkin = CharacterSkin.SHADOW_NINJA
)

sealed class CompanionTriggerEvent {
    data class NotificationAlert(
        val packageName: String,
        val accentColor: Int,
        val contentIntent: PendingIntent?
    ) : CompanionTriggerEvent()

    data class MusicBeatStart(
        val trackTitle: String,
        val artist: String,
        val estimatedBpm: Float,
        val accentColor: Int
    ) : CompanionTriggerEvent()

    object MusicStopped : CompanionTriggerEvent()
}

object CompanionEventBus {
    private val _events = MutableSharedFlow<CompanionTriggerEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CompanionTriggerEvent> = _events

    var isMusicCurrentlyPlaying: Boolean = false
    var activeTrackBpm: Float = 120f
    var lastNotificationIntent: PendingIntent? = null

    fun emit(event: CompanionTriggerEvent) {
        _events.tryEmit(event)
    }
}

object CompanionRepository {
    private const val PREFS_NAME = "companion_prefs"
    private val _config = MutableStateFlow(CompanionConfig())
    val config: StateFlow<CompanionConfig> = _config

    val manualCommand = MutableStateFlow<String?>(null)

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedSkinName = prefs.getString("skin", CharacterSkin.SHADOW_NINJA.name)
        val resolvedSkin = runCatching {
            CharacterSkin.valueOf(savedSkinName ?: CharacterSkin.SHADOW_NINJA.name)
        }.getOrDefault(CharacterSkin.SHADOW_NINJA)

        _config.value = CompanionConfig(
            ropeLengthDp = prefs.getFloat("ropeLengthDp", 170f),
            ropeThicknessDp = prefs.getFloat("ropeThicknessDp", 3.2f),
            intervalMinutes = prefs.getInt("intervalMinutes", 5),
            allowEdgeWalk = prefs.getBoolean("allowEdgeWalk", true),
            reactToNotifications = prefs.getBoolean("reactToNotifications", true),
            danceToMusic = prefs.getBoolean("danceToMusic", true),
            xOffsetPx = prefs.getInt("xOffsetPx", 0),
            yOffsetPx = prefs.getInt("yOffsetPx", 0),
            skin = resolvedSkin
        )
    }

    fun update(context: Context, transform: (CompanionConfig) -> CompanionConfig) {
        val updated = transform(_config.value)
        _config.value = updated
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putFloat("ropeLengthDp", updated.ropeLengthDp)
            putFloat("ropeThicknessDp", updated.ropeThicknessDp)
            putInt("intervalMinutes", updated.intervalMinutes)
            putBoolean("allowEdgeWalk", updated.allowEdgeWalk)
            putBoolean("reactToNotifications", updated.reactToNotifications)
            putBoolean("danceToMusic", updated.danceToMusic)
            putInt("xOffsetPx", updated.xOffsetPx)
            putInt("yOffsetPx", updated.yOffsetPx)
            putString("skin", updated.skin.name)
            apply()
        }
    }
}
