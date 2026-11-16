#include <jni.h>

#include <stdlib.h>
#include <string.h>

#include "netif.h"

static void ni_write_u16(unsigned char *buffer, int value) {
    buffer[0] = (unsigned char)(value & 0xff);
    buffer[1] = (unsigned char)((value >> 8) & 0xff);
}

static void ni_write_i32(unsigned char *buffer, int value) {
    buffer[0] = (unsigned char)(value & 0xff);
    buffer[1] = (unsigned char)((value >> 8) & 0xff);
    buffer[2] = (unsigned char)((value >> 16) & 0xff);
    buffer[3] = (unsigned char)((value >> 24) & 0xff);
}

/*
 * Serializes the routing table into fixed 44-byte records:
 *
 *   0      family (4 / 6)
 *   1      prefix length
 *   2..3   flags (little endian)
 *   4..7   interface index (little endian)
 *   8..11  metric (little endian)
 *   12..27 destination address (4 or 16 significant bytes)
 *   28..43 gateway address (4 or 16 significant bytes, zero when absent)
 *
 * The layout is parsed by NativeRouting.getRoutes() in Routing.jvm.kt and by
 * NI_ROUTE_RECORD_SIZE in netif.h.
 */
JNIEXPORT jbyteArray JNICALL
Java_pw_binom_net_interfaces_NativeRouting_nativeGetRoutes(JNIEnv *env, jobject instance) {
    NIRoute *routes;
    NIRoute *route;
    jbyteArray result;
    unsigned char *buffer;
    int count = 0;
    int size;
    int offset = 0;

    (void)instance;
    routes = ni_get_routes();
    if (routes == NULL) {
        return (*env)->NewByteArray(env, 0);
    }
    for (route = routes; route != NULL; route = route->next) {
        count++;
    }

    size = count * NI_ROUTE_RECORD_SIZE;
    buffer = (unsigned char *)calloc(1, size > 0 ? (size_t)size : 1);
    if (buffer == NULL) {
        ni_free_routes(routes);
        return NULL;
    }

    for (route = routes; route != NULL; route = route->next) {
        unsigned char *record = buffer + offset;
        int address_length = route->family == 4 ? 4 : 16;

        record[0] = (unsigned char)route->family;
        record[1] = (unsigned char)route->prefix_length;
        ni_write_u16(record + 2, route->flags);
        ni_write_i32(record + 4, route->interface_index);
        ni_write_i32(record + 8, route->metric);
        memcpy(record + 12, route->destination, (size_t)address_length);
        memcpy(record + 28, route->gateway, (size_t)address_length);
        offset += NI_ROUTE_RECORD_SIZE;
    }

    result = (*env)->NewByteArray(env, (jsize)size);
    if (result != NULL && size > 0) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)size, (const jbyte *)buffer);
    }

    free(buffer);
    ni_free_routes(routes);
    return result;
}
