package com.edge60pro.punchholecompanion

import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar
import java.util.Locale

enum class CharacterSkin(
    val displayName: String,
    val ropeColor: Int,
    val ropeBraidColor: Int,
    val bodyColor: Int,
    val secondaryColor: Int,
    val accentColor: Int,
    val eyeColor: Int,
    val voicePitch: Float,
    val voiceRate: Float
) {
    MOCHI_CAT(
        displayName = "Mochi the Cat",
        ropeColor = Color.parseColor("#F4A261"),
        ropeBraidColor = Color.parseColor("#E76F51"),
        bodyColor = Color.parseColor("#FFF8F0"),
        secondaryColor = Color.parseColor("#FFB5A7"),
        accentColor = Color.parseColor("#F77F00"),
        eyeColor = Color.parseColor("#2B2D42"),
        voicePitch = 1.52f, // High, cute chibi cat pitch
        voiceRate = 1.04f
    ),
    SHADOW_NINJA(
        displayName = "Kuro the Ninja",
        ropeColor = Color.parseColor("#E2C08D"),
        ropeBraidColor = Color.parseColor("#B58E58"),
        bodyColor = Color.parseColor("#222738"),
        secondaryColor = Color.parseColor("#FFE0B2"),
        accentColor = Color.parseColor("#FF3366"),
        eyeColor = Color.parseColor("#00F5D4"),
        voicePitch = 1.20f, // Energetic anime shinobi pitch
        voiceRate = 1.10f
    ),
    ASTRO_BOT(
        displayName = "Astro Neko-Bot",
        ropeColor = Color.parseColor("#90E0EF"),
        ropeBraidColor = Color.parseColor("#00B4D8"),
        bodyColor = Color.parseColor("#F8F9FA"),
        secondaryColor = Color.parseColor("#1B1F3B"),
        accentColor = Color.parseColor("#FFBE0B"),
        eyeColor = Color.parseColor("#00F5D4"),
        voicePitch = 1.36f, // Cute companion bot pitch
        voiceRate = 0.98f
    ),
    KITSUNE_SPIRIT(
        displayName = "Kitsune Fox",
        ropeColor = Color.parseColor("#95D5B2"),
        ropeBraidColor = Color.parseColor("#52B788"),
        bodyColor = Color.parseColor("#FF7B00"),
        secondaryColor = Color.parseColor("#FFF3B0"),
        accentColor = Color.parseColor("#FFD166"),
        eyeColor = Color.parseColor("#3C096C"),
        voicePitch = 1.44f, // Playful mystical fox pitch
        voiceRate = 1.02f
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
    val skin: CharacterSkin = CharacterSkin.MOCHI_CAT
)

data class CuteDialogue(
    val bubbleText: String,
    val spokenHindiText: String
)

/**
 * Zero-Idle-Drain Hindi Voice Engine.
 * Initializes TextToSpeech on demand and automatically shuts it down 9 seconds after speaking
 * so it uses 0 MB of RAM and 0% battery while the character is resting.
 */
object CuteVoiceEngine {
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingSpeech: Triple<String, CharacterSkin, Float>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val hibernateRunnable = Runnable {
        shutdownNow()
    }

    fun speak(context: Context, spokenText: String, skin: CharacterSkin) {
        val cfg = CompanionRepository.config.value
        if (!cfg.voiceEnabled || cfg.voiceVolume <= 0.01f || spokenText.isBlank()) return

        // Never interrupt an active phone or WhatsApp call
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager?.mode == AudioManager.MODE_IN_CALL ||
            audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION
        ) return

        mainHandler.removeCallbacks(hibernateRunnable)
        mainHandler.postDelayed(hibernateRunnable, 9_500L)

        val engine = tts
        if (engine != null && isReady) {
            playUtterance(engine, spokenText, skin, cfg.voiceVolume)
            return
        }

        pendingSpeech = Triple(spokenText, skin, cfg.voiceVolume)
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val activeTts = tts ?: return@TextToSpeech
                    val hindiLocale = Locale("hi", "IN")
                    val result = activeTts.setLanguage(hindiLocale)
                    if (result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        activeTts.setLanguage(Locale("en", "IN"))
                    }
                    isReady = true
                    pendingSpeech?.let { (text, charSkin, vol) ->
                        playUtterance(activeTts, text, charSkin, vol)
                        pendingSpeech = null
                    }
                }
            }
        }
    }

    private fun playUtterance(
        engine: TextToSpeech,
        text: String,
        skin: CharacterSkin,
        volume: Float
    ) {
        engine.setPitch(skin.voicePitch)
        engine.setSpeechRate(skin.voiceRate)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "cute_companion_voice")
    }

    fun shutdownNow() {
        mainHandler.removeCallbacks(hibernateRunnable)
        pendingSpeech = null
        isReady = false
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
    }

    fun buildScreenOnGreeting(skin: CharacterSkin, rawName: String): CuteDialogue {
        val name = rawName.trim().ifEmpty { "Dost" }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        // Late-night caring reminder (11 PM to 4 AM) and Morning greeting (5 AM to 10 AM)
        if (hour in 23..24 || hour in 0..4 && (0..2).random() == 0) {
            return CuteDialogue(
                bubbleText = "Arre $name, so jao na! 🌙",
                spokenHindiText = "अरे $name, इतनी रात हो गई, अभी तक जाग रहे हो? जल्दी सो जाओ ना!"
            )
        }
        if (hour in 5..10 && (0..2).random() == 0) {
            return CuteDialogue(
                bubbleText = "Subah ho gayi $name! ☀️",
                spokenHindiText = "गुड मॉर्निंग $name! सुबह हो गई, चाय पी ली क्या?"
            )
        }

        return when (skin) {
            CharacterSkin.MOCHI_CAT -> listOf(
                CuteDialogue(
                    "Meow~ Kon-kon, $name hain kya? 🐾",
                    "म्याऊं! कौन कौन, $name हैं क्या? आपसे मिलकर मज़ा आ गया!"
                ),
                CuteDialogue(
                    "Arre waah $name aa gaye! 💕",
                    "अरे वाह, $name आ गए! म्याऊं, आज क्या प्लान है?"
                ),
                CuteDialogue(
                    "Nyaa~ Kaise ho $name? ✨",
                    "म्याऊं म्याऊं! कैसे हो $name? मुझे याद किया क्या?"
                )
            ).random()

            CharacterSkin.SHADOW_NINJA -> listOf(
                CuteDialogue(
                    "Nin-Nin! $name Boss hain kya? 🥷",
                    "निन निन! कौन कौन, $name बॉस हैं क्या? निंजा हाज़िर है!"
                ),
                CuteDialogue(
                    "Swagat hai $name Boss! ⚡",
                    "नमस्ते $name बॉस! हुकुम कीजिये, आज कौन सा मिशन है?"
                )
            ).random()

            CharacterSkin.ASTRO_BOT -> listOf(
                CuteDialogue(
                    "Beep! $name hain kya? 🤖",
                    "बीप बूप! कौन कौन, $name हैं क्या? आपको देखकर दिल खुश हो गया!"
                ),
                CuteDialogue(
                    "Hi $name! System Happy! 🚀",
                    "हेलो $name! आपके आते ही मेरी खुशी हंड्रेड परसेंट हो गई!"
                )
            ).random()

            CharacterSkin.KITSUNE_SPIRIT -> listOf(
                CuteDialogue(
                    "Kon-Kon! $name hain kya? 🦊",
                    "कॉन कॉन! $name हैं क्या? आज आपका दिन बहुत शुभ हो!"
                ),
                CuteDialogue(
                    "Namaste $name ji! ✨🔔",
                    "कॉन कॉन! नमस्ते $name! चलो थोड़ा खेलते हैं!"
                )
            ).random()
        }
    }

    fun buildChargingGreeting(rawName: String, batteryPct: Int): CuteDialogue {
        val name = rawName.trim().ifEmpty { "Dost" }
        return listOf(
            CuteDialogue(
                "Charge dene ke liye Thank U $name! ⚡",
                "चार्ज देने के लिए थैंक यू $name! आहा, अब मुझमें फुल एनर्जी आ रही है!"
            ),
            CuteDialogue(
                "Shukriya $name! Yummy Power ($batteryPct%) 🔋",
                "चार्ज लगाने के लिए बहुत बहुत शुक्रिया $name! पेट भर गया एनर्जी से!"
            ),
            CuteDialogue(
                "Thank U $name! Maza aa gaya! ✨",
                "चार्ज देने के लिए थैंक यू सो मच $name! बहुत सुकून मिल रहा है!"
            )
        ).random()
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
