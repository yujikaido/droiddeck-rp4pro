# Progress log: DroidDeck Mali on the Retroid Pocket 4 Pro

This is the working log kept during the investigation, 26-27 October 2026, in the order things happened,
wrong turns included. It is lightly edited for publication: the device owner is "the owner", local paths are
generalised, and commit hashes are those of the published branches. "I" in the text is Claude, which did the
debugging over adb under the owner's direction; the device, the testing and the decisions were the owner's
(yujikaido). The polished summaries are in the README and the pull-request write-ups beside this file.

---

# DroidDeck on the Retroid Pocket 4 Pro (Mali-G77 MC9, Dimensity 1100)

Written 2026-10-26. Status (evening of the same day): **Steam runs, draws and signs in on this device** with a
one-function fix to the MaliBridge server, built here and installed over adb. See "What was wrong" at the end.

## What the device actually has

From the Vulkan hardware database (two reports from a "Moorechip Retroid Pocket 4 Pro"):

| Report | Driver | Vulkan | Android | Note |
|---|---|---|---|---|
| 27598 | Arm r32p1 (32.1.0) | 1.1.177 | 13 (TP1A.220624.014, mp1V1196) | stock firmware |
| 49869 | Arm r49p1 (49.1.0) | 1.3.278 | 13 (same build) | submitted as "New Fenix"; **not** a Retroid OTA, origin unknown |

Kernel is 4.19.191 (ION allocator, **no /dev/dma_heap**). The stock r32p1 driver has every extension the
bridge needs to promote it to Vulkan 1.2 for gamescope (timeline semaphores, renderpass2, driver_properties,
spirv_1_4, image_drm_format_modifier, queue_family_foreign, external_memory_fd, dma_buf...). It lacks
dynamic_rendering, robustness2 and synchronization2, all of which MaliBridge emulates.

## Why official DroidDeck says "GPU not supported"

DroidDeck runs Steam inside a Linux userland and gives it Mesa Turnip as the Vulkan driver. Turnip only exists
for Adreno. Inside the container there is no way to reach Android's Mali driver directly. Every Mali route
below is a way of getting Vulkan calls back out to the phone's driver (or replacing it with Mesa PanVK).

## The three Mali routes, and where each stands for the RP4 Pro

