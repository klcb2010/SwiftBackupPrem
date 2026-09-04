#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/mod"

APP_SRC="$ROOT/app/src/main/java/io/github/s1ddhants1/swiftbackupprem"
HOOK_DIR="$APP_SRC/hook"
MODULE_FILE="$ROOT/app/src/main/java/io/github/s1ddhants1/swiftbackupprem/Module.kt"

echo "[mod] root=$ROOT"
echo "[mod] mod=$MOD"

# ------------------------------------------------------------
# Required files
# ------------------------------------------------------------

test -f "$MOD/ModHook.kt" || {
    echo "[FAIL] $MOD/ModHook.kt not found"
    exit 1
}

test -f "$MODULE_FILE" || {
    echo "[FAIL] $MODULE_FILE not found"
    exit 1
}

mkdir -p "$HOOK_DIR"

# ------------------------------------------------------------
# Inject ModHook
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

if marker in text:
    print("[OK] ModHook already registered")
    sys.exit(0)

lines = text.splitlines()

insert_at = None

# Prefer the end of the normal HookHandler sequence.
for i, line in enumerate(lines):
    if "CloudDiscoveryHook.apply(" in line:
        insert_at = i + 1
        break

# Fallback.
if insert_at is None:
    for i, line in enumerate(lines):
        if "BackupRebuilderHook.apply(" in line:
            insert_at = i + 1
            break

if insert_at is None:
    print("[FAIL] Hook registration point not found")
    sys.exit(1)

reference_line = lines[insert_at - 1]
indent = reference_line[:len(reference_line) - len(reference_line.lstrip())]

lines.insert(
    insert_at,
    indent + marker
)

path.write_text(
    "\n".join(lines) + "\n",
    encoding="utf-8"
)

print("[OK] ModHook registered in Module.kt")
PY

# ------------------------------------------------------------
# Verify
# ------------------------------------------------------------

grep -Fq \
    'ModHook.apply(this, ctx, cl, targets, prefs)' \
    "$MODULE_FILE" || {
        echo "[FAIL] ModHook registration verification failed"
        exit 1
    }

grep -Fq \
    'object ModHook : HookHandler' \
    "$HOOK_DIR/ModHook.kt" || {
        echo "[FAIL] ModHook implementation verification failed"
        exit 1
    }

echo "[OK] ModHook registration verified"
echo "[OK] mod injection complete"