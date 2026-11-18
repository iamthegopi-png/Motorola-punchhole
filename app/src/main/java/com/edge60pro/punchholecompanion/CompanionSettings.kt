package com.edge60pro.punchholecompanion

import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

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
        ropeColor = Color.parseColor("#F4A261"),
        ropeBraidColor = Color.parseColor("#E76F51"),
        bodyColor = Color.parseColor("#FFF8F0"),
        secondaryColor = Color.parseColor("#FFB5A7"),
        accentColor = Color.parseColor("#F77F00"),
        eyeColor = Color.parseColor("#2B2D42")
    ),
    SHADOW_NINJA(
        displayName = "Kuro the Ninja",
        ropeColor = Color.parseColor("#E2C08D"),
        ropeBraidColor = Color.parseColor("#B58E58"),
        bodyColor = Color.parseColor("#222738"),
        secondaryColor = Color.parseColor("#FFE0B2"),
        accentColor = Color.parseColor("#FF3366"),
        eyeColor = Color.parseColor("#00F5D4")
    ),
    ASTRO_BOT(
        displayName = "Astro Neko-Bot",
        ropeColor = Color.parseColor("#90E0EF"),
        ropeBraidColor = Color.parseColor("#00B4D8"),
        bodyColor = Color.parseColor("#F8F9FA"),
        secondaryColor = Color.parseColor("#1B1F3B"),
        accentColor = Color.parseColor("#FFBE0B"),
        eyeColor = Color.parseColor("#00F5D4")
    ),
    KITSUNE_SPIRIT(
        displayName = "Kitsune Fox",
        ropeColor = Color.parseColor("#95D5B2"),
        ropeBraidColor = Color.parseColor("#52B788"),
        bodyColor = Color.parseColor("#FF7B00"),
        secondaryColor = Color.parseColor("#FFF3B0"),
        accentColor = Color.parseColor("#FFD166"),
        eyeColor = Color.parseColor("#3C096C")
    )
}

data class CompanionConfig(
    val userName: String = "Gopi",
    val greetOnScreenOn: Boolean = true,
    val chargingAnimationEnabled: Boolean = true,
    val voiceEnabled: Boolean = true,
    val voiceVolume: Float = 0.85f,
    val ropeLengthDp: Float = 175f,
    val ropeThicknessDp: Float = 4.0f,
    val intervalMinutes: Int = 5,
    val allowEdgeWalk: Boolean = true,
    val reactToNotifications: Boolean = true,
    val danceToMusic: Boolean = true,
    val autoSwapCharacters: Boolean = true,
    val xOffsetPx: Int = 0,
    val yOffsetPx: Int = 0,
    val skin: CharacterSkin = CharacterSkin.MOCHI_CAT,
    val customAssetRevision: Int = 0
)

object CustomAudioAndDialogueEngine {
    private const val VOICE_PREFS = "companion_custom_voice_prefs"
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    var isCurrentlySpeaking: Boolean = false
        private set

    private fun internalMp3File(context: Context, skin: CharacterSkin, isCharging: Boolean): File {
        val kind = if (isCharging) "charge" else "greet"
        return File(context.filesDir, "custom_voice_${skin.name.lowercase()}_$kind.mp3")
    }

