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

mkdir -p \
  "$ASSET_DIR" \
  "$WORK_DIR/rootfs/system/bin" \
  "$WORK_DIR/rootfs/system/framework" \
  "$WORK_DIR/rootfs/vendor"

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

touch "$WORK_DIR/rootfs/system/framework/.keep"
touch "$WORK_DIR/rootfs/vendor/.keep"
chmod 0755 "$WORK_DIR/rootfs/system/bin/app_process64"
chmod 0755 "$WORK_DIR/rootfs/system/bin/servicemanager"
chmod 0755 "$WORK_DIR/rootfs/system/bin/sh"
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
