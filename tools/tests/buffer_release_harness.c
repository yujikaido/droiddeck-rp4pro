/* Host harness for the compositor's wl_buffer release paths (compositor.c: the paced release queue,
 * drop_dmabuf, droiddeck_release_buffer), compiled from the real source text that
 * test_buffer_release.py cuts out. The client side copies what gamescope's nested Wayland backend
 * checks: a buffer is "acquired" when it is committed and a release while it is not acquired is the
 * error "Compositor released us but we were not acquired". The harness also keeps its own model of
 * which surfaces show which buffer, so a release of a buffer still on screen is caught too. */
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* ---- minimal libwayland surface ---- */
struct wl_list { struct wl_list *prev, *next; };
static void wl_list_init(struct wl_list *l) { l->prev = l->next = l; }
static void wl_list_insert(struct wl_list *l, struct wl_list *e) {
    e->prev = l; e->next = l->next; l->next = e; e->next->prev = e;
}
static void wl_list_remove(struct wl_list *e) {
    e->prev->next = e->next; e->next->prev = e->prev; e->next = e->prev = NULL;
}
#define wl_container_of(ptr, sample, member) \
    (__typeof__(sample))((char *)(ptr) - offsetof(__typeof__(*sample), member))
#define wl_list_for_each(pos, head, member) \
    for (pos = wl_container_of((head)->next, pos, member); &pos->member != (head); \
         pos = wl_container_of(pos->member.next, pos, member))
#define wl_list_for_each_safe(pos, tmp, head, member) \
    for (pos = wl_container_of((head)->next, pos, member), \
         tmp = wl_container_of((pos)->member.next, tmp, member); \
         &pos->member != (head); \
         pos = tmp, tmp = wl_container_of(pos->member.next, tmp, member))

struct wl_listener;
typedef void (*wl_notify_func_t)(struct wl_listener *listener, void *data);
struct wl_listener { struct wl_list link; wl_notify_func_t notify; };
struct dmabuf_buffer { int refs; void *ahb_state; unsigned release_sent; };
struct wl_resource {
    uint32_t id;
    struct wl_list destroy_listeners;
    struct dmabuf_buffer *db;
};
static void wl_resource_add_destroy_listener(struct wl_resource *r, struct wl_listener *l) {
    wl_list_insert(r->destroy_listeners.prev, &l->link);
}
static uint32_t wl_resource_get_id(struct wl_resource *r) { return r->id; }
struct wl_display;
static struct wl_display *g_display;
static void wl_display_flush_clients(struct wl_display *d) { (void)d; }
#define WLOGE(...) ((void)0)
#define WLOGI(...) ((void)0)

/* ---- compositor state the cut-out code uses ---- */
struct surface {
    struct wl_resource *resource;
    struct wl_list link;
    struct wl_resource *dmabuf;
    struct dmabuf_buffer *dmabuf_buf;
    struct wl_listener dmabuf_destroy;
    int64_t next_release_ns;
    int releases_pending;
    struct buf *model; /* harness only: what this surface shows */
};
struct pending_release {
    struct wl_resource *buffer;
    struct wl_listener destroy;
    struct surface *surface;
    int64_t at_ns;
    int64_t since_ns;
    struct wl_list link;
};
static struct wl_list g_surfaces;
static struct wl_list g_pending_releases;
static int g_release_timer_fd = -1;
static int64_t g_refresh_ns = 16666667;
static volatile int g_fps_limit;
static volatile int g_release_unpaced;
static struct {
    unsigned releases, releases_held;
    int64_t release_ns, release_max_ns;
} g_perf;

static int64_t g_now = 1000000000LL;
static int64_t now_ns(void) { return g_now; }

#define TFD_TIMER_ABSTIME 1
static int64_t g_timer_at; /* 0 = disarmed */
static int timerfd_settime(int fd, int flags, const struct itimerspec *its, struct itimerspec *old) {
    (void)fd; (void)flags; (void)old;
    g_timer_at = (int64_t)its->it_value.tv_sec * 1000000000LL + its->it_value.tv_nsec;
    return 0;
}

static void droiddeck_log(const char *tag, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "[%s] ", tag);
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    va_end(ap);
}

static struct dmabuf_buffer *get_dmabuf(struct wl_resource *buffer) { return buffer ? buffer->db : NULL; }
static void dmabuf_buffer_unref(struct dmabuf_buffer *b) { if (b) b->refs--; }

