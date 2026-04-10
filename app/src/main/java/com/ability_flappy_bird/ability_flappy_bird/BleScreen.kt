package com.ability_flappy_bird.ability_flappy_bird

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

// ── Colour palette ────────────────────────────────────────────────────────────
private val BgTop    = Color(0xFF0A1628)
private val BgBottom = Color(0xFF0D2D55)
private val Accent   = Color(0xFF2196F3)
private val AccentOn = Color(0xFF00E5FF)
private val CardBg   = Color(0xFF122040)
private val CardBdr  = Color(0xFF1E3A6E)
private val TextPrim = Color(0xFFE8F4FD)
private val TextSub  = Color(0xFF8BAEC8)
private val HandGold = Color(0xFFFFCC02)

internal const val MAX_PLOT_SAMPLES = 200
internal val ChPositive = Color(1.00f, 0.23f, 0.19f, 0.80f)   // red,  80 % opacity
internal val ChNegative = Color(0.00f, 0.48f, 1.00f, 0.80f)   // blue, 80 % opacity
internal const val Y_MAX = 5f

@Composable
fun BleScreen(
    bleManager: BleManager,
    onProceed: (EmgSettings?) -> Unit
) {
    val context = LocalContext.current

    val bleState           by bleManager.state.collectAsState()
    val devices            by bleManager.devices.collectAsState()
    val connectedName      by bleManager.connectedName.collectAsState()
    val emgFrame           by bleManager.emgFrame.collectAsState()
    val thresholdPositive  by bleManager.thresholdPositive.collectAsState()
    val thresholdNegative  by bleManager.thresholdNegative.collectAsState()

    var selectedChannel by remember { mutableStateOf(EmgChannel.POSITIVE) }

    // Rolling history for the plot – collected directly from the flow so no
    // frames are dropped between recompositions.
    val history = remember { mutableStateListOf<EmgFrame>() }
    LaunchedEffect(Unit) {
        bleManager.emgFrame.collect { frame ->
            frame ?: return@collect
            history.add(frame)
            while (history.size > MAX_PLOT_SAMPLES) history.removeAt(0)
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────────
    val requiredPerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    var hasPerms by remember {
        mutableStateOf(requiredPerms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> hasPerms = results.values.all { it }; if (hasPerms) bleManager.startScan() }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* BT on/off is checked at button press time */ }

    // Stop scan when leaving this screen
    DisposableEffect(Unit) { onDispose { bleManager.stopScan() } }

    // ── Layout ────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(64.dp))

            // ── Header icon ───────────────────────────────────────────────────
            BluetoothStatusIcon(bleState)

            Spacer(Modifier.height(20.dp))

            Text(
                text = "ABILITY HAND",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = HandGold,
                letterSpacing = 2.sp
            )
            Text(
                text = "Connect your prosthetic hand to play",
                fontSize = 14.sp,
                color = TextSub,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 24.dp)
            )

            // ── Status card ───────────────────────────────────────────────────
            StatusCard(bleState, connectedName)

            Spacer(Modifier.height(20.dp))

            // ── Action button (Scan / Stop / Retry) ───────────────────────────
            if (bleState != BleConnectionState.CONNECTED) {
                ScanButton(
                    bleState   = bleState,
                    btOn       = bleManager.isBluetoothOn,
                    hasPerms   = hasPerms,
                    onScan     = {
                        when {
                            !bleManager.isBluetoothOn -> enableBtLauncher.launch(
                                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            )
                            !hasPerms -> permLauncher.launch(requiredPerms)
                            bleState == BleConnectionState.SCANNING -> bleManager.stopScan()
                            else -> bleManager.startScan()
                        }
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Device list ───────────────────────────────────────────────────
            if (devices.isNotEmpty() && bleState != BleConnectionState.CONNECTED) {
                Text(
                    text = "NEARBY DEVICES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSub,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(devices, key = { it.address }) { device ->
                        DeviceRow(device = device, onConnect = { bleManager.connect(device.address) })
                    }
                }
            } else if (bleState == BleConnectionState.CONNECTED) {
                // ── Threshold + channel selection ─────────────────────────────
                Spacer(Modifier.height(8.dp))
                ThresholdChannelCard(
                    thresholdPositive = thresholdPositive,
                    thresholdNegative = thresholdNegative,
                    selectedChannel   = selectedChannel,
                    onChannelChange   = { selectedChannel = it }
                )
                Spacer(Modifier.height(8.dp))
                // ── Live EMG plot ─────────────────────────────────────────────
                EmgPlotCard(history, modifier = Modifier.weight(1f))
                Spacer(Modifier.height(8.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }

            // ── Bottom buttons ────────────────────────────────────────────────
            if (bleState == BleConnectionState.CONNECTED) {
                val threshold = when (selectedChannel) {
                    EmgChannel.POSITIVE -> thresholdPositive ?: Float.MAX_VALUE
                    EmgChannel.NEGATIVE -> thresholdNegative ?: Float.MAX_VALUE
                }
                Button(
                    onClick = { onProceed(EmgSettings(selectedChannel, threshold)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentOn),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = BgTop)
                    Spacer(Modifier.width(8.dp))
                    Text("Start Game", color = BgTop, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.height(12.dp))
            }

            TextButton(onClick = { onProceed(null) }) {
                Text(
                    text = if (bleState == BleConnectionState.CONNECTED)
                        "Continue without hand control"
                    else
                        "Play without device",
                    color = TextSub,
                    fontSize = 14.sp
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Sub-composables ────────────────────────────────────────────────────────────

@Composable
private fun BluetoothStatusIcon(state: BleConnectionState) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.3f, targetValue = 1f, label = "alpha",
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse)
    )
    val icon = when (state) {
        BleConnectionState.CONNECTED  -> Icons.Filled.BluetoothConnected
        BleConnectionState.SCANNING,
        BleConnectionState.CONNECTING -> Icons.Filled.BluetoothSearching
        else                          -> Icons.Filled.Bluetooth
    }
    val tint = when (state) {
        BleConnectionState.CONNECTED  -> AccentOn
        BleConnectionState.SCANNING,
        BleConnectionState.CONNECTING -> Accent
        else                          -> TextSub
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(88.dp)
            .clip(CircleShape)
            .background(CardBg)
            .border(2.dp, tint.copy(alpha = if (state == BleConnectionState.SCANNING) alpha else 1f), CircleShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Bluetooth status",
            tint = tint,
            modifier = Modifier.size(44.dp)
        )
    }
}

@Composable
private fun StatusCard(state: BleConnectionState, connectedName: String?) {
    val (label, colour) = when (state) {
        BleConnectionState.CONNECTED  -> "Connected to $connectedName" to AccentOn
        BleConnectionState.CONNECTING -> "Connecting…" to Accent
        BleConnectionState.SCANNING   -> "Scanning for ABILITY HAND…" to Accent
        BleConnectionState.IDLE       -> "Not connected" to TextSub
    }

    val dotColor by animateColorAsState(colour, label = "dot")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(12.dp))
            Text(label, color = TextPrim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ScanButton(
    bleState: BleConnectionState,
    btOn: Boolean,
    hasPerms: Boolean,
    onScan: () -> Unit
) {
    val label = when {
        !btOn                               -> "Enable Bluetooth"
        !hasPerms                           -> "Allow Bluetooth Access"
        bleState == BleConnectionState.SCANNING   -> "Stop Scanning"
        bleState == BleConnectionState.CONNECTING -> "Connecting…"
        else                                -> "Scan for Device"
    }

    val enabled = bleState != BleConnectionState.CONNECTING

    if (bleState == BleConnectionState.SCANNING) {
        OutlinedButton(
            onClick = onScan,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
        ) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    } else {
        Button(
            onClick = onScan,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Filled.BluetoothSearching, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun DeviceRow(device: BleDeviceItem, onConnect: () -> Unit) {
    val borderColor = if (device.isAbilityHand) HandGold else CardBdr

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (device.isAbilityHand) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onConnect),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isAbilityHand) CardBg.copy(alpha = 0.9f) else CardBg
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Bluetooth,
                contentDescription = null,
                tint = if (device.isAbilityHand) HandGold else TextSub,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    color = if (device.isAbilityHand) HandGold else TextPrim,
                    fontSize = 15.sp,
                    fontWeight = if (device.isAbilityHand) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device.address,
                    color = TextSub,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.SignalCellularAlt,
                    contentDescription = null,
                    tint = rssiColor(device.rssi),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "${device.rssi} dBm",
                    color = TextSub,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
        }
    }
}

private fun rssiColor(rssi: Int): Color = when {
    rssi >= -60 -> Color(0xFF4CAF50)
    rssi >= -75 -> Color(0xFFFFCC02)
    else        -> Color(0xFFF44336)
}

// ── Live EMG plot card ─────────────────────────────────────────────────────────

@Composable
private fun EmgPlotCard(history: List<EmgFrame>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Title + legend
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LIVE EMG  •  P2",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = AccentOn,
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.weight(1f))
            LegendDot(ChPositive, "Positive")
            Spacer(Modifier.width(12.dp))
            LegendDot(ChNegative, "Negative")
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
            ) {
                // Y-axis labels: 5 → 0 top to bottom
                Column(
                    modifier = Modifier
                        .width(20.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    for (label in 5 downTo 0) {
                        Text(
                            text = "$label",
                            color = TextSub,
                            fontSize = 9.sp,
                            lineHeight = 9.sp
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    drawEmgPlot(history)
                }
            }
        }
    }
}

// ── Threshold + channel selection card ────────────────────────────────────────

@Composable
private fun ThresholdChannelCard(
    thresholdPositive: Float?,
    thresholdNegative: Float?,
    selectedChannel: EmgChannel,
    onChannelChange: (EmgChannel) -> Unit
) {
    val bothLoading = thresholdPositive == null && thresholdNegative == null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = if (bothLoading) "READING THRESHOLDS…" else "EMG THRESHOLDS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = AccentOn,
                letterSpacing = 1.5.sp
            )

            Spacer(Modifier.height(10.dp))

            // Threshold values row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ThresholdValue(
                    label  = "Positive (RD)",
                    value  = thresholdPositive,
                    color  = ChPositive
                )
                ThresholdValue(
                    label  = "Negative (RE)",
                    value  = thresholdNegative,
                    color  = ChNegative
                )
            }

            Spacer(Modifier.height(12.dp))

            // Channel selection checkboxes (mutually exclusive)
            Text(
                text = "ACTIVE CHANNEL",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = TextSub,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = selectedChannel == EmgChannel.POSITIVE,
                    onCheckedChange = { if (it) onChannelChange(EmgChannel.POSITIVE) },
                    colors = CheckboxDefaults.colors(checkedColor = ChPositive)
                )
                Text("Positive  (CH1)", color = TextPrim, fontSize = 13.sp)
                Spacer(Modifier.width(20.dp))
                Checkbox(
                    checked = selectedChannel == EmgChannel.NEGATIVE,
                    onCheckedChange = { if (it) onChannelChange(EmgChannel.NEGATIVE) },
                    colors = CheckboxDefaults.colors(checkedColor = ChNegative)
                )
                Text("Negative  (CH2)", color = TextPrim, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ThresholdValue(label: String, value: Float?, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (value != null) "%.2f".format(value) else "—",
            color = color,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Text(text = label, color = TextSub, fontSize = 10.sp)
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, color = TextSub, fontSize = 11.sp)
    }
}

internal fun DrawScope.drawEmgPlot(history: List<EmgFrame>) {
    // Horizontal grid lines at y = 0, 1, 2, 3, 4, 5
    for (i in 0..5) {
        val y = size.height * (1f - i / Y_MAX)
        drawLine(
            color = Color.White.copy(alpha = 0.08f),
            start = Offset(0f, y),
            end   = Offset(size.width, y),
            strokeWidth = 1f
        )
    }

    if (history.size < 2) return

    val xStep = size.width / (MAX_PLOT_SAMPLES - 1).toFloat()
    val stroke = Stroke(
        width = 2.dp.toPx(),
        cap   = StrokeCap.Round,
        join  = StrokeJoin.Round
    )

    fun buildPath(selector: (EmgFrame) -> Float): Path {
        val path = Path()
        history.forEachIndexed { i, frame ->
            // Anchor latest sample to the right edge
            val x = size.width - (history.size - 1 - i) * xStep
            val y = size.height * (1f - selector(frame) / Y_MAX).coerceIn(0f, 1f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return path
    }

    // Draw CH2 (Negative / blue) first so CH1 renders on top where they overlap
    drawPath(buildPath { it.ch2 }, color = ChNegative, style = stroke)
    drawPath(buildPath { it.ch1 }, color = ChPositive, style = stroke)
}
