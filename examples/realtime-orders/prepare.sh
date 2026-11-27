#!/usr/bin/env bash
set -euo pipefail
cache=${DK_CDC_CACHE:-"${TMPDIR:-/tmp}/data-khaos-cdc-3.6.0"}
mkdir -p "$cache"
cache=$(cd "$cache" && pwd)
mkdir -p "$cache/sql"
maven=https://repo.maven.apache.org/maven2
cdn=https://dlcdn.apache.org/flink/flink-cdc-3.6.0
archive_site=https://archive.apache.org/dist/flink/flink-cdc-3.6.0
archive=flink-cdc-3.6.0-1.20-bin.tar.gz

download() {
  local url=$1 dest=$2 bits=$3 expected actual tmp
  if [[ -s "$dest" ]]; then return; fi
  tmp=$(mktemp "$dest.XXXXXX")
  if ! curl -fsSL --max-time 300 --retry 2 "$url" -o "$tmp" ||
     ! expected=$(curl -fsSL --max-time 45 --retry 2 "$url.sha$bits" | awk 'NR==1 {for(i=1;i<=NF;i++) if ($i ~ /^[a-fA-F0-9]+$/ && length($i) == n) {print tolower($i); exit}}' n=$((bits == 1 ? 40 : bits / 4))); then
    rm -f "$tmp"
    return 1
  fi
  actual=$(shasum -a "$bits" "$tmp" | awk '{print $1}')
  if [[ "$actual" != "$expected" ]]; then
    rm -f "$tmp"
    printf 'Checksum mismatch: %s\n' "$url" >&2
    return 1
  fi
  chmod 0644 "$tmp"
  mv -f "$tmp" "$dest"
}

if [[ ! -r "$cache/cdc/bin/flink-cdc.sh" ]]; then
  download "$cdn/$archive" "$cache/$archive" 512 || download "$archive_site/$archive" "$cache/$archive" 512
  staging=$(mktemp -d "$cache/cdc.XXXXXX")
  tar -xzf "$cache/$archive" -C "$staging" --strip-components=1
  chmod 0755 "$staging"
  mv "$staging" "$cache/cdc"
fi
for connector in mysql kafka; do
  artifact=flink-cdc-pipeline-connector-$connector
  file=$artifact-3.6.0-1.20.jar
  download "$maven/org/apache/flink/$artifact/3.6.0-1.20/$file" "$cache/cdc/lib/$file" 1
done
download "$maven/org/apache/flink/flink-sql-connector-kafka/3.4.0-1.20/flink-sql-connector-kafka-3.4.0-1.20.jar" "$cache/sql/kafka.jar" 1
download "$maven/org/apache/flink/flink-connector-jdbc/3.3.0-1.20/flink-connector-jdbc-3.3.0-1.20.jar" "$cache/sql/jdbc.jar" 1
download "$maven/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar" "$cache/sql/mysql.jar" 1
# Flink CDC discovers its CLI dependencies by scanning cdc/lib for regular JAR files.
cdc_mysql="$cache/cdc/lib/mysql-connector-j-8.3.0.jar"
if ! cmp -s "$cache/sql/mysql.jar" "$cdc_mysql"; then
  pending=$(mktemp "$cdc_mysql.XXXXXX")
  install -m 0644 "$cache/sql/mysql.jar" "$pending"
  mv -f "$pending" "$cdc_mysql"
fi
printf '%s\n' "$cache"
