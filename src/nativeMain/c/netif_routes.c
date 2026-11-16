#include "netif.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)

#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0600
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>
#include <netioapi.h>
#include <windows.h>

#else

#include <sys/socket.h>
#include <sys/types.h>

#if defined(__linux__) || defined(__ANDROID__)

#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <unistd.h>

#elif defined(__APPLE__)

#include <net/if.h>
#include <net/if_dl.h>
#include <net/route.h>
#include <netinet/in.h>
#include <sys/sysctl.h>

#endif

#endif /* _WIN32 */

/* ------------------------------------------------------------------------- */
/* Shared helpers                                                             */
/* ------------------------------------------------------------------------- */

static int ni_bytes_are_zero(const unsigned char *bytes, int length) {
    int index;

    for (index = 0; index < length; index++) {
        if (bytes[index] != 0) {
            return 0;
        }
    }
    return 1;
}

static NIRoute *ni_new_route(void) {
    return (NIRoute *)calloc(1, sizeof(NIRoute));
}

static void ni_append_route(NIRoute **head, NIRoute *route) {
    NIRoute *tail;

    if (*head == NULL) {
        *head = route;
        return;
    }
    tail = *head;
    while (tail->next != NULL) {
        tail = tail->next;
    }
    tail->next = route;
}

void ni_free_routes(NIRoute *routes) {
    NIRoute *route = routes;

    while (route != NULL) {
        NIRoute *next = route->next;
        free(route);
        route = next;
    }
}

static void ni_finish_route(NIRoute *route) {
    int length = route->family == 4 ? 4 : 16;

    if (route->prefix_length == 0) {
        route->flags |= NI_ROUTE_FLAG_DEFAULT;
    }
    if (!ni_bytes_are_zero(route->gateway, length)) {
        route->flags |= NI_ROUTE_FLAG_GATEWAY;
    }
}

/* ------------------------------------------------------------------------- */
/* Linux / Android: netlink RTM_GETROUTE dump                                 */
/* ------------------------------------------------------------------------- */

#if (defined(__linux__) || defined(__ANDROID__)) && !defined(_WIN32)

static void ni_parse_rtattr(NIRoute *route, struct rtattr *attr, int length) {
    int address_length = route->family == 4 ? 4 : 16;

    for (; RTA_OK(attr, length); attr = RTA_NEXT(attr, length)) {
        int payload = (int)RTA_PAYLOAD(attr);

        switch (attr->rta_type) {
            case RTA_DST:
                if (payload >= address_length) {
                    memcpy(route->destination, RTA_DATA(attr), (size_t)address_length);
                }
                break;
            case RTA_GATEWAY:
                if (payload >= address_length) {
                    memcpy(route->gateway, RTA_DATA(attr), (size_t)address_length);
                }
                break;
            case RTA_OIF:
                if (payload >= (int)sizeof(int)) {
                    memcpy(&route->interface_index, RTA_DATA(attr), sizeof(int));
                }
                break;
            case RTA_PRIORITY:
                if (payload >= (int)sizeof(int)) {
                    memcpy(&route->metric, RTA_DATA(attr), sizeof(int));
                }
                break;
            default:
                break;
        }
    }
}

