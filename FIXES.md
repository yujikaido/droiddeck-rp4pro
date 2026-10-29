# What is fixed, and how

[README](README.md) · [Games](GAMES.md) · [Changelog](CHANGELOG.md) · **Fixes** · [License](LICENSE)

The problems this build fixes, with their causes, the screenshots, the known limits and where the source is.

## What is fixed

| Problem on the stock v0.5.4 | Cause | Fix |
|---|---|---|
| Steam never starts: gamescope dies at once | Mali r32p1 refuses an image-format query with `MUTABLE_FORMAT_BIT` on dma-buf images; gamescope needs it for every flippable image | The bridge retries the query without the flag and answers that (MaliBridge `server_opaque.c`) |
| The whole bridge goes down, or images read back wrong | r32p1 crashes in `vkGetImageSubresourceLayout` on an unbound AHardwareBuffer image, and reports `rowPitch 0` for linear images on AHardwareBuffer pages | A plain "twin" image answers requirements and layouts; linear tiling is hidden under the AHardwareBuffer memory strategy |
| Games installed on the SD card hang at a black screen (Ys Origin) | Android mounts the SD card noexec; DroidDeck's noexec shim only covered FEX processes, not Wine's ARM64EC ones | The shim covers Wine's processes too (`tools/linuxfs/preload/noexec.c`) |
| **White rooms** in Ys Origin (any room with lighting effects, 12 fps) | Effect and particle draws get NaN world matrices from Microsoft's native `d3dx9_43`, which computes them wrong under FEX (as in Oath). A PC GPU drops a NaN position; r32p1 draws it as a sheet over the whole screen. (First thought to be the game's dead particles: that was wrong, found at fix48) | First the MaliBridge erratum `nan_position` (every vertex shader moves a NaN, Inf or zero-w position outside the clip volume). Since fix48, DroidDeck's known-games table switches the game to Wine's built-in `d3dx9_43`, and the effects the guard had hidden are back (brazier fire, statue glow) |
| **Press Play twice**: the first launch with frame generation on crashes the app | The Win-FG engine destroys its images and resets its descriptor pool while a frame is still in flight, and reuses descriptor sets from a destroyed pool at the same frame size | Waits for the GPU before both teardowns; the scratch cache is dropped with its pool |
| **Tales of Symphonia quits at start**, back to the Steam page (fix44) | The game wants a 2x MSAA render target; r32p1 multisamples at 1x and 4x only, and DXVK 1.10's D3D9 offers 2x anyway, then fails the texture ("Cannot create texture ... Samples: 2") | New MaliBridge erratum `msaa2_as_4`: 2x is offered wherever 4x exists, and 2x images, render passes and pipelines are made with 4 samples |
| **Ys: The Oath in Felghana shows only the HUD** after a save loads, DirectX 9 version (fix45) | An apitrace shows the world drawn with every world matrix (vertex constants c24-c31) NaN; they come from Microsoft's native `d3dx9_41`/`d3dx9_43`, which return NaN under FEX. The DirectX 8 version, without `d3dx9`, is fine | DroidDeck's known-games table sets `WINEDLLOVERRIDES=d3dx9_41,d3dx9_43=b` for this game (Wine's built-in `d3dx9`), applied by itself at session start |
| **Tales of Symphonia battles at 15 fps** (fix49) | The game locks its main thread to Windows "CPU 0". On the Dimensity 1100 that is a Cortex-A55 (slow core); the thread sat at 91 % with the GPU 11-23 % busy | fix49: DroidDeck's known-games table set `WINE_CPU_TOPOLOGY=8:4,5,6,7,0,1,2,3`, so Windows CPU 0-3 are the A78s: 30 fps (the game's cap), 60 shown with LSFG 2x. fix50: the same for every game, built from the device's own core speeds (fastest first); a game's own setting, the Game cores setting or `BL_FAST_CORES_FIRST=0` turn it off. Of six games checked, only Symphonia pins; the others run as before |
| **The Frame limit did not cap DirectX games** (fix48) | Steam -> Settings -> Display -> Frame limit only set the compositor's rate; a game without vsync kept rendering faster and wasting power | The session also sets DXVK's own limiter (`DXVK_FRAME_RATE`) a little above the limit: a 60 limit holds 60.0 fps, and Ys Origin's GPU drops to its lowest clock |
| **LSFG unavailable** ("device Vulkan version below 1.2") | The LSFG engine has a Vulkan 1.1 route through three KHR extensions, but the compositor never switched it on, handed the driver a 1.2-only feature struct and did not enable the extensions | fix14: the 1.1 route is on, the memory-model feature goes through its KHR struct, and the three extensions are enabled on the compositor's device |

