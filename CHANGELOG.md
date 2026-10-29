# Changelog

Newest first. Each build is on the [Releases](../../releases) page; from fix43 on, the app's **Updates** page
offers new ones by itself. Back to the [README](README.md).

## fix45: Ys: The Oath in Felghana draws its world

In the DirectX 9 version, a loaded save showed only the HUD on black. The game was drawing the world, but every
world matrix came out as NaN: they come from Microsoft's own `d3dx9` library, which gets them wrong under FEX
(the x86 emulator). Wine's built-in `d3dx9` gets them right, so DroidDeck now switches Oath to it by itself (its
known-games table). Icebound Cave runs at 69 fps. Details in [FIXES.md](FIXES.md).

| Before: only the HUD on black | After fix45: 69 fps |
|---|---|
| ![Oath before](screenshots/14-ys-oath-icebound-cave-before-black.jpg) | ![Oath after](screenshots/15-ys-oath-icebound-cave-after-fix45-69fps.jpg) |

## fix44: Tales of Symphonia starts

It used to close at once and drop back to the Steam page. The game asks for 2x anti-aliasing (MSAA), and this
GPU only does 1x and 4x. DXVK tells the game 2x is there, then can't make it, so the game quits. A new bridge
erratum, `msaa2_as_4`, offers 2x and quietly makes it with 4 samples, so any game that asks for 2x is covered.
On fix43 it shows up in the app's **Updates** page.

## fix43: updates from this repository

The app's **Updates** page now follows this repository's releases. Before, it offered the official MaliBridge
APKs, which won't install over this build (another signing key) and leave out these fixes. On fix42 or older,
install fix43 by hand once; after that, new builds show up in Updates.

## fix42: v0.5.4.1 from source, and the Retro tab

fix42 is built from source on DroidDeck Mali v0.5.4.1, so it replaces the whole app. It's signed with the same
personal debug key as fix14, so it installs over it.

![The Retro start screen](screenshots/13-retro-start-screen.jpg)

- **Rebased on DroidDeck Mali v0.5.4.1.** The bridge fixes now sit on top of ASEbrahim's v0.5.4.1 release. Steam,
  Ys I, Ys Origin's rooms and sleep/resume have been checked on it.
- **A Retro tab.** A front end for your emulators, built into DroidDeck so Steam games and retro games live in
  one place (inspired by Daijishō, see [Credits](README.md#credits)). Retro in the side bar opens a start screen
  like Steam's: covers from your own library drift past, with **Play Retro Games** and a Settings sheet. Play
  opens retro mode over the whole screen, with two tabs:
  - **Library:** a card per system. L1/R1, or D-pad down to the systems strip and then left/right, flips
    between them. A system's games open as a list (the selected game's screenshot or cover beside the titles;
    Ⓧ switches between the two) or as a box-art grid. B steps back one level at a time.
  - **Settings:** each system's emulator in a pop-up list (RetroArch cores, My Boy!, PPSSPP, Dolphin, DraStic
    and so on), your ROM folders, the default view (list or grid) and list picture, and wallpaper packs.

  It picks up your existing `Roms` folders on internal storage and the SD card. Covers, screenshots and game
  info (genre, year, developer) come from libretro's databases, and PSP, GameCube and cartridge games are named
  from the disc or a checksum rather than the file name. It has favorites (★) and recently played. Every game
  starts fresh: an emulator left running in the background is closed first, so a new game never comes up as
  the old one or a black screen, and Home from a game brings you back to retro mode where you were. See
  [Games and BIOS files](README.md#games-and-bios-files).
- **An opt-in cached memory switch** for games that write to GPU memory one byte at a time (see
  [Device tips](README.md#other-tips)). It's off unless you turn it on for a game.

## fix14 and earlier

The stock v0.5.4 APK with three libraries replaced: Steam starts, SD-card games run, Ys Origin's white rooms
are gone, "press Play twice" no longer crashes, and LSFG works on the Vulkan 1.1 driver (fix14). Each is a row
in [FIXES.md](FIXES.md); [`docs/PROGRESS-LOG.md`](docs/PROGRESS-LOG.md) has the day-by-day log.
