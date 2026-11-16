package pw.binom.net.interfaces

import kotlin.test.Test
import kotlin.test.assertTrue

class NetworkRoutingTest {

    @Test
    fun readsRoutingTable() {
        val routes = NetworkRouting.getRoutes()
        println("=== ${routes.size} route(s) ===")
        routes.forEach { println(it) }
        assertTrue(
            routes.all { it.prefixLength in 0..it.destination.version.bitCount },
            "Every route must have a valid prefix length",
        )
        assertTrue(
            routes.all { route -> route.gateway == null || route.gateway.version == route.destination.version },
            "A route gateway must match the destination address family",
        )
    }

    @Test
    fun readsDefaultRoutes() {
        val defaults = NetworkRouting.getDefaultRoutes()
        println("=== ${defaults.size} default route(s) ===")
        defaults.forEach { println(it) }

        assertTrue(defaults.all { it.prefixLength == 0 }, "Default routes must have prefix 0")
        assertTrue(
            defaults.all { it.gateway == null || it.isGateway },
            "A non-null gateway must be reported as a gateway route",
        )

        println("default IPv4 gateway: ${NetworkRouting.getDefaultGateway(IpVersion.V4)}")
        println("default IPv6 gateway: ${NetworkRouting.getDefaultGateway(IpVersion.V6)}")
    }

    @Test
    fun resolvesGatewaysPerInterface() {
        NetworkInterface.getAll()
            .filter { NetworkRouting.getDefaultRoutes().any { route -> route.interfaceIndex == it.index } }
            .forEach { network ->
                println("${network.name} (index=${network.index}) gateways=${network.defaultGateways()}")
            }
        NetworkInterface.getAvailable().forEach { network ->
            val gateways = network.defaultGateways()
            assertTrue(
                gateways.all { it.version == IpVersion.V4 || it.version == IpVersion.V6 },
                "Every gateway must be an IPv4 or IPv6 address",
            )
        }
    }
}
