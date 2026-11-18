package com.edge60pro.punchholecompanion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startCompanionService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CompanionRepository.init(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Settings.canDrawOverlays(this)) {
            startCompanionService()
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompanionDashboardScreen(onStartService = { startCompanionService() })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            startCompanionService()
        }
    }

    private fun startCompanionService() {
        val intent = Intent(this, PunchHoleCompanionService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionDashboardScreen(onStartService: () -> Unit) {
    val context = LocalContext.current
    val config by CompanionRepository.config.collectAsState()
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var isBatteryUnrestricted by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }

    var selectedStudioSkin by remember { mutableStateOf(config.skin) }
    var customGreetText by remember(selectedStudioSkin, config.customAssetRevision) {
        mutableStateOf(
            CustomAudioAndDialogueEngine.getCustomDialogue(context, selectedStudioSkin, isCharging = false)
        )
    }
    var customChargeText by remember(selectedStudioSkin, config.customAssetRevision) {
        mutableStateOf(
            CustomAudioAndDialogueEngine.getCustomDialogue(context, selectedStudioSkin, isCharging = true)
        )
    }
    var hasGreetMp3 by remember(selectedStudioSkin, config.customAssetRevision) {
        mutableStateOf(
            CustomAudioAndDialogueEngine.hasSavedMp3(context, selectedStudioSkin, isCharging = false)
        )
    }
    var hasChargeMp3 by remember(selectedStudioSkin, config.customAssetRevision) {
        mutableStateOf(
            CustomAudioAndDialogueEngine.hasSavedMp3(context, selectedStudioSkin, isCharging = true)
        )
    }

    val greetMp3Picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val saved = CustomAudioAndDialogueEngine.saveMp3FromUri(
                context = context,
                skin = selectedStudioSkin,
                isCharging = false,
                sourceUri = uri
            )
            hasGreetMp3 = saved
            CompanionRepository.update(context) {
                it.copy(customAssetRevision = it.customAssetRevision + 1)
            }
            Toast.makeText(
                context,
                if (saved) "Screen-On MP3 saved for ${selectedStudioSkin.displayName}!"
                else "Could not save MP3",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val chargeMp3Picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val saved = CustomAudioAndDialogueEngine.saveMp3FromUri(
                context = context,
                skin = selectedStudioSkin,
                isCharging = true,
                sourceUri = uri
            )
            hasChargeMp3 = saved
            CompanionRepository.update(context) {
                it.copy(customAssetRevision = it.customAssetRevision + 1)
            }
            Toast.makeText(
                context,
                if (saved) "Charging MP3 saved for ${selectedStudioSkin.displayName}!"
                else "Could not save MP3",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_app_logo),
                contentDescription = "App Logo",
                modifier = Modifier.size(64.dp)
            )
            Column {
                Text(
                    text = "Edge 60 Punch Companion",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "MP3 Voice Studio • Hinglish Edition",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Card 1: Overlay, Keep-Alive & Banking Stealth Mode
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("1. Controls & Anti-Close Protection", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (!Settings.canDrawOverlays(context)) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            } else {
                                onStartService()
                                CompanionRepository.manualCommand.value = "TOGGLE"
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Enable / Summon")
                    }
                    FilledTonalButton(
                        onClick = {
                            onStartService()
                            CompanionRepository.manualCommand.value = "TRICK"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Trigger Trick")
                    }
                }

                if (!isBatteryUnrestricted) {
                    Button(
                        onClick = {
                            runCatching {
                                val intent = Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                            isBatteryUnrestricted =
                                powerManager.isIgnoringBatteryOptimizations(context.packageName)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("⚡ Stop Auto-Close (Allow Background Run)")
                    }
                }

                OutlinedButton(
                    onClick = { CompanionRepository.manualCommand.value = "PAUSE_BANKING" },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Pause Overlay for UPI / Banking (5 Min)")
                }
            }
        }

        // Card 2: Voice On/Off, Volume, MP3 Upload & Short Hinglish Dialogues
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("2. Voice On/Off, MP3 Upload & Hinglish", fontWeight = FontWeight.SemiBold)

                // Voice On/Off Switch right at the top of Card 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Character Voice Sound (On / Off)", fontWeight = FontWeight.Medium)
                        Text(
                            "Plays MP3 only on Screen-On & Charger plug-in",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.voiceEnabled,
                        onCheckedChange = { enabled ->
                            if (!enabled) CustomAudioAndDialogueEngine.stopAndReleaseNow()
                            CompanionRepository.update(context) { it.copy(voiceEnabled = enabled) }
                        }
                    )
                }

                // Volume Slider
                Text("Voice Volume: ${(config.voiceVolume * 100).roundToInt()}%")
                Slider(
                    value = config.voiceVolume,
                    onValueChange = { vol ->
                        CompanionRepository.update(context) { it.copy(voiceVolume = vol) }
                    },
                    valueRange = 0f..1f,
                    enabled = config.voiceEnabled
                )

                // Your Name Box
                OutlinedTextField(
                    value = config.userName,
                    onValueChange = { newName ->
                        CompanionRepository.update(context) { it.copy(userName = newName.take(16)) }
                    },
                    label = { Text("Your Name for Greeting") },
                    placeholder = { Text("e.g., Gopi") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Divider()

                Text(
                    text = "Select Character to Upload MP3 & Write Short Hinglish:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                CharacterSkin.values().toList().chunked(2).forEach { rowSkins ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowSkins.forEach { skinOption ->
                            FilterChip(
                                selected = selectedStudioSkin == skinOption,
                                onClick = { selectedStudioSkin = skinOption },
                                label = { Text(skinOption.displayName) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Screen-On Short Hinglish Dialogue + MP3 Upload
                OutlinedTextField(
                    value = customGreetText,
                    onValueChange = { text ->
                        customGreetText = text.take(36)
                        CustomAudioAndDialogueEngine.setCustomDialogue(
                            context = context,
                            skin = selectedStudioSkin,
                            isCharging = false,
                            dialogueText = customGreetText
                        )
                    },
                    label = { Text("${selectedStudioSkin.displayName}: Screen-On Hinglish") },
                    placeholder = { Text("Kon-kon? {name} hain kya?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { greetMp3Picker.launch("audio/*") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (hasGreetMp3) "✓ Screen-On MP3 Saved" else "Upload Screen-On MP3")
                    }
                    if (hasGreetMp3) {
                        OutlinedButton(
                            onClick = {
                                CustomAudioAndDialogueEngine.deleteSavedMp3(
                                    context,
                                    selectedStudioSkin,
                                    isCharging = false
                                )
                                hasGreetMp3 = false
                            }
                        ) {
                            Text("Clear")
                        }
                    }
                }

                // Charging Short Hinglish Dialogue + MP3 Upload
                OutlinedTextField(
                    value = customChargeText,
                    onValueChange = { text ->
                        customChargeText = text.take(36)
                        CustomAudioAndDialogueEngine.setCustomDialogue(
                            context = context,
                            skin = selectedStudioSkin,
                            isCharging = true,
                            dialogueText = customChargeText
                        )
                    },
                    label = { Text("${selectedStudioSkin.displayName}: Charging Hinglish") },
                    placeholder = { Text("Charge ke liye Thank U {name}!") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { chargeMp3Picker.launch("audio/*") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (hasChargeMp3) "✓ Charging MP3 Saved" else "Upload Charging MP3")
                    }
                    if (hasChargeMp3) {
                        OutlinedButton(
                            onClick = {
                                CustomAudioAndDialogueEngine.deleteSavedMp3(
                                    context,
                                    selectedStudioSkin,
                                    isCharging = true
                                )
                                hasChargeMp3 = false
                            }
                        ) {
                            Text("Clear")
                        }
                    }
                }

                // Test Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = {
                            CompanionRepository.update(context) {
                                it.copy(skin = selectedStudioSkin, autoSwapCharacters = false)
                            }
                            onStartService()
                            CompanionRepository.manualCommand.value = "GREET_TEST"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Test Screen-On")
                    }
                    FilledTonalButton(
                        onClick = {
                            CompanionRepository.update(context) {
                                it.copy(skin = selectedStudioSkin, autoSwapCharacters = false)
                            }
                            onStartService()
                            CompanionRepository.manualCommand.value = "CHARGE_TEST"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Test Charging")
                    }
                }

                Divider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Greet When Screen Turns On")
                    Switch(
                        checked = config.greetOnScreenOn,
                        onCheckedChange = { enabled ->
                            CompanionRepository.update(context) { it.copy(greetOnScreenOn = enabled) }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Charging Animation & Thank You")
                    Switch(
                        checked = config.chargingAnimationEnabled,
                        onCheckedChange = { enabled ->
                            CompanionRepository.update(context) { it.copy(chargingAnimationEnabled = enabled) }
                        }
                    )
                }
            }
        }

        // Card 3: Studio Cartoon Characters & Auto-Swap
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("3. Studio Cartoon Characters", fontWeight = FontWeight.SemiBold)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Swap Character Every Visit")
                        Text(
                            "Rotates between Cat, Ninja, Astro-Bot & Kitsune each time",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.autoSwapCharacters,
                        onCheckedChange = { enabled ->
                            CompanionRepository.update(context) { it.copy(autoSwapCharacters = enabled) }
                        }
                    )
                }

                CharacterSkin.values().toList().chunked(2).forEach { rowSkins ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowSkins.forEach { skinOption ->
                            FilterChip(
                                selected = config.skin == skinOption,
                                onClick = {
                                    selectedStudioSkin = skinOption
                                    CompanionRepository.update(context) {
                                        it.copy(skin = skinOption, autoSwapCharacters = false)
                                    }
                                },
                                label = { Text(skinOption.displayName) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // Card 4: Braided Rope Customisation
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("4. Braided Rope Customisation", fontWeight = FontWeight.SemiBold)

                Text("Rope Length: ${config.ropeLengthDp.roundToInt()} dp")
                Slider(
                    value = config.ropeLengthDp,
                    onValueChange = { value ->
                        CompanionRepository.update(context) { it.copy(ropeLengthDp = value) }
                    },
                    valueRange = 80f..360f
                )

                Text("Rope Thickness: ${"%.1f".format(config.ropeThicknessDp)} dp")
                Slider(
                    value = config.ropeThicknessDp,
                    onValueChange = { value ->
                        CompanionRepository.update(context) { it.copy(ropeThicknessDp = value) }
                    },
                    valueRange = 2.0f..8.0f
                )
            }
        }

        // Card 5: Appearance Timer
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("5. Appearance Interval Timer", fontWeight = FontWeight.SemiBold)
                val intervals = listOf(3, 5, 10, 15, 0)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    intervals.forEach { mins ->
                        FilterChip(
                            selected = config.intervalMinutes == mins,
                            onClick = {
                                CompanionRepository.update(context) { it.copy(intervalMinutes = mins) }
                            },
                            label = { Text(if (mins == 0) "Manual" else "${mins}m") }
                        )
                    }
                }
            }
        }

        // Card 6: Smart Behaviors & Cutout Calibration
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("6. Behaviors & Cutout Alignment", fontWeight = FontWeight.SemiBold)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Screen Edge Parkour")
                    Switch(
                        checked = config.allowEdgeWalk,
                        onCheckedChange = { enabled ->
                            CompanionRepository.update(context) { it.copy(allowEdgeWalk = enabled) }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dance to Music (Zero-Permission)")
                    Switch(
                        checked = config.danceToMusic,
                        onCheckedChange = { enabled ->
                            CompanionRepository.update(context) { it.copy(danceToMusic = enabled) }
                        }
                    )
                }

                Text("Horizontal Cutout Offset: ${config.xOffsetPx} px")
                Slider(
                    value = config.xOffsetPx.toFloat(),
                    onValueChange = { value ->
                        CompanionRepository.update(context) { it.copy(xOffsetPx = value.roundToInt()) }
                    },
                    valueRange = -50f..50f
                )

                Text("Vertical Cutout Offset: ${config.yOffsetPx} px")
                Slider(
                    value = config.yOffsetPx.toFloat(),
                    onValueChange = { value ->
                        CompanionRepository.update(context) { it.copy(yOffsetPx = value.roundToInt()) }
                    },
                    valueRange = -40f..40f
                )
            }
        }
    }
}
