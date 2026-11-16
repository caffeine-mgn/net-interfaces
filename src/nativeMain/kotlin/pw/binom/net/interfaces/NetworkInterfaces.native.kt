package pw.binom.net.interfaces

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import netif.NI_FLAG_BROADCAST
import netif.NI_FLAG_LOOPBACK
import netif.NI_FLAG_MULTICAST
import netif.NI_FLAG_POINT_TO_POINT
import netif.NI_FLAG_RUNNING
import netif.NI_FLAG_UP
import netif.NI_MAX_HARDWARE_ADDRESS
import netif.ni_free_interfaces
import netif.ni_get_interfaces

@OptIn(ExperimentalForeignApi::class)
internal actual fun getAllNetworkInterfaces(): List<NetworkInterface> {
    val head = ni_get_interfaces() ?: return emptyList()
    try {
        val result = ArrayList<NetworkInterface>()
        var node: CPointer<netif.NIInterface>? = head
        while (node != null) {
            val native = node.pointed

            val addresses = ArrayList<InterfaceAddress>()
            var addressNode = native.addresses
            while (addressNode != null) {
                val address = addressNode.pointed
                val version = when (address.family) {
                    4 -> IpVersion.V4
                    6 -> IpVersion.V6
                    else -> null
                }
                if (version != null) {
                    val bytes = ByteArray(version.byteCount) { index ->
                        address.bytes[index].toByte()
                    }
                    addresses += InterfaceAddress(
                        address = IpAddress.of(bytes),
                        prefixLength = address.prefix_length,
                    )
                }
                addressNode = address.next
            }

            val hardwareLength = native.hardware_address_length
                .coerceIn(0, NI_MAX_HARDWARE_ADDRESS)
            val hardwareAddress = if (hardwareLength > 0) {
                ByteArray(hardwareLength) { index ->
                    native.hardware_address[index].toByte()
                }
                    // Loopback and some virtual devices report all-zero bytes;
                    // treat that as "no hardware address", like the JVM does.
                    .takeIf { bytes -> bytes.any { it != 0.toByte() } }
                    ?.let(::MacAddress)
            } else {
                null
            }

            val flags = native.flags
            result += NetworkInterface(
                name = native.name?.toKString() ?: "",
                displayName = native.display_name?.toKString(),
                index = native.index,
                mtu = native.mtu,
                isUp = flags and NI_FLAG_UP != 0,
                isRunning = flags and NI_FLAG_RUNNING != 0,
                isLoopback = flags and NI_FLAG_LOOPBACK != 0,
                isPointToPoint = flags and NI_FLAG_POINT_TO_POINT != 0,
                isBroadcast = flags and NI_FLAG_BROADCAST != 0,
                supportsMulticast = flags and NI_FLAG_MULTICAST != 0,
                isVirtual = false,
                hardwareAddress = hardwareAddress,
                addresses = addresses,
            )

            node = native.next
        }
        return result
    } finally {
        ni_free_interfaces(head)
    }
}
