import AppKit
import SwiftUI
import UniformTypeIdentifiers

// MARK: - Native Drop Destination View (AppKit)

/// A rock-solid AppKit NSView that registers for all Finder drag types and passes dropped URLs.
final class NativeDropContainerView: NSView {

    var onDragStateChanged: ((Bool) -> Void)?
    var onUrlsDropped: (([URL]) -> Void)?

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        setupDrag()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupDrag()
    }

    private func setupDrag() {
        registerForDraggedTypes([
            .fileURL,
            .URL,
            NSPasteboard.PasteboardType("public.file-url"),
            NSPasteboard.PasteboardType("com.apple.finder.node"),
            NSPasteboard.PasteboardType(UTType.fileURL.identifier),
            NSPasteboard.PasteboardType("NSFilenamesPboardType"),
            .string
        ])
    }

    private func extractUrls(from sender: NSDraggingInfo) -> [URL] {
        var result: [URL] = []
        let pboard = sender.draggingPasteboard

        if let objs = pboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], !objs.isEmpty {
            result.append(contentsOf: objs)
        }
        if result.isEmpty, let filenames = pboard.propertyList(forType: NSPasteboard.PasteboardType("NSFilenamesPboardType")) as? [String] {
            result.append(contentsOf: filenames.map { URL(fileURLWithPath: $0) })
        }
        if result.isEmpty, let str = pboard.string(forType: .fileURL), let u = URL(string: str) {
            result.append(u)
        }
        return result
    }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        let urls = extractUrls(from: sender)
        if !urls.isEmpty {
            onDragStateChanged?(true)
            return .copy
        }
        return []
    }

    override func draggingUpdated(_ sender: NSDraggingInfo) -> NSDragOperation {
        let urls = extractUrls(from: sender)
        return urls.isEmpty ? [] : .copy
    }

    override func draggingExited(_ sender: NSDraggingInfo?) {
        onDragStateChanged?(false)
    }

    override func draggingEnded(_ sender: NSDraggingInfo) {
        onDragStateChanged?(false)
    }

    override func prepareForDragOperation(_ sender: NSDraggingInfo) -> Bool {
        return !extractUrls(from: sender).isEmpty
    }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        onDragStateChanged?(false)
        let urls = extractUrls(from: sender)
        guard !urls.isEmpty else { return false }
        onUrlsDropped?(urls)
        return true
    }
}

// MARK: - SwiftUI Drop Bridge

struct NativeDropZoneRepresentable: NSViewRepresentable {
    let onDragChanged: (Bool) -> Void
    let onDropUrls: ([URL]) -> Void

    func makeNSView(context: Context) -> NativeDropContainerView {
        let view = NativeDropContainerView(frame: .zero)
        view.onDragStateChanged = onDragChanged
        view.onUrlsDropped = onDropUrls
        return view
    }

    func updateNSView(_ nsView: NativeDropContainerView, context: Context) {
        nsView.onDragStateChanged = onDragChanged
        nsView.onUrlsDropped = onDropUrls
    }
}

// MARK: - FileDropWindowController

@MainActor
final class FileDropWindowController: NSObject {

    private var window: NSWindow?
    private weak var menuBarController: MenuBarController?

    init(menuBarController: MenuBarController) {
        self.menuBarController = menuBarController
        super.init()
    }

    func show() {
        if let existing = window, existing.isVisible {
            existing.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
            return
        }

        guard let controller = menuBarController else { return }
        let rootView = FileDropView(controller: controller)
        let hosting = NSHostingView(rootView: rootView)
        hosting.frame = NSRect(x: 0, y: 0, width: 440, height: 340)

        let win = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 440, height: 340),
            styleMask: [.titled, .closable, .miniaturizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        win.title = "LocalBridge Transfer"
        win.titleVisibility = .hidden
        win.titlebarAppearsTransparent = true
        win.isMovableByWindowBackground = true
        win.contentView = hosting
        win.center()
        win.backgroundColor = NSColor(red: 0.05, green: 0.05, blue: 0.05, alpha: 1)
        win.isReleasedWhenClosed = false
        win.level = .floating

        win.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        self.window = win
    }

