package pw.binom.net.interfaces

/**
 * Loads the bundled `libnetif_jni` shared library into the current process.
 *
 * Desktop JVM unpacks the platform `.so` / `.dylib` / `.dll` from the jar and
 * loads it from a per-user cache ([NativeLoader]); Android loads the
 * ABI-matched `libnetif_jni.so` packaged in the AAR's `jniLibs` through the
 * system linker by its logical name.
 */
internal expect fun loadNativeLibrary()
