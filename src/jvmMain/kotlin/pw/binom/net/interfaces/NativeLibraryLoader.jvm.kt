package pw.binom.net.interfaces

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal actual fun loadNativeLibrary() = NativeLoader.load()

/**
 * Extracts `libnetif_jni` from the jar into a per-user cache and loads it.
 *
 * The SHA-256 of the resource is compared with the on-disk copy before loading,
 * so a pre-planted file at the cache path cannot be used to execute arbitrary
 * code (CWE-426). Extraction is guarded by a per-library lock.
 */
internal object NativeLoader {
    private val VERSION: String =
        NativeLoader::class.java.`package`?.implementationVersion ?: "dev"

    private val loaded = ConcurrentHashMap.newKeySet<String>()
    private val extractLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun load() {
        val target = currentTarget()
        val fileName = currentLibFileName()
        val cached = extractedFile(fileName)
        val lock = extractLocks.computeIfAbsent(fileName) { ReentrantLock() }

        lock.withLock {
            extractIfNeeded(target, fileName, cached)
            if (loaded.add(fileName)) {
                System.load(cached.toAbsolutePath().toString())
            }
        }
    }

    private fun extractIfNeeded(target: String, fileName: String, cached: Path) {
        val expectedHash = readInJarSha256(target, fileName)
        if (Files.exists(cached) && expectedHash.contentEquals(sha256OfFile(cached)) &&
            isSecurelyOwned(cached)
        ) {
            return
        }

        Files.createDirectories(cached.parent, *posixOwnerOnlyAttrs())
        val temp = Files.createTempFile(cached.parent, "netif-", ".tmp")
        try {
            writeFromJar(target, fileName, temp)
            if (!expectedHash.contentEquals(sha256OfFile(temp))) {
                error("Native library integrity check failed after extraction")
            }
            tightenPermissions(temp)
            Files.move(
                temp,
                cached,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun readInJarSha256(target: String, fileName: String): ByteArray {
        val path = "/$target/$fileName"
        val stream = NativeLoader::class.java.getResourceAsStream(path)
            ?: error("Native library not found in jar at $path")
        return stream.use(::sha256OfStream)
    }

    private fun writeFromJar(target: String, fileName: String, destination: Path) {
        val path = "/$target/$fileName"
        val stream = NativeLoader::class.java.getResourceAsStream(path)
            ?: error("Native library not found in jar at $path")
        stream.use { input ->
            Files.newOutputStream(destination).use { output -> input.copyTo(output) }
        }
    }

    private fun sha256OfFile(path: Path): ByteArray =
        Files.newInputStream(path).use(::sha256OfStream)

    private fun sha256OfStream(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var read = input.read(buffer)
        while (read >= 0) {
            if (read > 0) digest.update(buffer, 0, read)
            read = input.read(buffer)
        }
        return digest.digest()
    }

    private fun tightenPermissions(file: Path) {
        try {
            file.toFile().apply {
                setReadable(true, true)
                setWritable(true, true)
                setExecutable(true, true)
            }
        } catch (_: SecurityException) {
            // best effort
        }
        try {
            Files.setPosixFilePermissions(
                file,
                EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        } catch (_: UnsupportedOperationException) {
            // not a POSIX file system
        }
    }

    private fun isSecurelyOwned(file: Path): Boolean = try {
        val allowed = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        Files.getPosixFilePermissions(file).all { it in allowed }
    } catch (_: UnsupportedOperationException) {
        true
    } catch (_: java.io.IOException) {
        false
    }

    private fun posixOwnerOnlyAttrs(): Array<FileAttribute<*>> = try {
        arrayOf(
            PosixFilePermissions.asFileAttribute(
                EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            ),
        )
    } catch (_: UnsupportedOperationException) {
        emptyArray()
    }

    private fun currentTarget(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("linux") && (arch.contains("amd64") || arch == "x86_64") -> "linux_x64"
            os.contains("linux") && (arch.contains("aarch64") || arch.contains("arm64")) -> "linux_arm64"
            os.contains("mac") && (arch.contains("amd64") || arch == "x86_64") -> "macos_x64"
            os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64")) -> "macos_arm64"
            os.contains("windows") && (arch.contains("amd64") || arch == "x86_64") -> "mingw_x64"
            else -> error("Unsupported OS/arch: $os / $arch")
        }
    }

    private fun currentLibFileName(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("windows") -> "libnetif_jni.dll"
            os.contains("mac") -> "libnetif_jni.dylib"
            else -> "libnetif_jni.so"
        }
    }

    private fun extractedFile(fileName: String): Path {
        val base = System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir")
        val os = System.getProperty("os.name").lowercase()
        val subdir = if (os.contains("windows")) "net-interfaces" else ".cache/net-interfaces"
        return Path.of(base, subdir, VERSION, fileName)
    }
}
