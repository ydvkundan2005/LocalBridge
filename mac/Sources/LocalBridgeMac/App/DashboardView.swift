import SwiftUI
import AppKit
import UniformTypeIdentifiers

// ─────────────────────────────────────────────────────────────────────────────
// Design tokens — Refined Minimalist Palette
// ─────────────────────────────────────────────────────────────────────────────

private let BG         = Color(red: 0.05, green: 0.05, blue: 0.05)
private let Surface1   = Color(red: 0.09, green: 0.09, blue: 0.09)
private let Surface2   = Color(red: 0.13, green: 0.13, blue: 0.13)
private let Primary    = Color(red: 0.94, green: 0.94, blue: 0.94)
private let Secondary  = Color(red: 0.52, green: 0.52, blue: 0.52)
private let Border     = Color(red: 0.18, green: 0.18, blue: 0.18)
private let AccentON   = Color(red: 0.0,  green: 0.88, blue: 0.44)
private let AccentBlue = Color(red: 0.30, green: 0.56, blue: 1.0)
private let AccentWarn = Color(red: 1.0,  green: 0.60, blue: 0.0)
private let AccentErr  = Color(red: 0.94, green: 0.33, blue: 0.31)

struct DashboardView: View {
    @ObservedObject var controller: MenuBarController

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HeaderRow(controller: controller)

                if !controller.isMasterEnabled {
                    SilentModeBanner(controller: controller)
                } else {
                    if let peer = controller.pendingPairingPeer {
                        PairingBanner(peerName: peer, controller: controller)
                    }
                    ConnectionCard(controller: controller)
                    FileTransferCard(controller: controller)
                    TextAndClipboardCard(controller: controller)
                    NearbyDevicesCard(controller: controller)
                }
                Spacer(minLength: 12)
            }
            .padding(24)
        }
        .background(BG)
        .preferredColorScheme(.dark)
    }
}

// MARK: - Header with Master Toggle

private struct HeaderRow: View {
    @ObservedObject var controller: MenuBarController

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text("LocalBridge")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundStyle(Primary)
                Text("Fast, private Android ↔ Mac transfer")
                    .font(.system(size: 11))
                    .foregroundStyle(Secondary)
            }
            Spacer()

            // Minimalist Master Toggle Pill
            HStack(spacing: 8) {
                Circle()
                    .fill(controller.isMasterEnabled ? AccentON : Secondary.opacity(0.4))
                    .frame(width: 6, height: 6)
                Text(controller.isMasterEnabled ? "Active" : "Paused")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(controller.isMasterEnabled ? Primary : Secondary)
                Toggle("", isOn: Binding(
                    get: { controller.isMasterEnabled },
                    set: { controller.setMasterEnabled($0) }
                ))
                .toggleStyle(SwitchToggleStyle(tint: AccentON))
                .labelsHidden()
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(Surface1)
            .clipShape(RoundedRectangle(cornerRadius: 20))
            .overlay(RoundedRectangle(cornerRadius: 20).stroke(Border, lineWidth: 0.8))
        }
    }
}

// MARK: - Silent Mode Banner

private struct SilentModeBanner: View {
    @ObservedObject var controller: MenuBarController

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(Secondary.opacity(0.10))
                    .frame(width: 36, height: 36)
                Image(systemName: "pause.fill")
                    .font(.system(size: 14))
                    .foregroundStyle(Secondary)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("LocalBridge is Paused")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Primary)
                Text("Network listeners and background services are completely stopped.")
                    .font(.system(size: 11))
                    .foregroundStyle(Secondary)
            }
            Spacer()
            Button(action: { controller.setMasterEnabled(true) }) {
                Text("Resume")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 6)
                    .background(AccentON)
                    .clipShape(RoundedRectangle(cornerRadius: 7))
            }
            .buttonStyle(.plain)
        }
        .padding(14)
        .background(Surface1)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Border, lineWidth: 0.8))
    }
}

// MARK: - Pairing Banner