/* Zero-copy layer: when on, a dropped buffer stays with the layer (ahb_swapchain.c) and comes back
 * through droiddeck_release_buffer() later. */
static int g_layer_holds;
static struct wl_resource *g_layer_buffer;
static struct surface *g_layer_surface;
static int ahb_swapchain_defer_release(struct dmabuf_buffer *b, struct wl_resource *buffer, struct surface *s, int paced) {
    (void)b;
    if (!g_layer_holds) return 0;
    g_layer_buffer = buffer;
    g_layer_surface = paced ? s : NULL;
    return 1;
}

/* ---- the client (gamescope's view) ---- */
struct buf {
    struct wl_resource res;
    struct dmabuf_buffer db;
    int acquired, shown, releases;
    int64_t last_release_ns;
};
static unsigned g_not_acquired, g_released_on_screen, g_releases;
static int g_log_releases;

static void wl_buffer_send_release(struct wl_resource *r) {
    struct buf *b = wl_container_of(r, b, res);
    b->releases++;
    b->last_release_ns = g_now;
    g_releases++;
    if (b->shown > 0) g_released_on_screen++;
    if (!b->acquired) g_not_acquired++;
    b->acquired = 0;
    if (g_log_releases) printf("release %u at %lld\n", r->id, (long long)(g_now - 1000000000LL));
}

#include "release_paths.inc"

#ifndef HAVE_BUFFER_COMMITTED
static void buffer_committed(struct surface *s, struct wl_resource *buffer) { (void)s; (void)buffer; }
#endif

static void on_dmabuf_destroyed(struct wl_listener *l, void *data) {
    struct surface *s = wl_container_of(l, s, dmabuf_destroy);
    (void)data;
    wl_list_remove(&s->dmabuf_destroy.link);
    s->dmabuf = NULL;
}

static struct buf *new_buf(uint32_t id) {
    struct buf *b = calloc(1, sizeof(*b));
    b->res.id = id;
    b->res.db = &b->db;
    b->db.refs = 1;
    wl_list_init(&b->res.destroy_listeners);
    return b;
}
static struct surface *new_surface(uint32_t id) {
    struct surface *s = calloc(1, sizeof(*s));
    s->resource = calloc(1, sizeof(*s->resource));
    s->resource->id = id;
    wl_list_init(&s->resource->destroy_listeners);
    wl_list_insert(g_surfaces.prev, &s->link);
    return s;
}

/* surface_commit() for a dma-buf (take_dmabuf) or for a non-dma-buf / NULL attach (drop_dmabuf). */
static void commit(struct surface *s, struct buf *b) {
    if (s->model != b) {
        if (s->model) s->model->shown--;
        if (b) b->shown++;
        s->model = b;
    }
    if (b) b->acquired = 1;
    if (b) buffer_committed(s, &b->res);
    if (b) {
        if (s->dmabuf == &b->res && s->dmabuf_buf == &b->db) return;
        drop_dmabuf(s, 1);
        s->dmabuf = &b->res;
        s->dmabuf_buf = &b->db;
        b->db.refs++;
        s->dmabuf_destroy.notify = on_dmabuf_destroyed;
        wl_resource_add_destroy_listener(&b->res, &s->dmabuf_destroy);
    } else {
        drop_dmabuf(s, 1);
    }
}

/* surface_resource_destroy(): unpaced drop. */
static void destroy_surface(struct surface *s) {
    if (s->model) { s->model->shown--; s->model = NULL; }
    pending_releases_forget_surface(s);
    drop_dmabuf(s, 0);
    wl_list_remove(&s->link);
}

/* The event loop: the timer fires at its armed time, in order, until `until`. */
static void run_until(int64_t until) {
    while (g_timer_at && g_timer_at <= until) {
        if (g_timer_at > g_now) g_now = g_timer_at;
        int64_t before = g_timer_at;
        on_release_timer(g_release_timer_fd, 0, NULL);
        if (g_timer_at == before) break; /* nothing left due */
    }
    if (until > g_now) g_now = until;
}
static void advance_ms(double ms) { run_until(g_now + (int64_t)(ms * 1000000.0)); }

