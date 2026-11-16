package pw.binom.net.interfaces

/** IP protocol version. */
public enum class IpVersion(public val byteCount: Int) {
    V4(4),
    V6(16),
    ;

    /** Number of bits in an address of this version. */
    public val bitCount: Int get() = byteCount * 8
}

/**
 * An immutable IP address (IPv4 or IPv6).
 *
 * Create one with [IpAddress.of] from raw bytes or [IpAddress.parse] from its
 * textual representation. The textual form follows the usual conventions:
 * dotted decimal for IPv4 and RFC 5952 compression for IPv6.
 */
public class IpAddress private constructor(
    private val raw: ByteArray,
    public val version: IpVersion,
) {
    /** A copy of the raw address bytes (4 for IPv4, 16 for IPv6). */
    public val bytes: ByteArray get() = raw.copyOf()

    internal fun rawBytes(): ByteArray = raw

    /** Textual representation of the address. */
    public val host: String = when (version) {
        IpVersion.V4 -> formatIpv4(raw)
        IpVersion.V6 -> formatIpv6(raw)
    }

    /** Returns whether this is a loopback address (127.0.0.0/8 or ::1). */
    public val isLoopback: Boolean
        get() = when (version) {
            IpVersion.V4 -> raw[0] == 127.toByte()
            IpVersion.V6 -> isIpv6Loopback()
        }

    /** Returns whether this is a link-local address (169.254.0.0/16 or fe80::/10). */
    public val isLinkLocal: Boolean
        get() = when (version) {
            IpVersion.V4 -> raw[0] == 169.toByte() && raw[1] == 254.toByte()
            IpVersion.V6 -> (raw[0].toInt() and 0xff) == 0xfe && ((raw[1].toInt() and 0xc0) == 0x80)
        }

    /**
     * Returns whether this is a private / site-local address:
     * 10/8, 172.16/12, 192.168/16 or an IPv6 unique local address (fc00::/7).
     */
    public val isSiteLocal: Boolean
        get() = when (version) {
            IpVersion.V4 -> {
                val a = raw[0].toInt() and 0xff
                val b = raw[1].toInt() and 0xff
                a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
            }

            IpVersion.V6 -> (raw[0].toInt() and 0xfe) == 0xfc
        }

    /** Returns whether this is the unspecified address (`0.0.0.0` or `::`). */
    public val isUnspecified: Boolean
        get() = raw.all { it == 0.toByte() }

    /** Returns whether this is a multicast address. */
    public val isMulticast: Boolean
        get() = when (version) {
            IpVersion.V4 -> (raw[0].toInt() and 0xf0) == 0xe0
            IpVersion.V6 -> raw[0] == 0xff.toByte()
        }

    override fun equals(other: Any?): Boolean =
        this === other || (other is IpAddress && version == other.version && raw.contentEquals(other.raw))

    override fun hashCode(): Int = 31 * version.hashCode() + raw.contentHashCode()

    override fun toString(): String = host

    private fun isIpv6Loopback(): Boolean {
        for (index in 0 until 15) {
            if (raw[index] != 0.toByte()) return false
        }
        return raw[15] == 1.toByte()
    }

    public companion object {
        public val LOOPBACK_V4: IpAddress = of(byteArrayOf(127, 0, 0, 1))
        public val LOOPBACK_V6: IpAddress = of(ByteArray(16).also { it[15] = 1 })

        /**
         * Creates an address from 4 (IPv4) or 16 (IPv6) raw bytes.
         *
         * @throws IllegalArgumentException when [bytes] has an unsupported size.
         */
        public fun of(bytes: ByteArray): IpAddress = when (bytes.size) {
            4 -> IpAddress(bytes.copyOf(), IpVersion.V4)
            16 -> IpAddress(bytes.copyOf(), IpVersion.V6)
            else -> throw IllegalArgumentException("An IP address must be 4 or 16 bytes, got ${bytes.size}")
        }

        /**
         * Parses an IPv4 or IPv6 literal. Returns `null` when [host] is not a
         * valid address literal. No name resolution is performed.
         */
        public fun parse(host: String): IpAddress? {
            val value = host.trim()
            if (value.isEmpty()) return null
            return if (value.contains(':')) parseIpv6(value) else parseIpv4(value)
        }
    }
}

