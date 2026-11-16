# Keep the JNI entry point: its name is resolved dynamically by the JVM and
# must not be renamed or removed by R8/ProGuard in consuming applications.
-keepclasseswithmembernames class pw.binom.net.interfaces.NativeRouting {
    native <methods>;
}
