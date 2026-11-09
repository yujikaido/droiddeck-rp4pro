#include "ahb_offer.h"

#include <stddef.h>
#include <sys/stat.h>

#define FOURCC(a, b, c, d) ((uint32_t)(a) | ((uint32_t)(b) << 8) | ((uint32_t)(c) << 16) | ((uint32_t)(d) << 24))
#define DRM_ARGB8888 FOURCC('A', 'R', '2', '4')
#define DRM_XRGB8888 FOURCC('X', 'R', '2', '4')
#define DRM_ABGR8888 FOURCC('A', 'B', '2', '4')
#define DRM_XBGR8888 FOURCC('X', 'B', '2', '4')
#define MOD_LINEAR 0ULL
#define MOD_INVALID 0x00ffffffffffffffULL

/* AHARDWAREBUFFER_FORMAT_* */
#define AHB_R8G8B8A8_UNORM 1u
#define AHB_R8G8B8X8_UNORM 2u
#define AHB_B8G8R8A8_UNORM 5u

int ahb_offer_key_from_fds(const int *fds, int n, struct ahb_offer_key *k) {
    k->n = 0;
    for (int i = 0; i < n && k->n < AHB_OFFER_MAX_FDS; i++) {
        struct stat st;
        if (fds[i] < 0 || fstat(fds[i], &st) != 0) continue;
        k->dev[k->n] = st.st_dev;
        k->ino[k->n] = st.st_ino;
        k->n++;
    }
    return k->n;
}

int ahb_offer_key_has_fd(const struct ahb_offer_key *k, int fd) {
    struct stat st;
    if (fd < 0 || fstat(fd, &st) != 0) return 0;
    for (int i = 0; i < k->n; i++)
        if (k->dev[i] == st.st_dev && k->ino[i] == st.st_ino) return 1;
    return 0;
}

int ahb_offer_keys_overlap(const struct ahb_offer_key *a, const struct ahb_offer_key *b) {
    for (int i = 0; i < a->n; i++)
        for (int j = 0; j < b->n; j++)
            if (a->dev[i] == b->dev[j] && a->ino[i] == b->ino[j]) return 1;
    return 0;
}

int ahb_offer_format_fits(uint32_t ahb_format, uint32_t drm_format) {
    /* DRM fourccs name the 32-bit word little-endian: AB24/XB24 are R,G,B,A bytes in memory,
     * AR24/XR24 are B,G,R,A. */
    switch (ahb_format) {
    case AHB_R8G8B8A8_UNORM:
    case AHB_R8G8B8X8_UNORM: return drm_format == DRM_ABGR8888 || drm_format == DRM_XBGR8888;
    case AHB_B8G8R8A8_UNORM: return drm_format == DRM_ARGB8888 || drm_format == DRM_XRGB8888;
    default: return 0;
    }
}

const char *ahb_offer_mismatch(const struct ahb_offer_desc *o, const struct ahb_offer_plane *p) {
    if (p->n_planes != 1) return "more than one plane";
    if (p->width <= 0 || p->height <= 0 || (uint32_t)p->width != o->width || (uint32_t)p->height != o->height)
        return "another size";
    if (p->offset != 0) return "a plane offset";
    if ((uint64_t)o->stride_px * 4u != p->stride) return "another row stride";
    if (p->modifier != MOD_LINEAR && p->modifier != MOD_INVALID) return "a tiled or compressed layout";
    if (!ahb_offer_format_fits(o->ahb_format, p->drm_format)) return "another pixel format";
    return NULL;
}