static void setup(int fps_limit, int unpaced) {
    int p[2];
    assert(pipe2(p, O_NONBLOCK) == 0);
    g_release_timer_fd = p[0];
    wl_list_init(&g_surfaces);
    wl_list_init(&g_pending_releases);
    g_fps_limit = fps_limit;
    g_release_unpaced = unpaced;
}

#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "FAILED %s:%d: %s (releases %u, not acquired %u, on screen %u)\n", \
    __FILE__, __LINE__, #cond, g_releases, g_not_acquired, g_released_on_screen); return 1; } } while (0)

/* gamescope moves the game's frame from its window surface to a subsurface above a black backing
 * (Steam notification up), then goes on presenting on the subsurface. */
static int case_plane_move(void) {
    setup(60, 0);
    struct surface *win = new_surface(10), *sub = new_surface(11);
    struct buf *b1 = new_buf(101), *b2 = new_buf(102), *b3 = new_buf(103);
    commit(win, b1);
    advance_ms(16.7);
    commit(sub, b1);   /* the frame goes to the subsurface... */
    commit(win, NULL); /* ...and the window gets the black backing (single-pixel buffer) */
    advance_ms(50);
    CHECK(b1->releases == 0);
    CHECK(g_released_on_screen == 0);
    commit(sub, b2);
    advance_ms(50);
    CHECK(b1->releases == 1);
    commit(sub, b3);
    advance_ms(50);
    commit(sub, b1);
    advance_ms(50);
    CHECK(b2->releases == 1 && b3->releases == 1);
    CHECK(g_not_acquired == 0 && g_released_on_screen == 0);
    return 0;
}

/* The notification goes: the subsurface lets go of the frame and the window shows it again in the
 * same dispatch, before the paced release is due. */
static int case_move_back(void) {
    setup(60, 0);
    struct surface *win = new_surface(10), *sub = new_surface(11);
    struct buf *b1 = new_buf(101), *b2 = new_buf(102);
    commit(sub, b1);
    advance_ms(40);
    commit(sub, NULL);
    commit(win, b1);
    advance_ms(50);
    CHECK(g_released_on_screen == 0);
    CHECK(b1->releases == 0);
    commit(win, b2);
    advance_ms(50);
    CHECK(b1->releases == 1);
    CHECK(g_not_acquired == 0 && g_released_on_screen == 0);
    return 0;
}

/* One surface shows a buffer again before its paced release is due. */
static int case_reshown(void) {
    setup(30, 0);
    struct surface *win = new_surface(10);
    struct buf *b1 = new_buf(101), *b2 = new_buf(102), *b3 = new_buf(103);
    commit(win, b1);
    advance_ms(40);
    commit(win, b2);  /* b1 queued */
    commit(win, b1);  /* b1 back before the timer: b2 queued too */
    advance_ms(100);
    CHECK(g_released_on_screen == 0);
    CHECK(b1->releases == 0 && b2->releases == 1);
    commit(win, b3);
    advance_ms(100);
    CHECK(b1->releases == 1);
    CHECK(g_not_acquired == 0 && g_released_on_screen == 0);
    return 0;
}

/* A surface showing a shared buffer is destroyed (unpaced drop). */
static int case_destroy_shared(void) {
    setup(60, 0);
    struct surface *win = new_surface(10), *sub = new_surface(11);
    struct buf *b1 = new_buf(101), *b2 = new_buf(102);
    commit(win, b1);
    commit(sub, b1);
    destroy_surface(win);
    advance_ms(50);
    CHECK(b1->releases == 0 && g_released_on_screen == 0);
    commit(sub, b2);
    advance_ms(50);
    CHECK(b1->releases == 1);
    CHECK(g_not_acquired == 0 && g_released_on_screen == 0);
    return 0;
}

/* Two surfaces each drop the same buffer before either paced release is due: one release only
 * (the double-release guard), and none while it is still shown. */
static int case_double_queue(void) {
    setup(30, 0);
    struct surface *win = new_surface(10), *sub = new_surface(11);
    struct buf *b1 = new_buf(101), *b2 = new_buf(102), *b3 = new_buf(103);
    commit(win, b1);
    advance_ms(40);
    commit(win, b2);  /* b1 queued from the window */
    commit(sub, b1);  /* shown again on the subsurface */
    commit(sub, b3);  /* and dropped there too, before the first release is due */
    advance_ms(200);
    CHECK(b1->releases == 1);
    CHECK(g_not_acquired == 0 && g_released_on_screen == 0);
    /* a queued release followed by an unpaced one for the same buffer */
    commit(win, b1);
    commit(win, b2);  /* b1 queued */
    g_release_unpaced = 1;
    commit(sub, b1);
    commit(sub, b3);  /* unpaced: sent now */
    g_release_unpaced = 0;
    advance_ms(200);
    CHECK(b1->releases == 2);
    CHECK(g_not_acquired == 0 && g_released_on_screen == 0);
    return 0;
}