### 1. MaliBridge / "DroidDeck Mali" (ASEbrahim) — closest to working
- https://github.com/ASEbrahim/MaliBridge — prebuilt APK `DroidDeck-MaliBridge-v0.5.4.apk` on the Releases page (released 2026-10-26).
- Installs as its own app next to official DroidDeck. Mali bridge is on by default.
- RP4 Pro is tracked in **MaliBridge issue #10** ("[Mali G77 Retroid pocket 4 pro] session ended error").
- Failure chain on this device, from the source:
  1. gamescope (Steam's compositor) refuses any GPU below Vulkan 1.2. v0.5.3 added "promotion" so gamescope
     sees the r32p1 driver as 1.2. (server/server_promote.c)
  2. gamescope then hands its output images to DroidDeck's compositor as **exported dma-bufs**. Arm's driver
     imports dma-bufs but never exports them. The bridge emulates export by allocating pages from
     `/dev/dma_heap` and importing them. (server/server_memory.c, "dma-buf export of images")
  3. The RP4 Pro has no `/dev/dma_heap` (kernel 4.19). v0.5.4 added a fallback that takes the pages from a BLOB
     AHardwareBuffer instead, written specifically for "Retroid Pocket 4 Pro's Dimensity 1100 and Huawei's
     Kirin 985". It is only offered if a start-up self-check (`ahb_export_check`) passes.
  4. The reporter on issue #10 says v0.5.4 still ends at "Starting display and audio". The maintainer has
     pulled the "fixed" claim from the README until an RP4 Pro confirms it, and is waiting for logs.
- **What is needed to move this forward: a v0.5.4 session log from an RP4 Pro.** The bridge log will say which
  of the three steps above fails (look for lines starting `promotion:`, `export emulation:`, `ahb:` in
  `vkbridge-server.log`, and `flippable image:` in the gamescope log).

### 2. Upstream DroidDeck test branch `test/non-adreno-all` + The412Banner driver pairs
- https://github.com/The412Banner/droiddeck-nonadreno-drivers — test APK (build c19b5416, DroidDeck 0.6.0 base)
  at the release tagged `droiddeck-test-non-adreno-all`. Experimental, not an official release.
- Offers three routes per phone: Venus (Mesa venus over the phone's driver), PanVK pairs (Mesa's open driver on the
  stock mali_kbase kernel driver), and the Mali bridge (bundled at v0.5.3.1, i.e. **before** the RP4 Pro
  AHardwareBuffer fallback).
- RP4 Pro is tracked in **issue #7** there. Results on this device:
  - PanVK (faux123 pair): `ION_IOC_ALLOC failed: Inappropriate ioctl for device` → the Linux-side PanVK build
    allocates through dma-heap/ION interfaces the 4.19 kernel does not accept. Deeper fix, in Mesa.
  - Mali bridge: self-test 13/13 passes, then gamescope: `flippable image: no usable modifier for DRM format
    0x34325241 (2 offered by backend)` — the export problem in step 2 above, with the older bridge.
  - Venus: refused, driver reports 1.1.177 and gamescope needs 1.2.
- A cloned checkout of this branch is in `DroidDeck/` (fetched as FETCH_HEAD; `pr-1587` is the MaliBridge PR branch).

### 3. faux123's PanVK for the RP4 Pro
- https://github.com/faux123/panvk-faux123-driver-rp4pro — Mesa PanVK reporting Vulkan 1.3 on the stock kernel
  driver, no root. Proven only in GameNative-Mali so far. Its Linux-side twin is what fails with ION errors in route 2.

## Expectations once it starts

The GPU is not the ceiling, the CPU is. Switch emulation is ARM-on-ARM; Steam games are x86 translated by FEX
on four Cortex-A78 cores. MaliBridge's own device table puts Dimensity 8xxx / Mali-G615 phones at "lighter games
only" (Stardew, Rain World, GTA San Andreas). Expect that tier: 2D and older 3D titles, not modern AAA.

## What to do on the device (next step)

1. Install `DroidDeck-MaliBridge-v0.5.4.apk` from https://github.com/ASEbrahim/MaliBridge/releases/latest
   (sideload; allow installs from the browser/file manager).
2. Open **DroidDeck Mali** → **Setup**. Confirm **Mali bridge: on**, **Safe mode: on**, **DXVK: automatic**,
   **Steam menus: automatic**. On Android 13 the first Steam launch will show a "Fix it for me" button for the
   child-process restriction; use it.
3. Start a **Steam session**. Let it download the runtime and Steam (several minutes, a few GB).
4. When it fails (or works!), tap **Stop**, then **Setup → Diagnostics → Share logs** and save the zip.
   Also tap **Send logs to MaliBridge** and note the `ML-xxxx` code.
5. Put the zip in the project's `logs/` folder. I will read `vkbridge-server.log`
   and the gamescope log against the source and work out the exact failing step, then patch.

## Building a fixed APK on this PC

A full DroidDeck build (`tools/build_local.sh`) needs Docker for the Linux-side pieces. The **bridge server**,
which is where the RP4 Pro fix lives, is an Android binary and builds with the NDK alone:

1. `bash external/MaliBridge/scripts/build-server-android.sh <NDK 27 dir> <out dir>` (about 45 s).
2. `bash tools/repack-apk.sh <original v0.5.4 apk> <out dir> build/<new>.apk` replaces the two server
   libraries in the stock APK, zipaligns and signs with the Android debug key.
3. `adb install -r -g build/<new>.apk`. (The first install over the stock app needed an uninstall, because
   the signing key changed; later builds install in place.)

Built: `build/DroidDeck-MaliBridge-v0.5.4-rp4pro-fix1.apk`.

## What was wrong (found 2026-10-26 with the device on adb)

The chain from the first write-up held up to step 3 and then turned out to be one step longer:

1. Promotion to Vulkan 1.2 for gamescope: worked out of the box.
2. dma-buf export emulation: the AHardwareBuffer fallback **passed** its self-check and gamescope's 1080p
   output buffers were exported through it.
3. The real blocker: Mali r32p1 answers `VK_ERROR_FORMAT_NOT_SUPPORTED` to
   `vkGetPhysicalDeviceImageFormatProperties2` for a dma-buf image at DRM-modifier tiling **whenever
   `VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT` is set**, with or without a format list. gamescope sets that flag on every
   flippable image (its sRGB twin format differs), so every modifier was skipped and it died on
   `assert(modifiers.size() > 0)`. The same image **creates fine** with the flag, exports, and takes an sRGB view.

Proven with `tools/vkquery.py` and `tools/vkcreate.py`: Python ctypes tests run inside the DroidDeck rootfs
(`libproot.so` under `run-as`) against a hand-started bridge server. Fix: in
`external/MaliBridge/server/server_opaque.c` (`mutable_query_retry`), a refused query with the flag is asked again
without the flag and the list, and that answer is returned. Committed on branch `rp4pro-fixes` in the
MaliBridge submodule; still to be sent upstream against issue #10.

## After the bridge fix: what the games showed (evening of 2026-10-26)

- **TMNT: Shredder's Revenge (D3D11, DXVK)**: plays at 60 fps average, frame generation on. Startup is a
  ~20 s black screen while shaders pre-compile; normal.
- **Double Play press**: the DroidDeck app crashed (SIGSEGV in libGLES_mali.so from `vkp_framegen_run`,
  compositor thread) whenever a *second* session started in the same app process with frame generation
  armed. The relaunch gets a fresh process, which works. Not fixed yet; lives in
  `app/src/main/cpp/waylandcomp/src/framegen_bridge.c`.
- **Ys Origin (D3D8, 32-bit) black screen at 0 fps**: not graphics. The game's `release/programs/{ogg,vorbis,
  vorbisfile}.dll` live on the SD card, which Android mounts noexec. Wine logs `map_image_into_view failed to
  set 60000020 protection ... noexec filesystem?`, then the ARM64EC FEX inside Wine refuses the first call
  (`NoExec instruction in entry block`, access violation info[0]=8); the main thread ends up in the game's own
  exception handler and never draws again. DroidDeck's shim `tools/linuxfs/preload/noexec.c` handles this
  only for processes named FEX*, so Proton (ARM64) never gets it. Fix committed on DroidDeck branch
  `rp4pro-noexec-arm64ec` (Wine's processes count too). **Built and verified 2026-10-26 20:31**: with the
  rebuilt `libblsession.so` (Zig cross build, `tools/build-preload-zig.sh`, sources from the `pr-1587` ref the
  runtime was built from; building from `main` broke Zink's dma-buf sharing because `drm.c` differs), Ys Origin
  reaches its title screen from the SD card at 61 fps. Shipped as `build/DroidDeck-MaliBridge-v0.5.4-rp4pro-fix3.apk`
  (bridge fix + preload fix in `assets/linuxfs/libblsession.so`).
- DXVK's D3D8 (`PROTON_DXVK_D3D8`) exists only for 64-bit games in this Proton build; 32-bit D3D8 games use
  wined3d. `WINE_D3D_CONFIG=renderer=vulkan` initialises fine on the bridge.

Result on the device: gamescope starts, Steam bootstraps (first run crashed once in the bootstrapper with
SIGSEGV during its manifest download; the second start finished the update, exit 42 restart, and came up), the
sign-in screen renders, sign-in works, a game download started.

## Night of 2026-10-26: what changed while the owner slept (fix4 → fix6)

Installed on the device now: `build/DroidDeck-MaliBridge-v0.5.4-rp4pro-fix6.apk` (installed 21:58). It is the
stock v0.5.4 APK with three parts replaced:

| APK path | Built from | What it fixes |
|---|---|---|
| `lib/arm64-v8a/libvkbridge_server.so` (+ `_jni`) | `external/MaliBridge` branch `rp4pro-fixes` (6014ea1 + 0c5be1d) | mutable-format query retry; AHB "twin" answers; linear images hidden |
| `assets/linuxfs/libblsession.so` | Zig cross build of the `pr-1587` preload sources + `rp4pro-noexec-arm64ec` | SD-card DLLs run under Wine ARM64EC (Ys Origin) |
| `lib/arm64-v8a/libdroiddeckwayland.so` | the MaliBridge author's fork, branch `mali-test-build` (b3006a8a), + the frame-generation reset | "press Play twice" crash |

### Bridge server: AHB "twin" and hidden linear tiling (fix4/fix5)

- Mali r32p1 crashes inside `vkGetImageSubresourceLayout` on an image created with an AHardwareBuffer external
  type that has no memory bound yet, and `vkGetImageMemoryRequirements` on such an image is undefined by the spec
  (VUID 04004). The one time a probe asked the app's live server, the whole bridge went down and ended the
  session. The server now creates a plain twin of every AHB-external image (same create info without the
  external chain) and answers requirements, requirements2 and subresource layout from the twin; the real image
  is only ever bound. (`server/server_emu_layer.c`, `vkb_emu_image` gained `plain_ci`, `twin_*`.)
- Images bound to BLOB AHBs read back with `rowPitch 0` on r32p1, so Zink's linear staging images went wrong.
  With memory strategy `ahb`, the server reports `linearTilingFeatures = 0` for every format
  (`server/server_memory.c`, `hide_linear`); Zink then stages through buffers, which are byte-exact.
  `VKBRIDGE_AHB_LINEAR=1` in the server's `env` file turns the hiding off.
- Verified with `tools/vkreadback.py`, `vkblit.py`, `vklinear.py`, `vkexport.py` (row-varying patterns, memset
  pre-fill, private server instance): readbacks, blits, uploads and shared-image reads byte-exact after the fixes.
- **Did not remove Ys Origin's white rooms** (the owner, 21:36: "i still see white when starting it").

### Frame generation: the "press Play twice" crash (fix6)

Disassembly of the crash placed the fault in `vkUpdateDescriptorSets` issued by the DD-FG engine on the
*second* session of the same app process: the engine, its descriptor sets and the ring of generated images
survive `droiddeck_wayland_run` ending and are reused with the next session's device state, which Mali does not
forgive. Fix: `vkp_framegen_session_begin()` in `waylandcomp/src/framegen_bridge.c`, called from
`droiddeck_wayland_run` right after `vkp_ready()`: drops the ring, stops the engine, forgets the built size and
format, and marks the config dirty so everything is rebuilt on first use. Logged as
`session start: frame generation reset (engine and ring rebuilt on first use)` in the compositor log.
Committed on DroidDeck branch `rp4pro-framegen-session-reset` (9f3e323c, against `main`).

**Which sources the APK's compositor really came from.** The installed APK's Java side still calls
`nativeSetWinFgTuning` (renamed to `nativeSetDdFgTuning` on 2026-10-19), so it was built *before* `pr-1587` was
rebased onto `main`; the matching tree is the author's fork `https://github.com/ASEbrahim/DroidDeck`, branch
`mali-test-build` (b3006a8a, fetched read-only as `ase/mali-test-build`): its `WaylandCompositor.java` natives
equal the shipped library's 69 JNI exports exactly, and all 68 message-like strings of the shipped library are in a
build of it. A build from `main` or `pr-1587` head lacks `nativeSetWinFgTuning` (UnsatisfiedLinkError when frame
generation is configured) and adds ten natives the APK never calls, so **do not** ship that one.

