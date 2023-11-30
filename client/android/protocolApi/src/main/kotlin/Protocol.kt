package org.amnezia.vpn.protocol

import android.annotation.SuppressLint
import android.content.Context
import android.net.IpPrefix
import android.net.VpnService
import android.net.VpnService.Builder
import android.os.Build
import android.system.OsConstants
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.MutableStateFlow
import org.amnezia.vpn.util.Log
import org.amnezia.vpn.util.net.InetNetwork
import org.amnezia.vpn.util.net.IpRange
import org.amnezia.vpn.util.net.IpRangeSet
import org.json.JSONObject

private const val TAG = "Protocol"

const val VPN_SESSION_NAME = "AmneziaVPN"

private const val SPLIT_TUNNEL_DISABLE = 0
private const val SPLIT_TUNNEL_INCLUDE = 1
private const val SPLIT_TUNNEL_EXCLUDE = 2

abstract class Protocol {

    abstract val statistics: Statistics
    protected lateinit var state: MutableStateFlow<ProtocolState>

    open fun initialize(context: Context, state: MutableStateFlow<ProtocolState>) {
        this.state = state
    }

    abstract fun startVpn(config: JSONObject, vpnBuilder: Builder, protect: (Int) -> Boolean)

    abstract fun stopVpn()

    protected fun ProtocolConfig.Builder.configSplitTunnel(config: JSONObject) {
        val splitTunnelType = config.optInt("splitTunnelType")
        if (splitTunnelType == SPLIT_TUNNEL_DISABLE) return
        val splitTunnelSites = config.getJSONArray("splitTunnelSites")
        when (splitTunnelType) {
            SPLIT_TUNNEL_INCLUDE -> {
                // remove default routes, if any
                removeRoute(InetNetwork("0.0.0.0", 0))
                removeRoute(InetNetwork("::", 0))
                // add routes from config
                for (i in 0 until splitTunnelSites.length()) {
                    val address = InetNetwork.parse(splitTunnelSites.getString(i))
                    addRoute(address)
                }
            }

            SPLIT_TUNNEL_EXCLUDE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // exclude routes from config
                    for (i in 0 until splitTunnelSites.length()) {
                        val address = InetNetwork.parse(splitTunnelSites.getString(i))
                        excludeRoute(address)
                    }
                } else {
                    // For older versions of Android, build a list of subnets without excluded addresses
                    val ipRangeSet = IpRangeSet()
                    ipRangeSet.remove(IpRange("127.0.0.0", 8))
                    for (i in 0 until splitTunnelSites.length()) {
                        val address = InetNetwork.parse(splitTunnelSites.getString(i))
                        ipRangeSet.remove(IpRange(address))
                    }
                    // remove default routes, if any
                    removeRoute(InetNetwork("0.0.0.0", 0))
                    removeRoute(InetNetwork("::", 0))
                    ipRangeSet.subnets().forEach(::addRoute)
                    addRoute(InetNetwork("2000::", 3))
                }
            }
        }
    }

    protected open fun buildVpnInterface(config: ProtocolConfig, vpnBuilder: Builder) {
        vpnBuilder.setSession(VPN_SESSION_NAME)

        for (addr in config.addresses) vpnBuilder.addAddress(addr)

        for (addr in config.dnsServers) vpnBuilder.addDnsServer(addr)
        // fix for Samsung android ignoring DNS servers outside the VPN route range
        if (Build.BRAND == "samsung") {
            for (addr in config.dnsServers) vpnBuilder.addRoute(InetNetwork(addr))
        }

        config.searchDomain?.let { vpnBuilder.addSearchDomain(it) }

        for (addr in config.routes) vpnBuilder.addRoute(addr)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            for (addr in config.excludedRoutes) vpnBuilder.excludeRoute(addr)

        for (app in config.excludedApplications) vpnBuilder.addDisallowedApplication(app)

        vpnBuilder.setMtu(config.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            config.httpProxy?.let { vpnBuilder.setHttpProxy(it) }

        if (config.allowAllAF) {
            vpnBuilder.allowFamily(OsConstants.AF_INET)
            vpnBuilder.allowFamily(OsConstants.AF_INET6)
        }

        vpnBuilder.setBlocking(config.blockingMode)
        vpnBuilder.setUnderlyingNetworks(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            vpnBuilder.setMetered(false)
    }

    companion object {
        private fun extractLibrary(context: Context, libraryName: String, destination: File): Boolean {
            Log.d(TAG, "Extracting library: $libraryName")
            val apks = hashSetOf<String>()
            context.applicationInfo.run {
                sourceDir?.let { apks += it }
                splitSourceDirs?.let { apks += it }
            }
            for (abi in Build.SUPPORTED_ABIS) {
                for (apk in apks) {
                    ZipFile(File(apk), ZipFile.OPEN_READ).use { zipFile ->
                        val mappedName = System.mapLibraryName(libraryName)
                        val libraryZipPath = listOf("lib", abi, mappedName).joinToString(File.separator)
                        val zipEntry = zipFile.getEntry(libraryZipPath)
                        zipEntry?.let {
                            Log.d(TAG, "Extracting apk:/$libraryZipPath to ${destination.absolutePath}")
                            FileOutputStream(destination).use { outStream ->
                                zipFile.getInputStream(zipEntry).use { inStream ->
                                    inStream.copyTo(outStream, 32 * 1024)
                                    outStream.fd.sync()
                                }
                            }
                        }
                        return true
                    }
                }
            }
            return false
        }

        @SuppressLint("UnsafeDynamicallyLoadedCode")
        fun loadSharedLibrary(context: Context, libraryName: String) {
            Log.d(TAG, "Loading library: $libraryName")
            try {
                System.loadLibrary(libraryName)
                return
            } catch (_: UnsatisfiedLinkError) {
                Log.d(TAG, "Failed to load library, try to extract it from apk")
            }
            var tempFile: File? = null
            try {
                tempFile = File.createTempFile("lib", ".so", context.codeCacheDir)
                if (extractLibrary(context, libraryName, tempFile)) {
                    System.load(tempFile.absolutePath)
                    return
                }
            } catch (e: Exception) {
                throw LoadLibraryException("Failed to load library apk: $libraryName", e)
            } finally {
                tempFile?.delete()
            }
        }
    }
}

private fun VpnService.Builder.addAddress(addr: InetNetwork) = addAddress(addr.address, addr.mask)
private fun VpnService.Builder.addRoute(addr: InetNetwork) = addRoute(addr.address, addr.mask)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun VpnService.Builder.excludeRoute(addr: InetNetwork) = excludeRoute(IpPrefix(addr.address, addr.mask))