## Screenshots

Ys Origin, 1F Search Party's Base, before and after (the game's own DXVK HUD at the top left):

| Before: the white room, 12 fps | After: 69 fps |
|---|---|
| ![white room](screenshots/01-ys-origin-white-room-before.jpg) | ![fixed room](screenshots/02-ys-origin-room-after-fix.jpg) |

Title screen at 61 fps from the SD card, and Win-FG 2x on the second launch in one session (49 real frames
to 60 on screen), which used to crash:

| Title | Win-FG second launch |
|---|---|
| ![title](screenshots/04-ys-origin-title-61fps.jpg) | ![win-fg](screenshots/09-win-fg-second-launch-60fps.jpg) |

LSFG Native x2 (fix14) on the Vulkan 1.1 driver, which the stock build reports as unsupported: the Steam UI at
10 real frames to 19 on screen, and Ys Origin at 50 to 60:

| LSFG on the Steam UI | LSFG in Ys Origin |
|---|---|
| ![lsfg steam](screenshots/12-lsfg-steam-ui-hud.jpg) | ![lsfg ys](screenshots/11-lsfg-ys-origin-title-50-to-60fps.jpg) |

How the white room was found. The sprite pipeline's pixels drawn as translucent red show its draws covering
the whole screen (left). A diagnostic vertex shader then placed every draw in its own grid cell, green when
the vertex data it fetched was sane and red when large (middle): the data was fine, so the per-draw matrices
were the suspect, and a replay of the frame on the PC showed 154 of 208 sprite draws carrying NaN matrices.
With the pipeline's pixels discarded the room is correct (right), which is what the guard now achieves
without touching the game's own sprites.

| Sprite draws in red | Vertex data per draw | Sprite pixels discarded |
|---|---|---|
| ![red](screenshots/05-diagnostic-sprite-draws-in-red.jpg) | ![grid](screenshots/06-diagnostic-vertex-data-grid.jpg) | ![discard](screenshots/10-ys-origin-room-sprites-discarded-29fps.jpg) |

