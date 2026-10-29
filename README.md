# DroidDeck Mali for the Retroid Pocket 4 Pro ( personal build) 

 [DroidDeck Mali](https://github.com/ASEbrahim/MaliBridge) (DroidDeck + MaliBridge) for the
**Retroid Pocket 4 Pro** (MediaTek Dimensity 1100, Mali-G77 MC9, Android 13, Arm driver r32p1). The stock
v0.5.4 build does not start on this device and two games showed GPU bugs; this build fixes those. Everything
here is GPL-3.0, like the projects it is built from. The bridge fixes are offered upstream as
[ASEbrahim/MaliBridge#35](https://github.com/ASEbrahim/MaliBridge/pull/35). The DroidDeck fixes were offered as
[#1692](https://github.com/Droid-Deck/DroidDeck/pull/1692) and [#1693](https://github.com/Droid-Deck/DroidDeck/pull/1693)
and withdrawn: that project's CI rejects commits carrying an AI co-author trailer, and these were written with
Claude Code, so they live on the fork branches below instead. Maintainers are welcome to take them from there.

Tested on my personal Retroid Pocket 4 Pro only. The fixes switch themselves on for the r32 Mali driver, so the build
is safe on other phones, but nothing else has been tested on them.

## New in fix42 to fix45

**fix45: Ys: The Oath in Felghana draws its world.** In the DirectX 9 version, a loaded save showed only the
HUD on black. The game was drawing the world, but every world matrix came out as NaN: they come from
Microsoft's own `d3dx9` library, which gets them wrong under FEX (the x86 emulator). Wine's built-in `d3dx9`
gets them right, so DroidDeck now switches Oath to it by itself (its known-games table). Icebound Cave runs at
69 fps.

**fix44: Tales of Symphonia starts.** It used to close at once and drop back to the Steam page. The game asks
for 2x anti-aliasing (MSAA), and this GPU only does 1x and 4x. DXVK tells the game 2x is there, then can't
make it, so the game quits. A new bridge erratum, `msaa2_as_4`, offers 2x and quietly makes it with 4 samples,
so any game that asks for 2x is covered. On fix43 it shows up in the app's **Updates** page.

fix42 is built from source on DroidDeck Mali v0.5.4.1, so it replaces the whole app. It's signed with the same
personal debug key as fix14, so it installs over it.

**fix43: updates from this repository.** The app's **Updates** page now follows this repository's releases.
Before, it offered the official MaliBridge APKs, which won't install over this build (another signing key) and
leave out these fixes. On fix42 or older, install fix43 by hand once; after that, new builds show up in Updates.

![The Retro start screen](screenshots/13-retro-start-screen.jpg)

- **Rebased on DroidDeck Mali v0.5.4.1.** The bridge fixes now sit on top of ASEbrahim's v0.5.4.1 release. Steam,
  Ys I, Ys Origin's rooms and sleep/resume have been checked on it.
- **A Retro tab.** A front end for your emulators, built into DroidDeck so Steam games and retro games live in
  one place (inspired by Daijishō, see Credits). Retro in the side bar opens a start screen like Steam's: covers
  from your own library drift past, with **Play Retro Games** and a Settings sheet. Play opens retro mode over
  the whole screen, with two tabs:
  - **Library:** a card per system. L1/R1, or D-pad down to the systems strip and then left/right, flips
    between them. A system's games open as a list (the selected game's screenshot or cover beside the titles;
    Ⓧ switches between the two) or as a box-art grid. B steps back one level at a time.
  - **Settings:** each system's emulator in a pop-up list (RetroArch cores, My Boy!, PPSSPP, Dolphin, DraStic
    and so on), your ROM folders, the default view (list or grid) and list picture, and wallpaper packs.

  It picks up your existing `Roms` folders on internal storage and the SD card. Covers, screenshots and game
  info (genre, year, developer) come from libretro's databases, and PSP, GameCube and cartridge games are named
  from the disc or a checksum rather than the file name. It has favorites (★) and recently played. Every game
  starts fresh: an emulator left running in the background is closed first, so a new game never comes up as
  the old one or a black screen, and Home from a game brings you back to retro mode where you were.
- **An opt-in cached memory switch** for games that write to GPU memory one byte at a time (see Device tips).
  It's off unless you turn it on for a game.

### Games and BIOS files

The Retro tab is a front end only. It doesn't include, download or link to any games or BIOS files; it plays the
ROM folders you already have, in emulators you install yourself. Only use games and BIOS files you have the
right to use, such as copies of cartridges and discs you own; the rules differ from country to country. What
the Retro tab downloads is artwork and game information (box art, screenshots, names, genres) from libretro's
public databases, never the games themselves.

## What is fixed

| Problem on the stock v0.5.4 | Cause | Fix |
|---|---|---|
| Steam never starts: gamescope dies at once | Mali r32p1 refuses an image-format query with `MUTABLE_FORMAT_BIT` on dma-buf images; gamescope needs it for every flippable image | The bridge retries the query without the flag and answers that (MaliBridge `server_opaque.c`) |
| The whole bridge goes down, or images read back wrong | r32p1 crashes in `vkGetImageSubresourceLayout` on an unbound AHardwareBuffer image, and reports `rowPitch 0` for linear images on AHardwareBuffer pages | A plain "twin" image answers requirements and layouts; linear tiling is hidden under the AHardwareBuffer memory strategy |
| Games installed on the SD card hang at a black screen (Ys Origin) | Android mounts the SD card noexec; DroidDeck's noexec shim only covered FEX processes, not Wine's ARM64EC ones | The shim covers Wine's processes too (`tools/linuxfs/preload/noexec.c`) |
| **White rooms** in Ys Origin (any room with lighting effects, 12 fps) | The game keeps dead particles with NaN matrices in its draw list; a PC GPU drops them, r32p1 draws each as a sheet over the whole screen | New MaliBridge erratum `nan_position`: every vertex shader gets a guard that moves a NaN, Inf or zero-w position outside the clip volume |
| **Press Play twice**: the first launch with frame generation on crashes the app | The Win-FG engine destroys its images and resets its descriptor pool while a frame is still in flight, and reuses descriptor sets from a destroyed pool at the same frame size | Waits for the GPU before both teardowns; the scratch cache is dropped with its pool |
| **Tales of Symphonia quits at start**, back to the Steam page (fix44) | The game wants a 2x MSAA render target; r32p1 multisamples at 1x and 4x only, and DXVK 1.10's D3D9 offers 2x anyway, then fails the texture ("Cannot create texture ... Samples: 2") | New MaliBridge erratum `msaa2_as_4`: 2x is offered wherever 4x exists, and 2x images, render passes and pipelines are made with 4 samples |
| **Ys: The Oath in Felghana shows only the HUD** after a save loads, DirectX 9 version (fix45) | An apitrace shows the world drawn with every world matrix (vertex constants c24-c31) NaN; they come from Microsoft's native `d3dx9_41`/`d3dx9_43`, which return NaN under FEX. The DirectX 8 version, without `d3dx9`, is fine | DroidDeck's known-games table sets `WINEDLLOVERRIDES=d3dx9_41,d3dx9_43=b` for this game (Wine's built-in `d3dx9`), applied by itself at session start |
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

## Games tested

Retroid Pocket 4 Pro, fix13, Proton Experimental, default DroidDeck settings unless noted. Add a row for each
game you try; "Playable" means a full session without crashes or rendering faults.

| Game | Install | Status | FPS | Notes |
|---|---|---|---|---|
| Teenage Mutant Ninja Turtles: Shredder's Revenge | SD card | Playable | 60 average | D3D11 via DXVK, 1280x720, frame generation on; about 20 s black screen at start while shaders compile (normal) |
| Ys Origin | SD card | Playable | 60-69 in rooms, 61 title | D3D9 32-bit; white rooms fixed by `nan_position`; refractive floors 50-60; Win-FG 2x and LSFG 2x (fix14) work |
| Ys I & II Chronicles+ (Ys I) | SD card | Playable | 60 field and dialog | D3D9 via DXVK plus Zink; dialog scenes ran at 5-11 fps until the FEX preset for this game was set to **Performance** (TSO off), see Device tips |
| Tales of Symphonia | SD card | Starts (fix44); play not tested yet | 30 in the opening movie | D3D9 via DXVK; quit at start until fix44 (`msaa2_as_4`) |
| Ys: The Oath in Felghana | SD card | Save loads and draws (fix45); play not tested yet | 69 standing in Icebound Cave | D3D9 via DXVK; showed only the HUD until fix45 switched it to Wine's built-in `d3dx9`. The DirectX 8 version also works (45 fps) |
| Ys VI: The Ark of Napishtim | SD card | Save loads and draws; play not tested yet | about 73 standing in the field | D3D9 via DXVK; works as installed. Steam asks which version to start: pick "Play Ys VI" |
| | | | | |
| | | | | |
| | | | | |

Status words: Playable / Playable with issues / Starts, not playable / Does not start. Please also say
whether the game is on internal storage or the SD card, and the frame-generation setting if it was on.

## Installing

`DroidDeck-MaliBridge-v0.5.4.1-rp4pro-fix45.apk` (on the [Releases](../../releases) page) is DroidDeck Mali
v0.5.4.1 built from source with these fixes and the Retro tab. fix14, the earlier build on v0.5.4, is still
there too. Both are signed with a **personal debug key**, not the author's. Because of that:

- If you have the official DroidDeck Mali installed, **uninstall it first**. Android refuses to install over
  a different signature. You lose the app's settings and have to sign in to Steam again; your Steam library
  on the SD card stays.
- From fix43 on, the app's Updates page follows this repository's releases. The official builds will not
  install over this one. When the fixes land upstream, uninstall this and go back to the official APK.

## What is inside

fix42 and later are built whole from this repository's [`source`](../../tree/source) branch: ASEbrahim's DroidDeck Mali
v0.5.4.1 source release, with MaliBridge `rp4pro-fixes` in `external/MaliBridge`, the compositor fixes from
fix13 and fix14, and the Retro tab, one commit per change.

fix14 and earlier were the stock v0.5.4 APK with three libraries replaced:

| Part | Built from |
|---|---|
| `lib/arm64-v8a/libvkbridge_server.so`, `libvkbridge_jni.so` | MaliBridge, the 4 fixes on v0.5.4 (kept as branch `rp4pro-fixes-v054`) |
| `lib/arm64-v8a/libdroiddeckwayland.so` | DroidDeck's compositor from the author's `mali-test-build` branch + `patches/compositor-fix7-fork-b3006a8a.patch` + `patches/compositor-fix13-winfg-waits.patch` + `patches/compositor-fix14-lsfg-vk11.patch` |
| `assets/linuxfs/libblsession.so` | DroidDeck preload, branch `rp4pro-noexec-arm64ec` |

The patches in `patches/` apply to the fork's `app/src/main/cpp` tree; the same changes are committed on the
DroidDeck branches for `main`.

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
  uncached shared memory (see Device tips). The per-game FEX preset is the workaround for now.

## Device tips

Settings on the device itself, not in the APK.

### Make the Home button work like the Steam button

Out of the box, Home on the RP4 Pro needs two presses and there's no Quick Access menu. These four settings
fix that; all four are set on my RP4 Pro and confirmed working:

1. **Make DroidDeck the Home app.** In DroidDeck: **Setup → Use as Home screen** on, then **Default Home app →
   Choose → DroidDeck**.
2. **Turn off Retroid's Home guard.** In Retroid's **Handheld settings**, turn off **"Prevent press the Home
   button accidentally"**. With it on, the first press is swallowed and only shows a toast. From a PC it's
   `adb shell settings put system prevent_press_home_accidentally 0`.
3. **Set the controller type to Steam Deck.** In DroidDeck's Steam settings (**Settings** under **Play Steam**),
   set **Controller** to **Steam Deck controller** (it's also in the side panel during a session). It applies
   from the next Steam session. As the default Xbox 360 controller, the pad has no Quick Access button, so the
   three dots in the side panel do nothing.
4. **Keep Retroid's Controller style on Retro.** In Retroid's **Handheld settings → Input → Controller style**,
   choose **Retro**. With **XBox**, the Home key reaches apps as a keyboard key and Home stops working
   everywhere: in DroidDeck, in Steam and in emulators. Quick Settings has a Controller style tile too, so if
   Home suddenly stops working, check that it wasn't switched there.

After that, **one press of Home opens the Steam menu**, and the **three dots** in DroidDeck's side panel open
Steam's **Quick Access** menu, the same as on a Steam Deck.

### Other tips

- **A game crawls in menus or dialog scenes while its main thread sits at 100% CPU.** Seen in Ys I & II
  Chronicles+: the game converts a paletted 640x480 image into a locked Direct3D surface one byte at a time, and
  FEX's x86 memory-ordering emulation (TSO) turns every byte into a store-release into the bridge's uncached
  shared memory, about 160 ms a frame. Set that game's FEX preset to **Performance** (TSO off) in its game
  profile. It's a per-game setting and nothing else changes. Old single-threaded games take it well; games
  with lock-free threading may not, so try it per game.
  fix42 adds a second option for games that need TSO kept on: `VKBRIDGE_CACHED_UPLOAD=1` in that
  game's bridge preset gives it cached memory, and the bridge does the cache cleaning itself, since DXVK never
  flushes host memory. That cleaning costs about 3 ms per submit at the default 64 MB budget
  (`VKBRIDGE_CACHED_UPLOAD_MB`), so it's off by default, and for Ys I TSO off is still the better fix.
- **Retroid settings worth trying for a Steam Deck feel** (in Retroid's Settings and Game Assistant):
  - **Performance mode High** and the fan on Smart or Sport while playing. I haven't compared it with Standard
    yet.
  - **L2/R2 mode Analog**, not Digital, so Steam games see real triggers. Keep the stick and trigger dead
    zones small and let Steam Input handle them.
  - Leave the **Back and Home key mappings** at stock ("Enable convert gamepad key value" off); DroidDeck
    relies on the normal Back and Home keys.
  - Keep **"Clean process when standby"** off, or whitelist DroidDeck, so sleep doesn't end your Steam session.
  - **Docked to a TV:** DisplayPort over USB-C goes up to 1080p120 (needs a reboot after changing it), and
    "Disable auto sleep when HDMI/DP connected" keeps the screen on. Not tested with DroidDeck yet.
- **Retro tab emulators.**
  - A RetroArch core has to be downloaded in RetroArch first (**Online Updater → Core Downloader**). The Retro
    tab can't see which cores RetroArch has, so choose one you've downloaded.
  - RetroArch 32 is left out of the lists when RetroArch 64 is installed. It's a second, separate RetroArch with
    its own cores and settings, and one that was never set up plays every game as a black screen.
  - For GBA, My Boy! works. If it shows a **Syncing** box at each start, its Google Drive sync needs your
    approval again in My Boy!'s settings (or turn its sync off); **Skip** plays without syncing.

## Source and commit history

This repository holds the build, the write-ups and the evidence. The app that fix45 is built from is on this
repository's **[`source`](../../commits/source)** branch: the v0.5.4.1 source release as its first commit, then
one commit per change, the Retro tab included. The bridge and compositor fixes, with their history, are also on
branches of the two forks, where each commit is one fix with its reasoning in the message:

**[yujikaido/MaliBridge](https://github.com/yujikaido/MaliBridge/commits/rp4pro-fixes), branch `rp4pro-fixes`**
(now on v0.5.4.1; the v0.5.4 version that fix14 was built from is kept as `rp4pro-fixes-v054`). PR
[ASEbrahim/MaliBridge#35](https://github.com/ASEbrahim/MaliBridge/pull/35) was updated to the v0.5.4.1 base.

| Date | Commit |
|---|---|
| 2026-10-26 | server: answer MUTABLE_FORMAT image format queries Mali r32p1 refuses (Steam starts) |
| 2026-10-26 | server: linear images under the AHardwareBuffer memory strategy (twin images, linear tiling hidden) |
| 2026-10-27 | server: VKBRIDGE_AHB_RARELY from the env file; driver-module SPIR-V substitution (the shader bisection tool) |
| 2026-10-27 | server: nan_position (MK-45), vertex positions guarded on Mali-G77 r32p1 (the white rooms) |
| 2026-10-28 | server: cached upload memory (VKBRIDGE_CACHED_UPLOAD, opt-in, per game) |
| 2026-10-28 | server: cached upload per process, coalesced cleans |
| 2026-10-29 | server: msaa2_as_4 (MK-46), 2x multisampling made with 4 samples (Tales of Symphonia); on the `source` branch only for now |

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
[`docs/PROGRESS-LOG.md`](docs/PROGRESS-LOG.md) is the unpolished day-by-day log, dead ends included.

## Repository layout

| Folder | Contents |
|---|---|
| `screenshots/` | the images above |
| `patches/` | the compositor and frame-generation patches against the `mali-test-build` fork |
| `tools/` | the Vulkan probes run inside the DroidDeck rootfs during the investigation (`vk*.py`), the apitrace frame replay (`ys_replay.py`), and the preload build / APK repack scripts |
| `docs/` | the pull-request write-ups sent upstream, and [`PROGRESS-LOG.md`](docs/PROGRESS-LOG.md), the day-by-day investigation log |
| branch [`source`](../../tree/source) | the whole app fix45 is built from (DroidDeck Mali v0.5.4.1 with the fixes and the Retro tab) |

## Credits

**[yujikaido](https://github.com/yujikaido)** - Using my own Retroid Pocket 4 Pro these fixes were found and
tested on, and of this repository. Every build here went through many launch-test-rebuild rounds 
 over several days; the before/after screenshots are from those sessions. Questions about this build
and reports from other RP4 Pro owners go to this repository's Issues.

[ASEbrahim](https://github.com/ASEbrahim) for MaliBridge and DroidDeck Mali, and
[Droid-Deck](https://github.com/Droid-Deck/DroidDeck) for DroidDeck, which this build is made from.

The Retro tab was inspired by [Daijishō](https://github.com/TapiocaFox/Daijishou) by TapiocaFox, a
great emulator front end for Android, and the one I used before this. The Retro tab's code is new, written for
this build; Daijishō's app is closed source and none of its code is used. What the Retro tab does use from the
Daijishō project, with thanks:

- its **platform definitions** (which emulator apps can play each system, and how to launch them), bundled with
  their MIT license, so emulator support matches what Daijishō users already know
- its community **wallpaper packs**, which you can choose to download from the Daijishō repository; the
  pictures belong to their makers

Box art and screenshots come from [libretro-thumbnails](https://github.com/libretro-thumbnails), game info and
checksum names from [libretro-database](https://github.com/libretro/libretro-database), and arcade names from
the [MAME 2003-Plus](https://github.com/libretro/mame2003-plus-libretro) metadata.

The debugging itself (probes, shader bisection, the apitrace replay and the patches) was done with Claude
(Anthropic) driving the device over adb was under my direction; the write-ups are in the pull requests.
