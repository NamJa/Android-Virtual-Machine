#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSET_DIR="$ROOT_DIR/app/src/debug/assets/guest"
NAME="androidfs_7.1.2_arm64_debug"
ARCHIVE="$ASSET_DIR/$NAME.zip"
SHA_FILE="$ASSET_DIR/$NAME.sha256"
MANIFEST="$ASSET_DIR/$NAME.manifest.json"
WORK_DIR="$(mktemp -d)"

cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

sdk_dir() {
  if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME:-}" ]]; then
    printf '%s\n' "$ANDROID_HOME"
    return
  fi
  if [[ -f "$ROOT_DIR/local.properties" ]]; then
    sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | head -1
    return
  fi
  printf '%s\n' "$HOME/Library/Android/sdk"
}

find_ndk() {
  if [[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME:-}" ]]; then
    printf '%s\n' "$ANDROID_NDK_HOME"
    return
  fi
  local sdk
  sdk="$(sdk_dir)"
  find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1
}

find_tool() {
  local ndk="$1"
  local tool="$2"
  find "$ndk/toolchains/llvm/prebuilt" -path "*/bin/$tool" -type f | head -1
}

NDK_DIR="$(find_ndk)"
if [[ -z "$NDK_DIR" ]]; then
  echo "Unable to locate Android NDK. Set ANDROID_NDK_HOME or sdk.dir in local.properties." >&2
  exit 1
fi

CLANG="$(find_tool "$NDK_DIR" "aarch64-linux-android26-clang")"
if [[ -z "$CLANG" ]]; then
  echo "Unable to locate aarch64-linux-android26-clang under $NDK_DIR." >&2
  exit 1
fi

mkdir -p \
  "$ASSET_DIR" \
  "$WORK_DIR/rootfs/system/bin" \
  "$WORK_DIR/rootfs/system/framework" \
  "$WORK_DIR/rootfs/system/lib64" \
  "$WORK_DIR/rootfs/vendor" \
  "$WORK_DIR/rootfs/data" \
  "$WORK_DIR/rootfs/cache"

cat > "$WORK_DIR/rootfs/system/build.prop" <<'PROP'
ro.product.brand=CleanRoom
ro.product.manufacturer=CleanRoom
ro.product.model=VirtualPhoneDebug
ro.product.device=virtual_phone_debug
ro.build.version.release=7.1.2
ro.zygote=zygote64
PROP

cat > "$WORK_DIR/rootfs/system/bin/app_process64" <<'SH'
#!/system/bin/sh
echo debug app_process64 fixture
SH

cat > "$WORK_DIR/rootfs/system/bin/servicemanager" <<'SH'
#!/system/bin/sh
echo debug servicemanager fixture
SH

cat > "$WORK_DIR/rootfs/system/bin/sh" <<'SH'
#!/system/bin/sh
echo debug shell fixture
SH

cat > "$WORK_DIR/avm_hello.S" <<'ASM'
.text
.global avm_guest_entry
.type avm_guest_entry, %function
avm_guest_entry:
  stp x29, x30, [sp, #-16]!
  mov x29, sp
  mov x5, x0
  ldr x4, [x5, #8]
  ldr x0, [x5]
  adrp x1, msg
  add x1, x1, :lo12:msg
  mov x2, #6
  blr x4
  mov w0, #0
  ldp x29, x30, [sp], #16
  ret

.section .rodata
msg:
  .ascii "hello\n"
ASM

cat > "$WORK_DIR/empty_so.S" <<'ASM'
.text
.global avm_guest_stub
.type avm_guest_stub, %function
avm_guest_stub:
  ret
ASM

"$CLANG" \
  -nostdlib -fPIE -pie \
  -Wl,-e,avm_guest_entry \
  -Wl,--dynamic-linker=/system/bin/linker64 \
  -Wl,--build-id=none \
  -o "$WORK_DIR/rootfs/system/bin/avm-hello" \
  "$WORK_DIR/avm_hello.S"

"$CLANG" -nostdlib -shared -Wl,-soname,linker64 -Wl,--build-id=none \
  -o "$WORK_DIR/rootfs/system/bin/linker64" "$WORK_DIR/empty_so.S"
"$CLANG" -nostdlib -shared -Wl,-soname,libc.so -Wl,--build-id=none \
  -o "$WORK_DIR/rootfs/system/lib64/libc.so" "$WORK_DIR/empty_so.S"
"$CLANG" -nostdlib -shared -Wl,-soname,libdl.so -Wl,--build-id=none \
  -o "$WORK_DIR/rootfs/system/lib64/libdl.so" "$WORK_DIR/empty_so.S"

touch "$WORK_DIR/rootfs/system/framework/.keep"
touch "$WORK_DIR/rootfs/vendor/.keep"
chmod 0755 \
  "$WORK_DIR/rootfs/system/bin/app_process64" \
  "$WORK_DIR/rootfs/system/bin/servicemanager" \
  "$WORK_DIR/rootfs/system/bin/sh" \
  "$WORK_DIR/rootfs/system/bin/avm-hello" \
  "$WORK_DIR/rootfs/system/bin/linker64"
chmod 0644 \
  "$WORK_DIR/rootfs/system/lib64/libc.so" \
  "$WORK_DIR/rootfs/system/lib64/libdl.so"
find "$WORK_DIR/rootfs" -exec touch -t 202401010000 {} +

rm -f "$ARCHIVE" "$SHA_FILE" "$MANIFEST"
(
  cd "$WORK_DIR"
  zip -X -q -r "$ARCHIVE" rootfs
)

SHA256="$(shasum -a 256 "$ARCHIVE" | awk '{print $1}')"
COMPRESSED_SIZE="$(wc -c < "$ARCHIVE" | tr -d ' ')"
UNCOMPRESSED_SIZE="$(find "$WORK_DIR/rootfs" -type f -print0 | xargs -0 wc -c | tail -1 | awk '{print $1}')"

printf '%s  %s\n' "$SHA256" "$(basename "$ARCHIVE")" > "$SHA_FILE"
cat > "$MANIFEST" <<JSON
{
  "name": "$NAME",
  "guestVersion": "7.1.2",
  "guestArch": "arm64",
  "format": "zip",
  "compressedSize": $COMPRESSED_SIZE,
  "uncompressedSize": $UNCOMPRESSED_SIZE,
  "sha256": "$SHA256",
  "createdAt": "2024-01-01T00:00:00Z",
  "minHostSdk": 26
}
JSON

echo "Generated debug guest fixture:"
echo "  $ARCHIVE"
echo "  $SHA_FILE"
echo "  $MANIFEST"
