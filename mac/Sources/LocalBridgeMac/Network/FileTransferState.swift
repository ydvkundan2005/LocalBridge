import Foundation

public enum FileTransferStatus: String, Sendable {
    case transferring = "TRANSFERRING"
    case completed = "COMPLETED"
    case failed = "FAILED"
    case cancelled = "CANCELLED"
}

public struct FileTransferState: Identifiable, Sendable {
    public let id: String
    public let fileName: String
    public let totalBytes: Int64
    public var bytesTransferred: Int64
    public let isOutgoing: Bool
    public var status: FileTransferStatus
    public var speedBytesPerSec: Double
    public var errorMessage: String?
    public var savedPath: String?

    public init(
        id: String,
        fileName: String,
        totalBytes: Int64,
        bytesTransferred: Int64 = 0,
        isOutgoing: Bool,
        status: FileTransferStatus = .transferring,
        speedBytesPerSec: Double = 0,
        errorMessage: String? = nil,
        savedPath: String? = nil
    ) {
        self.id = id
        self.fileName = fileName
        self.totalBytes = totalBytes
        self.bytesTransferred = bytesTransferred
        self.isOutgoing = isOutgoing
        self.status = status
        self.speedBytesPerSec = speedBytesPerSec
        self.errorMessage = errorMessage
        self.savedPath = savedPath
    }

    public var percentage: Int {
        guard totalBytes > 0 else { return 0 }
        return min(100, max(0, Int((bytesTransferred * 100) / totalBytes)))
    }

    public var bytesRemaining: Int64 {
        max(0, totalBytes - bytesTransferred)
    }

    public var formattedTransferred: String {
        ByteCountFormatter.string(fromByteCount: bytesTransferred, countStyle: .file)
    }

    public var formattedTotal: String {
        ByteCountFormatter.string(fromByteCount: totalBytes, countStyle: .file)
    }

    public var formattedRemaining: String {
        ByteCountFormatter.string(fromByteCount: bytesRemaining, countStyle: .file)
    }

    public var formattedSpeed: String {
        String(format: "%.1f MB/s", speedBytesPerSec / (1024.0 * 1024.0))
    }

    public var etaSeconds: Int {
        guard speedBytesPerSec > 1024, bytesRemaining > 0 else { return 0 }
        return Int(Double(bytesRemaining) / speedBytesPerSec)
    }
}
