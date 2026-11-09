/* A stand-in for libvkbridge_jni.so's vkb_dmabuf_ahb_lookup (MaliBridge server_dab_reg.c). */
#include "bridge_pages.h"
#include <string.h>
int vkb_dmabuf_ahb_lookup(int fd, struct bridge_pages *out) {
    if (out->abi != BRIDGE_PAGES_ABI || out->struct_size < sizeof(*out) || fd == 43) return -1;
    if (fd != 42) return 0;
    struct bridge_pages keep = *out;
    memset(out, 0, sizeof(*out));
    out->abi = keep.abi; out->struct_size = keep.struct_size;
    out->ahb = (void *)0x1234; out->bytes = 4194304; out->has_image = 1;
    out->format = 44; out->width = 1280; out->height = 800; out->tiling = 0; out->usage = 0x0d; out->flags = 0x8;
    out->mip_levels = 1; out->array_layers = 1; out->samples = 1;
    out->view_format_count = 2; out->view_formats[0] = 44; out->view_formats[1] = 50;
    out->image_bytes = 4194304;
    return 1;
}
