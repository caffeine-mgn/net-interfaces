package pw.binom.net.interfaces

/**
 * An IP address assigned to a [NetworkInterface] together with its subnet
 * prefix length.
 */
public class InterfaceAddress(
    public val address: IpAddress,
    public val prefixLength: Int,
) {
    init {
        require(prefixLength in 0..address.version.bitCount) {
            "Invalid prefix length $prefixLength for ${address.version}"
        }
    }

    /** The subnet mask implied by [prefixLength]. */
    public val netmask: IpAddress
        get() {
            val bytes = ByteArray(address.version.byteCount)
            var remainingBits = prefixLength
            for (index in bytes.indices) {
                bytes[index] = when {
                    remainingBits >= 8 -> {
                        remainingBits -= 8
                        0xff.toByte()
                    }

                    remainingBits > 0 -> {
                        val value = (0xff shl (8 - remainingBits)) and 0xff
                        remainingBits = 0
                        value.toByte()
                    }

                    else -> 0
                }
            }
            return IpAddress.of(bytes)
        }

    /**
     * The directed broadcast address for this subnet, or `null` for IPv6
     * (IPv6 has no broadcast).
     */
    public val broadcast: IpAddress?
        get() {
            if (address.version != IpVersion.V4) return null
            val addressBytes = address.bytes
            val maskBytes = netmask.bytes
            return IpAddress.of(
                ByteArray(4) { index ->
                    ((addressBytes[index].toInt() and 0xff) or
                        (maskBytes[index].toInt().inv() and 0xff)).toByte()
                },
            )
        }

    /** Returns whether [other] belongs to the same subnet as [address]. */
    public fun contains(other: IpAddress): Boolean {
        if (other.version != address.version) return false
        val addressBytes = address.bytes
        val otherBytes = other.bytes
        val maskBytes = netmask.bytes
        for (index in addressBytes.indices) {
            if (((addressBytes[index].toInt() xor otherBytes[index].toInt()) and
                    (maskBytes[index].toInt() and 0xff)) != 0) {
                return false
            }
        }
        return true
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is InterfaceAddress && address == other.address && prefixLength == other.prefixLength)

    override fun hashCode(): Int = 31 * address.hashCode() + prefixLength

    override fun toString(): String = "$address/$prefixLength"
}
