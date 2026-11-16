package pw.binom.net.interfaces

import java.net.NetworkInterface as JavaNetworkInterface

internal actual fun getAllNetworkInterfaces(): List<NetworkInterface> {
    val result = ArrayList<NetworkInterface>()
    val enumeration = try {
        JavaNetworkInterface.getNetworkInterfaces()
    } catch (e: Exception) {
        throw NetworkInterfacesException("Failed to enumerate network interfaces", e)
    } ?: return result

    while (enumeration.hasMoreElements()) {
        val network = enumeration.nextElement()
        val addresses = network.interfaceAddresses.mapNotNull { interfaceAddress ->
            val bytes = interfaceAddress.address.address
            if (bytes.size != 4 && bytes.size != 16) {
                null
            } else {
                InterfaceAddress(
                    address = IpAddress.of(bytes),
                    prefixLength = interfaceAddress.networkPrefixLength.toInt(),
                )
            }
        }
        val name = network.name ?: ""
        result += NetworkInterface(
            name = name,
            displayName = network.displayName?.takeIf { it != name },
            index = safe(0) { network.index },
            mtu = safe(-1) { network.mtu },
            isUp = safe(false) { network.isUp },
            isRunning = safe(false) { network.isUp },
            isLoopback = safe(false) { network.isLoopback },
            isPointToPoint = safe(false) { network.isPointToPoint },
            isBroadcast = addresses.any { it.address.version == IpVersion.V4 },
            supportsMulticast = safe(false) { network.supportsMulticast() },
            isVirtual = safe(false) { network.isVirtual },
            hardwareAddress = safe<ByteArray?>(null) { network.hardwareAddress }
                ?.takeIf { it.isNotEmpty() && it.any { byte -> byte != 0.toByte() } }
                ?.let(::MacAddress),
            addresses = addresses,
        )
    }
    return result
}

private inline fun <T> safe(default: T, block: () -> T): T =
    try {
        block()
    } catch (_: Exception) {
        default
    }
