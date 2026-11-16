#include "netif.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)

#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>
#include <windows.h>

#else /* POSIX */

/*
 * getifaddrs() is available on Android since API 24. Kotlin/Native's Android
 * toolchain declares __ANDROID_API__=21 by default, which hides the prototype,
 * so raise it here before including <ifaddrs.h>. The library's minSdk is 24.
 */
#if defined(__ANDROID__) && (!defined(__ANDROID_API__) || __ANDROID_API__ < 24)
#undef __ANDROID_API__
#define __ANDROID_API__ 24
#endif

#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

#if defined(__APPLE__)
#include <net/if_dl.h>
#endif

#if defined(__linux__) || defined(__ANDROID__)
#include <netpacket/packet.h>
#endif

#endif /* _WIN32 */

/* ------------------------------------------------------------------------- */
/* Shared helpers                                                             */
/* ------------------------------------------------------------------------- */

static char *ni_strdup(const char *value) {
    size_t length;
    char *copy;

    if (value == NULL) {
        return NULL;
    }
    length = strlen(value) + 1;
    copy = (char *)malloc(length);
    if (copy == NULL) {
        return NULL;
    }
    memcpy(copy, value, length);
    return copy;
}

static NIInterface *ni_new_interface(const char *name) {
    NIInterface *iface = (NIInterface *)calloc(1, sizeof(NIInterface));

    if (iface == NULL) {
        return NULL;
    }
    iface->name = ni_strdup(name);
    iface->mtu = -1;
    return iface;
}

static NIInterface *ni_find_interface(NIInterface *head, const char *name) {
    NIInterface *iface;

    for (iface = head; iface != NULL; iface = iface->next) {
        if (iface->name != NULL && name != NULL && strcmp(iface->name, name) == 0) {
            return iface;
        }
    }
    return NULL;
}

static NIInterface *ni_get_or_create(NIInterface **head, const char *name) {
    NIInterface *iface = ni_find_interface(*head, name);
    NIInterface *tail;

    if (iface != NULL) {
        return iface;
    }
    iface = ni_new_interface(name);
    if (iface == NULL) {
        return NULL;
    }
    if (*head == NULL) {
        *head = iface;
    } else {
        tail = *head;
        while (tail->next != NULL) {
            tail = tail->next;
        }
        tail->next = iface;
    }
    return iface;
}

static NIAddress *ni_add_address(NIInterface *iface, int family, const void *bytes, int length, int prefix_length) {
    NIAddress *address;
    NIAddress *tail;

    address = (NIAddress *)calloc(1, sizeof(NIAddress));
    if (address == NULL) {
        return NULL;
    }
    address->family = family;
    address->prefix_length = prefix_length;
    if (length > (int)sizeof(address->bytes)) {
        length = (int)sizeof(address->bytes);
    }
    if (length > 0) {
        memcpy(address->bytes, bytes, (size_t)length);
    }
    if (iface->addresses == NULL) {
        iface->addresses = address;
    } else {
        tail = iface->addresses;
        while (tail->next != NULL) {
            tail = tail->next;
        }
        tail->next = address;
    }
    return address;
}

void ni_free_interfaces(NIInterface *interfaces) {
    NIInterface *iface = interfaces;

    while (iface != NULL) {
        NIInterface *next_interface = iface->next;
        NIAddress *address = iface->addresses;

        while (address != NULL) {
            NIAddress *next_address = address->next;
            free(address);
            address = next_address;
        }
        free(iface->name);
        free(iface->display_name);
        free(iface);
        iface = next_interface;
    }
}

/* ------------------------------------------------------------------------- */
/* POSIX (Linux, Android, macOS, iOS, tvOS, watchOS)                          */
/* ------------------------------------------------------------------------- */

#if !defined(_WIN32)

static void ni_apply_flags(NIInterface *iface, unsigned int flags) {
    if ((flags & IFF_UP) != 0) {
        iface->flags |= NI_FLAG_UP;
    }
    if ((flags & IFF_LOOPBACK) != 0) {
        iface->flags |= NI_FLAG_LOOPBACK;
    }
    if ((flags & IFF_POINTOPOINT) != 0) {
        iface->flags |= NI_FLAG_POINT_TO_POINT;
    }
    if ((flags & IFF_MULTICAST) != 0) {
        iface->flags |= NI_FLAG_MULTICAST;
    }
    if ((flags & IFF_BROADCAST) != 0) {
        iface->flags |= NI_FLAG_BROADCAST;
    }
#ifdef IFF_RUNNING
    if ((flags & IFF_RUNNING) != 0) {
        iface->flags |= NI_FLAG_RUNNING;
    }
#endif
}

