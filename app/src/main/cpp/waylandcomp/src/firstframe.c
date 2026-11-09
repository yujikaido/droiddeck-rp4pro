#include "firstframe.h"

#include <errno.h>
#include <stdatomic.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>

#include <linux/dma-buf.h>
#include <unistd.h>

#define FOURCC(a, b, c, d) ((uint32_t)(a) | ((uint32_t)(b) << 8) | ((uint32_t)(c) << 16) | ((uint32_t)(d) << 24))

static _Atomic int g_ff[FF_COUNT];

void ff_client(const char *name, int connected)
{
    if (!name || strncmp(name, "gamescope", 9)) return;
    if (connected) atomic_fetch_add(&g_ff[FF_GAMESCOPE], 1);
    else if (atomic_fetch_sub(&g_ff[FF_GAMESCOPE], 1) <= 0) atomic_store(&g_ff[FF_GAMESCOPE], 0);
}

void ff_add(enum ff_counter c)
{
    if (c > FF_GAMESCOPE && c < FF_COUNT) atomic_fetch_add(&g_ff[c], 1);
}

/* A new session: its frames count from here; gamescope's connection stays what it is. */
void ff_reset(void)
{
    for (int i = FF_GAMESCOPE + 1; i < FF_COUNT; i++) atomic_store(&g_ff[i], 0);
}

int ff_probe(int *out, int n)
{
    int k = 0;
    for (; k < n && k < FF_COUNT; k++) out[k] = atomic_load(&g_ff[k]);
    return k;
}

int dmabuf_cpu_copyable(uint32_t drm_format, uint64_t modifier, int n_planes, int32_t w, int32_t h, uint32_t stride)
{
    if (modifier != 0 || n_planes != 1 || w <= 0 || h <= 0) return 0;
    if (drm_format != FOURCC('A', 'R', '2', '4') && drm_format != FOURCC('X', 'R', '2', '4')) return 0;
    return stride >= (uint32_t)w * 4u && stride % 4u == 0;
}

const void *dmabuf_cpu_pixels(struct dmabuf_cpu *m, int fd, uint32_t offset, uint32_t stride, int32_t w, int32_t h)
{
    if (m->failed || fd < 0 || w <= 0 || h <= 0) return NULL;
    if (m->map) return (const uint8_t *)m->map + offset;
    /* the rows the copy reads: every stride but the last row's padding */
    size_t need = (size_t)offset + (size_t)stride * (size_t)(h - 1) + (size_t)w * 4u;
    off_t size = lseek(fd, 0, SEEK_END);
    lseek(fd, 0, SEEK_SET);
    if (size < 0 || (size_t)size < need) {
        m->failed = 1;
        return NULL;
    }
    void *p = mmap(NULL, (size_t)size, PROT_READ, MAP_SHARED, fd, 0);
    if (p == MAP_FAILED) {
        m->failed = 1;
        return NULL;
    }
    m->map = p;
    m->len = (size_t)size;
    return (const uint8_t *)p + offset;
}

static void sync_fd(int fd, uint64_t flags)
{
    struct dma_buf_sync s = {.flags = flags | DMA_BUF_SYNC_READ};
    int r;
    do r = ioctl(fd, DMA_BUF_IOCTL_SYNC, &s);
    while (r < 0 && (errno == EINTR || errno == EAGAIN));
}

void dmabuf_cpu_begin(int fd) { sync_fd(fd, DMA_BUF_SYNC_START); }
void dmabuf_cpu_end(int fd) { sync_fd(fd, DMA_BUF_SYNC_END); }

void dmabuf_cpu_release(struct dmabuf_cpu *m)
{
    if (m->map) munmap(m->map, m->len);
    m->map = NULL;
    m->len = 0;
}
