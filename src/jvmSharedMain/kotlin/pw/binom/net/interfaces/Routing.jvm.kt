package pw.binom.net.interfaces

private const val RECORD_SIZE = 44
private const val OFFSET_FAMILY = 0
private const val OFFSET_PREFIX = 1
private const val OFFSET_FLAGS = 2
private const val OFFSET_INTERFACE_INDEX = 4
private const val OFFSET_METRIC = 8
private const val OFFSET_DESTINATION = 12
private const val OFFSET_GATEWAY = 28

/**
 * JNI bridge to the same C routing code used by the Kotlin/Native targets.
 * The native library is bundled in the jar (desktop) or AAR (`jniLibs`) and
 * loaded once by [loadNativeLibrary].
 */
internal object NativeRouting {
    init {
        loadNativeLibrary()
    }

    /** Returns the serialized routing table, or `null` on failure. */
    external fun nativeGetRoutes(): ByteArray?

    fun getRoutes(): List<Route> {
        val data = try {
            nativeGetRoutes()
        } catch (e: UnsatisfiedLinkError) {
            throw NetworkInterfacesException("Failed to load the native routing library", e)
        } ?: return emptyList()

        if (data.size < RECORD_SIZE || data.size % RECORD_SIZE != 0) {
            return emptyList()
        }

        val result = ArrayList<Route>(data.size / RECORD_SIZE)
        var offset = 0
        while (offset + RECORD_SIZE <= data.size) {
            val version = when (data[offset + OFFSET_FAMILY].toInt() and 0xff) {
                4 -> IpVersion.V4
                6 -> IpVersion.V6
                else -> null
            }
            if (version != null) {
                val prefixLength = data[offset + OFFSET_PREFIX].toInt() and 0xff
                val flags = (data[offset + OFFSET_FLAGS].toInt() and 0xff) or
                    ((data[offset + OFFSET_FLAGS + 1].toInt() and 0xff) shl 8)
                val interfaceIndex = readInt32(data, offset + OFFSET_INTERFACE_INDEX)
                val metric = readInt32(data, offset + OFFSET_METRIC)
                val addressLength = version.byteCount
                val destination = IpAddress.of(
                    data.copyOfRange(
                        offset + OFFSET_DESTINATION,
                        offset + OFFSET_DESTINATION + addressLength,
                    ),
                )
                val gateway = if (flags and RouteFlags.GATEWAY != 0) {
                    IpAddress.of(
                        data.copyOfRange(
                            offset + OFFSET_GATEWAY,
                            offset + OFFSET_GATEWAY + addressLength,
                        ),
                    )
                } else {
                    null
                }
                if (prefixLength in 0..version.bitCount) {
                    result += Route(
                        destination = destination,
                        prefixLength = prefixLength,
                        gateway = gateway,
                        interfaceIndex = interfaceIndex,
                        metric = metric,
                        flags = flags,
                    )
                }
            }
            offset += RECORD_SIZE
        }
        return result
    }

    private fun readInt32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            ((data[offset + 3].toInt() and 0xff) shl 24)
}

internal actual fun getAllRoutes(): List<Route> = NativeRouting.getRoutes()
