package dev.localbridge.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localbridge.android.network.BonjourDiscovery
import dev.localbridge.android.network.ClipboardForegroundService
import dev.localbridge.android.network.ConnectionStatus
import dev.localbridge.android.network.FileTransferState
import dev.localbridge.android.network.FileTransferStatus
import dev.localbridge.android.network.LocalBridgeWebSocketClient
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_FILE_PICKER = "dev.localbridge.android.EXTRA_OPEN_FILE_PICKER"
        val openFilePickerTrigger = kotlinx.coroutines.flow.MutableStateFlow(false)
    }

    private lateinit var discovery: BonjourDiscovery
    private lateinit var webSocketClient: LocalBridgeWebSocketClient

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_FILE_PICKER, false)) {
            openFilePickerTrigger.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as dev.localbridge.android.LocalBridgeApplication
        discovery = app.discovery
        webSocketClient = app.webSocketClient

        // Mac → Android: write received text directly to Android clipboard with zero prompt
        webSocketClient.onClipboardReceived = { content, _, _ ->
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("LocalBridge", content))
            android.util.Log.i("LB.Main", "Mac→Android clipboard written: ${content.length} chars")
        }

        if (intent?.getBooleanExtra(EXTRA_OPEN_FILE_PICKER, false) == true) {
            openFilePickerTrigger.value = true
        }

        // Start initial discovery if master is ON
        if (webSocketClient.isMasterEnabled) {
            discovery.startDiscovery()
        }

        setContent {
            LocalBridgeTheme {
                LocalBridgeApp(
                    discovery = discovery,
                    webSocketClient = webSocketClient,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (webSocketClient.isMasterEnabled) {
            discovery.startDiscovery()
            webSocketClient.reconnectIfPaired()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Design tokens
// ─────────────────────────────────────────────────────────────────────────────

private val BG         = Color(0xFF0A0A0A)
private val Surface1   = Color(0xFF141414)
private val Surface2   = Color(0xFF1C1C1C)
private val Primary    = Color(0xFFF0F0F0)
private val Secondary  = Color(0xFF808080)
private val Border     = Color(0xFF262626)
private val AccentON   = Color(0xFF00E070)
private val AccentBlue = Color(0xFF4D8FFF)
private val AccentWarn = Color(0xFFFF9900)
private val AccentErr  = Color(0xFFEF5350)

// ─────────────────────────────────────────────────────────────────────────────
// Theme
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LocalBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = BG,
            surface = Surface1,
            primary = AccentON,
            onPrimary = Color.Black,
        ),
        content = content,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// App Root
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LocalBridgeApp(
    discovery: BonjourDiscovery,
    webSocketClient: LocalBridgeWebSocketClient,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isMasterEnabled by webSocketClient.isMasterEnabledFlow.collectAsStateWithLifecycle()
    var pairingChallenge by remember { mutableStateOf<PairingChallenge?>(null) }

    val discoveredHosts by discovery.discovered.collectAsStateWithLifecycle()
    val isSearching by discovery.isSearching.collectAsStateWithLifecycle()
    val connectionStatus by webSocketClient.status.collectAsStateWithLifecycle()
    val pairedPeerName by webSocketClient.pairedPeerName.collectAsStateWithLifecycle()
    val fileTransferState by webSocketClient.fileTransferState.collectAsStateWithLifecycle()

    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            if (webSocketClient.isPaired()) {
                coroutineScope.launch {
                    webSocketClient.sendFile(uri)
                }
            } else if (webSocketClient.isLogicallyPaired()) {
                android.widget.Toast.makeText(context, "Reconnecting to Mac… please wait a moment", android.widget.Toast.LENGTH_SHORT).show()
                webSocketClient.reconnectIfPaired()
            } else {
                android.widget.Toast.makeText(context, "Pair with your Mac to send files", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    val openPickerTrigger by MainActivity.openFilePickerTrigger.collectAsStateWithLifecycle()
    LaunchedEffect(openPickerTrigger) {
        if (openPickerTrigger) {
            filePickerLauncher.launch("*/*")
            MainActivity.openFilePickerTrigger.value = false
        }
    }

    // Register incoming pairing challenge callback
    LaunchedEffect(Unit) {
        webSocketClient.onPairingChallengeReceived = { serverName, code, accept, reject ->
            pairingChallenge = PairingChallenge(serverName, code, accept, reject)
        }
    }

    // Auto-connect when Mac is found and master toggle is ON
    // If already paired, we connect silently (sendReidentify) instead of prompting
    LaunchedEffect(discoveredHosts, isMasterEnabled) {
        if (isMasterEnabled && discoveredHosts.isNotEmpty() &&
            connectionStatus == ConnectionStatus.DISCONNECTED) {
            val host = discoveredHosts.first()
            webSocketClient.connect(host.host, host.port, initiatePairingImmediately = false)
        }
    }

    // Immediate auto-reconnect if already paired in preferences (doesn't wait for Bonjour!)
    LaunchedEffect(isMasterEnabled) {
        if (isMasterEnabled) {
            webSocketClient.reconnectIfPaired()
            try {
                ClipboardForegroundService.start(context)
            } catch (e: Exception) {
                android.util.Log.w("LB.Main", "Could not start ForegroundService: ${e.message}")
            }
        } else {
            try {
                ClipboardForegroundService.stop(context)
            } catch (e: Exception) {
                android.util.Log.w("LB.Main", "Could not stop ForegroundService: ${e.message}")
            }
        }
    }

    // Pairing dialog (Accept / Reject)
    pairingChallenge?.let { challenge ->
        AlertDialog(
            onDismissRequest = { challenge.onReject(); pairingChallenge = null },
            containerColor = Surface2,
            tonalElevation = 0.dp,
            icon = {
                Icon(Icons.Filled.Devices, contentDescription = null, tint = AccentON, modifier = Modifier.size(28.dp))
            },
            title = {
                Text(
                    "Pairing Request",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primary,
                    textAlign = TextAlign.Center,
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Pair with \"${challenge.serverName}\"?",
                        fontSize = 13.sp,
                        color = Primary,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Confirm this 6-digit code matches on your Mac screen:",
                        fontSize = 11.sp,
                        color = Secondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentON.copy(alpha = 0.10f))
                            .border(1.dp, AccentON.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            challenge.code,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentON,
                            letterSpacing = 5.sp,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { challenge.onAccept(); pairingChallenge = null },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentON, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Accept & Pair", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { challenge.onReject(); pairingChallenge = null },
                    border = BorderStroke(1.dp, AccentErr.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Reject", color = AccentErr)
                }
            },
        )
    }

    HomeScreen(
        isMasterEnabled = isMasterEnabled,
        onMasterToggle = { enabled ->
            webSocketClient.isMasterEnabled = enabled
            if (enabled) {
                discovery.startDiscovery()
            } else {
                discovery.stopDiscovery()
            }
        },
        discoveredHosts = discoveredHosts,
        isSearching = isSearching,
        connectionStatus = connectionStatus,
        pairedPeerName = pairedPeerName,
        pairedHost = webSocketClient.pairedHostInPrefs,
        fileTransferState = fileTransferState,
        onPairClick = { host ->
            if (webSocketClient.isPairedInPrefs) {
                val currentPeer = webSocketClient.pairedNameInPrefs ?: "current device"
                android.widget.Toast.makeText(
                    context,
                    "Already paired with $currentPeer. Please unpair first to pair with another device.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                webSocketClient.connectAndPair(host.host, host.port)
            }
        },
        onUnpairClick = { webSocketClient.unpair() },
        onRetryDiscovery = {
            discovery.stopDiscovery()
            discovery.startDiscovery()
        },
        onSendClipboardClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            val text = if (clip != null && clip.itemCount > 0)
                clip.getItemAt(0)?.coerceToText(context)?.toString() else null
            if (!text.isNullOrEmpty()) {
                if (webSocketClient.isPaired()) {
                    webSocketClient.sendClipboard(text)
                    android.widget.Toast.makeText(context, "⚡ Sent to Mac clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                } else if (webSocketClient.isLogicallyPaired()) {
                    android.widget.Toast.makeText(context, "Reconnecting to Mac… please wait a moment", android.widget.Toast.LENGTH_SHORT).show()
                    webSocketClient.reconnectIfPaired()
                } else {
                    android.widget.Toast.makeText(context, "Pair with Mac first", android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                android.widget.Toast.makeText(context, "Phone clipboard is empty", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        onSendTextClick = { text ->
            if (text.isNotEmpty()) {
                if (webSocketClient.isPaired()) {
                    webSocketClient.sendClipboard(text)
                    android.widget.Toast.makeText(context, "⚡ Text sent to Mac clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                } else if (webSocketClient.isLogicallyPaired()) {
                    android.widget.Toast.makeText(context, "Reconnecting to Mac… please wait a moment", android.widget.Toast.LENGTH_SHORT).show()
                    webSocketClient.reconnectIfPaired()
                } else {
                    android.widget.Toast.makeText(context, "Pair with Mac first", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        },
        onSendFileClick = { filePickerLauncher.launch("*/*") },
        onCancelFileTransfer = { webSocketClient.cancelFileTransfer() },
    )
}

data class PairingChallenge(
    val serverName: String,
    val code: String,
    val onAccept: () -> Unit,
    val onReject: () -> Unit,
)

// ─────────────────────────────────────────────────────────────────────────────
// Home Screen — Minimalist Design
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun HomeScreen(
    isMasterEnabled: Boolean,
    onMasterToggle: (Boolean) -> Unit,
    discoveredHosts: List<BonjourDiscovery.DiscoveredHost>,
    isSearching: Boolean,
    connectionStatus: ConnectionStatus,
    pairedPeerName: String?,
    pairedHost: String? = null,
    fileTransferState: FileTransferState?,
    onPairClick: (BonjourDiscovery.DiscoveredHost) -> Unit,
    onUnpairClick: () -> Unit,
    onRetryDiscovery: () -> Unit,
    onSendClipboardClick: () -> Unit,
    onSendTextClick: (String) -> Unit,
    onSendFileClick: () -> Unit,
    onCancelFileTransfer: () -> Unit,
) {
    val isPaired = pairedPeerName != null
    val isSocketPaired = connectionStatus == ConnectionStatus.PAIRED
    val isReconnecting = isPaired && !isSocketPaired &&
        (connectionStatus == ConnectionStatus.DISCONNECTED ||
         connectionStatus == ConnectionStatus.CONNECTING ||
         connectionStatus == ConnectionStatus.FAILED)
    var typedText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .imePadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(52.dp))

        // ── Top Bar: Title & Master Toggle Pill ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "LocalBridge",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Primary,
                    letterSpacing = 0.2.sp
                )
                Text(
                    text = "Fast, private Android ↔ Mac transfer",
                    fontSize = 11.sp,
                    color = Secondary
                )
            }

            // Minimalist Master Toggle Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Surface1)
                    .border(1.dp, Border, RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (isMasterEnabled) AccentON else Secondary.copy(alpha = 0.4f), CircleShape)
                )
                Text(
                    text = if (isMasterEnabled) "Active" else "Paused",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isMasterEnabled) Primary else Secondary,
                )
                Switch(
                    checked = isMasterEnabled,
                    onCheckedChange = onMasterToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AccentON,
                        uncheckedThumbColor = Secondary,
                        uncheckedTrackColor = Surface2,
                        uncheckedBorderColor = Border,
                    ),
                    modifier = Modifier.size(width = 40.dp, height = 22.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Master Disabled: Silent Mode Banner ──
        if (!isMasterEnabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface1)
                    .border(1.dp, Border, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Secondary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Pause, contentDescription = null, tint = Secondary, modifier = Modifier.size(18.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("LocalBridge is Paused", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Primary)
                    Text("All network transfers and discovery are stopped.", fontSize = 11.sp, color = Secondary)
                }
                Button(
                    onClick = { onMasterToggle(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentON, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Resume", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(30.dp))
            return
        }

        // ── Device Connection Card ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surface1)
                .border(1.dp, Border, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "DEVICE CONNECTION",
                fontSize = 10.sp,
                color = Secondary,
                letterSpacing = 1.0.sp,
                fontWeight = FontWeight.SemiBold
            )

            if (isPaired) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Surface2)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AccentON.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Laptop, null, tint = AccentON, modifier = Modifier.size(18.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(pairedPeerName ?: "Mac", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Primary)
                        Text(
                            text = if (isSocketPaired) "Connected · Direct local Wi-Fi" else "Reconnecting to Mac…",
                            fontSize = 11.sp,
                            color = if (isSocketPaired) AccentON else AccentWarn
                        )
                    }
                    TextButton(
                        onClick = onUnpairClick,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Unpair", fontSize = 11.sp, color = AccentErr, fontWeight = FontWeight.Medium)
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Surface2)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Border.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Laptop, null, tint = Secondary, modifier = Modifier.size(18.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("No device paired", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Primary)
                        Text(
                            text = if (discoveredHosts.isNotEmpty()) "Mac found · Pair below" else "Searching local network for Mac…",
                            fontSize = 11.sp,
                            color = Secondary
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Card 1: High-Speed File Transfer ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surface1)
                .border(1.dp, Border, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FILE TRANSFER", fontSize = 10.sp, color = Secondary, letterSpacing = 1.0.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("Direct Socket", fontSize = 10.sp, color = AccentON, fontWeight = FontWeight.Medium)
            }

            // Big Send File Action Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isPaired) AccentON.copy(alpha = 0.08f) else Surface2)
                    .border(1.dp, if (isPaired) AccentON.copy(alpha = 0.25f) else Border, RoundedCornerShape(10.dp))
                    .clickable(enabled = isPaired, onClick = onSendFileClick)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isPaired) AccentON.copy(alpha = 0.15f) else Border.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, null, tint = if (isPaired) AccentON else Secondary, modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Send File or Media", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (isPaired) Primary else Secondary)
                    Text("Tap to browse files, photos, or videos", fontSize = 11.sp, color = Secondary)
                }
                Icon(Icons.Filled.ArrowUpward, null, tint = if (isPaired) AccentON else Secondary.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
            }

            // Live Transfer Progress Section
            fileTransferState?.let { transfer ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Surface2)
                        .border(1.dp, Border, RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                if (transfer.isOutgoing) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                null,
                                tint = AccentON,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                if (transfer.isOutgoing) "Sending to Mac" else "Receiving from Mac",
                                fontSize = 11.sp,
                                color = Primary,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Text(
                            "${transfer.percentage}%",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AccentON,
                        )
                    }

                    Text(
                        transfer.fileName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Primary,
                        maxLines = 1,
                    )

                    val animatedProgress by animateFloatAsState(
                        targetValue = (transfer.percentage / 100f).coerceIn(0f, 1f),
                        label = "transferProgress"
                    )
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (transfer.status == FileTransferStatus.FAILED) AccentErr else AccentON,
                        trackColor = Border,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${transfer.formattedTransferred} / ${transfer.formattedTotal}",
                            fontSize = 11.sp,
                            color = Secondary,
                        )
                        if (transfer.speedBytesPerSec > 0) {
                            Text(
                                transfer.formattedSpeed + (if (transfer.etaSeconds > 0) " · ~${transfer.etaSeconds}s" else ""),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = AccentBlue,
                            )
                        }
                    }

                    if (transfer.status == FileTransferStatus.TRANSFERRING) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                onClick = onCancelFileTransfer,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text("Cancel", fontSize = 11.sp, color = AccentErr)
                            }
                        }
                    } else if (transfer.status == FileTransferStatus.COMPLETED) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.CheckCircle, null, tint = AccentON, modifier = Modifier.size(13.dp))
                            Text(
                                if (transfer.isOutgoing) "Sent to Mac Downloads" else "Saved to Downloads",
                                fontSize = 11.sp,
                                color = AccentON,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    } else if (transfer.status == FileTransferStatus.FAILED) {
                        Text(
                            "Transfer failed: ${transfer.errorMessage ?: "Unknown error"}",
                            fontSize = 11.sp,
                            color = AccentErr,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Card 2: Text & Clipboard ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surface1)
                .border(1.dp, Border, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TEXT & CLIPBOARD", fontSize = 10.sp, color = Secondary, letterSpacing = 1.0.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
            }

            // Send Clipboard
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Surface2)
                    .clickable(enabled = isPaired, onClick = onSendClipboardClick)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentON.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.ContentPaste, null, tint = if (isPaired) AccentON else Secondary, modifier = Modifier.size(16.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Send Clipboard to Mac", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (isPaired) Primary else Secondary)
                    Text("Pushes current phone clipboard to Mac", fontSize = 11.sp, color = Secondary)
                }
                Icon(Icons.Filled.ChevronRight, null, tint = if (isPaired) AccentON else Border, modifier = Modifier.size(15.dp))
            }

            // Direct Type & Send Text Input
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = typedText,
                    onValueChange = { typedText = it },
                    placeholder = { Text("Type text to send to Mac clipboard…", fontSize = 12.sp, color = Secondary) },
                    modifier = Modifier.weight(1f),
                    enabled = isPaired,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Primary,
                        unfocusedTextColor = Primary,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = Border,
                        focusedContainerColor = Surface2,
                        unfocusedContainerColor = Surface2,
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                )
                if (typedText.isNotEmpty() && isPaired) {
                    Button(
                        onClick = {
                            onSendTextClick(typedText)
                            typedText = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text("Send", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Card 3: Nearby Devices ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surface1)
                .border(1.dp, Border, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NEARBY DEVICES", fontSize = 10.sp, color = Secondary, letterSpacing = 1.0.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (isSearching) {
                    Text("Scanning…", fontSize = 10.sp, color = AccentON)
                } else {
                    TextButton(
                        onClick = onRetryDiscovery,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) { Text("Scan", fontSize = 10.sp, color = AccentBlue) }
                }
            }

            // This phone
            DeviceRow(
                icon = Icons.Filled.PhoneAndroid,
                iconTint = AccentBlue,
                name = android.os.Build.MODEL,
                detail = "This phone · Android ${android.os.Build.VERSION.RELEASE}",
                badge = {
                    Text("THIS", fontSize = 9.sp, color = AccentBlue, letterSpacing = 0.5.sp, fontWeight = FontWeight.Medium)
                },
            )

            // Discovered Macs
            if (discoveredHosts.isEmpty()) {
                DeviceRow(
                    icon = Icons.Filled.Laptop,
                    iconTint = Border,
                    name = "Searching for Mac…",
                    detail = "Ensure Mac is on same Wi-Fi with LocalBridge open",
                    badge = null,
                )
            } else {
                discoveredHosts.forEach { host ->
                    val isThisHostPaired = isPaired && (
                        (pairedHost != null && host.host == pairedHost) ||
                        pairedPeerName.equals(host.serviceName, ignoreCase = true) ||
                        (pairedPeerName != null && host.serviceName.contains(pairedPeerName, ignoreCase = true)) ||
                        (pairedPeerName != null && pairedPeerName.contains(host.serviceName, ignoreCase = true)) ||
                        discoveredHosts.size == 1
                    )
                    val isPairingHost = connectionStatus == ConnectionStatus.PAIRING || connectionStatus == ConnectionStatus.CONNECTING

                    DeviceRow(
                        icon = Icons.Filled.Laptop,
                        iconTint = if (isThisHostPaired) AccentON else Secondary,
                        name = host.serviceName,
                        detail = "${host.host}:${host.port}",
                        badge = {
                            when {
                                isThisHostPaired -> {
                                    Text("PAIRED", fontSize = 9.sp, color = AccentON, fontWeight = FontWeight.Bold)
                                }
                                isPairingHost -> {
                                    Text("PAIRING…", fontSize = 9.sp, color = AccentWarn)
                                }
                                else -> {
                                    Button(
                                        onClick = { onPairClick(host) },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentON, contentColor = Color.Black),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                    ) {
                                        Text("Pair", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared Minimal Composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeviceRow(
    icon: ImageVector,
    iconTint: Color,
    name: String,
    detail: String,
    badge: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(iconTint.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Primary)
            Text(detail, fontSize = 11.sp, color = Secondary)
        }
        badge?.invoke()
    }
}

