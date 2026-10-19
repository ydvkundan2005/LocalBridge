package dev.localbridge.android

import android.app.Application
import android.util.Log
import dev.localbridge.android.network.BonjourDiscovery
import dev.localbridge.android.network.LocalBridgeWebSocketClient

/**
 * Application entry point for LocalBridge.
 * Holds application-level singletons for discovery and WebSocket client
 * so background services and UI can reliably share the connection.
 */
class LocalBridgeApplication : Application() {

    lateinit var webSocketClient: LocalBridgeWebSocketClient
        private set

    lateinit var discovery: BonjourDiscovery
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "LocalBridgeApplication.onCreate")
        discovery = BonjourDiscovery(this)
        webSocketClient = LocalBridgeWebSocketClient(this)
    }

    companion object {
        private const val TAG = "LocalBridge"
        lateinit var instance: LocalBridgeApplication
            private set
    }
}

