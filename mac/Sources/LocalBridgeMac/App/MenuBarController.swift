import AppKit
import Combine
import SwiftUI

/// Manages the NSStatusItem (menu-bar icon), its dropdown menu, and the
/// Dashboard window.
@MainActor
final class MenuBarController: NSObject, ObservableObject {

    private var statusItem: NSStatusItem?
    private var cancellables = Set<AnyCancellable>()
    private let logger = RedactedLogger(subsystem: "dev.localbridge.mac", category: "MenuBar")

    private var dashboardWindow: NSWindow?
    private var sendTextPanel: NSPanel?
    private var fileDropWindowController: FileDropWindowController?
    private var bonjourAdvertiser: BonjourAdvertiser?
    private var serviceBrowser: ServiceBrowser?
    private var localBridgeServer: LocalBridgeServer?

    // ── UserDefaults keys for persistent pairing ──
    private let defaults = UserDefaults.standard
    private let kPairedName     = "lb_paired_name"
    private let kPairedDeviceId = "lb_paired_device_id"

    // Observable state — drives both the menu label and the dashboard view
    @Published private(set) var isMasterEnabled: Bool = true
    @Published private(set) var isConnected: Bool = false
    @Published private(set) var pairedPeerName: String? = nil
    @Published private(set) var discoveredPeers: [DiscoveredPeer] = []
    @Published private(set) var lastSentBytes: Int = 0
    @Published private(set) var lastSentMime: String = "—"
    @Published private(set) var eventCount: Int = 0
    @Published var pendingPairingPeer: String? = nil
    @Published var pendingPairingCode: String? = nil
    @Published var activeTransfer: FileTransferState? = nil
    @Published var typedText: String = ""
    @Published var isDashboardDropTargeted: Bool = false

    var isPaired: Bool {
        return pairedPeerName != nil
    }

    override init() {
        super.init()
        // Restore persisted pairing so the UI immediately shows the paired badge
        pairedPeerName = defaults.string(forKey: kPairedName)
        setupStatusItem()
        setupServer()
        setupDiscovery()
    }

    func tearDown() {
        bonjourAdvertiser?.stopAdvertising()
        serviceBrowser?.stopBrowsing()
        localBridgeServer?.stop()
        statusItem = nil
        dashboardWindow?.close()
        sendTextPanel?.close()
    }

    func setMasterEnabled(_ enabled: Bool) {
        guard isMasterEnabled != enabled else { return }
        isMasterEnabled = enabled
        if !enabled {
            bonjourAdvertiser?.stopAdvertising()
            bonjourAdvertiser = nil
            serviceBrowser?.stopBrowsing()
            serviceBrowser = nil
            localBridgeServer?.stop()
            localBridgeServer = nil
            isConnected = false
            // NOTE: do NOT clear pairedPeerName here — pairing persists across toggles.
            // The user must explicitly tap Unpair.
            discoveredPeers = []
            pendingPairingPeer = nil
            pendingPairingCode = nil
            logger.info("LocalBridge paused (silent mode)")
        } else {
            setupServer()
            setupDiscovery()
            logger.info("LocalBridge resumed")
        }
        updateStatusItemAppearance()
    }

    @objc func toggleMasterEnabled() {
        setMasterEnabled(!isMasterEnabled)
    }

    // MARK: - Server + Discovery Setup

