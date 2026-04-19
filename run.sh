#!/usr/bin/env bash
# Activate cactus venv, install saratoga deps, run app.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
CACTUS="$HERE/../cactus"
if [ ! -d "$CACTUS/venv" ]; then
  echo "Cactus venv missing. Run: cd ../cactus && source ./setup"
  exit 1
fi
# shellcheck source=/dev/null
source "$CACTUS/setup"
pip install -q -r "$HERE/requirements.txt"
cd "$HERE" && python app.py
