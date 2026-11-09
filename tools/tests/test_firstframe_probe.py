"""The compositor's first-frame counters and its CPU copy of a dma-buf the driver refuses (firstframe.c).

Upstream DroidDeck #1655 (Galaxy Tab A11+, Mali-G615 r44p1): the compositor's driver refused every
LINEAR frame gamescope sent ("dmabuf import failed"), nothing reached the screen, and the loading
screen said "Waiting for Steam's first frame" until the player gave up. A LINEAR AR24/XR24 frame is
now read through a mapping of the dma-buf instead; this runs that code on the host, a memfd standing
in for the dma-buf, with the row layout gamescope uses (stride wider than the row, an offset).
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / 'app/src/main/cpp/waylandcomp/src'

TEST_MAIN = r'''
#define _GNU_SOURCE
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include "firstframe.h"

#define FOURCC(a, b, c, d) ((uint32_t)(a) | ((uint32_t)(b) << 8) | ((uint32_t)(c) << 16) | ((uint32_t)(d) << 24))

static int probe(int i) { int v[FF_COUNT]; assert(ff_probe(v, FF_COUNT) == FF_COUNT); return v[i]; }

/* What vkp_image_upload_shm does with the pixels: w * 4 bytes of each of h rows, stride apart. */
static void upload(uint8_t *dst, const uint8_t *src, int w, int h, uint32_t stride) {
    for (int y = 0; y < h; y++) memcpy(dst + (size_t)y * w * 4, src + (size_t)y * stride, (size_t)w * 4);
}

static int frame_fd(int w, int h, uint32_t stride, uint32_t offset, size_t size) {
    int fd = memfd_create("frame", 0);
    assert(fd >= 0);
    uint8_t *buf = calloc(1, size);
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w * 4; x++)
            if (offset + (size_t)y * stride + x < size) buf[offset + (size_t)y * stride + x] = (uint8_t)(y * 31 + x);
    assert(write(fd, buf, size) == (ssize_t)size);
    free(buf);
    return fd;
}

int main(void) {
    /* gamescope's connections, other programs not counted, never below 0 */
    ff_client("gamescope", 1);
    ff_client("Xwayland", 1);
    assert(probe(FF_GAMESCOPE) == 1);
    ff_client("gamescope", 1);
    ff_client("gamescope", 0);
    ff_client("gamescope", 0);
    ff_client("gamescope", 0);
    assert(probe(FF_GAMESCOPE) == 0);
    ff_client("gamescope", 1);

    /* counters from the reset on; gamescope's connection is not a count */
    ff_add(FF_DMABUF); ff_add(FF_DMABUF); ff_add(FF_IMPORT_FAILED); ff_add(FF_FRAMES);
    ff_add(FF_GAMESCOPE); ff_add(FF_COUNT);
    assert(probe(FF_DMABUF) == 2 && probe(FF_IMPORT_FAILED) == 1 && probe(FF_FRAMES) == 1);
    assert(probe(FF_GAMESCOPE) == 1);
    ff_reset();
    assert(probe(FF_DMABUF) == 0 && probe(FF_IMPORT_FAILED) == 0 && probe(FF_FRAMES) == 0);
    assert(probe(FF_GAMESCOPE) == 1);
    int few[2];
    assert(ff_probe(few, 2) == 2);

    /* what the CPU copies: one LINEAR plane of AR24/XR24, rows at least w * 4 apart */
    const uint32_t AR24 = FOURCC('A', 'R', '2', '4'), XR24 = FOURCC('X', 'R', '2', '4'), AB24 = FOURCC('A', 'B', '2', '4');
    assert(dmabuf_cpu_copyable(AR24, 0, 1, 1280, 720, 5120));
    assert(dmabuf_cpu_copyable(XR24, 0, 1, 1280, 720, 5376));
    assert(!dmabuf_cpu_copyable(AB24, 0, 1, 1280, 720, 5120));             /* other byte order */
    assert(!dmabuf_cpu_copyable(AR24, 0x0800000000000001ull, 1, 1280, 720, 5120)); /* tiled */
    assert(!dmabuf_cpu_copyable(AR24, 0, 2, 1280, 720, 5120));
    assert(!dmabuf_cpu_copyable(AR24, 0, 1, 1280, 720, 5116));             /* rows overlap */
    assert(!dmabuf_cpu_copyable(AR24, 0, 1, 0, 720, 5120));

    /* a frame with an offset and padded rows, the buffer ending right after the last row's pixels */
    const int w = 5, h = 3;
    const uint32_t stride = 32, offset = 64;
    size_t exact = offset + (size_t)stride * (h - 1) + (size_t)w * 4;
    int fd = frame_fd(w, h, stride, offset, exact);
    struct dmabuf_cpu m = {0};
    const uint8_t *px = dmabuf_cpu_pixels(&m, fd, offset, stride, w, h);
    assert(px);
    dmabuf_cpu_begin(fd); /* no dma-buf: read as it is, no wait */
    uint8_t got[5 * 3 * 4];
    upload(got, px, w, h, stride);
    dmabuf_cpu_end(fd);
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w * 4; x++) assert(got[y * w * 4 + x] == (uint8_t)(y * 31 + x));
    assert(dmabuf_cpu_pixels(&m, fd, offset, stride, w, h) == px); /* the mapping is kept */
    dmabuf_cpu_release(&m);
    assert(!m.map);
    dmabuf_cpu_release(&m);
    close(fd);

    /* one byte short of the last row: not mapped, and not tried again */
    fd = frame_fd(w, h, stride, offset, exact - 1);
    struct dmabuf_cpu s = {0};
    assert(!dmabuf_cpu_pixels(&s, fd, offset, stride, w, h));
    assert(s.failed && !s.map);
    assert(!dmabuf_cpu_pixels(&s, fd, offset, stride, w, h));
    close(fd);
    assert(!dmabuf_cpu_pixels(&s, -1, 0, 4, 1, 1));
    puts("OK");
    return 0;
}
'''


class FirstFrameProbeTest(unittest.TestCase):
    def test_counters_and_cpu_copy(self):
        cc = shutil.which(os.environ.get('CC', 'cc'))
        if not cc:
            self.skipTest('A host C compiler is required')
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / 'main.c').write_text(TEST_MAIN)
            exe = tmp / 'firstframe_test'
            build = subprocess.run([cc, '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-I', str(SRC), str(tmp / 'main.c'),
                                    str(SRC / 'firstframe.c'), '-o', str(exe)], capture_output=True, text=True)
            self.assertEqual(build.returncode, 0, build.stderr)
            run = subprocess.run([str(exe)], capture_output=True, text=True, timeout=30)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
            self.assertIn('OK', run.stdout)

    def test_the_compositor_uses_it(self):
        compositor = (SRC / 'compositor.c').read_text()
        take = compositor[compositor.index('static void take_dmabuf('):]
        take = take[:take.index('\n}\n')]
        # the copy only where the import failed and no display layer shows the buffer
        self.assertIn('if (!b->img && !ahb_swapchain_has_ahb(b) &&', take)
        self.assertIn('dmabuf_cpu_pixels(&b->cpu, b->fd[0], b->offset[0], b->stride[0], b->width, b->height)', take)
        self.assertIn('s->has_content = b->img != NULL || cpu_copied;', take)
        self.assertIn('ff_add(cpu_copied ? FF_CPU_COPIES : FF_IMPORT_FAILED);', take)
        unref = compositor[compositor.index('void dmabuf_buffer_unref('):]
        self.assertIn('dmabuf_cpu_release(&b->cpu);', unref[:unref.index('\n}\n')])
        cmake = (REPO / 'app/src/main/cpp/waylandcomp/CMakeLists.txt').read_text()
        self.assertIn('src/firstframe.c', cmake)


if __name__ == '__main__':
    unittest.main()