Recipe (about 3 min):
```
git archive ase/mali-test-build app/src/main/cpp | tar -x -C <src>
# apply framegen_bridge.c/.h from 9f3e323c; add the vkp_framegen_session_begin() call after vkp_ready() in
# compositor.c by hand (line 3112 in that revision; the main-based hunk does not apply)
cmake -S <src>/app/src/main/cpp -B <build> -G Ninja -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release \
  -DDROIDDECK_SKIP_QUESTXR=ON && ninja -C <build> droiddeckwayland
llvm-strip --strip-unneeded -o libdroiddeckwayland.stripped.so <build>/waylandcomp/libdroiddeckwayland.so
```
Checked before packing: NEEDED list identical to the shipped library (`ANDROID_STL=c++_shared` matters; the
default static libc++ drops `libc++_shared.so` and exports hundreds of `std::` symbols), JNI exports identical,
the only new dynamic symbol is `vkp_framegen_session_begin`. `tools/repack-apk.sh` takes it through its
`extra.list` as `lib/arm64-v8a/libdroiddeckwayland.so=<stripped .so>`.

**Not yet tested on the device** (the owner was asleep, the device locked): start Steam, press Play on a game with
frame generation on, exit to Steam, press Play again. Expected: no crash, and the reset line in the compositor log.

### Ys Origin white rooms — still open

