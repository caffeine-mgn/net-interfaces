package pw.binom.net.interfaces

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InterfaceAddressTest {

    @Test
    fun computesIpv4Netmask() {
        val address = InterfaceAddress(IpAddress.parse("192.168.1.10")!!, 24)
        assertEquals("255.255.255.0", address.netmask.host)
        assertEquals("192.168.1.255", address.broadcast?.host)
    }

    @Test
    fun computesNonByteAlignedNetmask() {
        val address = InterfaceAddress(IpAddress.parse("10.0.0.1")!!, 20)
        assertEquals("255.255.240.0", address.netmask.host)
        assertEquals("10.0.15.255", address.broadcast?.host)
    }

    @Test
    fun computesIpv6Netmask() {
        val address = InterfaceAddress(IpAddress.parse("fe80::1")!!, 64)
        assertEquals("ffff:ffff:ffff:ffff::", address.netmask.host)
        assertNull(address.broadcast)
    }

    @Test
    fun containsChecksSubnet() {
        val subnet = InterfaceAddress(IpAddress.parse("192.168.1.10")!!, 24)
        assertTrue(subnet.contains(IpAddress.parse("192.168.1.200")!!))
        assertFalse(subnet.contains(IpAddress.parse("192.168.2.1")!!))
        assertFalse(subnet.contains(IpAddress.parse("::1")!!))
    }

    @Test
    fun rejectsInvalidPrefix() {
        assertFailsWith<IllegalArgumentException> {
            InterfaceAddress(IpAddress.parse("1.2.3.4")!!, 33)
        }
        assertFailsWith<IllegalArgumentException> {
            InterfaceAddress(IpAddress.parse("::1")!!, 129)
        }
    }
}
