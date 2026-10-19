package dev.localbridge.android.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    PAIRING,
    PAIRED,
    FAILED
}

/**
 * Manages the WebSocket connection to the Mac server.
 *
 * ─── Pairing design ───────────────────────────────────────────────────────────
 * Pairing is a LOGICAL state stored in SharedPreferences. It is completely
 * independent of the WebSocket transport lifetime.
 *
 *  • Once paired → stays "paired" in prefs even if the socket closes, the app
 *    is killed, or the phone is rebooted.
 *  • On every (re)connection the device automatically sends pair.reidentify so
 *    the Mac server can silently restore the session without a pairing dialog.
 *  • The only way to clear pairing is an explicit call to unpair().
 *  • Auto-reconnect runs every 4 s whenever the prefs say we are paired and the
 *    socket is not currently open.
 * ──────────────────────────────────────────────────────────────────────────────
 */
private class OutgoingTransferCoordinator(val maxInFlight: Int = 32) {
    private val channel = kotlinx.coroutines.channels.Channel<Unit>(maxInFlight)
    @Volatile var isCancelled = false
    @Volatile var totalBytesAcked = 0L

    init {
        repeat(maxInFlight) { channel.trySend(Unit) }
    }

    suspend fun waitToSend() {
        if (isCancelled) return
        channel.receive()
    }

    fun onAckReceived(bytesAcked: Long) {
        totalBytesAcked = maxOf(totalBytesAcked, bytesAcked)
        channel.trySend(Unit)
    }

    fun cancel() {
        isCancelled = true
        channel.close()
    }
}

class LocalBridgeWebSocketClient(private val context: Context) {

    companion object {
        private const val TAG             = "LB.WS"
        private const val CHUNK_SIZE      = 512 * 1024        // 512 KB per binary frame for maximum throughput
        private const val QUEUE_LIMIT     = 16  * 1024 * 1024 // 16 MB back-pressure limit
        private const val RECONNECT_MS    = 4_000L            // retry interval while paired
        private const val RESET_DELAY_MS  = 2_500L            // UI auto-reset after transfer
        private const val FILE_SAVED_TIMEOUT_MS = 30_000L     // max wait for file.saved ack

        // SharedPreferences keys
        private const val PREFS_NAME        = "lb_prefs"
        private const val KEY_PAIRED        = "paired"
        private const val KEY_PAIRED_NAME   = "paired_name"
        private const val KEY_PAIRED_HOST   = "paired_host"
        private const val KEY_PAIRED_PORT   = "paired_port"
        private const val KEY_DEVICE_ID     = "device_id"
        private const val KEY_MASTER_ENABLED = "master_enabled"
    }