static int ni_prefix_from_netmask(const struct sockaddr *netmask) {
    int bits = 0;
    int i;

    if (netmask == NULL) {
        return 0;
    }
    if (netmask->sa_family == AF_INET) {
        const struct sockaddr_in *mask = (const struct sockaddr_in *)netmask;
        uint32_t value = ntohl(mask->sin_addr.s_addr);

        for (i = 31; i >= 0; i--) {
            if ((value & ((uint32_t)1 << i)) != 0) {
                bits++;
            } else {
                break;
            }
        }
    } else if (netmask->sa_family == AF_INET6) {
        const struct sockaddr_in6 *mask = (const struct sockaddr_in6 *)netmask;
        int byte_index;

        for (byte_index = 0; byte_index < 16; byte_index++) {
            int bit_index = 7;

            for (; bit_index >= 0; bit_index--) {
                if (((mask->sin6_addr.s6_addr[byte_index] >> bit_index) & 0x1) != 0) {
                    bits++;
                } else {
                    return bits;
                }
            }
        }
    }
    return bits;
}

NIInterface *ni_get_interfaces(void) {
    struct ifaddrs *list = NULL;
    struct ifaddrs *entry;
    NIInterface *head = NULL;
    int socket_fd;

    if (getifaddrs(&list) != 0) {
        return NULL;
    }

    for (entry = list; entry != NULL; entry = entry->ifa_next) {
        NIInterface *iface;
        int family;

        if (entry->ifa_name == NULL) {
            continue;
        }
        iface = ni_get_or_create(&head, entry->ifa_name);
        if (iface == NULL) {
            continue;
        }
        if (iface->index == 0) {
            iface->index = (int)if_nametoindex(entry->ifa_name);
        }
        ni_apply_flags(iface, entry->ifa_flags);

        if (entry->ifa_addr == NULL) {
            continue;
        }
        family = entry->ifa_addr->sa_family;

        if (family == AF_INET) {
            const struct sockaddr_in *address = (const struct sockaddr_in *)entry->ifa_addr;

            ni_add_address(iface, 4, &address->sin_addr, 4,
                           ni_prefix_from_netmask(entry->ifa_netmask));
        } else if (family == AF_INET6) {
            const struct sockaddr_in6 *address = (const struct sockaddr_in6 *)entry->ifa_addr;

            ni_add_address(iface, 6, &address->sin6_addr, 16,
                           ni_prefix_from_netmask(entry->ifa_netmask));
        }
#if defined(__linux__) || defined(__ANDROID__)
        else if (family == AF_PACKET) {
            const struct sockaddr_ll *link = (const struct sockaddr_ll *)entry->ifa_addr;

            if (link->sll_halen > 0 && iface->hardware_address_length == 0) {
                int length = link->sll_halen;

                if (length > NI_MAX_HARDWARE_ADDRESS) {
                    length = NI_MAX_HARDWARE_ADDRESS;
                }
                memcpy(iface->hardware_address, link->sll_addr, (size_t)length);
                iface->hardware_address_length = length;
            }
        }
#elif defined(__APPLE__)
        else if (family == AF_LINK) {
            const struct sockaddr_dl *link = (const struct sockaddr_dl *)entry->ifa_addr;

            if (link->sdl_alen > 0 && iface->hardware_address_length == 0) {
                int length = link->sdl_alen;

                if (length > NI_MAX_HARDWARE_ADDRESS) {
                    length = NI_MAX_HARDWARE_ADDRESS;
                }
                memcpy(iface->hardware_address, LLADDR(link), (size_t)length);
                iface->hardware_address_length = length;
            }
        }
#endif
    }
    freeifaddrs(list);

    /* MTU is not exposed by getifaddrs, so ask the kernel per interface. */
    socket_fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (socket_fd >= 0) {
        NIInterface *iface;

        for (iface = head; iface != NULL; iface = iface->next) {
            struct ifreq request;

            if (iface->name == NULL) {
                continue;
            }
            memset(&request, 0, sizeof(request));
            strncpy(request.ifr_name, iface->name, IFNAMSIZ - 1);
            if (ioctl(socket_fd, SIOCGIFMTU, &request) == 0) {
                iface->mtu = request.ifr_mtu;
            }
        }
        close(socket_fd);
    }

    return head;
}

#endif /* !_WIN32 */

/* ------------------------------------------------------------------------- */
/* Windows (mingw)                                                            */
/* ------------------------------------------------------------------------- */