Ys: The Oath in Felghana before and after fix45 is in the [CHANGELOG](CHANGELOG.md#fix45-ys-the-oath-in-felghana-draws-its-world).

## Known limits

- Frame generation x2 on the 60 Hz panel shows 30 real and 30 generated frames a second, so a game already
  at 50-60 fps gains only pacing; the mode pays off for games that run at 30. LSFG costs about 20 ms of GPU
  time per generated frame on this chip, Win-FG less. LSFG needs your own Lossless Scaling DLL loaded in
  the app, as on every DroidDeck.
- Ys Origin's refractive floors cost about 10 ms a frame on this GPU; rooms with one run at 50-60 fps rather
  than 70.
- The `nan_position` erratum is switched on by driver version (r32). It needs a startup probe before it can
  be offered to every Mali.
- Any game that writes to locked GPU memory with FEX TSO on pays the store-release cost on this driver's
  uncached shared memory (see [Device tips](README.md#other-tips)). The per-game FEX preset is the workaround for now.

## What is inside

fix42 and later are built whole from this repository's [`source`](../../tree/source) branch: ASEbrahim's DroidDeck Mali
source release (v0.5.4.1 for fix42 to fix45, v0.5.4.2 from fix46), with MaliBridge `rp4pro-fixes` in `external/MaliBridge`, the compositor fixes from
fix13 and fix14, and the Retro tab, one commit per change.

fix14 and earlier were the stock v0.5.4 APK with three libraries replaced:

| Part | Built from |
|---|---|
| `lib/arm64-v8a/libvkbridge_server.so`, `libvkbridge_jni.so` | MaliBridge, the 4 fixes on v0.5.4 (kept as branch `rp4pro-fixes-v054`) |
| `lib/arm64-v8a/libdroiddeckwayland.so` | DroidDeck's compositor from the author's `mali-test-build` branch + `patches/compositor-fix7-fork-b3006a8a.patch` + `patches/compositor-fix13-winfg-waits.patch` + `patches/compositor-fix14-lsfg-vk11.patch` |
| `assets/linuxfs/libblsession.so` | DroidDeck preload, branch `rp4pro-noexec-arm64ec` |

The patches in `patches/` apply to the fork's `app/src/main/cpp` tree; the same changes are committed on the
DroidDeck branches for `main`.

## Source and commit history

This repository holds the build, the write-ups and the evidence. The app that fix50 is built from is on this
repository's **[`source`](../../commits/source)** branch: the v0.5.4.1 source release as its first commit, then
one commit per change (ASEbrahim's v0.5.4.2 came in as one commit, fix46), the Retro tab included. The bridge and compositor fixes, with their history, are also on
branches of the two forks, where each commit is one fix with its reasoning in the message:

**[yujikaido/MaliBridge](https://github.com/yujikaido/MaliBridge/commits/rp4pro-fixes), branch `rp4pro-fixes`**
(now on v0.5.4.2; the v0.5.4.1 version is kept as `rp4pro-fixes-v0541` and the v0.5.4 one that fix14 was built
from as `rp4pro-fixes-v054`). PR [ASEbrahim/MaliBridge#35](https://github.com/ASEbrahim/MaliBridge/pull/35) was
updated to the v0.5.4.2 base, with a correction on `nan_position`'s cause.

| Date | Commit |
|---|---|
| 2026-10-26 | server: answer MUTABLE_FORMAT image format queries Mali r32p1 refuses (Steam starts) |
| 2026-10-26 | server: linear images under the AHardwareBuffer memory strategy (twin images, linear tiling hidden) |
| 2026-10-27 | server: VKBRIDGE_AHB_RARELY from the env file; driver-module SPIR-V substitution (the shader bisection tool) |
| 2026-10-27 | server: nan_position (MK-45), vertex positions guarded on Mali-G77 r32p1 (the white rooms) |
| 2026-10-28 | server: cached upload memory (VKBRIDGE_CACHED_UPLOAD, opt-in, per game) |
| 2026-10-28 | server: cached upload per process, coalesced cleans |
| 2026-10-29 | server: msaa2_as_4 (MK-46), 2x multisampling made with 4 samples (Tales of Symphonia) |

**[yujikaido/DroidDeck](https://github.com/yujikaido/DroidDeck/branches), branches `rp4pro-noexec-arm64ec`, `rp4pro-framegen-session-reset` and `rp4pro-lsfg-vk11`**

| Date | Commit |
|---|---|
| 2026-10-26 | preload/noexec: treat Wine's ARM64EC processes as FEX for noexec mounts (SD-card games) |
| 2026-10-26 | waylandcomp: reset frame generation at each session start |
| 2026-10-27 | framegen: wait for the GPU before a resize or scratch teardown; drop the scratch with its pool (press Play twice) |
| 2026-10-27 | framegen: drop the scratch at a resize too; forget refused formats at session start (review follow-ups) |
| 2026-10-27 | framegen: run the LSFG chain on Vulkan 1.1 drivers through its extension path (branch `rp4pro-lsfg-vk11`) |

The order is the order the problems appeared: nothing started, then games from the SD card, then the white
rooms, then the second launch. The pull requests in `docs/` tell each story in full, and
[`docs/PROGRESS-LOG.md`](docs/PROGRESS-LOG.md) is the unpolished day-by-day log, dead ends included. The
fix44 to fix50 changes (the `msaa2_as_4` erratum, the known-games entries for Oath and Ys Origin, the
anti-aliasing switch, the Frame limit change and fast cores first) are on the `source` branch.
