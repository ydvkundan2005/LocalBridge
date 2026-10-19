package dev.localbridge.android.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque

/**
 * Handles both mDNS / Bonjour advertising and discovery on Android:
 * 1. Advertises this Android phone as `_localbridge._tcp` via NsdManager.registerService,
 *    so Mac's ServiceBrowser can discover it.
 * 2. Browses for `_localbridge._tcp` services on the local Wi-Fi, resolving them
 *    sequentially to prevent NsdManager resolve collisions, so Android discovers Mac.
 * 3. Holds WifiManager.MulticastLock so Android Wi-Fi hardware does not drop mDNS packets.
 */
class BonjourDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "LB.BonjourDiscovery"
        const val SERVICE_TYPE = "_localbridge._tcp"
        const val DEFAULT_PORT = 38473
    }

    data class DiscoveredHost(
        val serviceName: String,
        val host: String,
        val port: Int,
    )

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var multicastLock: WifiManager.MulticastLock? = null


    private val _discovered = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val discovered: StateFlow<List<DiscoveredHost>> = _discovered

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val resolvedHosts = mutableMapOf<String, DiscoveredHost>()
    private var registeredServiceName: String? = null

    // Sequential resolve queue to prevent NsdManager resolve collisions
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var isResolving = false
    private var isAdvertisingRequested = false
    private var isBrowsingRequested = false

    // Device display name for advertising
    val deviceDisplayName: String = run {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    // -------------------------------------------------------------------------
    // Registration Listener (Advertiser)
    // -------------------------------------------------------------------------

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            registeredServiceName = serviceInfo.serviceName
            isAdvertisingRequested = false
            _isAdvertising.value = true
            Log.i(TAG, "Service registered as: ${serviceInfo.serviceName} port=${serviceInfo.port}")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "Service registration failed: errorCode=$errorCode")
            isAdvertisingRequested = false
            _isAdvertising.value = false
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service unregistered: ${serviceInfo.serviceName}")
            isAdvertisingRequested = false
            _isAdvertising.value = false
            registeredServiceName = null
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "Service unregistration failed: errorCode=$errorCode")
        }
    }

    // -------------------------------------------------------------------------
    // Discovery Listener (Browser)
    // -------------------------------------------------------------------------

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery start failed: errorCode=$errorCode")
            _lastError.value = "Discovery start failed (code $errorCode)"
            isBrowsingRequested = false
            _isSearching.value = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Discovery stop failed: errorCode=$errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(TAG, "NSD discovery started for $serviceType")
            isBrowsingRequested = false
            _isSearching.value = true
            _lastError.value = null
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "NSD discovery stopped")
            isBrowsingRequested = false
            _isSearching.value = false
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d(TAG, "Service found: name='${service.serviceName}' type='${service.serviceType}'")

            // Don't discover ourselves
            val myName = registeredServiceName ?: deviceDisplayName
            if (service.serviceName.equals(myName, ignoreCase = true)) {
                Log.d(TAG, "Ignoring self discovery: ${service.serviceName}")
                return
            }

            if (service.serviceType.contains("_localbridge")) {
                enqueueResolve(service)
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.d(TAG, "Service lost: ${service.serviceName}")
            synchronized(resolvedHosts) {
                resolvedHosts.remove(service.serviceName)
            }
            publishHosts()
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Starts advertising and browsing for nearby LocalBridge devices.
     */
    fun startDiscovery() {
        acquireMulticastLock()
        startAdvertising()
        startBrowsing()
    }

    /**
     * Stops advertising and browsing.
     */
    fun stopDiscovery() {
        stopBrowsing()
        stopAdvertising()
        releaseMulticastLock()
    }

    fun restartDiscovery() {
        stopDiscovery()
        startDiscovery()
    }

    // -------------------------------------------------------------------------
    // Internal Advertising
    // -------------------------------------------------------------------------

    private fun startAdvertising() {
        if (_isAdvertising.value || isAdvertisingRequested) return
        isAdvertisingRequested = true
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = deviceDisplayName
                serviceType = SERVICE_TYPE
                setPort(DEFAULT_PORT)
            }

            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            Log.d(TAG, "Registered service advertise request for $deviceDisplayName on port $DEFAULT_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD service", e)
            isAdvertisingRequested = false
        }
    }

    private fun stopAdvertising() {
        try {
            nsdManager.unregisterService(registrationListener)
        } catch (e: Exception) {
            Log.d(TAG, "Unregister service suppressed: ${e.message}")
        }
        isAdvertisingRequested = false
        _isAdvertising.value = false
        registeredServiceName = null
    }

    // -------------------------------------------------------------------------
    // Internal Browsing
    // -------------------------------------------------------------------------

    private fun startBrowsing() {
        if (_isSearching.value || isBrowsingRequested) return
        isBrowsingRequested = true
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            Log.d(TAG, "Discovery requested for $SERVICE_TYPE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD discovery", e)
            _lastError.value = "Failed to start discovery: ${e.message}"
            isBrowsingRequested = false
        }
    }

    private fun stopBrowsing() {
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.d(TAG, "Stop discovery error suppressed: ${e.message}")
        }
        isBrowsingRequested = false
        synchronized(resolveQueue) {
            resolveQueue.clear()
            isResolving = false
        }
        synchronized(resolvedHosts) {
            resolvedHosts.clear()
        }
        publishHosts()
        _isSearching.value = false
    }

    // -------------------------------------------------------------------------
    // Sequential Service Resolution
    // -------------------------------------------------------------------------

    private fun enqueueResolve(service: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.add(service)
            if (!isResolving) {
                resolveNext()
            }
        }
    }

    private fun resolveNext() {
        synchronized(resolveQueue) {
            if (resolveQueue.isEmpty()) {
                isResolving = false
                return
            }
            isResolving = true
            val nextService = resolveQueue.poll() ?: run {
                isResolving = false
                return
            }

            try {
                nsdManager.resolveService(nextService, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: errorCode=$errorCode")
                        synchronized(resolveQueue) {
                            resolveNext()
                        }
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress
                        val port = serviceInfo.port.takeIf { it > 0 } ?: DEFAULT_PORT
                        if (host != null) {
                            val peer = DiscoveredHost(
                                serviceName = serviceInfo.serviceName,
                                host = host,
                                port = port,
                            )
                            Log.i(TAG, "Resolved peer: ${serviceInfo.serviceName} → $host:$port")
                            synchronized(resolvedHosts) {
                                resolvedHosts[serviceInfo.serviceName] = peer
                            }
                            publishHosts()
                        }
                        synchronized(resolveQueue) {
                            resolveNext()
                        }
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "Exception during resolveService: ${e.message}")
                synchronized(resolveQueue) {
                    resolveNext()
                }
            }
        }
    }

    private fun baseDeviceName(name: String): String {
        return name.replace(Regex("""[\s\-_]*\(\d+\)$|[\s\-_]+\d+$"""), "").trim()
    }

    private fun publishHosts() {
        synchronized(resolvedHosts) {
            val seenIps = mutableSetOf<String>()
            val seenBases = mutableSetOf<String>()
            val deduplicated = mutableListOf<DiscoveredHost>()

            // Sort by length so canonical names without suffixes come first
            val sorted = resolvedHosts.values.sortedBy { it.serviceName.length }
            for (peer in sorted) {
                val base = baseDeviceName(peer.serviceName).lowercase()
                val ip = peer.host
                if (seenIps.contains(ip) || seenBases.contains(base)) {
                    continue
                }
                seenIps.add(ip)
                seenBases.add(base)
                deduplicated.add(peer)
            }
            _discovered.value = deduplicated
        }
    }

    // -------------------------------------------------------------------------
    // Multicast Lock
    // -------------------------------------------------------------------------

    private fun acquireMulticastLock() {
        if (multicastLock != null && multicastLock?.isHeld == true) return
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("LocalBridgeMdnsLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "MulticastLock held: ${multicastLock?.isHeld}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
            multicastLock = null
            Log.d(TAG, "MulticastLock released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MulticastLock: ${e.message}")
        }
    }
}
