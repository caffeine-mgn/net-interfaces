package pw.binom.net.interfaces

/**
 * A hardware (MAC) address of a network interface.
 *
 * [bytes] holds the raw octets as reported by the operating system; [formatted]
 * renders them as `aa:bb:cc:dd:ee:ff`.
 */
public class MacAddress(bytes: ByteArray) {
    /** A copy of the raw address octets. */
    public val bytes: ByteArray = bytes.copyOf()

    init {
        require(this.bytes.isNotEmpty()) { "A hardware address cannot be empty" }
    }

    /** Colon-separated, lower-case hexadecimal representation. */
    public val formatted: String = buildString {
        this@MacAddress.bytes.forEachIndexed { index, byte ->
            if (index > 0) append(':')
            append((byte.toInt() and 0xff).toString(16).padStart(2, '0'))
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is MacAddress && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = formatted
}
