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
    // Incrementing version counter to refresh UI when custom dialogues or MP3s change
    val customAssetRevision: Int = 0
)

/**
 * Handles internal MP3 storage, custom per-character dialogues, weekly rotating funny Hindi
 * conversations, and zero-idle-drain MediaPlayer audio playback.
 */
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

    /**
     * Copies a user-picked MP3/audio URI into the app's private internal storage (filesDir).
     */
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

    /**
     * Plays the uploaded MP3 for the character ONLY during Screen-On or Charging.
     * Immediately releases MediaPlayer hardware resources upon completion for 0% idle battery drain.
     */
    fun playCharacterMp3IfAvailable(
        context: Context,
        skin: CharacterSkin,
        isCharging: Boolean
    ) {
        val cfg = CompanionRepository.config.value
        if (!cfg.voiceEnabled || cfg.voiceVolume <= 0.01f) return

        // Do not play audio during an active phone or WhatsApp call
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager?.mode == AudioManager.MODE_IN_CALL ||
            audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION
        ) return

        // Use this character's MP3, or fall back to any character's uploaded MP3 of the same type
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
     * Returns the user's custom written Screen-On dialogue if set, otherwise picks from
     * a weekly-rotating pack of funny & cute Hindi greetings.
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
                listOf("Meow~ Kon-kon, $name hain kya? 🐾", "Arre waah $name aa gaye! 💕", "Purr~ Mujhe yaad kiya $name? ✨"),
                listOf("Nyaa! $name, aaj bade smart lag rahe ho! 😺", "Meow! Phone kholte hi meri yaad aayi? 🐾", "Chalo $name, thoda masti karein! 🧶"),
                listOf("Oho $name! Chai-naashta ho gaya? ☕", "Meow~ Aapki smile toh kamaal hai $name! ✨", "Kon-kon? Mere best friend $name! 💕"),
                listOf("Purrfect timing $name! 🐾", "Meow! Aaj ka din super hit jayega $name! 🌟", "Arre $name, mujhe bhool toh nahi gaye? 😸")
            )[weekIndex]

            CharacterSkin.SHADOW_NINJA -> listOf(
                listOf("Nin-Nin! Kon-kon, $name Boss hain kya? 🥷", "Swagat hai $name Boss! ⚡", "Shadow Ninja haazir hai $name! 🍃"),
                listOf("Hukum mere aaka $name! 🥷", "Boss $name, aaj kaun sa mission hai? ⚡", "Chupke se aaya hoon sirf $name ke liye! ✨"),
                listOf("Nin-Nin! $name Boss ki jai ho! 🔥", "Speed 120Hz, Loyalty 100% for $name! 🥷", "Arre $name Boss, aap aaye bahaar aayi! 🍃"),
                listOf("Stealth Mode Off! Namaste $name Boss! ⚡", "Koi pareshan kare toh batana $name! 🥷", "Nin-Nin! Aaj full form mein ho $name! 🌟")
            )[weekIndex]

            CharacterSkin.ASTRO_BOT -> listOf(
                listOf("Beep-Boop! Kon-kon, $name hain kya? 🤖", "Hi $name! Aapko dekh kar dil khush ho gaya! 🚀", "System Happy: $name Detected! ✨"),
                listOf("Radar Scan: 100% Cute $name Found! 🤖", "Zero-G सलाम, $name! 🚀", "Beep! Aapke aate hi battery khush ho gayi $name! ⚡"),
                listOf("Hello $name! Aaj tohchamk rahe ho! 🌟", "Astro-Bot reporting to Captain $name! 🚀", "Boop-Beep! Kya haal chaal $name? 🤖"),
                listOf("Turbo Greeting for $name! ✨", "Galaxy ka sabse achha dost: $name! 🪐", "Beep! Aapka din mangalmay ho $name! 🤖")
            )[weekIndex]

            CharacterSkin.KITSUNE_SPIRIT -> listOf(
                listOf("Kon-Kon! $name hain kya? 🦊", "Namaste $name ji! Aaj ka din shubh ho! 🔔", "Fox Magic sirf $name ke liye! ✨"),
                listOf("Kon-Kon! $name, aaj kuch achha hone wala hai! 🦊", "Shrine Bell baj gayi, $name aa gaye! 🔔", "Pyare $name ko Kitsune ka pranaam! ✨"),
                listOf("Oho $name! Kitne dino baad mile! 🦊", "Kon-Kon! Hamesha muskurate raho $name! 🌸", "Lucky Charm activated for $name! 🔔"),
                listOf("Suprabhat / Namaste $name! 🦊", "Fox Spirit aapke saath hai $name! ✨", "Kon-Kon! Chalo thoda jhoola jhoolein $name! 🔔")
            )[weekIndex]
        }
        return pool.random()
    }

    /**
     * Returns the user's custom written Charging dialogue if set, otherwise picks a cute Hindi thank-you.
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
            "Charge dene ke liye Thank U $name! ⚡ ($batteryPct%)",
            "Shukriya $name! Aha, pet bhar gaya energy se! 🔋",
            "Thank U so much $name! Ab aayega maza! ✨ ($batteryPct%)",
            "Yummy Power! Charge lagane ke liye dhanyawad $name! ⚡"
        ).random()
    }

    /**
     * Silent visual-only conversations for tricks and timer visits (rotates weekly, zero audio).
     */
    fun resolveSilentConversation(skin: CharacterSkin, rawName: String): String {
        val name = rawName.trim().ifEmpty { "Dost" }
        val weekIndex = Calendar.getInstance().get(Calendar.WEEK_OF_YEAR) % 4
        val generalPool = listOf(
            listOf(
                "Aur batao $name, sab badhiya? ✨",
                "Thoda paani pi lo $name, hydration zaroori hai! 💧",
                "Upar punch-hole mein thoda andhera tha, bahar hawa achhi hai! 🍃",
                "Dekho $name, bina haath lagaye jhool raha hoon! 🎪"
            ),
            listOf(
                "Aaj mausam bada suhana hai na $name? 🌤️",
                "Phone thoda tilt karo $name, jhoola jhoolna hai! 🎢",
                "Main chhota zaroor hoon par करतब bade hain! ✨",
                "Aankhon ko thoda aaram bhi de dena $name! 👀"
            ),
            listOf(
                "Kya chal raha hai screen pe $name? 📱",
                "Mujhe kheench ke chhodo, rocket ban jaunga! 🚀",
                "Aapka phone toh makkhan jaisa chalta hai $name! ⚡",
                "Thak gaye ho toh 2 minute muskura lo $name! 😊"
            ),
            listOf(
                "Upar camera ke paas mera chhota sa ghar hai! 🏠",
                "Single tap pe trick, double tap pe ghar wapas! 🐾",
                "Aaj ka din aapke naam $name! 🌟",
                "Shhh... main chupchap aapki company enjoy kar raha hoon! 💕"
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
