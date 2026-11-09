/* The MaliBridge server in this process hands dma-bufs out over AHardwareBuffers where the GPU
 * driver has no dma-buf memory (Kirin, Maleoon; its VKBRIDGE_DMABUF_VIA_AHB=1 elsewhere). The
 * compositor cannot import those as dma-bufs there either: it asks the server for the buffer and
 * the image the memory was made for, and makes the same image over the same buffer.
 * The struct and the answer are the server's vkb_dmabuf_ahb_lookup() (MaliBridge
 * common/vkb_dmabuf_ahb.h, ABI 1): fields are only ever added at the end. */
#ifndef BRIDGE_PAGES_H
#define BRIDGE_PAGES_H
#include <stdint.h>

#define BRIDGE_PAGES_ABI 1

struct bridge_pages {
    uint32_t abi, struct_size;
    void *ahb; /* AHardwareBuffer*, one reference for the caller */
    uint64_t bytes;
    uint32_t has_image;
    uint32_t format, width, height, tiling, usage, flags;
    uint32_t mip_levels, array_layers, samples;
    uint32_t view_format_count;
    uint32_t view_formats[8];
    uint64_t row_pitch;
    uint64_t image_bytes;
};

/* 1: fd is one of the bridge's, *out filled (release out->ahb); 0: not (or no bridge here). */
int bridge_pages_lookup(int fd, struct bridge_pages *out);
/* 1 once a lookup has found the bridge's function in this process. */
int bridge_pages_present(void);
#endif
