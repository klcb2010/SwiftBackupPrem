#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/mod"
JAVA_SRC="$ROOT/app/src/main/java"

echo "[mod] root=$ROOT"
echo "[mod] mod=$MOD"

# ------------------------------------------------------------
# Required files
# ------------------------------------------------------------

test -f "$MOD/ModHook.kt" || {
    echo "[FAIL] $MOD/ModHook.kt not found"
    exit 1
}

# Do not hardcode the Kotlin package path. The namespace/package may
# change while the module entry point remains named Module.kt.
mapfile -t MODULE_FILES < <(
    find "$JAVA_SRC" -type f -name 'Module.kt' -print 2>/dev/null | sort
)

if [ "${#MODULE_FILES[@]}" -eq 0 ]; then
    echo "[FAIL] Module.kt not found under $JAVA_SRC"
    echo "[FAIL] Available Kotlin source files:"
    find "$JAVA_SRC" -type f -name '*.kt' -print 2>/dev/null | head -80 || true
    exit 1
fi

if [ "${#MODULE_FILES[@]}" -gt 1 ]; then
    echo "[FAIL] Multiple Module.kt files found; refusing ambiguous injection:"
    printf '  %s\n' "${MODULE_FILES[@]}"
    exit 1
fi

MODULE_FILE="${MODULE_FILES[0]}"
APP_SRC="$(dirname "$MODULE_FILE")"
HOOK_DIR="$APP_SRC/hook"

echo "[mod] module=$MODULE_FILE"
echo "[mod] hook_dir=$HOOK_DIR"

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

# Fallback for source layouts without CloudDiscoveryHook.
if insert_at is None:
    for i, line in enumerate(lines):
        if "BackupRebuilderHook.apply(" in line:
            insert_at = i + 1
            break

if insert_at is None:
    print("[FAIL] Hook registration point not found in", path)
    sys.exit(1)

reference_line = lines[insert_at - 1]
indent = reference_line[:len(reference_line) - len(reference_line.lstrip())]
lines.insert(insert_at, indent + marker)

path.write_text("\n".join(lines) + "\n", encoding="utf-8")
print("[OK] ModHook registered in Module.kt")
PY

# ------------------------------------------------------------
# Verify
# ------------------------------------------------------------

grep -Fq 'ModHook.apply(this, ctx, cl, targets, prefs)' "$MODULE_FILE" || {
    echo "[FAIL] ModHook registration verification failed"
    exit 1
}

grep -Fq 'object ModHook : HookHandler' "$HOOK_DIR/ModHook.kt" || {
    echo "[FAIL] ModHook implementation verification failed"
    exit 1
}

echo "[OK] ModHook registration verified"
echo "[OK] mod injection complete"