NIRoute *ni_get_routes(void) {
    struct {
        struct nlmsghdr header;
        struct rtmsg message;
    } request;
    struct sockaddr_nl kernel;
    NIRoute *head = NULL;
    char buffer[64 * 1024];
    int descriptor;
    int done = 0;

    descriptor = socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE);
    if (descriptor < 0) {
        return NULL;
    }

    memset(&request, 0, sizeof(request));
    request.header.nlmsg_len = NLMSG_LENGTH(sizeof(struct rtmsg));
    request.header.nlmsg_type = RTM_GETROUTE;
    request.header.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
    request.header.nlmsg_seq = 1;
    request.message.rtm_family = AF_UNSPEC;

    memset(&kernel, 0, sizeof(kernel));
    kernel.nl_family = AF_NETLINK;

    if (sendto(descriptor, &request, request.header.nlmsg_len, 0,
               (struct sockaddr *)&kernel, sizeof(kernel)) < 0) {
        close(descriptor);
        return NULL;
    }

    while (!done) {
        ssize_t received = recv(descriptor, buffer, sizeof(buffer), 0);
        struct nlmsghdr *header;
        int remaining;

        if (received <= 0) {
            break;
        }
        remaining = (int)received;

        for (header = (struct nlmsghdr *)buffer; NLMSG_OK(header, remaining);
             header = NLMSG_NEXT(header, remaining)) {
            struct rtmsg *message;
            NIRoute *route;
            int attribute_length;
            struct rtattr *attribute;

            if (header->nlmsg_type == NLMSG_DONE) {
                done = 1;
                break;
            }
            if (header->nlmsg_type == NLMSG_ERROR) {
                done = 1;
                break;
            }
            if (header->nlmsg_type != RTM_NEWROUTE) {
                continue;
            }

            message = (struct rtmsg *)NLMSG_DATA(header);
            if (message->rtm_family != AF_INET && message->rtm_family != AF_INET6) {
                continue;
            }
            /* Only routes that can carry a gateway are interesting. Connected
             * and local routes are represented by the interface addresses. */
            if (message->rtm_type != RTN_UNICAST &&
                message->rtm_type != RTN_BLACKHOLE &&
                message->rtm_type != RTN_UNREACHABLE &&
                message->rtm_type != RTN_PROHIBIT) {
                continue;
            }

            route = ni_new_route();
            if (route == NULL) {
                continue;
            }
            route->family = message->rtm_family == AF_INET ? 4 : 6;
            route->prefix_length = (int)message->rtm_dst_len;

            if (message->rtm_type == RTN_BLACKHOLE) {
                route->flags |= NI_ROUTE_FLAG_BLACKHOLE;
            } else if (message->rtm_type == RTN_UNREACHABLE ||
                       message->rtm_type == RTN_PROHIBIT) {
                route->flags |= NI_ROUTE_FLAG_REJECT;
            }
            if (message->rtm_protocol == RTPROT_BOOT ||
                message->rtm_protocol == RTPROT_RA ||
                message->rtm_protocol == RTPROT_DHCP) {
                route->flags |= NI_ROUTE_FLAG_DYNAMIC;
            }

            attribute_length =
                (int)header->nlmsg_len - (int)NLMSG_LENGTH(sizeof(struct rtmsg));
            attribute = (struct rtattr *)((char *)NLMSG_DATA(header) +
                                          NLMSG_ALIGN(sizeof(struct rtmsg)));
            ni_parse_rtattr(route, attribute, attribute_length);

            ni_finish_route(route);
            ni_append_route(&head, route);
        }
    }

    close(descriptor);
    return head;
}

/* ------------------------------------------------------------------------- */
/* macOS / iOS / tvOS / watchOS: sysctl(CTL_NET, PF_ROUTE, NET_RT_DUMP)       */
/* ------------------------------------------------------------------------- */

#elif defined(__APPLE__) && !defined(_WIN32)

/*
 * Darwin stores a netmask in a family-less sockaddr, trimmed of trailing zero
 * bytes. The mask starts at the sin_addr / sin6_addr offset (4 for IPv4,
 * 8 for IPv6), and `sa_len` covers only the significant prefix bytes.
 */
static int ni_prefix_from_darwin_mask(const struct sockaddr *mask, int family) {
    const unsigned char *raw = (const unsigned char *)mask;
    int offset = family == 4 ? 4 : 8;
    int total = family == 4 ? 4 : 16;
    int length;
    int bits = 0;
    int index;

    if (mask == NULL) {
        return 0;
    }
    length = (int)mask->sa_len - offset;
    if (length < 0) {
        length = 0;
    }
    if (length > total) {
        length = total;
    }

    for (index = 0; index < total; index++) {
        unsigned char byte = index < length ? raw[offset + index] : 0;
        int bit;

        for (bit = 7; bit >= 0; bit--) {
            if (((byte >> bit) & 1) != 0) {
                bits++;
            } else {
                return bits;
            }
        }
    }
    return bits;
}

