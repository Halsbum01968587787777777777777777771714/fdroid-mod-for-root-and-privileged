#!/usr/bin/env bash
# Baut F-Droid (gepatcht) + Privileged Extension + Magisk-Modul auf Ubuntu.
#
# Benutzung:   ./build-ubuntu.sh               alles: Abhängigkeiten, SDK, Schlüssel, Build, Magisk-ZIP
#              ./build-ubuntu.sh --clean       vorher "gradlew clean"
#              ./build-ubuntu.sh --no-sync     Verification-Metadaten nicht synchronisieren
#              FORCE_APT=1 ./build-ubuntu.sh   apt-Schritt erzwingen (sonst nur wenn etwas fehlt)
#              SKIP_NET_CHECK=1 ...            Internet-Vorabprüfung überspringen (z. B. hinter Proxy)
# Ergebnis:    ./dist/  (F-Droid APK, Extension APK, Magisk-ZIP)
# Log:         ./build-ubuntu.log  (bei Fehlern bitte diese Datei schicken)
set -Eeuo pipefail

cd "$(dirname "$(readlink -f "$0")")"
PROJECT_DIR="$PWD"
LOG_FILE="$PROJECT_DIR/build-ubuntu.log"
: > "$LOG_FILE"
exec > >(tee -a "$LOG_FILE") 2>&1

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
# Version der Android Command-line-Tools. Bei Download-Fehler wird eine ältere probiert.
CMDLINE_TOOLS_VERSIONS=("${CMDLINE_TOOLS_VERSION:-13114758}" "11076708")
JDK_PACKAGE="${JDK_PACKAGE:-openjdk-21-jdk-headless}"

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33mHinweis: %s\033[0m\n' "$*" >&2; }
die()  { printf '\n\033[1;31mFehler: %s\033[0m\n' "$*" >&2; exit 1; }
trap 'rc=$?; printf "\n\033[1;31mAbbruch in Zeile %s (Exit-Code %s). Details in %s\033[0m\n" "$LINENO" "$rc" "$LOG_FILE" >&2' ERR

retry() { # retry <versuche> <befehl...>
  local max=$1 i=1; shift
  until "$@"; do
    [ "$i" -lt "$max" ] || return 1
    warn "Versuch $i/$max fehlgeschlagen, neuer Versuch in 10 s ..."
    i=$((i + 1)); sleep 10
  done
}

DO_CLEAN=0
DO_SYNC=1
for arg in "$@"; do
  case "$arg" in
    --clean) DO_CLEAN=1 ;;
    --no-sync) DO_SYNC=0 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) die "Unbekannte Option: $arg" ;;
  esac
done
[ "${SKIP_SYNC:-0}" != "1" ] || DO_SYNC=0

[ "$(id -u)" -ne 0 ] || die "Bitte NICHT als root starten (das SDK landet sonst in /root). Das Skript nutzt sudo selbst, wo nötig."

# ---------------------------------------------------------------- 0. Vorabprüfungen
log "Prüfe System"
FREE_GB="$(df -Pk "$PROJECT_DIR" | awk 'NR==2 {print int($4/1048576)}')"
MEM_GB="$(awk '/MemTotal/ {print int($2/1048576)}' /proc/meminfo)"
echo "Freier Speicher: ${FREE_GB} GB, RAM: ${MEM_GB} GB"
[ "${FREE_GB:-0}" -ge 10 ] || die "Zu wenig freier Speicherplatz (${FREE_GB} GB). Es werden mindestens ca. 10 GB gebraucht."
[ "${MEM_GB:-0}" -ge 6 ] || warn "Nur ${MEM_GB} GB RAM. Der Build kann scheitern oder sehr langsam sein. Empfohlen: mindestens 8 GB."

# Heap für Gradle: 60 % des RAMs, zwischen 3 und 8 GB
XMX=$(( MEM_GB * 6 / 10 )); [ "$XMX" -le 8 ] || XMX=8; [ "$XMX" -ge 3 ] || XMX=3
export GRADLE_JVMARGS="-Xmx${XMX}g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"

# ---------------------------------------------------------------- 1. Pakete (nur wenn etwas fehlt)
need_apt=0
for c in javac keytool curl unzip python3; do command -v "$c" >/dev/null 2>&1 || need_apt=1; done
if command -v java >/dev/null 2>&1; then
  [ "$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')" -ge 17 ] 2>/dev/null || need_apt=1
