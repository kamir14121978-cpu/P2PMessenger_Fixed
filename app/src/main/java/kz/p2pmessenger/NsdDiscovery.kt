package kz.p2pmessenger

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.UUID

/** A single foreground discovery session. All mutable state lives on the main thread. */
@Suppress("DEPRECATION") // resolveService supports the project's Android 10 minimum.
class NsdDiscovery(
    context: Context,
    private val onPeers: (List<Peer>) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val manager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val id = PROCESS_ID
    private var localName = "P2PMessenger-${Build.MODEL.take(16)}-${id.take(8)}"
    private var active = false
    private var registrationRequested = false
    private var discoveryRequested = false
    private val peers = linkedMapOf<String, Peer>()
    private val present = mutableMapOf<String, NsdServiceInfo>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private fun dispatch(block: () -> Unit) { main.post { block() } }
    private fun publish() { onPeers(peers.values.sortedBy { it.name }) }
    private fun status(message: String) { if (active) onStatus(message) }

    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) = dispatch {
            localName = info.serviceName // Android may rename a conflicting instance.
            if (!active) unregister()
            else {
                peers.remove(localName)
                publish()
            }
        }
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = dispatch {
            registrationRequested = false
            status("Не удалось объявить телефон в сети ($code). Нажмите «Обновить».")
        }
        override fun onServiceUnregistered(info: NsdServiceInfo) = dispatch {
            registrationRequested = false
        }
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = dispatch {
            android.util.Log.w("P2PMessenger", "NSD unregister failed: $code")
        }
    }

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = dispatch {
            if (!active) stopDiscovery()
        }
        override fun onServiceFound(info: NsdServiceInfo) = dispatch {
            if (active && info.serviceType.trimEnd('.') == SERVICE_TYPE.trimEnd('.') &&
                info.serviceName != localName && !present.containsKey(info.serviceName)) {
                present[info.serviceName] = info
                pending.add(info)
                resolveNext()
            }
        }
        override fun onServiceLost(info: NsdServiceInfo) = dispatch {
            if (active) {
                present.remove(info.serviceName)
                peers.remove(info.serviceName)
                publish()
            }
        }
        override fun onDiscoveryStopped(type: String) = dispatch {
            discoveryRequested = false
        }
        override fun onStartDiscoveryFailed(type: String, code: Int) = dispatch {
            status("Поиск недоступен ($code). Можно обновить поиск или ввести IP.")
            stopDiscovery()
        }
        override fun onStopDiscoveryFailed(type: String, code: Int) = dispatch {
            android.util.Log.w("P2PMessenger", "NSD stop failed: $code")
        }
    }

    fun start() {
        check(!active)
        active = true
        status("Поиск телефонов в локальной сети…")
        val info = NsdServiceInfo().apply {
            serviceName = localName
            serviceType = SERVICE_TYPE
            port = PORT
            setAttribute("id", id)
        }
        runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            registrationRequested = true
        }.onFailure { status("Регистрация NSD недоступна. Можно использовать IP.") }
        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            discoveryRequested = true
        }.onFailure { status("Поиск NSD недоступен. Можно использовать IP.") }
    }

    // Older Android releases allow only one outstanding resolve operation.
    private fun resolveNext() {
        if (!active || resolving) return
        var next = pending.poll() ?: return
        while (present[next.serviceName] !== next) next = pending.poll() ?: return
        val candidate = next
        resolving = true
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) = dispatch {
                resolving = false
                if (active && present[candidate.serviceName] === candidate) {
                    val ownId = info.attributes["id"]?.toString(Charsets.UTF_8) == id
                    val host = info.host?.hostAddress
                    if (!ownId && info.serviceName != localName && host != null && info.port == PORT) {
                        peers[candidate.serviceName] = Peer(candidate.serviceName, host, info.port)
                        publish()
                    }
                }
                resolveNext()
            }
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) = dispatch {
                resolving = false
                if (active && present[candidate.serviceName] === candidate) {
                    present.remove(candidate.serviceName)
                    status("Не удалось получить адрес ${candidate.serviceName} ($code). Обновите поиск.")
                }
                resolveNext()
            }
        }
        runCatching { manager.resolveService(candidate, listener) }.onFailure {
            resolving = false
            present.remove(candidate.serviceName)
            status("Не удалось получить адрес устройства. Обновите поиск.")
            resolveNext()
        }
    }

    fun stop() {
        active = false // Ignore late resolve callbacks from this session.
        pending.clear()
        present.clear()
        peers.clear()
        onPeers(emptyList())
        stopDiscovery()
        unregister()
    }

    private fun stopDiscovery() {
        if (discoveryRequested) {
            runCatching { manager.stopServiceDiscovery(discovery) }
                .onFailure { android.util.Log.w("P2PMessenger", "NSD stop", it) }
        }
    }

    private fun unregister() {
        if (registrationRequested) {
            runCatching { manager.unregisterService(registration) }
                .onFailure { android.util.Log.w("P2PMessenger", "NSD unregister", it) }
        }
    }

    companion object {
        // Also excludes our previous session while its asynchronous cleanup completes.
        private val PROCESS_ID = UUID.randomUUID().toString()
        const val SERVICE_TYPE = "_p2pmessenger._tcp."
        const val PORT = 45888
    }
}
