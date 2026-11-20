package com.amatsagu.reson.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * High-level classification of network transports.
 */
enum class NetworkType {
    WIFI,
    CELLULAR_5G,
    CELLULAR_4G,
    CELLULAR_3G,
    CELLULAR,
    ETHERNET,
    OTHER,
    NONE
}

/**
 * Snapshot of the device's default network connectivity.
 */
data class NetworkState(
    val isConnected: Boolean = false,
    val isValidated: Boolean = false,
    val type: NetworkType = NetworkType.NONE,
    val networkHandle: Long = -1L,
    val displayName: String = "Offline",
)

/**
 * Transitions emitted when the underlying network route or connection changes.
 */
sealed interface NetworkTransition {
    data class Lost(val lastState: NetworkState) : NetworkTransition
    data class Switched(val from: NetworkState, val to: NetworkState) : NetworkTransition
    data class Restored(val to: NetworkState) : NetworkTransition
}

/**
 * Central monitor for network changes, handoffs (Wi-Fi <-> 5G/4G/3G, Wi-Fi roaming),
 * and disconnections. Provides debounced reactive transitions for seamless audio
 * stream recovery and smart request queue scheduling.
 */
class NetworkMonitor private constructor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _networkState = MutableStateFlow(getCurrentInitialState())
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private val _transitions = MutableSharedFlow<NetworkTransition>(extraBufferCapacity = 8)
    val transitions: SharedFlow<NetworkTransition> = _transitions.asSharedFlow()

    private var debounceJob: Job? = null
    private var lastEmittedState: NetworkState = _networkState.value

    companion object {
        private const val TAG = "Reson:Network"

        @Volatile
        private var instance: NetworkMonitor? = null

        fun getInstance(context: Context): NetworkMonitor {
            return instance ?: synchronized(this) {
                instance ?: NetworkMonitor(context.applicationContext).also {
                    instance = it
                    it.registerCallback()
                }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            Log.d(TAG, "Network available: handle=${network.networkHandle}, capabilities=$capabilities")
            evaluateNetworkChange(network, capabilities)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            Log.d(TAG, "Capabilities changed: handle=${network.networkHandle}, validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
            evaluateNetworkChange(network, capabilities)
        }

        override fun onLost(network: Network) {
            Log.w(TAG, "Network lost: handle=${network.networkHandle}")
            handleNetworkLost(network)
        }

        override fun onUnavailable() {
            Log.w(TAG, "Network unavailable")
            handleNetworkLost(null)
        }
    }

    private fun registerCallback() {
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            Log.i(TAG, "Registered default network callback successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register default network callback, falling back to request", e)
            try {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, networkCallback)
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Failed to register fallback network callback", fallbackError)
            }
        }
    }

    private fun getCurrentInitialState(): NetworkState {
        return try {
            val activeNetwork = connectivityManager.activeNetwork ?: return NetworkState()
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkState()
            resolveState(activeNetwork, caps)
        } catch (e: Exception) {
            Log.w(TAG, "Error getting initial network state: ${e.message}")
            NetworkState()
        }
    }

    private fun resolveState(network: Network, capabilities: NetworkCapabilities): NetworkState {
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val type = resolveNetworkType(capabilities)
        val displayName = when (type) {
            NetworkType.WIFI -> "Wi-Fi"
            NetworkType.CELLULAR_5G -> "5G Mobile"
            NetworkType.CELLULAR_4G -> "4G LTE"
            NetworkType.CELLULAR_3G -> "3G Mobile"
            NetworkType.CELLULAR -> "Mobile Data"
            NetworkType.ETHERNET -> "Ethernet"
            NetworkType.OTHER -> "Connected"
            NetworkType.NONE -> "Offline"
        }

        return NetworkState(
            isConnected = hasInternet,
            isValidated = isValidated,
            type = type,
            networkHandle = network.networkHandle,
            displayName = displayName
        )
    }

    private fun resolveNetworkType(capabilities: NetworkCapabilities): NetworkType {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val downKbps = capabilities.linkDownstreamBandwidthKbps
                when {
                    downKbps >= 100_000 -> NetworkType.CELLULAR_5G
                    downKbps >= 15_000 -> NetworkType.CELLULAR_4G
                    downKbps in 1..14_999 -> NetworkType.CELLULAR_3G
                    else -> NetworkType.CELLULAR
                }
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkType.OTHER
            else -> NetworkType.OTHER
        }
    }

    private fun evaluateNetworkChange(network: Network, capabilities: NetworkCapabilities?) {
        val caps = capabilities ?: connectivityManager.getNetworkCapabilities(network) ?: return
        val newState = resolveState(network, caps)

        // Only transition once the network is actually validated (avoids half-connected captive portal states)
        if (!newState.isConnected || !newState.isValidated) {
            _networkState.value = newState
            return
        }

        // Debounce transitions by 650ms to let DHCP, DNS and routing tables stabilize on tower/Wi-Fi handoff
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(650)
            val prev = lastEmittedState
            _networkState.value = newState

            if (!prev.isConnected || prev.type == NetworkType.NONE) {
                Log.i(TAG, "Network connection RESTORED -> ${newState.displayName} (handle=${newState.networkHandle})")
                lastEmittedState = newState
                _transitions.emit(NetworkTransition.Restored(newState))
            } else if (prev.networkHandle != newState.networkHandle || prev.type != newState.type) {
                Log.i(TAG, "Network SWITCHED from ${prev.displayName} to ${newState.displayName} (oldHandle=${prev.networkHandle}, newHandle=${newState.networkHandle})")
                lastEmittedState = newState
                _transitions.emit(NetworkTransition.Switched(from = prev, to = newState))
            } else {
                lastEmittedState = newState
            }
        }
    }

    private fun handleNetworkLost(network: Network?) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            // Short grace delay (250ms) to see if an immediate handoff to cellular occurs before announcing total loss
            delay(250)
            val currentActive = connectivityManager.activeNetwork
            val currentCaps = currentActive?.let { connectivityManager.getNetworkCapabilities(it) }

            if (currentActive == null || currentCaps == null || !currentCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                val prev = lastEmittedState
                val lostState = NetworkState(
                    isConnected = false,
                    isValidated = false,
                    type = NetworkType.NONE,
                    networkHandle = -1L,
                    displayName = "Offline"
                )
                _networkState.value = lostState
                lastEmittedState = lostState
                Log.w(TAG, "Network LOST completely. Was: ${prev.displayName}")
                _transitions.emit(NetworkTransition.Lost(prev))
            }
        }
    }

    /**
     * Synchronous check if the device currently has a validated internet connection.
     */
    fun isOnline(): Boolean {
        val state = _networkState.value
        return state.isConnected && state.isValidated
    }
}
