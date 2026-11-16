import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import pw.binom.kotlin.clang.BuildDynamicTask
import pw.binom.kotlin.clang.KnClangExtension
import pw.binom.kotlin.clang.clangBuildDynamic
import pw.binom.kotlin.clang.clangBuildStatic
import java.io.File
import java.net.URI
import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kn.clang)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

allprojects {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

group = "pw.binom.net"
// Release tags drive the version; `-Pversion=` overrides it for dry runs.
version = providers.gradleProperty("version").orNull
    ?: System.getenv("GITHUB_REF_NAME")
    ?: "1.0.0-SNAPSHOT"

// The signing plugin is applied explicitly: vanniktech's signAllPublications()
// creates the sign*Publication tasks, but in this configuration it does not
// trigger the project's `pluginManager.withPlugin("signing")` callback, so the
// signatory used to stay unconfigured ("no configured signatory").
apply(plugin = "signing")

val useGpgSigning = providers.gradleProperty("signingUseGpg").orNull == "true"
val inMemorySigningKey = providers.gradleProperty("signingInMemoryKey").orNull
    ?: providers.gradleProperty("binom.gpg.private_key").orNull
val inMemorySigningKeyId = providers.gradleProperty("signingInMemoryKeyId").orNull
    ?: providers.gradleProperty("binom.gpg.key_id").orNull
val inMemorySigningPassword = providers.gradleProperty("signingInMemoryKeyPassword").orNull
    ?: providers.gradleProperty("binom.gpg.password").orNull
val inMemorySigningBase64 =
    providers.gradleProperty("signingInMemoryKeyIsBase64").orNull?.toBoolean() ?: false
val signingConfigured =
    useGpgSigning || (inMemorySigningKey != null &&
        inMemorySigningKeyId != null &&
        inMemorySigningPassword != null)

extensions.configure<SigningExtension>("signing") {
    when {
        useGpgSigning -> {
            useGpgCmd()
            logger.lifecycle(
                "[signing] Using system gpg via signing.gnupg.keyName=" +
                    "${findProperty("signing.gnupg.keyName")}",
            )
        }

        signingConfigured -> {
            val decodedKey = if (inMemorySigningBase64) {
                String(Base64.getDecoder().decode(inMemorySigningKey))
            } else {
                requireNotNull(inMemorySigningKey).replace("\\n", "\n")
            }
            logger.lifecycle(
                "[signing] Using in-memory PGP key, length=${decodedKey.length}, " +
                    "isBase64=$inMemorySigningBase64",
            )
            useInMemoryPgpKeys(decodedKey, inMemorySigningKeyId, inMemorySigningPassword)
        }

        else -> logger.lifecycle(
            "[signing] No signing credentials configured; publications will not be signed.",
        )
    }
}

val nativeSources = file("src/nativeMain/c")

val knClang = extensions.getByType(KnClangExtension::class.java)
knClang.konanVersion.set(libs.versions.kotlin.get())

kotlin {
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
            }
        }
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    linuxX64()
    linuxArm64()
    mingwX64()

    androidNativeArm32()
    androidNativeArm64()
    androidNativeX64()
    androidNativeX86()

    macosArm64()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    tvosArm64()
    tvosSimulatorArm64()
    watchosArm32()
    watchosArm64()
    watchosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // java.net.NetworkInterface is available on both desktop JVM and
        // Android (JVM), so both targets share one implementation.
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmSharedMain)
        androidMain.get().dependsOn(jvmSharedMain)

        // Every Kotlin/Native target shares the cinterop-based implementation.
        val nativeMain by creating {
            dependsOn(commonMain.get())
        }
        val nativeTest by creating {
            dependsOn(commonTest.get())
        }

        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