    func close() {
        window?.close()
    }
}

// MARK: - FileDropModel

private final class FileDropModel: ObservableObject {
    @Published var isDragOver: Bool = false
    @Published var statusMessage: String? = nil
    @Published var errorMessage: String? = nil
}

// MARK: - FileDropView (Minimalist SwiftUI Design)

struct FileDropView: View {
    @ObservedObject var controller: MenuBarController
    @StateObject private var model = FileDropModel()

    private let bg       = Color(red: 0.06, green: 0.06, blue: 0.06)
    private let surface  = Color(red: 0.10, green: 0.10, blue: 0.10)
    private let border   = Color(red: 0.18, green: 0.18, blue: 0.18)
    private let accentON = Color(red: 0.0,  green: 0.88, blue: 0.44)
    private let accentBl = Color(red: 0.30, green: 0.56, blue: 1.0)
    private let accentEr = Color(red: 0.94, green: 0.33, blue: 0.31)
    private let primary  = Color(red: 0.95, green: 0.95, blue: 0.95)
    private let secondary = Color(red: 0.52, green: 0.52, blue: 0.52)

    var body: some View {
        ZStack {
            bg.ignoresSafeArea()

            // Transparent overlay capturing all AppKit drops across the entire window
            NativeDropZoneRepresentable(
                onDragChanged: { isOver in
                    withAnimation(.easeInOut(duration: 0.15)) {
                        model.isDragOver = isOver
                    }
                },
                onDropUrls: { urls in
                    handleDroppedUrls(urls)
                }
            )

            VStack(spacing: 0) {
                // Minimal Header Bar
                HStack(spacing: 8) {
                    Circle()
                        .fill(controller.isPaired ? accentON : secondary.opacity(0.4))
                        .frame(width: 7, height: 7)

                    Text(controller.isPaired ? (controller.pairedPeerName ?? "Paired Phone") : "Not Paired")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(primary)

                    Spacer()

                    Text(controller.isPaired ? "Ready" : "Pair in menu first")
                        .font(.system(size: 11))
                        .foregroundStyle(secondary)
                }
                .padding(.horizontal, 20)
                .padding(.top, 18)
                .padding(.bottom, 14)

                // Main Content
                if let transfer = controller.activeTransfer {
                    MinimalTransferProgressCard(
                        transfer: transfer,
                        onCancel: { controller.cancelTransfer() },
                        surface: surface,
                        border: border,
                        accentON: accentON,
                        accentBl: accentBl,
                        accentEr: accentEr,
                        primary: primary,
                        secondary: secondary
                    )
                    .padding(20)
                    Spacer()
                } else {
                    MinimalDropArea(
                        isDragOver: model.isDragOver,
                        isPaired: controller.isPaired,
                        errorMessage: model.errorMessage,
                        surface: surface,
                        border: border,
                        accentON: accentON,
                        accentBl: accentBl,
                        accentEr: accentEr,
                        primary: primary,
                        secondary: secondary,
                        onSelectFiles: openFilePicker
                    )
                    .padding(20)
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private func handleDroppedUrls(_ urls: [URL]) {
        guard controller.isPaired else {
            model.errorMessage = "Pair with your phone first"
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
                model.errorMessage = nil
            }
            return
        }
        model.errorMessage = nil
        for url in urls {
            controller.sendFile(url: url)
        }
    }

    @MainActor
    private func openFilePicker() {
        guard controller.isPaired else {
            model.errorMessage = "Pair with your phone first"
            return
        }
        let panel = NSOpenPanel()
        panel.title = "Select Files to Send"
        panel.prompt = "Send"
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = true
        if panel.runModal() == .OK {
            for url in panel.urls {
                controller.sendFile(url: url)
            }
        }
    }
}

// MARK: - Minimal Drop Area

private struct MinimalDropArea: View {
    let isDragOver: Bool
    let isPaired: Bool
    let errorMessage: String?
    let surface, border, accentON, accentBl, accentEr, primary, secondary: Color
    let onSelectFiles: () -> Void

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 14)
                .fill(isDragOver ? accentON.opacity(0.08) : surface)

            RoundedRectangle(cornerRadius: 14)
                .strokeBorder(
                    isDragOver ? accentON : (errorMessage != nil ? accentEr : border),
                    style: StrokeStyle(lineWidth: isDragOver ? 2 : 1, dash: isDragOver ? [] : [6, 6])
                )
                .animation(.easeInOut(duration: 0.15), value: isDragOver)

            VStack(spacing: 14) {
                ZStack {
                    Circle()
                        .fill(isDragOver ? accentON.opacity(0.15) : border.opacity(0.5))
                        .frame(width: 52, height: 52)

                    Image(systemName: isDragOver ? "arrow.down" : "square.and.arrow.up")
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(isDragOver ? accentON : (isPaired ? primary : secondary))
                }

                if let err = errorMessage {
                    Text(err)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(accentEr)
                } else {
                    VStack(spacing: 4) {
                        Text(isDragOver ? "Release to Send" : "Drop files here to send")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(primary)

                        Text(isPaired ? "Ultra-fast direct stream to your phone" : "Pair with your phone to start")
                            .font(.system(size: 11))
                            .foregroundStyle(secondary)
                    }
                }

                if !isDragOver {
                    Button(action: onSelectFiles) {
                        HStack(spacing: 6) {
                            Image(systemName: "folder")
                                .font(.system(size: 11))
                            Text("Browse Files…")
                                .font(.system(size: 11, weight: .medium))
                        }
                        .foregroundStyle(primary)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 6)
                        .background(border)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                    .buttonStyle(.plain)
                    .disabled(!isPaired)
                    .opacity(isPaired ? 1.0 : 0.4)
                }
            }
            .padding(24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Minimal Transfer Progress Card

private struct MinimalTransferProgressCard: View {
    let transfer: FileTransferState
    let onCancel: () -> Void
    let surface, border, accentON, accentBl, accentEr, primary, secondary: Color

    var body: some View {
        let progress = min(max(CGFloat(transfer.percentage) / 100.0, 0.0), 1.0)

        VStack(alignment: .leading, spacing: 14) {
            // Header
            HStack {
                HStack(spacing: 6) {
                    Image(systemName: transfer.isOutgoing ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                        .foregroundStyle(accentON)
                        .font(.system(size: 14))

                    Text(transfer.isOutgoing ? "Sending to Phone" : "Receiving from Phone")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(primary)
                }

                Spacer()

                Text("\(transfer.percentage)%")
                    .font(.system(size: 15, weight: .semibold, design: .monospaced))
                    .foregroundStyle(accentON)
            }

            // File Name
            Text(transfer.fileName)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(primary)
                .lineLimit(1)

            // Sleek Progress Bar
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 3)
                        .fill(border)
                        .frame(height: 6)

                    RoundedRectangle(cornerRadius: 3)
                        .fill(transfer.status == .failed ? accentEr : accentON)
                        .frame(width: max(0, geo.size.width * progress), height: 6)
                        .animation(.easeInOut(duration: 0.12), value: transfer.percentage)
                }
            }
            .frame(height: 6)

            // Transfer stats line
            HStack {
                Text("\(transfer.formattedTransferred) / \(transfer.formattedTotal)")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(secondary)

                Spacer()

                if transfer.speedBytesPerSec > 0 {
                    Text(transfer.formattedSpeed)
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .foregroundStyle(accentBl)
                }
            }

            // Status / Action
            HStack {
                if transfer.status == .completed {
                    HStack(spacing: 5) {
                        Image(systemName: "checkmark")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(accentON)
                        Text(transfer.isOutgoing ? "Saved to Phone" : "Saved to Downloads")
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(accentON)
                    }
                } else if transfer.status == .failed {
                    Text("Failed: \(transfer.errorMessage ?? "Error")")
                        .font(.system(size: 11))
                        .foregroundStyle(accentEr)
                }

                Spacer()

                if transfer.status == .transferring {
                    Button("Cancel") { onCancel() }
                        .font(.system(size: 11))
                        .foregroundStyle(accentEr)
                        .buttonStyle(.plain)
                }
            }
        }
        .padding(18)
        .background(surface)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(border, lineWidth: 1))
    }
}