White frames coincide with 12-18 fps and ~160 ms stalls; wined3d (D3D8 on wined3d, DXVK's D3D8 is 64-bit only
in this Proton) maps the 320x240 DirectDraw sysmem surface and waits on CS finishes; the bridge trace shows DXVK
device churn (new images cleared, 47 scaled blits per 600 commands); the DXVK log is clean except
"Unhandled render state 161". Linear hiding did not help. Armed for the next run (per-game environment for app
207350 in `files/game-environment.json` + the rootfs copy): `PROTON_LOG=1`, `DXVK_LOG_LEVEL=info`,
`DXVK_LOG_PATH=/root`, `DXVK_HUD=fps,frametimes,drawcalls,submissions,compiler`; the bridge image-command trace
(`files/vkbridge-pipelines/trace`, empty file) is on. Both cost a little performance; delete the trace file and the
`games` entry to go back to normal.

## 2026-10-27, morning and forenoon: the Ys Origin white room, hunted down to one draw

Installed on the device at the end of this: `build/DroidDeck-MaliBridge-v0.5.4-rp4pro-fix9.apk` = fix6 +
- compositor (fix7, `build/compositor-fix7-fork-b3006a8a.patch`): `vkp_recover_if_lost()` rebuilds the Vulkan device
  at the next session start after `VK_ERROR_DEVICE_LOST` (before: every later session in the process showed
  "0 on screen" and DroidDeck sat at "waiting for first frame"; the loss itself hit 6 ms after the frame-generation
  swapchain rebuild on a game's second launch, 05:29:25); `vkp_framegen_rearm()` resets the engine and ring at every
  re-arm. Built, installed, **recovery not yet exercised** by a real Steam stop-and-restart.
- server (fix8/fix9): `VKBRIDGE_AHB_RARELY` is also read from `<cache dir>/env`; `VKBRIDGE_SPIRV_SUBST=1` now also
  replaces the *driver's* module (after the bridge's rewrites) by `<cache dir>/spv-subst/<fnv1a of the code>.spv`,
  the same hash the profiler's `dump-spirv/g-<hash>.spv` files carry, so a dumped shader can be edited and put back.

### How the device was driven without the owner
Steam's screens take `input tap`. The game's own menus take the mouse too, but only a *held* press registers
(`input swipe x y x y 180`); a plain tap only moves the highlight. Cursor scale: the game draws the cursor at
(1.08 x, 1.11 y) of the tap, so aim at (X/1.08, Y/1.11). Sequence used: Play (180,433) → 65 s → Load Game
(614,494) → 6 s → page 3 tab (523,86) twice → bottom save No.24 (620,581) → Yes (795,632) → 25 s. The game is
stopped with `run-as <pkg> kill <pid of yso_win.exe>` (visible in `ps -A`).

### Ruled out (each by a relaunch of the room, save No.24, 1F Search Party's Base)
- wined3d `renderer=no3d` for DirectDraw: the game loses its window (DirectDraw is only its DirectShow video
  player; the 320x240 surface from last night is that).
- DXVK `d3d9.allowDirectBufferMapping=False` + `cachedDynamicBuffers=True`: every room slow (7 fps), still white.
- Bridge errata off (`-bitwise_scalar,-dyn_reemit,-vb_split,-view_quarantine,-view_counts,-cull_nontri`),
  `+stencil_layout`, `+clamp_zero`: white. `VKBRIDGE_EMU_ROBUSTNESS2=0`: gamescope needs robustness2 (session
  dies); the bridge already stands in dummies for null descriptors anyway.
- `d3d9.forceSwapchainMSAA=0` (the game asks for 4x MSAA; every post step starts with a resolve): white.
- `VKBRIDGE_AHB_RARELY=1` (uncached shared pages; the self-test had judged the cached ones coherent): white.
- `VKBRIDGE_CLIP_DISCARD=0`: 13 → 19 fps, still white (the emulation's discard kills Mali's early-Z/FPK).
- Probes through the bridge (tools/vkspec.py, private server, NDK glslc): specialization constants work in
  vertex and fragment stages in every shape DXVK uses (int compare, bit extract, bool branch, composite select,
  switch, float math); a NaN fragment lands as 0x00000000, not white. (The server's own `spec_select` probe
  fails on r32p1 - a compute shader - with the SPIR-V fix on; fragment/vertex shaders are fine.)
  A missing barrier gave a false "folded at defaults" first: render pass → copy needs
  COLOR_ATTACHMENT_OUTPUT→TRANSFER + a host invalidate.

### Found
- `apitrace` (14.0 win32, shim `d3d9.dll` beside the game, `TRACE_FILE=Z:/root/ys.trace`): 877 frames,
  `logs/2026-10-27/ys.trace`. Frame 613 (`frame70.txt`): 1569 calls; scene into the 4x MSAA back buffer; two
  StretchRects of it into a 512x256 and a 2048x1024 RT texture; a 4-tap blur ping-pong; additive composite.
- `VKBRIDGE_GPU_PROF=2`: one 1280x720 pass costs 70 ms a frame (85 % of GPU time); inside it one pipeline,
  VS `aeadcbdbe014e7f7` + FS `f6b4350642e345f3`, **one draw of 46 triangles (138 vertices) takes 72 ms**.
  In the trace that draw is `DrawIndexedPrimitive(TRIANGLELIST, NumVertices=138, primCount=46)` once a frame
  (call 581761 and its siblings): vertex decl 300 (pos, normal, 2 colours, uv, stride 48), stage 0 =
  `D3DTOP_BUMPENVMAP` with a 256x256 `D3DFMT_V8U8` bump map and BUMPENVMAT00..11, stage 1 = the 2048x1024
  frame copy with `D3DTTFF_PROJECTED` and `BLENDFACTORALPHA` against TFACTOR 0x99320FD0, opaque, fog off.
  A refractive floor: it exists only in some rooms, which is the room pattern the owner saw, and its colour comes
  from sampling the frame copy at bump-displaced projected coordinates (SPIR-V: 1/w, bump dot products, sample).
- Variant B of that FS (output = the stage-1 sample as is) substituted at the driver: still white, so either the
  frame copy itself is white or the coordinates land on white; variant C (flat grey, to show the draw's
  coverage) is the next step, then D (plain projected sample without the bump offset).