    private func setupServer() {
        guard isMasterEnabled else { return }
        let server = LocalBridgeServer()
        server.onClientConnected = { [weak self] _ in
            Task { @MainActor in self?.isConnected = true }
        }
        server.onClientDisconnected = { [weak self] in
            Task { @MainActor in
                self?.isConnected = false
                self?.updateStatusItemAppearance()
            }
        }
        server.onPairingRequested = { [weak self] peerName, code in
            Task { @MainActor in
                self?.pendingPairingPeer = peerName
                self?.pendingPairingCode = code
            }
        }
        server.onPairingConfirmed = { [weak self] peerName in
            Task { @MainActor in
                guard let self else { return }
                self.pairedPeerName = peerName
                self.isConnected = true
                self.pendingPairingPeer = nil
                self.pendingPairingCode = nil
                // Persist name + deviceId so both survive server/app restart
                self.defaults.set(peerName, forKey: self.kPairedName)
                // Read deviceId back from the server (set during pair.confirm handling)
                if let did = self.localBridgeServer?.pairedDeviceId {
                    self.defaults.set(did, forKey: self.kPairedDeviceId)
                }
                self.logger.info("Paired with \(peerName) — saved to UserDefaults")
                self.updateStatusItemAppearance()
            }
        }
        server.onPairingRejected = { [weak self] peerName in
            Task { @MainActor in
                self?.pendingPairingPeer = nil
                self?.pendingPairingCode = nil
                self?.logger.info("Pairing was rejected with \(peerName)")
            }
        }
        server.onUnpaired = { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.pairedPeerName = nil
                self.pendingPairingPeer = nil
                self.pendingPairingCode = nil
                // Clear from UserDefaults
                self.defaults.removeObject(forKey: self.kPairedName)
                self.defaults.removeObject(forKey: self.kPairedDeviceId)
                self.updateStatusItemAppearance()
                self.logger.info("Device unpaired — UserDefaults cleared")
            }
        }
        // Mac receives clipboard from phone → write directly to Mac pasteboard
        server.onClipboardReceived = { [weak self] content, mime, bytes in
            Task { @MainActor in
                guard let self = self, self.isMasterEnabled else { return }
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(content, forType: .string)
                self.lastSentMime = mime
                self.lastSentBytes = bytes
                self.eventCount += 1
                self.logger.info("Received clipboard from phone: \(bytes)B")
            }
        }
        server.onFileTransferProgress = { [weak self] state in
            Task { @MainActor in
                guard let self = self else { return }
                // Never let a progress update revert a completed or failed state
                if let current = self.activeTransfer, current.id == state.id {
                    if current.status == .completed || current.status == .failed || current.status == .cancelled {
                        return
                    }
                }
                self.activeTransfer = state
            }
        }
        server.onFileTransferCompleted = { [weak self] state in
            Task { @MainActor in
                guard let self = self else { return }
                var finalState = state
                finalState.status = .completed
                finalState.bytesTransferred = finalState.totalBytes
                self.activeTransfer = finalState
                self.logger.info("File transfer complete: \(state.fileName)")
                // Auto-clear after 2.5 s so the UI returns to idle state
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                if self.activeTransfer?.id == finalState.id &&
                   (self.activeTransfer?.status == .completed || self.activeTransfer?.status == .cancelled) {
                    self.activeTransfer = nil
                }
            }
        }
        server.onFileTransferFailed = { [weak self] state in
            Task { @MainActor in
                guard let self = self else { return }
                var finalState = state
                finalState.status = .failed
                self.activeTransfer = finalState
                self.logger.error("File transfer failed: \(state.fileName)")
                // Auto-clear after 2.5 s
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                if self.activeTransfer?.id == finalState.id &&
                   (self.activeTransfer?.status == .failed || self.activeTransfer?.status == .cancelled) {
                    self.activeTransfer = nil
                }
            }
        }
        do {
            try server.start()
            // Restore paired state into the server immediately after start
            if let name = defaults.string(forKey: kPairedName) {
                let did = defaults.string(forKey: kPairedDeviceId)
                server.restorePairedState(peerName: name, deviceId: did)
                logger.info("Restored pairing state: \(name) (\(did ?? "nil"))")
            }
            self.localBridgeServer = server
            logger.info("LocalBridgeServer started on port 38473")
        } catch {
            logger.error("Failed to start LocalBridgeServer: \(error)")
        }
    }

    private func setupDiscovery() {
        guard isMasterEnabled else { return }
        let advertiser = BonjourAdvertiser()
        advertiser.startAdvertising()
        bonjourAdvertiser = advertiser

        let browser = ServiceBrowser()
        browser.onPeersChanged = { [weak self] peers in
            Task { @MainActor [weak self] in
                self?.discoveredPeers = peers
            }
        }
        browser.startBrowsing()
        serviceBrowser = browser
    }

    func refreshDiscovery() {
        guard isMasterEnabled else { return }
        serviceBrowser?.stopBrowsing()
        serviceBrowser = nil
        discoveredPeers = []
        let browser = ServiceBrowser()
        browser.onPeersChanged = { [weak self] peers in
            Task { @MainActor [weak self] in
                self?.discoveredPeers = peers
            }
        }
        browser.startBrowsing()
        serviceBrowser = browser
    }

    // MARK: - Status Item

    private func setupStatusItem() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        guard let button = statusItem?.button else { return }
        button.action = #selector(statusBarButtonClicked)
        button.target = self
        statusItem?.menu = nil
        updateStatusItemAppearance()
    }

    func updateStatusItemAppearance() {
        guard let button = statusItem?.button else { return }
        let symbolName: String
        if !isMasterEnabled {
            symbolName = "power.circle"
        } else if isPaired {
            symbolName = "checkmark.circle"
        } else {
            symbolName = "doc.on.clipboard"
        }
        if let image = NSImage(systemSymbolName: symbolName, accessibilityDescription: "LocalBridge") {
            image.isTemplate = true
            button.image = image
        } else {
            button.title = isMasterEnabled ? "LB" : "LB (Off)"
        }
    }

    @objc private func statusBarButtonClicked() {
        let menu = buildMenu()
        statusItem?.menu = menu
        statusItem?.button?.performClick(nil)
        statusItem?.menu = nil
    }

    private func buildMenu() -> NSMenu {
        let menu = NSMenu()

        // ── Header & Master Toggle ──
        let header = NSMenuItem(title: "LocalBridge", action: nil, keyEquivalent: "")
        header.isEnabled = false
        menu.addItem(header)

        let masterItem = NSMenuItem(
            title: isMasterEnabled ? "● Master: ON (Active)" : "○ Master: OFF (Silent Mode)",
            action: #selector(toggleMasterEnabled),
            keyEquivalent: ""
        )
        masterItem.target = self
        menu.addItem(masterItem)

        menu.addItem(.separator())

        if !isMasterEnabled {
            let pausedNote = NSMenuItem(title: "Silent mode: 0% power / network", action: nil, keyEquivalent: "")
            pausedNote.isEnabled = false
            menu.addItem(pausedNote)

            let resumeItem = NSMenuItem(title: "Turn On LocalBridge", action: #selector(toggleMasterEnabled), keyEquivalent: "")
            resumeItem.target = self
            menu.addItem(resumeItem)

            menu.addItem(.separator())
            let dashItem = NSMenuItem(title: "Open Dashboard…", action: #selector(openDashboard), keyEquivalent: "d")
            dashItem.keyEquivalentModifierMask = [.command, .shift]
            dashItem.target = self
            menu.addItem(dashItem)

            menu.addItem(.separator())
            menu.addItem(NSMenuItem(title: "Quit LocalBridge", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
            return menu
        }

        // ── Pending Pair Request ──
        if let pendingPeer = pendingPairingPeer {
            let reqItem = NSMenuItem(title: "Pair request from \(pendingPeer):", action: nil, keyEquivalent: "")
            reqItem.isEnabled = false
            menu.addItem(reqItem)

            let acceptItem = NSMenuItem(title: "  ✓ Accept Pairing", action: #selector(acceptPairing), keyEquivalent: "")
            acceptItem.target = self
            menu.addItem(acceptItem)

            let rejectItem = NSMenuItem(title: "  ✕ Reject Pairing", action: #selector(rejectPairing), keyEquivalent: "")
            rejectItem.target = self
            menu.addItem(rejectItem)

            menu.addItem(.separator())
        }

        // ── Connection & Pairing Status ──
        if let peer = pairedPeerName {
            let pairedItem = NSMenuItem(title: "● Paired with \(peer)", action: nil, keyEquivalent: "")
            pairedItem.isEnabled = false
            menu.addItem(pairedItem)

            let unpairItem = NSMenuItem(title: "Unpair from \(peer)", action: #selector(unpair), keyEquivalent: "")
            unpairItem.target = self
            menu.addItem(unpairItem)
        } else if isConnected {
            let unpItem = NSMenuItem(title: "● Connected (Unpaired)", action: nil, keyEquivalent: "")
            unpItem.isEnabled = false
            menu.addItem(unpItem)
        } else {
            let searchItem = NSMenuItem(title: "○ Searching for devices…", action: nil, keyEquivalent: "")
            searchItem.isEnabled = false
            menu.addItem(searchItem)
        }

        menu.addItem(.separator())

        // ── Send Clipboard (Mac → Phone) ──
        let sendClipItem = NSMenuItem(
            title: "Send Clipboard to Phone",
            action: #selector(sendClipboardToPhone),
            keyEquivalent: "c"
        )
        sendClipItem.keyEquivalentModifierMask = [.command, .shift]
        sendClipItem.target = self
        sendClipItem.isEnabled = isPaired
        menu.addItem(sendClipItem)

        // ── Send Text (type text, send to phone clipboard) ──
        let sendTextItem = NSMenuItem(
            title: "Send Text to Phone…",
            action: #selector(openSendTextPanel),
            keyEquivalent: "t"
        )
        sendTextItem.keyEquivalentModifierMask = [.command, .shift]
        sendTextItem.target = self
        sendTextItem.isEnabled = isPaired
        menu.addItem(sendTextItem)

        // ── Send File — Two modes ──
        let sendFileItem = NSMenuItem(
            title: "Send File to Phone…",
            action: #selector(openSendFilePanel),
            keyEquivalent: "f"
        )
        sendFileItem.keyEquivalentModifierMask = [.command, .shift]
        sendFileItem.target = self
        sendFileItem.isEnabled = isPaired
        menu.addItem(sendFileItem)

        let dropItem = NSMenuItem(
            title: "Drop Files Here…",
            action: #selector(openFileDropWindow),
            keyEquivalent: ""
        )
        dropItem.target = self
        dropItem.isEnabled = isPaired
        menu.addItem(dropItem)

        menu.addItem(.separator())

        // ── Open Dashboard ──
        let dashItem = NSMenuItem(title: "Open Dashboard…", action: #selector(openDashboard), keyEquivalent: "d")
        dashItem.keyEquivalentModifierMask = [.command, .shift]
        dashItem.target = self
        menu.addItem(dashItem)

        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "Quit LocalBridge", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))

        return menu
    }

    // MARK: - Send Actions

    @objc func sendClipboardToPhone() {
        guard isMasterEnabled else {
            showAlert(title: "LocalBridge is Off", message: "Turn on the master toggle to send.")
            return
        }
        guard isPaired else {
            showAlert(title: "Not paired", message: "Pair LocalBridge with your phone first.")
            return
        }
        guard let text = NSPasteboard.general.string(forType: .string), !text.isEmpty else {
            showAlert(title: "Nothing to send", message: "Your Mac clipboard is empty.")
            return
        }
        localBridgeServer?.sendClipboardPush(content: text, mimeType: "text/plain")
        eventCount += 1
        lastSentBytes = text.utf8.count
        lastSentMime = "text/plain"
        logger.info("Sent clipboard to phone: \(text.utf8.count)B")
    }

    @objc func openSendTextPanel() {
        guard isPaired else {
            showAlert(title: "Not paired", message: "Pair LocalBridge with your phone first.")
            return
        }
        if let existing = sendTextPanel, existing.isVisible {
            existing.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
            return
        }
        let view = SendTextView(controller: self)
        let hosting = NSHostingView(rootView: view)
        hosting.frame = NSRect(x: 0, y: 0, width: 400, height: 240)

        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 400, height: 240),
            styleMask: [.titled, .closable, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.title = "Send Text to Phone"
        panel.contentView = hosting
        panel.center()
        panel.backgroundColor = NSColor(red: 0.06, green: 0.06, blue: 0.06, alpha: 1)
        panel.titlebarAppearsTransparent = true
        panel.isReleasedWhenClosed = false
        panel.level = .floating
        panel.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        sendTextPanel = panel
    }

    func sendText(_ text: String) {
        guard !text.isEmpty else { return }
        guard isMasterEnabled else {
            showAlert(title: "LocalBridge is Off", message: "Turn on the master toggle to send.")
            return
        }
        guard isPaired else {
            showAlert(title: "Not paired", message: "Pair LocalBridge with your phone first.")
            return
        }
        localBridgeServer?.sendClipboardPush(content: text, mimeType: "text/plain")
        eventCount += 1
        lastSentBytes = text.utf8.count
        lastSentMime = "text/plain"
        sendTextPanel?.close()
        logger.info("Sent text to phone: \(text.utf8.count)B")
    }

    @objc func openSendFilePanel() {
        guard isPaired else {
            showAlert(title: "Not paired", message: "Pair LocalBridge with your phone first.")
            return
        }
        let panel = NSOpenPanel()
        panel.title = "Select File or Media to Send to Phone"
        panel.prompt = "Send to Phone"
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = true
        if panel.runModal() == .OK {
            for url in panel.urls {
                sendFile(url: url)
            }
        }
        // Cancelled: no-op — keep app in clean state
    }

    @objc func openFileDropWindow() {
        if fileDropWindowController == nil {
            fileDropWindowController = FileDropWindowController(menuBarController: self)
        }
        fileDropWindowController?.show()
    }

    @objc func sendFile(url: URL) {
        guard isMasterEnabled else {
            showAlert(title: "LocalBridge is Off", message: "Turn on the master toggle first.")
            return
        }
        guard isPaired else {
            showAlert(title: "Not Paired", message: "Pair LocalBridge with your phone first.")
            return
        }
        Task {
            do {
                try await localBridgeServer?.sendFile(url: url)
            } catch {
                await MainActor.run {
                    self.showAlert(title: "File Send Failed", message: error.localizedDescription)
                }
            }
        }
    }

    func cancelTransfer() {
        localBridgeServer?.cancelFileTransfer()
        activeTransfer = nil
    }

    // MARK: - Dashboard

    @objc func openDashboard() {
        if let existing = dashboardWindow, existing.isVisible {
            existing.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
            return
        }

        let view = DashboardView(controller: self)
        let hosting = NSHostingView(rootView: view)
        hosting.frame = NSRect(x: 0, y: 0, width: 480, height: 600)

        let win = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 480, height: 600),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        win.title = "LocalBridge"
        win.contentView = hosting
        win.center()
        win.setFrameAutosaveName("LocalBridgeDashboard")
        win.backgroundColor = NSColor(red: 0.05, green: 0.05, blue: 0.05, alpha: 1)
        win.titlebarAppearsTransparent = true
        win.isReleasedWhenClosed = false
        win.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        dashboardWindow = win
    }

    // MARK: - Pairing & 1-to-1 Device Management

    func pairWith(peer: DiscoveredPeer) {
        guard isMasterEnabled else {
            showAlert(title: "LocalBridge is Off", message: "Turn on the master toggle first.")
            return
        }
        guard pairedPeerName == nil else {
            showAlert(title: "Already paired", message: "You are already paired with \(pairedPeerName!). Unpair first to connect to another device.")
            return
        }
        if let code = localBridgeServer?.initiatePairing(with: peer.deviceName) {
            pendingPairingPeer = peer.deviceName
            pendingPairingCode = code
        } else {
            pendingPairingPeer = peer.deviceName
        }
    }

    @objc func acceptPairing() {
        guard let peerName = pendingPairingPeer else { return }
        localBridgeServer?.sendPairAccepted()
        pairedPeerName = peerName
        isConnected = true
        pendingPairingPeer = nil
        pendingPairingCode = nil
        updateStatusItemAppearance()
    }

    @objc func rejectPairing() {
        localBridgeServer?.sendPairRejected()
        pendingPairingPeer = nil
        pendingPairingCode = nil
        updateStatusItemAppearance()
    }

    @objc func unpair() {
        localBridgeServer?.unpair()
        pairedPeerName = nil
        pendingPairingPeer = nil
        pendingPairingCode = nil
        // Erase persisted pairing — user explicitly unpairing means fresh start
        defaults.removeObject(forKey: kPairedName)
        defaults.removeObject(forKey: kPairedDeviceId)
        updateStatusItemAppearance()
        logger.info("Unpaired — UserDefaults cleared")
    }

    // MARK: - Helpers

    private func showAlert(title: String, message: String) {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = message
        alert.alertStyle = .informational
        alert.runModal()
    }
}