fi
if [ "$need_apt" = 1 ] || [ "${FORCE_APT:-0}" = 1 ]; then
  log "Installiere Abhängigkeiten (apt, sudo-Passwort wird evtl. abgefragt)"
  sudo apt-get update
  sudo apt-get install -y "$JDK_PACKAGE" curl unzip python3 ca-certificates
else
  log "Benötigte Programme sind schon installiert, überspringe apt"
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
command -v keytool >/dev/null || die "keytool fehlt (gehört zum JDK)."
log "Java $JAVA_MAJOR ($JAVA_HOME)"

# ---------------------------------------------------------------- 2b. Internet
if [ "${SKIP_NET_CHECK:-0}" != "1" ]; then
  log "Prüfe Internetverbindung"
  for host in dl.google.com repo.maven.apache.org services.gradle.org; do
    curl -fsSI --max-time 20 "https://$host" >/dev/null 2>&1 \
      || die "Keine Verbindung zu $host. Internet/Proxy prüfen (oder SKIP_NET_CHECK=1 setzen, wenn alles schon im Cache liegt)."
  done
fi

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
    if retry 3 curl -fL -o "$TMP_ZIP" "$url" && unzip -tq "$TMP_ZIP" >/dev/null 2>&1; then ok=1; break; fi
  done
  [ "$ok" = 1 ] || die "Download der Command-line-Tools fehlgeschlagen. Setze CMDLINE_TOOLS_VERSION=<Nummer> (siehe https://developer.android.com/studio#command-line-tools-only)."
  rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest" "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools"
  unzip -q "$TMP_ZIP" -d "$ANDROID_SDK_ROOT/cmdline-tools"
  mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  rm -f "$TMP_ZIP"
fi
[ -x "$SDKMANAGER" ] || die "sdkmanager nicht gefunden unter $SDKMANAGER"

log "Akzeptiere SDK-Lizenzen und installiere Plattform-Tools"
yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null || true
retry 3 "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" "platform-tools" \
  || die "Installation von platform-tools fehlgeschlagen."
# compileSdk 37: je nach SDK-Stand heißt das Paket android-37 oder android-37.0.
# Falls beides fehlschlägt, lädt Gradle (AGP) die Plattform bei akzeptierten Lizenzen selbst nach.
"$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" "platforms;android-37" 2>/dev/null \
  || "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" "platforms;android-37.0" 2>/dev/null \
  || warn "Plattform 37 nicht per sdkmanager gefunden, Gradle lädt sie beim Build nach."

printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > local.properties

# ---------------------------------------------------------------- 4. Signierschlüssel
get_prop() { grep -E "^$1=" keystore.properties | head -n1 | cut -d= -f2-; }

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
  (umask 077; cat > keystore.properties <<PROPS
storeFile=my-fdroid.jks
storePassword=$PASS
keyAlias=fdroid
keyPassword=$PASS
PROPS
  )
  chmod 600 keystore.properties my-fdroid.jks
  echo
  echo "WICHTIG: Sichere my-fdroid.jks und keystore.properties! Ohne diesen Schlüssel kannst du F-Droid"
  echo "später nicht mehr updaten, ohne es vorher zu deinstallieren."
else
  log "Verwende vorhandenen Schlüssel (keystore.properties)"
fi

KS_FILE="$(get_prop storeFile)"; KS_PASS="$(get_prop storePassword)"; KS_ALIAS="$(get_prop keyAlias)"
[ -f "$KS_FILE" ] || die "Keystore-Datei '$KS_FILE' (aus keystore.properties) nicht gefunden."
MY_CERT_SHA256="$(keytool -list -v -keystore "$KS_FILE" -storepass "$KS_PASS" -alias "$KS_ALIAS" 2>/dev/null \
  | awk '/SHA256:/ {print $2; exit}' | tr -d ':' | tr 'A-F' 'a-f')"
[ -n "$MY_CERT_SHA256" ] || die "Konnte das Zertifikat aus dem Keystore nicht lesen (Passwort/Alias in keystore.properties prüfen)."
echo "Zertifikat SHA-256 deines Schlüssels: $MY_CERT_SHA256"

