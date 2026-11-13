#!/usr/bin/env bash
# Baut F-Droid (gepatcht) + Privileged Extension + Magisk-Modul auf Ubuntu (getestet auf dem Papier für 26.04).
#
# Benutzung:   ./build-ubuntu.sh            (alles: Abhängigkeiten, SDK, Schlüssel, Build, Magisk-ZIP)
#              ./build-ubuntu.sh --clean    (vorher "gradlew clean")
#              SKIP_APT=1 ./build-ubuntu.sh (apt-Schritt überspringen, wenn schon alles installiert ist)
# Ergebnis:    ./dist/  (F-Droid APK, Extension APK, Magisk-ZIP)
set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")"
PROJECT_DIR="$PWD"

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
# Version der Android Command-line-Tools. Bei Download-Fehler wird eine ältere probiert.
CMDLINE_TOOLS_VERSIONS=("${CMDLINE_TOOLS_VERSION:-13114758}" "11076708")
JDK_PACKAGE="${JDK_PACKAGE:-openjdk-21-jdk-headless}"

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
die()  { printf '\n\033[1;31mFehler: %s\033[0m\n' "$*" >&2; exit 1; }

DO_CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --clean) DO_CLEAN=1 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) die "Unbekannte Option: $arg" ;;
  esac
done

[ "$(id -u)" -ne 0 ] || die "Bitte NICHT als root starten (das SDK landet sonst in /root). Das Skript nutzt sudo selbst, wo nötig."

# ---------------------------------------------------------------- 1. Pakete
if [ "${SKIP_APT:-0}" != "1" ]; then
  log "Installiere Abhängigkeiten (apt, sudo-Passwort wird evtl. abgefragt)"
  sudo apt-get update
  sudo apt-get install -y "$JDK_PACKAGE" curl unzip python3 ca-certificates
fi

# ---------------------------------------------------------------- 2. Java
if [ -z "${JAVA_HOME:-}" ]; then
  JAVAC_PATH="$(readlink -f "$(command -v javac || true)" 2>/dev/null || true)"
  [ -n "$JAVAC_PATH" ] || die "javac nicht gefunden. Installiere ein JDK (apt install $JDK_PACKAGE)."
  JAVA_HOME="$(dirname "$(dirname "$JAVAC_PATH")")"
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
JAVA_MAJOR="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
[ "${JAVA_MAJOR:-0}" -ge 17 ] || die "JDK 17 oder neuer nötig (gefunden: ${JAVA_MAJOR:-unbekannt})."
log "Java $JAVA_MAJOR ($JAVA_HOME)"

# ---------------------------------------------------------------- 3. Android SDK
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_SDK_ROOT
SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  log "Lade Android Command-line-Tools"
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
  TMP_ZIP="$(mktemp --suffix=.zip)"
  ok=0
  for v in "${CMDLINE_TOOLS_VERSIONS[@]}"; do
    url="https://dl.google.com/android/repository/commandlinetools-linux-${v}_latest.zip"
    echo "Versuche $url"
    if curl -fL --retry 3 -o "$TMP_ZIP" "$url"; then ok=1; break; fi
  done
  [ "$ok" = 1 ] || die "Download der Command-line-Tools fehlgeschlagen. Setze CMDLINE_TOOLS_VERSION=<Nummer> (siehe https://developer.android.com/studio#command-line-tools-only)."
  rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest" "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools"
  unzip -q "$TMP_ZIP" -d "$ANDROID_SDK_ROOT/cmdline-tools"
  mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  rm -f "$TMP_ZIP"
fi

log "Akzeptiere SDK-Lizenzen und installiere Plattform-Tools"
yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" "platform-tools"
# compileSdk 37: je nach SDK-Stand heißt das Paket android-37 oder android-37.0.
# Falls beides fehlschlägt, lädt Gradle (AGP) die Plattform bei akzeptierten Lizenzen selbst nach.
"$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" "platforms;android-37" 2>/dev/null \
  || "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" "platforms;android-37.0" 2>/dev/null \
  || echo "Hinweis: Plattform 37 nicht per sdkmanager gefunden, Gradle lädt sie beim Build nach."

printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > local.properties

# ---------------------------------------------------------------- 4. Signierschlüssel
if [ ! -f keystore.properties ]; then
  log "Erzeuge eigenen Signierschlüssel (einmalig)"
  if [ -f my-fdroid.jks ]; then
    die "my-fdroid.jks existiert, aber keystore.properties fehlt. Lege keystore.properties nach keystore.properties.example an."
  fi
  PASS="$(python3 -c 'import secrets;print(secrets.token_urlsafe(24))')"
  keytool -genkeypair -keystore my-fdroid.jks -alias fdroid \
    -keyalg RSA -keysize 4096 -validity 36500 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=Self-built F-Droid, O=Private, C=DE"
  umask 077
  cat > keystore.properties <<PROPS
storeFile=my-fdroid.jks
storePassword=$PASS
keyAlias=fdroid
keyPassword=$PASS
PROPS
  chmod 600 keystore.properties my-fdroid.jks
  echo
  echo "WICHTIG: Sichere my-fdroid.jks und keystore.properties! Ohne diesen Schlüssel kannst du F-Droid"
  echo "später nicht mehr updaten, ohne es vorher zu deinstallieren."
else
  log "Verwende vorhandenen Schlüssel (keystore.properties)"
fi

# ---------------------------------------------------------------- 5. Build
chmod +x ./gradlew
GRADLE_ARGS=(--no-daemon --stacktrace)
if [ "$DO_CLEAN" = 1 ]; then
  log "gradlew clean"
  ./gradlew "${GRADLE_ARGS[@]}" clean
fi

log "Baue Privileged Extension und F-Droid (full, release). Das dauert beim ersten Mal einige Minuten."
./gradlew "${GRADLE_ARGS[@]}" :privileged-extension:assembleRelease :app:assembleFullDefaultRelease

# ---------------------------------------------------------------- 6. Ergebnisse einsammeln
log "Sammle Ergebnisse in ./dist"
rm -rf dist && mkdir -p dist

FDROID_APK="$(find app/build/outputs/apk -path '*ull*' -path '*elease*' -name '*.apk' | sort | head -n1 || true)"
EXT_APK="$(find privileged-extension/build/outputs/apk/release -name '*.apk' | sort | head -n1 || true)"
[ -n "$FDROID_APK" ] || die "F-Droid APK nicht gefunden unter app/build/outputs/apk."
[ -n "$EXT_APK" ] || die "Extension APK nicht gefunden unter privileged-extension/build/outputs/apk/release."

cp "$FDROID_APK" dist/F-Droid-own-signed.apk
cp "$EXT_APK" dist/F-Droid-Privileged-Extension.apk
python3 tools/make_magisk_module.py "$EXT_APK" "$PROJECT_DIR/dist/fdroid-privileged-extension-own.zip"

# Signatur kurz prüfen, falls apksigner vorhanden ist
APKSIGNER="$(find "$ANDROID_SDK_ROOT/build-tools" -name apksigner 2>/dev/null | sort -V | tail -n1 || true)"
if [ -n "$APKSIGNER" ]; then
  echo "--- Signatur F-Droid:"
  "$APKSIGNER" verify --print-certs dist/F-Droid-own-signed.apk | head -n3 || true
fi

cat <<DONE

Fertig. Dateien in $PROJECT_DIR/dist:
  F-Droid-own-signed.apk                  -> aufs Handy installieren (offizielles F-Droid vorher deinstallieren)
  fdroid-privileged-extension-own.zip     -> in Magisk flashen, dann neu starten
  F-Droid-Privileged-Extension.apk        -> nur zur Info (steckt schon im Magisk-ZIP)

Per USB installieren (USB-Debugging an):
  adb install -r dist/F-Droid-own-signed.apk
DONE
