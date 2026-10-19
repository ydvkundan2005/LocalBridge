package dev.localbridge.android.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localbridge.android.LocalBridgeApplication
import dev.localbridge.android.network.ConnectionStatus
import dev.localbridge.android.network.FileTransferState
import dev.localbridge.android.network.FileTransferStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Handles Android Share Sheet intent:
//   - ACTION_SEND -> single file shared to LocalBridge
//   - ACTION_SEND_MULTIPLE -> multiple files shared (sent sequentially)
//
// Registered in AndroidManifest.xml with intent-filter for action SEND / SEND_MULTIPLE
// and all mime types. This makes LocalBridge appear in the system Share menu for any file.
//
// The activity uses a translucent transparent theme and renders a bottom-sheet-style
// Compose overlay without showing an opaque activity window.
class ShareReceiveActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LB.ShareReceive"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = applicationContext as? LocalBridgeApplication
        val client = app?.webSocketClient

        // Collect incoming URIs from the Share intent
        val uris = mutableListOf<Uri>()
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) uris += uri
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val extras = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (extras != null) uris += extras
            }
        }

        if (uris.isEmpty()) {
            Log.w(TAG, "Share intent received with no URI — finishing immediately")
            finish()
            return
        }

        Log.i(TAG, "Share intent: ${uris.size} file(s) — isPaired=${client?.isPaired()}")

        setContent {
            LocalBridgeTheme {
                ShareSheet(
                    client = client,
                    uris = uris,
                    onDismiss = { finish() },
                    onOpenApp = {
                        val intent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(intent)
                        finish()
                    },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Design tokens (mirror MainActivity)
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
// Share Sheet Composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShareSheet(
    client: dev.localbridge.android.network.LocalBridgeWebSocketClient?,
    uris: List<Uri>,
    onDismiss: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val connectionStatus by (client?.status ?: kotlinx.coroutines.flow.MutableStateFlow(ConnectionStatus.DISCONNECTED))
        .collectAsStateWithLifecycle()
    val fileTransferState by (client?.fileTransferState ?: kotlinx.coroutines.flow.MutableStateFlow(null))
        .collectAsStateWithLifecycle()
    val pairedName by (client?.pairedPeerName ?: kotlinx.coroutines.flow.MutableStateFlow(null))
        .collectAsStateWithLifecycle()

    val isPaired = connectionStatus == ConnectionStatus.PAIRED
    var hasSent by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }

    // Auto-send if paired
    LaunchedEffect(isPaired, hasSent) {
        if (isPaired && !hasSent && client != null) {
            hasSent = true
            coroutineScope.launch {
                try {
                    for (uri in uris) {
                        client.sendFile(uri)
                    }
                } catch (e: Exception) {
                    sendError = e.message ?: "Transfer failed"
                }
            }
        }
    }

    // Auto-dismiss after completion
    val transfer = fileTransferState
    LaunchedEffect(transfer?.status) {
        if (transfer?.status == FileTransferStatus.COMPLETED) {
            delay(2000)
            onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Surface1)
                .border(1.dp, Border, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Handle
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Border)
            )

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(AccentON.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Share, null, tint = AccentON, modifier = Modifier.size(18.dp))
                    }
                    Column {
                        Text(
                            "Send via LocalBridge",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Primary,
                        )
                        Text(
                            "${uris.size} file${if (uris.size > 1) "s" else ""}" +
                                (pairedName?.let { " → $it" } ?: ""),
                            fontSize = 11.sp,
                            color = Secondary,
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, null, tint = Secondary, modifier = Modifier.size(20.dp))
                }
            }

            // Content area
            when {
                !isPaired && transfer == null -> {
                    // Not connected
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(AccentErr.copy(alpha = 0.07f))
                            .border(1.dp, AccentErr.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.WifiOff, null, tint = AccentErr, modifier = Modifier.size(28.dp))
                        Text(
                            "Not connected to Mac",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Primary,
                        )
                        Text(
                            "Open LocalBridge and pair with your Mac first, then share this file again.",
                            fontSize = 12.sp,
                            color = Secondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onDismiss,
                                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text("Cancel", color = Secondary, fontSize = 13.sp)
                            }
                            Button(
                                onClick = onOpenApp,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text("Open LocalBridge", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
                    }
                }

                transfer != null -> {
                    // Transfer in progress / completed / failed
                    TransferProgressCard(transfer = transfer)
                }

                isPaired && !hasSent -> {
                    // Waiting to start
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = AccentON,
                        )
                        Text("Preparing transfer…", fontSize = 13.sp, color = Secondary)
                    }
                }

                sendError != null -> {
                    Text(
                        "Transfer failed: $sendError",
                        fontSize = 12.sp,
                        color = AccentErr,
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Surface2),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Dismiss", color = Primary)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TransferProgressCard(transfer: FileTransferState) {
    val animatedProgress by animateFloatAsState(
        targetValue = (transfer.percentage / 100f).coerceIn(0f, 1f),
        label = "shareProgress",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                transfer.fileName,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                "${transfer.percentage}%",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = when (transfer.status) {
                    FileTransferStatus.COMPLETED -> AccentON
                    FileTransferStatus.FAILED, FileTransferStatus.CANCELLED -> AccentErr
                    else -> AccentON
                },
            )
        }

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = when (transfer.status) {
                FileTransferStatus.COMPLETED -> AccentON
                FileTransferStatus.FAILED, FileTransferStatus.CANCELLED -> AccentErr
                else -> AccentON
            },
            trackColor = Border,
        )

        when (transfer.status) {
            FileTransferStatus.TRANSFERRING -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${transfer.formattedTransferred} / ${transfer.formattedTotal}",
                        fontSize = 11.sp,
                        color = Secondary,
                    )
                    Text(
                        transfer.formattedSpeed +
                            (if (transfer.etaSeconds > 0) " · ~${transfer.etaSeconds}s" else ""),
                        fontSize = 11.sp,
                        color = AccentBlue,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            FileTransferStatus.COMPLETED -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.CheckCircle, null, tint = AccentON, modifier = Modifier.size(14.dp))
                    Text("Saved to Mac Downloads", fontSize = 11.sp, color = AccentON, fontWeight = FontWeight.Medium)
                }
            }
            FileTransferStatus.FAILED -> {
                Text(
                    "Failed: ${transfer.errorMessage ?: "Unknown error"}",
                    fontSize = 11.sp,
                    color = AccentErr,
                )
            }
            FileTransferStatus.CANCELLED -> {
                Text("Cancelled", fontSize = 11.sp, color = Secondary)
            }
        }
    }
}
