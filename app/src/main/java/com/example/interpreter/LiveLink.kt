package com.example.interpreter

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject

// Links two phones nearby (Bluetooth / Wi-Fi, no internet needed) so each person talks into
// their own phone in their own language. Messages are small JSON objects.
class LiveLink(
    ctx: Context,
    private val myName: String,
    private val listener: Listener
) {
    interface Listener {
        fun onFound(id: String, name: String)
        fun onLost(id: String)
        fun onConfirm(id: String, name: String, code: String)
        fun onLinked(name: String)
        fun onUnlinked()
        fun onFailed()
        fun onMessage(msg: JSONObject)
    }

    private val client = Nearby.getConnectionsClient(ctx)
    private val names = HashMap<String, String>()
    private var peer: String? = null
    val linked: Boolean get() = peer != null

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            try { listener.onMessage(JSONObject(String(bytes, Charsets.UTF_8))) } catch (_: Exception) {}
        }
        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {}
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            names[id] = info.endpointName
            listener.onConfirm(id, info.endpointName, info.authenticationDigits)
        }
        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                peer = id
                client.stopAdvertising()
                client.stopDiscovery()
                listener.onLinked(names[id] ?: id)
            } else {
                listener.onFailed()
            }
        }
        override fun onDisconnected(id: String) {
            if (peer == id) peer = null
            listener.onUnlinked()
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            names[id] = info.endpointName
            listener.onFound(id, info.endpointName)
        }
        override fun onEndpointLost(id: String) = listener.onLost(id)
    }

    // Both phones advertise and look at the same time; either one can pick the other.
    fun start() {
        val strategy = Strategy.P2P_POINT_TO_POINT
        client.startAdvertising(myName, SERVICE, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { listener.onFailed() }
        client.startDiscovery(SERVICE, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { listener.onFailed() }
    }

    fun connect(id: String) {
        client.requestConnection(myName, id, lifecycle).addOnFailureListener { listener.onFailed() }
    }

    fun accept(id: String) {
        client.acceptConnection(id, payloads).addOnFailureListener { listener.onFailed() }
    }

    fun reject(id: String) {
        client.rejectConnection(id)
    }

    fun send(msg: JSONObject) {
        val p = peer ?: return
        client.sendPayload(p, Payload.fromBytes(msg.toString().toByteArray(Charsets.UTF_8)))
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        peer = null
    }

    companion object {
        private const val SERVICE = "com.example.interpreter.live"
    }
}