/* Zero-copy layer: the window lets go of a buffer the display layer still holds, shows it again,
 * then the layer lets go of it. */
static int case_layer(void) {
    setup(60, 0);
    struct surface *win = new_surface(10);
    struct buf *b1 = new_buf(101), *b2 = new_buf(102);
    commit(win, b1);
    g_layer_holds = 1;
    commit(win, b2);  /* b1 stays with the layer */
    g_layer_holds = 0;
    CHECK(g_layer_buffer == &b1->res);
    commit(win, b1);  /* shown again */
    droiddeck_release_buffer(g_layer_surface, g_layer_buffer, g_layer_surface != NULL, g_now); /* the layer lets go */
    advance_ms(50);
    CHECK(g_released_on_screen == 0);
    CHECK(b1->releases == 0);
    commit(win, b2);
    advance_ms(50);
    CHECK(b1->releases == 1);
    CHECK(g_not_acquired == 0 && g_released_on_screen == 0);
    return 0;
}

/* The guard itself: a second release of a buffer with no commit in between is never sent (here the
 * layer path is asked twice, unpaced). */
static int case_double_guard(void) {
    setup(60, 0);
    struct surface *win = new_surface(10);
    struct buf *b1 = new_buf(101), *b2 = new_buf(102);
    commit(win, b1);
    g_layer_holds = 1;
    commit(win, b2);
    g_layer_holds = 0;
    droiddeck_release_buffer(NULL, &b1->res, 0, g_now);
    droiddeck_release_buffer(NULL, &b1->res, 0, g_now);
    advance_ms(50);
    CHECK(b1->releases == 1);
    commit(win, b1);  /* committed again: its next release goes out */
    commit(win, b2);
    advance_ms(50);
    CHECK(b1->releases == 2);
    CHECK(g_not_acquired == 0 && g_released_on_screen == 0);
    return 0;
}

/* The normal game path: one surface, a client that only reuses released buffers (3 in its pool),
 * presenting every 16.67 ms with jitter. Prints every release; the test compares the list with the
 * one the code before this fix printed (release times must not move). */
static int case_one_surface(int fps_limit, int unpaced) {
    setup(fps_limit, unpaced);
    g_log_releases = 1;
    struct surface *win = new_surface(10);
    struct buf *pool[3] = { new_buf(101), new_buf(102), new_buf(103) };
    int next = 0;
    unsigned seed = 7;
    for (int frame = 0; frame < 120; frame++) {
        struct buf *b = NULL;
        for (int k = 0; k < 3; k++) {
            struct buf *c = pool[(next + k) % 3];
            if (!c->acquired) { b = c; next = (next + k + 1) % 3; break; }
        }
        if (b) commit(win, b);
        seed = seed * 1103515245u + 12345u;
        advance_ms(16.67 + (double)((seed >> 16) % 9) - 4.0);
    }
    advance_ms(200);
    CHECK(g_not_acquired == 0 && g_released_on_screen == 0);
    return 0;
}

int main(int argc, char **argv) {
    assert(argc == 2);
    const char *c = argv[1];
    if (!strcmp(c, "plane_move")) return case_plane_move();
    if (!strcmp(c, "move_back")) return case_move_back();
    if (!strcmp(c, "reshown")) return case_reshown();
    if (!strcmp(c, "destroy_shared")) return case_destroy_shared();
    if (!strcmp(c, "double_queue")) return case_double_queue();
    if (!strcmp(c, "layer")) return case_layer();
    if (!strcmp(c, "double_guard")) return case_double_guard();
    if (!strcmp(c, "one_surface_paced")) return case_one_surface(30, 0);
    if (!strcmp(c, "one_surface_refresh")) return case_one_surface(0, 0);
    if (!strcmp(c, "one_surface_unpaced")) return case_one_surface(0, 1);
    fprintf(stderr, "unknown case %s\n", c);
    return 2;
}
