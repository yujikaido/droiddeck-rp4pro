#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <vkd3d-build-dir> <output.wcp>" >&2
    exit 2
fi
build=$(realpath "$1")
output=$(realpath -m "$2")
source_dir=$(dirname "$build")
expected=7f0c30ad3c8f28dfe47c34bb897b972e2d1ca724
actual=$(git -C "$source_dir" rev-parse HEAD)
if [ "$actual" != "$expected" ]; then
    echo "Unexpected VKD3D revision: $actual" >&2
    exit 1
fi
grep -Fq 'const VkMemoryPropertyFlags optional_flags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT;' "$source_dir/libs/vkd3d/memory.c" || {
    echo 'The readback fallback patch is missing.' >&2
    exit 1
}
for dll in libs/d3d12/d3d12.dll libs/d3d12core/d3d12core.dll; do
    test -f "$build/$dll" || { echo "Missing $build/$dll" >&2; exit 1; }
done
stage=$(mktemp -d)
trap 'rm -rf -- "$stage"' EXIT
mkdir -p "$stage/files/lib/wine/vkd3d-proton/aarch64-windows"
cp "$build/libs/d3d12/d3d12.dll" "$stage/files/lib/wine/vkd3d-proton/aarch64-windows/"
cp "$build/libs/d3d12core/d3d12core.dll" "$stage/files/lib/wine/vkd3d-proton/aarch64-windows/"
cat > "$stage/profile.json" <<'EOF'
{"type":"VKD3D-Linux","versionName":"3.1.0-readback-fallback","versionCode":1,"description":"VKD3D-Proton 7f0c30ad with host-visible readback fallback; x64 DLLs for Proton ARM64"}
EOF
mkdir -p "$(dirname "$output")"
tar --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 --numeric-owner -C "$stage" -cf - profile.json files | zstd -q -T1 -9 -o "$output"
sha256sum "$output"
