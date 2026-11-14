#!/usr/bin/env bash
# Traegt die Pruefsummen (SHA-256) der neuen Bibliotheken in gradle/verification-metadata.xml ein:
#   dev.rikka.shizuku:{api,provider,aidl,shared}, io.github.iamr0s:Dhizuku-API, org.lsposed.hiddenapibypass
# Das macht Gradle selbst (--write-verification-metadata): es laedt die Artefakte und berechnet die Hashes.
# Bereits vorhandene Eintraege bleiben erhalten. Benoetigt Internet, SDK und JDK (wie ein normaler Build).
#
# Benutzung: ./tools/sync-verification-metadata.sh [--force]
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")/.."

META=gradle/verification-metadata.xml
[ -f "$META" ] || { echo "Fehler: $META nicht gefunden" >&2; exit 1; }

already_synced() {
  grep -q 'group="dev.rikka.shizuku"' "$META" \
    && grep -q 'name="Dhizuku-API"' "$META" \
    && grep -q 'group="org.lsposed.hiddenapibypass"' "$META"
}

if [ "${1:-}" != "--force" ] && already_synced; then
  echo "verification-metadata.xml enthaelt die neuen Bibliotheken bereits. (--force erzwingt eine erneute Synchronisierung)"
  exit 0
fi

cp "$META" "$META.bak"
echo "==> Berechne Pruefsummen mit Gradle (das ist gleichzeitig ein kompletter Build, dauert einige Minuten)"
chmod +x ./gradlew
./gradlew --no-daemon --write-verification-metadata sha256 \
  :app:assembleFullDefaultRelease :privileged-extension:assembleRelease

if already_synced; then
  echo "==> Fertig. Neue Eintraege (Vergleich mit $META.bak):"
  diff <(grep -o '<component [^>]*>' "$META.bak" | sort -u) \
       <(grep -o '<component [^>]*>' "$META" | sort -u) | grep '^>' || true
  echo "Bitte kurz mit 'diff $META.bak $META' pruefen. Die Sicherung kann danach geloescht werden."
else
  echo "Warnung: Die neuen Bibliotheken wurden nicht eingetragen. Pruefe die Gradle-Ausgabe oben." >&2
  exit 1
fi
