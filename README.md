# net-interfaces

[![CI](https://github.com/caffeine-mgn/net-interfaces/actions/workflows/ci.yml/badge.svg)](https://github.com/caffeine-mgn/net-interfaces/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/pw.binom.net/interfaces.svg)](https://repo1.maven.org/maven2/pw/binom/net/interfaces/)

Kotlin Multiplatform library for enumerating the network interfaces of the
host: names, indexes, MTU, flags (up / loopback / point-to-point / multicast),
hardware (MAC) addresses and assigned IP addresses with their subnet prefix.

No coroutines, no sockets — this is a small, dependency-free snapshot API.
JS and Wasm are intentionally not supported.

## Supported targets

| Platform | Targets |
|---|---|
| JVM | `jvm` |
| Android (JVM) | `androidTarget` |
| Linux | `linuxX64`, `linuxArm64` |
| Windows | `mingwX64` |
| Android Native | `androidNativeArm32`, `androidNativeArm64`, `androidNativeX64`, `androidNativeX86` |
| macOS | `macosArm64` |
| iOS | `iosArm64`, `iosSimulatorArm64`, `iosX64` |
| tvOS | `tvosArm64`, `tvosSimulatorArm64` |
| watchOS | `watchosArm32`, `watchosArm64`, `watchosSimulatorArm64` |

The JVM/Android implementation uses `java.net.NetworkInterface`. Native targets
share one small C implementation (`getifaddrs` on POSIX, `GetAdaptersAddresses`
on Windows) compiled with the Kotlin/Native Clang toolchain and imported through
cinterop.

Android Native note: Kotlin/Native links against API 21, while `getifaddrs` was
added in API 24. The symbol is resolved at runtime, so the library links with
any `minSdk`; on API 21–23 enumeration returns an empty list, on API 24+ it
behaves like the other POSIX targets.

## Installation

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("pw.binom.net:interfaces:<version>")
}
```

## Usage

### List interfaces

```kotlin
import pw.binom.net.interfaces.NetworkInterface

NetworkInterface.getAll().forEach { network ->
    println(network)
    // NetworkInterface(name=eth0, index=2, mtu=1500, addresses=[192.168.1.10/24])

    println("up=${network.isUp} loopback=${network.isLoopback} mac=${network.hardwareAddress}")
    network.addresses.forEach { address ->
        println("  ${address.address.host}/${address.prefixLength} mask=${address.netmask.host}")
    }
}
```

### Pick an interface for discovery / multicast

`getAvailable()` returns interfaces that are up and running, not loopback and
have at least one assigned address — normally the right list for service
advertisement, mDNS/SSDP and peer discovery.

```kotlin
val candidates = NetworkInterface.getAvailable()
val primary = candidates.firstOrNull()?.primaryAddress
```

### Look up an interface

```kotlin
NetworkInterface.findByName("eth0")            // by OS name or Windows friendly name
NetworkInterface.findByIp("192.168.1.10")      // parse + search
NetworkInterface.findByAddress(IpAddress.parse("fe80::1")!!)
NetworkInterface.getByIp("192.168.1.10")       // throws when not found
```

### Address helpers

```kotlin
val ip = IpAddress.parse("192.168.1.10")!!
val subnet = InterfaceAddress(ip, 24)

subnet.netmask.host          // 255.255.255.0
subnet.broadcast?.host       // 192.168.1.255
subnet.contains(IpAddress.parse("192.168.1.20")!!) // true

ip.isLoopback
ip.isLinkLocal
ip.isSiteLocal
ip.isMulticast
```

IPv6 literals are parsed and rendered in RFC 5952 form (`2001:db8::1`), zone
ids (`fe80::1%eth0`) are accepted when parsing and ignored.

## API overview

- `NetworkInterface` — immutable snapshot of one interface.
- `InterfaceAddress` — an `IpAddress` plus prefix length; computes netmask,
  broadcast address and subnet membership.
- `IpAddress` — immutable IPv4/IPv6 address with parsing, formatting and
  address classification.
- `MacAddress` — immutable hardware address.
- `NetworkInterfacesException` — platform enumeration failure.

## Notes on the native implementation

The native part is a rewrite of the `NetworkInterface` code that used to live in
`pw.binom.io:socket`. In particular it fixes these issues from the original:

- the Windows adapter list no longer dereferences a null node while building the
  list;
- the list returned by `getifaddrs` / `GetAdaptersAddresses` is always released
  (the old Kotlin wrapper passed the already-null cursor to the free function);
- `index` is the real OS interface index (`if_nametoindex` / `IfIndex`) instead
  of a counter that incremented once per address;
- interfaces are grouped by name, so an interface with several addresses is
  reported once with all of them;
- MTU, display name, hardware address and interface flags are exposed.

## Building

```shell
./gradlew jvmTest linuxX64Test     # run tests on the current host
./gradlew build                    # everything buildable on this host
```

Releases are published to Maven Central from a macOS runner (the only host that
can build the Apple targets), see `.github/workflows/release.yml`.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
