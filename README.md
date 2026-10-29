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

## Latest: fix45

**Ys: The Oath in Felghana draws its world** in the DirectX 9 version (it showed only the HUD on black), and
fix44 made **Tales of Symphonia** start. Get it from the [Releases](../../releases) page, or from the app's
**Updates** page on fix43 or later. Every build's notes are in the [CHANGELOG](CHANGELOG.md).

## More pages

| Page | What is in it |
|---|---|
| [GAMES.md](GAMES.md) | the games tested on the RP4 Pro: status, frame rate, settings each one needs |
| [CHANGELOG.md](CHANGELOG.md) | what each build added, newest first, with before/after screenshots |
| [FIXES.md](FIXES.md) | what is fixed and why, the Ys Origin screenshots and how the white room was found, known limits, what is inside and the source history |
| [docs/PROGRESS-LOG.md](docs/PROGRESS-LOG.md) | the unpolished day-by-day investigation log |

## Installing

`DroidDeck-MaliBridge-v0.5.4.1-rp4pro-fix45.apk` (on the [Releases](../../releases) page) is DroidDeck Mali
v0.5.4.1 built from source with these fixes and the Retro tab. fix14, the earlier build on v0.5.4, is still
there too. Both are signed with a **personal debug key**, not the author's. Because of that:

- If you have the official DroidDeck Mali installed, **uninstall it first**. Android refuses to install over
  a different signature. You lose the app's settings and have to sign in to Steam again; your Steam library
  on the SD card stays.
- From fix43 on, the app's Updates page follows this repository's releases. The official builds will not
  install over this one. When the fixes land upstream, uninstall this and go back to the official APK.

## Games and BIOS files

The Retro tab is a front end only. It doesn't include, download or link to any games or BIOS files; it plays the
ROM folders you already have, in emulators you install yourself. Only use games and BIOS files you have the
right to use, such as copies of cartridges and discs you own; the rules differ from country to country. What
the Retro tab downloads is artwork and game information (box art, screenshots, names, genres) from libretro's
public databases, never the games themselves.

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

## Repository layout

| Folder | Contents |
|---|---|
| `GAMES.md`, `CHANGELOG.md`, `FIXES.md` | the games tested, each build's changes, and the fixes with their causes and source history |
| `screenshots/` | the images on these pages |
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
