#!/usr/bin/env sh
set -eu

repository_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$repository_dir"
exec "$@"