private struct PairingBanner: View {
    let peerName: String
    @ObservedObject var controller: MenuBarController

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Circle()
                    .fill(AccentWarn)
                    .frame(width: 7, height: 7)
                Text("Pairing Request")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Primary)
                Spacer()
                if let code = controller.pendingPairingCode {
                    Text(code)
                        .font(.system(size: 13, weight: .bold, design: .monospaced))
                        .foregroundStyle(AccentWarn)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(AccentWarn.opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                }
            }
            Text("Confirm this code matches on \"\(peerName)\" to securely connect.")
                .font(.system(size: 11))
                .foregroundStyle(Secondary)

            HStack(spacing: 10) {
                Button(action: { controller.rejectPairing() }) {
                    Text("Decline")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(AccentErr)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 7)
                        .background(AccentErr.opacity(0.08))
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(.plain)

                Button(action: { controller.acceptPairing() }) {
                    Text("Accept & Pair")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.black)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 7)
                        .background(AccentON)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(14)
        .background(Surface1)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(AccentWarn.opacity(0.4), lineWidth: 1))
    }
}

// MARK: - Connection Card

private struct ConnectionCard: View {
    @ObservedObject var controller: MenuBarController

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("DEVICE CONNECTION")
                    .font(.system(size: 10, weight: .semibold))
                    .tracking(1.0)
                    .foregroundStyle(Secondary)
                Spacer()
            }

            if controller.isPaired, let peerName = controller.pairedPeerName {
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 8)
                            .fill(AccentON.opacity(0.12))
                            .frame(width: 36, height: 36)
                        Image(systemName: "iphone")
                            .font(.system(size: 16))
                            .foregroundStyle(AccentON)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(peerName)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(Primary)
                        Text(controller.isConnected ? "Connected · Direct local Wi-Fi" : "Saved · Waiting for device…")
                            .font(.system(size: 11))
                            .foregroundStyle(Secondary)
                    }
                    Spacer()

                    Button(action: { controller.unpair() }) {
                        Text("Unpair")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(AccentErr)
                            .padding(.horizontal, 9)
                            .padding(.vertical, 4)
                            .background(AccentErr.opacity(0.08))
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                    }
                    .buttonStyle(.plain)
                }
                .padding(12)
                .background(Surface2)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            } else {
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 8)
                            .fill(Border.opacity(0.4))
                            .frame(width: 36, height: 36)
                        Image(systemName: "iphone")
                            .font(.system(size: 16))
                            .foregroundStyle(Secondary)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text("No device paired")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(Primary)
                        Text("Open LocalBridge on your phone to connect")
                            .font(.system(size: 11))
                            .foregroundStyle(Secondary)
                    }
                    Spacer()
                }
                .padding(12)
                .background(Surface2)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            }
        }
        .padding(16)
        .background(Surface1)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Border, lineWidth: 0.8))
    }
}

// MARK: - High-Speed File Transfer Card

