# Games tested

[README](README.md) · **Games** · [Changelog](CHANGELOG.md) · [Fixes](FIXES.md) · [License](LICENSE)

Retroid Pocket 4 Pro, fix13 to fix45, Proton Experimental, default DroidDeck settings unless noted. Add a row
for each game you try; "Playable" means a full session without crashes or rendering faults.

| Game | Install | Status | FPS | Notes |
|---|---|---|---|---|
| Teenage Mutant Ninja Turtles: Shredder's Revenge | SD card | Playable | 60 average | D3D11 via DXVK, 1280x720, frame generation on; about 20 s black screen at start while shaders compile (normal) |
| Ys Origin | SD card | Playable | 60-69 in rooms, 61 title | D3D9 32-bit; white rooms fixed by `nan_position`; refractive floors 50-60; Win-FG 2x and LSFG 2x (fix14) work |
| Ys I & II Chronicles+ (Ys I) | SD card | Playable | 60 field and dialog | D3D9 via DXVK plus Zink; dialog scenes ran at 5-11 fps until the FEX preset for this game was set to **Performance** (TSO off), see [Device tips](README.md#other-tips) |
| Tales of Symphonia | SD card | Starts (fix44); play not tested yet | 30 in the opening movie | D3D9 via DXVK; quit at start until fix44 (`msaa2_as_4`) |
| Ys: The Oath in Felghana | SD card | Save loads and draws (fix45); play not tested yet | 69 standing in Icebound Cave | D3D9 via DXVK; showed only the HUD until fix45 switched it to Wine's built-in `d3dx9`. The DirectX 8 version also works (45 fps) |
| Ys VI: The Ark of Napishtim | SD card | Save loads and draws; play not tested yet | about 73 standing in the field | D3D9 via DXVK; works as installed. Steam asks which version to start: pick "Play Ys VI" |
| Tales of Eternia Remastered | SD card (FAT32) | Does not install | - | Steam stops with "Not enough disk space" although the card has room: one folder holds 9,362 files, more than a FAT32 folder can list. Use internal storage or an exFAT card |
| | | | | |
| | | | | |
| | | | | |

Status words: Playable / Playable with issues / Starts, not playable / Does not start. Please also say
whether the game is on internal storage or the SD card, and the frame-generation setting if it was on.

## Per-game notes

- **Ys Origin:** before/after screenshots of the white room, LSFG and Win-FG are in [FIXES.md](FIXES.md#screenshots).
- **Ys I & II Chronicles+:** set its FEX preset to **Performance** (TSO off) for full-speed dialog scenes.
- **Tales of Symphonia:** needs fix44 or later.
- **Ys: The Oath in Felghana:** needs fix45 or later for the DirectX 9 version; DroidDeck sets it up by itself.
  Before/after screenshots are in the [CHANGELOG](CHANGELOG.md#fix45-ys-the-oath-in-felghana-draws-its-world).
- **Ys VI: The Ark of Napishtim:** Steam's launch menu offers four entries; "Play Ys VI" is the DirectX 9 game.