### 09:00-09:40: the culprit pipeline (where to resume)
- The white and ~50 of the 70 ms are the **sprite/effect pipeline**: VS `6d48a7fb7d1728fa` (the game's vs: r4 = v0 x c24..27,
  oPos = r4 x c4..7, oD0 = v1 x c16, oT0 = v3.xy + c1.xy; 4 attributes, decl 298, stride 28) + FS `6692464689b014c6`
  (DXVK fixed-function: texture x diffuse, fog, alpha test). Substituting that FS with discard-all gives the **correct room at
  29 fps** (`drive-085238-spriteKill2.png`); translucent red shows those draws **covering the whole screen**; texture-only and
  diffuse-only outputs are both pure white → the vertex fetch or the VS constants are wrong at draw time, not the FS math.
- The recorded vertex data is right (small quads, coloured: `logs/2026-10-27/blobs`); the per-draw constants are
  `SetVertexShaderConstantF(4, 24 vectors)` = c4..c27.
- Probes that PASS through the bridge (tools/vkcoh.py): UBO written through a coherent mapping before each draw; vertex ring
  with firstVertex; 1024 draws in one command buffer each with its own descriptor set and 4 KB UBO slice. So the plain
  paths are fine; something DXVK-specific remains (candidates: DXVK's per-draw constant upload path, vertex binding offsets
  into its 256 MiB chunks, D3DCOLOR vertex format).
- The profiler at level 2 credits a whole pass to its first pipeline: the "72 ms floor draw" was that artefact.
- VS substitution: a GLSL stand-in made the game exit at launch; a minimal patch of the dumped VS (variant M: oPos from
  v0 only, oD0 = v1; `scratchpad/spec/vs-6d48a7fb7d1728fa-M.spv`) was pushed but the launch could not be verified before
  the pause. Driver-module substitution (fix9 server) works: name the file by the FNV-1a of `dump-spirv/g-<hash>.spv`.
- Steam driving gotcha: after a game exit the game page is sometimes scrolled; a tap at Play then opens Achievements. Tap
  ACTIVITY (424,103) and swipe down twice to get the header back, verify with a screenshot, then Play.
- Device left with: fix9 installed, env = `VKBRIDGE_STATS=5` only, no spv-subst files, DXVK config file
  `/root/dxvk-ys.conf` = `d3d9.forceSamplerTypeSpecConstants = True` (harmless; remove with the game env entry when done).

## 2026-10-27, 10:30-12:40: the white rooms FIXED (fix12)

Installed on the device: `build/DroidDeck-MaliBridge-v0.5.4-rp4pro-fix12.apk` = fix9 + the bridge erratum
**nan_position (MK-45)**, MaliBridge branch `rp4pro-fixes` commits 9b9e2c1 (substitution helper) and
9116729 (the erratum; files `server/spirv_nanpos.[ch]`, `server/server_errata_nanpos.c`, table entries in
`server_errata.c`/`.h`/`vkb_server.h`). The server library alone: `build/libvkbridge_server-fix12.so`.
Ys Origin's save No.24 room (1F Search Party's Base) renders correctly at 60-69 fps (`logs/2026-10-27/fix12-room*.png`).

### Root cause
Ys Origin leaves its dead particle sprites in the draw list with **NaN world matrices** (`tools/ys_replay.py` on
the apitrace frame: 154 of the 208 draws with the sprite vertex shader have NaN in c24..c27). A PC GPU drops a
primitive whose clip-space position is NaN; this Mali-G77 r32p1 draws each of them as a sheet over the whole render
target (bit pattern -nan(ind) through the shader's dot products; the exact driver mechanism is not known - a NaN
fed straight in as a vertex attribute IS dropped, so it is the arithmetic path). 150 full-screen additive sheets a
frame = white rooms at 12 fps, exactly in the rooms that have effects.

### The fix
A SPIR-V rewrite of every vertex shader on its way to the driver (`spirv_nanpos.c`): before each return of the
entry point, the Position output is loaded and, if any component's exponent bits are all ones (NaN/Inf, tested on
the bits with scalar integer ops so no fast-math folds it) or w == 0, replaced by (2, 2, 2, 1), outside the clip
volume. Handles DXVK's `oPos` variable and gl_PerVertex members. Gated on r32 drivers (G_IN_RANGE both modes);
`VKBRIDGE_ERRATA=-nan_position` turns it off. Host check: `scratchpad/nanpos_test.c` (zig cc) + spirv-val.

### What was ruled out on the way (so nobody repeats it)
- Vertex fetch (probe `tools/vkstrip.py`: strips, firstVertex, bind offsets, indexed vertexOffset, rebinding
  between pipelines of different strides - all exact); the grid diagnostic shader (variant X: each draw in a cell
  by gl_VertexIndex, red if |v0| > 2) showed every particle fetching sane data.
- The frame copy for the bloom (resolve + blit keep alpha, `tools/vkalpha.py`), colour write masks, AFBC
  (`VKBRIDGE_NO_COMPRESSION=all`), the A1R5G5B5 texture format: my probe had the Vulkan format numbers off by two
  (6 = R5G5B5A1, 7 = B5G5R5A1, **8 = A1R5G5B5**), the driver reads all of them right. fix10/fix11 built on that
  misreading and were reverted.
- The "game does not launch" runs from 09:14 on were my own `VKBRIDGE_EMU_DIVISOR=0` left in the env file: the
  server reads the emulation set at start and keeps it until the app process restarts (`am force-stop`), so an
  env change there needs an app restart, not a game relaunch.
- Variant M (position = raw v0.xy*2) was a bad test: several sprites are ±1-2 units wide, so without the
  projection they cover the screen anyway.

