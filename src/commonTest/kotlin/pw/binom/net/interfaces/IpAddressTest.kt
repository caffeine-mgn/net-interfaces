package pw.binom.net.interfaces

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IpAddressTest {

    @Test
    fun createsFromBytes() {
        val v4 = IpAddress.of(byteArrayOf(192.toByte(), 168.toByte(), 0, 1))
        assertEquals(IpVersion.V4, v4.version)
        assertEquals("192.168.0.1", v4.host)

        val v6 = IpAddress.of(ByteArray(16).also { it[15] = 1 })
        assertEquals(IpVersion.V6, v6.version)
        assertEquals("::1", v6.host)
    }

    @Test
    fun rejectsWrongByteCount() {
        assertFailsWith<IllegalArgumentException> { IpAddress.of(ByteArray(5)) }
    }

    @Test
    fun bytesAreDefensiveCopies() {
        val source = byteArrayOf(10, 0, 0, 1)
        val address = IpAddress.of(source)
        source[0] = 1
        assertEquals(10, address.bytes[0].toInt())
    }

    @Test
    fun parsesIpv4() {
        assertEquals("10.20.30.40", IpAddress.parse("10.20.30.40")?.host)
        assertNull(IpAddress.parse("10.20.30"))
        assertNull(IpAddress.parse("10.20.30.256"))
        assertNull(IpAddress.parse("10.20.30.a"))
    }

    @Test
    fun parsesIpv6() {
        assertEquals("::", IpAddress.parse("::")?.host)
        assertEquals("::1", IpAddress.parse("::1")?.host)
        assertEquals("2001:db8::1", IpAddress.parse("2001:db8::1")?.host)
        assertEquals("fe80::1", IpAddress.parse("fe80::1%eth0")?.host)
        assertEquals(
            "2001:db8::1",
            IpAddress.parse("2001:0db8:0000:0000:0000:0000:0000:0001")?.host,
        )
        assertNull(IpAddress.parse("2001:db8::1::2"))
        assertNull(IpAddress.parse("2001:db8:1"))
        assertNull(IpAddress.parse("2001:db8:zzzz::1"))
    }

    @Test
    fun embeddedIpv4() {
        val address = IpAddress.parse("::ffff:192.168.0.1")
        assertEquals(IpVersion.V6, address?.version)
        assertEquals(0xff.toByte(), address?.bytes?.get(10))
        assertEquals(0xff.toByte(), address?.bytes?.get(11))
        assertEquals(192.toByte(), address?.bytes?.get(12))
        assertEquals(1.toByte(), address?.bytes?.get(15))
    }

    @Test
    fun classifiesAddresses() {
        assertTrue(IpAddress.parse("127.0.0.1")!!.isLoopback)
        assertTrue(IpAddress.parse("::1")!!.isLoopback)
        assertFalse(IpAddress.parse("8.8.8.8")!!.isLoopback)

        assertTrue(IpAddress.parse("169.254.10.20")!!.isLinkLocal)
        assertTrue(IpAddress.parse("fe80::1")!!.isLinkLocal)

        assertTrue(IpAddress.parse("10.1.2.3")!!.isSiteLocal)
        assertTrue(IpAddress.parse("172.16.5.5")!!.isSiteLocal)
        assertTrue(IpAddress.parse("192.168.1.1")!!.isSiteLocal)
        assertTrue(IpAddress.parse("fd00::1")!!.isSiteLocal)
        assertFalse(IpAddress.parse("8.8.8.8")!!.isSiteLocal)

        assertTrue(IpAddress.parse("224.0.0.1")!!.isMulticast)
        assertTrue(IpAddress.parse("ff02::1")!!.isMulticast)
    }

    @Test
    fun equalityIgnoresInstance() {
        assertEquals(IpAddress.parse("1.2.3.4"), IpAddress.parse("1.2.3.4"))
        assertEquals(IpAddress.parse("::1")?.hashCode(), IpAddress.parse("0:0:0:0:0:0:0:1")?.hashCode())
    }
}
