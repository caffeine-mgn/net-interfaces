package pw.binom.net.interfaces

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import netif.NI_ROUTE_FLAG_GATEWAY
import netif.ni_free_routes
import netif.ni_get_routes

@OptIn(ExperimentalForeignApi::class)
internal actual fun getAllRoutes(): List<Route> {
    val head = ni_get_routes() ?: return emptyList()
    try {
        val result = ArrayList<Route>()
        var node: CPointer<netif.NIRoute>? = head
        while (node != null) {
            val native = node.pointed
            val version = when (native.family) {
                4 -> IpVersion.V4
                6 -> IpVersion.V6
                else -> null
            }
            if (version != null) {
                val byteCount = version.byteCount
                val destination = IpAddress.of(
                    ByteArray(byteCount) { index -> native.destination[index].toByte() },
                )
                val gateway = if (native.flags and NI_ROUTE_FLAG_GATEWAY != 0) {
                    IpAddress.of(
                        ByteArray(byteCount) { index -> native.gateway[index].toByte() },
                    )
                } else {
                    null
                }
                result += Route(
                    destination = destination,
                    prefixLength = native.prefix_length,
                    gateway = gateway,
                    interfaceIndex = native.interface_index,
                    metric = native.metric,
                    flags = native.flags,
                )
            }
            node = native.next
        }
        return result
    } finally {
        ni_free_routes(head)
    }
}
