#!/usr/bin/env bash
#
# build_aosp_guest_rom.sh — produce a clean-room AOSP guest ROM (tar.zst + manifest)
# for the AVM guest pipeline (RomInstaller / RomArchiveReader / RootfsHealthCheck).
#
# Clean-room policy: the output ROM is a DEV/CI ARTIFACT and is NOT committed to the
# repo (release builds ship no guest assets; product uses user-provided offline import).
# The guest binaries come from AOSP source (Apache-2.0), never from Google's prebuilt
# emulator/device images.
#
# Modes:
#   --rootfs-dir DIR     Package an already-assembled rootfs tree (portable; tar+zstd only).
#   --system-img IMG     Extract a built AOSP system.img, assemble a rootfs, then package
#                        (Linux only: needs simg2img + e2fsprogs `debugfs`).
#
# The full AOSP build (repo sync + lunch + make) is intentionally out of this script's
# hot path — see "AOSP BUILD" below; it is heavy and environment-specific (Docker).
#
# Output: <out>/<name>.tar.zst, <out>/<name>.sha256, <out>/<name>.manifest.json
#
# ---------------------------------------------------------------------------
# AOSP BUILD (run once in a pinned Docker env, then point --system-img here):
#   docker run -it --rm -v "$PWD:/aosp" -w /aosp ubuntu:16.04   # + openjdk-8, repo, python2
#   repo init -u https://android.googlesource.com/platform/manifest -b android-7.1.2_r36
#   repo sync -c -j"$(nproc)"
#   source build/envsetup.sh && lunch aosp_arm64-userdebug && make -j"$(nproc)"
#   # -> out/target/product/generic_arm64/system.img
# ---------------------------------------------------------------------------

set -euo pipefail

NAME=""
GUEST_VERSION="7.1.2"
ARCH="arm64"
MIN_HOST_SDK="26"
OUT_DIR="./out/guest-rom"
ROOTFS_DIR=""
SYSTEM_IMG=""

die() { echo "error: $*" >&2; exit 1; }

usage() {
  sed -n '2,40p' "$0"
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rootfs-dir) ROOTFS_DIR="${2:?}"; shift 2 ;;
    --system-img) SYSTEM_IMG="${2:?}"; shift 2 ;;
    --out) OUT_DIR="${2:?}"; shift 2 ;;
    --name) NAME="${2:?}"; shift 2 ;;
    --guest-version) GUEST_VERSION="${2:?}"; shift 2 ;;
    --arch) ARCH="${2:?}"; shift 2 ;;
    --min-host-sdk) MIN_HOST_SDK="${2:?}"; shift 2 ;;
    -h|--help) usage 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

command -v tar >/dev/null  || die "tar not found"
command -v zstd >/dev/null || die "zstd not found (brew install zstd / apt install zstd)"

[[ -z "$NAME" ]] && NAME="androidfs_${GUEST_VERSION}_${ARCH}"

# --- cross-platform helpers ---
file_size() { # bytes
  if stat -f%z "$1" >/dev/null 2>&1; then stat -f%z "$1"; else stat -c%s "$1"; fi
}
sha256_hex() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}
# Reads bytes 0-3 (magic), 4 (class), 18 (e_machine low) to confirm an arm64 ELF64.
is_arm64_elf() {
  local f="$1"
  [[ -f "$f" ]] || return 1
  local magic class machine
  magic=$(dd if="$f" bs=1 count=4 skip=0 2>/dev/null | od -An -tx1 | tr -d ' \n')
  class=$(dd if="$f" bs=1 count=1 skip=4 2>/dev/null | od -An -tx1 | tr -d ' \n')
  machine=$(dd if="$f" bs=1 count=1 skip=18 2>/dev/null | od -An -tx1 | tr -d ' \n')
  [[ "$magic" == "7f454c46" && "$class" == "02" && "$machine" == "b7" ]]
}

WORK_DIR="$(mktemp -d)"
cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT

# --- obtain a rootfs tree ---
if [[ -n "$SYSTEM_IMG" ]]; then
  [[ -n "$ROOTFS_DIR" ]] && die "pass either --system-img or --rootfs-dir, not both"
  command -v simg2img >/dev/null || die "simg2img not found (android-sdk-libsparse-utils)"
  command -v debugfs  >/dev/null || die "debugfs not found (e2fsprogs)"
  echo "==> extracting $SYSTEM_IMG"
  simg2img "$SYSTEM_IMG" "$WORK_DIR/system.raw.img"
  ROOTFS_DIR="$WORK_DIR/rootfs"
  mkdir -p "$ROOTFS_DIR/system"
  # AOSP system.img root == /system contents; dump it into rootfs/system (no root needed).
  debugfs -R "rdump / $ROOTFS_DIR/system" "$WORK_DIR/system.raw.img" >/dev/null
fi

[[ -n "$ROOTFS_DIR" ]] || die "nothing to package: pass --rootfs-dir or --system-img"
[[ -d "$ROOTFS_DIR" ]] || die "rootfs dir not found: $ROOTFS_DIR"

# --- assemble: ensure the pipeline's required + writable + boot-ready layout ---
mkdir -p "$ROOTFS_DIR/vendor" "$ROOTFS_DIR/data" "$ROOTFS_DIR/cache"

req=(
  system/build.prop system/bin/app_process64 system/bin/servicemanager
  system/bin/sh system/framework
)
for r in "${req[@]}"; do
  [[ -e "$ROOTFS_DIR/$r" ]] || die "rootfs missing required entry: $r"
done

# bootReady prerequisites must be real arm64 ELFs (mirrors RootfsHealthCheck.bootReady).
for b in system/bin/linker64 system/lib64/libc.so system/bin/app_process64; do
  is_arm64_elf "$ROOTFS_DIR/$b" || die "not an arm64 ELF (not boot-ready): $b"
done
echo "==> rootfs is structurally complete and boot-ready"

# --- package: tar preserves symlinks + perms; zstd compresses ---
mkdir -p "$OUT_DIR"
TAR="$WORK_DIR/$NAME.tar"
echo "==> taring rootfs (symlinks + permissions preserved)"
tar -C "$ROOTFS_DIR" -cf "$TAR" system vendor data cache
ARCHIVE="$OUT_DIR/$NAME.tar.zst"
echo "==> zstd compressing -> $ARCHIVE"
zstd -19 -q -f "$TAR" -o "$ARCHIVE"

UNCOMPRESSED=$(file_size "$TAR")
COMPRESSED=$(file_size "$ARCHIVE")
SHA=$(sha256_hex "$ARCHIVE")
CREATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "$SHA" > "$OUT_DIR/$NAME.sha256"
cat > "$OUT_DIR/$NAME.manifest.json" <<JSON
{
  "name": "$NAME",
  "guestVersion": "$GUEST_VERSION",
  "guestArch": "$ARCH",
  "format": "tar.zst",
  "compressedSize": $COMPRESSED,
  "uncompressedSize": $UNCOMPRESSED,
  "sha256": "$SHA",
  "createdAt": "$CREATED_AT",
  "minHostSdk": $MIN_HOST_SDK
}
JSON

cat <<DONE

==> done
  archive:  $ARCHIVE  ($COMPRESSED bytes)
  manifest: $OUT_DIR/$NAME.manifest.json
  sha256:   $SHA

Notes:
  - This artifact is a dev/CI ROM; do NOT commit it (release ships no guest assets).
  - Signing: the offline import path expects an Ed25519-signed manifest. Add
    "signature"/"publicKeyId"/"patchLevel" via the signing tool (EP8.2); the manifest
    above is the canonical body to sign.
DONE
