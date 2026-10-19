import Foundation
import Network

/// Lightweight discovery state published to the UI.
struct DiscoveredPeer: Identifiable, Equatable {
    let id: String           // device name
    let host: String         // resolved IP address
    let port: UInt16
    let deviceName: String
    let discoveredAt: Date
}

/// Holds observable discovery state.
final class DiscoveryState: ObservableObject {
    @Published var peers: [DiscoveredPeer] = []
    @Published var isSearching: Bool = false
    @Published var lastError: String? = nil
}

/// Discovers LocalBridge peers advertised as `_localbridge._tcp` on the local network.
/// Uses NWBrowser for discovery + NetService to resolve actual IP addresses.
@MainActor
final class ServiceBrowser: NSObject, NetServiceDelegate {

    private var browser: NWBrowser?
    let state = DiscoveryState()
    private let logger = RedactedLogger(subsystem: "dev.localbridge.mac", category: "Browser")
    private let selfDeviceName: String

    // NetService instances being resolved
    private var pendingResolvers: [String: NetService] = [:]
    private var resolvedPeers: [String: DiscoveredPeer] = [:]

    var onPeersChanged: (([DiscoveredPeer]) -> Void)?

    init(selfDeviceName: String = Host.current().localizedName ?? "LocalBridge Mac") {
        self.selfDeviceName = selfDeviceName
        super.init()
    }

    func startBrowsing() {
        guard !state.isSearching else { return }
        let params = NWParameters()
        params.includePeerToPeer = true

        let descriptor = NWBrowser.Descriptor.bonjour(type: "_localbridge._tcp", domain: "local.")
        browser = NWBrowser(for: descriptor, using: params)

        browser?.browseResultsChangedHandler = { [weak self] results, _ in
            Task { @MainActor [weak self] in
                self?.handleResults(results)
            }
        }
        browser?.stateUpdateHandler = { [weak self] newState in
            Task { @MainActor [weak self] in
                switch newState {
                case .ready:
                    self?.state.isSearching = true
                    self?.state.lastError = nil
                    self?.logger.info("ServiceBrowser is ready and searching")
                case .failed(let error):
                    self?.state.isSearching = false
                    self?.state.lastError = error.localizedDescription
                    self?.logger.info("ServiceBrowser failed: \(error)")
                case .cancelled:
                    self?.state.isSearching = false
                default:
                    break
                }
            }
        }
        browser?.start(queue: .main)
        state.isSearching = true
        logger.info("ServiceBrowser started for _localbridge._tcp")
    }

    func stopBrowsing() {
        browser?.cancel()
        browser = nil
        state.isSearching = false
        state.peers = []
        for service in pendingResolvers.values {
            service.stop()
        }
        pendingResolvers.removeAll()
        resolvedPeers.removeAll()
        onPeersChanged?([])
        logger.info("ServiceBrowser stopped")
    }

    // MARK: - Private

    private func handleResults(_ results: Set<NWBrowser.Result>) {
        let currentNames = Set(results.compactMap { result -> String? in
            if case .service(let name, _, _, _) = result.endpoint { return name }
            return nil
        })

        // Remove peers that left
        let removed = resolvedPeers.keys.filter { !currentNames.contains($0) }
        for name in removed {
            resolvedPeers.removeValue(forKey: name)
            pendingResolvers[name]?.stop()
            pendingResolvers.removeValue(forKey: name)
        }

        for result in results {
            if case .service(let name, _, _, _) = result.endpoint {
                // Ignore self
                if name.caseInsensitiveCompare(selfDeviceName) == .orderedSame {
                    continue
                }
                if resolvedPeers[name] != nil || pendingResolvers[name] != nil {
                    continue
                }
                resolveService(name: name)
            }
        }
        publishPeers()
    }

    private func resolveService(name: String) {
        logger.info("Resolving service via NetService: \(name)")
        let service = NetService(domain: "local.", type: "_localbridge._tcp.", name: name)
        service.delegate = self
        service.schedule(in: .main, forMode: .common)
        pendingResolvers[name] = service
        service.resolve(withTimeout: 5.0)
    }

    // MARK: - NetServiceDelegate

    nonisolated func netServiceDidResolveAddress(_ sender: NetService) {
        let name = sender.name
        let port = UInt16(sender.port > 0 ? sender.port : Int(BonjourAdvertiser.defaultPort))
        var resolvedIP = "\(name).local"

        for addrData in sender.addresses ?? [] {
            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            addrData.withUnsafeBytes { ptr in
                guard let sa = ptr.baseAddress?.assumingMemoryBound(to: sockaddr.self) else { return }
                if sa.pointee.sa_family == AF_INET {
                    if getnameinfo(sa, socklen_t(addrData.count), &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST) == 0 {
                        // Use modern String initialiser — truncate null terminator before decoding
                        let nullIdx = hostname.firstIndex(of: 0) ?? hostname.endIndex
                        resolvedIP = hostname[..<nullIdx].withUnsafeBytes { buf in
                            String(decoding: buf, as: UTF8.self)
                        }
                    }
                }
            }
            if resolvedIP != "\(name).local" { break }
        }

        Task { @MainActor in
            self.pendingResolvers.removeValue(forKey: name)
            let peer = DiscoveredPeer(
                id: name,
                host: resolvedIP,
                port: port,
                deviceName: name,
                discoveredAt: Date()
            )
            self.resolvedPeers[name] = peer
            self.publishPeers()
            self.logger.info("Resolved peer: \(name) → \(resolvedIP):\(port)")
        }
    }

    nonisolated func netService(_ sender: NetService, didNotResolve errorDict: [String : NSNumber]) {
        let name = sender.name
        Task { @MainActor in
            self.pendingResolvers.removeValue(forKey: name)
            if self.resolvedPeers[name] == nil {
                let peer = DiscoveredPeer(
                    id: name,
                    host: "\(name).local",
                    port: BonjourAdvertiser.defaultPort,
                    deviceName: name,
                    discoveredAt: Date()
                )
                self.resolvedPeers[name] = peer
                self.publishPeers()
            }
            self.logger.info("NetService failed to resolve \(name), using fallback: \(errorDict)")
        }
    }

    private func baseDeviceName(_ name: String) -> String {
        let pattern = #"[\s\-_]*\(\d+\)$|[\s\-_]+\d+$"#
        return name.replacingOccurrences(of: pattern, with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func publishPeers() {
        var seenIPs = Set<String>()
        var seenBases = Set<String>()
        var deduplicated: [DiscoveredPeer] = []

        // Sort by deviceName length so shorter/canonical names come first (e.g. "Nothing A059" before "Nothing A059 (2)")
        let sorted = Array(resolvedPeers.values).sorted { $0.deviceName.count < $1.deviceName.count }
        for peer in sorted {
            let base = baseDeviceName(peer.deviceName).lowercased()
            let ip = peer.host
            let isRealIP = !ip.hasSuffix(".local")
            if isRealIP && seenIPs.contains(ip) {
                continue
            }
            if seenBases.contains(base) {
                continue
            }
            if isRealIP {
                seenIPs.insert(ip)
            }
            seenBases.insert(base)
            deduplicated.append(peer)
        }
        state.peers = deduplicated
        onPeersChanged?(deduplicated)
    }
}
