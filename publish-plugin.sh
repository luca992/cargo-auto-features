#!/bin/bash
# Uploads a new plugin version to JetBrains Marketplace.
# The first version of a plugin must be uploaded manually at
# https://plugins.jetbrains.com/plugin/add; this script handles every version after that.
# Requires MARKETPLACE_TOKEN, a permanent token from
# https://plugins.jetbrains.com/author/me/tokens.
set -euo pipefail
cd "$(dirname "$0")"

: "${MARKETPLACE_TOKEN:?set MARKETPLACE_TOKEN}"

ZIP=$(ls build/plugin/dist/cargo-auto-features-*.zip 2>/dev/null | head -1)
[[ -n "$ZIP" ]] || { echo "error: no plugin zip; run ./build-plugin.sh first" >&2; exit 1; }

curl -fsS -H "Authorization: Bearer $MARKETPLACE_TOKEN" \
    -F "xmlId=io.github.luca992.cargo-auto-features" \
    -F "file=@$ZIP" \
    https://plugins.jetbrains.com/plugin/uploadPlugin
echo
echo "uploaded $ZIP"
