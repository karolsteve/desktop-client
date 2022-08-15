/* This Source Code Form is subject to the terms of the Mozilla Public
* License, v. 2.0. If a copy of the MPL was not distributed with this
* file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.amnezia.vpn

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.net.ProxyInfo
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.wireguard.android.util.SharedLibraryLoader
import com.wireguard.config.*
import com.wireguard.crypto.Key
import org.json.JSONObject

class VPNService : android.net.VpnService() {
    private val tag = "VPNService"
    private var mBinder: VPNServiceBinder = VPNServiceBinder(this)
    private var mConfig: JSONObject? = null
    private var mProtocol: String? = null
    private var mConnectionTime: Long = 0
    private var mAlreadyInitialised = false
    private var mbuilder: Builder = Builder()

    private var mOpenVPNThreadv3: OpenVPNThreadv3? = null
    private var currentTunnelHandle = -1

    fun init() {
        if (mAlreadyInitialised) {
            return
        }
        Log.init(this)
        SharedLibraryLoader.loadSharedLibrary(this, "wg-go")
        SharedLibraryLoader.loadSharedLibrary(this, "ovpn3")
        Log.i(tag, "Loaded libs")
        Log.e(tag, "Wireguard Version ${wgVersion()}")
        mOpenVPNThreadv3 = OpenVPNThreadv3(this)
        mAlreadyInitialised = true

    }

    override fun onUnbind(intent: Intent?): Boolean {
    Log.v(tag, "Got Unbind request")
        if (!isUp) {
            // If the Qt Client got closed while we were not connected
            // we do not need to stay as a foreground service.
            stopForeground(true)
        }
        return super.onUnbind(intent)
    }

    /**
     * EntryPoint for the Service, gets Called when AndroidController.cpp
     * calles bindService. Returns the [VPNServiceBinder] so QT can send Requests to it.
     */
    override fun onBind(intent: Intent?): IBinder? {
        Log.v(tag, "Got Bind request")
        init()
        return mBinder
    }

    /**
     * Might be the entryPoint if the Service gets Started via an
     * Service Intent: Might be from Always-On-Vpn from Settings
     * or from Booting the device and having "connect on boot" enabled.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        init()
        intent?.let {
            if (intent.getBooleanExtra("startOnly", false)) {
                Log.i(tag, "Start only!")
                return super.onStartCommand(intent, flags, startId)
            }
        }
        // This start is from always-on
        if (this.mConfig == null) {
            // We don't have tunnel to turn on - Try to create one with last config the service got
            val prefs = Prefs.get(this)
            val lastConfString = prefs.getString("lastConf", "")
            if (lastConfString.isNullOrEmpty()) {
                // We have nothing to connect to -> Exit
                Log.e(
                    tag,
                    "VPN service was triggered without defining a Server or having a tunnel"
                )
                return super.onStartCommand(intent, flags, startId)
            }
            this.mConfig = JSONObject(lastConfString)
        }

        return super.onStartCommand(intent, flags, startId)
    }

    // Invoked when the application is revoked.
    // At this moment, the VPN interface is already deactivated by the system.
    override fun onRevoke() {
        this.turnOff()
        super.onRevoke()
    }

    var connectionTime: Long = 0
        get() {
            return mConnectionTime
        }

    var isUp: Boolean
        get() {
            return currentTunnelHandle >= 0
        }
        set(value) {
            if (value) {
                mBinder.dispatchEvent(VPNServiceBinder.Events.connected, "")
                mConnectionTime = System.currentTimeMillis()
                return
            }
            mBinder.dispatchEvent(VPNServiceBinder.Events.disconnected, "")
            mConnectionTime = 0
        }
    val status: JSONObject
        get() {
            val deviceIpv4: String = ""
            return JSONObject().apply {
                putOpt("rx_bytes", getConfigValue("rx_bytes"))
                putOpt("tx_bytes", getConfigValue("tx_bytes"))
                putOpt("endpoint", mConfig?.getJSONObject("server")?.getString("ipv4Gateway"))
                putOpt("deviceIpv4", mConfig?.getJSONObject("device")?.getString("ipv4Address"))
            }
        }

    /*
    * Checks if the VPN Permission is given.
    * If the permission is given, returns true
    * Requests permission and returns false if not.
    */
    fun checkPermissions(): Boolean {
        // See https://developer.android.com/guide/topics/connectivity/vpn#connect_a_service
        // Call Prepare, if we get an Intent back, we dont have the VPN Permission
        // from the user. So we need to pass this to our main Activity and exit here.
        val intent = prepare(this)
        if (intent == null) {
            Log.e(tag, "VPN Permission Already Present")
            return true
        }
        Log.e(tag, "Requesting VPN Permission")
        return false
    }

    fun turnOn(json: JSONObject?): Int {
        if (!checkPermissions()) {
            Log.e(tag, "turn on was called without no permissions present!")
            isUp = false
            return 0
        }
        Log.i(tag, "Permission okay")
        mConfig = json!!
        mProtocol = mConfig!!.getString("protocol")
        when (mProtocol) {
            "openvpn" -> startOpenVpn()
            "wireguard" -> startWireGuard()
            else -> {
                Log.e(tag, "No protocol")
                return 0
            }
        }
        NotificationUtil.show(this) // Go foreground
        return 1
    }

    fun establish(): ParcelFileDescriptor? {
        mbuilder.allowFamily(OsConstants.AF_INET)
        mbuilder.allowFamily(OsConstants.AF_INET6)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mbuilder.setMetered(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) setUnderlyingNetworks(null)

        return mbuilder.establish()
    }

    fun setMtu(mtu: Int) {
        mbuilder.setMtu(mtu)
    }

    fun addAddress(ip: String, len: Int) {
        Log.v(tag, "mbuilder.addAddress($ip, $len)")
        mbuilder.addAddress(ip, len)
    }

    fun addRoute(ip: String, len: Int) {
        Log.v(tag, "mbuilder.addRoute($ip, $len)")
        mbuilder.addRoute(ip, len)
    }

    fun addDNS(ip: String) {
        Log.v(tag, "mbuilder.addDnsServer($ip)")
        mbuilder.addDnsServer(ip)
        if ("samsung".equals(Build.BRAND) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mbuilder.addRoute(ip, 32)
        }
    }

    fun setSessionName(name: String) {
        Log.v(tag, "mbuilder.setSession($name)")
        mbuilder.setSession(name)
    }

    fun addHttpProxy(host: String, port: Int): Boolean {
        val proxyInfo = ProxyInfo.buildDirectProxy(host, port)
        Log.v(tag, "mbuilder.addHttpProxy($host, $port)")
        mbuilder.setHttpProxy(proxyInfo)
        return true
    }

    fun setDomain(domain: String) {
        Log.v(tag, "mbuilder.setDomain($domain)")
        mbuilder.addSearchDomain(domain)
    }

    fun turnOff() {
        Log.v(tag, "Try to disable tunnel")
        when (mProtocol) {
            "wireguard" -> wgTurnOff(currentTunnelHandle)
            "openvpn" -> ovpnTurnOff()
            else -> {
                Log.e(tag, "No protocol")
            }
        }
        currentTunnelHandle = -1
        stopForeground(true)

        isUp = false
        stopSelf();
    }

    private fun ovpnTurnOff() {
        mOpenVPNThreadv3?.stop()
        mOpenVPNThreadv3 = null
        Log.e(tag, "mOpenVPNThreadv3 stop!")
    }

    /**
     * Configures an Android VPN Service Tunnel
     * with a given Wireguard Config
     */
    private fun setupBuilder(config: Config, builder: Builder) {
        // Setup Split tunnel
        for (excludedApplication in config.`interface`.excludedApplications)
            builder.addDisallowedApplication(excludedApplication)

        // Device IP
        for (addr in config.`interface`.addresses) builder.addAddress(addr.address, addr.mask)
        // DNS
        for (addr in config.`interface`.dnsServers) builder.addDnsServer(addr.hostAddress)
        // Add All routes the VPN may route tos
        for (peer in config.peers) {
            for (addr in peer.allowedIps) {
                builder.addRoute(addr.address, addr.mask)
            }
        }
        builder.allowFamily(OsConstants.AF_INET)
        builder.allowFamily(OsConstants.AF_INET6)
        builder.setMtu(config.`interface`.mtu.orElse(1280))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) setUnderlyingNetworks(null)

        builder.setBlocking(true)
    }

    /**
     * Gets config value for {key} from the Current
     * running Wireguard tunnel
     */
    private fun getConfigValue(key: String): String? {
        if (!isUp) {
            return null
        }
        val config = wgGetConfig(currentTunnelHandle) ?: return null
        val lines = config.split("\n")
        for (line in lines) {
            val parts = line.split("=")
            val k = parts.first()
            val value = parts.last()
            if (key == k) {
                return value
            }
        }
        return null
    }

    private fun parseConfigData(data: String): Map<String, Map<String, String>> {
        val parseData = mutableMapOf<String, Map<String, String>>()
        var currentSection: Pair<String, MutableMap<String, String>>? = null
        data.lines().forEach { line ->
            if (line.isNotEmpty()) {
                if (line.startsWith('[')) {
                    currentSection?.let {
                        parseData.put(it.first, it.second)
                    }
                    currentSection =
                        line.substring(1, line.indexOfLast { it == ']' }) to mutableMapOf()
                } else {
                    val parameter = line.split("=", limit = 2)
                    currentSection!!.second.put(parameter.first().trim(), parameter.last().trim())
                }
            }
        }
        currentSection?.let {
            parseData.put(it.first, it.second)
        }
        return parseData
    }

    /**
     * Create a Wireguard [Config]  from a [json] string -
     * The [json] will be created in AndroidVpnProtocol.cpp
     */
    private fun buildWireugardConfig(obj: JSONObject): Config {
        val confBuilder = Config.Builder()
        val wireguardConfigData = obj.getJSONObject("wireguard_config_data")
        val config = parseConfigData(wireguardConfigData.getString("config"))
        val peerBuilder = Peer.Builder()
        val peerConfig = config["Peer"]!!
        peerBuilder.setPublicKey(Key.fromBase64(peerConfig["PublicKey"]))
        peerConfig["PresharedKey"]?.let {
            peerBuilder.setPreSharedKey(Key.fromBase64(it))
        }
        val allowedIPList = peerConfig["AllowedIPs"]?.split(",") ?: emptyList()
        if (allowedIPList.isEmpty()) {
            val internet = InetNetwork.parse("0.0.0.0/0") // aka The whole internet.
            peerBuilder.addAllowedIp(internet)
        } else {
            allowedIPList.forEach {
                val network = InetNetwork.parse(it.trim())
                peerBuilder.addAllowedIp(network)
            }
        }
        val endpointConfig = peerConfig["Endpoint"]
        val endpoint = InetEndpoint.parse(endpointConfig)
        peerBuilder.setEndpoint(endpoint)
        peerConfig["PersistentKeepalive"]?.let {
            peerBuilder.setPersistentKeepalive(it.toInt())
        }
        confBuilder.addPeer(peerBuilder.build())

        val ifaceBuilder = Interface.Builder()
        val ifaceConfig = config["Interface"]!!
        ifaceBuilder.parsePrivateKey(ifaceConfig["PrivateKey"])
        ifaceBuilder.addAddress(InetNetwork.parse(ifaceConfig["Address"]))
        ifaceConfig["DNS"]!!.split(",").forEach {
            ifaceBuilder.addDnsServer(InetNetwork.parse(it.trim()).address)
        }
        /*val jExcludedApplication = obj.getJSONArray("excludedApps")
    (0 until jExcludedApplication.length()).toList().forEach {
        val appName = jExcludedApplication.get(it).toString()
        ifaceBuilder.excludeApplication(appName)
    }*/
        confBuilder.setInterface(ifaceBuilder.build())

        return confBuilder.build()
    }

    fun getVpnConfig(): JSONObject {
        return mConfig!!
    }

    private fun startOpenVpn() {
        mOpenVPNThreadv3 = OpenVPNThreadv3(this)
        Thread({
            mOpenVPNThreadv3?.run()
        }).start()
    }

    private fun startWireGuard() {
        val wireguard_conf = buildWireugardConfig(mConfig!!)
        if (currentTunnelHandle != -1) {
            Log.e(tag, "Tunnel already up")
            // Turn the tunnel down because this might be a switch
            wgTurnOff(currentTunnelHandle)
        }
        val wgConfig: String = wireguard_conf!!.toWgUserspaceString()
        val builder = Builder()
        setupBuilder(wireguard_conf, builder)
        builder.setSession("avpn0")
        builder.establish().use { tun ->
            if (tun == null) return
            Log.i(tag, "Go backend " + wgVersion())
            currentTunnelHandle = wgTurnOn("avpn0", tun.detachFd(), wgConfig)
        }
        if (currentTunnelHandle < 0) {
            Log.e(tag, "Activation Error Code -> $currentTunnelHandle")
            isUp = false
            return
        }
        protect(wgGetSocketV4(currentTunnelHandle))
        protect(wgGetSocketV6(currentTunnelHandle))
        isUp = true

        // Store the config in case the service gets
        // asked boot vpn from the OS
        val prefs = Prefs.get(this)
        prefs.edit()
            .putString("lastConf", mConfig.toString())
            .apply()
    }

    companion object {
        @JvmStatic
        fun startService(c: Context) {
            c.applicationContext.startService(
                Intent(c.applicationContext, VPNService::class.java).apply {
                    putExtra("startOnly", true)
                })
        }

        @JvmStatic
        private external fun wgGetConfig(handle: Int): String?

        @JvmStatic
        private external fun wgGetSocketV4(handle: Int): Int

        @JvmStatic
        private external fun wgGetSocketV6(handle: Int): Int

        @JvmStatic
        private external fun wgTurnOff(handle: Int)

        @JvmStatic
        private external fun wgTurnOn(ifName: String, tunFd: Int, settings: String): Int

        @JvmStatic
        private external fun wgVersion(): String?
    }
}
