#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Shared uiautomator helpers. Callers must set SERIAL before sourcing.

dump_xml() {
  adb -s "$SERIAL" shell uiautomator dump /sdcard/glag_e2e.xml >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell cat /sdcard/glag_e2e.xml 2>/dev/null || true
}

tap_bounds() {
  local line="$1"
  local b x1 y1 x2 y2 x y
  b="$(echo "$line" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')"
  [[ -n "$b" ]] || return 1
  read -r x1 y1 x2 y2 <<< "$b"
  x=$(((x1 + x2) / 2))
  y=$(((y1 + y2) / 2))
  adb -s "$SERIAL" shell input tap "$x" "$y"
}

find_line() {
  local pattern="$1"
  local xml="$2"
  echo "$xml" | grep -Eo "<node [^>]*${pattern}[^>]*>" | head -1 || true
}

tap_resource_id() {
  local rid="$1"
  local xml line
  xml="$(dump_xml)"
  line="$(find_line "resource-id=\"${rid}\"" "$xml")"
  [[ -n "$line" ]] || return 1
  tap_bounds "$line"
}

tap_text() {
  local text="$1"
  local xml line
  xml="$(dump_xml)"
  line="$(find_line "text=\"${text}\"" "$xml")"
  [[ -n "$line" ]] || return 1
  tap_bounds "$line"
}

tap_content_desc() {
  local desc="$1"
  local xml line
  xml="$(dump_xml)"
  line="$(find_line "content-desc=\"${desc}\"" "$xml")"
  [[ -n "$line" ]] || return 1
  tap_bounds "$line"
}