private fun formatIpv4(bytes: ByteArray): String = buildString(15) {
    bytes.forEachIndexed { index, byte ->
        if (index > 0) append('.')
        append(byte.toInt() and 0xff)
    }
}

private fun formatIpv6(bytes: ByteArray): String {
    val groups = IntArray(8) {
        ((bytes[it * 2].toInt() and 0xff) shl 8) or (bytes[it * 2 + 1].toInt() and 0xff)
    }

    var bestStart = -1
    var bestLength = 0
    var index = 0
    while (index < groups.size) {
        if (groups[index] == 0) {
            var end = index
            while (end < groups.size && groups[end] == 0) end++
            val length = end - index
            if (length > bestLength) {
                bestLength = length
                bestStart = index
            }
            index = end
        } else {
            index++
        }
    }

    val result = StringBuilder(39)
    if (bestLength >= 2) {
        var group = 0
        while (group < bestStart) {
            if (group > 0) result.append(':')
            result.append(groups[group].toString(16))
            group++
        }
        result.append("::")
        group = bestStart + bestLength
        var first = true
        while (group < groups.size) {
            if (!first) result.append(':')
            result.append(groups[group].toString(16))
            first = false
            group++
        }
    } else {
        groups.forEachIndexed { groupIndex, value ->
            if (groupIndex > 0) result.append(':')
            result.append(value.toString(16))
        }
    }
    return result.toString()
}

private fun parseIpv4(value: String): IpAddress? {
    val parts = value.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    parts.forEachIndexed { index, part ->
        if (part.isEmpty() || part.length > 3) return null
        if (!part.all { it in '0'..'9' }) return null
        val octet = part.toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        bytes[index] = octet.toByte()
    }
    return IpAddress.of(bytes)
}

private fun parseIpv6(value: String): IpAddress? {
    var text = value
    if (text.startsWith('[') && text.endsWith(']')) {
        text = text.substring(1, text.length - 1)
    }
    val zoneIndex = text.indexOf('%')
    if (zoneIndex >= 0) {
        text = text.substring(0, zoneIndex)
    }
    if (text.isEmpty()) return null

    val doubleColon = text.indexOf("::")
    if (doubleColon >= 0 && doubleColon != text.lastIndexOf("::")) return null

    val headPart: String
    val tailPart: String
    if (doubleColon >= 0) {
        headPart = text.substring(0, doubleColon)
        tailPart = text.substring(doubleColon + 2)
    } else {
        headPart = text
        tailPart = ""
    }

    val head = parseIpv6Groups(headPart) ?: return null
    val tail = parseIpv6Groups(tailPart) ?: return null
    val total = head.size + tail.size

    val groups = ArrayList<Int>(8)
    if (doubleColon >= 0) {
        if (total > 8) return null
        groups.addAll(head)
        repeat(8 - total) { groups.add(0) }
        groups.addAll(tail)
    } else {
        if (total != 8) return null
        groups.addAll(head)
    }

    val bytes = ByteArray(16)
    groups.forEachIndexed { index, group ->
        bytes[index * 2] = (group ushr 8).toByte()
        bytes[index * 2 + 1] = group.toByte()
    }
    return IpAddress.of(bytes)
}

private fun parseIpv6Groups(part: String): List<Int>? {
    if (part.isEmpty()) return emptyList()
    val tokens = part.split(':')
    val result = ArrayList<Int>(tokens.size)
    tokens.forEachIndexed { index, token ->
        if (token.isEmpty()) return null
        if (token.contains('.')) {
            if (index != tokens.lastIndex) return null
            val ipv4 = parseIpv4(token) ?: return null
            val bytes = ipv4.rawBytes()
            result.add(((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff))
            result.add(((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff))
        } else {
            if (token.length > 4) return null
            if (!token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            result.add(token.toIntOrNull(16) ?: return null)
        }
    }
    return result
}
