// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual object NetworkPathMonitor {
    private val _state = MutableStateFlow(NetworkPathState())
    actual val state: StateFlow<NetworkPathState> = _state

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val connectivity = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivity == null) {
            _state.value = NetworkPathState(kind = NetworkPathKind.Unknown)
            return
        }

        fun publish(network: Network? = connectivity.activeNetwork) {
            val caps = network?.let(connectivity::getNetworkCapabilities)
            val kind = when {
                caps == null -> NetworkPathKind.Unavailable
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ->
                    NetworkPathKind.Available
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                    NetworkPathKind.Constrained
                else -> NetworkPathKind.Unavailable
            }
            _state.value = NetworkPathState(
                kind = kind,
                isConstrained = kind == NetworkPathKind.Constrained,
            )
        }

        publish()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = publish(network)
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) = publish(network)
                override fun onLost(network: Network) = publish(null)
            },
        )
    }
}
