import Foundation
import NIO
import NIOCore
import NIOPosix
import NIOHTTP1
import NIOWebSocket

private final class OutgoingFileCoordinator: @unchecked Sendable {
    private let lock = NSLock()
    private var inFlight: Int = 0
    private let maxInFlight: Int
    private var pendingContinuations: [CheckedContinuation<Void, Never>] = []
    private(set) var isCancelled: Bool = false
    private(set) var totalBytesAcked: Int64 = 0

    init(maxInFlight: Int = 32) {
        self.maxInFlight = maxInFlight
    }

    func waitToSend() async {
        await withCheckedContinuation { cont in
            lock.lock()
            if isCancelled {
                lock.unlock()
                cont.resume()
                return
            }
            if inFlight < maxInFlight {
                inFlight += 1
                lock.unlock()
                cont.resume()
            } else {
                pendingContinuations.append(cont)
                lock.unlock()
            }
        }
    }

    func onAckReceived(bytesAcked: Int64) {
        lock.lock()
        defer { lock.unlock() }
        totalBytesAcked = max(totalBytesAcked, bytesAcked)
        if inFlight > 0 {
            inFlight -= 1
        }
        if !pendingContinuations.isEmpty && !isCancelled {
            let next = pendingContinuations.removeFirst()
            inFlight += 1
            next.resume()
        }
    }

    func cancel() {
        lock.lock()
        defer { lock.unlock() }
        isCancelled = true
        inFlight = 0
        while !pendingContinuations.isEmpty {
            pendingContinuations.removeFirst().resume()
        }
    }
}

/// SwiftNIO-based WebSocket server on port 38473.
/// Listens for incoming connections from the Android app, handles protocol envelopes,
/// pairing handshake, bidirectional clipboard sync, and high-speed binary chunked file transfer.
final class LocalBridgeServer: @unchecked Sendable {

    static let defaultPort: Int = 38473
    /// 512 KB chunks — optimal for wire-speed network throughput and high-performance streaming
    private let chunkSize = 512 * 1024
    /// Delay (seconds) before clearing completed/failed transfer state
    private let transferResetDelay: UInt64 = 2_500_000_000
    /// Throttles UI progress updates to at most once per 50 ms
    private var lastProgressReportTime: Date = .distantPast

    private var group: MultiThreadedEventLoopGroup?
    private var channel: Channel?
    fileprivate let logger = RedactedLogger(subsystem: "dev.localbridge.mac", category: "Server")

    var onClientConnected: ((String) -> Void)?
    var onClientDisconnected: (() -> Void)?
    var onPairingRequested: ((String, String) -> Void)? // peerName, comparisonCode
    var onPairingConfirmed: ((String) -> Void)?         // peerName
    var onPairingRejected: ((String) -> Void)?          // peerName
    var onUnpaired: (() -> Void)?
    var onClipboardReceived: ((String, String, Int) -> Void)? // content, mimeType, bytes

    // File transfer callbacks
    var onFileTransferProgress: ((FileTransferState) -> Void)?
    var onFileTransferCompleted: ((FileTransferState) -> Void)?
    var onFileTransferFailed: ((FileTransferState) -> Void)?

    func reportProgressThrottled(_ state: FileTransferState, force: Bool = false) {
        let now = Date()
        if force || now.timeIntervalSince(lastProgressReportTime) >= 0.05 {
            lastProgressReportTime = now
            onFileTransferProgress?(state)
        }
    }

    /// Name of the currently paired Android device (persists across reconnects).
    public var pairedPeerName: String? = nil
    /// Stable device-id sent by the Android app — used to recognise reconnects.
    /// Exposed as public(set) so MenuBarController can persist it in UserDefaults.
    public private(set) var pairedDeviceId: String? = nil

    // Holds the channel and peer info while user decides to accept/reject
    private var pendingPairChannel: Channel? = nil
    private var pendingPairPeerName: String? = nil
    private var pendingPairDeviceId: String? = nil
    private var pendingPairMessageId: String? = nil

    // Active incoming file transfer
    private var incomingFileTransfer: FileTransferState?
    private var incomingFileHandle: FileHandle?
    private var incomingTempUrl: URL?
    private var incomingStartTime: Date?

    // Active outgoing file transfer
    private var isOutgoingCancelled: Bool = false
    private var outgoingFileTransfer: FileTransferState?
    private var outgoingCoordinator: OutgoingFileCoordinator?
    private var outgoingStartTime: Date?

    // Continuation waiting for file.saved ack from receiver
    private var pendingFileSavedFileId: String? = nil
    private var pendingFileSavedContinuation: CheckedContinuation<Bool, Never>? = nil

