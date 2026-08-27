#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

fail=0
check() {
    local pattern="$1"
    local description="$2"
    if grep -RInE --include='*.java' --exclude-dir=target --exclude-dir=.git "$pattern" src; then
        echo "ENFORCEMENT FAILED: $description" >&2
        fail=1
    fi
}

check 'catch[[:space:]]*\([[:space:]]*(Exception|Throwable)([[:space:]]|\))' 'Do not catch Exception or Throwable.'
check 'catch[[:space:]]*\([^)]*\)[[:space:]]*\{[[:space:]]*\}' 'Do not silently swallow exceptions.'
check 'catch[[:space:]]*\([^)]*\)[[:space:]]*\{[[:space:]]*[^}]*([Ii]gnored|printStackTrace)' 'Do not ignore or print exceptions directly.'
check '\.printStackTrace[[:space:]]*\(' 'Use structured logging or propagate exceptions instead of printStackTrace().' 
check 'throw[[:space:]]+new[[:space:]]+RuntimeException[[:space:]]*\(' 'Do not use RuntimeException as a generic error bucket.'
check 'assert[[:space:]]+[^=]' 'Do not rely on assertions for runtime validation.'
check '//[[:space:]]*(Increment|Set|Get|Call|Return|Create|Update|Delete|Handle|Check|Fix|Apply)[[:space:]]' 'Comments must explain why, not narrate obvious code.'
check 'TODO([(:]|$)' 'Delivered code must not contain unresolved TODOs.'
check 'TODO[[:space:]]+\b' 'Delivered code must not contain unresolved TODOs.'

tracked="$(git ls-files)"
for path in target .idea .classpath .project; do
    if grep -qxF "$path" <<<"$tracked" || grep -qE "^${path}/" <<<"$tracked"; then
        echo "ENFORCEMENT FAILED: generated or IDE artifact is tracked: $path" >&2
        fail=1
    fi
done

while IFS= read -r file; do
    if grep -qE '(^|/)(.*\.env|.*\.pem|.*\.key|credentials\.json)$' <<<"$file"; then
        echo "ENFORCEMENT FAILED: possible secret/credential file is tracked: $file" >&2
        fail=1
    fi
done <<< "$tracked"

if [[ "$fail" -ne 0 ]]; then
    exit 1
fi

echo "Work enforcements passed."