### Device state after this
`files/game-environment.json` and `.config/droiddeck/game-environment.json` back to no games (no HUD, no logs),
`vkbridge-pipelines/env` = `VKBRIDGE_STATS=5`, the trace file and `/root/dxvk-ys.conf` removed, spv-subst empty.
The private probe servers under `files/vkbtest/` may still be running (harmless; they die with the app process).
Still open: fix7's device-loss recovery unverified by a real Steam stop-and-restart; the erratum wants a probe
instead of the r32 range before upstreaming; nothing pushed to GitHub.

## 2026-10-27, 12:50-13:20: the "press Play twice" crash FIXED (fix13)

Installed now: `build/DroidDeck-MaliBridge-v0.5.4-rp4pro-fix13.apk` = fix12 (bridge with nan_position) + a compositor
rebuilt with two small fixes in the Win-FG engine (`build/compositor-fix13-winfg-waits.patch` against the fork's
`app/src/main/cpp/framegen/winfg/`; the same change committed on DroidDeck branch `rp4pro-framegen-session-reset`
for main's `framegen/ddfg/`). Compositor library: `build/libdroiddeckwayland-fix13.so`.

### What the crash really was
With Win-FG on, the app process died (SIGSEGV in libGLES_mali, fault 0x40, from `vkp_framegen_run`) at the first
frame after the presenting window changed size (Steam 1333x750 -> the game 1280x720), and at the second launch in a
process. `FrameGen::onResize` destroys the pyramid images and `destroyScratch` resets the descriptor pool with no
wait for the GPU, while the previous frame still uses them; Mali's driver faults on that. The engine also keeps its
descriptor sets in a process-wide static `g_scratch` keyed only by the frame size, so an engine rebuilt at the same
size (my fix6/fix7 resets, or any second session of the same game) reused sets from a destroyed pool. Fix: a
`vkDeviceWaitIdle` before both teardowns, and `FrameGen::destroy` drops the scratch with its pool. The fix7
session/re-arm resets and the device-loss recovery stay (now harmless). Verified 13:12-13:18: Steam -> Ys Origin
(Win-FG x2 generating, 60 fps on screen, 300 generated frames per 10 s) -> exit -> Play again in the same app
process -> title at 60 fps, no crash, no device loss.

### Gotchas recorded on the way
- The stock APK's Java names the engine `winfg` in `shared_prefs/frame_gen.xml` (main's source says `ddfg`); an
  unknown name shows "Win-FG: unsupported" in the HUD and never arms.
- My compositor build from the fork copy (`scratchpad/wlsrc3`, build dir `wlbuild3s`, top-level
  `app/src/main/cpp` CMake, Ninja, NDK 27, c++_shared, SKIP_QUESTXR) does include the engines; the .so lands in
  `wlbuild3s/waylandcomp/`. The device-loss recovery (`vkp_recover_if_lost`) was never triggered in these runs and
  stays unexercised.
- The owner's frame-generation setting was put back to off after the test.

## 2026-10-27, 14:15-14:35: LSFG Native on the Vulkan 1.1 driver (fix14)

The README's "only Win-FG, LSFG needs Vulkan 1.2" limit was a switch, not a hardware wall. `framegen/lsfg/lsfg_probe.cpp`
already had a Vulkan 1.1 route (`allowVk11`: VK_KHR_spirv_1_4 + VK_KHR_shader_float_controls + VK_KHR_vulkan_memory_model,
modules lowered to SPIR-V 1.4), but `waylandcomp/src/framegen_engine.cpp` called it with `false`, handed the driver a
`VkPhysicalDeviceVulkan12Features` chain (refused below 1.2), and nothing added the three extensions at device creation.

What the r32p1 driver has (Android `cmd gpu vkjson` + gpuinfo report 27598): all three extensions, `vulkanMemoryModel`
+ `DeviceScope`, `shaderStorageImageWriteWithoutFormat`, `shaderStorageImageExtendedFormats`, storage/blit/colour on
R8G8B8A8_UNORM. The DLL's shader cache (`files/lsfg/shaders-626b196d799606cd.cache`, 25 modules) is SPIR-V **1.0**
(capabilities Shader, Float16, StorageImageExtendedFormats, ImageQuery, StorageImageWriteWithoutFormat; no memory-model
capability), so the "lowering" is a no-op for these modules.

fix14 (`build/compositor-fix14-lsfg-vk11.patch`, 6 files, ~140 lines): `allowVk11 = true`; below 1.2 the feature chain is
`VkPhysicalDeviceVulkanMemoryModelFeaturesKHR`; new `fge_device_extensions()` → `vkp_framegen_device_extensions()` and
`vk_present.c` appends those names to its device extension list (array grown 9 → 12).

Verified 14:30-14:32 on the device (fix14 APK, prefs engine=lsfg x2, Lossless.dll already in `files/lsfg`):
- compositor: `lsfg caps 'supported (device Vulkan 1.1.177 via extensions, SPIR-V lowered, fmt 37)'`, LSFG Native available.
- Steam UI: HUD "LSFG 2x 10 → 19 fps". Ys Origin title: "LSFG 2x 50 → 60 fps"; `[stats] 600 frames on screen (60.0 fps)
  | 496 GPU frames from games | 300 generated frames` per 10 s; render_scene 19-22 ms avg; no errors, no device loss.
- On a 60 Hz panel x2 means 30 real + 30 generated frames on screen, so a game already at 50-60 fps gains smoothness
  only in pacing; the mode pays off for games that run at 30. GPU cost of the chain is ~20 ms per generated frame here.

Device state: fix14 installed, frame-generation pref left at engine=lsfg x2 (the owner loaded the DLL for it). Branch
`rp4pro-lsfg-vk11` on DroidDeck (from upstream main) carries the change for a PR.

## 2026-10-27, 15:00: upstream status

