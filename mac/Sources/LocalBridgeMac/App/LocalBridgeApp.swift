import AppKit
import SwiftUI

/// LocalBridge macOS menu-bar application entry point.
///
/// Phase 2 scope: menu-bar icon, pasteboard watcher/writer, echo guard.
/// Networking (Phase 3), file transfer (Phase 4), and full UI (Phase 5)
/// are not included here.
///
/// Design follows 08_UI_SPEC.md §1 (dark, minimalist) and §8 (menu-bar popover).
@main
struct LocalBridgeApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        // Menu-bar only; no main window in Phase 2.
        // A Settings window will be added in Phase 5.
        Settings {
            EmptyView()
        }
    }
}

// MARK: - AppDelegate

final class AppDelegate: NSObject, NSApplicationDelegate, @unchecked Sendable {
    private var menuBarController: MenuBarController?
    private var activityToken: NSObjectProtocol?
    private let logger = RedactedLogger(subsystem: "dev.localbridge.mac", category: "AppDelegate")

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)

        // Prevent macOS from idling or terminating the background server
        activityToken = ProcessInfo.processInfo.beginActivity(
            options: [.userInitiated, .idleSystemSleepDisabled],
            reason: "LocalBridge Background Server and Discovery"
        )

        menuBarController = MenuBarController()
        menuBarController?.openDashboard()
        logger.info("LocalBridgeMac started")
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return false
    }

    func applicationWillTerminate(_ notification: Notification) {
        if let token = activityToken {
            ProcessInfo.processInfo.endActivity(token)
            activityToken = nil
        }
        menuBarController?.tearDown()
        logger.info("LocalBridgeMac terminating")
    }
}