    private var activeChannel: Channel?
    private(set) var isRunning: Bool = false

    func start(port: Int = defaultPort) throws {
        guard !isRunning else { return }

        let eventLoopGroup = MultiThreadedEventLoopGroup(numberOfThreads: 1)
        self.group = eventLoopGroup

        let bootstrap = ServerBootstrap(group: eventLoopGroup)
            .serverChannelOption(ChannelOptions.backlog, value: 256)
            .serverChannelOption(ChannelOptions.socketOption(.so_reuseaddr), value: 1)
            .childChannelOption(ChannelOptions.socketOption(.tcp_nodelay), value: 1)
            .childChannelOption(ChannelOptions.socketOption(.so_rcvbuf), value: 2 * 1024 * 1024)
            .childChannelOption(ChannelOptions.socketOption(.so_sndbuf), value: 2 * 1024 * 1024)
            .childChannelInitializer { [weak self] channel in
                channel.pipeline.configureHTTPServerPipeline(withServerUpgrade: (
                    upgraders: [
                        NIOWebSocketServerUpgrader(
                            maxFrameSize: 4 * 1024 * 1024,
                            shouldUpgrade: { channel, _ in
                                channel.eventLoop.makeSucceededFuture(HTTPHeaders())
                            },
                            upgradePipelineHandler: { [weak self] channel, _ in
                                let handler = WebSocketServerHandler(server: self)
                                return channel.pipeline.addHandler(handler)
                            }
                        )
                    ],
                    completionHandler: { _ in }
                ))
            }

        let boundChannel = try bootstrap.bind(host: "0.0.0.0", port: port).wait()
        self.channel = boundChannel
        self.isRunning = true
        logger.info("LocalBridgeServer listening on port \(port)")
    }

    func stop() {
        try? channel?.close().wait()
        try? group?.syncShutdownGracefully()
        channel = nil
        group = nil
        isRunning = false
        logger.info("LocalBridgeServer stopped")
    }

    /// Called when the Mac user clicks PAIR in Dashboard on Mac.
    func initiatePairing(with peerName: String) -> String? {
        guard let channel = activeChannel else {
            logger.info("initiatePairing: no active channel to send pair request")
            return nil
        }
        let code = String(format: "%06d", Int.random(in: 100000...999999))
        let messageId = UUID().uuidString.lowercased()
        pendingPairChannel = channel
        pendingPairPeerName = peerName
        pendingPairMessageId = messageId

        let challengePayload: [String: Any] = [
            "comparisonCode": code,
            "serverName": Host.current().localizedName ?? "Kundan’s MacBook Air",
            "nonce": UUID().uuidString.lowercased()
        ]
        sendEnvelope(type: "pair.challenge", requestId: messageId, payload: challengePayload, on: channel)
        logger.info("Mac initiated pairing with \(peerName), sent challenge code: \(code)")
        return code
    }

    /// Called by MenuBarController when the Mac user clicks Accept on a pending pair request.
    func sendPairAccepted() {
        guard let channel = pendingPairChannel,
              let peerName = pendingPairPeerName else {
            logger.info("sendPairAccepted: no pending pair channel")
            return
        }
        let messageId = pendingPairMessageId
        self.pairedPeerName = peerName
        if let did = pendingPairDeviceId {
            self.pairedDeviceId = did
        }
        let resultPayload: [String: Any] = [
            "success": true,
            "pairedDeviceId": UUID().uuidString.lowercased(),
            "pairedDeviceName": Host.current().localizedName ?? "Mac"
        ]
        sendEnvelope(type: "pair.result", requestId: messageId, payload: resultPayload, on: channel)
        logger.info("Sent pair.result(success=true) to \(peerName) (pairedDeviceId=\(self.pairedDeviceId ?? "nil"))")
        onPairingConfirmed?(peerName)
        pendingPairChannel = nil
        pendingPairPeerName = nil
        pendingPairDeviceId = nil
        pendingPairMessageId = nil
    }

    /// Called by MenuBarController when the Mac user clicks Reject.
    func sendPairRejected() {
        guard let channel = pendingPairChannel else { return }
        let resultPayload: [String: Any] = [
            "success": false,
            "reason": "MAC_USER_REJECTED"
        ]
        sendEnvelope(type: "pair.result", requestId: pendingPairMessageId, payload: resultPayload, on: channel)
        logger.info("Sent pair.result(success=false) — user rejected")
        pendingPairChannel = nil
        pendingPairPeerName = nil
        pendingPairDeviceId = nil
        pendingPairMessageId = nil
    }