- Droid-Deck/DroidDeck PRs #1692 (noexec) and #1693 (framegen) **closed by the owner's decision**: that repo's
  `tools/release/check_authorship.py` rejects commits with an AI co-author trailer (ours carry
  "Co-Authored-By: Claude Fable 5.1"), and stripping it to pass was not acceptable. The fixes stay on the fork
  branches `rp4pro-noexec-arm64ec`, `rp4pro-framegen-session-reset` (+ 302fd2ad review follow-ups: scratch dropped
  at resize, refused formats cleared at session start) and `rp4pro-lsfg-vk11`, and in the release repo.
- ASEbrahim/MaliBridge#35 stays open (no such check there).
- CodeRabbit's third point (narrow the `wine*` match in noexec.c) declined: on DroidDeck every Wine process is
  the ARM64EC build with FEX inside, so the broad match is intended.

## 2026-10-27 evening: Ys I & II Chronicles+ dialog crawl (5-11 fps) = FEX TSO stores into uncached bridge memory

- ys1plus.exe (steam 223810, DXVK d3d9 + a Zink instance, presents through Zink) ran its main thread at 100% CPU
  while a dialog was on screen; compositor saw 5.7 game frames/s; the bridge server spent only ~180 ms per 5 s in
  handlers, so the GPU side was idle. simpleperf (NDK 27, run-as, `-p <pid> -f 1000 --duration 8`): 98% of samples in
  `[anon:FEXMemJIT]`, nine PCs within 0x290 bytes with equal counts (every PC a stall).
