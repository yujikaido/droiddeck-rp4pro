# MaliBridge by ASEbrahim

MaliBridge by ASEbrahim: https://github.com/ASEbrahim/MaliBridge
(GPL-3.0-or-later with the additional terms in NOTICE here, copied unchanged from the project).

DroidDeck builds MaliBridge from the `external/MaliBridge` submodule, pinned at 48477fe
(v0.5.4) and not modified. Its Vulkan ICD runs inside the Linux runtime and forwards each call
over a Unix socket to its server, which runs in the app on the phone's own Vulkan driver.
DroidDeck uses it only on a GPU without Adreno's KGSL; an Adreno keeps Turnip.

The DroidDeck side of the bridge (the Setup switches, the server's start and session wiring, the
Mali render node stand-in, the compositor's AHardwareBuffer import off Adreno, the Steam web helper
layout, the socket wait and the DXVK choice for older Vulkan drivers) is Ahmad Sayed Alsayed
Ebrahim's (ASEbrahim) work from https://github.com/ASEbrahim/DroidDeck, kept under their name in
this repository's history.

Changes ported from Droid-Deck/DroidDeck #1950 and #2027 (the MaliBridge integration in DroidDeck's
main branch, merged 2026-11-03), each commit naming its author:

- mr-abccc (openMali): the bridge's render node for KWin on Zink (Mali desktop on DroidDeck's KWin 6.8), DNS servers of the other connected Wi-Fi and Ethernet networks, the mscoree
  native-then-builtin fallback for dotnet40 (Terraria), the HUD leaving out GPU readings a Mali can't
  give and a VRAM total of 0, the HUD's session-resolution fallback and CPU clock before temperature,
  and Setup's checks open with the bridge on.
- Kurt Himebauch (xXJSONDeruloXx): the DNS order (active network, discovered, public, gateways; none
  discovered behind a VPN) and the Steam session ending when the bridge is not usable.
- KIMCHI (K11MCH1): the Steam UI composited on the bridge and the software Steam UI under 4 GB of RAM
  (already in this line before the port).
- Max (maxjivi05): the MaliBridge client component in CI, the bridge on by default on a Mali GPU and
  the translated bridge settings (already in this line before the port).

MaliBridge's own credits, in its README, include:

- Vulkan-Headers 1.4.341 (Khronos Group), Apache-2.0 OR MIT: https://github.com/KhronosGroup/Vulkan-Headers
- Arm's HWCPipe (MIT), whose counter layouts its GPU counter reader follows: https://github.com/ARM-software/HWCPipe
- bcdec by Sergii Kudlai, MIT or Unlicense: https://github.com/iOrange/bcdec
- etc2_encode by Lee Gao, MIT: https://github.com/leegao/etc2_encode
- Lee Gao's Mali findings and SPIRV-Tools MaliOptimizationBarrierPass (Apache-2.0):
  https://github.com/leegao/SPIRV-Tools, and compat_layer (MIT): https://github.com/leegao/compat_layer
- Julius Pinsker's bionic-vulkan-wrapper PR #228 (MIT)
- David Cimo's droiddeck-mali (GPL-3.0): https://github.com/DavidCimo/droiddeck-mali
- The412Banner's Mali compatibility layer and testing on the DroidDeck branch `test/non-adreno-all`
- taowen's libhybris `arlinux` branch (Apache-2.0): https://github.com/taowen/libhybris/tree/arlinux
- GameNative (GPL-3.0), wallentx's placed-memory layer for DroidDeck (MIT), Arm's published errata,
  ANGLE's Mali workarounds, and design notes from Winlator's Vortek

Each keeps its own licence and notice in the submodule (`third_party/` and the files that name
them).
