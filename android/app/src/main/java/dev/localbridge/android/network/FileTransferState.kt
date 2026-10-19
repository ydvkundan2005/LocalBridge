package dev.localbridge.android.network

enum class FileTransferStatus {
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class FileTransferState(
    val id: String,
    val fileName: String,
    val totalBytes: Long,
    val bytesTransferred: Long = 0,
    val isOutgoing: Boolean, // true = Android -> Mac, false = Mac -> Android
    val status: FileTransferStatus = FileTransferStatus.TRANSFERRING,
    val speedBytesPerSec: Double = 0.0,
    val errorMessage: String? = null,
    val savedPath: String? = null
) {
    val percentage: Int
        get() = if (totalBytes > 0) ((bytesTransferred * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

    val bytesRemaining: Long
        get() = (totalBytes - bytesTransferred).coerceAtLeast(0)

    val formattedTransferred: String
        get() = formatBytes(bytesTransferred)

    val formattedTotal: String
        get() = formatBytes(totalBytes)

    val formattedRemaining: String
        get() = formatBytes(bytesRemaining)

    val formattedSpeed: String
        get() = "%.1f MB/s".format(speedBytesPerSec / (1024.0 * 1024.0))

    val etaSeconds: Long
        get() = if (speedBytesPerSec > 1024 && bytesRemaining > 0) (bytesRemaining / speedBytesPerSec).toLong() else 0

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            if (bytes < 1024) return "$bytes B"
            val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt().coerceIn(1, 4)
            val pre = "KMGT"[exp - 1]
            return "%.1f %sB".format(bytes / Math.pow(1024.0, exp.toDouble()), pre)
        }
    }
}