    fun saveMp3FromUri(
        context: Context,
        skin: CharacterSkin,
        isCharging: Boolean,
        sourceUri: Uri
    ): Boolean {
        return runCatching {
            val destFile = internalMp3File(context, skin, isCharging)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile, false).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.exists() && destFile.length() > 0L
        }.getOrDefault(false)
    }

    fun deleteSavedMp3(context: Context, skin: CharacterSkin, isCharging: Boolean) {
        runCatching {
            val file = internalMp3File(context, skin, isCharging)
            if (file.exists()) file.delete()
        }
    }

    fun hasSavedMp3(context: Context, skin: CharacterSkin, isCharging: Boolean): Boolean {
        val file = internalMp3File(context, skin, isCharging)
        return file.exists() && file.length() > 0L
    }

    fun setCustomDialogue(
        context: Context,
        skin: CharacterSkin,
        isCharging: Boolean,
        dialogueText: String
    ) {
        val key = "dialogue_${skin.name}_${if (isCharging) "charge" else "greet"}"
        context.getSharedPreferences(VOICE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, dialogueText.trim())
            .apply()
    }

    fun getCustomDialogue(context: Context, skin: CharacterSkin, isCharging: Boolean): String {
        val key = "dialogue_${skin.name}_${if (isCharging) "charge" else "greet"}"
        return context.getSharedPreferences(VOICE_PREFS, Context.MODE_PRIVATE)
            .getString(key, "") ?: ""
    }

    fun playCharacterMp3IfAvailable(
        context: Context,
        skin: CharacterSkin,
        isCharging: Boolean
    ) {
        val cfg = CompanionRepository.config.value
        if (!cfg.voiceEnabled || cfg.voiceVolume <= 0.01f) return

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager?.mode == AudioManager.MODE_IN_CALL ||
            audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION
        ) return

        var audioFile = internalMp3File(context, skin, isCharging)
        if (!audioFile.exists() || audioFile.length() == 0L) {
            val fallback = CharacterSkin.values()
                .map { internalMp3File(context, it, isCharging) }
                .firstOrNull { it.exists() && it.length() > 0L }
            if (fallback != null) {
                audioFile = fallback
            } else {
                return
            }
        }

        stopAndReleaseNow()

        runCatching {
            val vol = cfg.voiceVolume.coerceIn(0f, 1f)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(audioFile.absolutePath)
                setVolume(vol, vol)
                setOnCompletionListener {
                    stopAndReleaseNow()
                }
                setOnErrorListener { _, _, _ ->
                    stopAndReleaseNow()
                    true
                }
                prepare()
                isCurrentlySpeaking = true
                start()
            }
        }.onFailure {
            stopAndReleaseNow()
        }
    }

    fun stopAndReleaseNow() {
        isCurrentlySpeaking = false
        runCatching {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.reset()
                mp.release()
            }
        }
        mediaPlayer = null
    }

    /**
     * Short, cute Hinglish Screen-On greetings (rotates weekly if custom text is blank).
     */
    fun resolveScreenOnBubbleText(context: Context, skin: CharacterSkin, rawName: String): String {
        val name = rawName.trim().ifEmpty { "Dost" }
        val custom = getCustomDialogue(context, skin, isCharging = false)
        if (custom.isNotBlank()) {
            return custom.replace("{name}", name, ignoreCase = true)
        }

        val weekIndex = Calendar.getInstance().get(Calendar.WEEK_OF_YEAR) % 4
        val pool = when (skin) {
            CharacterSkin.MOCHI_CAT -> listOf(
                listOf("Kon-kon? $name hain kya? 🐾", "Meow~ Hi $name! 💕", "Aa gaye $name! ✨"),
                listOf("Kya haal $name? 😺", "Meow! Miss kiya? 🐾", "Chalo masti karein! 🧶"),
                listOf("Oho $name! Chai pi? ☕", "Cute smile $name! ✨", "Hi mere dost $name! 💕"),
                listOf("Purr~ Welcome $name! 🐾", "Super day $name! 🌟", "Kaise ho $name? 😸")
            )[weekIndex]

            CharacterSkin.SHADOW_NINJA -> listOf(
                listOf("Kon-kon? $name Boss? 🥷", "Swagat hai $name! ⚡", "Ninja ready $name! 🍃"),
                listOf("Hukum Boss $name! 🥷", "Kya plan hai $name? ⚡", "Hi $name Boss! ✨"),
                listOf("Nin-Nin! Jai ho $name! 🔥", "Full speed $name! 🥷", "Boss $name aa gaye! 🍃"),
                listOf("Namaste $name Boss! ⚡", "Sab set hai $name! 🥷", "Rock on $name! 🌟")
            )[weekIndex]

            CharacterSkin.ASTRO_BOT -> listOf(
                listOf("Kon-kon? $name hain kya? 🤖", "Hi $name! Dil khush! 🚀", "Hello $name! ✨"),
                listOf("100% Cute $name! 🤖", "Zero-G Salaam $name! 🚀", "Battery happy $name! ⚡"),
                listOf("Chamak rahe ho $name! 🌟", "Hi Captain $name! 🚀", "Kya haal $name? 🤖"),
                listOf("Turbo Hi $name! ✨", "Best friend $name! 🪐", "Good vibes $name! 🤖")
            )[weekIndex]

            CharacterSkin.KITSUNE_SPIRIT -> listOf(
                listOf("Kon-Kon! $name hain kya? 🦊", "Namaste $name ji! 🔔", "Fox magic $name! ✨"),
                listOf("Lucky day $name! 🦊", "Ding-dong! Hi $name! 🔔", "Kaise ho $name? ✨"),
                listOf("Oho $name aa gaye! 🦊", "Smile karo $name! 🌸", "Good luck $name! 🔔"),
                listOf("Hi pyare $name! 🦊", "Chalo khelein $name! ✨", "Kon-Kon $name! 🔔")
            )[weekIndex]
        }
        return pool.random()
    }

    /**
     * Short, cute Hinglish Charging lines.
     */
    fun resolveChargingBubbleText(
        context: Context,
        skin: CharacterSkin,
        rawName: String,
        batteryPct: Int
    ): String {
        val name = rawName.trim().ifEmpty { "Dost" }
        val custom = getCustomDialogue(context, skin, isCharging = true)
        if (custom.isNotBlank()) {
            return custom
                .replace("{name}", name, ignoreCase = true)
                .replace("{battery}", "$batteryPct%", ignoreCase = true)
        }

        return listOf(
            "Charge dene ke liye Thank U! ⚡",
            "Thank U $name! ($batteryPct%) 🔋",
            "Shukriya $name! Full power! ✨",
            "Yummy energy! Thank U $name! ⚡"
        ).random()
    }

    /**
     * Short, silent Hinglish conversations for tricks & idle hangouts (2-4 words).
     */
    fun resolveSilentConversation(skin: CharacterSkin, rawName: String): String {
        val name = rawName.trim().ifEmpty { "Dost" }
        val weekIndex = Calendar.getInstance().get(Calendar.WEEK_OF_YEAR) % 4
        val generalPool = listOf(
            listOf(
                "Sab badhiya $name? ✨",
                "Paani pi lo $name! 💧",
                "Mast hawa hai! 🍃",
                "Jhoola time! 🎪"
            ),
            listOf(
                "Mausam mast hai! 🌤️",
                "Phone tilt karo! 🎢",
                "Dekho mera stunt! ✨",
                "Aaram kar lo $name! 👀"
            ),
            listOf(
                "Kya chal raha hai? 📱",
                "Kheench ke chhodo! 🚀",
                "Makkhan phone hai! ⚡",
                "Muskurao $name! 😊"
            ),
            listOf(
                "Mera chhota ghar! 🏠",
                "Tap pe trick! 🐾",
                "Super hit din! 🌟",
                "Chupchap chill~ 💕"
            )
        )[weekIndex]

        return generalPool.random()
    }
}

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
            userName = prefs.getString("userName", "Gopi") ?: "Gopi",
            greetOnScreenOn = prefs.getBoolean("greetOnScreenOn", true),
            chargingAnimationEnabled = prefs.getBoolean("chargingAnimationEnabled", true),
            voiceEnabled = prefs.getBoolean("voiceEnabled", true),
            voiceVolume = prefs.getFloat("voiceVolume", 0.85f),
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
            putString("userName", updated.userName)
            putBoolean("greetOnScreenOn", updated.greetOnScreenOn)
            putBoolean("chargingAnimationEnabled", updated.chargingAnimationEnabled)
            putBoolean("voiceEnabled", updated.voiceEnabled)
            putFloat("voiceVolume", updated.voiceVolume)
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
