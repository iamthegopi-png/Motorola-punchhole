package com.edge60pro.punchholecompanion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
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
                    text = "Tilt-Reactive Studio Edition • UPI Safe",
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

                // 1-Tap Button to prevent Motorola from closing the app in the background
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

        // Card 2: Studio Character Roster & Auto-Swap Toggle
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("2. Studio Cartoon Characters", fontWeight = FontWeight.SemiBold)

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

        // Card 3: Braided Rope Customisation
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("3. Braided Rope Customisation", fontWeight = FontWeight.SemiBold)

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

        // Card 4: Appearance Timer
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("4. Appearance Interval Timer", fontWeight = FontWeight.SemiBold)
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

        // Card 5: Smart Behaviors & Cutout Calibration
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("5. Behaviors & Cutout Alignment", fontWeight = FontWeight.SemiBold)

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