    // ─── OkHttp ──────────────────────────────────────────────────────────────

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0,  TimeUnit.MILLISECONDS) // infinite for WebSocket frames
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var webSocket: WebSocket? = null

    // ─── SharedPreferences ───────────────────────────────────────────────────

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Stable device-ID generated once and persisted forever. */
    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null)
        ?: UUID.randomUUID().toString().lowercase()
            .also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }

    // ─── Pairing helpers (always read/write from prefs) ───────────────────────

    /** True if the user has ever successfully paired. Persists across restarts. */
    private var prefPaired: Boolean
        get()      = prefs.getBoolean(KEY_PAIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_PAIRED, value).apply()

    private var prefPairedName: String?
        get()      = prefs.getString(KEY_PAIRED_NAME, null)
        set(value) = if (value != null)
                         prefs.edit().putString(KEY_PAIRED_NAME, value).apply()
                     else
                         prefs.edit().remove(KEY_PAIRED_NAME).apply()

    private var prefHost: String?
        get()      = prefs.getString(KEY_PAIRED_HOST, null)
        set(value) = if (value != null)
                         prefs.edit().putString(KEY_PAIRED_HOST, value).apply()
                     else
                         prefs.edit().remove(KEY_PAIRED_HOST).apply()

    private var prefPort: Int
        get()      = prefs.getInt(KEY_PAIRED_PORT, 38473)
        set(value) = prefs.edit().putInt(KEY_PAIRED_PORT, value).apply()

    /** Master toggle state persisted across app restarts. */
    var isMasterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_MASTER_ENABLED, value).apply()
            _isMasterEnabledFlow.value = value
            if (!value) {
                stopReconnectLoop()
                disconnect()
            }
        }

    private val _isMasterEnabledFlow = MutableStateFlow(prefs.getBoolean(KEY_MASTER_ENABLED, true))
    val isMasterEnabledFlow: StateFlow<Boolean> = _isMasterEnabledFlow

    val isPairedInPrefs: Boolean get() = prefPaired
    val pairedNameInPrefs: String? get() = prefPairedName
    val pairedHostInPrefs: String? get() = prefHost

    // ─── Public state flows ───────────────────────────────────────────────────

    private val _status = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val status: StateFlow<ConnectionStatus> = _status

    /**
     * The name of the paired Mac. Non-null as soon as pairing succeeds and
     * stays non-null across socket disconnects / app restarts (reads from prefs).
     */
    private val _pairedPeerName = MutableStateFlow<String?>(prefPairedName)
    val pairedPeerName: StateFlow<String?> = _pairedPeerName

    private val _statusMessage = MutableStateFlow(
        if (prefPaired) "Connecting to ${prefPairedName ?: "Mac"}…" else "Ready to connect"
    )
    val statusMessage: StateFlow<String> = _statusMessage

    /** File transfer progress — null when idle. */
    private val _fileTransferState = MutableStateFlow<FileTransferState?>(null)
    val fileTransferState: StateFlow<FileTransferState?> = _fileTransferState

    // ─── Internal state ───────────────────────────────────────────────────────

    private var receivingFileTransfer: FileTransferState? = null
    private var receivingStream: OutputStream?            = null
    private var receivingUri: Uri?                        = null
    private var receivingStartTime: Long                  = 0L
    @Volatile private var activeReceivingFileIdBytes: ByteArray? = null
    @Volatile private var lastProgressUpdateTime: Long    = 0L

    @Volatile private var isOutgoingCancelled = false
    private var outgoingCoordinator: OutgoingTransferCoordinator? = null
    private var activeOutgoingFileId: String? = null
    private var outgoingStartTime: Long = 0L

    /** fileId of an outgoing transfer waiting for file.saved ack from the receiver. */
    private var pendingFileSavedFileId: String? = null
    /** Continuation to resume when file.saved arrives for outgoing transfer. */
    private var pendingFileSavedContinuation: CancellableContinuation<Boolean>? = null

    // Pending pairing dialog info
    private var pendingChallengeWs:        WebSocket? = null
    private var pendingChallengeMessageId: String?    = null

    /** Coroutine scope for background tasks (reconnect loop, transfer reset). */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Job that keeps retrying the connection whenever we are logically paired
     *  but the socket is not open. Cancelled on explicit disconnect/unpair. */
    private var reconnectJob: Job? = null

    init {
        // If we are already paired from a previous session AND master is enabled, start reconnect loop
        if (isMasterEnabled && prefPaired && prefHost != null) {
            startReconnectLoop()
        }
    }

    // ─── Callbacks ───────────────────────────────────────────────────────────

    /** Invoked on the calling thread when a pair.challenge arrives (Mac → Phone). */
    var onPairingChallengeReceived: ((serverName: String, code: String,
                                      accept: () -> Unit,
                                      reject: () -> Unit) -> Unit)? = null

    var onClipboardReceived: ((content: String, mimeType: String, bytes: Int) -> Unit)? = null

    // ─── Device name ──────────────────────────────────────────────────────────

    private val deviceName: String = run {
        val mfg   = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        if (model.startsWith(mfg, ignoreCase = true)) model else "$mfg $model"
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Open a WebSocket to [host]:[port]. If [initiatePairingImmediately] is
     * true the device will send pair.begin straight away (used when the user
     * taps PAIR in the UI). Otherwise it sends pair.reidentify when already
     * paired or just connects when unpaired.
     */
    fun connect(host: String, port: Int, initiatePairingImmediately: Boolean = false) {
        if (!isMasterEnabled) {
            Log.d(TAG, "connect() ignored: master toggle is OFF")
            return
        }
        // Avoid stomping an already-live connection to the same endpoint
        if (webSocket != null && host == prefHost && port == prefPort &&
            (_status.value == ConnectionStatus.CONNECTED ||
             _status.value == ConnectionStatus.PAIRED    ||
             _status.value == ConnectionStatus.PAIRING)) {
            if (initiatePairingImmediately && !prefPaired) initiatePairing()
            return
        }

        // Persist last-known host so auto-reconnect can use it after restart
        prefHost = host
        prefPort = port

        _status.value = ConnectionStatus.CONNECTING
        _statusMessage.value = "Connecting to $host…"
        Log.i(TAG, "connect → ws://$host:$port  (paired=$prefPaired)")

        webSocket?.close(1000, "Reconnecting")

        val request = Request.Builder().url("ws://$host:$port").build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Socket open  paired=$prefPaired  immediate=$initiatePairingImmediately")
                when {
                    initiatePairingImmediately && !prefPaired -> {
                        _status.value = ConnectionStatus.PAIRING
                        _statusMessage.value = "Pairing with Mac…"
                        sendPairBegin(ws)
                    }
                    prefPaired -> {
                        // Already paired — re-identify so Mac can restore silently
                        _status.value = ConnectionStatus.PAIRED
                        _statusMessage.value = "Connected to ${prefPairedName ?: "Mac"}"
                        sendReidentify(ws)
                    }
                    else -> {
                        _status.value = ConnectionStatus.CONNECTED
                        _statusMessage.value = "Connected — ready to pair"
                    }
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try { handleText(ws, text) } catch (e: Exception) {
                    Log.e(TAG, "handleText error", e)
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                try { handleBinary(bytes) } catch (e: Exception) {
                    Log.e(TAG, "handleBinary error", e)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Socket failure: ${t.message}")
                webSocket = null
                outgoingCoordinator?.cancel()
                outgoingCoordinator = null
                activeOutgoingFileId = null
                pendingFileSavedContinuation?.resume(false)
                pendingFileSavedContinuation = null
                pendingFileSavedFileId = null
                try { receivingStream?.close() } catch (_: Exception) {}
                receivingStream = null
                if (receivingFileTransfer != null) {
                    _fileTransferState.value = receivingFileTransfer?.copy(
                        status = FileTransferStatus.FAILED,
                        errorMessage = "Connection lost"
                    )
                    receivingFileTransfer = null
                    scheduleTransferReset()
                }
                if (isMasterEnabled && prefPaired) {
                    // Logical pairing intact — show as paired-but-offline
                    _status.value = ConnectionStatus.DISCONNECTED
                    _statusMessage.value = "Reconnecting to ${prefPairedName ?: "Mac"}…"
                    startReconnectLoop()
                } else {
                    _status.value = ConnectionStatus.DISCONNECTED
                    _statusMessage.value = if (!isMasterEnabled) "Disabled" else "Connection failed"
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Socket closed: $reason ($code)")
                webSocket = null
                outgoingCoordinator?.cancel()
                outgoingCoordinator = null
                activeOutgoingFileId = null
                pendingFileSavedContinuation?.resume(false)
                pendingFileSavedContinuation = null
                pendingFileSavedFileId = null
                try { receivingStream?.close() } catch (_: Exception) {}
                receivingStream = null
                if (receivingFileTransfer != null) {
                    _fileTransferState.value = receivingFileTransfer?.copy(
                        status = FileTransferStatus.FAILED,
                        errorMessage = "Connection closed"
                    )
                    receivingFileTransfer = null
                    scheduleTransferReset()
                }
                if (!isMasterEnabled || (code == 1000 && reason == "User disconnected")) {
                    // Explicit user disconnect or toggle OFF — stay disconnected
                    _status.value = ConnectionStatus.DISCONNECTED
                    _statusMessage.value = if (!isMasterEnabled) "Disabled" else "Disconnected"
                } else if (prefPaired) {
                    // Unexpected close while paired — reconnect
                    _status.value = ConnectionStatus.DISCONNECTED
                    _statusMessage.value = "Reconnecting to ${prefPairedName ?: "Mac"}…"
                    startReconnectLoop()
                } else {
                    _status.value = ConnectionStatus.DISCONNECTED
                    _statusMessage.value = "Disconnected"
                }
            }
        })
    }

    fun connectAndPair(host: String, port: Int) {
        if (!isMasterEnabled) {
            Log.d(TAG, "connectAndPair() ignored: master toggle is OFF")
            return
        }
        if (prefPaired && (prefHost != host || prefPort != port)) {
            Log.w(TAG, "connectAndPair() blocked: strictly 1-to-1 pairing enforced, already paired with $prefPairedName")
            _statusMessage.value = "Already paired with ${prefPairedName ?: "another device"}. Unpair first."
            return
        }
        connect(host, port, initiatePairingImmediately = true)
    }

    fun initiatePairing() {
        val ws = webSocket
        if (ws != null) {
            _status.value = ConnectionStatus.PAIRING
            _statusMessage.value = "Pairing with Mac…"
            sendPairBegin(ws)
        } else {
            val h = prefHost
            if (h != null) connect(h, prefPort, initiatePairingImmediately = true)
        }
    }

    /** Immediately trigger reconnect if we are logically paired and disconnected */
    fun reconnectIfPaired() {
        if (!isMasterEnabled) return
        val h = prefHost
        if (prefPaired && h != null && _status.value != ConnectionStatus.PAIRED && _status.value != ConnectionStatus.CONNECTING) {
            Log.d(TAG, "reconnectIfPaired() connecting to $h:$prefPort")
            connect(h, prefPort, initiatePairingImmediately = false)
        }
    }

    /**
     * Explicit user-initiated disconnect (master toggle OFF). Does NOT clear
     * pairing state — pair is remembered for the next session.
     */
    fun disconnect() {
        stopReconnectLoop()
        outgoingCoordinator?.cancel()
        outgoingCoordinator = null
        activeOutgoingFileId = null
        pendingFileSavedContinuation?.resume(false)
        pendingFileSavedContinuation = null
        pendingFileSavedFileId = null
        try { receivingStream?.close() } catch (_: Exception) {}
        receivingStream = null
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _status.value = ConnectionStatus.DISCONNECTED
        _statusMessage.value = if (isMasterEnabled) "Disconnected" else "Disabled"
    }

    /** Explicit unpair — clears ALL persisted pairing state. */
    fun unpair() {
        stopReconnectLoop()
        val ws = webSocket
        if (ws != null) sendEnvelope(ws, "pair.unpair", null, JSONObject())
        webSocket?.close(1000, "Unpaired")
        webSocket = null

        prefPaired     = false
        prefPairedName = null

        _pairedPeerName.value = null
        _status.value         = ConnectionStatus.DISCONNECTED
        _statusMessage.value  = "Unpaired"
        Log.i(TAG, "Unpaired — prefs cleared")
    }

    /** True only when the socket is open AND logically paired. */
    fun isPaired(): Boolean = webSocket != null && _status.value == ConnectionStatus.PAIRED

    /** True when logically paired (even if socket is temporarily down). */
    fun isLogicallyPaired(): Boolean = prefPaired && prefPairedName != null

    // ─── Clipboard ───────────────────────────────────────────────────────────

    fun sendClipboard(content: String, mimeType: String = "text/plain;charset=utf-8") {
        val ws = webSocket ?: return
        if (!prefPaired) { Log.w(TAG, "Not paired — skip clipboard send"); return }
        val payload = JSONObject().apply {
            put("eventId",        UUID.randomUUID().toString().lowercase())
            put("originDeviceId", deviceId)
            put("originSequence", 1)
            put("capturedAt",     java.time.Instant.now().toString())
            put("mimeType",       mimeType)
            put("byteLength",     content.toByteArray().size)
            put("content",        content)
        }
        sendEnvelope(ws, "clipboard.push", null, payload)
        Log.i(TAG, "Sent clipboard (${content.length} chars)")
    }

    // ─── File send ───────────────────────────────────────────────────────────

    suspend fun sendFile(uri: Uri) = withContext(Dispatchers.IO) {
        val ws = webSocket ?: return@withContext
        if (!prefPaired) { Log.w(TAG, "Not paired — skip file send"); return@withContext }

        var fileName = "file"; var fileSize = 0L
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (ni != -1) fileName = c.getString(ni) ?: "file"
                if (si != -1) fileSize = c.getLong(si)
            }
        }

        val fileId = UUID.randomUUID().toString().lowercase()
        isOutgoingCancelled = false
        activeOutgoingFileId = fileId
        val coordinator = OutgoingTransferCoordinator(maxInFlight = 32)
        outgoingCoordinator = coordinator
        outgoingStartTime = System.currentTimeMillis()

        var state = FileTransferState(id = fileId, fileName = fileName,
            totalBytes = fileSize, isOutgoing = true,
            status = FileTransferStatus.TRANSFERRING)
        _fileTransferState.value = state

        sendEnvelope(ws, "file.start", null, JSONObject().apply {
            put("fileId",   fileId); put("fileName", fileName)
            put("fileSize", fileSize); put("mimeType", mimeType)
            put("chunkSize", CHUNK_SIZE)
        })

        try {
            val rawStream = context.contentResolver.openInputStream(uri) ?: return@withContext
            java.io.BufferedInputStream(rawStream, 1024 * 1024).use { stream ->
                val buf        = ByteArray(CHUNK_SIZE)
                val fileIdB    = fileId.toByteArray(Charsets.US_ASCII) // 36 bytes
                var idx        = 0
                var readTotal  = 0L

                while (readTotal < fileSize && !isOutgoingCancelled && !coordinator.isCancelled) {
                    coordinator.waitToSend()
                    if (isOutgoingCancelled || coordinator.isCancelled) break

                    val n = stream.read(buf)
                    if (n <= 0) break

                    val pkt = ByteArray(40 + n)
                    System.arraycopy(fileIdB, 0, pkt, 0,  36)
                    pkt[36] = (idx ushr 24).toByte()
                    pkt[37] = (idx ushr 16).toByte()
                    pkt[38] = (idx ushr 8).toByte()
                    pkt[39] = idx.toByte()
                    System.arraycopy(buf,     0, pkt, 40, n)
                    ws.send(pkt.toByteString())
                    readTotal += n
                    idx++

                    while (ws.queueSize() > QUEUE_LIMIT && !isOutgoingCancelled && !coordinator.isCancelled) {
                        delay(1)
                    }
                }

                if (isOutgoingCancelled || coordinator.isCancelled) {
                    sendEnvelope(ws, "file.cancel", null, JSONObject().apply { put("fileId", fileId) })
                    _fileTransferState.value = state.copy(status = FileTransferStatus.CANCELLED)
                    outgoingCoordinator = null
                    activeOutgoingFileId = null
                    scheduleTransferReset()
                    return@withContext
                }

                // Wait until all in-flight chunks are acknowledged by receiver
                val ackWaitStart = System.currentTimeMillis()
                while (coordinator.totalBytesAcked < fileSize && !isOutgoingCancelled && !coordinator.isCancelled) {
                    if (System.currentTimeMillis() - ackWaitStart > 60_000L) {
                        Log.e(TAG, "Timed out waiting for chunk acknowledgments from receiver")
                        break
                    }
                    delay(2)
                }

                if (isOutgoingCancelled || coordinator.isCancelled || coordinator.totalBytesAcked < fileSize) {
                    _fileTransferState.value = state.copy(
                        status = FileTransferStatus.FAILED,
                        errorMessage = "Transmission incomplete: acknowledged ${coordinator.totalBytesAcked}/$fileSize bytes"
                    )
                    outgoingCoordinator = null
                    activeOutgoingFileId = null
                    scheduleTransferReset()
                    return@withContext
                }

                // Send file.complete to inform receiver all chunks are done
                sendEnvelope(ws, "file.complete", null, JSONObject().apply {
                    put("fileId", fileId); put("fileName", fileName); put("totalBytes", fileSize)
                })

                state = state.copy(bytesTransferred = fileSize, status = FileTransferStatus.TRANSFERRING)
                _fileTransferState.value = state
                Log.i(TAG, "All chunks acked for $fileName ($fileSize B) — waiting for file.saved ack")

                // Wait for file.saved ack from receiver (max 30s)
                val savedConfirmed = withTimeoutOrNull(FILE_SAVED_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        pendingFileSavedFileId = fileId
                        pendingFileSavedContinuation = cont
                        cont.invokeOnCancellation {
                            pendingFileSavedFileId = null
                            pendingFileSavedContinuation = null
                        }
                    }
                } ?: false

                if (savedConfirmed) {
                    _fileTransferState.value = state.copy(status = FileTransferStatus.COMPLETED)
                    Log.i(TAG, "File saved confirmed by receiver: $fileName")
                } else {
                    _fileTransferState.value = state.copy(status = FileTransferStatus.FAILED,
                        errorMessage = "Receiver did not confirm file save")
                    Log.w(TAG, "file.saved ack timed out or failed for $fileName")
                }
                pendingFileSavedFileId = null
                pendingFileSavedContinuation = null
                outgoingCoordinator = null
                activeOutgoingFileId = null
                scheduleTransferReset()
            }
        } catch (e: Exception) {
            Log.e(TAG, "File send error", e)
            pendingFileSavedFileId = null
            pendingFileSavedContinuation?.cancel()
            pendingFileSavedContinuation = null
            outgoingCoordinator = null
            activeOutgoingFileId = null
            _fileTransferState.value = state.copy(status = FileTransferStatus.FAILED,
                errorMessage = e.message)
            scheduleTransferReset()
        }
    }

    fun cancelFileTransfer() {
        isOutgoingCancelled = true
        outgoingCoordinator?.cancel()
        outgoingCoordinator = null
        activeOutgoingFileId = null
        val cur = _fileTransferState.value; val ws = webSocket
        if (cur != null && ws != null)
            sendEnvelope(ws, "file.cancel", null,
                JSONObject().apply { put("fileId", cur.id) })
        _fileTransferState.value = cur?.copy(status = FileTransferStatus.CANCELLED)
        receivingStream?.close(); receivingStream = null; receivingFileTransfer = null
        scheduleTransferReset()
    }

    // =========================================================================
    // Private — auto-reconnect loop
    // =========================================================================

    private fun startReconnectLoop() {
        if (!isMasterEnabled) return
        if (reconnectJob?.isActive == true) return   // already running
        reconnectJob = scope.launch {
            while (prefPaired && isMasterEnabled) {
                if (_status.value != ConnectionStatus.PAIRED &&
                    _status.value != ConnectionStatus.CONNECTING &&
                    _status.value != ConnectionStatus.PAIRING) {
                    val h = prefHost
                    if (h != null && isMasterEnabled) {
                        Log.d(TAG, "Reconnect loop connecting to $h:$prefPort…")
                        withContext(Dispatchers.Main) {
                            connect(h, prefPort, initiatePairingImmediately = false)
                        }
                    }
                }
                delay(RECONNECT_MS)
            }
        }
    }

    private fun stopReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    // =========================================================================
    // Private — binary frames (file chunks)
    // =========================================================================

    private fun handleBinary(bytes: ByteString) {
        if (bytes.size < 40) return
        val cur = receivingFileTransfer ?: return
        val idBytes = activeReceivingFileIdBytes ?: return
        if (!bytes.rangeEquals(0, idBytes, 0, 36)) return
        val ws = webSocket ?: return

        val b0 = bytes[36].toInt() and 0xFF
        val b1 = bytes[37].toInt() and 0xFF
        val b2 = bytes[38].toInt() and 0xFF
        val b3 = bytes[39].toInt() and 0xFF
        val chunkIdx = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3

        val raw = bytes.toByteArray()
        val chunkLen = raw.size - 40
        val stream = receivingStream ?: return

        try {
            val updated = cur.bytesTransferred + chunkLen
            val now = System.currentTimeMillis()
            val ms = now - receivingStartTime
            receivingFileTransfer = cur.copy(
                bytesTransferred = updated,
                speedBytesPerSec = if (ms > 0) updated * 1000.0 / ms else 0.0
            )

            if (now - lastProgressUpdateTime >= 50 || updated >= cur.totalBytes) {
                lastProgressUpdateTime = now
                _fileTransferState.value = receivingFileTransfer
            }

            // High-throughput zero-overhead ACK sent immediately so sender does not stall
            val ackJson = """{"protocol":"localbridge","protocolVersion":1,"type":"file.ack","payload":{"fileId":"${cur.id}","chunkIndex":$chunkIdx,"bytesReceived":$updated}}"""
            ws.send(ackJson)

            // Direct write to high-performance buffered stream (2 MB buffer)
            stream.write(raw, 40, chunkLen)
        } catch (e: Exception) {
            Log.e(TAG, "Chunk write error", e)
            try { receivingStream?.close() } catch (_: Exception) {}
            receivingStream = null
            _fileTransferState.value = cur.copy(status = FileTransferStatus.FAILED,
                errorMessage = e.message)
            receivingFileTransfer = null
            sendEnvelope(ws, "file.saved", null, JSONObject().apply {
                put("fileId", cur.id)
                put("status", "error")
                put("error", e.message ?: "Write error")
            })
            scheduleTransferReset()
        }
    }

    // =========================================================================
    // Private — JSON envelope handler
    // =========================================================================

    private fun handleText(ws: WebSocket, text: String) {
        val env     = JSONObject(text)
        val type    = env.optString("type")
        val payload = env.optJSONObject("payload") ?: JSONObject()
        val msgId: String? = env.optString("messageId").takeIf { it.isNotEmpty() }
        Log.d(TAG, "← $type")

        when (type) {

            // ── Pairing ──────────────────────────────────────────────────────

            "pair.challenge", "pair.request" -> {
                val serverName = payload.optString("serverName", "Mac")
                val code       = payload.optString("comparisonCode", "------")
                Log.i(TAG, "Challenge from $serverName (code=$code)")
                pendingChallengeWs        = ws
                pendingChallengeMessageId = msgId
                onPairingChallengeReceived?.invoke(serverName, code,
                    { acceptPairing(ws, serverName, code, msgId) },
                    { rejectPairing(ws) })
            }

            "pair.result" -> {
                val ok         = payload.optBoolean("success", false)
                val serverName = payload.optString("pairedDeviceName", "Mac")
                val reason     = payload.optString("reason", "")
                if (ok) {
                    Log.i(TAG, "Pairing confirmed with $serverName")
                    prefPaired     = true
                    prefPairedName = serverName
                    _pairedPeerName.value = serverName
                    _status.value         = ConnectionStatus.PAIRED
                    _statusMessage.value  = "Paired with $serverName"
                    pendingChallengeWs = null
                    startReconnectLoop()   // keep alive going forward
                } else {
                    Log.w(TAG, "Pairing result failed: $reason")
                    // Do NOT wipe out saved pairing if we were already paired and an auto-reconnect was rejected
                    if (!prefPaired) {
                        _pairedPeerName.value = null
                    }
                    _status.value         = ConnectionStatus.CONNECTED
                    _statusMessage.value  = if (reason == "ALREADY_PAIRED")
                        "Mac is already paired with another device"
                    else "Pairing rejected by $serverName"
                }
            }

            // Mac silently restored the session after reconnect
            "pair.session.restored" -> {
                val serverName = payload.optString("pairedDeviceName",
                    prefPairedName ?: "Mac")
                Log.i(TAG, "Session restored with $serverName")
                prefPaired            = true
                prefPairedName        = serverName
                _pairedPeerName.value = serverName
                _status.value         = ConnectionStatus.PAIRED
                _statusMessage.value  = "Connected to $serverName"
            }

            "pair.unpair" -> {
                Log.i(TAG, "Mac sent pair.unpair")
                prefPaired     = false
                prefPairedName = null
                _pairedPeerName.value = null
                _status.value         = ConnectionStatus.CONNECTED
                _statusMessage.value  = "Unpaired by Mac"
                stopReconnectLoop()
            }

            // ── Clipboard ────────────────────────────────────────────────────

            "clipboard.push" -> {
                if (!prefPaired) { Log.w(TAG, "clipboard.push ignored — not paired"); return }
                val content  = payload.optString("content", "")
                val mimeType = payload.optString("mimeType", "text/plain")
                val bytes    = payload.optInt("byteLength", content.toByteArray().size)
                Log.i(TAG, "← clipboard.push ($bytes B)")
                applyClipboard(content)
                onClipboardReceived?.invoke(content, mimeType, bytes)
                sendEnvelope(ws, "clipboard.ack", msgId, JSONObject().apply {
                    put("eventId",  payload.optString("eventId"))
                    put("status",   "APPLIED")
                    put("appliedAt", java.time.Instant.now().toString())
                })
            }

            // ── File events ──────────────────────────────────────────────────

            "file.start" -> {
                if (!prefPaired) return
                val fileId   = payload.optString("fileId", UUID.randomUUID().toString())
                val rawFileName = payload.optString("fileName", "received_file")
                // Sanitize: strip directory traversal — use last path component only
                val fileName = rawFileName
                    .substringAfterLast("/")
                    .substringAfterLast("\\")
                    .replace("..", "_")
                    .trim()
                    .ifEmpty { "received_file" }
                val fileSize = payload.optLong("fileSize", 0L)
                val mime     = payload.optString("mimeType", "application/octet-stream")
                Log.i(TAG, "file.start: $fileName ($fileSize B)")
                try { receivingStream?.close() } catch (_: Exception) {}
                receivingStream = null
                activeReceivingFileIdBytes = fileId.toByteArray(Charsets.US_ASCII)
                try {
                    val (uri, stream) = createDownloadStream(fileName, mime)
                    if (stream == null) {
                        Log.e(TAG, "Cannot create download stream for $fileName")
                        sendEnvelope(ws, "file.saved", null, JSONObject().apply {
                            put("fileId", fileId)
                            put("status", "error")
                            put("error", "Cannot create download stream")
                        })
                        return
                    }
                    receivingUri = uri
                    receivingStream = stream
                    receivingStartTime = System.currentTimeMillis()
                    receivingFileTransfer = FileTransferState(id = fileId,
                        fileName = fileName, totalBytes = fileSize,
                        isOutgoing = false, status = FileTransferStatus.TRANSFERRING)
                    _fileTransferState.value = receivingFileTransfer
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot create download stream", e)
                    sendEnvelope(ws, "file.saved", null, JSONObject().apply {
                        put("fileId", fileId)
                        put("status", "error")
                        put("error", e.message ?: "Failed to create download stream")
                    })
                }
            }

            "file.ack" -> {
                val fileId = payload.optString("fileId", "")
                val bytesReceived = payload.optLong("bytesReceived", 0L)
                val coord = outgoingCoordinator
                if (fileId == activeOutgoingFileId && coord != null) {
                    coord.onAckReceived(bytesReceived)
                    val now = System.currentTimeMillis()
                    val ms = now - outgoingStartTime
                    val cur = _fileTransferState.value
                    if (cur != null && cur.id == fileId && cur.status == FileTransferStatus.TRANSFERRING) {
                        if (now - lastProgressUpdateTime >= 50 || bytesReceived >= cur.totalBytes) {
                            lastProgressUpdateTime = now
                            _fileTransferState.value = cur.copy(
                                bytesTransferred = bytesReceived,
                                speedBytesPerSec = if (ms > 0) bytesReceived * 1000.0 / ms else 0.0
                            )
                        }
                    }
                }
            }

            "file.complete" -> {
                val cur = receivingFileTransfer ?: return
                val savedUri = receivingUri
                val expectedBytes = payload.optLong("totalBytes", cur.totalBytes)

                receivingFileTransfer = null
                receivingUri = null
                activeReceivingFileIdBytes = null

                if (cur.bytesTransferred < expectedBytes) {
                    Log.e(TAG, "file.complete: incomplete transfer: ${cur.bytesTransferred}/$expectedBytes")
                    try { receivingStream?.close() } catch (_: Exception) {}
                    receivingStream = null
                    // Clear IS_PENDING so MediaStore doesn't keep an orphaned entry
                    savedUri?.let { uri ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            try {
                                context.contentResolver.update(uri,
                                    android.content.ContentValues().apply {
                                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                                    }, null, null)
                            } catch (_: Exception) {}
                            try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                        }
                    }
                    _fileTransferState.value = cur.copy(
                        status = FileTransferStatus.FAILED,
                        errorMessage = "Incomplete transfer: received ${cur.bytesTransferred}/$expectedBytes bytes"
                    )
                    sendEnvelope(ws, "file.saved", null, JSONObject().apply {
                        put("fileId", cur.id)
                        put("status", "error")
                        put("error", "Incomplete transfer")
                    })
                    scheduleTransferReset()
                    return
                }

                try {
                    receivingStream?.flush()
                    receivingStream?.close()
                    receivingStream = null
                    finishDownload(savedUri)
                    _fileTransferState.value = cur.copy(
                        bytesTransferred = cur.totalBytes,
                        status = FileTransferStatus.COMPLETED,
                        savedPath = "Downloads/LocalBridge/${cur.fileName}"
                    )
                    Log.i(TAG, "file.complete: ${cur.fileName} — sending file.saved ack")
                    sendEnvelope(ws, "file.saved", null, JSONObject().apply {
                        put("fileId", cur.id)
                        put("fileName", cur.fileName)
                        put("savedPath", "Downloads/LocalBridge/${cur.fileName}")
                        put("status", "ok")
                    })
                    scheduleTransferReset()
                } catch (e: Exception) {
                    Log.e(TAG, "Finalize error", e)
                    // Attempt to clear IS_PENDING even on failure to avoid orphaned MediaStore entries
                    savedUri?.let { uri ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            try {
                                context.contentResolver.update(uri,
                                    android.content.ContentValues().apply {
                                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                                    }, null, null)
                            } catch (_: Exception) {}
                        }
                    }
                    _fileTransferState.value = cur.copy(status = FileTransferStatus.FAILED,
                        errorMessage = e.message)
                    sendEnvelope(ws, "file.saved", null, JSONObject().apply {
                        put("fileId", cur.id)
                        put("status", "error")
                        put("error", e.message ?: "save failed")
                    })
                    scheduleTransferReset()
                }
            }

            "file.cancel" -> {
                activeReceivingFileIdBytes = null
                try { receivingStream?.close() } catch (_: Exception) {}
                receivingStream = null
                receivingUri?.let { uri ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        context.contentResolver.delete(uri, null, null)
                }
                receivingFileTransfer?.let {
                    _fileTransferState.value = it.copy(status = FileTransferStatus.CANCELLED)
                    scheduleTransferReset()
                }
                receivingFileTransfer = null; receivingUri = null
            }

            // file.saved: receiver confirmed it saved our outgoing file
            "file.saved" -> {
                val fileId = payload.optString("fileId", "")
                val status = payload.optString("status", "ok")
                if (fileId == pendingFileSavedFileId) {
                    pendingFileSavedFileId = null
                    val cont = pendingFileSavedContinuation
                    pendingFileSavedContinuation = null
                    cont?.resume(status == "ok")
                    Log.i(TAG, "file.saved ack received for $fileId (status=$status)")
                } else {
                    Log.w(TAG, "file.saved for unknown fileId=$fileId (expected=$pendingFileSavedFileId)")
                }
            }

            "session.ping" -> {
                sendEnvelope(ws, "session.pong", msgId, JSONObject())
            }
        }
    }

    // =========================================================================
    // Private — pairing flow helpers
    // =========================================================================

    private fun sendPairBegin(ws: WebSocket) {
        sendEnvelope(ws, "pair.begin", null, JSONObject().apply {
            put("deviceName",    deviceName)
            put("deviceId",      deviceId)
            put("clientVersion", "0.2.0")
        })
        Log.i(TAG, "→ pair.begin")
    }

    /** Sent on reconnect so Mac can silently restore session. */
    private fun sendReidentify(ws: WebSocket) {
        sendEnvelope(ws, "pair.reidentify", null, JSONObject().apply {
            put("deviceName",    deviceName)
            put("deviceId",      deviceId)
            put("clientVersion", "0.2.0")
        })
        Log.i(TAG, "→ pair.reidentify")
    }

    private fun acceptPairing(ws: WebSocket, serverName: String, code: String, requestId: String?) {
        sendEnvelope(ws, "pair.confirm", requestId, JSONObject().apply {
            put("deviceName",      deviceName)
            put("deviceId",        deviceId)
            put("comparisonCode",  code)
        })
        Log.i(TAG, "Accepted pairing with $serverName")
    }

    private fun rejectPairing(ws: WebSocket) {
        sendEnvelope(ws, "pair.reject", null, JSONObject().apply {
            put("reason", "USER_REJECTED")
        })
        ws.close(1000, "Pairing rejected")
        _status.value = ConnectionStatus.DISCONNECTED
        _statusMessage.value = "Pairing rejected"
        Log.i(TAG, "Rejected pairing")
    }

    // =========================================================================
    // Private — MediaStore / filesystem helpers
    // =========================================================================

    private fun createDownloadStream(fileName: String, mimeType: String): Pair<Uri?, OutputStream?> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/LocalBridge")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri    = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            val raw = uri?.let { context.contentResolver.openOutputStream(it, "w") }
            val stream = raw?.let { java.io.BufferedOutputStream(it, 2 * 1024 * 1024) }
            Pair(uri, stream)
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "LocalBridge")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            val stream = java.io.BufferedOutputStream(FileOutputStream(file), 2 * 1024 * 1024)
            Pair(Uri.fromFile(file), stream)
        }
    }

    private fun finishDownload(uri: Uri?) {
        if (uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            context.contentResolver.update(uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null)
        } catch (e: Exception) { Log.e(TAG, "IS_PENDING clear failed", e) }
    }

    private fun scheduleTransferReset() {
        scope.launch {
            delay(RESET_DELAY_MS)
            _fileTransferState.value = null
        }
    }

    // =========================================================================
    // Private — envelope builder
    // =========================================================================

    private fun sendEnvelope(ws: WebSocket, type: String, requestId: String?,
                             payload: JSONObject) {
        val env = JSONObject().apply {
            put("protocol",        "localbridge")
            put("protocolVersion", 1)
            put("type",            type)
            put("messageId",       UUID.randomUUID().toString().lowercase())
            put("requestId",       requestId ?: JSONObject.NULL)
            put("sentAt",          java.time.Instant.now().toString())
            put("senderDeviceId",  deviceId)
            put("recipientDeviceId", "mac")
            put("payload",         payload)
        }
        ws.send(env.toString())
    }

    private fun applyClipboard(text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("LocalBridge", text))
        } catch (e: Exception) { Log.w(TAG, "Clipboard write failed: ${e.message}") }
    }
}
