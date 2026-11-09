/* bridge_pages.c against a stand-in for the MaliBridge server's vkb_dmabuf_ahb_lookup. */
#include "bridge_pages.h"
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>

static int fails;
#define CHECK(c, what) do { if (!(c)) { fails++; printf("FAIL %s\n", what); } else printf("PASS %s\n", what); } while (0)

int main(int argc, char **argv) {
    struct bridge_pages bp;
    /* nothing loaded yet: no bridge in this process */
    if (argc > 1 && !strcmp(argv[1], "absent")) {
        CHECK(!bridge_pages_present(), "no server library loaded: no bridge");
        CHECK(!bridge_pages_lookup(7, &bp), "no bridge: every fd goes the dma-buf way");
        return fails != 0;
    }
    if (!dlopen(BRIDGE_PAGES_LIB, RTLD_NOW | RTLD_GLOBAL)) { printf("FAIL load %s\n", dlerror()); return 1; }
    CHECK(bridge_pages_present(), "the server's function found without loading anything");
    CHECK(bridge_pages_lookup(42, &bp) == 1, "the bridge's fd found");
    CHECK(bp.abi == BRIDGE_PAGES_ABI && bp.struct_size == sizeof(bp), "ABI and size passed and kept");
    CHECK(bp.has_image && bp.width == 1280 && bp.height == 800 && bp.format == 44 && bp.tiling == 0 &&
          bp.usage == 0x0d && bp.flags == 0x8 && bp.view_format_count == 2 && bp.view_formats[1] == 50 &&
          bp.image_bytes == 4194304 && bp.bytes == 4194304 && bp.ahb == (void *)0x1234, "the image as the server described it");
    CHECK(bridge_pages_lookup(5, &bp) == 0 && !bp.has_image && !bp.ahb, "another fd: not the bridge's, nothing left in the struct");
    CHECK(bridge_pages_lookup(-1, &bp) == 0, "no fd");
    CHECK(bridge_pages_lookup(43, &bp) == 0 && !bp.ahb, "the server refusing the ABI: not taken");
    printf(fails ? "FAILED: %d\n" : "OK: 0 failures\n", fails);
    return fails != 0;
}