    /// Unpairs the current device — clears both name and device-id.
    func unpair() {
        if let channel = activeChannel {
            sendEnvelope(type: "pair.unpair", requestId: nil, payload: [:], on: channel)
        }
        pairedPeerName       = nil
        pairedDeviceId       = nil
        pendingPairChannel   = nil
        pendingPairPeerName  = nil
        pendingPairDeviceId  = nil
        pendingPairMessageId = nil
        onUnpaired?()
        logger.info("Device unpaired — pairedDeviceId cleared")
    }

    /// Call this right after `start()` to restore a previously-persisted pairing
    /// so reconnecting phones are recognised without showing a new pairing dialog.
    func restorePairedState(peerName: String, deviceId: String? = nil) {
        pairedPeerName = peerName
        pairedDeviceId = deviceId
        logger.info("Pairing state restored: \(peerName) / \(deviceId ?? "nil")")
    }

    /// Mac pushes clipboard to phone. Strictly requires pairing.
    func sendClipboardPush(content: String, mimeType: String = "text/plain") {
        guard let channel = activeChannel else {
            logger.warning("Cannot send clipboard.push: no active WebSocket connection")
            return
        }
        guard pairedPeerName != nil else {
            logger.warning("Cannot send clipboard.push: device is not paired")
            return
        }

        let payload: [String: Any] = [
            "eventId": UUID().uuidString.lowercased(),
            "originDeviceId": Host.current().localizedName ?? "Mac",
            "originSequence": 1,
            "capturedAt": ISO8601DateFormatter().string(from: Date()),
            "mimeType": mimeType,
            "byteLength": content.utf8.count,
            "content": content
        ]
        sendEnvelope(type: "clipboard.push", requestId: nil, payload: payload, on: channel)
        logger.info("Sent clipboard.push (\(content.utf8.count) bytes) to phone")
    }

    // MARK: - High-Speed File Transfer

    /// Streams a file to the paired phone using 64 KB binary chunks with flow control and receiver acks.
    func sendFile(url: URL) async throws {
        guard let channel = activeChannel, pairedPeerName != nil else {
            throw NSError(domain: "LocalBridge", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "Not paired with device"])
        }

        let fileId = UUID().uuidString.lowercased()
        let fileName = url.lastPathComponent
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        let fileSize = (attributes[.size] as? NSNumber)?.int64Value ?? 0

        var state = FileTransferState(id: fileId, fileName: fileName, totalBytes: fileSize, isOutgoing: true)
        self.outgoingFileTransfer = state
        self.isOutgoingCancelled = false
        self.outgoingStartTime = Date()
        let coordinator = OutgoingFileCoordinator(maxInFlight: 32)
        self.outgoingCoordinator = coordinator
        reportProgressThrottled(state, force: true)

        // 1 — file.start
        let startPayload: [String: Any] = [
            "fileId": fileId,
            "fileName": fileName,
            "fileSize": fileSize,
            "chunkSize": chunkSize
        ]
        sendEnvelope(type: "file.start", requestId: nil, payload: startPayload, on: channel)

