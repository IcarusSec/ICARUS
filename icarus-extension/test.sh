#!/bin/bash
# Runs every test class (any *Test.java with a main method) and the AutoAuth self-check,
# with assertions enabled. Build first with ./build.sh. UI tests need a display: on a
# headless machine run `xvfb-run -a ./test.sh`.
set -uo pipefail
cd "$(dirname "$0")"

if [ ! -d build_manual/classes ] || [ ! -d build_manual/test-classes ]; then
    echo "[!] Run ./build.sh first."
    exit 2
fi

CP="build_manual/test-classes:build_manual/classes:$(ls libs/*.jar | tr '\n' ':')"
JAVA="${JAVA:-java}"
pass=0
fail=0
failed=()

run() {
    local name="$1"; shift
    local log
    log=$(mktemp)
    if timeout 300 "$JAVA" -ea "$@" >"$log" 2>&1; then
        pass=$((pass + 1))
        echo "  PASS  $name"
    else
        fail=$((fail + 1))
        failed+=("$name")
        echo "  FAIL  $name"
        sed 's/^/        /' "$log" | tail -25
    fi
    rm -f "$log"
}

echo "[*] Running tests..."
while read -r file; do
    grep -q "static void main" "$file" || continue
    class=$(echo "${file#src/test/java/}" | sed 's/\.java$//; s#/#.#g')
    run "$class" -cp "$CP" "$class"
done < <(find src/test/java -name "*Test.java" | sort)

SELF=$(mktemp -d)
if javac -cp "$CP" -d "$SELF" selfcheck/AutoAuthSelfCheck.java; then
    run "AutoAuthSelfCheck" -cp "$SELF:$CP" AutoAuthSelfCheck
else
    fail=$((fail + 1)); failed+=("AutoAuthSelfCheck (compile)")
fi
rm -rf "$SELF"

echo
echo "[*] $pass passed, $fail failed"
if [ "$fail" -gt 0 ]; then
    printf '    %s\n' "${failed[@]}"
    exit 1
fi
