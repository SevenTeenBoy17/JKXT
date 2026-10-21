#!/bin/sh
# Complete a plain SQL dump before making it visible to the application scanner.
set -eu
umask 077
prefix=${1:-jkxt-private}
stamp=${2:-}
if [ -n "$stamp" ]; then
  printf '%s' "$stamp" | grep -Eq '^[0-9]{8}-[0-9]{6}$' || { echo 'Invalid backup stamp' >&2; exit 2; }
else
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
fi
case "$prefix" in jkxt-private|jkxt-public|jkxt-test) ;; *) echo 'Invalid backup prefix' >&2; exit 2 ;; esac
: "${POSTGRES_DB:?database required}"
: "${POSTGRES_USER:?database user required}"
: "${PGPASSWORD:?database password required}"
test -d /backups
temporary=$(mktemp "/backups/.$prefix.XXXXXX")
checksum_temporary="$temporary.sha256"
trap 'rm -f "$temporary" "$checksum_temporary"' EXIT HUP INT TERM
name="$prefix-$stamp-${temporary##*.}.sql"
pg_dump -h "${PGHOST:-postgres}" -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --no-acl -f "$temporary"
test -s "$temporary"
digest=$(sha256sum "$temporary")
digest=${digest%% *}
printf '%s  %s\n' "$digest" "$name" > "$checksum_temporary"
# The scanner must require a matching checksum. Publishing it first is harmless until the dump exists.
mv "$checksum_temporary" "/backups/$name.sha256"
mv "$temporary" "/backups/$name"
printf 'BACKUP=%s SHA256=%s\n' "$name" "$digest"
