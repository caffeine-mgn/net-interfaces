package pw.binom.net.interfaces

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NetworkInterfaceTest {

    @Test
    fun enumeratesInterfaces() {
        val interfaces = NetworkInterface.getAll()
        println("=== ${interfaces.size} network interface(s) ===")
        interfaces.forEach { network ->
            println(
                "name=${network.name} display=${network.displayName} index=${network.index} " +
                    "mtu=${network.mtu} up=${network.isUp} running=${network.isRunning} " +
                    "loopback=${network.isLoopback} p2p=${network.isPointToPoint} " +
                    "broadcast=${network.isBroadcast} multicast=${network.supportsMulticast} " +
                    "mac=${network.hardwareAddress}",
            )
            network.addresses.forEach { address ->
                println("  ${address.address.host}/${address.prefixLength} netmask=${address.netmask.host}")
            }
        }
        println("=== available ===")
        NetworkInterface.getAvailable().forEach { println("  ${it.name} -> ${it.primaryAddress}") }

        assertTrue(interfaces.isNotEmpty(), "Expected at least one network interface")
        assertTrue(interfaces.all { it.name.isNotEmpty() }, "Every interface must have a name")
        assertTrue(
            interfaces.flatMap { it.addresses }.all { it.prefixLength in 0..it.address.version.bitCount },
            "Every address must have a valid prefix length",
        )
        assertTrue(
            interfaces.any { it.isLoopback && it.contains(IpAddress.LOOPBACK_V4) },
            "Expected a loopback interface with 127.0.0.1",
        )
    }

    @Test
    fun findsLoopback() {
        val loopback = NetworkInterface.findByAddress(IpAddress.LOOPBACK_V4)
        assertNotNull(loopback, "Expected an interface with 127.0.0.1")
        assertTrue(loopback.isLoopback)
        assertTrue(loopback.contains("127.0.0.1"))
    }

    @Test
    fun availableExcludesUnusableInterfaces() {
        val available = NetworkInterface.getAvailable()
        assertTrue(
            available.none { it.isLoopback },
            "getAvailable() must not return loopback interfaces",
        )
        assertTrue(
            available.all { it.isUp && it.isRunning && it.addresses.isNotEmpty() },
            "getAvailable() must only return up, running interfaces with addresses",
        )
    }
}
