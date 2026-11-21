#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/mod"
JAVA_SRC="$ROOT/app/src/main/java"
HOOK_DIR="$JAVA_SRC/io/github/s1ddhants1/swiftbackupprem/hook"

echo "[mod] root=$ROOT"
echo "[mod] mod=$MOD"

# ------------------------------------------------------------
# Required files and source layout
# ------------------------------------------------------------

test -f "$MOD/ModHook.kt" || {
    echo "[FAIL] $MOD/ModHook.kt not found"
    exit 1
}

test -f "$JAVA_SRC/io/github/s1ddhants1/swiftbackupprem/hook/HookHandler.kt" || {
    echo "[FAIL] shared HookHandler.kt not found under $JAVA_SRC"
    exit 1
}

MODULE_FILES=(
    "$ROOT/app/src/normal/java/io/github/s1ddhants1/swiftbackupprem/Module.kt"
    "$ROOT/app/src/legacy/java/io/github/s1ddhants1/swiftbackupprem/Module.kt"
)

for module_file in "${MODULE_FILES[@]}"; do
    if [ ! -f "$module_file" ]; then
        echo "[FAIL] expected flavor entry not found: $module_file"
        exit 1
    fi
done

echo "[mod] shared_hook_dir=$HOOK_DIR"
printf '[mod] flavor_entry=%s\n' "${MODULE_FILES[@]}"

mkdir -p "$HOOK_DIR"

# ------------------------------------------------------------
# Inject shared ModHook implementation
# ------------------------------------------------------------

cp "$MOD/ModHook.kt" "$HOOK_DIR/ModHook.kt"
echo "[OK] copied ModHook.kt into shared source set"

# ------------------------------------------------------------
# Register ModHook in both normal and legacy entry points
# ------------------------------------------------------------

python3 - "${MODULE_FILES[@]}" <<'PY'
from pathlib import Path
import sys

marker = "ModHook.apply(this, ctx, cl, targets, prefs)"
paths = [Path(value) for value in sys.argv[1:]]

if len(paths) != 2:
    raise SystemExit(f"[FAIL] expected exactly 2 flavor entry points, got {len(paths)}")

for path in paths:
    text = path.read_text(encoding="utf-8")

    if marker in text:
        print(f"[OK] ModHook already registered: {path}")
        continue

    lines = text.splitlines()
    insert_at = None

    for i, line in enumerate(lines):
        if "CloudDiscoveryHook.apply(" in line:
            insert_at = i + 1
            break

    if insert_at is None:
        print(f"[FAIL] CloudDiscoveryHook registration point not found in {path}")
        raise SystemExit(1)

    reference_line = lines[insert_at - 1]
    indent = reference_line[:len(reference_line) - len(reference_line.lstrip())]
    lines.insert(insert_at, indent + marker)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"[OK] ModHook registered: {path}")
PY

# ------------------------------------------------------------
# Verify shared implementation and both flavor registrations
# ------------------------------------------------------------

grep -Fq 'object ModHook : HookHandler' "$HOOK_DIR/ModHook.kt" || {
    echo "[FAIL] ModHook implementation verification failed"
    exit 1
}

grep -Fq 'module: HookContext' "$HOOK_DIR/ModHook.kt" || {
    echo "[FAIL] ModHook does not implement the current HookContext API"
    exit 1
}

if grep -Eq 'XposedModule|XposedInterface' "$HOOK_DIR/ModHook.kt"; then
    echo "[FAIL] legacy Xposed API reference found in ModHook.kt"
    exit 1
fi

for module_file in "${MODULE_FILES[@]}"; do
    grep -Fq "$marker" "$module_file" || {
        echo "[FAIL] ModHook registration verification failed: $module_file"
        exit 1
    }
done

echo "[OK] ModHook implementation verified"
echo "[OK] normal and legacy registrations verified"
echo "[OK] mod injection complete"