#if defined(_WIN32)

static char *ni_wide_to_utf8(const WCHAR *value) {
    int length;
    char *result;

    if (value == NULL) {
        return NULL;
    }
    length = WideCharToMultiByte(CP_UTF8, 0, value, -1, NULL, 0, NULL, NULL);
    if (length <= 0) {
        return NULL;
    }
    result = (char *)malloc((size_t)length);
    if (result == NULL) {
        return NULL;
    }
    WideCharToMultiByte(CP_UTF8, 0, value, -1, result, length, NULL, NULL);
    return result;
}

static void ni_append_interface(NIInterface **head, NIInterface *iface) {
    NIInterface *tail;

    if (*head == NULL) {
        *head = iface;
        return;
    }
    tail = *head;
    while (tail->next != NULL) {
        tail = tail->next;
    }
    tail->next = iface;
}

NIInterface *ni_get_interfaces(void) {
    ULONG buffer_size = 16 * 1024;
    IP_ADAPTER_ADDRESSES *buffer = NULL;
    ULONG result = ERROR_BUFFER_OVERFLOW;
    NIInterface *head = NULL;
    int attempt;

    for (attempt = 0; attempt < 4; attempt++) {
        buffer = (IP_ADAPTER_ADDRESSES *)malloc(buffer_size);
        if (buffer == NULL) {
            return NULL;
        }
        result = GetAdaptersAddresses(
            AF_UNSPEC,
            GAA_FLAG_INCLUDE_PREFIX | GAA_FLAG_SKIP_ANYCAST |
                GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER,
            NULL,
            buffer,
            &buffer_size);
        if (result != ERROR_BUFFER_OVERFLOW) {
            break;
        }
        free(buffer);
        buffer = NULL;
    }
    if (result != NO_ERROR || buffer == NULL) {
        free(buffer);
        return NULL;
    }

    {
        PIP_ADAPTER_ADDRESSES adapter;

        for (adapter = buffer; adapter != NULL; adapter = adapter->Next) {
            NIInterface *iface = ni_new_interface(adapter->AdapterName);
            PIP_ADAPTER_UNICAST_ADDRESS unicast;

            if (iface == NULL) {
                continue;
            }
            iface->display_name = ni_wide_to_utf8(adapter->FriendlyName);
            iface->index = (int)adapter->IfIndex;
            iface->mtu = (int)adapter->Mtu;

            if (adapter->OperStatus == IfOperStatusUp) {
                iface->flags |= NI_FLAG_UP | NI_FLAG_RUNNING;
            }
            if (adapter->IfType == IF_TYPE_SOFTWARE_LOOPBACK) {
                iface->flags |= NI_FLAG_LOOPBACK;
            }
            if (adapter->IfType == IF_TYPE_PPP || adapter->IfType == IF_TYPE_TUNNEL) {
                iface->flags |= NI_FLAG_POINT_TO_POINT;
            }
            if (adapter->IfType == IF_TYPE_ETHERNET_CSMACD) {
                iface->flags |= NI_FLAG_BROADCAST;
            }
            if ((adapter->Flags & IP_ADAPTER_NO_MULTICAST) == 0) {
                iface->flags |= NI_FLAG_MULTICAST;
            }

            if (adapter->PhysicalAddressLength > 0) {
                int length = (int)adapter->PhysicalAddressLength;

                if (length > NI_MAX_HARDWARE_ADDRESS) {
                    length = NI_MAX_HARDWARE_ADDRESS;
                }
                memcpy(iface->hardware_address, adapter->PhysicalAddress, (size_t)length);
                iface->hardware_address_length = length;
            }

            for (unicast = adapter->FirstUnicastAddress; unicast != NULL; unicast = unicast->Next) {
                const struct sockaddr *address = unicast->Address.lpSockaddr;

                if (address == NULL) {
                    continue;
                }
                if (address->sa_family == AF_INET) {
                    const struct sockaddr_in *ipv4 = (const struct sockaddr_in *)address;

                    ni_add_address(iface, 4, &ipv4->sin_addr, 4,
                                   (int)unicast->OnLinkPrefixLength);
                } else if (address->sa_family == AF_INET6) {
                    const struct sockaddr_in6 *ipv6 = (const struct sockaddr_in6 *)address;

                    ni_add_address(iface, 6, &ipv6->sin6_addr, 16,
                                   (int)unicast->OnLinkPrefixLength);
                }
            }

            ni_append_interface(&head, iface);
        }
    }

    free(buffer);
    return head;
}

#endif /* _WIN32 */
