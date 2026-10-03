#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Add Mattermost-style copyright headers to source files that lack them.
#
# Usage:
#   ./scripts/add-license-headers.sh              # all project sources
#   ./scripts/add-license-headers.sh path...      # only listed files
#   ./scripts/add-license-headers.sh --check      # fail if any missing
#   ./scripts/add-license-headers.sh --check path...
#
# New files: prefer git hook (.githooks/pre-commit) so headers are applied
# automatically on commit.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

CHECK=0
ARGS=()
for a in "$@"; do
  if [[ "$a" == "--check" ]]; then
    CHECK=1
  else
    ARGS+=("$a")
  fi
done

HEADER_LINE1='Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.'
HEADER_LINE2='See LICENSE for license information.'

has_header() {
  head -n 8 "$1" 2>/dev/null | grep -q 'Copyright (c) 2026-present Glagolitsa contributors' 2>/dev/null
}

write_header() {
  local style="$1"
  local out="$2"
  case "$style" in
    slash)
      {
        echo "// ${HEADER_LINE1}"
        echo "// ${HEADER_LINE2}"
        echo ""
      } >"$out"
      ;;
    hash)
      {
        echo "# ${HEADER_LINE1}"
        echo "# ${HEADER_LINE2}"
        echo ""
      } >"$out"
      ;;
    sql)
      {
        echo "-- ${HEADER_LINE1}"
        echo "-- ${HEADER_LINE2}"
        echo ""
      } >"$out"
      ;;
  esac
}

style_for() {
  case "$1" in
    *.kt|*.kts|*.go) echo slash ;;
    *.sh) echo hash ;;
    *.sq|*.sqm|*.sql) echo sql ;;
    *) echo slash ;;
  esac
}

is_source() {
  case "$1" in
    *.kt|*.kts|*.go|*.sh|*.sq|*.sqm|*.sql) return 0 ;;
    *) return 1 ;;
  esac
}

updated=0
skipped=0
missing=0

process_file() {
  local f="$1"
  [[ -f "$f" && -s "$f" ]] || return 0
  is_source "$f" || return 0

  # Skip build outputs if passed explicitly
  case "$f" in
    */build/*|*/.gradle/*|*/bin/*) return 0 ;;
  esac

  if has_header "$f"; then
    skipped=$((skipped + 1))
    return 0
  fi

  local style
  style="$(style_for "$f")"

  if [[ "$CHECK" -eq 1 ]]; then
    echo "MISSING header: $f"
    missing=$((missing + 1))
    return 0
  fi

  local tmp mode
  tmp="$(mktemp)"
  # Preserve executable bit (mktemp+mv would otherwise drop +x on scripts).
  mode="$(stat -f '%Lp' "$f" 2>/dev/null || stat -c '%a' "$f" 2>/dev/null || echo 644)"

  if [[ "$f" == *.sh ]] && head -n 1 "$f" | grep -q '^#!'; then
    head -n 1 "$f" >"$tmp"
    echo "" >>"$tmp"
    write_header hash "${tmp}.hdr"
    cat "${tmp}.hdr" >>"$tmp"
    rm -f "${tmp}.hdr"
    tail -n +2 "$f" >>"$tmp"
    mv "$tmp" "$f"
    chmod "$mode" "$f" 2>/dev/null || true
    echo "OK  $f (shebang preserved)"
  else
    write_header "$style" "$tmp"
    cat "$f" >>"$tmp"
    mv "$tmp" "$f"
    chmod "$mode" "$f" 2>/dev/null || true
    echo "OK  $f"
  fi
  updated=$((updated + 1))
}

FILE_LIST="$(mktemp)"
if [[ ${#ARGS[@]} -gt 0 ]]; then
  for f in "${ARGS[@]}"; do
    echo "$f" >>"$FILE_LIST"
  done
else
  find \
    shared/src \
    androidApp/src \
    desktopApp/src \
    server \
    scripts \
    -type f \( \
      -name '*.kt' -o \
      -name '*.go' -o \
      -name '*.kts' -o \
      -name '*.sq' -o \
      -name '*.sqm' -o \
      -name '*.sql' -o \
      -name '*.sh' \
    \) \
    ! -path '*/build/*' \
    ! -path '*/.gradle/*' \
    ! -path '*/bin/*' \
    2>/dev/null | sort >"$FILE_LIST"

  for extra in build.gradle.kts settings.gradle.kts; do
    [[ -f "$extra" ]] && echo "$extra" >>"$FILE_LIST"
  done
fi

while IFS= read -r f; do
  [[ -n "$f" ]] || continue
  process_file "$f"
done <"$FILE_LIST"
rm -f "$FILE_LIST"

echo ""
echo "updated=$updated skipped=$skipped missing=$missing check=$CHECK"

if [[ "$CHECK" -eq 1 && "$missing" -gt 0 ]]; then
  exit 1
fi
