package pw.binom.net.interfaces

internal actual fun loadNativeLibrary() {
    System.loadLibrary("netif_jni")
}
