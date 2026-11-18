#!/usr/bin/env bash
# Chrome DevTools Protocol の定義 JSON を npm の devtools-protocol パッケージから取得して protocol/ に置く。
# 使い方: scripts/update-protocol.sh [version]   (省略時は latest)
set -euo pipefail
cd "$(dirname "$0")/.."
version="${1:-$(npm view devtools-protocol version)}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -fsSL "https://registry.npmjs.org/devtools-protocol/-/devtools-protocol-${version}.tgz" | tar -xz -C "$tmp"
cp "$tmp/package/json/browser_protocol.json" "$tmp/package/json/js_protocol.json" protocol/
echo "$version" > protocol/VERSION
echo "devtools-protocol ${version} -> protocol/"
