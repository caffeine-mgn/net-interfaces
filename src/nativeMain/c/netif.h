#ifndef PW_BINOM_NET_INTERFACES_H
#define PW_BINOM_NET_INTERFACES_H

#ifdef __cplusplus
extern "C" {
#endif

/* Interface flag bits reported in NIInterface.flags. */
#define NI_FLAG_UP 1
#define NI_FLAG_LOOPBACK 2
#define NI_FLAG_POINT_TO_POINT 4
#define NI_FLAG_MULTICAST 8
#define NI_FLAG_BROADCAST 16
#define NI_FLAG_RUNNING 32

#define NI_MAX_HARDWARE_ADDRESS 32

/* One IP address assigned to an interface. `family` is 4 or 6. */
typedef struct NIAddress {
    int family;
    int prefix_length;
    unsigned char bytes[16];
    struct NIAddress *next;
} NIAddress;

/*
 * One network interface. `name` is the OS identifier (eth0, en0, ...),
 * `display_name` is a human friendly name when the OS provides one (Windows).
 * `mtu` is -1 when it could not be determined.
 */
typedef struct NIInterface {
    char *name;
    char *display_name;
    int index;
    int mtu;
    int flags;
    int hardware_address_length;
    unsigned char hardware_address[NI_MAX_HARDWARE_ADDRESS];
    struct NIAddress *addresses;
    struct NIInterface *next;
} NIInterface;

/* Returns a linked list of all interfaces, or NULL when enumeration fails. */
NIInterface *ni_get_interfaces(void);

/* Releases a list returned by ni_get_interfaces(). NULL is a no-op. */
void ni_free_interfaces(NIInterface *interfaces);

#ifdef __cplusplus
}
#endif

#endif /* PW_BINOM_NET_INTERFACES_H */
