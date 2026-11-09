#pragma once
/*
 * What the compositor has seen on the way to a session's first frame, for the loading screen's
 * watchdog (FirstFrameWatchdog.kt): whether gamescope is connected, how many GPU frames came in,
 * how many could not be imported, how many were copied by the CPU instead, how many reached the
 * screen. And that CPU copy: a LINEAR 32-bit dma-buf the compositor's driver refuses to import is
 * read through a mapping of it (upstream DroidDeck #1655: Galaxy Tab A11+ with Mali-G615 r44p1,
 * every frame of gamescope refused, nothing on screen while Steam ran behind the loading screen).
 */
#include <stddef.h>
#include <stdint.h>

enum ff_counter {
    FF_GAMESCOPE = 0,     /* gamescope clients connected now */
    FF_DMABUF,            /* GPU frames (dma-buf commits) since the reset */
    FF_IMPORT_FAILED,     /* dma-bufs the driver refused, with no CPU copy either */
    FF_CPU_COPIES,        /* frames copied by the CPU instead */
    FF_FRAMES,            /* frames on screen */
    FF_SHM,               /* window redraws (shared-memory buffers) */
    FF_COUNT
};

void ff_client(const char *name, int connected);
void ff_add(enum ff_counter c);
void ff_reset(void);
/* Copies the counters to out (n entries at most); returns how many. */
int ff_probe(int *out, int n);

/* A dma-buf the CPU can copy into a shared-memory image as it is: one plane, LINEAR, AR24 or
 * XR24 (the byte order of wl_shm's ARGB8888/XRGB8888). */
int dmabuf_cpu_copyable(uint32_t drm_format, uint64_t modifier, int n_planes, int32_t w, int32_t h, uint32_t stride);

struct dmabuf_cpu {
    void *map;
    size_t len;
    int failed;
};
/* The dma-buf's pixels (offset applied); the mapping is kept for the buffer's next frames. NULL when
 * it cannot be mapped or is smaller than its rows (then never tried again). */
const void *dmabuf_cpu_pixels(struct dmabuf_cpu *m, int fd, uint32_t offset, uint32_t stride, int32_t w, int32_t h);
/* Around the CPU's read: waits for the GPU's writes (DMA_BUF_IOCTL_SYNC); a file that is no
 * dma-buf (host tests) is read as it is. */
void dmabuf_cpu_begin(int fd);
void dmabuf_cpu_end(int fd);
void dmabuf_cpu_release(struct dmabuf_cpu *m);