private struct FileTransferCard: View {
    @ObservedObject var controller: MenuBarController

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("FILE TRANSFER")
                    .font(.system(size: 10, weight: .semibold))
                    .tracking(1.0)
                    .foregroundStyle(Secondary)
                Spacer()
                Text("Direct Socket")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(AccentON)
            }

            // Active Transfer or Drop Zone
            if let transfer = controller.activeTransfer {
                TransferActiveView(transfer: transfer, onCancel: { controller.cancelTransfer() })
            } else {
                ZStack {
                    // Native AppKit drop zone behind the view to ensure 100% reliable Finder drops
                    NativeDropZoneRepresentable(
                        onDragChanged: { isOver in
                            withAnimation(.easeInOut(duration: 0.15)) {
                                controller.isDashboardDropTargeted = isOver
                            }
                        },
                        onDropUrls: { urls in
                            guard controller.isPaired else { return }
                            for url in urls {
                                controller.sendFile(url: url)
                            }
                        }
                    )

                    Button(action: { controller.openSendFilePanel() }) {
                        HStack(spacing: 12) {
                            ZStack {
                                RoundedRectangle(cornerRadius: 9)
                                    .fill(controller.isDashboardDropTargeted ? AccentON.opacity(0.20) : AccentON.opacity(0.10))
                                    .frame(width: 40, height: 40)
                                Image(systemName: controller.isDashboardDropTargeted ? "arrow.down" : "plus")
                                    .font(.system(size: 16, weight: .semibold))
                                    .foregroundStyle(controller.isPaired ? AccentON : Secondary)
                            }

                            VStack(alignment: .leading, spacing: 2) {
                                Text(controller.isDashboardDropTargeted ? "Release to Send" : "Send File or Media")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(controller.isDashboardDropTargeted ? AccentON : (controller.isPaired ? Primary : Secondary))
                                Text(controller.isDashboardDropTargeted ? "Sending directly to phone Downloads" : "Drop files here or click to browse")
                                    .font(.system(size: 11))
                                    .foregroundStyle(Secondary)
                            }

                            Spacer()

                            Image(systemName: "arrow.up.circle")
                                .font(.system(size: 18))
                                .foregroundStyle(controller.isPaired ? AccentON : Secondary.opacity(0.4))
                        }
                        .padding(14)
                        .background(controller.isDashboardDropTargeted ? AccentON.opacity(0.08) : Surface2)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .overlay(
                            RoundedRectangle(cornerRadius: 10)
                                .stroke(controller.isDashboardDropTargeted ? AccentON : Border, lineWidth: controller.isDashboardDropTargeted ? 1.5 : 0.8)
                        )
                    }
                    .buttonStyle(.plain)
                    .disabled(!controller.isPaired || !controller.isMasterEnabled)
                }
            }
        }
        .padding(16)
        .background(Surface1)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Border, lineWidth: 0.8))
    }
}

// MARK: - Active Transfer View

private struct TransferActiveView: View {
    let transfer: FileTransferState
    let onCancel: () -> Void

    var body: some View {
        let progress = min(max(CGFloat(transfer.percentage) / 100.0, 0.0), 1.0)

        VStack(alignment: .leading, spacing: 10) {
            HStack {
                HStack(spacing: 6) {
                    Image(systemName: transfer.isOutgoing ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                        .foregroundStyle(AccentON)
                        .font(.system(size: 13))
                    Text(transfer.isOutgoing ? "Sending to Phone" : "Receiving from Phone")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(Primary)
                }
                Spacer()
                Text("\(transfer.percentage)%")
                    .font(.system(size: 14, weight: .semibold, design: .monospaced))
                    .foregroundStyle(AccentON)
            }

            Text(transfer.fileName)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(Primary)
                .lineLimit(1)

            // Sleek Progress Bar
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 3)
                        .fill(Border)
                        .frame(height: 5)
                    RoundedRectangle(cornerRadius: 3)
                        .fill(transfer.status == .failed ? AccentErr : AccentON)
                        .frame(width: max(0, geo.size.width * progress), height: 5)
                        .animation(.easeInOut(duration: 0.12), value: transfer.percentage)
                }
            }
            .frame(height: 5)

            HStack {
                Text("\(transfer.formattedTransferred) / \(transfer.formattedTotal)")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(Secondary)
                Spacer()
                if transfer.speedBytesPerSec > 0 {
                    Text(transfer.formattedSpeed)
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .foregroundStyle(AccentBlue)
                }
                if transfer.status == .transferring {
                    Button("Cancel") { onCancel() }
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(AccentErr)
                        .buttonStyle(.plain)
                        .padding(.leading, 8)
                }
            }

            if transfer.status == .completed {
                HStack(spacing: 5) {
                    Image(systemName: "checkmark")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(AccentON)
                    Text(transfer.isOutgoing ? "Saved to Phone Downloads" : "Saved to Mac Downloads")
                        .font(.system(size: 11))
                        .foregroundStyle(AccentON)
                }
            } else if transfer.status == .failed {
                Text("Failed: \(transfer.errorMessage ?? "Error")")
                    .font(.system(size: 11))
                    .foregroundStyle(AccentErr)
            }
        }
        .padding(12)
        .background(Surface2)
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