- Dumped the page through `/proc/<pid>/mem` (toybox dd needs `bs=4096 skip=<page>`; `adb exec-out`, not `adb shell`,
  for binary) and disassembled with llvm-objdump (raw bytes wrapped by llvm-objcopy -I binary). The loop: for 480 rows x
  640 px, `ldaprb` palette index, `ldapr` palette entry, then four `stlrb` (B,G,R,A) into dst = base + row*pitch + x*4,
  alpha = (index == 255 ? 0 : 255). A paletted full-screen blit into a locked D3D9 surface, byte by byte, with
  store-release on every byte because FEX_TSOENABLED=1 (the app's default preset). The destination is DXVK's mapping
  buffer = the bridge's AHB shared type (uncached pages; "no coherent type takes a cached AHB" in the self-test), so
  every stlrb drains to RAM: 1.2 M stores x ~140 ns = the ~170 ms/frame measured (ft_p50 176 ms).
- Fix applied: per-game FEX_TSOENABLED=0 for 223810 in files/game-environment.json ("games": {"223810": {...}}) and the
  published guest copy files/linuxfs/root/.config/droiddeck/game-environment.json (droiddeck-fex reads it at launch).
  Backups *.bak-tso. Relaunch: title 60/60, field 59-60, the owner confirms dialog fps normal. Main thread 19% CPU.
- Broader candidate (not done): any TSO-on game writing locked GPU memory pays this on the AHB strategy; a cached
  AHB type for app mappings with DMA_BUF_IOCTL_SYNC at submit/wait would fix it for all games without touching FEX.
- Home key: `adb shell input keyevent KEYCODE_HOME` opens the Steam menu on the first press (HomeActivity ->
  SessionActivity ACTION_HOME_GUIDE -> sendSteamGuide). The physical Home key sits on the "Retroid Pocket Controller"
  input device (event3, KEY_HOME + BTN_MODE + KEY_BACK). Single Back from adb opens the app drawer; Back double-tap from
  adb showed nothing (timing of `input` uncertain). Waiting on the owner's physical-button results.
- MaliBridge v0.5.4.1 (f9e1970, 2026-10-27 21:24 UTC): his RP4 Pro start fix is a guess from other users' logs
  (linear_mod: modifier-list dma-buf images made plain LINEAR; "most likely refuses"), no twin/linear-hiding, no
  nan_position. Useful for us: vkb_emu_device_clamp_features (DXVK 1.10.3 D3D10/11 on Vulkan 1.1), vkb_srv_env_refresh
  (env file re-read), suspend/resume, present timeout, 12 GB heap cap. rp4pro-fixes rebased onto it as branch
  rp4pro-fixes-0541 (b4e78ed; conflicts were keep-both in server_emu.h, server_emu_layer.c, server_errata.c,
  vkb_server.h). Untested on device; his linear_mod path queries the real driver with the mutable flag beneath our
  retry, so Steam start and Ys Origin need re-checking with it.

### Night: button causes found; TO-DO list

- Home needing two presses: Retroid system setting `prevent_press_home_accidentally=1` (rp3settings owns the key;
  getevent sees no KEY_HOME; a toast per press). `adb shell settings put system prevent_press_home_accidentally 0`.
- Quick-access panel / three-dots / double-Back / hold-Back chords: DroidDeck "Steam controller" was Xbox 360
  (no QAM button; the app's Guide+A chord fallback does nothing). Set to Steam Deck: VERIFIED working.
- DXVK 1.10.3 and current never call vkFlush/InvalidateMappedMemoryRanges: a cached host type for apps needs the
  bridge to do the cache maintenance itself.

TO-DO (order agreed): 1 cache-cost probe for the general cached-memory bridge fix (else pre-apply TSO off to the
other Ys games); 2 test fix15 (rebased bridge on v0.5.4.1: Steam start, Ys Origin, Ys I), then push/update #35;
3 nan_position startup probe; 4 full v0.5.4.1-based build (source tarball, compositor rebuild); 5 32-bit DXVK D3D8
for Ys Origin; 6 library pass, sleep/resume + device-loss test, 1080p, battery Win-FG vs LSFG, update prompt,
Guide+A chord fallback bug.

## 2026-10-28: cached memory, building the app myself, and a Retro tab (fix15 to fix43)

- **Cache test.** Ys I's dialog crawl came from FEX writing one byte at a time into the bridge's uncached memory.
  I measured a cached version: the stall went from 163 ms to 0.74 ms, but the cache then has to be flushed before
  every submit (about 0.03-0.05 ms per MB). So it became an opt-in switch per game (`VKBRIDGE_CACHED_UPLOAD`, fix17)
  rather than the default. Ys I stays on TSO off, which is simpler and just as fast.
- **fix18** moved everything onto ASEbrahim's v0.5.4.1. Steam, Ys I, sleep/resume and Ys Origin's rooms all
  checked out. PR #35 was updated to the new base.
- The D3D8 idea for Ys Origin was dropped: it's a D3D9 game under DXVK 1.10.3.
- **The app now builds from source** (fix20). Up to then I could only swap libraries inside the stock APK.
- **A Retro tab** (fix24 to fix42): a Steam-style start screen for your emulators and games, with box art and game info,
  using the platform files from Daijishou (MIT). It starts each game fresh in its emulator. One gotcha: the Home key
  only works with Retroid's controller style set to Retro.
- **fix43**: the app's Updates page now follows this repository's releases.

## 2026-10-29: four games fixed, and the README split up (fix44 to fix51)

- **Tales of Symphonia closed at start.** It asks for 2x multisampling, which this Mali driver doesn't have.
  Bridge fix `msaa2_as_4`: it reports 2x and quietly makes the image with 4 samples (fix44).
- **Oath in Felghana's world was black.** Microsoft's own `d3dx9_41/43` DLLs return NaN matrices when run through
  FEX. Using Wine's built-in d3dx9 fixes it (fix45, about 69 fps). The same cause turned out to be behind Ys
  Origin's white rooms (fix48), not the dead particles I first blamed.
- **fix46** took ASEbrahim's v0.5.4.2. Lesson learned: Windows line endings in the guest scripts stop Steam from
  starting, so the source repo keeps `autocrlf` off.
- **Symphonia's battles ran at 15 fps.** The game pins its main thread to Windows CPU 0, which on this chip is a
  slow little core. Mapping CPU 0 to a big core gave a steady 30 (fix49), and fix50 does it for every game
  ("fast cores first").
- **fix51**: when you come back from a Retro game, its emulator closes.
- The README got too long, so it's now split into README, Games, Changelog and Fixes.

## 2026-10-31 to 2026-11-02: ScummVM and the new bridge (fix52, fix53)

- **fix52**: ScummVM games on the Retro tab (King's Quest, Space Quest and friends), played with the touch screen.
- **November 1**: ASEbrahim released MaliBridge v0.5.5.1. fix53 takes his new bridge and moves my RP4 Pro fixes onto
  it. Tales of Vesperia's towns went from about 42 to 46 fps; everything tested before still works.
- Cleanup: a leftover shader dump folder from the October 27 hunt had grown to 44,000 files (610 MB). Deleted.

## 2026-11-03 to 2026-11-04: SD card installs

- **Tales of Eternia "Reserving space" at 5 files a second.** It has 33,552 of its files in a single folder, and
  the card is exFAT, where every new file name means scanning the whole folder. DroidDeck's folder-listing cache now
  works on native exFAT/FAT/NTFS cards too, and I ported upstream's fast path (#1331). Result: 22-30 files a second.
  The card itself sets the floor: about 10 ms to create a file, and 11.6 ms to look up a new name in a 26,000-file
  folder. So there's maybe 2x left at most.
- **Steam's "Move install folder" to the card failed** with "Disk write failure". Steam copies a file, then sets its
  date, and the card refuses that. A small preload now treats that refusal as success on exFAT/FAT/NTFS only.
  Tales of Vesperia (24 GB) moved to the card fine after that.
- **Turning the screen off paused Steam** in the middle of reserving, committing or moving, because those steps
  don't write to Steam's log for minutes. They now count as busy from start to end (capped at 3 hours).
- **November 4 evening**: started the Network page.

## 2026-11-05: the Network page (fix54)

- **The Network page**: connect to a shared folder (SMB) on a NAS or PC. A Steam games tab installs games
  straight into Steam's library and backs them up the other way; a Files tab copies anything in both directions;
  a Settings tab handles shares, copy speed and where games go.
- **Tested from the PC while I was away:**
  - Installed Ys: The Oath in Felghana (1.8 GB, 2,096 files) and backed up King's Quest Collection (2.3 GB,
    953 files) at about 35-50 MB/s.
  - Dropped the Wi-Fi in the middle of each: they waited and carried on.
  - Cancelled an install halfway: it resumed without re-copying the finished files, and Steam's "Verify
    integrity" found nothing wrong.
- **Retro tab**: a few fixes. Newly added games only showed after leaving the app, DraStic opened its menu instead
  of the game, some files that aren't games were listed as games, and one PS1 game showed as "EBOOT".
- **Server discovery**: Add share finds servers on the network (Bonjour, plus a quick check of the SMB port) and
  can list a server's shares once you sign in. I asked for this in the first version, because every file app has it.
- **Keyboard**: in landscape, Gboard covered the whole screen with its own tiny text box. Text fields now keep the
  keyboard docked, and dialogs move above it, in every DroidDeck dialog.
- **Files tab buttons**: the first version put a copy button under each side, and the one under where I wanted the
  file to go actually sent things the other way. Now it's **Transfer →** under the left side and **← Transfer**
  under the right, shown only when something is picked.
- **Privacy**: the server's address now shows only when adding or editing a share. Everywhere else the share goes
  by its name.
- **On the NAS**: moving about 640 GB of old Steam backups into the handheld's folder took seconds with `mv` on the
  NAS itself, since both folders are in the same dataset. Copying them through a PC would have taken hours. A
  separate NAS user for the handheld also needs permission to the files already in its folder, or Steam games
  shows "access refused".
- **fix54 published.**
