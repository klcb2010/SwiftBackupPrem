#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/mod"

APP_SRC="$ROOT/app/src/main/java/io/github/s1ddhants1/swiftbackupprem"
HOOK_DIR="$APP_SRC/hook"
MODULE_FILE="$APP_SRC/Module.kt"

echo "[mod] root=$ROOT"
echo "[mod] mod=$MOD"

# ------------------------------------------------------------
# Check required files
# ------------------------------------------------------------

if [ ! -f "$MOD/ModHook.kt" ]; then
    echo "[FAIL] $MOD/ModHook.kt not found"
    exit 1
fi

if [ ! -f "$MODULE_FILE" ]; then
    echo "[FAIL] $MODULE_FILE not found"
    exit 1
fi

mkdir -p "$HOOK_DIR"

# ------------------------------------------------------------
# Inject ModHook.kt
# ------------------------------------------------------------

cp "$MOD/ModHook.kt" "$HOOK_DIR/ModHook.kt"

echo "[OK] injected ModHook.kt"

# ------------------------------------------------------------
# Register ModHook in Module.kt
# ------------------------------------------------------------

python3 - "$MODULE_FILE" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")

marker = "ModHook.apply(this, ctx, cl, targets, prefs)"

# Already injected
if marker in text:
    print("[OK] ModHook already registered")
    sys.exit(0)

lines = text.splitlines()

# Preferred insertion point:
# CloudDiscoveryHook.apply(...)
preferred = None

for i, line in enumerate(lines):
    if "CloudDiscoveryHook.apply(" in line:
        preferred = i
        break

# Fallback:
# BackupRebuilderHook.apply(...)
if preferred is None:
    for i, line in enumerate(lines):
        if "BackupRebuilderHook.apply(" in line:
            preferred = i
            break

if preferred is None:
    print("[FAIL] Could not find Hook registration point in Module.kt")
    print("[INFO] Expected CloudDiscoveryHook.apply(...) or BackupRebuilderHook.apply(...)")
    sys.exit(1)

# Preserve the indentation of the existing hook line.
indent = lines[preferred][:len(lines[preferred]) - len(lines[preferred].lstrip())]

lines.insert(
    preferred + 1,
    indent + marker
)

path.write_text(
    "\n".join(lines) + ("\n" if text.endswith("\n") else ""),
    encoding="utf-8"
)

print("[OK] ModHook registered in Module.kt")
PY

# ------------------------------------------------------------
# Final verification
# ------------------------------------------------------------

if ! grep -Fq 'ModHook.apply(this, ctx, cl, targets, prefs)' "$MODULE_FILE"; then
    echo "[FAIL] ModHook registration verification failed"
    exit 1
fi

if ! grep -Fq 'object ModHook : HookHandler' "$HOOK_DIR/ModHook.kt"; then
    echo "[FAIL] ModHook.kt verification failed"
    exit 1
fi

echo "[OK] ModHook registration verified"
echo "[OK] mod injection complete"