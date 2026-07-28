package com.johnanderson.familyportal.ha

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

data class DiscoveredHomeAssistant(
    val name: String,
    val url: String,
    val version: String?,
    val locationName: String?,
    val uuid: String?,
)

@Suppress("DEPRECATION")
class HomeAssistantDiscovery(context: Context) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val services = ConcurrentHashMap<String, DiscoveredHomeAssistant>()
    private val resolving = ConcurrentHashMap.newKeySet<String>()
    private val _discovered = MutableStateFlow<List<DiscoveredHomeAssistant>>(emptyList())
    val discovered: StateFlow<List<DiscoveredHomeAssistant>> = _discovered.asStateFlow()
    private var listener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var fallbackJob: Job? = null
    private var jmDns: JmDNS? = null

    @Synchronized
    fun start() {
        if (listener != null || jmDns != null) return
        services.clear()
        publish()
        multicastLock = wifiManager.createMulticastLock("FamilyPortal:ha-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = startJmDnsFallback()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (resolving.add(serviceInfo.serviceName)) resolve(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                services.remove(serviceInfo.serviceName)
                publish()
            }
        }
        listener = discoveryListener
        nsdManager.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        fallbackJob = scope.launch {
            delay(NSD_TIMEOUT_MILLIS)
            if (services.isEmpty()) startJmDnsFallback()
            delay(JMDNS_TIMEOUT_MILLIS)
            if (services.isEmpty()) scanLocalSubnet()
        }
    }

    @Synchronized
    fun stop() {
        fallbackJob?.cancel()
        fallbackJob = null
        listener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        listener = null
        scope.launch {
            runCatching { jmDns?.close() }
            jmDns = null
        }
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        resolving.clear()
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolving.remove(serviceInfo.serviceName)
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                resolving.remove(serviceInfo.serviceName)
                val attributes = serviceInfo.attributes.mapValues { (_, value) -> value.toString(Charsets.UTF_8) }
                val host = serviceInfo.host?.hostAddress ?: return
                addService(serviceInfo.serviceName, host, serviceInfo.port, attributes)
            }
        })
    }

    @Synchronized
    private fun startJmDnsFallback() {
        if (jmDns != null) return
        scope.launch {
            val address = wifiIpv4Address() ?: run { Log.e(TAG, "No LAN IPv4 address for mDNS"); return@launch }
            Log.i(TAG, "Starting JmDNS on " + address)
            val instance = runCatching { JmDNS.create(address, "FamilyPortal") }
                .onFailure { Log.e(TAG, "Unable to start JmDNS", it) }
                .getOrNull() ?: return@launch
            jmDns = instance
            instance.addServiceListener(JMDNS_SERVICE_TYPE, object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    Log.i(TAG, "JmDNS found " + event.name)
                    instance.requestServiceInfo(event.type, event.name, true)
                }
                override fun serviceRemoved(event: ServiceEvent) {
                    services.remove(event.name)
                    publish()
                }
                override fun serviceResolved(event: ServiceEvent) {
                    Log.i(TAG, "JmDNS resolved " + event.name)
                    val info = event.info
                    val host = info.inet4Addresses.firstOrNull()?.hostAddress
                        ?: info.inetAddresses.firstOrNull()?.hostAddress
                        ?: return
                    val attributes = info.propertyNames.asSequence().associateWith(info::getPropertyString)
                    addService(event.name, host, info.port, attributes)
                }
            })
        }
    }

    private suspend fun scanLocalSubnet() = coroutineScope {
        val localAddress = wifiIpv4Address() ?: return@coroutineScope
        val bytes = localAddress.address
        if (bytes.size != 4) return@coroutineScope
        Log.i(TAG, "Scanning local subnet for Home Assistant")
        val semaphore = Semaphore(SCAN_CONCURRENCY)
        (1..254).map { host ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val candidate = "${bytes[0].toInt() and 0xff}.${bytes[1].toInt() and 0xff}.${bytes[2].toInt() and 0xff}.$host"
                    if (candidate != localAddress.hostAddress && isHomeAssistant(candidate)) {
                        addService("Home Assistant", candidate, DEFAULT_HA_PORT, emptyMap())
                    }
                }
            }
        }.awaitAll()
    }

    private fun isHomeAssistant(host: String): Boolean {
        val connection = runCatching {
            (URL("http://$host:$DEFAULT_HA_PORT/manifest.json").openConnection() as HttpURLConnection).apply {
                connectTimeout = SCAN_CONNECT_TIMEOUT_MILLIS
                readTimeout = SCAN_READ_TIMEOUT_MILLIS
                requestMethod = "GET"
                instanceFollowRedirects = false
            }
        }.getOrNull() ?: return false
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
            connection.inputStream.bufferedReader().use { reader ->
                val body = reader.readText().take(8_192)
                body.contains("Home Assistant", ignoreCase = true) ||
                    body.contains("home-assistant", ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun addService(name: String, host: String, port: Int, attributes: Map<String, String?>) {
        val advertisedUrl = attributes["internal_url"]?.takeIf(String::isNotBlank)
            ?: attributes["base_url"]?.takeIf(String::isNotBlank)
        services[name] = DiscoveredHomeAssistant(
            name = name,
            url = (advertisedUrl ?: "http://$host:$port").trimEnd('/'),
            version = attributes["version"],
            locationName = attributes["location_name"],
            uuid = attributes["uuid"],
        )
        publish()
    }

    private fun wifiIpv4Address(): Inet4Address? = NetworkInterface.getNetworkInterfaces().toList()
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList().asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }

    private fun publish() {
        _discovered.value = services.values.sortedBy { it.name.lowercase() }
    }

    private companion object {
        const val TAG = "HomeAssistantDiscovery"
        const val NSD_SERVICE_TYPE = "_home-assistant._tcp"
        const val JMDNS_SERVICE_TYPE = "_home-assistant._tcp.local."
        const val NSD_TIMEOUT_MILLIS = 4_000L
        const val JMDNS_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_HA_PORT = 8123
        const val SCAN_CONCURRENCY = 24
        const val SCAN_CONNECT_TIMEOUT_MILLIS = 250
        const val SCAN_READ_TIMEOUT_MILLIS = 750
    }
}
