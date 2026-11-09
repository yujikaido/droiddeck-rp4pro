#ifndef AHB_OFFER_H
#define AHB_OFFER_H
/*
 * Offered AHardwareBuffers (banner_ahb_v1 version 3, offer/withdraw): the matching rules, kept free
 * of Android and Wayland so the host tests can run them.
 *
 * A nested compositor (gamescope) passes a game's dma-bufs on as wl_buffers of its own and knows
 * nothing about gralloc. The game's driver, which allocated those dma-bufs as AHardwareBuffers,
 * offers each one on a connection of its own. A wl_buffer created later from the same dma-buf, with
 * the same layout, gets the AHardwareBuffer as if attach had been sent for it.
 *
 * "The same dma-buf" is decided from the files themselves, never from what a client says: the
 * AHardwareBuffer's own native handle holds the dma-buf, and two fds of one dma-buf share its
 * inode (st_dev, st_ino) however many times the fd was duplicated or passed between processes.
 */
#include <stdint.h>
#include <sys/types.h>

#define AHB_OFFER_MAX_FDS 4

struct ahb_offer_key {
    int n;
    dev_t dev[AHB_OFFER_MAX_FDS];
    ino_t ino[AHB_OFFER_MAX_FDS];
};

/* What the offered buffer is: its AHardwareBuffer_Desc (width, height, stride in pixels, format). */
struct ahb_offer_desc {
    uint32_t width, height, stride_px, ahb_format;
};

/* What a zwp_linux_dmabuf_v1 wl_buffer was created with. */
struct ahb_offer_plane {
    int n_planes;
    int fd;              /* plane 0 */
    uint32_t offset;     /* plane 0, bytes */
    uint32_t stride;     /* plane 0, bytes */
    int32_t width, height;
    uint32_t drm_format; /* fourcc */
    uint64_t modifier;
};

/* The identities of a handle's fds (fstat; fds that fail are skipped). Returns the count kept. */
int ahb_offer_key_from_fds(const int *fds, int n, struct ahb_offer_key *k);

/* 1 when fd is one of the key's files. */
int ahb_offer_key_has_fd(const struct ahb_offer_key *k, int fd);

/* 1 when two keys share a file (the same buffer offered twice). */
int ahb_offer_keys_overlap(const struct ahb_offer_key *a, const struct ahb_offer_key *b);

/* 1 when a dma-buf of DRM format drm_format holds the bytes of an AHardwareBuffer of ahb_format
 * (4 bytes a pixel, in the same order; an X format's padding byte is the A byte, unused). */
int ahb_offer_format_fits(uint32_t ahb_format, uint32_t drm_format);

/* NULL when the wl_buffer is the offered buffer, laid out as it was allocated; otherwise why not
 * (for the log). The dma-buf identity is checked separately (ahb_offer_key_has_fd). */
const char *ahb_offer_mismatch(const struct ahb_offer_desc *o, const struct ahb_offer_plane *p);

#endif
