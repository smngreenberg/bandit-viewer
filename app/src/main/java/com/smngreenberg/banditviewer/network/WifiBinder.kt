package com.smngreenberg.banditviewer.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class WifiBinder(context: Context) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    
    var onNetworkLost: (() -> Unit)? = null

    suspend fun bind(): Result<Network> {
        val deferred = CompletableDeferred<Network>()
        
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
            
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                deferred.complete(network)
            }
            
            override fun onUnavailable() {
                deferred.completeExceptionally(Exception("Wi-Fi network unavailable"))
            }

            override fun onLost(network: Network) {
                onNetworkLost?.invoke()
            }
        }
        
        connectivityManager.requestNetwork(request, callback!!)
        
        val network = try {
            withTimeoutOrNull(5000) { deferred.await() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        
        if (network == null) {
            unbind()
            return Result.failure(Exception("Timed out waiting for camera Wi-Fi. Join the camera's network in Settings."))
        }
        
        connectivityManager.bindProcessToNetwork(network)
        
        val linkProperties = connectivityManager.getLinkProperties(network)
        if (!isBanditSubnet(linkProperties)) {
            unbind()
            return Result.failure(Exception("Connected Wi-Fi doesn't look like the Bandit (expected 192.168.1.x). Join the camera's Wi-Fi in Settings."))
        }
        
        return Result.success(network)
    }

    private fun isBanditSubnet(lp: LinkProperties?): Boolean {
        lp?.linkAddresses?.forEach { addr ->
            val ip = addr.address.hostAddress ?: ""
            if (ip.startsWith("192.168.1.")) {
                return true
            }
        }
        return false
    }

    fun unbind() {
        connectivityManager.bindProcessToNetwork(null)
        callback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // Ignore
            }
            callback = null
        }
    }
}
