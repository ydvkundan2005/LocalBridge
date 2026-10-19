import Foundation
import Network

/// Advertises the LocalBridge service over mDNS/Bonjour so Android can discover
/// the Mac without manual IP entry.
///
/// Service type: `_localbridge._tcp.` (02_ARCHITECTURE.md §3)
/// Default port:  38473 (03_PROTOCOL.md §1)
@MainActor
final class BonjourAdvertiser: NSObject, NetServiceDelegate {

    nonisolated static let serviceType = "_localbridge._tcp."
    nonisolated static let defaultPort: UInt16 = 38473

    private var netService: NetService?
    private let port: UInt16
    let deviceName: String
    private let logger = RedactedLogger(subsystem: "dev.localbridge.mac", category: "Bonjour")

    private(set) var isAdvertising: Bool = false

    init(port: UInt16 = defaultPort, deviceName: String = Host.current().localizedName ?? "LocalBridge Mac") {
        self.port = port
        self.deviceName = deviceName
        super.init()
    }

    // MARK: - Start / Stop

    func startAdvertising() {
        guard !isAdvertising else { return }
        let service = NetService(
            domain: "local.",
            type: Self.serviceType,
            name: deviceName,
            port: Int32(port)
        )
        service.delegate = self
        service.publish()
        self.netService = service
        self.isAdvertising = true
        logger.info("Bonjour advertising started for \(self.deviceName) on port \(self.port)")
    }

    func stopAdvertising() {
        netService?.stop()
        netService = nil
        isAdvertising = false
        logger.info("Bonjour advertiser stopped")
    }

    // MARK: - NetServiceDelegate

    nonisolated func netServiceDidPublish(_ sender: NetService) {
        let name = sender.name
        Task { @MainActor in
            self.logger.info("Bonjour service successfully published: \(name)")
            self.isAdvertising = true
        }
    }

    nonisolated func netService(_ sender: NetService, didNotPublish errorDict: [String : NSNumber]) {
        Task { @MainActor in
            self.logger.info("Bonjour didNotPublish error: \(errorDict)")
            self.isAdvertising = false
        }
    }

    nonisolated func netServiceDidStop(_ sender: NetService) {
        Task { @MainActor in
            self.logger.info("Bonjour service stopped")
            self.isAdvertising = false
        }
    }
}
