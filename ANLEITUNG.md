# F-Droid (master) mit Installationsmethoden: Standard / Privileged Extension / Root

Stand: gepatcht auf F-Droid `master` (2.0.3). **Nicht kompiliert** (in der Entstehungsumgebung gab es kein Android SDK).
Der Code wurde sorgfältig gegen die vorhandenen Klassen geprüft, aber ein erster Build kann trotzdem eine
Fehlermeldung liefern. Schick sie mir einfach, dann behebe ich sie.

## Was geändert wurde

| Bereich | Datei |
|---|---|
| Einstellung "Installationsmethode" | `settings/SettingsConstants.kt`, `settings/SettingsManager.kt`, `ui/settings/Settings.kt`, `ui/settings/SettingsViewModel.kt`, `res/values*/strings.xml` (EN + DE) |
| Privileged Extension (Backport aus 1.23) | `install/PrivilegedInstaller.kt`, `aidl/.../IPrivilegedService.aidl`, `IPrivilegedCallback.aidl`, `<queries>` im Manifest |
| Root | `install/RootInstaller.kt` |
| Umschaltung beim Installieren | `install/AppInstallManager.kt` |
| Migrations-Hinweis ausgeblendet, wenn Extension gewählt | `utils/Utils.kt` |
| Neues Modul: Extension selbst bauen, vertraut deinem Schlüssel | `privileged-extension/` |
| Signing mit eigenem Schlüssel | `app/build.gradle.kts`, `keystore.properties.example` |
| Magisk-Modul packen | `tools/make_magisk_module.py`, `tools/magisk-module-template/` |

Verhalten: Ist die gewählte Methode nicht nutzbar (Extension fehlt, Root verweigert), fällt F-Droid beim Installieren
auf den Standard-Installer zurück. In den Einstellungen wird die Methode beim Auswählen sofort getestet
(Root: `su -c id` löst den Magisk-Dialog aus; Extension: wird gesucht und per AIDL geprüft). Schlägt der Test fehl,
springt die Einstellung zurück auf "Standard" und ein Hinweis erscheint.

## Warum ein eigenes Extension-Modul nötig ist

Die Extension erlaubt nur den Client `org.fdroid.fdroid` mit dem **offiziellen F-Droid-Zertifikat**. Ein selbst
gebauter F-Droid ist anders signiert und würde abgelehnt. Deshalb ist die Extension (v0.2.13, unverändert bis auf die
Whitelist) als Modul `privileged-extension` im Projekt. Beim Bauen liest sie dein Zertifikat aus
`keystore.properties` und nimmt es in die Whitelist auf. Das offizielle Zertifikat bleibt ebenfalls erlaubt.

## Bauen unter Windows

1. **Android Studio** installieren (bringt JDK mit). Beim ersten Öffnen die **Android SDK Platform 37** installieren lassen.
2. Projektordner entpacken, in Android Studio **Open** wählen und den Sync abwarten.
3. **Eigenen Schlüssel erzeugen** (einmalig), in der Android-Studio-Konsole oder CMD im Projektordner:
   ```
   "C:\Program Files\Android\Android Studio\jbr\bin\keytool" -genkeypair -v -keystore my-fdroid.jks -alias fdroid -keyalg RSA -keysize 4096 -validity 36500
   ```
   Den Schlüssel gut sichern. Ohne ihn gibt es keine Updates.
