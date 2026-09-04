#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/mod"

APP_SRC="$ROOT/app/src/main/java/io/github/s1ddhants1/swiftbackupprem"
HOOK_DIR="$APP_SRC/hook"
MODULE_FILE="$APP_SRC/Module.kt"

echo "[mod] root=$ROOT"
echo "[mod] mod=$MOD"

mkdir -p "$HOOK_DIR"

# ------------------------------------------------------------
# 1. 注入 ModHook.kt
# ------------------------------------------------------------

if [ ! -f "$MOD/ModHook.kt" ]; then
    echo "[FAIL] $MOD/ModHook.kt not found"
    exit 1
fi

cp "$MOD/ModHook.kt" "$HOOK_DIR/ModHook.kt"

echo "[OK] injected ModHook.kt"

# ------------------------------------------------------------
# 2. 给 Module.kt 增加 ModHook.apply()
# ------------------------------------------------------------

if [ ! -f "$MODULE_FILE" ]; then
    echo "[FAIL] $MODULE_FILE not found"
    exit 1
fi

python3 - "$MODULE_FILE" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")

marker = "            ModHook.apply(this, ctx, cl, targets, prefs)"

if marker in text:
    print("[OK] ModHook already registered")
    sys.exit(0)

needle = "          CloudDiscoveryHook.apply(this, ctx, cl, targets, prefs)"

if needle not in text:
    print("[FAIL] Module.kt injection marker not found")
    sys.exit(1)

text = text.replace(
    needle,
    needle + "\n          ModHook.apply(this, ctx, cl, targets, prefs)",
    1,
)

path.write_text(text, encoding="utf-8")
print("[OK] ModHook registered in Module.kt")
PY

echo "[OK] mod injection complete"