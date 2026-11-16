import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import pw.binom.kotlin.clang.KnClangExtension
import pw.binom.kotlin.clang.clangBuildStatic
import pw.binom.kotlin.clang.compileTaskName
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
        tasks.matching { it.name == target.compileTaskName }.configureEach {
            dependsOn(buildNative)
        }

        cinterops {
            val netif by creating {
                definitionFile.set(project.file("src/nativeInterop/cinterop/netif.def"))
                packageName = "netif"
                includeDirs.headerFilterOnly(nativeSources)
            }
        }

        compileTaskProvider.configure {
            compilerOptions.freeCompilerArgs.addAll(
                "-include-binary",
                buildNative.staticFile.get().asFile.absolutePath,
            )
        }
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)

    // Signing is mandatory for Central but would break a plain local
    // `publishToMavenLocal` when no key is configured, so enable it only when
    // CI (or a developer) actually provides one.
    val signingConfigured =
        providers.gradleProperty("signingInMemoryKey").isPresent ||
            providers.gradleProperty("binom.gpg.private_key").isPresent ||
            providers.gradleProperty("signingUseGpg").orNull == "true"
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

/*
 * GPG signing, mirroring the caffeine-mgn/* convention:
 *
 *   signingUseGpg=true — CI mode. The key is imported into the system keyring
 *   and Gradle delegates to the `gpg` binary (useGpgCmd()).
 *
 *   default — local development. An in-memory key is read from
 *   `signingInMemoryKey*` or `binom.gpg.*` Gradle properties.
 */
pluginManager.withPlugin("signing") {
    if (findProperty("signingUseGpg") == "true") {
        extensions.configure<SigningExtension>("signing") {
            useGpgCmd()
        }
        logger.lifecycle("[signing] Using system gpg via signing.gnupg.keyName=${findProperty("signing.gnupg.keyName")}")
        return@withPlugin
    }

    val key = providers.gradleProperty("signingInMemoryKey").orNull
        ?: providers.gradleProperty("binom.gpg.private_key").orNull
    val keyId = providers.gradleProperty("signingInMemoryKeyId").orNull
        ?: providers.gradleProperty("binom.gpg.key_id").orNull
    val password = providers.gradleProperty("signingInMemoryKeyPassword").orNull
        ?: providers.gradleProperty("binom.gpg.password").orNull
    val isBase64 = providers.gradleProperty("signingInMemoryKeyIsBase64").orNull?.toBoolean() ?: false

    if (key != null && keyId != null && password != null) {
        val decodedKey = if (isBase64) {
            String(Base64.getDecoder().decode(key))
        } else {
            key.replace("\\n", "\n")
        }
        logger.lifecycle("[signing] Using in-memory PGP key, length=${decodedKey.length}, isBase64=$isBase64")
        extensions.getByType(SigningExtension::class.java)
            .useInMemoryPgpKeys(decodedKey, keyId, password)
    } else {
        logger.lifecycle("[signing] No in-memory PGP key configured; publications will be signed by the publishing plugin only.")
    }
}