        // 2 — stream binary frames with sliding-window flow control
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }

        let fileIdBytes = [UInt8](fileId.utf8)
        var chunkIndex: UInt32 = 0
        var bytesReadTotal: Int64 = 0

        while bytesReadTotal < fileSize && !isOutgoingCancelled && !coordinator.isCancelled {
            await coordinator.waitToSend()
            if isOutgoingCancelled || coordinator.isCancelled { break }

            let chunkData = handle.readData(ofLength: chunkSize)
            if chunkData.isEmpty { break }

            var buffer = channel.allocator.buffer(capacity: 40 + chunkData.count)
            buffer.writeBytes(fileIdBytes)                      // 36-byte ASCII UUID
            buffer.writeInteger(chunkIndex, as: UInt32.self)    // 4-byte chunk index
            buffer.writeBytes(chunkData)

            let frame = WebSocketFrame(fin: true, opcode: .binary, data: buffer)
            channel.write(frame, promise: nil)

            bytesReadTotal += Int64(chunkData.count)
            chunkIndex += 1

            if chunkIndex % 4 == 0 || !channel.isWritable {
                channel.flush()
            }

            if !channel.isWritable {
                await Task.yield()
            }
        }
        channel.flush()

        if isOutgoingCancelled || coordinator.isCancelled {
            sendEnvelope(type: "file.cancel", requestId: nil, payload: ["fileId": fileId], on: channel)
            state.status = .cancelled
            self.outgoingFileTransfer = nil
            self.outgoingCoordinator = nil
            onFileTransferFailed?(state)
            scheduleTransferReset()
            return
        }

        // Wait until all in-flight chunks are acknowledged by receiver
        let ackWaitStart = Date()
        while coordinator.totalBytesAcked < fileSize && !isOutgoingCancelled && !coordinator.isCancelled {
            if Date().timeIntervalSince(ackWaitStart) > 60.0 {
                logger.error("Timed out waiting for chunk acknowledgments from receiver")
                break
            }
            try? await Task.sleep(nanoseconds: 2_000_000) // 2 ms check
        }

        if isOutgoingCancelled || coordinator.isCancelled || coordinator.totalBytesAcked < fileSize {
            state.status = .failed
            state.errorMessage = "Failed to transmit all chunks: acknowledged \(coordinator.totalBytesAcked)/\(fileSize)"
            self.outgoingFileTransfer = nil
            self.outgoingCoordinator = nil
            onFileTransferFailed?(state)
            scheduleTransferReset()
            return
        }

        // 3 — file.complete: inform receiver all chunks have been received and verified
        sendEnvelope(type: "file.complete", requestId: nil, payload: [
            "fileId": fileId, "fileName": fileName, "totalBytes": fileSize
        ], on: channel)

        state.status = .transferring
        state.bytesTransferred = fileSize
        self.outgoingFileTransfer = state
        onFileTransferProgress?(state)
        logger.info("All chunks acked for \(fileName) (\(fileSize) B) — waiting for file.saved ack")

        // 4 — await file.saved acknowledgment from the receiver (with 30-second timeout)
        let savedOk: Bool = await withTaskGroup(of: Bool.self) { group in
            group.addTask {
                await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
                    self.pendingFileSavedFileId = fileId
                    self.pendingFileSavedContinuation = continuation
                }
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: 30_000_000_000) // 30 s timeout
                guard !Task.isCancelled else { return false }
                if self.pendingFileSavedFileId == fileId {
                    self.pendingFileSavedFileId = nil
                    let cont = self.pendingFileSavedContinuation
                    self.pendingFileSavedContinuation = nil
                    cont?.resume(returning: false)
                }
                return false
            }
            let result = await group.next() ?? false
            group.cancelAll()
            return result
        }

        state.status = savedOk ? .completed : .failed
        state.bytesTransferred = fileSize
        self.outgoingFileTransfer = nil
        self.outgoingCoordinator = nil
        if savedOk {
            onFileTransferCompleted?(state)
            logger.info("Sent \(fileName) (\(fileSize) bytes) — receiver confirmed save")
        } else {
            state.errorMessage = "Receiver did not confirm file save"
            onFileTransferFailed?(state)
            logger.warning("file.saved ack not received for \(fileName)")
        }
        scheduleTransferReset()
    }

    /// Placeholder — the actual UI reset is driven by MenuBarController's
    /// onFileTransferCompleted / onFileTransferFailed closures, which schedule
    /// their own 2.5-second auto-clear. This method exists so callers don't
    /// need to be changed if server-side reset logic is added in future.
    private func scheduleTransferReset() {
        // No-op at server level; MenuBarController handles the auto-clear timer.
    }

    func cancelFileTransfer() {
        isOutgoingCancelled = true
        outgoingCoordinator?.cancel()
        outgoingCoordinator = nil
        if let outgoing = outgoingFileTransfer, let channel = activeChannel {
            sendEnvelope(type: "file.cancel", requestId: nil, payload: ["fileId": outgoing.id], on: channel)
        }
        // Resume any pending continuation so the coroutine is not leaked
        pendingFileSavedContinuation?.resume(returning: false)
        pendingFileSavedContinuation = nil
        pendingFileSavedFileId = nil
        outgoingFileTransfer = nil
    }

    // MARK: - Internal Channel Management

    fileprivate func channelConnected(_ channel: Channel) {
        self.activeChannel = channel
        let peer = channel.remoteAddress?.description ?? "unknown"
        logger.info("Client connected from \(peer)")
        onClientConnected?(peer)
    }

    fileprivate func channelDisconnected() {
        logger.info("Client disconnected (pairedPeerName=\(pairedPeerName ?? "nil"))")
        self.activeChannel = nil
        outgoingCoordinator?.cancel()
        outgoingCoordinator = nil
        if var outgoing = outgoingFileTransfer {
            outgoing.status = .failed
            outgoing.errorMessage = "Connection closed"
            onFileTransferFailed?(outgoing)
            outgoingFileTransfer = nil
        }
        // Clean up any in-progress incoming transfer
        try? incomingFileHandle?.close()
        incomingFileHandle = nil
        if let tempUrl = incomingTempUrl {
            try? FileManager.default.removeItem(at: tempUrl)
        }
        if var incoming = incomingFileTransfer {
            incoming.status = .failed
            incoming.errorMessage = "Connection closed"
            onFileTransferFailed?(incoming)
            incomingFileTransfer = nil
        }
        incomingTempUrl = nil
        incomingStartTime = nil
        // Clean up any in-progress outgoing transfer wait
        pendingFileSavedContinuation?.resume(returning: false)
        pendingFileSavedContinuation = nil
        pendingFileSavedFileId = nil
        // NOTE: do NOT clear pairedPeerName/pairedDeviceId here —
        // the phone may reconnect and we should restore the session silently.
        onClientDisconnected?()
    }

    // MARK: - Incoming Binary & Text Envelopes

    private let diskIOQueue = DispatchQueue(label: "dev.localbridge.mac.diskio", qos: .userInitiated)

    fileprivate func handleIncomingBinary(_ buffer: ByteBuffer, on channel: Channel) {
        var buf = buffer
        guard buf.readableBytes >= 40 else { return }
        guard let fileId = buf.readString(length: 36) else { return }
        guard let chunkIndex = buf.readInteger(as: UInt32.self) else { return }
        guard var incoming = incomingFileTransfer, incoming.id == fileId, let handle = incomingFileHandle else { return }

        guard let bytes = buf.readBytes(length: buf.readableBytes) else { return }
        let data = Data(bytes)
        let transferredSoFar = incoming.bytesTransferred + Int64(data.count)
        let startTime = incomingStartTime

        // Update in-memory state on the event loop for immediate ACK
        incoming.bytesTransferred = transferredSoFar
        let now = Date()
        let elapsed = now.timeIntervalSince(startTime ?? now)
        if elapsed > 0 {
            incoming.speedBytesPerSec = Double(transferredSoFar) / elapsed
        }
        self.incomingFileTransfer = incoming
        reportProgressThrottled(incoming)

        // Send ACK immediately on the event loop (before disk I/O)
        let ackJson = "{\"protocol\":\"localbridge\",\"protocolVersion\":1,\"type\":\"file.ack\",\"payload\":{\"fileId\":\"\(fileId)\",\"chunkIndex\":\(chunkIndex),\"bytesReceived\":\(transferredSoFar)}}"
        var ackBuf = channel.allocator.buffer(capacity: ackJson.utf8.count)
        ackBuf.writeString(ackJson)
        let ackFrame = WebSocketFrame(fin: true, opcode: .text, data: ackBuf)
        channel.writeAndFlush(ackFrame, promise: nil)

        // Write to disk on background queue — never block the event loop
        diskIOQueue.async {
            try? handle.write(contentsOf: data)
        }
    }

    fileprivate func handleIncomingText(_ text: String, on channel: Channel) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else {
            logger.warning("Received invalid or non-JSON envelope")
            return
        }

        let payload = json["payload"] as? [String: Any] ?? [:]
        let messageId = json["messageId"] as? String ?? UUID().uuidString.lowercased()
        let now = ISO8601DateFormatter().string(from: Date())
        logger.info("Handling envelope type: \(type)")

        switch type {
        case "pair.begin", "pair.request":
            let peerName = payload["deviceName"] as? String ?? payload["serverName"] as? String ?? "Android Device"
            let deviceId = payload["deviceId"] as? String

            // If reconnecting from the already-paired device (by deviceId or name), restore session silently!
            if let paired = pairedPeerName {
                let matchesId = (pairedDeviceId != nil && deviceId != nil && pairedDeviceId == deviceId)
                let matchesName = (paired == peerName)
                if matchesId || matchesName || pairedDeviceId == nil {
                    if let did = deviceId { self.pairedDeviceId = did }
                    self.pairedPeerName = peerName
                    self.activeChannel = channel
                    logger.info("Re-identified paired device \(peerName) via pair.begin — restoring session silently")
                    let restorePayload: [String: Any] = [
                        "serverName": Host.current().localizedName ?? "Mac",
                        "pairedDeviceName": Host.current().localizedName ?? "Mac"
                    ]
                    sendEnvelope(type: "pair.session.restored", requestId: messageId, payload: restorePayload, on: channel)
                    onPairingConfirmed?(peerName)
                    return
                }
            }

            // 1-to-1 pairing rule: reject if already paired with a different device
            if let existing = pairedPeerName, existing != peerName {
                logger.warning("Rejected pair request from \(peerName): already paired with \(existing)")
                let rejectPayload: [String: Any] = ["success": false, "reason": "ALREADY_PAIRED"]
                sendEnvelope(type: "pair.result", requestId: messageId, payload: rejectPayload, on: channel)
                return
            }

            let code = String(format: "%06d", Int.random(in: 100000...999999))
            logger.info("Pair request from \(peerName), awaiting user decision")

            pendingPairChannel = channel
            pendingPairPeerName = peerName
            pendingPairDeviceId = deviceId
            pendingPairMessageId = messageId

            onPairingRequested?(peerName, code)

        case "pair.reidentify":
            // Phone reconnected after a disconnect and is re-identifying itself.
            let peerName = payload["deviceName"] as? String ?? pairedPeerName ?? "Android Device"
            let deviceId = payload["deviceId"] as? String
            if let paired = pairedPeerName {
                let matchesId = (pairedDeviceId != nil && deviceId != nil && pairedDeviceId == deviceId)
                let matchesName = (paired == peerName)
                if matchesId || matchesName || pairedDeviceId == nil {
                    if let did = deviceId { self.pairedDeviceId = did }
                    self.pairedPeerName = peerName
                    self.activeChannel = channel
                    logger.info("Reidentify: session restored for \(peerName) (deviceId=\(deviceId ?? "nil"))")
                    let restorePayload: [String: Any] = [
                        "serverName": Host.current().localizedName ?? "Mac",
                        "pairedDeviceName": Host.current().localizedName ?? "Mac"
                    ]
                    sendEnvelope(type: "pair.session.restored", requestId: messageId, payload: restorePayload, on: channel)
                    onPairingConfirmed?(peerName)
                    return
                }
            }

            // 1-to-1 pairing rule: reject if already paired with a different device
            if let existing = pairedPeerName, existing != peerName {
                logger.warning("Rejected reidentify from \(peerName): already paired with \(existing)")
                let rejectPayload: [String: Any] = ["success": false, "reason": "ALREADY_PAIRED"]
                sendEnvelope(type: "pair.result", requestId: messageId, payload: rejectPayload, on: channel)
                return
            }

            // Unknown device and Mac is unpaired — treat as fresh pair.begin
            logger.info("Reidentify from new device \(peerName) while unpaired — treating as fresh pair.begin")
            let code = String(format: "%06d", Int.random(in: 100000...999999))
            pendingPairChannel = channel
            pendingPairPeerName = peerName
            pendingPairDeviceId = deviceId
            pendingPairMessageId = messageId
            onPairingRequested?(peerName, code)

        case "pair.confirm", "pair.accept":
            let peerName = payload["deviceName"] as? String ?? pendingPairPeerName ?? "Android Device"
            let deviceId = payload["deviceId"] as? String ?? pendingPairDeviceId ?? UUID().uuidString.lowercased()
            logger.info("Pair confirmed by \(peerName)")
            self.pairedPeerName = peerName
            self.pairedDeviceId = deviceId   // persist for reconnect recognition
            let resultPayload: [String: Any] = [
                "success": true,
                "pairedDeviceId": UUID().uuidString.lowercased(),
                "pairedDeviceName": Host.current().localizedName ?? "Mac"
            ]
            sendEnvelope(type: "pair.result", requestId: messageId, payload: resultPayload, on: channel)
            pendingPairChannel = nil
            pendingPairPeerName = nil
            pendingPairDeviceId = nil
            pendingPairMessageId = nil
            onPairingConfirmed?(peerName)

        case "pair.reject":
            let peerName = pendingPairPeerName ?? "Android Device"
            logger.info("Pair rejected by \(peerName)")
            pendingPairChannel = nil
            pendingPairPeerName = nil
            pendingPairMessageId = nil
            onPairingRejected?(peerName)

        case "pair.unpair":
            logger.info("Received pair.unpair from peer — clearing pairing state")
            self.pairedPeerName = nil
            self.pairedDeviceId = nil
            self.pendingPairChannel = nil
            self.pendingPairPeerName = nil
            self.pendingPairMessageId = nil
            onUnpaired?()

        case "session.ping":
            sendEnvelope(type: "session.pong", requestId: messageId, payload: [:], on: channel)

        case "clipboard.push":
            guard pairedPeerName != nil else {
                logger.warning("Ignored clipboard.push: not paired with any device")
                return
            }
            if let content = payload["content"] as? String,
               let mimeType = payload["mimeType"] as? String {
                let bytes = payload["byteLength"] as? Int ?? content.utf8.count
                logger.info("Received clipboard.push from phone: \(bytes) bytes")
                onClipboardReceived?(content, mimeType, bytes)

                let ackPayload: [String: Any] = [
                    "eventId": payload["eventId"] as? String ?? UUID().uuidString.lowercased(),
                    "status": "APPLIED",
                    "appliedAt": now,
                    "reasonCode": NSNull()
                ]
                sendEnvelope(type: "clipboard.ack", requestId: messageId, payload: ackPayload, on: channel)
            }

        // MARK: Incoming File Events
        case "file.start":
            guard pairedPeerName != nil else { return }
            let fileId = payload["fileId"] as? String ?? UUID().uuidString.lowercased()
            // Sanitize fileName: strip directory traversal — only use last path component
            let rawFileName = payload["fileName"] as? String ?? "received_file"
            let fileName = URL(fileURLWithPath: rawFileName).lastPathComponent
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "..", with: "_")
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .isEmpty ? "received_file" : URL(fileURLWithPath: rawFileName).lastPathComponent
            let fileSize = (payload["fileSize"] as? NSNumber)?.int64Value ?? 0
            logger.info("Incoming file start: \(fileName) (\(fileSize) bytes)")

            try? incomingFileHandle?.close()
            incomingFileHandle = nil
            if let oldTemp = incomingTempUrl {
                try? FileManager.default.removeItem(at: oldTemp)
            }

            let tempUrl = FileManager.default.temporaryDirectory.appendingPathComponent("\(fileId).part")
            FileManager.default.createFile(atPath: tempUrl.path, contents: nil)
            do {
                let handle = try FileHandle(forWritingTo: tempUrl)
                self.incomingFileHandle = handle
                self.incomingTempUrl = tempUrl
                self.incomingStartTime = Date()
                let state = FileTransferState(id: fileId, fileName: fileName, totalBytes: fileSize, isOutgoing: false)
                self.incomingFileTransfer = state
                onFileTransferProgress?(state)
            } catch {
                logger.error("Failed to create file handle for incoming file: \(error)")
                sendEnvelope(type: "file.saved", requestId: nil, payload: [
                    "fileId": fileId,
                    "status": "error",
                    "error": error.localizedDescription
                ], on: channel)
            }

        case "file.ack":
            let fileId = payload["fileId"] as? String ?? ""
            let bytesReceived = (payload["bytesReceived"] as? NSNumber)?.int64Value ?? 0
            if var outgoing = outgoingFileTransfer, outgoing.id == fileId, let coord = outgoingCoordinator {
                coord.onAckReceived(bytesAcked: bytesReceived)
                outgoing.bytesTransferred = bytesReceived
                let now = Date()
                let elapsed = now.timeIntervalSince(outgoingStartTime ?? now)
                if elapsed > 0 {
                    outgoing.speedBytesPerSec = Double(bytesReceived) / elapsed
                }
                self.outgoingFileTransfer = outgoing
                reportProgressThrottled(outgoing)
            }

        case "file.complete":
            guard var incoming = incomingFileTransfer, let tempUrl = incomingTempUrl else { return }
            diskIOQueue.sync {
                try? self.incomingFileHandle?.close()
                self.incomingFileHandle = nil
            }

            let expectedBytes = (payload["totalBytes"] as? NSNumber)?.int64Value ?? incoming.totalBytes
            if incoming.bytesTransferred < expectedBytes {
                logger.error("file.complete arrived with incomplete bytes: \(incoming.bytesTransferred)/\(expectedBytes)")
                try? FileManager.default.removeItem(at: tempUrl)
                incoming.status = .failed
                incoming.errorMessage = "Incomplete transfer: received \(incoming.bytesTransferred)/\(expectedBytes) bytes"
                onFileTransferFailed?(incoming)
                sendEnvelope(type: "file.saved", requestId: nil, payload: [
                    "fileId": incoming.id,
                    "status": "error",
                    "error": incoming.errorMessage ?? "Incomplete transfer"
                ], on: channel)
                self.incomingFileTransfer = nil
                self.incomingTempUrl = nil
                self.incomingStartTime = nil
                scheduleTransferReset()
                return
            }

            let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first ?? URL(fileURLWithPath: NSHomeDirectory() + "/Downloads")
            var destUrl = downloads.appendingPathComponent(incoming.fileName)
            let baseName = destUrl.deletingPathExtension().lastPathComponent
            let ext = destUrl.pathExtension
            var counter = 1
            while FileManager.default.fileExists(atPath: destUrl.path) {
                let newName = ext.isEmpty ? "\(baseName) (\(counter))" : "\(baseName) (\(counter)).\(ext)"
                destUrl = downloads.appendingPathComponent(newName)
                counter += 1
            }

            do {
                try FileManager.default.moveItem(at: tempUrl, to: destUrl)
                incoming.status = .completed
                incoming.bytesTransferred = incoming.totalBytes
                incoming.savedPath = destUrl.path
                logger.info("Saved incoming file to: \(destUrl.path)")
                reportProgressThrottled(incoming, force: true)
                onFileTransferCompleted?(incoming)
                // Send file.saved ack so the sender knows we're done
                sendEnvelope(type: "file.saved", requestId: nil, payload: [
                    "fileId": incoming.id,
                    "fileName": incoming.fileName,
                    "savedPath": destUrl.path,
                    "status": "ok"
                ], on: channel)
            } catch {
                logger.error("Failed to move file to Downloads: \(error)")
                incoming.status = .failed
                incoming.errorMessage = error.localizedDescription
                onFileTransferFailed?(incoming)
                // Send failure ack so sender does not wait forever
                sendEnvelope(type: "file.saved", requestId: nil, payload: [
                    "fileId": incoming.id,
                    "status": "error",
                    "error": error.localizedDescription
                ], on: channel)
            }
            self.incomingFileTransfer = nil
            self.incomingTempUrl = nil
            self.incomingStartTime = nil
            scheduleTransferReset()

        case "file.cancel":
            try? incomingFileHandle?.close()
            incomingFileHandle = nil
            if let tempUrl = incomingTempUrl {
                try? FileManager.default.removeItem(at: tempUrl)
            }
            if var incoming = incomingFileTransfer {
                incoming.status = .cancelled
                onFileTransferFailed?(incoming)
            }
            self.incomingFileTransfer = nil
            self.incomingTempUrl = nil
            self.incomingStartTime = nil
            scheduleTransferReset()

        case "file.saved":
            // Android confirmed it saved the file we sent — complete our outgoing transfer
            let fileId = payload["fileId"] as? String ?? ""
            let status = payload["status"] as? String ?? "ok"
            if fileId == pendingFileSavedFileId {
                pendingFileSavedFileId = nil
                let cont = pendingFileSavedContinuation
                pendingFileSavedContinuation = nil
                cont?.resume(returning: status == "ok")
                logger.info("file.saved ack received for \(fileId) (status=\(status))")
            } else {
                logger.warning("file.saved for unknown fileId=\(fileId) (expected=\(pendingFileSavedFileId ?? "nil"))")
            }

        default:
            logger.info("Unhandled protocol message: \(type)")
        }
    }

    private func sendEnvelope(type: String, requestId: String?, payload: [String: Any], on channel: Channel) {
        let now = ISO8601DateFormatter().string(from: Date())
        let envelope: [String: Any] = [
            "protocol": "localbridge",
            "protocolVersion": 1,
            "type": type,
            "messageId": UUID().uuidString.lowercased(),
            "requestId": requestId ?? NSNull(),
            "sentAt": now,
            "senderDeviceId": Host.current().localizedName ?? "Mac",
            "recipientDeviceId": "android",
            "payload": payload
        ]

        if let data = try? JSONSerialization.data(withJSONObject: envelope),
           let jsonString = String(data: data, encoding: .utf8) {
            var buffer = channel.allocator.buffer(capacity: jsonString.utf8.count)
            buffer.writeString(jsonString)
            let frame = WebSocketFrame(fin: true, opcode: .text, data: buffer)
            channel.writeAndFlush(frame).whenFailure { [weak self] error in
                self?.logger.error("Failed to send \(type): \(error)")
            }
        }
    }
}

