#!/usr/bin/env bash
# Builds the home page and docs (client/site) and the console (client/web) into one folder for a static host:
# the site at "/", the console under "/console". VITE_API_ORIGIN, when set, is where the console calls the API
# (for example https://api-hgb.l2cl.link); left empty, the console calls this site's own /api.
set -euo pipefail
cd "$(dirname "$0")/.."
out="${OUT_DIR:-dist-sites}"

pnpm --dir client/web install --frozen-lockfile
pnpm --dir client/site install --frozen-lockfile
VITE_API_ORIGIN="${VITE_API_ORIGIN:-}" pnpm --dir client/web build
pnpm --dir client/site build

rm -rf "$out"
mkdir -p "$out/console"
cp -R client/site/dist/. "$out/"
cp -R client/web/dist/. "$out/console/"
# Source maps stay out of what is served.
find "$out" -name '*.map' -delete
echo "built into $out"
