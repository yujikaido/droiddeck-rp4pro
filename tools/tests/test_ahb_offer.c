/* Host test for waylandcomp/src/ahb_offer.c: which dma-buf wl_buffer an offered AHardwareBuffer is.
 * memfds stand in for dma-bufs: the rule is the same (one inode per buffer, whatever fd names it). */
#include "ahb_offer.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <unistd.h>

#define FOURCC(a, b, c, d) ((uint32_t)(a) | ((uint32_t)(b) << 8) | ((uint32_t)(c) << 16) | ((uint32_t)(d) << 24))

/* fd through a Unix socket, as gamescope passes a game's dma-buf on to the host compositor. */
static int pass_fd(int fd) {
    int sv[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sv) == 0);
    char c = 0, cb[CMSG_SPACE(sizeof(int))];
    struct iovec io = {&c, 1};
    struct msghdr m = {0};
    m.msg_iov = &io; m.msg_iovlen = 1; m.msg_control = cb; m.msg_controllen = sizeof(cb);
    struct cmsghdr *h = CMSG_FIRSTHDR(&m);
    h->cmsg_level = SOL_SOCKET; h->cmsg_type = SCM_RIGHTS; h->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(h), &fd, sizeof(int));
    assert(sendmsg(sv[0], &m, 0) == 1);
    memset(cb, 0, sizeof(cb));
    assert(recvmsg(sv[1], &m, 0) == 1);
    int got;
    memcpy(&got, CMSG_DATA(CMSG_FIRSTHDR(&m)), sizeof(int));
    close(sv[0]); close(sv[1]);
    return got;
}

int main(void) {
    int meta = memfd_create("gralloc-metadata", 0), pages = memfd_create("gralloc-pages", 0);
    int other = memfd_create("another-buffer", 0);
    assert(meta >= 0 && pages >= 0 && other >= 0);

    /* The handle as Mali's gralloc lays it out: a closed slot, the pages, the metadata. */
    int handle[3] = {-1, pages, meta};
    struct ahb_offer_key k;
    assert(ahb_offer_key_from_fds(handle, 3, &k) == 2);
    assert(ahb_offer_key_has_fd(&k, pages));
    int dup_fd = dup(pages), passed = pass_fd(pages);
    assert(ahb_offer_key_has_fd(&k, dup_fd));     /* the bridge hands out a dup */
    assert(ahb_offer_key_has_fd(&k, passed));     /* and gamescope passes it on */
    assert(!ahb_offer_key_has_fd(&k, other));
    assert(!ahb_offer_key_has_fd(&k, -1));
    int none[2] = {-1, -1};
    struct ahb_offer_key empty;
    assert(ahb_offer_key_from_fds(none, 2, &empty) == 0);
    assert(!ahb_offer_key_has_fd(&empty, pages));

    struct ahb_offer_key k2, k3;
    int h2[1] = {passed}, h3[1] = {other};
    ahb_offer_key_from_fds(h2, 1, &k2);
    ahb_offer_key_from_fds(h3, 1, &k3);
    assert(ahb_offer_keys_overlap(&k, &k2));
    assert(!ahb_offer_keys_overlap(&k, &k3));

    /* Formats: the same four bytes a pixel in the same order, or nothing. */
    const uint32_t AR24 = FOURCC('A', 'R', '2', '4'), XR24 = FOURCC('X', 'R', '2', '4');
    const uint32_t AB24 = FOURCC('A', 'B', '2', '4'), XB24 = FOURCC('X', 'B', '2', '4');
    const uint32_t AB30 = FOURCC('A', 'B', '3', '0'), RG16 = FOURCC('R', 'G', '1', '6');
    assert(ahb_offer_format_fits(5, AR24) && ahb_offer_format_fits(5, XR24));
    assert(!ahb_offer_format_fits(5, AB24) && !ahb_offer_format_fits(5, XB24));
    assert(ahb_offer_format_fits(1, AB24) && ahb_offer_format_fits(1, XB24) && ahb_offer_format_fits(2, XB24));
    assert(!ahb_offer_format_fits(1, AR24) && !ahb_offer_format_fits(1, AB30));
    assert(!ahb_offer_format_fits(0x2b, AB30) && !ahb_offer_format_fits(4, RG16) && !ahb_offer_format_fits(0, XR24));

    /* The layout: what gamescope forwards for Stray's 1280x720 B8G8R8A8 swapchain (gralloc pads
     * the stride to 1280 px here, any value works as long as it is the buffer's own). */
    struct ahb_offer_desc o = {1280, 720, 1280, 5};
    struct ahb_offer_plane p = {1, passed, 0, 1280 * 4, 1280, 720, XR24, 0};
    assert(ahb_offer_mismatch(&o, &p) == NULL);
    p.modifier = 0x00ffffffffffffffULL; /* DRM_FORMAT_MOD_INVALID: implicit, linear here */
    assert(ahb_offer_mismatch(&o, &p) == NULL);
    struct ahb_offer_desc padded = {1270, 720, 1280, 5};
    struct ahb_offer_plane pp = {1, passed, 0, 1280 * 4, 1270, 720, AR24, 0};
    assert(ahb_offer_mismatch(&padded, &pp) == NULL);

    struct ahb_offer_plane bad;
    bad = p; bad.n_planes = 2;                 assert(!strcmp(ahb_offer_mismatch(&o, &bad), "more than one plane"));
    bad = p; bad.width = 1920;                 assert(!strcmp(ahb_offer_mismatch(&o, &bad), "another size"));
    bad = p; bad.height = 722;                 assert(!strcmp(ahb_offer_mismatch(&o, &bad), "another size"));
    bad = p; bad.width = -1;                   assert(!strcmp(ahb_offer_mismatch(&o, &bad), "another size"));
    bad = p; bad.offset = 4096;                assert(!strcmp(ahb_offer_mismatch(&o, &bad), "a plane offset"));
    bad = p; bad.stride = 1280 * 4 + 256;      assert(!strcmp(ahb_offer_mismatch(&o, &bad), "another row stride"));
    bad = p; bad.modifier = 0x0500000000000001ULL; assert(!strcmp(ahb_offer_mismatch(&o, &bad), "a tiled or compressed layout"));
    bad = p; bad.drm_format = AB24;            assert(!strcmp(ahb_offer_mismatch(&o, &bad), "another pixel format"));
    struct ahb_offer_desc rgba = {1280, 720, 1280, 1};
    bad = p; bad.drm_format = XB24;            assert(ahb_offer_mismatch(&rgba, &bad) == NULL);

    close(meta); close(pages); close(other); close(dup_fd); close(passed);
    puts("OK: ahb_offer");
    return 0;
}