private final class WebSocketServerHandler: ChannelInboundHandler, @unchecked Sendable {
    typealias InboundIn = WebSocketFrame
    typealias OutboundOut = WebSocketFrame

    private weak var server: LocalBridgeServer?

    init(server: LocalBridgeServer?) {
        self.server = server
    }

    func handlerAdded(context: ChannelHandlerContext) {
        // channelActive will fire next — do not call channelConnected here to avoid duplicate callbacks
    }

    func channelActive(context: ChannelHandlerContext) {
        server?.channelConnected(context.channel)
    }

    func channelInactive(context: ChannelHandlerContext) {
        server?.channelDisconnected()
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let frame = unwrapInboundIn(data)
        switch frame.opcode {
        case .text:
            var buffer = frame.unmaskedData
            if let string = buffer.readString(length: buffer.readableBytes) {
                server?.handleIncomingText(string, on: context.channel)
            } else {
                server?.logger.error("Failed to readString from frame buffer")
            }
        case .binary:
            server?.handleIncomingBinary(frame.unmaskedData, on: context.channel)
        case .ping:
            let data = frame.unmaskedData
            let pong = WebSocketFrame(fin: true, opcode: .pong, data: data)
            context.writeAndFlush(wrapOutboundOut(pong), promise: nil)
        case .connectionClose:
            context.close(promise: nil)
        default:
            break
        }
    }
}
