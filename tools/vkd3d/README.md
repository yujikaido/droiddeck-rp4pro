# VKD3D-Proton readback fallback

`patches/0001-readback-host-cached-fallback.patch` applies to VKD3D-Proton commit
`7f0c30ad3c8f28dfe47c34bb897b972e2d1ca724`. It makes the suballocator treat
`HOST_CACHED` as optional, matching the existing direct allocation fallback.
Cached memory remains the preferred first choice; when it is unavailable,
the second attempt can use a real host-visible type instead. No Vulkan
capability or memory property is invented.

This applies to all callers of the suballocator and is not tied to a game or a
particular driver. On RP4 Pro with PanVK, the separate 64-bit component was
installed into Proton Experimental (ARM64) and Little Nightmares Enhanced Edition
reached gameplay using the Proton's compatible DXGI, without game-local DLLs.
Performance is currently very low. Other Proton versions and 32-bit components
have not been validated.

To build an experimental 64-bit Windows DLL, check out that exact upstream
commit with its submodules, apply the patch, and use VKD3D-Proton's normal
`build-win64.txt` Meson cross-build. The toolchain must provide a recent MinGW
header defining `PATHCCH_NONE` (or define it to zero for an older toolchain).
`bash tools/vkd3d/package-component.sh <build-dir> <output.wcp>` packages the
built x64 DLLs in the component layout used by Proton ARM64. The component
manager preserves the Proton's original files for rollback; 32-bit DLLs are
not included and stay on the original version. The resulting DLL must be paired
with compatible Proton/DXGI components. This patch is against
[VKD3D-Proton](https://github.com/HansKristian-Work/vkd3d-proton), which is
licensed under the LGPL; consult the upstream repository for full license terms.
