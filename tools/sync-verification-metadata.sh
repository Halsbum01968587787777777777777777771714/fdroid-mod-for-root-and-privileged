#!/usr/bin/env bash
# Traegt die Pruefsummen (SHA-256) der neuen Bibliotheken in gradle/verification-metadata.xml ein:
#   dev.rikka.shizuku:{api,provider,aidl,shared}, io.github.iamr0s:Dhizuku-API, org.lsposed.hiddenapibypass
# Das macht Gradle selbst (--write-verification-metadata): es laedt die Artefakte und berechnet die Hashes.
# Bereits vorhandene Eintraege bleiben erhalten. Benoetigt Internet, SDK und JDK (wie ein normaler Build).
# Schlaegt etwas fehl, wird die urspruengliche Datei wiederhergestellt.
#
# Benutzung: ./tools/sync-verification-metadata.sh [--force]
set -Eeuo pipefail
cd "$(dirname "$(readlink -f "$0")")/.."

META=gradle/verification-metadata.xml
BACKUP="$META.bak"
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

cp "$META" "$BACKUP"
restore() {
  local rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "Fehler (Exit $rc). Stelle die urspruengliche $META wieder her." >&2
    cp "$BACKUP" "$META"
  fi
}
trap restore EXIT

JVMARGS=()
[ -z "${GRADLE_JVMARGS:-}" ] || JVMARGS=("-Dorg.gradle.jvmargs=$GRADLE_JVMARGS")

echo "==> Berechne Pruefsummen mit Gradle (das ist gleichzeitig ein kompletter Build, dauert einige Minuten)"
chmod +x ./gradlew
./gradlew --no-daemon --stacktrace "${JVMARGS[@]}" --write-verification-metadata sha256 \
  :app:assembleFullDefaultRelease :privileged-extension:assembleRelease

if ! already_synced; then
  echo "Fehler: Die neuen Bibliotheken wurden nicht eingetragen. Pruefe die Gradle-Ausgabe oben." >&2
  exit 1
fi
# Gradle muss die Datei danach auch wirklich als gueltiges XML geschrieben haben
python3 - "$META" <<'PY'
import sys, xml.dom.minidom
xml.dom.minidom.parse(sys.argv[1])
PY

echo "==> Fertig. Neue Eintraege (Vergleich mit $BACKUP):"
diff <(grep -o '<component [^>]*>' "$BACKUP" | sort -u) \
     <(grep -o '<component [^>]*>' "$META" | sort -u) | grep '^>' || true
echo "Bitte kurz mit 'diff $BACKUP $META' pruefen. Die Sicherung kann danach geloescht werden."