// ---------------------------------------------------------------------------
// Native implementation.
//
// Kotlin/Native's bundled platform libraries expose `getifaddrs` on neither
// Android nor (portably) on Windows, so the per-platform enumeration lives in
// a tiny C library compiled with the Kotlin/Native Clang toolchain (kn-clang)
// and imported through cinterop. One header -> one API for every native target.
// ---------------------------------------------------------------------------
kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
    val target = this

    compilations.getByName("main").defaultSourceSet.dependsOn(kotlin.sourceSets.getByName("nativeMain"))
    compilations.getByName("test").defaultSourceSet.dependsOn(kotlin.sourceSets.getByName("nativeTest"))

    val buildNative = clangBuildStatic(target = target.konanTarget, name = "netif") {
        include(nativeSources)
        compileDir(sourceDir = nativeSources)
    }

    compilations.getByName("main").apply {
        cinterops {
            val netif by creating {
                definitionFile.set(project.file("src/nativeInterop/cinterop/netif.def"))
                packageName = "netif"
                includeDirs.headerFilterOnly(nativeSources)
            }
        }

        compileTaskProvider.configure {
            // The static archive is baked into the produced klib via
            // -include-binary. Declaring it as an input makes Kotlin recompile
            // (and re-embed) whenever the C sources change; without this the
            // compile task stays UP-TO-DATE and keeps a stale libnetif.a.
            dependsOn(buildNative)
            inputs.file(buildNative.staticFile.get().asFile)
            compilerOptions.freeCompilerArgs.addAll(
                "-include-binary",
                buildNative.staticFile.get().asFile.absolutePath,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// JNI bridge for the JVM/Android routing API.
//
// The same C sources (netif.c + netif_routes.c) are compiled a second time as
// a shared JNI library for the JVM host targets and for every Android ABI.
// Desktop JVM unpacks the right library from the jar; Android loads it from
// the AAR's jniLibs. No external processes are involved anywhere.
// ---------------------------------------------------------------------------
val jniSources = file("src/jvmMain/c")
val jdkInclude = File(System.getProperty("java.home"), "include")
val jniHeaderStagingDir = layout.buildDirectory.dir("jni-include")

/**
 * A host JDK ships `jni_md.h` only for its own platform. When cross-compiling
 * the JVM library (e.g. mingw from Linux) the missing platform header is
 * downloaded from OpenJDK into build/jni-include.
 */
val provisionJniHeaders = tasks.register("provisionJniHeaders") {
    group = "build"
    description = "Stages jni_md.h headers needed to cross-build the JNI library"
    val base = "https://raw.githubusercontent.com/openjdk/jdk21u/master/src/java.base"
    val headers = mapOf(
        "linux" to "$base/unix/native/include/jni_md.h",
        "win32" to "$base/windows/native/include/jni_md.h",
    ).map { (platform, url) ->
        jniHeaderStagingDir.get().file("$platform/jni_md.h").asFile to url
    }
    outputs.files(headers.map { it.first })
    doLast {
        headers.forEach { (target, url) ->
            if (!target.exists()) {
                logger.lifecycle("[netif] staging ${target.name} from OpenJDK")
                target.parentFile.mkdirs()
                URI(url).toURL().openStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}

fun jniPlatformDir(target: KonanTarget): File? {
    val platform = when (target.family) {
        Family.LINUX -> "linux"
        Family.MINGW -> "win32"
        Family.OSX -> "darwin"
        else -> return null
    }
    val hostDir = File(jdkInclude, platform)
    if (File(hostDir, "jni_md.h").isFile) return hostDir
    return File(jniHeaderStagingDir.get().asFile, platform)
}

fun BuildDynamicTask.configureJni(target: KonanTarget, useJdkHeaders: Boolean) {
    include(nativeSources)
    include(jniSources)
    compileDir(sourceDir = nativeSources)
    compileDir(sourceDir = jniSources)
    if (target.family == Family.MINGW) {
        linkArgs("-liphlpapi", "-lws2_32")
    }
    if (target == KonanTarget.ANDROID_ARM64 || target == KonanTarget.ANDROID_X64) {
        // Google Play requires 16 KB page-size compatible 64-bit libraries
        // for apps targeting Android 15+.
        linkArgs("-Wl,-z,max-page-size=16384")
    }
    if (useJdkHeaders) {
        val platformDir = jniPlatformDir(target)
        include(jdkInclude)
        if (platformDir != null) include(platformDir)
        onlyIf("JNI headers for ${target.name}") {
            File(jdkInclude, "jni.h").isFile &&
                (platformDir == null || File(platformDir, "jni_md.h").isFile)
        }
    }
}

val currentHost = HostManager.host
val isMacHost = currentHost == KonanTarget.MACOS_X64 || currentHost == KonanTarget.MACOS_ARM64
val jvmJniTargets = buildList {
    add(KonanTarget.LINUX_X64)
    add(KonanTarget.LINUX_ARM64)
    add(KonanTarget.MINGW_X64)
    if (isMacHost) {
        // Both Apple-Silicon and Intel desktop JVMs are supported from a macOS
        // host; the SDK can target either architecture.
        add(KonanTarget.MACOS_ARM64)
        add(KonanTarget.MACOS_X64)
    }
}

val jvmJniTasks = jvmJniTargets.map { target ->
    clangBuildDynamic(target = target, name = "netif_jni", useLibPrefix = true) {
        this.configureJni(target, useJdkHeaders = true)
    }
}

tasks.matching { it.name.startsWith("buildDynamicNetif") }.configureEach {
    dependsOn(provisionJniHeaders)
}

fun libraryExtension(target: KonanTarget): String = when (target.family) {
    Family.MINGW -> "dll"
    Family.OSX, Family.IOS, Family.TVOS, Family.WATCHOS -> "dylib"
    else -> "so"
}

val jvmJniCopyTasks = jvmJniTasks.map { buildTask ->
    val target = buildTask.target.get()
    val extension = libraryExtension(target)
    tasks.register<Copy>("copyNetifJni${target.name.replaceFirstChar { it.uppercase() }}") {
        dependsOn(buildTask)
        from(buildTask.dynamicFile)
        rename { "libnetif_jni.$extension" }
        into(layout.buildDirectory.dir("processed-resources/native/${target.name}"))
    }
}

val jvmNativeResourcesDir = layout.buildDirectory.dir("processed-resources/native")
tasks.named<ProcessResources>("jvmProcessResources") {
    dependsOn(jvmJniCopyTasks)
    from(jvmNativeResourcesDir)
    include("**/*.so", "**/*.dylib", "**/*.dll")
}

tasks.named<Jar>("jvmJar") {
    manifest {
        attributes("Implementation-Version" to project.version.toString())
    }
}

// Android JVM: the same routing bridge packaged as AAR jniLibs.
val androidJniAbis = linkedMapOf(
    KonanTarget.ANDROID_ARM64 to "arm64-v8a",
    KonanTarget.ANDROID_ARM32 to "armeabi-v7a",
    KonanTarget.ANDROID_X64 to "x86_64",
    KonanTarget.ANDROID_X86 to "x86",
)
val androidJniLibsDir = layout.buildDirectory.dir("androidJniLibs")
val androidJniCopyTasks = androidJniAbis.map { (target, abi) ->
    val buildTask = clangBuildDynamic(target = target, name = "netif_jni", useLibPrefix = true) {
        this.configureJni(target, useJdkHeaders = false)
    }
    tasks.register<Copy>("copyNetifJniAndroid${target.name.replaceFirstChar { it.uppercase() }}") {
        dependsOn(buildTask)
        from(buildTask.dynamicFile)
        rename { "libnetif_jni.so" }
        into(androidJniLibsDir.get().dir(abi))
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
    }
}

android {
    namespace = "pw.binom.net.interfaces"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("src/androidMain/consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets["main"].jniLibs.srcDir(androidJniLibsDir.get().asFile)
}

afterEvaluate {
    // AGP packages build/androidJniLibs only after the ABI libraries exist.
    tasks.named("preBuild") {
        dependsOn(androidJniCopyTasks)
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)

    // Signing is mandatory for Central but would break a plain local
    // `publishToMavenLocal` when no key is configured, so enable it only when
    // CI (or a developer) actually provides one.
    if (signingConfigured) {
        signAllPublications()
    }

    coordinates(
        groupId = "pw.binom.net",
        artifactId = "interfaces",
        version = project.version.toString(),
    )

    pom {
        name.set("interfaces")
        description.set("Kotlin Multiplatform library for enumerating network interfaces and their addresses")
        url.set("https://github.com/caffeine-mgn/net-interfaces")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("caffeine-mgn")
                name.set("Anton Subochev")
                email.set("caffeine.mgn@gmail.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/caffeine-mgn/net-interfaces.git")
            developerConnection.set("scm:git:ssh://github.com/caffeine-mgn/net-interfaces.git")
            url.set("https://github.com/caffeine-mgn/net-interfaces")
        }
    }
}
