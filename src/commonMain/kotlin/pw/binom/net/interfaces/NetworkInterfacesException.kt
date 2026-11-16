package pw.binom.net.interfaces

/** Thrown when the platform fails to enumerate network interfaces. */
public class NetworkInterfacesException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