/* Darwin's routing socket aligns every sockaddr to 4 bytes (XNU's ROUNDUP),
 * and `struct rt_msghdr` has no rtm_family: the address family comes from the
 * RTAX_DST sockaddr. */
#define NI_RT_SA_SIZE(address) \
    ((((int)(address)->sa_len) + 3) & ~3)

static int ni_darwin_route_family(struct rt_msghdr *message, char *cursor, char *end) {
    int index;
    int gateway_family = 0;

    for (index = 0; index < RTAX_MAX && cursor + sizeof(struct sockaddr) <= end; index++) {
        struct sockaddr *address;
        int length;

        if ((message->rtm_addrs & (1 << index)) == 0) {
            continue;
        }
        address = (struct sockaddr *)cursor;
        if (address->sa_len == 0) {
            break;
        }
        if (index == RTAX_DST &&
            (address->sa_family == AF_INET || address->sa_family == AF_INET6)) {
            return address->sa_family;
        }
        if (index == RTAX_GATEWAY &&
            (address->sa_family == AF_INET || address->sa_family == AF_INET6)) {
            gateway_family = address->sa_family;
        }
        length = NI_RT_SA_SIZE(address);
        if (length <= 0) {
            break;
        }
        cursor += length;
    }
    return gateway_family;
}

static void ni_parse_route_sockaddrs(NIRoute *route, struct rt_msghdr *message,
                                     char *cursor, char *end) {
    int index;

    for (index = 0; index < RTAX_MAX && cursor + sizeof(struct sockaddr) <= end; index++) {
        struct sockaddr *address;
        int length;

        if ((message->rtm_addrs & (1 << index)) == 0) {
            continue;
        }
        address = (struct sockaddr *)cursor;
        if (address->sa_len == 0) {
            break;
        }

        if (index == RTAX_DST) {
            if (address->sa_family == AF_INET && route->family == 4) {
                memcpy(route->destination,
                       &((struct sockaddr_in *)address)->sin_addr, 4);
            } else if (address->sa_family == AF_INET6 && route->family == 6) {
                memcpy(route->destination,
                       &((struct sockaddr_in6 *)address)->sin6_addr, 16);
            }
        } else if (index == RTAX_GATEWAY) {
            if (address->sa_family == AF_INET && route->family == 4) {
                memcpy(route->gateway, &((struct sockaddr_in *)address)->sin_addr, 4);
            } else if (address->sa_family == AF_INET6 && route->family == 6) {
                memcpy(route->gateway, &((struct sockaddr_in6 *)address)->sin6_addr, 16);
            } else if (address->sa_family == AF_LINK) {
                struct sockaddr_dl *link = (struct sockaddr_dl *)address;

                if (route->interface_index == 0 && link->sdl_index != 0) {
                    route->interface_index = link->sdl_index;
                }
            }
        } else if (index == RTAX_NETMASK) {
            route->prefix_length = ni_prefix_from_darwin_mask(address, route->family);
        } else if (index == RTAX_IFP) {
            if (address->sa_family == AF_LINK) {
                struct sockaddr_dl *link = (struct sockaddr_dl *)address;

                if (link->sdl_index != 0) {
                    route->interface_index = link->sdl_index;
                }
            }
        }

        length = NI_RT_SA_SIZE(address);
        if (length <= 0) {
            break;
        }
        cursor += length;
    }
}