# ---------------------------------------------------------------- 5. Build
chmod +x ./gradlew
GRADLE_ARGS=(--no-daemon --stacktrace "-Dorg.gradle.jvmargs=$GRADLE_JVMARGS")

if [ "$DO_SYNC" = 1 ]; then
  # Neue Bibliotheken (Shizuku, Dhizuku, HiddenApiBypass) in gradle/verification-metadata.xml eintragen.
  # Macht nichts, wenn sie schon drin stehen.
  log "Synchronisiere gradle/verification-metadata.xml"
  ./tools/sync-verification-metadata.sh
fi

if [ "$DO_CLEAN" = 1 ]; then
  log "gradlew clean"
  ./gradlew "${GRADLE_ARGS[@]}" clean
fi

log "Baue Privileged Extension und F-Droid (full, release). Das dauert beim ersten Mal einige Minuten."
retry 2 ./gradlew "${GRADLE_ARGS[@]}" :privileged-extension:assembleRelease :app:assembleFullDefaultRelease \
  || die "Gradle-Build fehlgeschlagen. Die Fehlermeldung steht weiter oben und in $LOG_FILE."
./gradlew --stop >/dev/null 2>&1 || true

# ---------------------------------------------------------------- 6. Ergebnisse einsammeln
log "Sammle Ergebnisse in ./dist"
pick_apk() { # pick_apk <verzeichnis> <ipath-muster>...
  local dir=$1; shift
  local args=(-type f -name '*.apk' ! -name '*unsigned*')
  for p in "$@"; do args+=(-ipath "$p"); done
  find "$dir" "${args[@]}" 2>/dev/null | sort | head -n1
}
FDROID_APK="$(pick_apk app/build/outputs/apk '*full*' '*release*')"
EXT_APK="$(pick_apk privileged-extension/build/outputs/apk/release '*')"
[ -n "$FDROID_APK" ] || die "Signierte F-Droid-APK nicht gefunden unter app/build/outputs/apk (wurde wirklich mit keystore.properties signiert?)."
[ -n "$EXT_APK" ] || die "Signierte Extension-APK nicht gefunden unter privileged-extension/build/outputs/apk/release."

rm -rf dist && mkdir -p dist
cp "$FDROID_APK" dist/F-Droid-own-signed.apk
cp "$EXT_APK" dist/F-Droid-Privileged-Extension.apk
python3 tools/make_magisk_module.py "$EXT_APK" "$PROJECT_DIR/dist/fdroid-privileged-extension-own.zip"

# Beide APKs müssen mit DEINEM Schlüssel signiert sein. Sonst lehnt die Extension F-Droid ab.
APKSIGNER="$(find "$ANDROID_SDK_ROOT/build-tools" -name apksigner 2>/dev/null | sort -V | tail -n1 || true)"
if [ -n "$APKSIGNER" ]; then
  for apk in dist/F-Droid-own-signed.apk dist/F-Droid-Privileged-Extension.apk; do
    out="$("$APKSIGNER" verify --print-certs "$apk" 2>&1)" || die "Signaturprüfung fehlgeschlagen für $apk: $out"
    got="$(printf '%s\n' "$out" | awk '/certificate SHA-256 digest/ {print $NF; exit}' | tr -d ':' | tr 'A-F' 'a-f')"
    [ "$got" = "$MY_CERT_SHA256" ] \
      || die "$apk ist nicht mit deinem Schlüssel signiert (gefunden: ${got:-keins}, erwartet: $MY_CERT_SHA256)."
    echo "Signatur ok: $apk"
  done
else
  warn "apksigner nicht gefunden, Signatur wurde nicht geprüft."
fi

cat <<DONE

Fertig. Dateien in $PROJECT_DIR/dist:
  F-Droid-own-signed.apk                  -> aufs Handy installieren (offizielles F-Droid vorher deinstallieren)
  fdroid-privileged-extension-own.zip     -> in Magisk flashen, dann neu starten
  F-Droid-Privileged-Extension.apk        -> nur zur Info (steckt schon im Magisk-ZIP)

Per USB installieren (USB-Debugging an):
  adb install -r dist/F-Droid-own-signed.apk
DONE