4. `keystore.properties.example` nach `keystore.properties` kopieren und Passwörter eintragen.
5. Bauen:
   ```
   gradlew.bat :privileged-extension:assembleRelease :app:assembleFullDefaultRelease
   ```
   - F-Droid: `app\build\outputs\apk\fullDefault\release\`
   - Extension: `privileged-extension\build\outputs\apk\release\`
   
   Nimm die **full**-Variante (`org.fdroid.fdroid`). Die Basic-Variante (`org.fdroid.basic`) und Debug-Builds
   (`.debug`) werden von der Extension nicht akzeptiert.
6. Magisk-Modul packen (Python nötig):
   ```
   python tools\make_magisk_module.py
   ```
   Ergebnis: `fdroid-privileged-extension-own.zip`.

## Installieren

1. In Magisk **alte "F-Droid Privileged Extension"-Module deaktivieren/entfernen** (gleiche Paket-ID, würde kollidieren).
2. Das neue ZIP in Magisk (Module → Aus Speicher installieren) flashen und **neu starten**.
3. Offizielles F-Droid **deinstallieren** (gleiche Paket-ID, andere Signatur; deine installierten Apps bleiben) und die selbst
   gebaute, signierte F-Droid-APK installieren.
4. F-Droid → Einstellungen → **Installationsmethode** → "F-Droid Privileged Extension" oder "Root (Magisk)".

## Fehlersuche

- *"Privileged Extension ist nicht installiert"*: Modul nicht aktiv oder kein Neustart.
- *"hat keine Systemrechte oder vertraut der Signatur nicht"*: F-Droid wurde nicht mit demselben Schlüssel signiert
  wie die Extension, es ist die Basic-/Debug-Variante, oder die Rechte-Datei wurde nicht geladen.
- Logs: `adb logcat | findstr /i "PrivilegedService PrivilegedInstaller RootInstaller"`

## Bekannte Grenzen

- **Deinstallieren** läuft weiter über den normalen Systemdialog. Umgebaut wurde nur das Installieren.
- Root-Installation setzt F-Droid als Installer-Paket (`pm install -i`), dadurch klappen spätere Updates sauber.

## Bauen unter Ubuntu (Skript)

```
chmod +x build-ubuntu.sh
./build-ubuntu.sh
```

Das Skript installiert JDK 21 (apt), lädt die Android Command-line-Tools nach `~/Android/Sdk`, akzeptiert die Lizenzen,
erzeugt beim ersten Lauf einen eigenen Schlüssel (`my-fdroid.jks` + `keystore.properties`), baut Extension und F-Droid
und legt alles in `dist/` ab (APKs und das fertige Magisk-ZIP). Optionen: `--clean`, `SKIP_APT=1`.
Empfohlen: mindestens 8 GB RAM und etwa 10 GB freier Speicher.

## Installationsmethoden: Stand jetzt

Standard, Privileged Extension, Root, **Shizuku**, **Dhizuku** und **microG**. Ist die gewählte Methode beim Installieren
nicht nutzbar, fällt F-Droid auf den Standard-Installer zurück.

| Methode | Voraussetzung | Wie es funktioniert |
|---|---|---|
| Shizuku | Shizuku-App läuft (WLAN-ADB oder Root); beim Auswählen fragt Shizuku nach Zugriff für F-Droid | Der System-PackageInstaller wird mit dem Shizuku-Binder (shell/root) aufgerufen, wie im Shizuku-Installer von Aurora Store |
| Dhizuku | Dhizuku als Geräteadministrator eingerichtet; beim Auswählen fragt Dhizuku nach Zugriff | Der PackageInstaller wird mit dem Dhizuku-Binder aufgerufen, also als Device Owner. Als Installer wird das Dhizuku-Paket eingetragen |
| microG | microG Companion (`com.android.vending`, ab Version 84022620, mit `AppInstallActivity`) | Wie Aurora Store 4.8.4: Intent `org.microg.vending.action.INSTALL_PACKAGE` an microG, gestartet über eine unsichtbare Activity |
| Andere Installer-App | Du trägst den Paketnamen ein (Vorgabe: `io.github.muntashirakon.AppManager`); die App muss APK-Dateien per "Öffnen mit" annehmen | F-Droid schickt die APK per `ACTION_VIEW` (APK-Mime-Typ, Content-URI) nur an dieses Paket und prüft danach, ob die App installiert wurde |

**microG:** microG meldet kein Ergebnis zurück. F-Droid prüft deshalb nach dem Schließen von microGs Installer, ob das Paket
wirklich neu installiert wurde. Ob dabei noch ein microG-Dialog erscheint, entscheidet microG selbst. Android kann das Starten
der Activity aus dem Hintergrund blockieren, dann F-Droid geöffnet lassen.

**Shizuku und Dhizuku** greifen per Reflection (mit `org.lsposed.hiddenapibypass`) auf versteckte Android-Schnittstellen zu.
Das ist bewusst ohne Stub-Module gelöst, kann aber bei einer neuen Android-Version brechen.

**Abhängigkeitsprüfung (`verification-metadata.xml`):** Die neuen Bibliotheken (Shizuku api/provider/aidl/shared, Dhizuku-API,
HiddenApiBypass) brauchen SHA-256-Einträge in `gradle/verification-metadata.xml`. Die trägt Gradle selbst ein:
`tools/sync-verification-metadata.sh` (Linux) bzw. unter Windows einmalig
```
gradlew.bat --write-verification-metadata sha256 :app:assembleFullDefaultRelease :privileged-extension:assembleRelease
```
`build-ubuntu.sh` ruft das automatisch auf, solange die Einträge fehlen. Die Hashes werden beim ersten Download berechnet
(Vertrauen beim ersten Mal). Prüfe danach `git diff gradle/verification-metadata.xml`, es sollten nur neue Komponenten dazukommen.

Logs: `adb logcat | findstr /i "PrivilegedService PrivilegedInstaller RootInstaller"`

## Bekannte Grenzen

- **Deinstallieren** läuft weiter über den normalen Systemdialog. Umgebaut wurde nur das Installieren.
- Root-Installation setzt F-Droid als Installer-Paket (`pm install -i`), dadurch klappen spätere Updates sauber.

## Bauen unter Ubuntu (Skript)

```
chmod +x build-ubuntu.sh
./build-ubuntu.sh
```

Das Skript installiert JDK 21 (apt), lädt die Android Command-line-Tools nach `~/Android/Sdk`, akzeptiert die Lizenzen,
erzeugt beim ersten Lauf einen eigenen Schlüssel (`my-fdroid.jks` + `keystore.properties`), baut Extension und F-Droid
und legt alles in `dist/` ab (APKs und das fertige Magisk-ZIP). Optionen: `--clean`, `SKIP_APT=1`.
Empfohlen: mindestens 8 GB RAM und etwa 10 GB freier Speicher.

## Installationsmethoden: Stand jetzt

Standard, Privileged Extension, Root, **Shizuku**, **Dhizuku** und **microG**. Ist die gewählte Methode beim Installieren
nicht nutzbar, fällt F-Droid auf den Standard-Installer zurück.

| Methode | Voraussetzung | Wie es funktioniert |
|---|---|---|
| Shizuku | Shizuku-App läuft (WLAN-ADB oder Root); beim Auswählen fragt Shizuku nach Zugriff für F-Droid | Der System-PackageInstaller wird mit dem Shizuku-Binder (shell/root) aufgerufen, wie im Shizuku-Installer von Aurora Store |
| Dhizuku | Dhizuku als Geräteadministrator eingerichtet; beim Auswählen fragt Dhizuku nach Zugriff | Der PackageInstaller wird mit dem Dhizuku-Binder aufgerufen, also als Device Owner. Als Installer wird das Dhizuku-Paket eingetragen |
| microG | microG Companion (`com.android.vending`, ab Version 84022620, mit `AppInstallActivity`) | Wie Aurora Store 4.8.4: Intent `org.microg.vending.action.INSTALL_PACKAGE` an microG, gestartet über eine unsichtbare Activity |

**microG:** microG meldet kein Ergebnis zurück. F-Droid prüft deshalb nach dem Schließen von microGs Installer, ob das Paket
wirklich neu installiert wurde. Ob dabei noch ein microG-Dialog erscheint, entscheidet microG selbst. Android kann das Starten
der Activity aus dem Hintergrund blockieren, dann F-Droid geöffnet lassen.

**Shizuku und Dhizuku** greifen per Reflection (mit `org.lsposed.hiddenapibypass`) auf versteckte Android-Schnittstellen zu.
Das ist bewusst ohne Stub-Module gelöst, kann aber bei einer neuen Android-Version brechen.

**Abhängigkeitsprüfung:** Die neuen Bibliotheken (Shizuku, Dhizuku, HiddenApiBypass) stehen nicht in
`gradle/verification-metadata.xml`. `build-ubuntu.sh` schaltet die Prüfung ab. Unter Windows an jeden `gradlew.bat`-Aufruf
`--dependency-verification=off` anhängen.

Logs: `adb logcat | grep -i "BinderWrapperInstaller\|ShizukuInstaller\|DhizukuInstaller\|MicroGInstaller"`

**Andere Installer-App:** Das Textfeld für den Paketnamen erscheint, sobald die Methode gewählt ist. Beim Auswählen und bei
jeder Änderung des Namens prüft F-Droid, ob die App installiert ist und APK-Dateien annimmt. Ist das nicht so, wird bis zur
Korrektur der Standard-Installer verwendet (die Auswahl bleibt bestehen, damit du den Namen korrigieren kannst). Wie bei microG
gibt es kein Ergebnis von der anderen App: F-Droid wartet bis zu 5 Minuten, ob das Paket neu installiert wird. Bricht du dort
ab, endet das erst nach dieser Zeit mit einer Fehlermeldung.

## Übersetzungen der neuen Optionen

Alle Texte der Installationsmethoden und des Feldes "Paketname der Installer-App" (37 Strings) sind in 82 Sprachordnern
vorhanden. Android wählt automatisch nach der Systemsprache: deutsches Android zeigt Deutsch, russisches Russisch usw.

- Sehr gut abgedeckt (hohe Sicherheit): Deutsch, Englisch, Französisch, Spanisch, Italienisch, Portugiesisch (PT/BR), Russisch,
  Ukrainisch, Polnisch, Niederländisch, Türkisch, Japanisch, Koreanisch, Chinesisch (vereinfacht/traditionell), Arabisch,
  Tschechisch, Schwedisch, Dänisch, Finnisch, Norwegisch, Ungarisch, Rumänisch, Griechisch, Bulgarisch u. a. große Sprachen.
- Kleinere Sprachen (z. B. Armenisch, Georgisch, Kasachisch, Mongolisch, Singhalesisch, Telugu, Luxemburgisch, Asturisch,
  Okzitanisch, Walisisch, Irisch, Esperanto) sind sorgfältig übersetzt, sollten aber von Muttersprachlern gegengelesen werden.
- **Nicht übersetzt** (fallen automatisch auf Englisch zurück): Berberisch (ber, tzm), Tibetisch, Bretonisch, Brahui, Sorani (ckb),
  Schottisch-Gälisch, Kabylisch, Kurmandschi, Birmanisch, Nahuatl, Odia, Pashto, Pandschabi (Pakistan), Sardisch, Shona, Somali,
  Toki Pona, Tatarisch, Uigurisch. Hier wollte ich lieber Englisch als schlechte Übersetzungen.

Die Übersetzungen liegen als Textdatei in `tools/i18n/install_method_translations.txt` (je Sprache 32 Zeilen).
Zum Korrigieren oder Ergänzen einer Sprache: Block anpassen bzw. anhängen und
`python3 tools/apply_install_method_translations.py` ausführen (überschreibt vorhandene Einträge nicht; zum Ändern eines
bereits eingetragenen Textes die Zeile direkt in `app/src/main/res/values-xx/strings.xml` ändern).
