#!/usr/bin/env bash
set -euo pipefail
umask 077
backup_dir=/var/backups/suture-caja
install -d -o root -g root -m 700 "$backup_dir"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
tmp=$(mktemp "$backup_dir/.suture-caja-$stamp.XXXXXX")
trap 'rm -f "$tmp"' EXIT
runuser -u postgres -- pg_dump -Fc suture_caja > "$tmp"
pg_restore --list "$tmp" >/dev/null
mv "$tmp" "$backup_dir/suture-caja-$stamp.dump"
find "$backup_dir" -maxdepth 1 -type f -name 'suture-caja-*.dump' -mtime +14 -delete
