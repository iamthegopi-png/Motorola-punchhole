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
    val ropeBraidColor: Int,
    val bodyColor: Int,
    val secondaryColor: Int,
    val accentColor: Int,
    val eyeColor: Int
) {
    MOCHI_CAT(
        displayName = "Mochi the Cat",
        ropeColor = Color.parseColor("#F4A261"),      // Warm yarn/hemp rope
        ropeBraidColor = Color.parseColor("#E76F51"), // Braided yarn stripe
        bodyColor = Color.parseColor("#FFF8F0"),      // Cream studio fur
        secondaryColor = Color.parseColor("#FFB5A7"), // Pink inner ears & paws
        accentColor = Color.parseColor("#F77F00"),    // Calico ginger patch & collar
        eyeColor = Color.parseColor("#2B2D42")        // Deep glossy anime eyes
    ),
    SHADOW_NINJA(
        displayName = "Kuro the Ninja",
        ropeColor = Color.parseColor("#E2C08D"),      // Braided shinobi cord
        ropeBraidColor = Color.parseColor("#B58E58"), // Dark hemp twist
        bodyColor = Color.parseColor("#222738"),      // Cel-shaded obsidian suit
        secondaryColor = Color.parseColor("#FFE0B2"), // Skin tone face slit
        accentColor = Color.parseColor("#FF3366"),    // Flowing crimson scarf
        eyeColor = Color.parseColor("#00F5D4")        // Glowing cyan ninja eyes
    ),
    ASTRO_BOT(
        displayName = "Astro Neko-Bot",
        ropeColor = Color.parseColor("#90E0EF"),      // High-tech plasma tether
        ropeBraidColor = Color.parseColor("#00B4D8"), // Cyan energy coil
        bodyColor = Color.parseColor("#F8F9FA"),      // Glossy white ceramic armor
        secondaryColor = Color.parseColor("#1B1F3B"), // Dark OLED visor face
        accentColor = Color.parseColor("#FFBE0B"),    // Gold antenna & jetpack core
        eyeColor = Color.parseColor("#00F5D4")        // Digital LED visor eyes
    ),
    KITSUNE_SPIRIT(
        displayName = "Kitsune Fox",
        ropeColor = Color.parseColor("#95D5B2"),      // Spirit vine rope
        ropeBraidColor = Color.parseColor("#52B788"), // Emerald vine twist
        bodyColor = Color.parseColor("#FF7B00"),      // Sunset fox orange
        secondaryColor = Color.parseColor("#FFF3B0"), // Fluffy cream chest & tail tip
        accentColor = Color.parseColor("#FFD166"),    // Golden shrine bell
        eyeColor = Color.parseColor("#3C096C")        // Mystical violet eyes
    )
}

data class CompanionConfig(
    val ropeLengthDp: Float = 175f,
    val ropeThicknessDp: Float = 4.0f,
    val intervalMinutes: Int = 5,
    val allowEdgeWalk: Boolean = true,
    val reactToNotifications: Boolean = true,
    val danceToMusic: Boolean = true,
    val autoSwapCharacters: Boolean = true, // Automatically swaps character on each visit
    val xOffsetPx: Int = 0,
    val yOffsetPx: Int = 0,
    val skin: CharacterSkin = CharacterSkin.MOCHI_CAT
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
        val savedSkinName = prefs.getString("skin", CharacterSkin.MOCHI_CAT.name)
        val resolvedSkin = runCatching {
            CharacterSkin.valueOf(savedSkinName ?: CharacterSkin.MOCHI_CAT.name)
        }.getOrDefault(CharacterSkin.MOCHI_CAT)

        _config.value = CompanionConfig(
            ropeLengthDp = prefs.getFloat("ropeLengthDp", 175f),
            ropeThicknessDp = prefs.getFloat("ropeThicknessDp", 4.0f),
            intervalMinutes = prefs.getInt("intervalMinutes", 5),
            allowEdgeWalk = prefs.getBoolean("allowEdgeWalk", true),
            reactToNotifications = prefs.getBoolean("reactToNotifications", true),
            danceToMusic = prefs.getBoolean("danceToMusic", true),
            autoSwapCharacters = prefs.getBoolean("autoSwapCharacters", true),
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
            putBoolean("autoSwapCharacters", updated.autoSwapCharacters)
            putInt("xOffsetPx", updated.xOffsetPx)
            putInt("yOffsetPx", updated.yOffsetPx)
            putString("skin", updated.skin.name)
            apply()
        }
    }
}