// MARK: - Text & Clipboard Card

private struct TextAndClipboardCard: View {
    @ObservedObject var controller: MenuBarController

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("TEXT & CLIPBOARD")
                    .font(.system(size: 10, weight: .semibold))
                    .tracking(1.0)
                    .foregroundStyle(Secondary)
                Spacer()
            }

            // Quick Send Clipboard
            Button(action: { controller.sendClipboardToPhone() }) {
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 8)
                            .fill(AccentON.opacity(0.10))
                            .frame(width: 36, height: 36)
                        Image(systemName: "doc.on.clipboard")
                            .font(.system(size: 15))
                            .foregroundStyle(controller.isPaired ? AccentON : Secondary)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Send Clipboard to Phone")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(controller.isPaired ? Primary : Secondary)
                        Text("Pushes current Mac pasteboard to phone")
                            .font(.system(size: 11))
                            .foregroundStyle(Secondary)
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11))
                        .foregroundStyle(controller.isPaired ? AccentON : Border)
                }
                .padding(12)
                .background(Surface2)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            }
            .buttonStyle(.plain)
            .disabled(!controller.isPaired || !controller.isMasterEnabled)

            // Direct Text Input
            HStack(spacing: 8) {
                TextField("Type text to send to phone clipboard…", text: $controller.typedText)
                    .textFieldStyle(.plain)
                    .font(.system(size: 12))
                    .padding(9)
                    .background(Surface2)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Border, lineWidth: 0.8))
                    .disabled(!controller.isPaired)

                if !controller.typedText.isEmpty && controller.isPaired {
                    Button(action: {
                        controller.sendText(controller.typedText)
                        controller.typedText = ""
                    }) {
                        Text("Send")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(AccentBlue)
                            .clipShape(RoundedRectangle(cornerRadius: 7))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(16)
        .background(Surface1)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Border, lineWidth: 0.8))
    }
}

// MARK: - Nearby Devices Card

private struct NearbyDevicesCard: View {
    @ObservedObject var controller: MenuBarController

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("NEARBY DEVICES")
                    .font(.system(size: 10, weight: .semibold))
                    .tracking(1.0)
                    .foregroundStyle(Secondary)
                Spacer()
                Button(action: { controller.refreshDiscovery() }) {
                    Text("Scan")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(AccentBlue)
                }
                .buttonStyle(.plain)
            }

            if controller.discoveredPeers.isEmpty {
                HStack(spacing: 10) {
                    Image(systemName: "antenna.radiowaves.left.and.right")
                        .font(.system(size: 12))
                        .foregroundStyle(Secondary)
                    Text("Searching local network…")
                        .font(.system(size: 11))
                        .foregroundStyle(Secondary)
                    Spacer()
                }
                .padding(12)
                .background(Surface2)
                .clipShape(RoundedRectangle(cornerRadius: 8))
            } else {
                ForEach(controller.discoveredPeers) { peer in
                    HStack(spacing: 10) {
                        Image(systemName: "iphone")
                            .font(.system(size: 13))
                            .foregroundStyle(Primary)
                        Text(peer.deviceName)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(Primary)
                        Spacer()
                        if controller.pairedPeerName == peer.deviceName {
                            Text("Paired")
                                .font(.system(size: 10, weight: .medium))
                                .foregroundStyle(AccentON)
                        } else {
                            Button("Pair") { controller.pairWith(peer: peer) }
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(.black)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .background(AccentON)
                                .clipShape(RoundedRectangle(cornerRadius: 5))
                                .buttonStyle(.plain)
                        }
                    }
                    .padding(10)
                    .background(Surface2)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                }
            }
        }
        .padding(16)
        .background(Surface1)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Border, lineWidth: 0.8))
    }
}