NIRoute *ni_get_routes(void) {
    int mib[6] = {CTL_NET, PF_ROUTE, 0, 0, NET_RT_DUMP, 0};
    size_t needed = 0;
    char *buffer;
    char *cursor;
    char *end;
    NIRoute *head = NULL;

    if (sysctl(mib, 6, NULL, &needed, NULL, 0) < 0 || needed == 0) {
        return NULL;
    }
    buffer = (char *)malloc(needed);
    if (buffer == NULL) {
        return NULL;
    }
    if (sysctl(mib, 6, buffer, &needed, NULL, 0) < 0) {
        free(buffer);
        return NULL;
    }

    cursor = buffer;
    end = buffer + needed;
    while (cursor + sizeof(struct rt_msghdr) <= end) {
        struct rt_msghdr *message = (struct rt_msghdr *)cursor;
        NIRoute *route;
        int family;

        if (message->rtm_msglen == 0) {
            break;
        }
        cursor += message->rtm_msglen;

        if (message->rtm_version != RTM_VERSION) {
            continue;
        }
        if (message->rtm_type != RTM_GET && message->rtm_type != RTM_ADD &&
            message->rtm_type != RTM_CHANGE) {
            continue;
        }
        family = ni_darwin_route_family(message, (char *)(message + 1),
                                        (char *)message + message->rtm_msglen);
        if (family != AF_INET && family != AF_INET6) {
            continue;
        }

        route = ni_new_route();
        if (route == NULL) {
            continue;
        }
        route->family = family == AF_INET ? 4 : 6;
        route->interface_index = (int)message->rtm_index;
        route->metric = 0;
        if (message->rtm_flags & RTF_REJECT) {
            route->flags |= NI_ROUTE_FLAG_REJECT;
        }
        if (message->rtm_flags & RTF_BLACKHOLE) {
            route->flags |= NI_ROUTE_FLAG_BLACKHOLE;
        }
        if (message->rtm_flags & RTF_DYNAMIC) {
            route->flags |= NI_ROUTE_FLAG_DYNAMIC;
        }

        ni_parse_route_sockaddrs(route, message, (char *)(message + 1),
                                 (char *)message + message->rtm_msglen);
        /* RTF_HOST routes are /32 or /128; their netmask sockaddr is often
         * omitted entirely in the routing-socket dump. */
        if (route->prefix_length == 0 && (message->rtm_flags & RTF_HOST) != 0) {
            route->prefix_length = route->family == 4 ? 32 : 128;
        }
        ni_finish_route(route);
        ni_append_route(&head, route);
    }

    free(buffer);
    return head;
}

/* ------------------------------------------------------------------------- */
/* Windows: GetIpForwardTable2                                                */
/* ------------------------------------------------------------------------- */

#elif defined(_WIN32)

static int ni_sockaddr_inet_copy(const SOCKADDR_INET *address, int *family,
                                 unsigned char *destination) {
    if (address->si_family == AF_INET) {
        *family = 4;
        memcpy(destination, &address->Ipv4.sin_addr, 4);
        return 1;
    }
    if (address->si_family == AF_INET6) {
        *family = 6;
        memcpy(destination, &address->Ipv6.sin6_addr, 16);
        return 1;
    }
    return 0;
}

NIRoute *ni_get_routes(void) {
    PMIB_IPFORWARD_TABLE2 table = NULL;
    NIRoute *head = NULL;
    ULONG index;

    if (GetIpForwardTable2(AF_UNSPEC, &table) != NO_ERROR || table == NULL) {
        return NULL;
    }

    for (index = 0; index < table->NumEntries; index++) {
        MIB_IPFORWARD_ROW2 *row = &table->Table[index];
        NIRoute *route;
        int destination_family = 0;

        route = ni_new_route();
        if (route == NULL) {
            continue;
        }
        if (!ni_sockaddr_inet_copy(&row->DestinationPrefix.Prefix,
                                   &destination_family, route->destination)) {
            free(route);
            continue;
        }
        route->family = destination_family;
        route->prefix_length = (int)row->DestinationPrefix.PrefixLength;
        route->interface_index = (int)row->InterfaceIndex;
        route->metric = (int)row->Metric;

        if (row->Origin == NlroDHCP || row->Origin == NlroRouterAdvertisement) {
            route->flags |= NI_ROUTE_FLAG_DYNAMIC;
        }

        {
            int gateway_family = 0;
            unsigned char gateway[16];

            memset(gateway, 0, sizeof(gateway));
            if (ni_sockaddr_inet_copy(&row->NextHop, &gateway_family, gateway)) {
                int length = route->family == 4 ? 4 : 16;

                if (!ni_bytes_are_zero(gateway, length)) {
                    memcpy(route->gateway, gateway, (size_t)length);
                }
            }
        }

        ni_finish_route(route);
        ni_append_route(&head, route);
    }

    FreeMibTable(table);
    return head;
}

#else

NIRoute *ni_get_routes(void) {
    return NULL;
}

#endif
