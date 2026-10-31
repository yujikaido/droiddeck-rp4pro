# Games tested

[README](README.md) · **Games** · [Changelog](CHANGELOG.md) · [Fixes](FIXES.md) · [License](LICENSE)

Retroid Pocket 4 Pro, fix13 to fix51, Proton Experimental, default DroidDeck settings unless noted. Add a row
for each game you try; "Playable" means a full session without crashes or rendering faults.

| Game | Install | Status | FPS | Notes |
|---|---|---|---|---|
| Teenage Mutant Ninja Turtles: Shredder's Revenge | SD card | Playable | 60 average | D3D11 via DXVK, 1280x720, frame generation on; about 20 s black screen at start while shaders compile (normal) |
| Ys Origin | SD card | Playable | 60-69 in rooms, 61 title; Search Party's Base 45 with 4x anti-aliasing, 77 with it off | D3D9 32-bit; white rooms fixed (fix48: Wine's built-in `d3dx9`, fire and glow effects back); anti-aliasing switch on the game page (fix47); refractive floors 50-60; Win-FG 2x and LSFG 2x (fix14) work |
| Ys I & II Chronicles+ (Ys I) | SD card | Playable | 60 field and dialog | D3D9 via DXVK plus Zink; dialog scenes ran at 5-11 fps until the FEX preset for this game was set to **Performance** (TSO off), see [Device tips](README.md#other-tips) |
| Tales of Symphonia | SD card | Playable (fix49) | 30 in battle (the game's cap), 60 shown with LSFG 2x | D3D9 via DXVK; quit at start until fix44 (`msaa2_as_4`); battles ran 15 fps until fix49 moved the game off a slow core. Use Retroid **Performance** mode or higher: on Standard, battles drop to about 12 |
| Ys: The Oath in Felghana | SD card | Save loads and draws (fix45); play not tested yet | 69 standing in Icebound Cave | D3D9 via DXVK; showed only the HUD until fix45 switched it to Wine's built-in `d3dx9`. The DirectX 8 version also works (45 fps) |
| Ys VI: The Ark of Napishtim | Internal storage (SD card before) | Starts and runs | about 73 standing in the field | D3D9 via DXVK; works as installed. Steam asks which version to start: pick "Play Ys VI". If it closes a second after starting, see the tip below the table |
| Tales of Eternia Remastered | SD card (exFAT) | Playable | about 53 in towns and battles | Needs an **exFAT** card or internal storage: on FAT32, Steam stops with "Not enough disk space" (one folder holds 9,362 files, more than a FAT32 folder can list). Copied from a PC instead of downloaded, see the tip below. About a minute to start from a slow card |
| STAR OCEAN THE SECOND STORY R | | Next to test | - | |
| | | | | |
| | | | | |
| | | | | |

Status words: Playable / Playable with issues / Starts, not playable / Does not start. Please also say
whether the game is on internal storage or the SD card, and the frame-generation setting if it was on.

## Tips

- **Every game closes about a second after starting?** Steam updated Proton while it was running, and the update
  put back a graphics layer (DXVK 3) that the Mali chip can't run. Close Steam in DroidDeck and open it again:
  DroidDeck puts its Mali version (DXVK 1.10.3) back when Steam starts.
- **Slow downloads to the SD card:** Steam writes downloads in scattered pieces, which many cards handle slowly.
  Internal storage is much faster, and Steam's *Local Network Game Transfers* can copy a game from another PC at
  home that has it installed. To fill the card from a PC instead, copy the game's folder from
  `steamapps\common` **and** its `appmanifest_<id>.acf` file into
  `Android\data\com.droiddeck.launcher.mali\files\steam\steamapps` on the card (close Steam on the handheld
  first). Steam then sees the game as installed and downloads nothing. Without the `.acf` file it downloads it all
  again.

## Per-game notes

- **Ys Origin:** before/after screenshots of the white room, LSFG and Win-FG are in [FIXES.md](FIXES.md#screenshots).
- **Ys I & II Chronicles+:** set its FEX preset to **Performance** (TSO off) for full-speed dialog scenes.
- **Tales of Symphonia:** needs fix44 or later to start, fix49 for full speed. The game locks its main thread to
  "CPU 0", a slow Cortex-A55 on this chip; DroidDeck numbers the cores fast-first (`WINE_CPU_TOPOLOGY`), by
  itself: for this game in fix49, for every game since fix50. Best: Retroid **Performance** mode (the middle one) with **LSFG 2x**, about 60 fps on screen.
- **Ys: The Oath in Felghana:** needs fix45 or later for the DirectX 9 version; DroidDeck sets it up by itself.
  Before/after screenshots are in the [CHANGELOG](CHANGELOG.md#fix45-ys-the-oath-in-felghana-draws-its-world).
- **Ys VI: The Ark of Napishtim:** Steam's launch menu offers four entries; "Play Ys VI" is the DirectX 9 game.
