package pw.binom.net.interfaces

/** Flag bits shared with the native implementation. */
public object RouteFlags {
    public const val DEFAULT: Int = 1
    public const val GATEWAY: Int = 2
    public const val REJECT: Int = 4
    public const val BLACKHOLE: Int = 8
    public const val DYNAMIC: Int = 16
}

/**
 * One entry of the operating system routing table.
 *
 * [destination] together with [prefixLength] describes the destination
 * network; a default route has an all-zero destination and prefix length `0`
 * ([isDefault]). [gateway] is `null` for directly connected routes.
 */
public class Route internal constructor(
    public val destination: IpAddress,
    public val prefixLength: Int,
    public val gateway: IpAddress?,
    /** Operating-system index of the outgoing interface. */
    public val interfaceIndex: Int,
    /** Route metric / preference as reported by the OS (`0` when unknown). */
    public val metric: Int,
    /** Raw platform flag bits, see [RouteFlags]. */
    public val flags: Int,
) {
    init {
        require(prefixLength in 0..destination.version.bitCount) {
            "Invalid prefix length $prefixLength for ${destination.version}"
        }
    }

    /** `true` for the default route (`0.0.0.0/0` or `::/0`). */
    public val isDefault: Boolean get() = flags and RouteFlags.DEFAULT != 0

    /** `true` when the route points at a next-hop gateway. */
    public val isGateway: Boolean get() = flags and RouteFlags.GATEWAY != 0

    /** `true` when the OS explicitly rejects traffic for this destination. */
    public val isReject: Boolean get() = flags and RouteFlags.REJECT != 0

    /** `true` when the route silently drops traffic (blackhole). */
    public val isBlackhole: Boolean get() = flags and RouteFlags.BLACKHOLE != 0

    /** `true` when the route was installed dynamically (RA / DHCP). */
    public val isDynamic: Boolean get() = flags and RouteFlags.DYNAMIC != 0

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is Route &&
                destination == other.destination &&
                prefixLength == other.prefixLength &&
                gateway == other.gateway &&
                interfaceIndex == other.interfaceIndex &&
                metric == other.metric &&
                flags == other.flags)

    override fun hashCode(): Int {
        var result = destination.hashCode()
        result = 31 * result + prefixLength
        result = 31 * result + (gateway?.hashCode() ?: 0)
        result = 31 * result + interfaceIndex
        result = 31 * result + metric
        result = 31 * result + flags
        return result
    }

    override fun toString(): String =
        "Route($destination/$prefixLength, gateway=$gateway, ifIndex=$interfaceIndex, " +
            "metric=$metric, default=$isDefault)"
}

/**
 * Access to the operating system routing table.
 *
 * Everything is read programmatically: netlink on Linux/Android,
 * `sysctl(PF_ROUTE)` on Apple platforms and `GetIpForwardTable2` on Windows.
 * The JVM implementation calls the same C code through a bundled JNI library;
 * no external processes are ever executed.
 */
public object NetworkRouting {
    /** All routes currently known to the operating system. */
    public fun getRoutes(): List<Route> = getAllRoutes()

    /** Default routes only (`0.0.0.0/0` and `::/0`). */
    public fun getDefaultRoutes(): List<Route> = getRoutes().filter { it.isDefault }

    /**
     * The first usable default gateway for [version], or `null` when there is
     * none. Loopback and unspecified next hops (which appear in the `local`
     * routing table on some systems, Android in particular) are skipped.
     *
     * Note that Android and other policy-routing systems may have several
     * default routes in different tables. Use
     * [NetworkInterface.defaultGateways] to ask a specific interface.
     */
    public fun getDefaultGateway(version: IpVersion): IpAddress? =
        getDefaultRoutes()
            .mapNotNull { it.gateway }
            .firstOrNull {
                it.version == version && !it.isLoopback && !it.isUnspecified
            }
}

internal expect fun getAllRoutes(): List<Route>
