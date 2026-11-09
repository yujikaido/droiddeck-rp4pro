#include "bridge_pages.h"
#include <dlfcn.h>
#include <pthread.h>
#include <string.h>

typedef int (*lookup_fn)(int fd, struct bridge_pages *out);

static pthread_once_t g_once = PTHREAD_ONCE_INIT;
static lookup_fn g_lookup;

/* The in-app server is libvkbridge_jni.so (System.loadLibrary or the debug override, both that
 * soname); never loaded here, only found. BRIDGE_PAGES_LIB names another (tests). */
static void resolve(void) {
#ifdef BRIDGE_PAGES_LIB
    const char *lib = BRIDGE_PAGES_LIB;
#else
    const char *lib = "libvkbridge_jni.so";
#endif
    void *h = dlopen(lib, RTLD_NOW | RTLD_NOLOAD);
    if (h) g_lookup = (lookup_fn)dlsym(h, "vkb_dmabuf_ahb_lookup");
}

int bridge_pages_present(void) {
    pthread_once(&g_once, resolve);
    return g_lookup != NULL;
}

int bridge_pages_lookup(int fd, struct bridge_pages *out) {
    if (fd < 0 || !out || !bridge_pages_present()) return 0;
    memset(out, 0, sizeof(*out));
    out->abi = BRIDGE_PAGES_ABI;
    out->struct_size = sizeof(*out);
    if (g_lookup(fd, out) != 1) {
        memset(out, 0, sizeof(*out));
        return 0;
    }
    return 1;
}
