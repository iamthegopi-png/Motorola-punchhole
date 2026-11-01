package com.edge60pro.punchholecompanion

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CompanionRepository.init(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompanionDashboardScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionDashboardScreen() {
    val context = LocalContext.current
    val config by CompanionRepository.config.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Moto Edge 60 Pro Cutout Companion",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Studio-grade cartoon companions that automatically swap on every visit with zero idle battery drain.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Card 1: Permissions & Live Test
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("1. Permissions & Live Preview", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Enable Overlay")
                    }
                    FilledTonalButton(
                        onClick = { CompanionRepository.manualCommand.value = "TOGGLE" },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Summon / Swap")
                    }
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Notification & Music Access")
                }
                OutlinedButton(
                    onClick = { CompanionRepository.manualCommand.value = "TRICK" },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Trigger Studio Trick Now")
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
                    Text("React to Notifications")
                    Switch(
                        checked = config.reactToNotifications,
                        onCheckedChange = { enabled ->
                            CompanionRepository.update(context) { it.copy(reactToNotifications = enabled) }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dance to Music")
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
