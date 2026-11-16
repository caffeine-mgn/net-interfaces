package pw.binom.net.interfaces

/**
 * A network interface reported by the operating system.
 *
 * Instances are immutable snapshots: call [getAll] again to refresh the state.
 */
public class NetworkInterface internal constructor(
    /** Operating-system interface name, for example `eth0` or `en0`. */
    public val name: String,
    /** Human friendly name when the OS provides one (Windows), otherwise `null`. */
    public val displayName: String?,
    /** OS interface index, or `0` when unavailable. */
    public val index: Int,
    /** Maximum transmission unit in bytes, or `-1` when unavailable. */
    public val mtu: Int,
    public val isUp: Boolean,
    public val isRunning: Boolean,
    public val isLoopback: Boolean,
    public val isPointToPoint: Boolean,
    public val isBroadcast: Boolean,
    public val supportsMulticast: Boolean,
    public val isVirtual: Boolean,
    /** Link-layer (MAC) address, when the interface has one. */
    public val hardwareAddress: MacAddress?,
    addresses: List<InterfaceAddress>,
) {
    /** IP addresses assigned to this interface. */
    public val addresses: List<InterfaceAddress> = addresses.toList()

    /** IP addresses assigned to this interface, without prefix information. */
    public val ipAddresses: List<IpAddress> get() = addresses.map { it.address }

    /**
     * The most useful address of this interface: the first site-local IPv4
     * address, then any IPv4, then any address.
     */
    public val primaryAddress: IpAddress?
        get() = addresses.firstOrNull { it.address.version == IpVersion.V4 && it.address.isSiteLocal }?.address
            ?: addresses.firstOrNull { it.address.version == IpVersion.V4 }?.address
            ?: addresses.firstOrNull()?.address

    /** Returns whether [address] is assigned to this interface. */
    public fun contains(address: IpAddress): Boolean = addresses.any { it.address == address }

    /** Returns whether [host] parses to an address assigned to this interface. */
    public fun contains(host: String): Boolean {
        val address = IpAddress.parse(host) ?: return false
        return contains(address)
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is NetworkInterface &&
                name == other.name &&
                index == other.index &&
                addresses == other.addresses)

    override fun hashCode(): Int = 31 * (31 * name.hashCode() + index) + addresses.hashCode()

    override fun toString(): String =
        "NetworkInterface(name=$name, index=$index, mtu=$mtu, addresses=$addresses)"

    public companion object {
        /**
         * Returns every network interface reported by the operating system,
         * including loopback and interfaces that are down.
         */
        public fun getAll(): List<NetworkInterface> = getAllNetworkInterfaces()

        /**
         * Returns interfaces that are usable right now: up and running, not
         * loopback and with at least one assigned address. This is the list
         * normally used to pick an interface for multicast, service
         * advertisement or peer discovery.
         */
        public fun getAvailable(): List<NetworkInterface> =
            getAll().filter {
                it.isUp && it.isRunning && !it.isLoopback && it.addresses.isNotEmpty()
            }

        /** Finds an interface by OS name or by human friendly display name. */
        public fun findByName(name: String): NetworkInterface? =
            getAll().firstOrNull { it.name == name || it.displayName == name }

        /** Finds an interface by IP literal, or `null` when no interface has it. */
        public fun findByIp(ip: String): NetworkInterface? {
            val address = IpAddress.parse(ip) ?: return null
            return findByAddress(address)
        }

        /** Finds an interface that has [address] assigned. */
        public fun findByAddress(address: IpAddress): NetworkInterface? =
            getAll().firstOrNull { it.contains(address) }

        /** Like [findByIp] but throws when the address is not assigned. */
        public fun getByIp(ip: String): NetworkInterface =
            findByIp(ip) ?: throw IllegalStateException("No network interface has address \"$ip\"")

        /** Like [findByAddress] but throws when the address is not assigned. */
        public fun getByAddress(address: IpAddress): NetworkInterface =
            findByAddress(address)
                ?: throw IllegalStateException("No network interface has address \"$address\"")

        /** Like [findByName] but throws when the interface does not exist. */
        public fun getByName(name: String): NetworkInterface =
            findByName(name) ?: throw IllegalStateException("No network interface named \"$name\"")
    }
}

internal expect fun getAllNetworkInterfaces(): List<NetworkInterface>
