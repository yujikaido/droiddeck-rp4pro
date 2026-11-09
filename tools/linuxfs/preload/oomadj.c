#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Android's activity manager owns every score; Steam's launch wrapper asks for 900 for each game (and its install scripts), which
   Android reads as a cached app: the game and its helpers become the first thing a memory killer takes. With BL_OOM_ADJ set (the
   app sets BL_OOM_ADJ=1 for every session; BL_OOM_ADJ=0 in droiddeck-env turns it off), writes to the score files inside the session
   go to /dev/null and every guest process keeps the score it inherits from the app. v0.5.4.2 shipped the same drop; v0.5.5 lost it. */

static int enabled(void) {
  static int state = -1;
  if (state < 0) {
    const char *value = getenv("BL_OOM_ADJ");
    state = value && *value && strcmp(value, "0") ? 1 : 0;
  }
  return state;
}

static int all_digits(const char *s, size_t n) {
  if (!n) return 0;
  for (size_t i = 0; i < n; i++)
    if (s[i] < '0' || s[i] > '9') return 0;
  return 1;
}

static int score_file(const char *path) {
  if (strncmp(path, "/proc/", 6)) return 0;
  const char *p = path + 6;
  size_t len = strcspn(p, "/");
  if (!(len == 4 && !strncmp(p, "self", 4)) && !(len == 11 && !strncmp(p, "thread-self", 11)) && !all_digits(p, len)) return 0;
  p += len;
  if (!strncmp(p, "/task/", 6)) {
    p += 6;
    len = strcspn(p, "/");
    if (!all_digits(p, len)) return 0;
    p += len;
  }
  return !strcmp(p, "/oom_score_adj") || !strcmp(p, "/oom_adj");
}

static void say_once(void) {
  static int said;
  if (__atomic_exchange_n(&said, 1, __ATOMIC_RELAXED)) return;
  static const char text[] = "droiddeck: a write to oom_score_adj was dropped (Steam asks for 900, which Android treats as a cached app); the process keeps the app's score\n";
  if (write(2, text, sizeof text - 1) < 0) return;
}

__attribute__((visibility("hidden"))) const char *bl_oomadj_path(const char *path, int writable);
__attribute__((visibility("hidden"))) const char *bl_oomadj_path(const char *path, int writable) {
  if (!writable || !path || path[0] != '/' || !enabled() || !score_file(path)) return path;
  say_once();
  return "/dev/null";
}
